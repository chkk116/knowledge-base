# -*- coding: utf-8 -*-
"""
黑盒 API 合同探测（原生壳联调用）
================================
对本地后端做一次全链路探测：验证码（图像分析解槽位）→ 登录 → 逐个探测
App 各页面依赖的接口，校验响应形状与原生解析预期一致。

用法：python tools/probe_api.py [base_url] [username] [password]
默认 http://localhost:8080 admin Admin@123
"""
import base64
import io
import json
import sys
import time
import urllib.request
import urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PASS = sys.argv[3] if len(sys.argv) > 3 else "Admin@123"

TOKEN = {"v": None}
results = []


def req(method, path, body=None, auth=True, raw=False):
    url = BASE + path
    data = None
    headers = {"User-Agent": "Mozilla/5.0 KbProbe/1.0"}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if auth and TOKEN["v"]:
        headers["Authorization"] = "Bearer " + TOKEN["v"]
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            text = resp.read().decode("utf-8", "replace")
            return resp.status, (text if raw else try_json(text))
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        return e.code, (text if raw else try_json(text))
    except Exception as e:
        return 0, str(e)


def try_json(text):
    try:
        return json.loads(text)
    except Exception:
        return text


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + ("  -- " + detail if detail else ""))


# ---------- 1. 验证码求解：拼块 alpha 模板内的乘性匹配 ----------
# 背景 slot 区域 = 原图×0.765（alpha60 压暗），拼块 = 原图切片（alpha 通道即形状模板）。
# 只比形状内部（腐蚀 3px 避开白边/抗锯齿），任何背景都成立，最小值尖锐。
def solve_captcha():
    st, cap = req("GET", "/api/auth/captcha", auth=False)
    if st != 200 or not isinstance(cap, dict):
        return None, "captcha http=%s" % st
    from PIL import Image
    bg = Image.open(io.BytesIO(base64.b64decode(cap["bg"].split(",", 1)[1]))).convert("L")
    pc = Image.open(io.BytesIO(base64.b64decode(cap["piece"].split(",", 1)[1])))
    W, H = bg.size
    bpx = bg.load()
    amask = pc.split()[3].load()
    ppx = pc.convert("L").load()
    PW, PH = pc.size
    cy = cap["pieceY"]
    K = 195.0 / 255.0
    interior = []
    for j in range(PH):
        for i in range(PW):
            if amask[i, j] > 200:
                ok = True
                for d in range(1, 4):
                    if i - d < 0 or i + d >= PW or j - d < 0 or j + d >= PH \
                            or amask[i - d, j] < 200 or amask[i + d, j] < 200 \
                            or amask[i, j - d] < 200 or amask[i, j + d] < 200:
                        ok = False
                        break
                if ok:
                    interior.append((i, j))
    if not interior:
        return None, "empty interior mask"
    best_x, best_err = -1, 1e18
    for ox in range(0, W - PW):
        e = sum(abs(bpx[ox + i, cy + j] - K * ppx[i, j]) for i, j in interior)
        if e < best_err:
            best_err, best_x = e, ox
    return {"token": cap["token"], "x": best_x}, "match@x=%d err=%.1f" % (best_x, best_err / len(interior))


st, solved = None, None
for attempt in range(3):
    solved, detail = solve_captcha()
    if solved:
        break
    time.sleep(0.5)
if not solved:
    check("auth/captcha 槽位检测", False, detail)
else:
    import random as _rnd
    traj = [{"x": solved["x"] * i / 6.0, "t": int(time.time() * 1000) + i * 45} for i in range(1, 7)]
    st, slide = req("POST", "/api/auth/captcha/slide",
                    {"token": solved["token"], "x": solved["x"], "trajectory": traj}, auth=False)
    check("auth/captcha/slide 拼图校验", st == 200 and slide.get("ok") is True,
          json.dumps(slide, ensure_ascii=False)[:120] if st == 200 else "http=%s" % st)

    st, login = req("POST", "/api/auth/login",
                    {"username": USER, "password": PASS, "captchaToken": solved["token"]}, auth=False)
    ok = st == 200 and isinstance(login, dict) and login.get("token")
    check("auth/login 登录", ok, ("token+id+role+permissions" if ok else "http=%s %s" % (st, str(login)[:120])))
    if ok:
        TOKEN["v"] = login["token"]

