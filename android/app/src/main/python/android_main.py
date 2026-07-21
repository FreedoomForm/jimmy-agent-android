"""
Точка входа для Android APK (Chaquopy).

Поднимает ВЕСЬ стек внутри приложения, без subprocess (на Android нет python-бинаря):
  - proxy.py  → HTTPServer в потоке (127.0.0.1:4100)
  - agent_server → ThreadingHTTPServer (127.0.0.1:8000), UI открывает WebView

Песочница — в приватной папке приложения (filesDir/sandbox).
"""

import os
import sys
import threading
from http.server import HTTPServer


def main(files_dir):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

    import proxy as P
    import agent_server as A

    # прокси в отдельном потоке (без subprocess)
    P.setup_logging("proxy.log", enable_log=False)
    proxy_srv = HTTPServer(("127.0.0.1", 4100), P.ProxyHandler)
    threading.Thread(target=proxy_srv.serve_forever, daemon=True, name="jimmy-proxy").start()
    print("[android] proxy.py запущен на 127.0.0.1:4100", flush=True)

    # агентский сервер
    A.PROXY_BASE = "http://127.0.0.1:4100"
    A.SANDBOX_DIR = A.Path(files_dir) / "sandbox"
    A.SANDBOX_DIR.mkdir(exist_ok=True)
    server = A.ThreadingHTTPServer(("127.0.0.1", 8000), A.Handler)
    print("[android] JimmyAgent готов: http://127.0.0.1:8000", flush=True)
    print(f"[android] песочница: {A.SANDBOX_DIR}", flush=True)
    server.serve_forever()
