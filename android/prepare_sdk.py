# -*- coding: utf-8 -*-
"""一次性准备 Android 构建环境：下载 cmdline-tools 与 Gradle 并解压。"""
import os
import subprocess
import sys
import zipfile

D = "D:/android-sdk"
G = "D:/gradle-8.7"


def download(url, out):
    if os.path.exists(out) and os.path.getsize(out) > 1024 * 1024:
        print(f"[skip] {out} exists")
        return
    print(f"[down] {url}")
    r = subprocess.run(["curl", "-L", "--retry", "3", "--connect-timeout", "20",
                        "-o", out, url])
    if r.returncode != 0:
        raise SystemExit(f"download failed: {url}")


def unzip(src, dest):
    os.makedirs(dest, exist_ok=True)
    print(f"[unzip] {src} -> {dest}")
    with zipfile.ZipFile(src) as z:
        z.extractall(dest)


def main():
    os.makedirs(D, exist_ok=True)
    zip_tools = os.path.join(D, "cmdline-tools.zip")
    zip_gradle = "D:/gradle-8.7-bin.zip"
    download("https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip", zip_tools)
    download("https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip", zip_gradle)

    latest = os.path.join(D, "cmdline-tools", "latest")
    if not os.path.exists(os.path.join(latest, "bin", "sdkmanager.bat")):
        unzip(zip_tools, os.path.join(D, "cmdline-tools"))
        inner = os.path.join(D, "cmdline-tools", "cmdline-tools")
        if os.path.exists(inner) and not os.path.exists(latest):
            os.rename(inner, latest)
    if not os.path.exists(os.path.join(G, "bin", "gradle.bat")):
        unzip(zip_gradle, "D:/")
    print("[ok] tools ready")
    print("cmdline-tools:", os.path.exists(os.path.join(latest, "bin", "sdkmanager.bat")))
    print("gradle:", os.path.exists(os.path.join(G, "bin", "gradle.bat")))


if __name__ == "__main__":
    if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
        sys.stdout.reconfigure(encoding="utf-8")
    main()
