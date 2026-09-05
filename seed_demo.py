# -*- coding: utf-8 -*-
"""可选：写入演示用户与设备，方便首次体验。

用法: python seed_demo.py
账号: zhangsan / lisi / wangwu，密码均为 123456
设备: 无人机-01（UAV-001）、内窥镜-02（ENDO-002）
"""
import sys

from app.db import get_db, init_db
from app.util import hash_password


def main():
    init_db()
    db = get_db()
    if not db.execute("SELECT 1 FROM users WHERE username='admin'").fetchone():
        db.execute("INSERT INTO users(username, password_hash, name, is_admin) VALUES(?,?,?,1)",
                   ("admin", hash_password("admin123"), "管理员"))
        print("[+] user admin/admin123 (default admin)")
    users = [("zhangsan", "张三"), ("lisi", "李四"), ("wangwu", "王五")]
    for username, name in users:
        if not db.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone():
            db.execute("INSERT INTO users(username, password_hash, name) VALUES(?,?,?)",
                       (username, hash_password("123456"), name))
            print(f"[+] user {username}/{name} (password: 123456)")
    devices = [("无人机-01", "UAV-001", "四旋翼巡检无人机"), ("内窥镜-02", "ENDO-002", "工业内窥镜")]
    for name, code, desc in devices:
        if not db.execute("SELECT 1 FROM devices WHERE code=?", (code,)).fetchone():
            db.execute("INSERT INTO devices(name, code, description) VALUES(?,?,?)", (name, code, desc))
            print(f"[+] device {name} ({code})")
    db.commit()
    db.close()
    if not users or not devices:
        pass
    print("done.")
    if len(sys.argv) > 1:
        print("extra args ignored")


if __name__ == "__main__":
    if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
        sys.stdout.reconfigure(encoding="utf-8")
    main()
