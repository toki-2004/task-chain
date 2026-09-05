# -*- coding: utf-8 -*-
"""启动协同任务链服务：打印局域网地址 + 二维码，供手机扫码访问。"""
import socket
import sys

import qrcode


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # 不发包，仅取路由
        ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip


def main():
    port = 8000
    ip = lan_ip()
    url = f"http://{ip}:{port}"
    print()
    print("=" * 46)
    print("  协同任务链 服务已启动")
    print("=" * 46)
    print(f"  本机访问:   http://127.0.0.1:{port}")
    print(f"  局域网访问: {url}   （手机连同一 WiFi）")
    print(f"  默认管理员: admin / admin123  （登录后请改密码）")
    print("  首次运行如 Windows 弹出防火墙提示，请勾选允许")
    print("=" * 46)
    print("  按 Ctrl+C 停止服务")
    print()
    try:
        qr = qrcode.QRCode(border=1)
        qr.add_data(url)
        qr.print_ascii(invert=True)
    except Exception:
        pass
    import uvicorn
    from app.main import app
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")


if __name__ == "__main__":
    if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
        sys.stdout.reconfigure(encoding="utf-8")
    main()
