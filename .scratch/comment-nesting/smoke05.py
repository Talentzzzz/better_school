# -*- coding: utf-8 -*-
"""issue 05 冒烟：评论异步审核端到端（真实 docker compose + RocketMQ broker + 8080 jar）"""
import json, random, re, time, urllib.request

BASE = 'http://localhost:8080'
LOG = 'C:/Users/a1368/Desktop/better_school/backend/logs/smoke-05.log'

def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method,
        data=json.dumps(body).encode('utf-8') if body is not None else None,
        headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read().decode('utf-8'))

def code_of(phone):
    for _ in range(30):
        with open(LOG, encoding='utf-8', errors='ignore') as f:
            text = f.read()
        for line in text.splitlines():
            if phone in line:
                m = re.search(r'验证码：(\d{6})', line)
                if m:
                    return m.group(1)
        time.sleep(0.5)
    raise RuntimeError('no code for ' + phone)

def register(phone, nick):
    call('POST', '/api/user/send-code', {'phone': phone})
    code = code_of(phone)
    resp = call('POST', '/api/user/register', {'phone': phone, 'code': code, 'nickname': nick})
    assert resp['code'] == '200', resp
    return resp['data']['token'], resp['data']['userInfo']['userId']

ok = []
def check(name, cond, detail=''):
    ok.append((name, bool(cond)))
    print(('PASS ' if cond else 'FAIL ') + name + (' | ' + str(detail) if detail else ''))

a_phone = '138%08d' % random.randint(0, 99999999)
b_phone = '139%08d' % random.randint(0, 99999999)
token_a, uid_a = register(a_phone, '冒烟帖主')
token_b, uid_b = register(b_phone, '冒烟评论人')

p = call('POST', '/api/community/posts', {'title': '异步审核冒烟帖', 'content': '评论异步审核冒烟内容', 'category': 'DAILY'}, token_a)
post_id = p['data']
check('发帖成功', p['code'] == '200' and post_id > 0)

# 1. 发布响应 = 完整 DTO
r = call('POST', '/api/community/posts/%d/comments' % post_id, {'content': '冒烟评论：第一楼'}, token_b)
dto = r.get('data') or {}
check('发布响应含完整 DTO', r['code'] == '200' and dto.get('commentId', 0) > 0
      and dto.get('postId') == post_id and dto.get('userId') == uid_b
      and dto.get('nickname') == '冒烟评论人' and dto.get('replyCount') == 0 and dto.get('content') == '冒烟评论：第一楼', dto)
comment_id = dto.get('commentId')

# 2. 发布后立即可见数 0（零同步审核）
guest0 = call('GET', '/api/community/posts/%d/comments?page=1&pageSize=10' % post_id)
check('发布后他人可见 0 条', guest0['data']['total'] == 0, guest0['data']['total'])

# 3. 异步审核通过：轮询可见
visible = False
for _ in range(30):
    guest = call('GET', '/api/community/posts/%d/comments?page=1&pageSize=10' % post_id)
    if guest['data']['total'] == 1 and guest['data']['list'][0]['commentId'] == comment_id:
        visible = True
        break
    time.sleep(1)
check('异步审核通过后他人可见', visible)

detail = call('GET', '/api/community/posts/%d' % post_id)
check('帖子评论总数 +1', detail['data']['commentCount'] == 1, detail['data']['commentCount'])

# 4. 敏感词评论：发布成功（不受敏感词影响）→ 异步驳回
r2 = call('POST', '/api/community/posts/%d/comments' % post_id, {'content': '出售作弊神器'}, token_b)
dto2 = r2.get('data') or {}
check('敏感词评论发布成功且响应含 DTO', r2['code'] == '200' and dto2.get('commentId', 0) > 0)
rejected_id = dto2.get('commentId')

gone, stayed = False, True
for _ in range(30):
    guest = call('GET', '/api/community/posts/%d/comments?page=1&pageSize=10' % post_id, None)
    mine = call('GET', '/api/community/posts/%d/comments?page=1&pageSize=10' % post_id, token_b)
    ids = [c['commentId'] for c in guest['data']['list']]
    mine_ids = [c['commentId'] for c in mine['data']['list']]
    if rejected_id not in ids and rejected_id not in mine_ids and ids == [comment_id]:
        gone = True
        break
    time.sleep(1)
check('驳回后对所有人（含作者）消失', gone)

detail2 = call('GET', '/api/community/posts/%d' % post_id)
check('驳回不改计数', detail2['data']['commentCount'] == 1, detail2['data']['commentCount'])

# 5. 通知（DB 断言）
time.sleep(2)
import subprocess
def db(sql):
    out = subprocess.run(['docker', 'exec', 'bs-mysql', 'mysql', '-uroot', '-pbs123456', 'better_school',
                          '-N', '-e', sql], capture_output=True, text=True)
    return out.stdout.strip()
n_owner = db("SELECT COUNT(*) FROM notification WHERE user_id=%d AND type='POST_COMMENTED'" % uid_a)
check('帖主收帖被评通知', n_owner == '1', n_owner)
n_author_reject = db("SELECT COUNT(*) FROM notification WHERE user_id=%d AND type='AUDIT_RESULT'" % uid_b)
check('作者收一条驳回通知', n_author_reject == '1', n_author_reject)

print('---')
print('SMOKE %s' % ('ALL PASS' if all(c for _, c in ok) else 'HAS FAILURES'))
