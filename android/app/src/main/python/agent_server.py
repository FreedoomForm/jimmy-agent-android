#!/usr/bin/env python3
"""
JimmyAgent — веб-сайт, где ChatJimmy (через jimmy-proxy) работает как АГЕНТ
в облачной песочнице: выполняет shell-команды, создаёт и читает файлы.

Архитектура:
    Браузер  →  agent_server.py (этот файл, порт 8000)
                    │  агентский цикл: model ⇄ tools
                    ▼
                proxy.py (порт 4100, OpenAI-совместимый)
                    ▼
                chatjimmy.ai  (Llama 3.1 8B на кремнии Taalas, ~17K tok/s)

Песочница: каталог ./sandbox — все файловые операции агента ограничены им.

Запуск:
    python3 agent_server.py [--port 8000] [--proxy-port 4100]
Открыть:
    http://localhost:8000
"""

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

import requests

# На Android нет bash — берём то, что есть (toybox sh)
SHELL_BIN = shutil.which("bash") or shutil.which("sh") or "/system/bin/sh"

BASE_DIR = Path(__file__).resolve().parent
SANDBOX_DIR = BASE_DIR / "sandbox"
STATIC_DIR = BASE_DIR / "static"
PROXY_SCRIPT = BASE_DIR / "proxy.py"

try:
    SANDBOX_DIR.mkdir(exist_ok=True)
except OSError:
    pass  # на Android каталог может быть read-only — путь переопределит android_main

# ---------------------------------------------------------------------------
# Системный промпт агента (компактный: у ChatJimmy лимит ~30K символов)
# ---------------------------------------------------------------------------
SYSTEM_PROMPT = """You are JimmyAgent, an autonomous AI agent running inside a cloud Linux sandbox.
You have tools to run shell commands and manage files. Your current working directory
is the sandbox workspace — every file you create lives there and the user can see it.

Reasoning protocol (MANDATORY):
- Before EVERY response — both before tool calls and before final answers — first think
  inside <think>...</think>, then act. Always close the tag. Output the <think> block FIRST.
- Inside <think> you MUST think like the Grug Brained Developer: telegraphic caveman inner
  monologue — drop articles and grammar polish, lowercase, very short sentences, refer to
  yourself as "grug". Plan the next action concretely (which tool, which arguments).
  Sneer at complexity ("complexity demon bad"), prefer the simplest club... tool for the job.
  A good <think> looks exactly like this:
  <think>grug look at task. user want poem file. grug use write_file one time, full poem
  inside, no complexity demon today. then list_files, show user. simple good. then grug
  tell user done, normal words not grug words.</think>
  Or when debugging:
  <think>hmm command fail. grug read error. syntax wrong, grug fix whole file with
  write_file, one club hit. no give up. grug calm, not reach for club.</think>
- Keep the thinking short (2-6 telegraphic lines). NEVER write grug-speak outside <think>.
- The FINAL answer to the user is NOT grug style: write it normally, politely, in the
  user's language.

How to work:
- Plan briefly, then act with tools ONE step at a time. Check each result before continuing.
- HARD RULE: NEVER call the same tool on the same target twice in one response.
  write_file REPLACES the whole file — a second call to the same path DESTROYS the
  first write. Duplicate calls are skipped by the runtime. Combine everything into
  ONE call per file with the FULL content.
- For DEPENDENT actions (when the next step needs the previous result), act one
  step at a time: one tool call, then look at the result, then continue.
- Prefer simple, reliable commands. Use bash. Quote paths that contain spaces.
- If the SAME action fails twice, STOP repeating it: change approach completely
  (simpler code, different tool) or explain to the user what blocks you.
- If a command or script FAILS: read the error, FIX the cause (usually rewrite the whole
  file with write_file) and RETRY. Never give up after the first error and never
  just describe a failure to the user.
- To save command output to a file use: command > file.txt
- python3 is already installed. Never use apt, pip or package installers unless the user asks.
- Install nothing system-wide; if a tool is missing, work around it or tell the user.
- Never invent tool results — always read the real output.
- When the task is complete (or impossible), answer WITHOUT tool calls: a short summary
  (max 4 sentences) of what you did and which files hold the results.
- Reply in the same language the user writes in.
"""

