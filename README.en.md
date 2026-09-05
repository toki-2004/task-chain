[中文](README.md) | English

# Task Chain

A **full-lifecycle task system** for "client ⇄ vendor" or "manager ⇄ subordinate" collaboration: publish → check out equipment → execute → submit → review → multi-node relay → terminate, with a complete audit trail.

Works as a web app (usable directly in the phone browser, nothing to install) and an Android app (WebView shell), served by one backend.

## Screenshots

| Login | Task detail (prerequisite equipment + actions) |
| --- | --- |
| ![Login](docs/screenshots/login.png) | ![Task detail](docs/screenshots/task-detail.png) |
| **Chain & full audit timeline** | **Admin console · devices** |
| ![Timeline](docs/screenshots/chain-timeline.png) | ![Admin](docs/screenshots/admin-devices.png) |

## Features

- **Task publishing**: title, description, image/video attachments, deadline, assignee, completion criteria.
- **Prerequisites**:
  - Prerequisite tasks (multiple allowed): submission is locked until they are approved; one tap jumps to the prerequisite's page; if you are its assignee, that page is your working page.
  - Prerequisite equipment: set by the publisher, optionally with penalty/handling terms and a hyperlink to the contract or regulation (one-tap jump from the detail page). The prerequisite text shows **black** when the equipment is free and **grey** when it is occupied.
- **Equipment management**: register equipment in the admin console; assignees **manually check out / return** equipment in the app; the system records who currently holds it and for which task; full custody history on the device page; admins can force-release.
- **Assignee actions**: send feedback to the publisher, appeal unreasonable requirements (publisher can reply and accept/reject), upload completion evidence (images/videos), submit for review.
- **Review**: submissions enter the pending-review list, reviewed by the **node creator** (approve, or reject with a mandatory reason; rejected tasks can be re-submitted).
- **Multi-node task chains**: once a node is approved, its **completer** creates the next node and assigns the next person; the chain initiator sees everything.
- **Termination**: any assignee may apply to terminate (double confirmation), reviewed by the **chain initiator**; the initiator can terminate directly. Termination ends the whole chain and is irreversible.
- **Home page**: everyone gets "Unfinished / Pending review / Completed" lists with badge counts, sorted by deadline (overdue highlighted in red); "Me" holds identity info and "My publications".
- **Full audit trail**: every node's assignee and creator, plus a timeline of create/checkout/feedback/appeal/submit/review/termination/equipment events.

## Quick start (LAN, 3 minutes)

1. On the PC, double-click `start_server.bat` (first run creates a venv and installs deps; requires Python 3.10+).
2. The default admin account is created automatically: **admin / admin123** (change it right away).
3. Log in as admin → "Me → Admin console" → register equipment, create user accounts.
4. On the phone (same Wi-Fi), open the LAN URL shown in the server window (a QR code is printed too), or install the Android app and enter the same address on first launch.
5. Optional: `python seed_demo.py` seeds demo users (zhangsan/lisi/wangwu, password 123456) and two demo devices.

## Android app

`android/` is a plain WebView shell project (no third-party dependencies):

- enter the server URL on first launch; cookies persist between runs;
- system photo/file picker for image/video uploads;
- menu items to switch server address and clear login state.

To build: install the Android SDK (`platforms;android-34`) and Gradle 8.7, then run in `android/`:

```
gradle assembleDebug
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`; sideload it on your phone.

## Business rules (design decisions, adjustable)

| Item | Rule |
| --- | --- |
| Prerequisite tasks | Must reference nodes of **other chains** (same-chain and cyclic references are rejected server-side); unlock submission when approved |
| Prerequisite equipment | The assignee must **check out and hold** the equipment to submit; terminating a chain does **not** auto-release equipment |
| Review rights | Each node is reviewed by its **creator**; node 1's creator is the chain initiator |
| Next-node creation | The current node's assignee (completer), after approval |
| Termination rights | Chain initiator (direct) + any node assignee of the chain (needs initiator review) |
| Visibility | Chain initiator and node creators/assignees of a chain; admins see everything |
| Accounts | Created by admins only (username + password), no self-registration |

## Project layout

```
task-chain/
├── start_server.bat        # one-click server launcher (Windows)
├── run_server.py           # entry point (prints LAN URL + QR code)
├── seed_demo.py            # optional demo users/devices
├── test_api.py             # end-to-end API smoke test (72 assertions)
├── requirements.txt
├── app/                    # FastAPI backend
├── static/                 # SPA frontend (vanilla HTML/CSS/JS, no build step)
├── android/                # Android WebView shell project
└── tunnel/                 # frp tunnel templates & guide
```

## Tunneling (access from outside the LAN)

See [tunnel/README.md](tunnel/README.md): frp (your own VPS), Tailscale/ZeroTier networking, or commercial solutions.

**Security reminder** before exposing to the public internet: change the admin password and all weak passwords, and put the service behind an HTTPS reverse proxy.

## Development & testing

```
pip install -r requirements.txt requests
python -m uvicorn app.main:app --port 8000
python test_api.py        # delete data.db and run seed_demo.py first
```

## Known limitations / roadmap

- Attachments are stored on local disk; max 200MB per video.
- Notifications are in-app badges with 30s polling; no push notifications yet.
- Passwords are PBKDF2-hashed; no built-in HTTPS (put behind a reverse proxy in production).

## License

MIT
