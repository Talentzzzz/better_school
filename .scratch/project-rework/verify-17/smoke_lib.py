# -*- coding: utf-8 -*-
"""票 17 冒烟共享工具（三服务全栈起好之后用）。

设计口径：
- 只依赖标准库（urllib）+ docker CLI（查库/改库），不装任何包。
- 登录走真实短信接口，验证码从**单体应用日志**里抓（dev 是 mock：`[SMS-mock] 向 {phone} 发送验证码：{code}`）。
- 每个冒烟脚本自带**专属手机号段**，互不干扰（见各自脚本头部）。
- 证据统一落 .scratch/project-rework/verify-17/evidence/。

用法：

    from smoke_lib import Harness
    h = Harness()
    tok, uid = h.register('13700017001', '冒烟甲')
    st, r = h.call('GET', '/api/wallet/balance', token=tok)
    h.evidence('01-balance', r)
"""
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = r"C:\Users\a1368\Desktop\better_school"
EVIDENCE_DIR = os.path.join(REPO, ".scratch", "project-rework", "verify-17", "evidence")

BS_BASE = "http://localhost:8080"
MKT_BASE = "http://localhost:8081"
TC_BASE = "http://localhost:8083"

# 三服务的标准输出日志（dev-stack.ps1 用 Start-Process 重定向到这里）
BS_LOG = os.path.join(REPO, "backend", "better-school", "logs", "dev-stack-bs.log")
MKT_LOG = os.path.join(REPO, "backend", "marketing", "logs", "dev-stack-marketing.log")
TC_LOG = os.path.join(REPO, "backend", "timeout-center", "logs", "dev-stack-center.log")

MYSQL = ["docker", "exec", "-i", "bs-mysql", "mysql", "-uroot", "-pbs123456", "--default-character-set=utf8mb4"]

PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)


def log_offset(path):
    """记录日志当前字节位置——用来只读「本次操作之后」新增的行。"""
    if not os.path.exists(path):
        return 0
    with open(path, "rb") as f:
        f.seek(0, os.SEEK_END)
        return f.tell()


def read_since(path, offset):
    with open(path, "rb") as f:
        f.seek(offset)
        return f.read().decode("utf-8", errors="replace")


def wait_until(predicate, timeout=15.0, interval=0.4, what="条件"):
    """轮询直到 predicate() 返回真值（异步链路用：MQ 消费、outbox 扫描、xxl 合并）。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(interval)
    raise AssertionError("等 %s 超时（%.0fs），最后一次结果：%r" % (what, timeout, last))


class Harness(object):
    def __init__(self, name):
        self.name = name
        os.makedirs(EVIDENCE_DIR, exist_ok=True)
        self.results = []

    # ---------------- HTTP ----------------

    def call(self, method, path, body=None, token=None, raw=None,
             ctype="application/json", base=BS_BASE, timeout=20):
        url = base + path
        data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", ctype)
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                payload = resp.read().decode("utf-8", errors="replace")
                return resp.status, self._parse(payload)
        except urllib.error.HTTPError as e:
            payload = e.read().decode("utf-8", errors="replace")
            return e.code, self._parse(payload)

    @staticmethod
    def _parse(payload):
        try:
            return json.loads(payload)
        except Exception:
            return payload

    def ok(self, method, path, body=None, token=None, base=BS_BASE, **kw):
        """断言业务码 200/0000，返回 data。"""
        st, r = self.call(method, path, body, token=token, base=base, **kw)
        code = r.get("code") if isinstance(r, dict) else None
        assert st == 200 and code in ("200", "0000"), "%s %s → HTTP %s / %s" % (method, path, st, r)
        return r.get("data")

    # ---------------- 登录（单体） ----------------

    def sms_code(self, phone, offset, tries=40):
        pattern = re.compile(re.escape(phone) + r"[^\n]*?(\d{6})")
        for _ in range(tries):
            m = pattern.findall(read_since(BS_LOG, offset))
            if m:
                return m[-1]
            time.sleep(0.25)
        raise AssertionError("单体日志里抓不到 %s 的验证码（看 %s）" % (phone, BS_LOG))

    def register(self, phone, nickname):
        off = log_offset(BS_LOG)
        self.call("POST", "/api/user/send-code", {"phone": phone})
        code = self.sms_code(phone, off)
        st, r = self.call("POST", "/api/user/register", {"phone": phone, "code": code, "nickname": nickname})
        if not isinstance(r, dict) or r.get("code") != "200":
            # 已注册过 → 改走登录
            return self.login(phone)
        return r["data"]["token"], r["data"]["userInfo"]["userId"]

    def login(self, phone):
        off = log_offset(BS_LOG)
        self.call("POST", "/api/user/send-code", {"phone": phone})
        code = self.sms_code(phone, off)
        st, r = self.call("POST", "/api/user/login", {"phone": phone, "code": code})
        assert isinstance(r, dict) and r.get("code") == "200", "登录失败：%s" % r
        return r["data"]["token"], r["data"]["userInfo"]["userId"]

    def promote_admin(self, phone):
        """提权 ADMIN 后重登（角色写在 JWT 里，必须换新 token）。

        注意列名：`bs_user` 的手机号列是 `phone`（不是 `mobile`，也不是业务键 `user_id`）。
        """
        self.sql("better_school", "UPDATE bs_user SET role='ADMIN' WHERE phone='%s'" % phone)
        return self.login(phone)

    def upload_png(self, token, filename="smoke.png"):
        boundary = "----bs" + str(int(time.time() * 1000))
        body = (("--%s\r\n" % boundary).encode()
                + ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % filename).encode()
                + b"Content-Type: image/png\r\n\r\n" + PNG_1PX
                + ("\r\n--%s--\r\n" % boundary).encode())
        return self.ok("POST", "/api/files/upload", token=token, raw=body,
                       ctype="multipart/form-data; boundary=" + boundary)

    # ---------------- 数据库 ----------------

    def sql(self, db, statement, timeout=30):
        """执行任意 SQL（含 SELECT 时返回行数组，字段以 \\t 分隔）。"""
        cmd = MYSQL + [db, "-N", "-B", "-e", statement]
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
        out = p.stdout.decode("utf-8", errors="replace").strip()
        if p.returncode != 0:
            raise AssertionError("SQL 失败：%s\n%s" % (statement, p.stderr.decode("utf-8", errors="replace")))
        return [line.split("\t") for line in out.splitlines()] if out else []

    def one(self, db, statement):
        rows = self.sql(db, statement)
        assert rows, "查询无结果：%s" % statement
        return rows[0][0]

    def count(self, db, statement):
        return int(self.one(db, statement))

    # ---------------- 证据 ----------------

    def evidence(self, label, payload):
        path = os.path.join(EVIDENCE_DIR, label + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2, default=str)
        print("  [证据] %s" % path)
        return path

    def evidence_text(self, label, text):
        path = os.path.join(EVIDENCE_DIR, label + ".txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print("  [证据] %s" % path)
        return path

    def step(self, label, detail=None):
        line = "[%s] %s" % (self.name, label)
        print(line + ((" — " + str(detail)) if detail is not None else ""))
        self.results.append({"step": label, "detail": detail})
        return detail