# ---------------------------------------------------------------------------
# Инструменты (OpenAI-формат; прокси превратит их в промпт для Llama)
# ---------------------------------------------------------------------------
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "run_shell",
            "description": "Run a bash command in the sandbox working directory and return stdout+stderr.",
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "The bash command to run."}
                },
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Write text content to a file inside the sandbox, creating parent folders.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Relative file path inside the sandbox."},
                    "content": {"type": "string", "description": "Full text content to write."},
                },
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the text content of a file inside the sandbox.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Relative file path inside the sandbox."}
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List files and folders inside the sandbox as a tree.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Relative folder path, default is root."}
                },
                "required": [],
            },
        },
    },
]

MAX_STEPS = 12
CMD_TIMEOUT = 60
MAX_OUTPUT_CHARS = 6000

# Примитивная защита от самых разрушительных команд (VM и так изолирована,
# но пусть модель даже не пробует).
BLOCKED_PATTERNS = [
    r"rm\s+-[a-z]*r[a-z]*f[a-z]*\s+/\s*$",
    r"rm\s+-[a-z]*r[a-z]*f[a-z]*\s+/\*",
    r"rm\s+-[a-z]*r[a-z]*f[a-z]*\s+~",
    r"\bmkfs\b", r"\bwipefs\b", r"\bdd\b.*\bof=/dev/",
    r"\bshutdown\b", r"\breboot\b", r"\bhalt\b", r"\bpoweroff\b",
    r":\(\)\s*\{\s*:\|:&\s*\}",  # fork-бомба
    r"\bkill\b.*\s-9\s+1\b",
]

HISTORY: list = []          # диалог (без system)
HISTORY_LOCK = threading.Lock()
RUN_LOCK = threading.Lock()  # один агент-запуск одновременно

PROXY_BASE = "http://127.0.0.1:4100"

# ---------------------------------------------------------------------------
# Песочница: ограничение путей
# ---------------------------------------------------------------------------

def safe_join(rel: str) -> Path:
    """Преобразует относительный путь в абсолютный ВНУТРИ песочницы."""
    rel = (rel or ".").strip() or "."
    if rel.startswith("/"):
        rel = rel.lstrip("/")
    p = (SANDBOX_DIR / rel).resolve()
    root = SANDBOX_DIR.resolve()
    if p != root and root not in p.parents:
        raise ValueError(f"путь «{rel}» выходит за пределы песочницы")
    return p


def sandbox_tree(sub: str = ".", max_entries: int = 300) -> str:
    root = safe_join(sub)
    if not root.exists():
        raise ValueError(f"«{sub}» не существует")
    if root.is_file():
        return root.name
    lines = []
    count = 0
    base_depth = len(root.parts)
    for dirpath, dirnames, filenames in os.walk(root):
        depth = len(Path(dirpath).parts) - base_depth
        if depth > 5:
            dirnames[:] = []
            continue
        dirnames[:] = sorted([d for d in dirnames if d not in ("__pycache__", ".git")])
        filenames = sorted(filenames)
        indent = "  " * depth
        if depth == 0:
            lines.append(".")
        rel_dir = Path(dirpath).relative_to(root) if depth else Path("")
        for fn in filenames:
            if count >= max_entries:
                lines.append(f"{indent}  … (показано {max_entries} элементов)")
                return "\n".join(lines)
            fp = Path(dirpath) / fn
            size = fp.stat().st_size
            lines.append(f"{indent}  📄 {rel_dir / fn if str(rel_dir) != '.' else fn} ({size} B)")
            count += 1
        for d in dirnames:
            pass  # каталоги появятся через walk
        # показать подкаталоги явно
        if depth < 5:
            for d in dirnames:
                count += 1
                if count > max_entries:
                    break
                lines.append(f"{indent}  📁 {(rel_dir / d) if str(rel_dir) != '.' else d}/")
    if len(lines) == 1:
        lines.append("  (пусто)")
    return "\n".join(lines)


def sandbox_entries(max_entries: int = 500):
    """Плоский список файлов/каталогов песочницы для фронтенда."""
    entries = []
    root = SANDBOX_DIR.resolve()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted([d for d in dirnames if d not in ("__pycache__", ".git")])
        rel_dir = Path(dirpath).relative_to(root)
        if len(rel_dir.parts) > 5:
            dirnames[:] = []
            continue
        for d in dirnames:
            entries.append({"path": str(rel_dir / d) if str(rel_dir) != "." else d, "type": "dir"})
        for fn in sorted(filenames):
            fp = Path(dirpath) / fn
            rel = str(rel_dir / fn) if str(rel_dir) != "." else fn
            entries.append({"path": rel, "type": "file", "size": fp.stat().st_size})
        if len(entries) >= max_entries:
            break
    entries.sort(key=lambda e: e["path"])
    return entries[:max_entries]


