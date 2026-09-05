# -*- coding: utf-8 -*-
"""协同任务链 · 固定入口服务器（部署在 frps 所在的 VPS 上）

作用：APK 固定入口填 http://<VPS_IP>:9300/config.json —— 无论本机 frp 隧道的
远程端口/地址怎么换，后台「APK地址 → 立即推送」都会把最新地址写到这里，
APK 任何时刻来问都能拿到当前可用地址。

部署（VPS 上）：
  1. 上传本文件，执行: python3 entry_server.py
     （或 ENTRY_TOKEN=你的密钥 PORT=9300 python3 entry_server.py）
     首次运行会打印随机 token，把它填进后台「APK地址 → 固定入口」的推送密钥里
  2. VPS 防火墙/安全组放行该 TCP 端口
  3. 建议用 systemd 或 nohup 常驻：
     nohup python3 entry_server.py >/dev/null 2>&1 &

接口：
  GET  /config.json                 -> {"app_server_url": "http://..."}
  POST /update  (X-Token 头)        -> 更新地址（后台推送用），body: {"app_server_url": "..."}

纯 Python 标准库，无任何依赖。
"""
import json
import os
import secrets
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "9300"))
TOKEN = os.environ.get("ENTRY_TOKEN") or secrets.token_hex(8)
CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "entry_config.json")
LOCK = threading.Lock()


def load_addr():
    try:
        with open(CONFIG_FILE, encoding="utf-8") as f:
            return json.load(f).get("app_server_url", "")
    except Exception:
        return ""


def save_addr(url):
    with LOCK:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump({"app_server_url": url,
                       "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")},
                      f, ensure_ascii=False)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/config.json"):
            self._send(200, json.dumps({"app_server_url": load_addr()}, ensure_ascii=False))
        else:
            self._send(404, '{"error":"not found"}')

    def do_POST(self):
        if not self.path.startswith("/update"):
            return self._send(404, '{"error":"not found"}')
        if self.headers.get("X-Token") != TOKEN:
            return self._send(403, '{"error":"bad token"}')
        try:
            n = int(self.headers.get("Content-Length") or 0)
            payload = json.loads(self.rfile.read(n).decode("utf-8"))
            url = str(payload.get("app_server_url", "")).strip()
        except Exception:
            return self._send(400, '{"error":"bad json"}')
        if not url or not url.startswith(("http://", "https://")):
            return self._send(400, '{"error":"bad url"}')
        save_addr(url)
        self._send(200, json.dumps({"ok": True, "app_server_url": url}, ensure_ascii=False))

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    if not os.path.exists(CONFIG_FILE):
        save_addr("")
    print(f"[entry-server] listening on 0.0.0.0:{PORT}")
    print(f"[entry-server] push token: {TOKEN}")
    print(f"[entry-server] APK entry URL: http://<this-server-ip>:{PORT}/config.json")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
