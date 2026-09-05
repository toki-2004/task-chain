# -*- coding: utf-8 -*-
"""通用工具：密码哈希、会话、事件记录。"""
import hashlib
import json
import os
import secrets
import sqlite3

from .db import get_db


def hash_password(password: str, salt: str = None) -> str:
    salt = salt or secrets.token_hex(8)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt.encode(), 60000)
    return f"{salt}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        salt, _ = stored.split("$", 1)
    except ValueError:
        return False
    return hash_password(password, salt) == stored


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
