# -*- coding: utf-8 -*-
"""协同任务链 - 后端 API（FastAPI + SQLite）。

业务规则（与 README《业务规则》一致）：
- 任务链(chain)由多个节点(node)组成；节点 N 审核通过后，其受任人可创建节点 N+1 并指定受任人。
- 节点提交的审核权 = 该节点的创建者；结束申请的审核权 = 链发起人；链发起人自己结束无需审核。
- 前置要求分两类：前置任务（须为其他链的节点，且该节点审核通过后才解锁提交）、前置设备
  （受任人须先在 App 内"领用"设备，提交时设备必须处于"由本任务领用中"状态）。
- 设备占用/释放全部手动：领用、归还；管理员可在后台强制释放。
"""
import mimetypes
import os
import re
import socket
import sqlite3
import threading
import time as _time
import uuid
from contextlib import contextmanager

from fastapi import FastAPI, Depends, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from .db import get_db, init_db, UPLOAD_DIR
from .util import (hash_password, verify_password, needs_rehash, new_session,
                   drop_session, log_event)

app = FastAPI(title="协同任务链")

MIN_PASSWORD_LEN = 8

# 登录防爆破：同 IP+用户名 连续失败 5 次锁 15 分钟（内存态，重启即清）
LOGIN_MAX_FAILS = 5
LOGIN_LOCK_SECONDS = 900
_login_fails = {}

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"}
VIDEO_EXTS = {".mp4", ".mov", ".m4v", ".webm", ".3gp"}
MAX_IMAGE = 20 * 1024 * 1024
MAX_VIDEO = 200 * 1024 * 1024


@contextmanager
def db_ctx():
    db = get_db()
    try:
        yield db
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


# ---------------------------------------------------------------- auth

def current_user(request: Request):
    token = request.cookies.get("sid")
    if not token:
        return None
    db = get_db()
    row = db.execute(
        "SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=?",
        (token,),
    ).fetchone()
    db.close()
    if not row or not row["active"]:
        return None
    return dict(row)


def require_user(request: Request) -> dict:
    user = current_user(request)
    if not user:
        raise HTTPException(401, "未登录或会话已过期")
    return user


def require_admin(request: Request) -> dict:
    user = require_user(request)
    if not user["is_admin"]:
        raise HTTPException(403, "需要管理员权限")
    return user


class LoginBody(BaseModel):
    username: str
    password: str


def _login_lock_key(request: Request, username: str):
    ip = request.client.host if request.client else "?"
    return (ip, username.strip().lower())


def _login_locked(key):
    entry = _login_fails.get(key)
    if not entry:
        return 0
    count, first_ts = entry
    if _time.time() - first_ts > LOGIN_LOCK_SECONDS:
        _login_fails.pop(key, None)
        return 0
    if count >= LOGIN_MAX_FAILS:
        return int(LOGIN_LOCK_SECONDS - (_time.time() - first_ts)) + 1
    return 0


def _login_record_fail(key):
    entry = _login_fails.get(key)
    now = _time.time()
    if entry and now - entry[1] <= LOGIN_LOCK_SECONDS:
        _login_fails[key] = (entry[0] + 1, entry[1])
    else:
        _login_fails[key] = (1, now)


@app.post("/api/login")
def login(body: LoginBody, request: Request):
    key = _login_lock_key(request, body.username)
    remain = _login_locked(key)
    if remain:
        raise HTTPException(429, f"失败次数过多，请 {remain // 60 + 1} 分钟后再试")
    with db_ctx() as db:
        row = db.execute("SELECT * FROM users WHERE username=?", (body.username.strip(),)).fetchone()
    if not row or not verify_password(body.password, row["password_hash"]):
        _login_record_fail(key)
        raise HTTPException(400, "用户名或密码错误")
    if not row["active"]:
        raise HTTPException(403, "该账号已被停用")
    _login_fails.pop(key, None)
    # 旧格式哈希在登录成功时透明升级；顺手清理过期会话
    if needs_rehash(row["password_hash"]):
        with db_ctx() as db:
            db.execute("UPDATE users SET password_hash=? WHERE id=?",
                       (hash_password(body.password), row["id"]))
    with db_ctx() as db:
        db.execute("DELETE FROM sessions WHERE created_at < datetime('now','-30 days')")
    token = new_session(row["id"])
    resp = JSONResponse({"ok": True, "user": {"id": row["id"], "name": row["name"], "is_admin": bool(row["is_admin"])}})
    resp.set_cookie("sid", token, httponly=True, samesite="lax", max_age=60 * 60 * 24 * 30, path="/")
    return resp


class RegisterBody(BaseModel):
    username: str
    name: str
    password: str


@app.post("/api/register")
def register(body: RegisterBody, request: Request):
    username = body.username.strip()
    name = body.name.strip()
    if not re.fullmatch(r"[A-Za-z0-9_]{2,32}", username):
        raise HTTPException(400, "账号只能为 2-32 位字母/数字/下划线")
    if not name:
        raise HTTPException(400, "请填写姓名")
    if len(body.password) < MIN_PASSWORD_LEN:
        raise HTTPException(400, f"密码至少 {MIN_PASSWORD_LEN} 位")
    with db_ctx() as db:
        if db.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone():
            raise HTTPException(400, "该账号已被注册")
        cur = db.execute(
            "INSERT INTO users(username, password_hash, name) VALUES(?,?,?)",
            (username, hash_password(body.password), name),
        )
        uid = cur.lastrowid
    token = new_session(uid)
    resp = JSONResponse({"ok": True, "user": {"id": uid, "name": name, "is_admin": False}})
    resp.set_cookie("sid", token, httponly=True, samesite="lax", max_age=60 * 60 * 24 * 30, path="/")
    return resp


@app.post("/api/logout")
def logout(request: Request):
    token = request.cookies.get("sid")
    if token:
        drop_session(token)
    resp = JSONResponse({"ok": True})
    resp.delete_cookie("sid", path="/")
    return resp


@app.get("/api/me")
def me(request: Request):
    user = require_user(request)
    db = get_db()
    unfinished = db.execute(
        "SELECT COUNT(*) c FROM nodes n JOIN chains c2 ON c2.id=n.chain_id "
        "WHERE n.assignee_id=? AND n.status IN ('in_progress','rejected') AND c2.status='active'",
        (user["id"],),
    ).fetchone()["c"]
    pending = db.execute(
        "SELECT COUNT(*) c FROM nodes WHERE status='pending_review' AND (assignee_id=? OR creator_id=?)",
        (user["id"], user["id"]),
    ).fetchone()["c"]
    term = db.execute(
        "SELECT COUNT(*) c FROM terminations t JOIN chains c2 ON c2.id=t.chain_id "
        "WHERE t.status='pending' AND c2.creator_id=?",
        (user["id"],),
    ).fetchone()["c"]
    feedback = db.execute(
        "SELECT COUNT(*) c FROM messages m JOIN nodes n ON n.id=m.node_id "
        "WHERE n.creator_id=? AND m.user_id<>? AND m.reply_to IS NULL "
        "AND m.kind IN ('feedback','appeal') "
        "AND NOT EXISTS (SELECT 1 FROM messages r WHERE r.reply_to=m.id)",
        (user["id"], user["id"]),
    ).fetchone()["c"]
    db.close()
    return {
        "user": {"id": user["id"], "username": user["username"], "name": user["name"], "is_admin": bool(user["is_admin"])},
        "badges": {"unfinished": unfinished, "pending_review": pending + term, "feedback": feedback},
    }


class PasswordBody(BaseModel):
    old: str
    new: str


