# -*- coding: utf-8 -*-
"""API 全流程冒烟测试：覆盖发布/前置/设备领用/反馈申诉/提交/审核/链式节点/结束/权限。

默认请求 http://127.0.0.1:8000（生产）。**测试会写入数据**——请用 run_tests.ps1
在独立临时库 + 8001 端口上运行，不要对着生产库跑。
"""
import io
import os
import struct
import sys
import zlib

import requests

BASE = os.environ.get("TASKCHAIN_TEST_BASE", "http://127.0.0.1:8000")
FAILED = []


def check(name, cond, extra=""):
    tag = "PASS" if cond else "FAIL"
    print(f"[{tag}] {name}" + (f" | {extra}" if extra and not cond else ""))
    if not cond:
        FAILED.append(name)


def login(username, password="123456"):
    s = requests.Session()
    r = s.post(f"{BASE}/api/login", json={"username": username, "password": password})
    assert r.ok, f"login {username} failed: {r.text}"
    return s


def make_png():
    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    raw = zlib.compress(b"\x00\xff\x00\x00")
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", raw) + chunk(b"IEND", b""))


def main():
    admin = login("admin", "admin123")
    zs = login("zhangsan")
    ls = login("lisi")
    ww = login("wangwu")

    # ---- 基础信息 ----
    me = zs.get(f"{BASE}/api/me").json()
    check("me 未完成徽章为 0", me["badges"]["unfinished"] == 0)
    users = zs.get(f"{BASE}/api/users").json()
    uid = {u["username"]: u["id"] for u in users}
    devices = zs.get(f"{BASE}/api/devices").json()
    check("设备列表含无人机", any(d["name"] == "无人机-01" for d in devices))
    uav = next(d for d in devices if d["name"] == "无人机-01")

    # ---- 管理员：注册新设备/新用户 ----
    r = admin.post(f"{BASE}/api/admin/devices", json={"name": "测试仪-03", "code": "TST-003"})
    check("管理员注册设备", r.ok, r.text)
    r = admin.post(f"{BASE}/api/admin/users", json={"username": "testu", "name": "测试员", "password": "testu12345"})
    check("管理员建用户", r.ok, r.text)
    r = admin.post(f"{BASE}/api/admin/users", json={"username": "weakpw", "name": "弱密码", "password": "1234"})
    check("管理员建用户弱密码被拒", r.status_code == 400, r.text)
    tu = login("testu", "testu12345")

    # ---- APK 官方访问地址配置 ----
    r = requests.get(f"{BASE}/api/appconfig")
    check("公开拉取 appconfig(空)", r.ok and r.json()["app_server_url"] == "", r.text)
    r = tu.put(f"{BASE}/api/admin/appconfig", json={"app_server_url": "ftp://x"})
    check("非管理员不能改配置(403)", r.status_code == 403, f"got {r.status_code}")
    r = admin.put(f"{BASE}/api/admin/appconfig", json={"app_server_url": "ftp://x"})
    check("非 http(s) 地址被拒", r.status_code == 400, r.text)
    r = admin.put(f"{BASE}/api/admin/appconfig", json={"app_server_url": "http://192.168.1.50:8000/"})
    check("管理员保存官方地址", r.ok and r.json()["app_server_url"] == "http://192.168.1.50:8000", r.text)
    r = requests.get(f"{BASE}/api/appconfig")
    check("公开拉取到新地址(末尾斜杠已清理)", r.json()["app_server_url"] == "http://192.168.1.50:8000", r.text)
    r = admin.get(f"{BASE}/api/admin/appconfig")
    check("管理端读取含 lan_url", r.ok and r.json()["lan_url"].startswith("http://"), r.text)
    r = admin.get(f"{BASE}/api/admin/appconfig/qr.svg")
    check("二维码 SVG 生成", r.ok and "svg" in r.headers.get("content-type", ""), r.status_code)
    admin.put(f"{BASE}/api/admin/appconfig", json={"app_server_url": ""})
    r = requests.get(f"{BASE}/api/appconfig")
    check("可清空官方地址", r.json()["app_server_url"] == "", r.text)

    # ---- 开放注册 ----
    s = requests.Session()
    r = s.post(f"{BASE}/api/register", json={"username": "newguy", "name": "新用户", "password": "register8"})
    check("自助注册成功并自动登录", r.ok and s.get(f"{BASE}/api/me").json()["user"]["name"] == "新用户", r.text)
    r = requests.post(f"{BASE}/api/register", json={"username": "newguy", "name": "重复", "password": "register8"})
    check("重复账号被拒", r.status_code == 400, r.text)
    r = requests.post(f"{BASE}/api/register", json={"username": "shortpw", "name": "短", "password": "a1b2c3"})
    check("短密码注册被拒", r.status_code == 400, r.text)
    r = requests.post(f"{BASE}/api/register", json={"username": "非法-名", "name": "x", "password": "a1b2c3d4"})
    check("非法用户名被拒", r.status_code == 400, r.text)
    r = requests.post(f"{BASE}/api/login", json={"username": "newguy", "password": "register8"})
    check("注册用户可登录", r.ok, r.text)

    # ---- 发布任务（带设备前置 + 截止时间）----
    r = zs.post(f"{BASE}/api/tasks", json={
        "title": "厂区航拍巡检", "content": "用无人机对厂区东侧围墙航拍", "criteria": "照片清晰覆盖全部围墙段",
        "deadline": "2099-01-01T18:00", "assignee_id": uid["lisi"],
        "prereqs": [{"type": "device", "device_id": uav["id"],
                     "penalty_text": "丢失按合同第3条赔偿", "penalty_url": "https://example.com/contract"}],
    })
    check("发布任务", r.ok, r.text)
    n1 = r.json()["node_id"]; c1 = r.json()["chain_id"]

    # 前置未满足：提交被拒
    r = ls.post(f"{BASE}/api/nodes/{n1}/submit", json={"note": "x"})
    check("前置设备未领用时提交被拒", r.status_code == 400, r.text)

    # 非受任人不能领用
    r = ww.post(f"{BASE}/api/devices/{uav['id']}/checkout", json={"node_id": n1})
    check("非受任人领用被拒", r.status_code == 403, r.text)

    # 受任人领用 → 详情显示已领用 → 提交
    r = ls.post(f"{BASE}/api/devices/{uav['id']}/checkout", json={"node_id": n1})
    check("受任人领用设备", r.ok, r.text)
    d = ls.get(f"{BASE}/api/nodes/{n1}").json()
    check("详情显示设备已领用", d["prereqs"][0].get("device_mine") is True)
    check("设备前置满足后 perms.can_submit", d["perms"]["can_submit"] is True)

    # 占用中：第三方再领用被拒
    r2 = ls.post(f"{BASE}/api/tasks", json={"title": "另拍", "assignee_id": uid["wangwu"], "prereqs": [{"type": "device", "device_id": uav["id"]}]})
    n_other = r2.json()["node_id"]
    r = ww.post(f"{BASE}/api/devices/{uav['id']}/checkout", json={"node_id": n_other})
    check("设备被占用时他人领用被拒", r.status_code == 400, r.text)

    # 反馈与申诉
    r = ls.post(f"{BASE}/api/nodes/{n1}/message", json={"text": "无人机电池只有一块，够吗？", "kind": "feedback"})
    check("受任人反馈", r.ok, r.text)
    r = ls.post(f"{BASE}/api/nodes/{n1}/message", json={"text": "截止时间太紧，申请放宽", "kind": "appeal"})
    check("受任人申诉", r.ok, r.text)
    mid = ls.get(f"{BASE}/api/nodes/{n1}").json()["messages"]
    appeal_id = next(m["id"] for m in mid if m["kind"] == "appeal")
    r = zs.post(f"{BASE}/api/messages/{appeal_id}/reply", json={"text": "已延长到月底", "resolve": "accepted"})
    check("发布者回复并受理申诉", r.ok, r.text)
    r = ls.post(f"{BASE}/api/nodes/{n1}/message", json={"text": "try", "kind": "feedback"})
    # 发布者（非受任人）不能反馈
    r = zs.post(f"{BASE}/api/nodes/{n1}/message", json={"text": "hi", "kind": "feedback"})
    check("发布者不能反馈", r.status_code == 403, r.text)

    # 上传证明并提交
    files = {"file": ("proof.png", io.BytesIO(make_png()), "image/png")}
    r = ls.post(f"{BASE}/api/files", files=files)
    check("上传图片", r.ok, r.text)
    fid = r.json()["id"]
    r = ls.get(f"{BASE}/files/{fid}")
    check("读取文件", r.ok and r.headers["content-type"].startswith("image/"), r.status_code)
    r = ls.post(f"{BASE}/api/nodes/{n1}/submit", json={"note": "已拍完", "files": [fid]})
    check("提交任务", r.ok, r.text)
    d = ls.get(f"{BASE}/api/nodes/{n1}").json()
    check("节点进入待审核", d["node"]["status"] == "pending_review")
    me = ls.get(f"{BASE}/api/me").json()
    check("李四待审核徽章>0", me["badges"]["pending_review"] >= 1)

    # 只有节点创建者能审核
    r = ww.post(f"{BASE}/api/nodes/{n1}/review", json={"approve": True})
    check("非创建者审核被拒", r.status_code == 403, r.text)
    r = zs.post(f"{BASE}/api/nodes/{n1}/review", json={"approve": False, "comment": ""})
    check("驳回必须写原因", r.status_code == 400, r.text)

    # 先驳回再重新提交再通过
    r = zs.post(f"{BASE}/api/nodes/{n1}/review", json={"approve": False, "comment": "缺西南角照片"})
    check("驳回", r.ok, r.text)
    d = ls.get(f"{BASE}/api/nodes/{n1}").json()
    check("驳回后回到 rejected", d["node"]["status"] == "rejected")
    check("被拒后仍可提交（设备仍在我手上）", d["perms"]["can_submit"] is True)
    r = ls.post(f"{BASE}/api/nodes/{n1}/submit", json={"note": "补拍西南角", "files": [fid]})
    check("重新提交", r.ok, r.text)
    r = zs.post(f"{BASE}/api/nodes/{n1}/review", json={"approve": True})
    check("审核通过", r.ok, r.text)

    # 未完成者不能创建下一节点；完成者可以
    r = zs.post(f"{BASE}/api/nodes/{n1}/next", json={"title": "x", "assignee_id": uid["zhangsan"]})
    check("非受任人创建下一节点被拒", r.status_code == 403, r.text)
    r = ls.post(f"{BASE}/api/nodes/{n1}/next", json={
        "title": "航拍照片归档标注", "content": "把照片按区域归档", "assignee_id": uid["wangwu"]})
    check("完成者创建下一节点", r.ok, r.text)
    n2 = r.json()["node_id"]

    # 同链前置被拒（下一节点不得引用本链任务）
    r = ls.post(f"{BASE}/api/nodes/{n1}/next", json={"title": "x", "assignee_id": uid["lisi"],
                                                     "prereqs": [{"type": "task", "ref_node_id": n2}]})
    check("同链循环前置被拒", r.status_code == 400, r.text)

    # 归还设备后：n2（无设备前置）不受影响；新任务领用恢复空闲
    r = ls.post(f"{BASE}/api/devices/{uav['id']}/return")
    check("归还设备", r.ok, r.text)
    r = ww.post(f"{BASE}/api/devices/{uav['id']}/checkout", json={"node_id": n_other})
    check("归还后他人可领用", r.ok, r.text)

    # n2 提交 → 由节点创建者（李四）审核
    r = ww.post(f"{BASE}/api/nodes/{n2}/submit", json={"note": "归档完成"})
    check("节点2提交", r.ok, r.text)
    r = zs.post(f"{BASE}/api/nodes/{n2}/review", json={"approve": True})
    check("链发起人审核节点2被拒（应由创建者李四审）", r.status_code == 403, r.text)
    r = ls.post(f"{BASE}/api/nodes/{n2}/review", json={"approve": True})
    check("节点创建者审核节点2通过", r.ok, r.text)

    # 前置任务链：chain2 依赖 chain1 节点1（已完成）
    r = zs.post(f"{BASE}/api/tasks", json={"title": "巡检报告", "assignee_id": uid["zhangsan"],
                                           "prereqs": [{"type": "task", "ref_node_id": n1}]})
    check("跨链前置任务发布", r.ok, r.text)
    n3, c3 = r.json()["node_id"], r.json()["chain_id"]
    r = zs.post(f"{BASE}/api/nodes/{n3}/submit", json={"note": "ok"})
    check("前置任务已完成 → 可提交", r.ok, r.text)
    zs.post(f"{BASE}/api/nodes/{n3}/review", json={"approve": True})

    # 循环依赖：让 chain1 的新节点依赖 chain3 → cycle
    r = ls.post(f"{BASE}/api/nodes/{n1}/next", json={"title": "环?", "assignee_id": uid["lisi"],
                                                     "prereqs": [{"type": "task", "ref_node_id": n3}]})
    check("跨链循环依赖被拒", r.status_code == 400, r.text)

    # 结束：受任人申请 → 发起人审核
    r = ww.post(f"{BASE}/api/chains/{c1}/terminate", json={"reason": "航拍任务已全部完成，结束链条"})
    check("受任人申请结束", r.ok and not r.json()["direct"], r.text)
    d = zs.get(f"{BASE}/api/nodes/{n1}").json()
    check("发起人看到待审结束申请", d["termination"] and d["perms"]["can_decide_terminate"])
    me = zs.get(f"{BASE}/api/me").json()
    check("结束申请计入待审核徽章", me["badges"]["pending_review"] >= 1)
    r = zs.post(f"{BASE}/api/chains/{c1}/terminate/review", json={"approve": True})
    check("发起人同意结束", r.ok, r.text)
    d = zs.get(f"{BASE}/api/nodes/{n1}").json()
    check("链状态已结束", d["chain"]["status"] == "terminated")

    # 发起人直接结束（二次确认在前端，API 直接生效）
    r = ww.post(f"{BASE}/api/tasks", json={"title": "临时观察", "assignee_id": uid["lisi"]})
    c4 = r.json()["chain_id"]
    r = ww.post(f"{BASE}/api/chains/{c4}/terminate", json={"reason": "不做了"})
    check("发起人直接结束", r.ok and r.json()["direct"], r.text)

    # 王五发起结束李四的链 → 李四拒绝
    r = ww.post(f"{BASE}/api/chains/{c3}/terminate", json={"reason": "想结束"})
    check("非参与者结束被拒", r.status_code == 403, r.text)

    # 权限：testu 无法查看
    r = tu.get(f"{BASE}/api/nodes/{n1}")
    check("非参与者查看被拒", r.status_code == 403, r.text)
    # 管理员可以查看
    r = admin.get(f"{BASE}/api/nodes/{n1}")
    check("管理员可查看任意任务", r.ok, r.text)

    # 时间线完整
    d = ls.get(f"{BASE}/api/nodes/{n1}").json()
    types = [e["type"] for e in d["events"]]
    for t in ["chain_create", "device_checkout", "feedback", "appeal", "submit", "review_reject", "review_approve", "node_create", "terminate_apply", "terminate_approve"]:
        check(f"时间线含 {t}", t in types, str(types))

    # 我的发布 / 设备历史
    mp = zs.get(f"{BASE}/api/mypub").json()
    check("我的发布含 chain1", any(x["chain"]["id"] == c1 for x in mp["chains"]))
    dd = admin.get(f"{BASE}/api/devices/{uav['id']}").json()
    check("设备流转记录>=2", len(dd["history"]) >= 2, str(len(dd["history"])))

    # 管理员强制释放
    admin.post(f"{BASE}/api/admin/devices", json={"name": "相机-04"})
    devices = admin.get(f"{BASE}/api/devices").json()
    cam = next(d for d in devices if d["name"] == "相机-04")
    r = ww.post(f"{BASE}/api/tasks", json={"title": "相机试用", "assignee_id": uid["wangwu"],
                                           "prereqs": [{"type": "device", "device_id": cam["id"]}]})
    n_cam = r.json()["node_id"]
    r = ww.post(f"{BASE}/api/devices/{cam['id']}/checkout", json={"node_id": n_cam})
    check("受任人为新任务领用相机", r.ok, r.text)
    r = admin.post(f"{BASE}/api/admin/devices/{cam['id']}/release")
    check("管理员强制释放", r.ok, r.text)
    admin.post(f"{BASE}/api/admin/devices", json={"name": "备用机-05"})
    devices = admin.get(f"{BASE}/api/devices").json()
    spare = next(d for d in devices if d["name"] == "备用机-05")
    r = admin.delete(f"{BASE}/api/admin/devices/{spare['id']}")
    check("删除空闲且未被引用的设备", r.ok, r.text)
    r = admin.delete(f"{BASE}/api/admin/devices/{cam['id']}")
    check("被前置引用的设备不能删", r.status_code == 400, r.text)
    r = admin.delete(f"{BASE}/api/admin/devices/99999")
    check("删除不存在的设备 404", r.status_code == 404, f"got {r.status_code}")

    # 用户删除
    r = admin.delete(f"{BASE}/api/admin/users/{uid['zhangsan']}")
    check("参与过任务的用户不能删", r.status_code == 400, r.text)
    r = admin.delete(f"{BASE}/api/admin/users/1")
    check("不能删除自己", r.status_code == 400, r.text)
    r = admin.post(f"{BASE}/api/admin/users", json={"username": "delsz", "name": "待删员", "password": "delsz12345"})
    check("新建待删用户", r.ok, r.text)
    new_id = r.json()["id"]
    r = admin.delete(f"{BASE}/api/admin/users/{new_id}")
    check("删除未参与任务的用户", r.ok, r.text)
    r = requests.post(f"{BASE}/api/login", json={"username": "delsz", "password": "delsz12345"})
    check("已删用户无法登录", r.status_code == 400, f"got {r.status_code}")

    # 管理员账号保护：不可停用，避免失去后台管理入口
    r = admin.post(f"{BASE}/api/admin/users", json={"username": "admin2", "name": "副管理", "password": "admin23456", "is_admin": True})
    check("新建第二个管理员", r.ok, r.text)
    a2 = r.json()["id"]
    r = admin.post(f"{BASE}/api/admin/users/{a2}/active", json={"active": False})
    check("管理员不能被停用", r.status_code == 400, r.text)
    r = admin.post(f"{BASE}/api/admin/users", json={"username": "toggletz", "name": "停用员", "password": "togg12345"})
    tz_id = r.json()["id"]
    r = admin.post(f"{BASE}/api/admin/users/{tz_id}/active", json={"active": False})
    check("普通用户可停用", r.ok, r.text)
    r = admin.post(f"{BASE}/api/admin/users/{tz_id}/active", json={"active": True})
    check("普通用户可启用", r.ok, r.text)
    r = admin.delete(f"{BASE}/api/admin/users/{tz_id}")
    check("清理停用测试用户", r.ok, r.text)

    # 任务链删除（管理员后台专属）
    r = ls.delete(f"{BASE}/api/admin/chains/{c4}")
    check("非管理员不能删除任务链", r.status_code == 403, f"got {r.status_code}")
    r = admin.delete(f"{BASE}/api/admin/chains/{c1}")
    check("被其他链引用为前置的链不能删", r.status_code == 400, r.text)
    devices = admin.get(f"{BASE}/api/devices").json()
    tester = next(d for d in devices if d["name"] == "测试仪-03")
    r = ww.post(f"{BASE}/api/tasks", json={"title": "占用中的链", "assignee_id": uid["wangwu"],
                                           "prereqs": [{"type": "device", "device_id": tester["id"]}]})
    c_busy, n_busy = r.json()["chain_id"], r.json()["node_id"]
    r = ww.post(f"{BASE}/api/devices/{tester['id']}/checkout", json={"node_id": n_busy})
    check("为待删链领用设备", r.ok, r.text)
    r = admin.delete(f"{BASE}/api/admin/chains/{c_busy}")
    check("有设备未归还的链不能删", r.status_code == 400, r.text)
    r = ww.post(f"{BASE}/api/devices/{tester['id']}/return")
    check("归还设备", r.ok, r.text)
    r = ww.post(f"{BASE}/api/tasks", json={"title": "一次性待删链", "assignee_id": uid["wangwu"]})
    c_del, n_del = r.json()["chain_id"], r.json()["node_id"]
    r = admin.delete(f"{BASE}/api/admin/chains/{c_del}")
    check("管理员删除无引用链", r.ok and r.json()["deleted_nodes"] == 1, r.text)
    r = ww.get(f"{BASE}/api/nodes/{n_del}")
    check("删除后节点详情 404", r.status_code == 404, f"got {r.status_code}")
    ov = admin.get(f"{BASE}/api/admin/overview").json()
    check("总览链数已减少", ov["stats"]["chains"] == 6, str(ov["stats"]["chains"]))

    # 列表桶
    for s, name in [(ls, "李四"), (zs, "张三"), (ww, "王五")]:
        for b in ["unfinished", "pending", "done"]:
            r = s.get(f"{BASE}/api/tasks?bucket={b}")
            check(f"{name} 列表 {b}", r.ok, r.text)

    # 登录防爆破（放在最后：锁的是不存在的用户名，不影响其他用例）
    for i in range(5):
        requests.post(f"{BASE}/api/login", json={"username": "bruteforce_x", "password": "wrong"})
    r = requests.post(f"{BASE}/api/login", json={"username": "bruteforce_x", "password": "whatever"})
    check("5 次失败后触发限流 429", r.status_code == 429, f"got {r.status_code}")

    # 旧版弱密码兼容（v1 哈希自动升级）：zhangsan/123456 仍可登录且升级后再登录正常
    r = requests.post(f"{BASE}/api/login", json={"username": "zhangsan", "password": "123456"})
    check("旧密码兼容登录", r.ok, r.text)
    r = requests.post(f"{BASE}/api/login", json={"username": "zhangsan", "password": "123456"})
    check("哈希升级后再登录", r.ok, r.text)

    print()
    if FAILED:
        print(f"== {len(FAILED)} FAILED ==")
        for f in FAILED:
            print(" -", f)
        sys.exit(1)
    print("== ALL TESTS PASSED ==")


if __name__ == "__main__":
    if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
        sys.stdout.reconfigure(encoding="utf-8")
    main()
