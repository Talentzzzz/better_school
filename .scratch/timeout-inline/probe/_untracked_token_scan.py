"""扫描未跟踪文件里的旧 token（排除 .scratch/.git/target/node_modules/logs/docker data）。"""
import io
import os
import re
import subprocess
import sys

ROOT = r"C:\Users\a1368\Desktop\better_school"
PATTERNS = [
    re.compile("timeout-center"),
    re.compile("timeout_register_outbox"),
    re.compile("callback_group"),
    re.compile(r"ITimeoutTaskCallback\b"),
    re.compile("bs_timeout"),
]
EXTS = {".md", ".java", ".xml", ".yml", ".yaml", ".ps1", ".sh", ".sql", ".py"}
SKIP_DIRS = {".git", ".scratch", "target", "node_modules", "logs", "data"}


def main():
    output = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT)
    files = []
    for line in output.decode("utf-8", errors="replace").splitlines():
        if not line.startswith("?? "):
            continue
        path = line[3:].strip()
        if path.startswith('"') and path.endswith('"'):
            path = path[1:-1]
        full = os.path.join(ROOT, path)
        if not os.path.isfile(full):
            continue
        if os.path.splitext(full)[1].lower() not in EXTS:
            continue
        if any(part in SKIP_DIRS for part in path.replace("\\", "/").split("/")):
            continue
        files.append(full)

    hits = 0
    for full in files:
        try:
            text = io.open(full, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            for pattern in PATTERNS:
                if pattern.search(line):
                    print(f"{os.path.relpath(full, ROOT)}:{number}: {line.strip()}")
                    hits += 1
    print(f"\n扫描未跟踪文件 {len(files)} 个，命中 {hits} 处")
    return 0 if hits == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