# ---------------------------------------------------------------------------
# Выполнение инструментов
# ---------------------------------------------------------------------------

def exec_run_shell(command: str) -> str:
    for pat in BLOCKED_PATTERNS:
        if re.search(pat, command):
            return f"ОШИБКА: команда заблокирована политикой безопасности ({pat})"
    # если модель просит python3, а доступен только текущий интерпретатор — подставим его
    if command.startswith(("python3 ", "python ")) and sys.executable and os.access(sys.executable, os.X_OK):
        command = sys.executable + command[command.index(" "):]
    try:
        proc = subprocess.run(
            [SHELL_BIN, "-c", command],
            cwd=SANDBOX_DIR,
            capture_output=True,
            text=True,
            timeout=CMD_TIMEOUT,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        out = out.strip() or f"(команда завершилась, код {proc.returncode}, вывода нет)"
        if proc.returncode != 0:
            out = f"[exit {proc.returncode}] " + out
    except subprocess.TimeoutExpired:
        out = f"ОШИБКА: таймаут {CMD_TIMEOUT}с — команда убита"
    except Exception as e:
        out = f"ОШИБКА: {e}"
    if len(out) > MAX_OUTPUT_CHARS:
        out = out[:MAX_OUTPUT_CHARS] + f"\n… (обрезано, всего {len(out)} символов)"
    return out


def exec_write_file(path: str, content: str) -> str:
    p = safe_join(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content or "", encoding="utf-8")
    return f"OK: записано {len(content or '')} символов в {p.relative_to(SANDBOX_DIR)}"


def exec_read_file(path: str) -> str:
    p = safe_join(path)
    if not p.exists():
        return f"ОШИБКА: файл «{path}» не найден"
    if p.is_dir():
        return f"ОШИБКА: «{path}» — это каталог, используй list_files"
    data = p.read_text(encoding="utf-8", errors="replace")
    if len(data) > MAX_OUTPUT_CHARS:
        data = data[:MAX_OUTPUT_CHARS] + f"\n… (обрезано, всего {len(data)} символов)"
    return data or "(файл пуст)"


def execute_tool(name: str, args: dict) -> str:
    try:
        if name == "run_shell":
            return exec_run_shell(str(args.get("command", "")))
        if name == "write_file":
            return exec_write_file(str(args.get("path", "")), str(args.get("content", "")))
        if name == "read_file":
            return exec_read_file(str(args.get("path", "")))
        if name == "list_files":
            return sandbox_tree(str(args.get("path", ".")))
        return f"ОШИБКА: неизвестный инструмент «{name}». Доступны: run_shell, write_file, read_file, list_files."
    except ValueError as e:
        return f"ОШИБКА пути: {e}"
    except Exception as e:
        return f"ОШИБКА выполнения {name}: {e}"


# ---------------------------------------------------------------------------
# История: обрезка, чтобы не раздувать контекст маленькой модели
# ---------------------------------------------------------------------------

def trim_history(msgs, max_chars=35000, max_msgs=40):
    out = list(msgs[-max_msgs:])
    while out and out[0]["role"] == "tool":
        out.pop(0)
    while (
        out
        and out[0]["role"] == "assistant"
        and out[0].get("tool_calls")
        and (len(out) == 1 or out[1].get("role") != "tool")
    ):
        out.pop(0)

    def size(ms):
        return sum(len(str(m.get("content") or "")) for m in ms)

    while size(out) > max_chars and len(out) > 2:
        first = out.pop(0)
        if first.get("role") == "assistant" and first.get("tool_calls"):
            while out and out[0]["role"] == "tool":
                out.pop(0)
        while out and out[0]["role"] == "tool":
            out.pop(0)
    return out


def call_llm(msgs):
    payload = {
        "model": "llama3.1-8B",
        "messages": [{"role": "system", "content": SYSTEM_PROMPT}] + msgs,
        "tools": TOOLS,
        "stream": False,
    }
    r = requests.post(f"{PROXY_BASE}/v1/chat/completions", json=payload, timeout=180)
    r.raise_for_status()
    return r.json()


# ---------------------------------------------------------------------------
# Спасательный парсер: иногда модель шлёт <tool_call> с битым JSON
# (например \$), и прокси его отбрасывает — подхватим сами.
# ---------------------------------------------------------------------------

def extract_thinking(content: str):
    """Вырезает <think>…</think> блоки. Возвращает (мысли, остальной текст)."""
    if not content:
        return "", ""
    blocks = re.findall(r"<think>(.*?)</think>", content, re.DOTALL)
    # незакрытый тег в конце — тоже считаем мыслью
    tail = re.search(r"<think>(?!.*</think>)(.*)$", content, re.DOTALL)
    if tail:
        blocks.append(tail.group(1))
    rest = re.sub(r"<think>.*?</think>", "", content, flags=re.DOTALL)
    rest = re.sub(r"<think>.*$", "", rest, flags=re.DOTALL).strip()
    thoughts = "\n".join(b.strip() for b in blocks if b.strip())
    return thoughts, rest

def extract_stray_tool_calls(content: str):
    pattern = re.compile(r"<tool_call>\s*(.*?)\s*</tool_call>", re.DOTALL)
    calls = []
    for raw in pattern.findall(content or ""):
        call = None
        try:
            call = json.loads(raw)
        except json.JSONDecodeError:
            fixed = re.sub(r'\\([^"\\/bfnrtu])', r"\1", raw)  # \$ -> $ и т.п.
            try:
                call = json.loads(fixed)
            except json.JSONDecodeError:
                continue
        items = call if isinstance(call, list) else [call]
        for it in items:
            if not isinstance(it, dict):
                continue
            name = it.get("name") or it.get("tool") or (it.get("function") or {}).get("name")
            args = (
                it.get("arguments")
                or it.get("parameters")
                or it.get("args")
                or (it.get("function") or {}).get("arguments")
            )
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except json.JSONDecodeError:
                    args = {}
            if name:
                calls.append((name, args if isinstance(args, dict) else {}))
    clean = pattern.sub("", content or "").strip()
    return clean, calls


def clean_final_text(t: str) -> str:
    """Убирает служебные теги, которые модель иногда эхом копирует в финальный ответ."""
    if not t:
        return t
    t = re.sub(r"</?tool_result>", "", t)
    t = re.sub(r"</?tool_call>", "", t)
    t = re.sub(r"</?think>", "", t)
    return t.strip()


# ---------------------------------------------------------------------------
# Агентский цикл
# ---------------------------------------------------------------------------

def agent_run(user_message: str, emit):
    """emit(event_dict) — отправляет событие клиенту; False = клиент отключился."""
    with HISTORY_LOCK:
        HISTORY.append({"role": "user", "content": user_message})
        msgs = trim_history(HISTORY)

    loop_guard_counts = {}  # (имя, аргументы) -> сколько раз вызывалось в этом запуске

    for step in range(1, MAX_STEPS + 1):
        if not emit({"type": "status", "text": f"Шаг {step}: модель думает…", "step": step}):
            return
        t0 = time.time()
        try:
            data = call_llm(msgs)
        except Exception as e:
            emit({"type": "error", "text": f"Модель недоступна: {e}"})
            return
        elapsed = time.time() - t0
        choice = data["choices"][0]
        msg = choice["message"]
        usage = data.get("usage", {})
        emit({
            "type": "llm",
            "step": step,
            "elapsed": round(elapsed, 2),
            "tokens": usage.get("total_tokens", 0),
        })

        tool_calls = msg.get("tool_calls") or []
        content = msg.get("content") or ""

        # сначала — мысли <think>…</think>: показываем в UI и ОСТАВЛЯЕМ в истории,
        # чтобы модель продолжала следовать протоколу на каждом шаге
        think_text, content = extract_thinking(content)
        if think_text and not emit({"type": "thinking", "step": step, "content": think_text}):
            return
        hist_content = (f"<think>\n{think_text}\n</think>\n" + content) if think_text else content

        # fallback: прокси не распарсил <tool_call> — подхватываем сами
        if (not tool_calls or choice.get("finish_reason") != "tool_calls") and content:
            clean, stray = extract_stray_tool_calls(content)
            if stray:
                tool_calls = [
                    {
                        "id": f"call_{uuid.uuid4().hex[:8]}",
                        "type": "function",
                        "function": {"name": n, "arguments": json.dumps(a, ensure_ascii=False)},
                    }
                    for n, a in stray
                ]
                content = clean

        if tool_calls:
            # flood-cap: не больше 6 вызовов за ход (защита контекста от флуда)
            FLOOD_CAP = 6
            if len(tool_calls) > FLOOD_CAP:
                dropped = len(tool_calls) - FLOOD_CAP
                tool_calls = tool_calls[:FLOOD_CAP]
                if not emit({"type": "notice", "text": f"🌊 модель выдала флуд вызовов — {dropped} отброшено, берём первые {FLOOD_CAP}"}):
                    return
            msgs.append({
                "role": "assistant",
                "content": hist_content or None,
                "tool_calls": tool_calls,
            })
            seen_targets = set()
            for i, tc in enumerate(tool_calls):
                fn = tc.get("function", {})
                name = fn.get("name", "?")
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except json.JSONDecodeError:
                    args = {}
                if not emit({"type": "tool_call", "id": tc["id"], "name": name, "arguments": args, "step": step}):
                    return
                # дисциплина: пропускаем дубли по той же цели в рамках одного хода
                dup_key = None
                if name == "write_file":
                    dup_key = ("w", str(args.get("path", "")).strip())
                elif name == "run_shell":
                    dup_key = ("c", str(args.get("command", "")).strip()[:200])
                if dup_key and dup_key in seen_targets:
                    result = ("SKIPPED: duplicate call on the same target in one turn. "
                              "write_file REPLACES the whole file, so do it ONCE with FULL content.")
                    ok = False
                    skip = True
                else:
                    if dup_key:
                        seen_targets.add(dup_key)
                    # loop-guard: один и тот же вызов (те же аргументы) — максимум 2 раза за задачу
                    call_key = (name, json.dumps(args, sort_keys=True, ensure_ascii=False)[:300])
                    n = loop_guard_counts.get(call_key, 0)
                    if n >= 2:
                        result = ("STOP: you already ran this exact call twice in this task. "
                                  "You are stuck in a loop, grug! DO SOMETHING DIFFERENT: "
                                  "rewrite the file with SIMPLER code, try another approach, "
                                  "or tell the user what blocks you.")
                        ok = False
                        skip = True
                    else:
                        loop_guard_counts[call_key] = n + 1
                        result = execute_tool(name, args)
                        ok = not result.startswith(("ОШИБКА", "[exit"))
                        skip = False
                if not emit({
                    "type": "tool_result",
                    "id": tc["id"],
                    "name": name,
                    "ok": ok,
                    "skipped": skip,
                    "output": result,
                    "step": step,
                    "mutates": (not skip) and name in ("write_file", "run_shell"),
                }):
                    return
                msgs.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "name": name,
                    "content": result[:4000],
                })
        else:
            content = clean_final_text(content) or "(пустой ответ модели)"
            hist_final = (f"<think>\n{think_text}\n</think>\n" + content) if think_text else content
            msgs.append({"role": "assistant", "content": hist_final})
            with HISTORY_LOCK:
                HISTORY.clear()
                HISTORY.extend(msgs)
            emit({"type": "message", "content": content})
            emit({"type": "done", "steps": step})
            return

    # лимит шагов
    with HISTORY_LOCK:
        HISTORY.clear()
        HISTORY.extend(msgs)
    emit({
        "type": "message",
        "content": f"⚠️ Достигнут лимит в {MAX_STEPS} шагов. Уточните задачу или продолжите — я помню контекст.",
    })
    emit({"type": "done", "steps": MAX_STEPS, "limit": True})