@app.post("/api/me/password")
def change_password(body: PasswordBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        row = db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
        if not verify_password(body.old, row["password_hash"]):
            raise HTTPException(400, "原密码不正确")
        if len(body.new) < MIN_PASSWORD_LEN:
            raise HTTPException(400, f"新密码至少 {MIN_PASSWORD_LEN} 位")
        db.execute("UPDATE users SET password_hash=? WHERE id=?", (hash_password(body.new), user["id"]))
    return {"ok": True}


@app.get("/api/users")
def list_users(request: Request):
    require_user(request)
    with db_ctx() as db:
        rows = db.execute("SELECT id, username, name, is_admin, active FROM users ORDER BY name").fetchall()
    return [dict(r) for r in rows]


# ---------------------------------------------------------------- admin: users / devices

class UserBody(BaseModel):
    username: str
    name: str
    password: str
    is_admin: bool = False


@app.get("/api/admin/users")
def admin_users(request: Request):
    require_admin(request)
    with db_ctx() as db:
        rows = db.execute("SELECT id, username, name, is_admin, active, created_at FROM users ORDER BY id").fetchall()
    return [dict(r) for r in rows]


@app.post("/api/admin/users")
def admin_add_user(body: UserBody, request: Request):
    require_admin(request)
    username = body.username.strip()
    if not re.fullmatch(r"[A-Za-z0-9_]{2,32}", username):
        raise HTTPException(400, "账号只能为 2-32 位字母/数字/下划线")
    if not body.name.strip():
        raise HTTPException(400, "请填写姓名")
    if len(body.password) < MIN_PASSWORD_LEN:
        raise HTTPException(400, f"密码至少 {MIN_PASSWORD_LEN} 位")
    with db_ctx() as db:
        if db.execute("SELECT 1 FROM users WHERE username=?", (username,)).fetchone():
            raise HTTPException(400, "该账号已存在")
        cur = db.execute(
            "INSERT INTO users(username, password_hash, name, is_admin) VALUES(?,?,?,?)",
            (username, hash_password(body.password), body.name.strip(), 1 if body.is_admin else 0),
        )
        uid = cur.lastrowid
    return {"ok": True, "id": uid}


class ResetBody(BaseModel):
    password: str


@app.post("/api/admin/users/{uid}/reset")
def admin_reset_user(uid: int, body: ResetBody, request: Request):
    require_admin(request)
    if len(body.password) < MIN_PASSWORD_LEN:
        raise HTTPException(400, f"密码至少 {MIN_PASSWORD_LEN} 位")
    with db_ctx() as db:
        db.execute("UPDATE users SET password_hash=? WHERE id=?", (hash_password(body.password), uid))
    return {"ok": True}


class ActiveBody(BaseModel):
    active: bool


@app.post("/api/admin/users/{uid}/active")
def admin_toggle_user(uid: int, body: ActiveBody, request: Request):
    admin = require_admin(request)
    if uid == admin["id"]:
        raise HTTPException(400, "不能停用自己的账号")
    with db_ctx() as db:
        target = db.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        if not target:
            raise HTTPException(404, "用户不存在")
        if target["is_admin"] and not body.active:
            raise HTTPException(400, "管理员账号不能被停用，避免失去后台管理入口")
        db.execute("UPDATE users SET active=? WHERE id=?", (1 if body.active else 0, uid))
        if not body.active:
            db.execute("DELETE FROM sessions WHERE user_id=?", (uid,))
    return {"ok": True}


@app.post("/api/admin/users/{uid}/demote")
def admin_demote_user(uid: int, request: Request):
    """降权：取消管理员身份、账号保留为普通成员。

    admin 引导账号不可降权；不能对自己的账号降权（防误操作）。
    """
    admin = require_admin(request)
    if uid == admin["id"]:
        raise HTTPException(400, "不能对自己的账号降权")
    with db_ctx() as db:
        target = db.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        if not target:
            raise HTTPException(404, "用户不存在")
        if not target["is_admin"]:
            raise HTTPException(400, "该用户不是管理员")
        if target["username"] == "admin":
            raise HTTPException(400, "admin 账号不可被降权")
        db.execute("UPDATE users SET is_admin=0 WHERE id=?", (uid,))
    return {"ok": True}


@app.post("/api/admin/users/{uid}/promote")
def admin_promote_user(uid: int, request: Request):
    """升权：把普通用户设为管理员。"""
    require_admin(request)
    with db_ctx() as db:
        target = db.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        if not target:
            raise HTTPException(404, "用户不存在")
        if target["is_admin"]:
            raise HTTPException(400, "该用户已是管理员")
        db.execute("UPDATE users SET is_admin=1 WHERE id=?", (uid,))
    return {"ok": True}


@app.delete("/api/admin/users/{uid}")
def admin_del_user(uid: int, request: Request):
    """删除用户：参与过任务（发起/创建/受任过节点）的不可删，只能停用，保证全流程留痕完整。

    管理员账号一律不可从后台删除，如确需删除请直接操作数据库。
    """
    admin = require_admin(request)
    if uid == admin["id"]:
        raise HTTPException(400, "不能删除自己的账号")
    with db_ctx() as db:
        u = db.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        if not u:
            raise HTTPException(404, "用户不存在")
        if u["is_admin"]:
            raise HTTPException(400, "管理员账号不能从后台删除，如确需删除请直接操作数据库")
        involved = db.execute(
            "SELECT COUNT(*) c FROM nodes WHERE assignee_id=? OR creator_id=?", (uid, uid)
        ).fetchone()["c"] + db.execute(
            "SELECT COUNT(*) c FROM chains WHERE creator_id=?", (uid,)
        ).fetchone()["c"]
        if involved:
            raise HTTPException(400, "该用户已参与任务，为保证流程记录完整只能停用，不能删除")
        db.execute("DELETE FROM sessions WHERE user_id=?", (uid,))
        db.execute("DELETE FROM users WHERE id=?", (uid,))
    return {"ok": True}


class DeviceBody(BaseModel):
    name: str
    code: str = ""
    description: str = ""


@app.post("/api/admin/devices")
def admin_add_device(body: DeviceBody, request: Request):
    require_admin(request)
    if not body.name.strip():
        raise HTTPException(400, "请填写设备名称")
    with db_ctx() as db:
        code = body.code.strip() or None
        if code and db.execute("SELECT 1 FROM devices WHERE code=?", (code,)).fetchone():
            raise HTTPException(400, "设备编号已存在")
        cur = db.execute(
            "INSERT INTO devices(name, code, description) VALUES(?,?,?)",
            (body.name.strip(), code, body.description.strip()),
        )
        did = cur.lastrowid
    return {"ok": True, "id": did}


@app.delete("/api/admin/devices/{did}")
def admin_del_device(did: int, request: Request):
    """删除设备：占用中或已被任务用作前置要求的不可删，保证流程记录完整。"""
    require_admin(request)
    with db_ctx() as db:
        dev = db.execute("SELECT * FROM devices WHERE id=?", (did,)).fetchone()
        if not dev:
            raise HTTPException(404, "设备不存在")
        if device_status(db, did):
            raise HTTPException(400, "设备当前被占用，请先归还或强制释放再删除")
        used = db.execute(
            "SELECT COUNT(*) c FROM prereqs WHERE type='device' AND device_id=?", (did,)
        ).fetchone()["c"]
        if used:
            raise HTTPException(400, "该设备已被任务用作前置要求，不能删除")
        db.execute("DELETE FROM device_custody WHERE device_id=?", (did,))
        db.execute("DELETE FROM devices WHERE id=?", (did,))
    return {"ok": True}


def device_status(db: sqlite3.Connection, did: int):
    row = db.execute(
        "SELECT dc.id custody_id, dc.holder_id, dc.node_id, dc.taken_at, u.name holder_name, "
        "n.title node_title, n.seq node_seq, c.title chain_title, c.id chain_id "
        "FROM device_custody dc LEFT JOIN users u ON u.id=dc.holder_id "
        "LEFT JOIN nodes n ON n.id=dc.node_id LEFT JOIN chains c ON c.id=n.chain_id "
        "WHERE dc.device_id=? AND dc.returned_at IS NULL",
        (did,),
    ).fetchone()
    if row:
        return dict(row)
    return None


@app.get("/api/devices")
def list_devices(request: Request):
    require_user(request)
    with db_ctx() as db:
        rows = db.execute("SELECT * FROM devices ORDER BY id").fetchall()
        out = []
        for r in rows:
            d = dict(r)
            d["custody"] = device_status(db, r["id"])
            out.append(d)
    return out


@app.get("/api/devices/{did}")
def device_detail(did: int, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        dev = db.execute("SELECT * FROM devices WHERE id=?", (did,)).fetchone()
        if not dev:
            raise HTTPException(404, "设备不存在")
        hist = db.execute(
            "SELECT dc.*, u.name holder_name, n.title node_title, n.seq node_seq, c.title chain_title "
            "FROM device_custody dc LEFT JOIN users u ON u.id=dc.holder_id "
            "LEFT JOIN nodes n ON n.id=dc.node_id LEFT JOIN chains c ON c.id=n.chain_id "
            "WHERE dc.device_id=? ORDER BY dc.id DESC",
            (did,),
        ).fetchall()
        my_nodes = db.execute(
            "SELECT n.id, n.title, n.seq, c.title chain_title FROM prereqs p "
            "JOIN nodes n ON n.id=p.node_id JOIN chains c ON c.id=n.chain_id "
            "WHERE p.type='device' AND p.device_id=? AND n.assignee_id=? AND n.status IN ('in_progress','rejected') "
            "AND c.status='active'",
            (did, user["id"]),
        ).fetchall()
        return {
            "device": dict(dev),
            "custody": device_status(db, did),
            "history": [dict(r) for r in hist],
            "my_nodes": [dict(r) for r in my_nodes],
        }


class CheckoutBody(BaseModel):
    node_id: int


@app.post("/api/devices/{did}/checkout")
def device_checkout(did: int, body: CheckoutBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        dev = db.execute("SELECT * FROM devices WHERE id=?", (did,)).fetchone()
        if not dev:
            raise HTTPException(404, "设备不存在")
        if device_status(db, did):
            raise HTTPException(400, "设备当前被占用，不能领用")
        node = db.execute("SELECT * FROM nodes WHERE id=?", (body.node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        if node["assignee_id"] != user["id"]:
            raise HTTPException(403, "只有该任务的受任人才能为任务领用设备")
        if node["status"] not in ("in_progress", "rejected"):
            raise HTTPException(400, "该任务当前不需要设备操作")
        chain = db.execute("SELECT * FROM chains WHERE id=?", (node["chain_id"],)).fetchone()
        if chain["status"] != "active":
            raise HTTPException(400, "任务链已结束")
        pre = db.execute(
            "SELECT 1 FROM prereqs WHERE node_id=? AND type='device' AND device_id=?",
            (body.node_id, did),
        ).fetchone()
        if not pre:
            raise HTTPException(400, "该设备不是此任务的前置要求")
        db.execute(
            "INSERT INTO device_custody(device_id, holder_id, node_id) VALUES(?,?,?)",
            (did, user["id"], body.node_id),
        )
        log_event(db, node["chain_id"], node["id"], user["id"], "device_checkout",
                  {"device": dev["name"]})
    return {"ok": True}


@app.post("/api/devices/{did}/return")
def device_return(did: int, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        cust = db.execute(
            "SELECT dc.*, n.chain_id, n.title node_title FROM device_custody dc "
            "LEFT JOIN nodes n ON n.id=dc.node_id WHERE dc.device_id=? AND dc.returned_at IS NULL",
            (did,),
        ).fetchone()
        if not cust:
            raise HTTPException(400, "该设备当前不在任何任务手上")
        if cust["holder_id"] != user["id"] and not user["is_admin"]:
            raise HTTPException(403, "只有持有人或管理员可以归还")
        db.execute("UPDATE device_custody SET returned_at=datetime('now','localtime') WHERE id=?", (cust["id"],))
        dev = db.execute("SELECT * FROM devices WHERE id=?", (did,)).fetchone()
        log_event(db, cust["chain_id"], cust["node_id"], user["id"], "device_return",
                  {"device": dev["name"]})
    return {"ok": True}


@app.post("/api/admin/devices/{did}/release")
def admin_release(did: int, request: Request):
    require_admin(request)
    with db_ctx() as db:
        cust = db.execute(
            "SELECT dc.*, n.chain_id FROM device_custody dc LEFT JOIN nodes n ON n.id=dc.node_id "
            "WHERE dc.device_id=? AND dc.returned_at IS NULL",
            (did,),
        ).fetchone()
        if not cust:
            raise HTTPException(400, "该设备当前未被占用")
        db.execute("UPDATE device_custody SET returned_at=datetime('now','localtime') WHERE id=?", (cust["id"],))
        dev = db.execute("SELECT * FROM devices WHERE id=?", (did,)).fetchone()
        log_event(db, cust["chain_id"], cust["node_id"], request_user_id(db, request), "device_release",
                  {"device": dev["name"]})
    return {"ok": True}


def request_user_id(db, request):
    row = db.execute("SELECT user_id FROM sessions WHERE token=?", (request.cookies.get("sid") or "",)).fetchone()
    return row["user_id"] if row else None


# ---------------------------------------------------------------- files

@app.post("/api/files")
async def upload_file(request: Request, file: UploadFile = File(...)):
    user = require_user(request)
    ext = os.path.splitext(file.filename or "")[1].lower()
    if ext not in IMAGE_EXTS and ext not in VIDEO_EXTS:
        raise HTTPException(400, f"不支持的文件类型：{ext or '(无后缀)'}，仅支持图片/视频")
    data = await file.read()
    limit = MAX_IMAGE if ext in IMAGE_EXTS else MAX_VIDEO
    if len(data) > limit:
        raise HTTPException(400, f"文件过大（上限 {limit // (1024*1024)}MB）")
    fid = uuid.uuid4().hex
    fname = fid + ext
    with open(os.path.join(UPLOAD_DIR, fname), "wb") as f:
        f.write(data)
    mime = "image/*" if ext in IMAGE_EXTS else "video/*"
    with db_ctx() as db:
        db.execute(
            "INSERT INTO files(id, name, mime, size, path, uploader_id) VALUES(?,?,?,?,?,?)",
            (fid, os.path.basename(file.filename or fname), mime, len(data), fname, user["id"]),
        )
    return {"id": fid, "name": os.path.basename(file.filename or fname), "mime": mime, "size": len(data)}


@app.get("/files/{fid}")
def get_file(fid: str, request: Request):
    require_user(request)
    with db_ctx() as db:
        row = db.execute("SELECT * FROM files WHERE id=?", (fid,)).fetchone()
    if not row:
        raise HTTPException(404, "文件不存在")
    path = os.path.join(UPLOAD_DIR, row["path"])
    if not os.path.exists(path):
        raise HTTPException(404, "文件已丢失")
    mime = mimetypes.guess_type(row["name"])[0] or ("image/jpeg" if row["mime"] == "image/*" else "video/mp4")
    size = os.path.getsize(path)
    rng = request.headers.get("range")
    if rng:
        m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip())
        if m:
            start = int(m.group(1)) if m.group(1) else 0
            end = int(m.group(2)) if m.group(2) else size - 1
            end = min(end, size - 1)
            if start > end or start >= size:
                return app.response_class(status_code=416, headers={"Content-Range": f"bytes */{size}"})
            with open(path, "rb") as f:
                f.seek(start)
                chunk = f.read(end - start + 1)
            return StreamingResponse(
                iter([chunk]), status_code=206, media_type=mime,
                headers={
                    "Content-Range": f"bytes {start}-{end}/{size}",
                    "Accept-Ranges": "bytes",
                    "Content-Length": str(len(chunk)),
                },
            )
    return FileResponse(path, media_type=mime, filename=row["name"], headers={"Accept-Ranges": "bytes"})


# ---------------------------------------------------------------- tasks

def valid_file_ids(db, ids, user):
    out = []
    for fid in ids or []:
        row = db.execute("SELECT id, name FROM files WHERE id=?", (fid,)).fetchone()
        if not row:
            raise HTTPException(400, "附件不存在，请重新上传")
        out.append((row["id"], row["name"]))
    return out


def check_chain_cycle(db, chain_id, ref_node_id):
    """新增边 chain_id -> ref_chain（前置指向），检查是否会成环。"""
    ref = db.execute("SELECT chain_id FROM nodes WHERE id=?", (ref_node_id,)).fetchone()
    if not ref:
        raise HTTPException(400, "前置任务不存在")
    ref_chain = ref["chain_id"]
    if ref_chain == chain_id:
        raise HTTPException(400, "前置任务不能选同一任务链中的任务")
    edges = {}
    for row in db.execute(
        "SELECT n.chain_id cid, p.ref_node_id rid FROM prereqs p JOIN nodes n ON n.id=p.node_id WHERE p.type='task'"
    ).fetchall():
        r2 = db.execute("SELECT chain_id FROM nodes WHERE id=?", (row["rid"],)).fetchone()
        if r2:
            edges.setdefault(row["cid"], set()).add(r2["chain_id"])
    # 检查从 ref_chain 出发是否可达 chain_id
    seen, stack = set(), [ref_chain]
    while stack:
        cur = stack.pop()
        if cur == chain_id:
            raise HTTPException(400, "前置要求会形成循环依赖，不允许")
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(edges.get(cur, []))
    return ref_chain


class TaskBody(BaseModel):
    title: str
    content: str = ""
    criteria: str = ""
    deadline: str = ""
    assignee_id: int
    attachments: list = []
    prereqs: list = []


def _validate_task_body(db, body: TaskBody, chain_id_for_cycle=None):
    title = (body.title or "").strip()
    if not title:
        raise HTTPException(400, "请填写任务主题")
    assignee = db.execute("SELECT * FROM users WHERE id=? AND active=1", (body.assignee_id,)).fetchone()
    if not assignee:
        raise HTTPException(400, "受任人不存在或已停用")
    deadline = (body.deadline or "").strip()
    if deadline and not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}", deadline):
        raise HTTPException(400, "截止时间格式不正确")
    files = valid_file_ids(db, body.attachments, None)
    prereqs = []
    for p in body.prereqs or []:
        if p.get("type") == "task":
            rid = p.get("ref_node_id")
            node = db.execute("SELECT * FROM nodes WHERE id=?", (rid,)).fetchone()
            if not node:
                raise HTTPException(400, "前置任务不存在")
            if chain_id_for_cycle is not None:
                check_chain_cycle(db, chain_id_for_cycle, rid)
            else:
                if node["chain_id"] == chain_id_for_cycle:
                    raise HTTPException(400, "前置任务不能选同一任务链中的任务")
                if db.execute("SELECT 1 FROM chains WHERE id=?", (node["chain_id"],)).fetchone() is None:
                    raise HTTPException(400, "前置任务所在任务链不存在")
            prereqs.append(("task", rid, None, (p.get("penalty_text") or "").strip(), (p.get("penalty_url") or "").strip()))
        elif p.get("type") == "device":
            did = p.get("device_id")
            if not db.execute("SELECT 1 FROM devices WHERE id=?", (did,)).fetchone():
                raise HTTPException(400, "前置设备不存在")
            prereqs.append(("device", None, did, (p.get("penalty_text") or "").strip(), (p.get("penalty_url") or "").strip()))
        else:
            raise HTTPException(400, "未知的前置要求类型")
    return title, deadline, files, prereqs, assignee


def _insert_node(db, chain_id, seq, title, content, criteria, deadline, assignee_id, creator_id, files, prereqs):
    cur = db.execute(
        "INSERT INTO nodes(chain_id, seq, title, content, criteria, deadline, assignee_id, creator_id) "
        "VALUES(?,?,?,?,?,?,?,?)",
        (chain_id, seq, title, content, criteria, deadline.replace("T", " ") if deadline else None, assignee_id, creator_id),
    )
    node_id = cur.lastrowid
    for fid, name in files:
        db.execute("INSERT INTO node_files(node_id, file_id, name) VALUES(?,?,?)", (node_id, fid, name))
    for ptype, rid, did, ptext, purl in prereqs:
        db.execute(
            "INSERT INTO prereqs(node_id, type, ref_node_id, device_id, penalty_text, penalty_url) VALUES(?,?,?,?,?,?)",
            (node_id, ptype, rid, did, ptext, purl),
        )
    return node_id


@app.post("/api/tasks")
def create_task(body: TaskBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        title, deadline, files, prereqs, assignee = _validate_task_body(db, body)
        cur = db.execute("INSERT INTO chains(title, creator_id) VALUES(?,?)", (title, user["id"]))
        chain_id = cur.lastrowid
        node_id = _insert_node(db, chain_id, 1, title, body.content or "", body.criteria or "",
                               deadline, assignee["id"], user["id"], files, prereqs)
        log_event(db, chain_id, node_id, user["id"], "chain_create",
                  {"title": title, "assignee": assignee["name"]})
    return {"ok": True, "chain_id": chain_id, "node_id": node_id}


@app.post("/api/nodes/{node_id}/next")
def create_next_node(node_id: int, body: TaskBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        node = db.execute("SELECT * FROM nodes WHERE id=?", (node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        if node["assignee_id"] != user["id"]:
            raise HTTPException(403, "只有本节点的受任人（完成者）才能创建下一节点")
        if node["status"] != "approved":
            raise HTTPException(400, "本节点审核通过后才能创建下一节点")
        chain = db.execute("SELECT * FROM chains WHERE id=?", (node["chain_id"],)).fetchone()
        if chain["status"] != "active":
            raise HTTPException(400, "任务链已结束")
        title, deadline, files, prereqs, assignee = _validate_task_body(db, body, chain_id_for_cycle=node["chain_id"])
        seq = db.execute("SELECT MAX(seq) m FROM nodes WHERE chain_id=?", (node["chain_id"],)).fetchone()["m"] + 1
        new_node = _insert_node(db, node["chain_id"], seq, title, body.content or "", body.criteria or "",
                                deadline, assignee["id"], user["id"], files, prereqs)
        log_event(db, node["chain_id"], new_node, user["id"], "node_create",
                  {"title": title, "seq": seq, "assignee": assignee["name"]})
    return {"ok": True, "node_id": new_node}


def node_brief(db, n):
    assignee = db.execute("SELECT name FROM users WHERE id=?", (n["assignee_id"],)).fetchone()
    chain = db.execute("SELECT title, status FROM chains WHERE id=?", (n["chain_id"],)).fetchone()
    d = dict(n)
    d["assignee_name"] = assignee["name"] if assignee else "?"
    d["chain_title"] = chain["title"] if chain else "?"
    d["chain_status"] = chain["status"] if chain else "active"
    return d


@app.get("/api/tasks")
def list_tasks(request: Request, bucket: str = "unfinished"):
    user = require_user(request)
    with db_ctx() as db:
        out = {"tasks": [], "terminations": []}
        if bucket == "unfinished":
            rows = db.execute(
                "SELECT n.* FROM nodes n JOIN chains c ON c.id=n.chain_id "
                "WHERE n.assignee_id=? AND n.status IN ('in_progress','rejected') AND c.status='active' "
                "ORDER BY (n.deadline IS NULL), n.deadline, n.id DESC",
                (user["id"],),
            ).fetchall()
            out["tasks"] = [node_brief(db, r) for r in rows]
        elif bucket == "pending":
            rows = db.execute(
                "SELECT DISTINCT n.* FROM nodes n WHERE n.status='pending_review' AND (n.assignee_id=? OR n.creator_id=?) "
                "ORDER BY (n.deadline IS NULL), n.deadline, n.id DESC",
                (user["id"], user["id"]),
            ).fetchall()
            out["tasks"] = [node_brief(db, r) for r in rows]
            terms = db.execute(
                "SELECT t.*, u.name applicant_name, c.title chain_title FROM terminations t "
                "JOIN users u ON u.id=t.applicant_id JOIN chains c ON c.id=t.chain_id "
                "WHERE t.status='pending' AND c.creator_id=? ORDER BY t.id DESC",
                (user["id"],),
            ).fetchall()
            out["terminations"] = [dict(r) for r in terms]
            fb = db.execute(
                "SELECT m.id mid, m.kind, m.text, m.created_at, u.name sender, "
                "n.id node_id, n.title node_title, c.title chain_title "
                "FROM messages m JOIN nodes n ON n.id=m.node_id JOIN chains c ON c.id=n.chain_id "
                "LEFT JOIN users u ON u.id=m.user_id "
                "WHERE n.creator_id=? AND m.user_id<>? AND m.reply_to IS NULL "
                "AND m.kind IN ('feedback','appeal') "
                "AND NOT EXISTS (SELECT 1 FROM messages r WHERE r.reply_to=m.id) "
                "ORDER BY m.id DESC",
                (user["id"], user["id"]),
            ).fetchall()
            out["feedback"] = [dict(r) for r in fb]
        elif bucket == "done":
            rows = db.execute(
                "SELECT n.* FROM nodes n JOIN chains c ON c.id=n.chain_id "
                "WHERE n.assignee_id=? AND (n.status='approved' OR c.status='terminated') "
                "ORDER BY n.approved_at DESC, n.id DESC",
                (user["id"],),
            ).fetchall()
            out["tasks"] = [node_brief(db, r) for r in rows]
        else:
            raise HTTPException(400, "未知列表")
    return out


@app.get("/api/mypub")
def my_publish(request: Request):
    user = require_user(request)
    with db_ctx() as db:
        chains = db.execute("SELECT * FROM chains WHERE creator_id=? ORDER BY id DESC", (user["id"],)).fetchall()
        out = []
        for c in chains:
            nodes = db.execute(
                "SELECT n.*, u.name assignee_name FROM nodes n LEFT JOIN users u ON u.id=n.assignee_id "
                "WHERE n.chain_id=? ORDER BY n.seq",
                (c["id"],),
            ).fetchall()
            out.append({"chain": dict(c), "nodes": [dict(n) for n in nodes]})
        extra = db.execute(
            "SELECT n.*, u.name assignee_name, c.title chain_title FROM nodes n "
            "LEFT JOIN users u ON u.id=n.assignee_id JOIN chains c ON c.id=n.chain_id "
            "WHERE n.creator_id=? AND c.creator_id<>? ORDER BY n.id DESC",
            (user["id"], user["id"]),
        ).fetchall()
    return {"chains": out, "nodes": [dict(r) for r in extra]}


@app.get("/api/pick/nodes")
def pick_nodes(request: Request):
    """前置任务候选：我参与过的所有链（发起/创建/受任）中的其他链节点。"""
    user = require_user(request)
    with db_ctx() as db:
        rows = db.execute(
            "SELECT DISTINCT n.id, n.title, n.seq, n.status, c.title chain_title, c.id chain_id, u.name assignee_name "
            "FROM nodes n JOIN chains c ON c.id=n.chain_id LEFT JOIN users u ON u.id=n.assignee_id "
            "WHERE c.creator_id=? OR n.creator_id=? OR n.assignee_id=? ORDER BY c.id DESC, n.seq",
            (user["id"], user["id"], user["id"]),
        ).fetchall()
    return [dict(r) for r in rows]


def participant_of_chain(db, chain_id, user) -> bool:
    if user["is_admin"]:
        return True
    if db.execute("SELECT 1 FROM chains WHERE id=? AND creator_id=?", (chain_id, user["id"])).fetchone():
        return True
    if db.execute("SELECT 1 FROM nodes WHERE chain_id=? AND (creator_id=? OR assignee_id=?)",
                  (chain_id, user["id"], user["id"])).fetchone():
        return True
    return False


@app.get("/api/nodes/{node_id}")
def node_detail(node_id: int, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        node = db.execute("SELECT * FROM nodes WHERE id=?", (node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        chain = db.execute("SELECT * FROM chains WHERE id=?", (node["chain_id"],)).fetchone()
        if not participant_of_chain(db, chain["id"], user):
            raise HTTPException(403, "你不是该任务的参与者，无法查看")

        nodes = db.execute(
            "SELECT n.*, u.name assignee_name, uc.name creator_name FROM nodes n "
            "LEFT JOIN users u ON u.id=n.assignee_id LEFT JOIN users uc ON uc.id=n.creator_id "
            "WHERE n.chain_id=? ORDER BY n.seq", (chain["id"],),
        ).fetchall()

        # 前置要求（带实时状态）
        prereq_rows = db.execute("SELECT * FROM prereqs WHERE node_id=?", (node_id,)).fetchall()
        prereqs = []
        for p in prereq_rows:
            item = dict(p)
            if p["type"] == "task":
                rn = db.execute(
                    "SELECT n.*, c.title chain_title, u.name assignee_name FROM nodes n "
                    "JOIN chains c ON c.id=n.chain_id LEFT JOIN users u ON u.id=n.assignee_id WHERE n.id=?",
                    (p["ref_node_id"],),
                ).fetchone()
                item["ref"] = dict(rn) if rn else None
            else:
                dev = db.execute("SELECT * FROM devices WHERE id=?", (p["device_id"],)).fetchone()
                item["device"] = dict(dev) if dev else None
                cust = device_status(db, p["device_id"]) if dev else None
                item["custody"] = cust
                if cust and cust.get("node_id") == node_id:
                    item["device_mine"] = True
                else:
                    item["device_mine"] = False
            prereqs.append(item)

        files = db.execute(
            "SELECT nf.file_id, nf.name, f.mime FROM node_files nf JOIN files f ON f.id=nf.file_id WHERE nf.node_id=?",
            (node_id,),
        ).fetchall()

        subs = db.execute(
            "SELECT s.*, u.name submitter_name FROM submissions s LEFT JOIN users u ON u.id=s.user_id "
            "WHERE s.node_id=? ORDER BY s.id DESC", (node_id,),
        ).fetchall()
        submissions = []
        for s in subs:
            sf = db.execute(
                "SELECT sf.file_id, sf.name, f.mime FROM submission_files sf JOIN files f ON f.id=sf.file_id WHERE sf.submission_id=?",
                (s["id"],),
            ).fetchall()
            submissions.append({"id": s["id"], "note": s["note"], "user": s["submitter_name"],
                                "created_at": s["created_at"], "files": [dict(x) for x in sf]})

        msgs = db.execute(
            "SELECT m.*, u.name uname FROM messages m LEFT JOIN users u ON u.id=m.user_id "
            "WHERE m.node_id=? ORDER BY m.id", (node_id,),
        ).fetchall()
        message_list = []
        for m in msgs:
            d = dict(m)
            replies = db.execute(
                "SELECT m2.*, u.name uname FROM messages m2 LEFT JOIN users u ON u.id=m2.user_id WHERE m2.reply_to=? ORDER BY m2.id",
                (m["id"],),
            ).fetchall()
            d["replies"] = [dict(r) for r in replies]
            message_list.append(d)

        evs = db.execute(
            "SELECT e.*, u.name uname FROM events e LEFT JOIN users u ON u.id=e.actor_id "
            "WHERE e.chain_id=? ORDER BY e.id", (chain["id"],),
        ).fetchall()

        term = db.execute(
            "SELECT t.*, u.name applicant_name FROM terminations t LEFT JOIN users u ON u.id=t.applicant_id "
            "WHERE t.chain_id=? AND t.status='pending' ORDER BY t.id DESC LIMIT 1", (chain["id"],),
        ).fetchone()

        is_assignee = node["assignee_id"] == user["id"]
        is_creator = node["creator_id"] == user["id"]
        is_chain_creator = chain["creator_id"] == user["id"]
        prereq_ok = True
        prereq_block = []
        for p in prereqs:
            if p["type"] == "task":
                ok = p["ref"] and p["ref"]["status"] == "approved"
            else:
                ok = bool(p.get("device_mine"))
            prereq_ok = prereq_ok and bool(ok)
            if not ok:
                prereq_block.append(p)

        can_assignee_act = is_assignee and node["status"] in ("in_progress", "rejected") and chain["status"] == "active"
        can_edit_task_now = (is_creator and node["status"] in ("in_progress", "rejected", "pending_review")
                             and chain["status"] == "active")
        perms = {
            "can_submit": can_assignee_act and prereq_ok,
            "can_feedback": can_assignee_act,
            "can_appeal": can_assignee_act,
            "can_review": is_creator and node["status"] == "pending_review",
            "can_reply": is_creator,
            "can_edit_task": can_edit_task_now,
            "can_edit_submission": is_assignee and node["status"] == "pending_review",
            "can_next": is_assignee and node["status"] == "approved" and chain["status"] == "active",
            "can_terminate": chain["status"] == "active" and not term and (
                is_chain_creator or is_assignee or
                bool(db.execute("SELECT 1 FROM nodes WHERE chain_id=? AND assignee_id=?", (chain["id"], user["id"])).fetchone())
            ),
            "can_terminate_direct": is_chain_creator,
            "can_decide_terminate": is_chain_creator and bool(term),
            "can_checkout": [p["device_id"] for p in prereqs
                             if p["type"] == "device" and not p.get("device_mine")
                             and not p.get("custody") and can_assignee_act],
            "can_return": [p["device_id"] for p in prereqs if p["type"] == "device" and p.get("device_mine")],
        }
        if term:
            perms["can_terminate"] = False

    return {
        "chain": dict(chain),
        "nodes": [dict(n) for n in nodes],
        "node": dict(node),
        "prereqs": prereqs,
        "attachments": [dict(f) for f in files],
        "submissions": submissions,
        "messages": message_list,
        "events": [dict(e) for e in evs],
        "termination": dict(term) if term else None,
        "perms": perms,
        "me": {"id": user["id"], "name": user["name"]},
    }


class SubmitBody(BaseModel):
    note: str = ""
    files: list = []


class NodeEditBody(BaseModel):
    title: str = ""
    content: str = ""
    criteria: str = ""
    deadline: str = ""
    assignee_id: int = 0


@app.put("/api/nodes/{node_id}/edit")
def edit_node(node_id: int, body: NodeEditBody, request: Request):
    """节点创建者修改任务（进行中/被驳回/待审核期间），并把变更写进时间线。"""
    user = require_user(request)
    with db_ctx() as db:
        node = db.execute("SELECT * FROM nodes WHERE id=?", (node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        if node["creator_id"] != user["id"]:
            raise HTTPException(403, "只有该节点的创建者（发布者）可以修改任务")
        chain = db.execute("SELECT * FROM chains WHERE id=?", (node["chain_id"],)).fetchone()
        if chain["status"] != "active":
            raise HTTPException(400, "任务链已结束，不能修改")
        if node["status"] not in ("in_progress", "rejected", "pending_review"):
            raise HTTPException(400, "该任务已完成，不能修改（如需继续流转请创建下一节点）")
        changes = []
        sets, vals = [], []
        title = body.title.strip()
        if title and title != node["title"]:
            sets.append("title=?"); vals.append(title)
            changes.append(f"标题改为「{title}」")
        if body.content.strip() and body.content != node["content"]:
            sets.append("content=?"); vals.append(body.content)
            changes.append("任务内容已更新")
        if body.criteria.strip() and body.criteria != node["criteria"]:
            sets.append("criteria=?"); vals.append(body.criteria)
            changes.append("完成条件已更新")
        deadline = (body.deadline or "").strip()
        if deadline:
            if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}", deadline):
                raise HTTPException(400, "截止时间格式不正确")
            deadline = deadline.replace("T", " ")
        if deadline != (node["deadline"] or ""):
            sets.append("deadline=?"); vals.append(deadline or None)
            changes.append(f"截止时间改为 {deadline or '（无）'}")
        if body.assignee_id and body.assignee_id != node["assignee_id"]:
            au = db.execute("SELECT * FROM users WHERE id=? AND active=1", (body.assignee_id,)).fetchone()
            if not au:
                raise HTTPException(400, "新受任人不存在或已停用")
            sets.append("assignee_id=?"); vals.append(body.assignee_id)
            changes.append(f"受任人改为 {au['name']}")
        if not changes:
            return {"ok": True, "changes": []}
        vals.append(node_id)
        db.execute(f"UPDATE nodes SET {', '.join(sets)} WHERE id=?", vals)
        log_event(db, node["chain_id"], node_id, user["id"], "task_edit", {"changes": "；".join(changes)})
    return {"ok": True, "changes": changes}


class SubmitEditBody(BaseModel):
    note: str = ""
    files: list = []


@app.put("/api/nodes/{node_id}/submission")
def edit_submission(node_id: int, body: SubmitEditBody, request: Request):
    """受任人在审核人处理前修改自己的提交（说明 + 证明文件整体替换）。"""
    user = require_user(request)
    with db_ctx() as db:
        node = db.execute("SELECT * FROM nodes WHERE id=?", (node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        if node["assignee_id"] != user["id"]:
            raise HTTPException(403, "只有受任人可以修改提交")
        if node["status"] != "pending_review":
            raise HTTPException(400, "仅待审核期间可以修改提交")
        sub = db.execute("SELECT * FROM submissions WHERE node_id=? ORDER BY id DESC LIMIT 1",
                         (node_id,)).fetchone()
        if not sub:
            raise HTTPException(400, "尚无提交记录")
        flist = valid_file_ids(db, body.files, user)
        db.execute("UPDATE submissions SET note=? WHERE id=?", (body.note or "", sub["id"]))
        db.execute("DELETE FROM submission_files WHERE submission_id=?", (sub["id"],))
        for fid, name in flist:
            db.execute("INSERT INTO submission_files(submission_id, file_id, name) VALUES(?,?,?)",
                       (sub["id"], fid, name))
        log_event(db, node["chain_id"], node_id, user["id"], "submission_edit",
                  {"files": len(flist)})
    return {"ok": True}


@app.post("/api/nodes/{node_id}/submit")
def submit_node(node_id: int, body: SubmitBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        node = db.execute("SELECT * FROM nodes WHERE id=?", (node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        if node["assignee_id"] != user["id"]:
            raise HTTPException(403, "只有受任人可以提交")
        if node["status"] not in ("in_progress", "rejected"):
            raise HTTPException(400, "该任务当前状态不可提交")
        chain = db.execute("SELECT * FROM chains WHERE id=?", (node["chain_id"],)).fetchone()
        if chain["status"] != "active":
            raise HTTPException(400, "任务链已结束")
        for p in db.execute("SELECT * FROM prereqs WHERE node_id=?", (node_id,)).fetchall():
            if p["type"] == "task":
                rn = db.execute("SELECT status FROM nodes WHERE id=?", (p["ref_node_id"],)).fetchone()
                if not rn or rn["status"] != "approved":
                    raise HTTPException(400, "前置任务尚未全部完成，不能提交")
            else:
                cust = db.execute(
                    "SELECT * FROM device_custody WHERE device_id=? AND node_id=? AND returned_at IS NULL",
                    (p["device_id"], node_id),
                ).fetchone()
                if not cust:
                    raise HTTPException(400, "存在未领用的前置设备，不能提交")
        flist = valid_file_ids(db, body.files, user)
        cur = db.execute("INSERT INTO submissions(node_id, user_id, note) VALUES(?,?,?)",
                         (node_id, user["id"], body.note or ""))
        sid = cur.lastrowid
        for fid, name in flist:
            db.execute("INSERT INTO submission_files(submission_id, file_id, name) VALUES(?,?,?)", (sid, fid, name))
        db.execute("UPDATE nodes SET status='pending_review' WHERE id=?", (node_id,))
        log_event(db, node["chain_id"], node_id, user["id"], "submit",
                  {"note": (body.note or "")[:100], "files": len(flist)})
    return {"ok": True}


class ReviewBody(BaseModel):
    approve: bool
    comment: str = ""


@app.post("/api/nodes/{node_id}/review")
def review_node(node_id: int, body: ReviewBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        node = db.execute("SELECT * FROM nodes WHERE id=?", (node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        if node["creator_id"] != user["id"]:
            raise HTTPException(403, "只有该节点的创建者（发布者）可以审核")
        if node["status"] != "pending_review":
            raise HTTPException(400, "该任务不在待审核状态")
        if body.approve:
            db.execute("UPDATE nodes SET status='approved', approved_at=datetime('now','localtime') WHERE id=?", (node_id,))
            log_event(db, node["chain_id"], node_id, user["id"], "review_approve", {"comment": body.comment or ""})
        else:
            if not (body.comment or "").strip():
                raise HTTPException(400, "驳回时请填写原因")
            db.execute("UPDATE nodes SET status='rejected' WHERE id=?", (node_id,))
            log_event(db, node["chain_id"], node_id, user["id"], "review_reject", {"comment": body.comment})
    return {"ok": True}


class MessageBody(BaseModel):
    text: str
    kind: str = "feedback"  # feedback | appeal


@app.post("/api/nodes/{node_id}/message")
def post_message(node_id: int, body: MessageBody, request: Request):
    user = require_user(request)
    if body.kind not in ("feedback", "appeal"):
        raise HTTPException(400, "未知类型")
    text = (body.text or "").strip()
    if not text:
        raise HTTPException(400, "内容不能为空")
    with db_ctx() as db:
        node = db.execute("SELECT * FROM nodes WHERE id=?", (node_id,)).fetchone()
        if not node:
            raise HTTPException(404, "任务不存在")
        if node["assignee_id"] != user["id"]:
            raise HTTPException(403, "只有受任人可以反馈/申诉")
        db.execute("INSERT INTO messages(node_id, user_id, kind, text) VALUES(?,?,?,?)",
                   (node_id, user["id"], body.kind, text))
        log_event(db, node["chain_id"], node_id, user["id"],
                  "appeal" if body.kind == "appeal" else "feedback", {"text": text[:100]})
    return {"ok": True}


class ReplyBody(BaseModel):
    text: str
    resolve: str = ""  # "" | accepted | rejected


@app.post("/api/messages/{mid}/reply")
def reply_message(mid: int, body: ReplyBody, request: Request):
    user = require_user(request)
    text = (body.text or "").strip()
    if not text:
        raise HTTPException(400, "回复内容不能为空")
    with db_ctx() as db:
        msg = db.execute("SELECT * FROM messages WHERE id=? AND reply_to IS NULL", (mid,)).fetchone()
        if not msg:
            raise HTTPException(404, "消息不存在")
        node = db.execute("SELECT * FROM nodes WHERE id=?", (msg["node_id"],)).fetchone()
        if node["creator_id"] != user["id"]:
            raise HTTPException(403, "只有该节点的创建者可以回复")
        db.execute("INSERT INTO messages(node_id, user_id, kind, reply_to, text) VALUES(?,?,?,?,?)",
                   (node["id"], user["id"], "reply", mid, text))
        if body.resolve in ("accepted", "rejected") and msg["kind"] == "appeal":
            db.execute("UPDATE messages SET status=? WHERE id=?", (body.resolve, mid))
            log_event(db, node["chain_id"], node["id"], user["id"], "appeal_resolve",
                      {"result": body.resolve, "text": text[:100]})
        elif msg["kind"] == "feedback":
            db.execute("UPDATE messages SET status='resolved' WHERE id=?", (mid,))
            log_event(db, node["chain_id"], node["id"], user["id"], "reply", {"text": text[:100]})
        else:
            log_event(db, node["chain_id"], node["id"], user["id"], "reply", {"text": text[:100]})
    return {"ok": True}


class TerminateBody(BaseModel):
    reason: str = ""


@app.post("/api/chains/{chain_id}/terminate")
def terminate_chain(chain_id: int, body: TerminateBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        chain = db.execute("SELECT * FROM chains WHERE id=?", (chain_id,)).fetchone()
        if not chain:
            raise HTTPException(404, "任务不存在")
        if chain["status"] != "active":
            raise HTTPException(400, "任务链已结束")
        if db.execute("SELECT 1 FROM terminations WHERE chain_id=? AND status='pending'", (chain_id,)).fetchone():
            raise HTTPException(400, "已有待审核的结束申请")
        is_chain_creator = chain["creator_id"] == user["id"]
        is_assignee = bool(db.execute("SELECT 1 FROM nodes WHERE chain_id=? AND assignee_id=?",
                                      (chain_id, user["id"])).fetchone())
        if not (is_chain_creator or is_assignee):
            raise HTTPException(403, "只有链发起人或任务受任人可以结束任务")
        if is_chain_creator:
            db.execute("UPDATE chains SET status='terminated', terminated_at=datetime('now','localtime'), "
                       "terminate_reason=? WHERE id=?", (body.reason or "", chain_id))
            log_event(db, chain_id, None, user["id"], "terminate_direct", {"reason": body.reason or ""})
            return {"ok": True, "direct": True}
        db.execute("INSERT INTO terminations(chain_id, applicant_id, reason) VALUES(?,?,?)",
                   (chain_id, user["id"], body.reason or ""))
        log_event(db, chain_id, None, user["id"], "terminate_apply", {"reason": body.reason or ""})
    return {"ok": True, "direct": False}


class TermReviewBody(BaseModel):
    approve: bool
    comment: str = ""


@app.post("/api/chains/{chain_id}/terminate/review")
def terminate_review(chain_id: int, body: TermReviewBody, request: Request):
    user = require_user(request)
    with db_ctx() as db:
        chain = db.execute("SELECT * FROM chains WHERE id=?", (chain_id,)).fetchone()
        if not chain:
            raise HTTPException(404, "任务不存在")
        if chain["creator_id"] != user["id"]:
            raise HTTPException(403, "只有链发起人可以审核结束申请")
        term = db.execute("SELECT * FROM terminations WHERE chain_id=? AND status='pending' "
                          "ORDER BY id DESC LIMIT 1", (chain_id,)).fetchone()
        if not term:
            raise HTTPException(400, "没有待审核的结束申请")
        if body.approve:
            db.execute("UPDATE terminations SET status='approved', decided_at=datetime('now','localtime'), "
                       "decided_by=? WHERE id=?", (user["id"], term["id"]))
            db.execute("UPDATE chains SET status='terminated', terminated_at=datetime('now','localtime'), "
                       "terminate_reason=? WHERE id=?", (term["reason"], chain_id))
            log_event(db, chain_id, None, user["id"], "terminate_approve",
                      {"applicant": term["applicant_id"], "reason": term["reason"]})
        else:
            db.execute("UPDATE terminations SET status='rejected', decided_at=datetime('now','localtime'), "
                       "decided_by=? WHERE id=?", (user["id"], term["id"]))
            log_event(db, chain_id, None, user["id"], "terminate_reject", {"comment": body.comment or ""})
    return {"ok": True}


# ---------------------------------------------------------------- app config (APK 访问地址)

def _get_config(db, key, default=""):
    row = db.execute("SELECT value FROM app_config WHERE key=?", (key,)).fetchone()
    return row["value"] if row else default


def _set_config(db, key, value):
    db.execute(
        "INSERT INTO app_config(key, value, updated_at) VALUES(?,?,datetime('now','localtime')) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
        (key, value),
    )


def _lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


@app.get("/api/appconfig")
def public_appconfig(request: Request):
    """公开接口：APK 启动/联网时拉取官方访问地址。

    已登录用户额外返回救援邮箱凭据（仅地址自救用途，供 APK 缓存后失联时 POP3 读取）。
    """
    user = current_user(request)
    with db_ctx() as db:
        url = _get_config(db, "app_server_url")
        out = {"app_server_url": url}
        if user:
            sender = _get_config(db, "rescue_mail_from")
            code = _get_config(db, "rescue_mail_code")
            if sender and code:
                out["rescue"] = {"user": sender, "token": code, "pop_host": POP_HOST}
    return out


class RescueMailBody(BaseModel):
    sender: str = ""    # 发件邮箱账号（即 POP3 用户名）
    code: str = ""      # SMTP/POP3 授权码，留空保持原值
    to: str = ""        # 收件邮箱（可与发件相同）


@app.get("/api/admin/rescuemail")
def admin_get_rescuemail(request: Request):
    require_admin(request)
    with db_ctx() as db:
        sender = _get_config(db, "rescue_mail_from")
        code = _get_config(db, "rescue_mail_code")
        to = _get_config(db, "rescue_mail_to")
    return {"sender": sender, "to": to,
            "code": (code[:4] + "****") if code else "",
            "pop_host": POP_HOST, "smtp_host": SMTP_HOST}


@app.put("/api/admin/rescuemail")
def admin_set_rescuemail(body: RescueMailBody, request: Request):
    require_admin(request)
    sender = body.sender.strip()
    to = body.to.strip()
    if sender and "@" not in sender:
        raise HTTPException(400, "发件邮箱格式不正确")
    if to and "@" not in to:
        raise HTTPException(400, "收件邮箱格式不正确")
    with db_ctx() as db:
        if sender:
            _set_config(db, "rescue_mail_from", sender)
        if to:
            _set_config(db, "rescue_mail_to", to)
        if body.code.strip():
            _set_config(db, "rescue_mail_code", body.code.strip())
    return {"ok": True}


@app.get("/api/admin/appconfig")
def admin_get_appconfig(request: Request):
    require_admin(request)
    with db_ctx() as db:
        url = _get_config(db, "app_server_url")
    port = request.url.port or (443 if request.url.scheme == "https" else 80)
    return {"app_server_url": url, "lan_url": f"http://{_lan_ip()}:{port}"}


class AppConfigBody(BaseModel):
    app_server_url: str = ""


@app.put("/api/admin/appconfig")
def admin_set_appconfig(body: AppConfigBody, request: Request):
    require_admin(request)
    url = body.app_server_url.strip()
    if url:
        if not url.startswith(("http://", "https://")):
            raise HTTPException(400, "地址必须以 http:// 或 https:// 开头")
        while url.endswith("/"):
            url = url[:-1]
    push_result = None
    rescue_result = None
    with db_ctx() as db:
        _set_config(db, "app_server_url", url)
        if url:
            push_result = _push_entry(db, url)
            rescue_result = _send_rescue_mail(url)
    resp = {"ok": True, "app_server_url": url}
    if push_result is not None:
        resp["entry_push"] = push_result
    if rescue_result is not None:
        resp["rescue_mail"] = rescue_result
    return resp


# ---- 固定入口：把官方地址推送到入口（自托管入口服务器优先，其次 Gitee）----

ENTRY_KEYS = ("entry_owner", "entry_repo", "entry_path", "entry_branch", "entry_token")
CUSTOM_KEYS = ("entry_push_url", "entry_push_token")
RESCUE_KEYS = ("rescue_mail_from", "rescue_mail_code", "rescue_mail_to")
SMTP_HOST = "smtp.qq.com"   # 如用 163 邮箱改为 smtp.163.com
POP_HOST = "pop.qq.com"     # 如用 163 邮箱改为 pop.163.com
RESCUE_SUBJECT = "task-chain address update"   # 救援邮件主题标记（全 ASCII，APK 按此定位）


def _send_rescue_mail(server_url):
    """官方地址变更时发一封救援邮件到固定邮箱（供 APK POP3 自救读取）。

    主题与正文必须全 ASCII 明文（Python MIMEText 用 ascii 字符集不做 base64），
    APK 端按主题标记定位救援邮件、直接正则提取正文 URL。
    """
    import smtplib
    from email.mime.text import MIMEText
    with db_ctx() as db:
        sender = _get_config(db, "rescue_mail_from")
        code = _get_config(db, "rescue_mail_code")
        to = _get_config(db, "rescue_mail_to")
    if not (sender and code and to):
        return {"ok": False, "message": "救援邮箱未配置"}
    try:
        now = __import__("datetime").datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        body = (f"TaskChain server address updated: {server_url}\n"
                f"Time: {now}\n"
                f"(Auto-sent by task-chain. The APK reads this mail to recover connection.)")
        msg = MIMEText(body, "plain", "ascii")
        msg["Subject"] = RESCUE_SUBJECT
        msg["From"] = sender
        msg["To"] = to
        smtp = smtplib.SMTP_SSL(SMTP_HOST, 465, timeout=10)
        smtp.login(sender, code)
        smtp.sendmail(sender, [to], msg.as_string())
        smtp.quit()
        return {"ok": True, "message": f"救援邮件已发送至 {to}"}
    except Exception as e:
        return {"ok": False, "message": f"救援邮件发送失败：{e}"}


def _push_entry(db, server_url):
    """向已配置的入口渠道推送；返回 {ok, message}（可能聚合多渠道结果）。"""
    results = []
    c_url = _get_config(db, "entry_push_url")
    c_token = _get_config(db, "entry_push_token")
    if c_url and c_token:
        results.append(_custom_push(c_url, c_token, server_url))
    owner = _get_config(db, "entry_owner")
    if owner and _get_config(db, "entry_repo") and _get_config(db, "entry_path") and _get_config(db, "entry_token"):
        results.append(_gitee_push_config(db, server_url))
    if not results:
        return None
    ok = any(r["ok"] for r in results)
    return {"ok": ok, "message": "；".join(r["message"] for r in results)}


def _custom_push(push_url, token, server_url):
    """推送到自托管入口服务器（tunnel/entry_server.py）。"""
    import json as _json
    import urllib.error
    import urllib.request
    try:
        req = urllib.request.Request(
            push_url, method="POST",
            data=_json.dumps({"app_server_url": server_url}).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Token": token})
        with urllib.request.urlopen(req, timeout=10) as r:
            resp = _json.loads(r.read().decode("utf-8"))
        return {"ok": bool(resp.get("ok")), "message": f"入口服务器：{resp.get('message', '已更新')}" if resp.get("message") else "已推送到入口服务器"}
    except urllib.error.HTTPError as e:
        if e.code == 403:
            return {"ok": False, "message": "入口服务器：推送密钥不正确"}
        return {"ok": False, "message": f"入口服务器：HTTP {e.code}"}
    except Exception as e:
        return {"ok": False, "message": f"入口服务器连接失败：{e}"}


def _gitee_push_config(db, server_url):
    """把当前官方地址推送到 Gitee 仓库 raw 文件。返回 {ok, message}，不抛异常。"""
    owner = _get_config(db, "entry_owner")
    repo = _get_config(db, "entry_repo")
    path = _get_config(db, "entry_path")
    branch = _get_config(db, "entry_branch") or "master"
    token = _get_config(db, "entry_token")
    if not (owner and repo and path and token):
        return {"ok": False, "message": "入口同步配置不完整（owner/repo/path/token）"}
    import base64
    import json as _json
    import urllib.error
    import urllib.request
    api = f"https://gitee.com/api/v5/repos/{owner}/{repo}/contents/{path}"
    b64 = base64.b64encode(_json.dumps({"app_server_url": server_url}).encode("utf-8")).decode()
    try:
        sha = None
        try:
            with urllib.request.urlopen(f"{api}?access_token={token}&ref={branch}", timeout=10) as r:
                sha = _json.loads(r.read().decode("utf-8")).get("sha")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                return {"ok": False, "message": f"Gitee 读取失败 HTTP {e.code}（检查 token/仓库权限）"}
        payload = {"access_token": token, "content": b64, "branch": branch,
                   "message": "update app_server_url by task-chain"}
        if sha:
            payload["sha"] = sha
        req = urllib.request.Request(
            api, data=_json.dumps(payload).encode("utf-8"),
            method="PUT" if sha else "POST",
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as r:
            r.read()
        return {"ok": True, "message": "已推送到 Gitee 入口文件"}
    except Exception as e:
        return {"ok": False, "message": f"推送失败：{e}"}


class EntrySyncBody(BaseModel):
    owner: str = ""
    repo: str = ""
    path: str = ""
    branch: str = "master"
    token: str = ""  # 留空 = 保持原 token
    push_url: str = ""
    push_token: str = ""  # 留空 = 保持原 token


@app.get("/api/admin/entrysync")
def admin_get_entrysync(request: Request):
    require_admin(request)
    with db_ctx() as db:
        cfg = {k: _get_config(db, k) for k in ENTRY_KEYS}
        custom_url = _get_config(db, "entry_push_url")
        custom_token = _get_config(db, "entry_push_token")
        app_url = _get_config(db, "app_server_url")
    token = cfg["entry_token"]
    configured = bool(cfg["entry_owner"] and cfg["entry_repo"] and cfg["entry_path"] and token)
    raw_url = (f"https://gitee.com/{cfg['entry_owner']}/{cfg['entry_repo']}"
               f"/raw/{cfg['entry_branch'] or 'master'}/{cfg['entry_path']}") if configured else ""
    custom_ready = bool(custom_url and custom_token)
    return {
        "entry_owner": cfg["entry_owner"], "entry_repo": cfg["entry_repo"],
        "entry_path": cfg["entry_path"], "entry_branch": cfg["entry_branch"],
        "token": (token[:4] + "****") if token else "",
        "configured": configured, "raw_url": raw_url,
        "push_url": custom_url,
        "push_token": (custom_token[:4] + "****") if custom_token else "",
        "custom_configured": custom_ready,
        "app_server_url": app_url,
    }


@app.put("/api/admin/entrysync")
def admin_set_entrysync(body: EntrySyncBody, request: Request):
    require_admin(request)
    with db_ctx() as db:
        if body.token.strip():
            _set_config(db, "entry_token", body.token.strip())
        if body.push_token.strip():
            _set_config(db, "entry_push_token", body.push_token.strip())
        _set_config(db, "entry_owner", body.owner.strip())
        _set_config(db, "entry_repo", body.repo.strip())
        _set_config(db, "entry_path", body.path.strip())
        _set_config(db, "entry_branch", body.branch.strip() or "master")
        if body.push_url.strip():
            u = body.push_url.strip()
            if not u.startswith(("http://", "https://")):
                raise HTTPException(400, "推送地址必须以 http:// 或 https:// 开头")
            _set_config(db, "entry_push_url", u)
    return {"ok": True}


@app.post("/api/admin/rescuemail/test")
def admin_rescuemail_test(request: Request):
    require_admin(request)
    with db_ctx() as db:
        url = _get_config(db, "app_server_url")
    return _send_rescue_mail(url or "http://测试地址:8000")


@app.post("/api/admin/entrysync/push")
def admin_entrysync_push(request: Request):
    require_admin(request)
    with db_ctx() as db:
        url = _get_config(db, "app_server_url")
        if not url:
            raise HTTPException(400, "请先设置 APK 官方访问地址")
        result = _gitee_push_config(db, url)
    return result


@app.get("/api/admin/appconfig/qr.svg")
def admin_appconfig_qr(request: Request):
    require_admin(request)
    with db_ctx() as db:
        url = _get_config(db, "app_server_url")
    if not url:
        raise HTTPException(400, "尚未设置 APK 访问地址")
    try:
        import io as _io
        import qrcode
        import qrcode.image.svg
        img = qrcode.make(url, image_factory=qrcode.image.svg.SvgPathImage)
        buf = _io.BytesIO()
        img.save(buf)
    except Exception:
        raise HTTPException(500, "二维码生成失败")
    return Response(buf.getvalue(), media_type="image/svg+xml")


# ---------------------------------------------------------------- admin: overview

@app.get("/api/admin/overview")
def admin_overview(request: Request):
    require_admin(request)
    with db_ctx() as db:
        chains = db.execute(
            "SELECT c.*, u.name creator_name, (SELECT COUNT(*) FROM nodes n WHERE n.chain_id=c.id) node_count "
            "FROM chains c LEFT JOIN users u ON u.id=c.creator_id ORDER BY c.id DESC"
        ).fetchall()
        stats = {
            "users": db.execute("SELECT COUNT(*) c FROM users").fetchone()["c"],
            "devices": db.execute("SELECT COUNT(*) c FROM devices").fetchone()["c"],
            "chains": db.execute("SELECT COUNT(*) c FROM chains").fetchone()["c"],
            "active": db.execute("SELECT COUNT(*) c FROM chains WHERE status='active'").fetchone()["c"],
        }
    return {"chains": [dict(r) for r in chains], "stats": stats}


@app.delete("/api/admin/chains/{chain_id}")
def admin_del_chain(chain_id: int, request: Request):
    """删除整条任务链（仅管理员后台）：被其他链引用为前置、或仍有设备未归还的不可删。

    级联清除该链的节点、前置要求、提交与证明文件、反馈申诉、时间线、终结记录与设备领用记录。
    """
    require_admin(request)
    with db_ctx() as db:
        chain = db.execute("SELECT * FROM chains WHERE id=?", (chain_id,)).fetchone()
        if not chain:
            raise HTTPException(404, "任务链不存在")
        node_ids = [r["id"] for r in db.execute(
            "SELECT id FROM nodes WHERE chain_id=?", (chain_id,)).fetchall()]
        ph = ",".join("?" * len(node_ids)) if node_ids else "NULL"

        used = db.execute(
            f"SELECT COUNT(*) c FROM prereqs p JOIN nodes n ON n.id=p.node_id "
            f"WHERE p.type='task' AND p.ref_node_id IN ({ph}) AND n.chain_id<>?",
            (*node_ids, chain_id),
        ).fetchone()["c"]
        if used:
            raise HTTPException(400, "其他任务链把本任务用作前置要求，不能删除")
        act = db.execute(
            f"SELECT COUNT(*) c FROM device_custody WHERE node_id IN ({ph}) AND returned_at IS NULL",
            node_ids,
        ).fetchone()["c"]
        if act:
            raise HTTPException(400, "该任务链仍有设备未归还，请先归还或强制释放再删除")

        file_ids = [r["fid"] for r in db.execute(
            f"SELECT nf.file_id fid FROM node_files nf WHERE nf.node_id IN ({ph}) "
            f"UNION SELECT sf.file_id fid FROM submission_files sf "
            f"JOIN submissions s ON s.id=sf.submission_id WHERE s.node_id IN ({ph})",
            (*node_ids, *node_ids),
        ).fetchall()]
        db.execute(f"DELETE FROM submission_files WHERE submission_id IN "
                   f"(SELECT id FROM submissions WHERE node_id IN ({ph}))", node_ids)
        db.execute(f"DELETE FROM submissions WHERE node_id IN ({ph})", node_ids)
        db.execute(f"DELETE FROM messages WHERE node_id IN ({ph})", node_ids)
        db.execute(f"DELETE FROM prereqs WHERE node_id IN ({ph})", node_ids)
        db.execute(f"DELETE FROM node_files WHERE node_id IN ({ph})", node_ids)
        db.execute(f"DELETE FROM device_custody WHERE node_id IN ({ph})", node_ids)
        db.execute("DELETE FROM events WHERE chain_id=?", (chain_id,))
        db.execute("DELETE FROM terminations WHERE chain_id=?", (chain_id,))
        db.execute("DELETE FROM nodes WHERE chain_id=?", (chain_id,))
        db.execute("DELETE FROM chains WHERE id=?", (chain_id,))
        for fid in file_ids:
            row = db.execute("SELECT path FROM files WHERE id=?", (fid,)).fetchone()
            if row:
                db.execute("DELETE FROM files WHERE id=?", (fid,))
                try:
                    os.remove(os.path.join(UPLOAD_DIR, row["path"]))
                except OSError:
                    pass
    return {"ok": True, "deleted_nodes": len(node_ids)}


# ---------------------------------------------------------------- APK 分发（服务器自分发，手机无需访问 GitHub）

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST_DIR = os.path.join(BASE_DIR, "dist")
APK_FILE = os.path.join(DIST_DIR, "task-chain.apk")
APK_VER_FILE = os.path.join(DIST_DIR, "version.txt")
os.makedirs(DIST_DIR, exist_ok=True)


def _dist_version():
    try:
        with open(APK_VER_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


def _apk_dist_info():
    ver = _dist_version()
    size = os.path.getsize(APK_FILE) if os.path.exists(APK_FILE) else 0
    return {"version": ver, "size": size, "available": os.path.exists(APK_FILE) and size > 0}


@app.get("/apk/info")
def apk_info():
    """公开：查询服务器当前分发的 APK 版本（供 APK 端检查更新）。"""
    return _apk_dist_info()


@app.get("/apk")
def apk_download():
    """公开：下载服务器分发的 APK（安装前无账号也可下载）。"""
    info = _apk_dist_info()
    if not info["available"]:
        raise HTTPException(404, "服务器尚未放置 APK 安装包")
    ver = info["version"] or "unknown"
    return FileResponse(
        APK_FILE,
        media_type="application/vnd.android.package-archive",
        filename=f"task-chain-{ver}.apk",
    )


def _cleanup_dist():
    """启动时清理分发目录：只保留最新一份 APK 与版本记录，删除旧版本残留。"""
    keep = {"task-chain.apk", "version.txt"}
    try:
        for name in os.listdir(DIST_DIR):
            if name in keep:
                continue
            try:
                os.remove(os.path.join(DIST_DIR, name))
                print(f"[dist] removed old file: {name}")
            except OSError:
                pass
    except Exception:
        pass


# ---------------------------------------------------------------- startup & static

@app.on_event("startup")
def startup():
    init_db()
    with db_ctx() as db:
        if not db.execute("SELECT 1 FROM users LIMIT 1").fetchone():
            db.execute("INSERT INTO users(username, password_hash, name, is_admin) VALUES(?,?,?,1)",
                       ("admin", hash_password("admin123"), "管理员"))
            print("[init] created default admin account: admin / admin123")
    _cleanup_dist()
    _start_discovery_responder()


# ---------------------------------------------------------------- LAN discovery (APK 零配置)

DISCOVERY_PROBE = b"TASKCHAIN_DISCOVER"
DISCOVERY_PORT = 9875


def _start_discovery_responder():
    """UDP 应答线程：APK 在局域网广播问询，本线程回应服务器地址（端口 9875/UDP）。"""
    http_port = os.environ.get("TASKCHAIN_PORT", "8000")
    disc_port = int(os.environ.get("TASKCHAIN_DISCOVERY_PORT", str(DISCOVERY_PORT)))

    def responder():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", disc_port))
            sock.settimeout(1.0)
        except Exception as e:
            print(f"[discovery] UDP {disc_port} bind failed: {e}")
            return
        while True:
            try:
                data, addr = sock.recvfrom(1024)
                if not data.startswith(DISCOVERY_PROBE):
                    continue
                try:
                    tmp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    tmp.connect((addr[0], 9))  # 不发包，取该方向的本机接口 IP
                    my_ip = tmp.getsockname()[0]
                    tmp.close()
                except Exception:
                    my_ip = _lan_ip()
                reply = f"TASKCHAIN_SERVER|http://{my_ip}:{http_port}".encode("utf-8")
                sock.sendto(reply, addr)
            except socket.timeout:
                continue
            except Exception:
                continue

    threading.Thread(target=responder, daemon=True, name="discovery-responder").start()


STATIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")
app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
