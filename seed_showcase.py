# -*- coding: utf-8 -*-
"""写入一条演示任务链（仅用于 README 截图与首次体验）。"""
import requests

BASE = "http://127.0.0.1:8000"


def login(username, password="123456"):
    s = requests.Session()
    r = s.post(f"{BASE}/api/login", json={"username": username, "password": password})
    assert r.ok
    return s


def main():
    users = {u["username"]: u["id"] for u in login("admin", "admin123").get(f"{BASE}/api/users").json()}
    devices = {d["name"]: d["id"] for d in login("admin", "admin123").get(f"{BASE}/api/devices").json()}
    zs, ls = login("zhangsan"), login("lisi")

    r = zs.post(f"{BASE}/api/tasks", json={
        "title": "厂区东侧围墙航拍巡检",
        "content": "使用无人机对厂区东侧围墙全线航拍，检查墙体破损情况，重点关注顶部裂缝。",
        "criteria": "照片清晰覆盖全部围墙段，破损位置需在图上标注",
        "deadline": "2026-09-20T18:00", "assignee_id": users["lisi"],
        "prereqs": [{"type": "device", "device_id": devices["无人机-01"],
                     "penalty_text": "设备丢失或损坏按设备租赁合同第 3 条赔偿",
                     "penalty_url": "https://example.com/contract#3"}],
    })
    n1 = r.json()["node_id"]
    ls.post(f"{BASE}/api/devices/{devices['无人机-01']}/checkout", json={"node_id": n1})
    ls.post(f"{BASE}/api/nodes/{n1}/message",
            json={"text": "无人机电池只有一块，可能需要充电车支援", "kind": "feedback"})
    ls.post(f"{BASE}/api/nodes/{n1}/submit", json={"note": "航拍完成，共 42 张照片，破损点已标注"})
    zs.post(f"{BASE}/api/nodes/{n1}/review", json={"approve": True})
    r = ls.post(f"{BASE}/api/nodes/{n1}/next", json={
        "title": "航拍照片归档标注", "content": "把 42 张照片按围墙段归档，标注破损位置。",
        "criteria": "归档表提交，每张照片有区域标签",
        "deadline": "2026-09-25T18:00", "assignee_id": users["wangwu"]})
    print("showcase chain ready:", r.json())


if __name__ == "__main__":
    main()