# ---------------------------------------------------------------------------
# Управление proxy.py как подпроцессом
# ---------------------------------------------------------------------------
_proxy_proc = None


def start_proxy(port: int):
    global _proxy_proc
    try:
        requests.get(f"{PROXY_BASE}/v1/models", timeout=2)
        print("[proxy] уже запущен")
        return
    except Exception:
        pass
    print(f"[proxy] запускаю proxy.py на порту {port}…")
    _proxy_proc = subprocess.Popen(
        [sys.executable, str(PROXY_SCRIPT), "--port", str(port)],
        cwd=str(BASE_DIR),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for _ in range(50):
        try:
            requests.get(f"{PROXY_BASE}/v1/models", timeout=2)
            print("[proxy] готов ✔")
            return
        except Exception:
            time.sleep(0.2)
    print("[proxy] ВНИМАНИЕ: не дождался запуска прокси")


def stop_proxy():
    global _proxy_proc
    if _proxy_proc and _proxy_proc.poll() is None:
        _proxy_proc.send_signal(signal.SIGTERM)


# ---------------------------------------------------------------------------
# HTTP-сервер
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    # ---------- helpers ----------
    def _send_json(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _send_file(self, path: Path, ctype: str):
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _sse_headers(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

    def _sse_send(self, obj) -> bool:
        try:
            self.wfile.write(f"data: {json.dumps(obj, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()
            return True
        except (BrokenPipeError, ConnectionResetError):
            return False

    # ---------- routing ----------
    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path in ("/", "/index.html"):
            self._send_file(STATIC_DIR / "index.html", "text/html; charset=utf-8")
            return

        # PWA-обвязка
        if path == "/manifest.webmanifest":
            self._send_file(STATIC_DIR / "manifest.webmanifest", "application/manifest+json")
            return
        if path == "/sw.js":
            self._send_file(STATIC_DIR / "sw.js", "text/javascript")
            return
        if path in ("/icon-192.png", "/icon-512.png"):
            self._send_file(STATIC_DIR / path.lstrip("/"), "image/png")
            return
        if path == "/jimmy-agent.zip":
            zpath = BASE_DIR.parent / "jimmy-agent.zip"
            if zpath.exists():
                self._send_file(zpath, "application/zip")
            else:
                self._send_json(404, {"error": "архив ещё не собран"})
            return

        if path == "/api/health":
            try:
                r = requests.get(f"{PROXY_BASE}/v1/models", timeout=5)
                models = [m["id"] for m in r.json().get("data", [])]
                self._send_json(200, {"ok": True, "models": models})
            except Exception as e:
                self._send_json(503, {"ok": False, "error": str(e)})
            return

        if path == "/api/files":
            try:
                self._send_json(200, {"ok": True, "tree": sandbox_tree("."), "entries": sandbox_entries()})
            except Exception as e:
                self._send_json(500, {"ok": False, "error": str(e)})
            return

        if path == "/api/file":
            qs = parse_qs(parsed.query)
            rel = qs.get("path", [""])[0]
            try:
                p = safe_join(rel)
                if not p.exists() or p.is_dir():
                    self._send_json(404, {"ok": False, "error": "файл не найден"})
                    return
                data = p.read_text(encoding="utf-8", errors="replace")
                self._send_json(200, {"ok": True, "path": rel, "content": data[:50000]})
            except ValueError as e:
                self._send_json(403, {"ok": False, "error": str(e)})
            except Exception as e:
                self._send_json(500, {"ok": False, "error": str(e)})
            return

        self._send_json(404, {"error": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self._send_json(400, {"error": "invalid JSON"})
            return

        if path == "/api/reset":
            with HISTORY_LOCK:
                HISTORY.clear()
            self._send_json(200, {"ok": True})
            return

        if path == "/api/chat":
            message = (body.get("message") or "").strip()
            if not message:
                self._send_json(400, {"error": "пустое сообщение"})
                return
            if not RUN_LOCK.acquire(blocking=False):
                self._send_json(409, {"error": "Агент занят — дождитесь завершения задачи или нажмите «Сброс»."})
                return
            try:
                self._sse_headers()
                self._sse_send({"type": "run_start", "task": message})
                agent_run(message, self._sse_send)
            except Exception as e:
                self._sse_send({"type": "error", "text": str(e)})
            finally:
                RUN_LOCK.release()
            return

        self._send_json(404, {"error": "not found"})


def main():
    global PROXY_BASE
    ap = argparse.ArgumentParser(description="JimmyAgent — сайт-агент поверх ChatJimmy")
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8000")))
    ap.add_argument("--proxy-port", type=int, default=4100)
    ap.add_argument("--external-proxy", action="store_true",
                    help="не запускать proxy.py (использовать уже работающий)")
    args = ap.parse_args()

    PROXY_BASE = f"http://127.0.0.1:{args.proxy_port}"
    if not args.external_proxy:
        start_proxy(args.proxy_port)

    print(f"[jimmy-agent] сайт: http://localhost:{args.port}")
    print(f"[jimmy-agent] песочница: {SANDBOX_DIR}")
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nостанавливаю…")
    finally:
        stop_proxy()
        server.shutdown()


if __name__ == "__main__":
    main()
