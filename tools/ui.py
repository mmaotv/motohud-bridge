"""ADB UI 辅助：dump 当前界面并列出可点元素（设备真实坐标）。

用法：
  python tools/ui.py dump            # 列出所有带文字/可点的元素
  python tools/ui.py find 导航        # 精确匹配文字，输出中心点 x y
"""
import os
import re
import subprocess
import sys

DEV = os.environ.get("ADB_DEV", "192.168.10.238:5555")
TMP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "test_shots", "ui.xml")


def sh(args):
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    return subprocess.run(["adb", "-s", DEV] + args, capture_output=True, text=True, env=env).stdout


def dump():
    os.makedirs(os.path.dirname(os.path.abspath(TMP)), exist_ok=True)
    sh(["shell", "uiautomator", "dump", "/sdcard/ui.xml"])
    sh(["pull", "/sdcard/ui.xml", os.path.abspath(TMP)])
    with open(TMP, encoding="utf-8") as f:
        return f.read()


def nodes(xml):
    out = []
    for m in re.finditer(r"<node[^>]*>", xml):
        t = m.group(0)
        txt = re.search(r'text="([^"]*)"', t)
        rid = re.search(r'resource-id="([^"]*)"', t)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        desc = re.search(r'content-desc="([^"]*)"', t)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        out.append({
            "text": txt.group(1) if txt else "",
            "desc": desc.group(1) if desc else "",
            "id": rid.group(1) if rid else "",
            "cx": (x1 + x2) // 2,
            "cy": (y1 + y2) // 2,
            "bounds": (x1, y1, x2, y2),
            "clickable": 'clickable="true"' in t,
        })
    return out


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    ns = nodes(dump())
    if cmd == "dump":
        for n in ns:
            if n["text"] or n["desc"] or n["clickable"]:
                label = n["text"] or n["desc"] or n["id"]
                if label:
                    print(f'{n["cx"]:4d},{n["cy"]:4d}  {label}')
    elif cmd == "find":
        key = sys.argv[2]
        for n in ns:
            if key in (n["text"] or "") or key in (n["desc"] or ""):
                print(n["cx"], n["cy"])
                return
        print("not found")


if __name__ == "__main__":
    main()
