#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────
#  JimmyAgent × Codex CLI — установка на Android (Termux)
#  Всё локально на телефоне. Cloudflare не нужен.
#
#  Запуск одной строкой:
#  curl -sL https://raw.githubusercontent.com/FreedoomForm/jimmy-agent-android/main/termux/install.sh | bash
# ─────────────────────────────────────────────────────────────
set -e

echo "📦 [1/5] Termux: пакеты…"
pkg update -y
pkg install -y proot-distro curl

echo "🐧 [2/5] Ubuntu-окружение (proot)…"
proot-distro install ubuntu 2>/dev/null || echo "ubuntu уже стоит — ок"

UB=/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/root
BASE=https://raw.githubusercontent.com/FreedoomForm/jimmy-agent-android/main/termux

echo "⬇️  [3/5] файлы агента…"
mkdir -p "$UB/jimmy-codex"
for f in proxy.py config.toml AGENTS.md run.sh; do
  curl -sL "$BASE/$f" -o "$UB/jimmy-codex/$f"
done
chmod +x "$UB/jimmy-codex/run.sh"

echo "🧠 [4/5] Codex CLI + Python внутри Ubuntu (2-4 мин)…"
proot-distro login ubuntu -- bash -lc '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y >/dev/null
  apt-get install -y python3 python3-requests nodejs npm curl >/dev/null
  npm install -g @openai/codex@0.69.0 --no-fund --no-audit >/dev/null
  mkdir -p ~/.codex
  cp /root/jimmy-codex/config.toml ~/.codex/config.toml
'

echo ""
echo "✅ [5/5] Готово! Управление:"
echo "   Запуск агента:  proot-distro login ubuntu -- bash /root/jimmy-codex/run.sh"
echo "   (прокси ChatJimmy поднимется сам на 127.0.0.1:4100, откроется Codex TUI)"
echo ""
echo "📁 Проект Codex: /root (AGENTS.md с grug-протоколом уже там)"
