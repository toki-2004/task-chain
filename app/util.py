# -*- coding: utf-8 -*-
"""通用工具：密码哈希、会话、事件记录。

哈希格式 v2：pbkdf2$<iterations>$<salt>$<hash>（当前 600000 次）
兼容 v1 旧格式：<salt>$<hash>（60000 次），登录成功时自动升级到 v2。
"""
import hashlib
import json
import secrets
import sqlite3

from .db import get_db

PBKDF2_ITERATIONS = 600_000


def hash_password(password: str, salt: str = None, iterations: int = PBKDF2_ITERATIONS) -> str:
    salt = salt or secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode(), iterations)
    return f"pbkdf2${iterations}${salt}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    parts = (stored or "").split("$")
    try:
        if parts[0] == "pbkdf2" and len(parts) == 4:
            iterations, salt, expected = int(parts[1]), parts[2], parts[3]
            dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode(), iterations)
            return secrets.compare_digest(dk.hex(), expected)
        if len(parts) == 2:  # v1 旧格式
            salt, expected = parts
            dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode(), 60000)
            return secrets.compare_digest(dk.hex(), expected)
    except (ValueError, TypeError):
        return False
    return False


def needs_rehash(stored: str) -> bool:
    return not (stored or "").startswith("pbkdf2$")


def new_session(user_id: int) -> str:
    token = secrets.token_urlsafe(24)
    db = get_db()
    db.execute("INSERT INTO sessions(token, user_id) VALUES(?,?)", (token, user_id))
    db.commit()
    db.close()
    return token


def drop_session(token: str):
    db = get_db()
    db.execute("DELETE FROM sessions WHERE token=?", (token,))
    db.commit()
    db.close()


def log_event(db: sqlite3.Connection, chain_id, node_id, actor_id, etype: str, detail=None):
    db.execute(
        "INSERT INTO events(chain_id, node_id, actor_id, type, detail) VALUES(?,?,?,?,?)",
        (chain_id, node_id, actor_id, etype, json.dumps(detail or {}, ensure_ascii=False)),
    )
