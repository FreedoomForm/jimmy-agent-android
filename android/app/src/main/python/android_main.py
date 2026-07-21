"""
Chaquopy entry point for the JimmyAgent APK.
Поднимает ТОЛЬКО proxy.py (ChatJimmy → OpenAI API на 127.0.0.1:4100).
UI приложения — нативный (Java), Codex запускается Java-процессами.
"""

import threading
from http.server import HTTPServer


def main(files_dir):
    import os
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import proxy as P

    P.setup_logging(os.path.join(files_dir, "proxy.log"), enable_log=False)
    srv = HTTPServer(("127.0.0.1", 4100), P.ProxyHandler)
    threading.Thread(target=srv.serve_forever, daemon=True, name="jimmy-proxy").start()
    print("[android_main] jimmy-proxy на 127.0.0.1:4100 ✔", flush=True)

    import time
    while True:
        time.sleep(3600)
