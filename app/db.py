# -*- coding: utf-8 -*-
"""数据库层：SQLite 连接与建表。"""
import os
import sqlite3

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 测试可用 TASKCHAIN_DB 指向临时库，避免触碰生产数据
DB_PATH = os.environ.get("TASKCHAIN_DB") or os.path.join(BASE_DIR, "data.db")
UPLOAD_DIR = os.path.join(BASE_DIR, "uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)

SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  name TEXT NOT NULL,
  is_admin INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS sessions(
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS files(
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  mime TEXT,
  size INTEGER,
  path TEXT NOT NULL,
  uploader_id INTEGER,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS chains(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  creator_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT DEFAULT (datetime('now','localtime')),
  terminated_at TEXT,
  terminate_reason TEXT
);
CREATE TABLE IF NOT EXISTS nodes(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chain_id INTEGER NOT NULL,
  seq INTEGER NOT NULL,
  title TEXT NOT NULL,
  content TEXT NOT NULL DEFAULT '',
  criteria TEXT NOT NULL DEFAULT '',
  deadline TEXT,
  assignee_id INTEGER NOT NULL,
  creator_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'in_progress',
  created_at TEXT DEFAULT (datetime('now','localtime')),
  approved_at TEXT
);
CREATE TABLE IF NOT EXISTS prereqs(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  node_id INTEGER NOT NULL,
  type TEXT NOT NULL,
  ref_node_id INTEGER,
  device_id INTEGER,
  penalty_text TEXT NOT NULL DEFAULT '',
  penalty_url TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS node_files(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  node_id INTEGER NOT NULL,
  file_id TEXT NOT NULL,
  name TEXT
);
CREATE TABLE IF NOT EXISTS submissions(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  node_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS submission_files(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  submission_id INTEGER NOT NULL,
  file_id TEXT NOT NULL,
  name TEXT
);
CREATE TABLE IF NOT EXISTS messages(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  node_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  kind TEXT NOT NULL,
  reply_to INTEGER,
  text TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'open',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chain_id INTEGER,
  node_id INTEGER,
  actor_id INTEGER,
  type TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS devices(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  code TEXT UNIQUE,
  description TEXT NOT NULL DEFAULT '',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS device_custody(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id INTEGER NOT NULL,
  holder_id INTEGER NOT NULL,
  node_id INTEGER,
  taken_at TEXT DEFAULT (datetime('now','localtime')),
  returned_at TEXT
);
CREATE TABLE IF NOT EXISTS terminations(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chain_id INTEGER NOT NULL,
  applicant_id INTEGER NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  created_at TEXT DEFAULT (datetime('now','localtime')),
  decided_at TEXT,
  decided_by INTEGER
);
"""


def get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db():
    conn = get_db()
    conn.executescript(SCHEMA)
    conn.commit()
    conn.close()