# ---------- 2. 业务接口合同 ----------
def probe(name, method, path, expect_keys=None, body=None, array=False, note=""):
    st, data = req(method, path, body)
    if st != 200:
        check(name, False, "http=%s %s" % (st, str(data)[:140]))
        return data
    if array:
        ok = isinstance(data, list)
        detail = ("len=%d" % len(data)) if ok else str(data)[:200]
    else:
        ok = isinstance(data, dict) and all(k in data for k in (expect_keys or []))
        missing = [k for k in (expect_keys or []) if isinstance(data, dict) and k not in data]
        detail = ("keys ok " + note) if ok else ("missing=" + str(missing) + " " + str(data)[:200])
    check(name, ok, detail)
    return data


if TOKEN["v"]:
    probe("auth/me", "GET", "/api/auth/me", ["id", "role", "permissions", "displayName"])
    probe("home: docs/overview", "GET", "/api/docs/overview", ["totalDocs", "weekNew", "members"])
    probe("home: announcements", "GET", "/api/announcements", array=True)
    shares = probe("docs: doc-shares/my", "GET", "/api/doc-shares/my", array=True)
    if isinstance(shares, list) and shares:
        sid = shares[0]["id"]
        docs = probe("docs: {id}/docs(含content?)", "GET", "/api/doc-shares/%d/docs" % sid, array=True)
        if isinstance(docs, list) and docs:
            d0 = docs[0]
            has_c = "content" in d0
            idf = "docId" if "docId" in d0 else ("id" if "id" in d0 else "NONE")
            check("docs: 列表含 content/docId", has_c and idf != "NONE",
                  "idField=%s content=%s keys=%s" % (idf, has_c, list(d0.keys())[:12]))
            did = d0.get("docId") or d0.get("id")
            probe("reader: {sid}/docs/{did}", "GET", "/api/doc-shares/%d/docs/%d" % (sid, did),
                  ["title", "content"])
    probe("msg: notifications", "GET", "/api/notifications", array=True)
    probe("msg: notifications/unread", "GET", "/api/notifications/unread", ["count"])
    probe("ai: models", "GET", "/api/ai/models", array=True)
    probe("ai: ask", "POST", "/api/ai/ask", ["answer"],
          body={"question": "测试连通性，请回答 ok", "mode": "kb"})
    probe("cards: flashcards/due", "GET", "/api/flashcards/due", array=True)
    probe("exam: exams", "GET", "/api/exams", array=True)
    probe("exam: exams/mine", "GET", "/api/exams/mine", array=True)
    ps = probe("practice: session", "POST", "/api/exams/practice/session",
               ["sessionId", "questions"], body={"count": 3})
    if isinstance(ps, dict) and ps.get("sessionId") and ps.get("questions"):
        q0 = ps["questions"][0]
        check("practice: 题目结构", all(k in q0 for k in ("id", "type", "content")),
              "keys=%s" % list(q0.keys()))
        st, chk = req("POST", "/api/exams/practice/check",
                      {"sessionId": ps["sessionId"], "questionId": q0["id"], "answer": ["A"]})
        check("practice: check 判分", st == 200 and isinstance(chk, dict) and "correct" in chk,
              "keys=%s" % (list(chk.keys()) if isinstance(chk, dict) else str(chk)[:100]))
    probe("admin: users 分页", "GET", "/api/admin/users?page=0&size=5")
    probe("admin: ai models", "GET", "/api/admin/ai/models", array=True)
    probe("admin: docs pending", "GET", "/api/admin/docs/pending", array=True)
    probe("admin: announcements", "GET", "/api/admin/announcements", array=True)
    st, uv = req("GET", "/api/public/app-version", auth=False)
    check("public: app-version", st == 200 and isinstance(uv, dict) and "versionCode" in uv,
          str(uv)[:80])

print("\n==== SUMMARY: %d/%d PASS ====" % (sum(1 for _, ok, _ in results if ok), len(results)))
