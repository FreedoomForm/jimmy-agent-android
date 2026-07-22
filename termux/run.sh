#!/usr/bin/env bash
# Запускается ВНУТРИ Ubuntu (proot) на телефоне.
# Поднимает jimmy-proxy (ChatJimmy) и стартует Codex CLI на нём.
set -e

# 1. прокси ChatJimmy → http://127.0.0.1:4100/v1
if ! curl -s --max-time 2 http://127.0.0.1:4100/v1/models >/dev/null 2>&1; then
  echo "⚡ поднимаю jimmy-proxy…"
  nohup python3 /root/jimmy-codex/proxy.py --port 4100 >/root/proxy.log 2>&1 &
  for i in $(seq 1 20); do
    curl -s --max-time 2 http://127.0.0.1:4100/v1/models >/dev/null 2>&1 && break
    sleep 0.5
  done
fi

# 2. AGENTS.md с grug-протоколом — в корень проекта
cp -f /root/jimmy-codex/AGENTS.md /root/AGENTS.md
cp -f /root/jimmy-codex/SYSTEM.md /root/SYSTEM.md
cd /root

# 3. Codex
export CHATJIMMY_API_KEY=dummy
export SHELL="${SHELL:-/bin/bash}"
echo "🧠 Codex CLI (model: llama3.1-8B via ChatJimmy, ~17K tok/s)"
echo "   совет: короткие конкретные задачи работают лучше всего"
exec codex
