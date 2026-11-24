# -*- coding: utf-8 -*-
"""
better-school 演示数据一键灌入（作品演示用，可重复执行）。

前提：backend/scripts/dev-stack.ps1 up 已把全栈拉起（better-school 在 8080）。

规模（约为原始手工版的 10 倍以上，重复执行会继续追加）：
  - 12 个演示用户 + 1 个已存在的本人号（phone 见 USERS）
  - ~190 篇帖子 / ~160 件商品
  - 每帖 3~6 条评论 + 大量楼中楼回复，评论点赞/帖子点赞/商品收藏全量铺开
  - 配图全部是按关键词从 Wikimedia Commons 拉取的**真实照片**（磁盘缓存，
    缺图时用 picsum.photos 真实图库兜底），经 /api/files/upload 真传 MinIO

流程：
  1. 注册/登录演示用户
  2. 钱包充值（商品交易托管用）
  3. 发布帖子/商品（发布即对外可见，无需审批）
  4. 造互动：点赞、收藏、评论、楼中楼、评论点赞

用法：
  python backend/scripts/seed-demo.py            # 全量
  python backend/scripts/seed-demo.py --skip-users   # 用户已在，只补内容
"""
import json
import os
import random
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.error

BASE = 'http://localhost:8080'
LOG = r'C:\Users\a1368\Desktop\better_school\backend\better-school\logs\dev-stack-bs.log'
IMG_DIR = r'C:\Users\a1368\AppData\Local\Temp\bsdemo-real'
UA = 'better-school-demo/1.0 (campus marketplace demo; contact demo@example.com)'

USERS = [
    # phone, nickname（新号则注册，老号直接登录）
    ('13800002001', '林间有鹿'),
    ('13800002002', '南区快递侠'),
    ('13800002003', '学长小陈'),
    ('13800002004', '桃子不甜'),
    ('13800002005', '阿伟不秃'),
    ('13800002006', '夜跑团团长'),
    ('13800002007', '奶茶续命中'),
    ('13800002008', '图书馆钉子户'),
    ('13800002009', '早八战神'),
    ('13800002010', '干饭第一名'),
    ('13800002011', '西门探索者'),
    ('13800002012', '雨天才下雨'),
]
ME = ('13683639985', '用户1368')


def api(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json; charset=utf-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode('utf-8'))
        except Exception:
            return {'code': str(e.code), 'info': 'HTTP ' + str(e.code), 'data': None}


# ---------------- 真实图片：按关键词从 Wikimedia Commons 拉取，磁盘缓存 ----------------

def _http_json(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode('utf-8'))


TITLE_BLACKLIST = ['painting', 'drawing', 'illustration', 'poster', 'logo', 'coat of arms',
                   'statue', 'sculpture', 'engraving', 'sketch', 'map of', 'diagram',
                   'portrait of', 'self-portrait', 'oil on canvas', 'woodblock']


def _commons_urls(keyword):
    """关键词搜真实照片：先限定 P31=photograph，再退回普通搜索 + 标题黑名单。"""
    def search(expr):
        q = urllib.parse.urlencode({
            'action': 'query', 'generator': 'search',
            'gsrsearch': 'filetype:bitmap ' + expr,
            'gsrnamespace': '6', 'gsrlimit': '8',
            'prop': 'imageinfo', 'iiprop': 'url|mime', 'iiurlwidth': '640', 'format': 'json'})
        try:
            data = _http_json('https://commons.wikimedia.org/w/api.php?' + q)
        except Exception as e:
            print('    [warn] commons 搜索失败 %s: %s' % (keyword, e))
            return []
        pages = sorted((data.get('query', {}).get('pages') or {}).values(),
                       key=lambda p: p.get('index', 99))
        out = []
        for p in pages:
            info = p.get('imageinfo')
            if not info or info[0].get('mime') != 'image/jpeg':
                continue
            title = p.get('title', '').lower()
            if any(bad in title for bad in TITLE_BLACKLIST):
                continue
            out.append(info[0]['thumburl'])
        return out

    urls = search('haswbstatement:P31=Q125191 ' + keyword)
    if not urls:
        urls = search(keyword)
    return urls


def ensure_images(keyword, want=2):
    """返回该关键词已缓存的图片路径列表（尽量 want 张）。只抓真实照片（JPEG）。"""
    safe = re.sub(r'[^a-z0-9]+', '_', keyword.lower()).strip('_') or 'kw'
    os.makedirs(IMG_DIR, exist_ok=True)
    have = [IMG_DIR + '\\' + '%s_%d.jpg' % (safe, i) for i in range(want)]
    if all(os.path.exists(p) for p in have):
        return have
    urls = _commons_urls(keyword)
    got = 0
    for u in urls:
        if got >= want:
            break
        p = IMG_DIR + '\\' + '%s_%d.jpg' % (safe, got)
        if os.path.exists(p):
            got += 1
            continue
        try:
            req = urllib.request.Request(u, headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=30) as r, open(p, 'wb') as f:
                f.write(r.read(4_000_000))
            got += 1
        except Exception:
            continue
    while got < want:  # 兜底：picsum 也是真实摄影图
        p = IMG_DIR + '\\' + '%s_%d.jpg' % (safe, got)
        try:
            req = urllib.request.Request(
                'https://picsum.photos/seed/%s%d/640/480' % (safe, got), headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=30) as r, open(p, 'wb') as f:
                f.write(r.read(4_000_000))
            got += 1
        except Exception:
            break
    return have[:got]


def upload_real(token, keyword, variant):
    paths = ensure_images(keyword)
    if not paths:
        return None
    path = paths[variant % len(paths)]
    boundary = '----seeddemo' + str(int(time.time() * 1000000))
    with open(path, 'rb') as f:
        content = f.read()
    body = (
        ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\n'
         'Content-Type: image/jpeg\r\n\r\n' % (boundary, os.path.basename(path))).encode('utf-8')
        + content + ('\r\n--%s--\r\n' % boundary).encode('utf-8'))
    req = urllib.request.Request(BASE + '/api/files/upload', data=body, method='POST')
    req.add_header('Content-Type', 'multipart/form-data; boundary=' + boundary)
    req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, timeout=30) as resp:
        out = json.loads(resp.read().decode('utf-8'))
    if out.get('code') != '200':
        raise RuntimeError('upload %s failed: %s' % (keyword, out))
    return out['data']['url']


def latest_code(phone):
    time.sleep(0.6)
    with open(LOG, 'r', encoding='utf-8', errors='ignore') as f:
        text = f.read()
    hits = re.findall(u'向 %s 发送验证码：(\\d{6})' % phone, text)
    if not hits:
        raise RuntimeError('日志里没找到 %s 的验证码' % phone)
    return hits[-1]


def register_or_login(phone, nickname):
    """注册失败（已注册）就登录。返回 (token, created)"""
    r = api('POST', '/api/user/send-code', body={'phone': phone})
    assert r.get('code') == '200', 'send-code 失败: %s' % r
    code = latest_code(phone)
    r = api('POST', '/api/user/register',
            body={'phone': phone, 'code': code, 'nickname': nickname})
    if r.get('code') == '200':
        print('  注册 %s %s (uid=%s)' % (phone, nickname, r['data']['userInfo']['userId']))
        return r['data']['token'], True
    # 已注册时上面的 register 尝试已把验证码消费掉，登录要重新取码
    api('POST', '/api/user/send-code', body={'phone': phone})
    code = latest_code(phone)
    r = api('POST', '/api/user/login', body={'phone': phone, 'code': code})
    assert r.get('code') == '200', 'login %s 失败: %s' % (phone, r)
    print('  登录 %s %s (uid=%s)' % (phone, nickname, r['data']['userInfo']['userId']))
    return r['data']['token'], False


# ---------------- 内容池 ----------------

# 手工精选帖（image keyword, category, title, content）
HAND_POSTS = [
    ('autumn campus', 'DAILY', '校园的秋天真的来了',
     '今天路过图书馆后面那条路，银杏叶铺了一地，随手拍了一张。\n一年中最舒服的两周，大家下课多出去走走～'),
    ('orange cat', 'DAILY', '图书馆门口的小猫越来越圆了',
     '那只橘猫最近被喂得明显圆了一圈，今天窝在台阶上晒太阳，摸起来像暖水袋。\n投喂的同学记得顺手带走餐盒呀。'),
    ('student desk', 'LOST_FOUND', '寻物启事：黑色保温杯丢在三教201',
     '周三下午三四节在三教201上完课，杯子落教室了，杯身有校运会纪念贴纸。\n有捡到的同学联系我，奶茶答谢！'),
    ('students studying', 'ASK_HELP', '求高数第四章极限习题讲解',
     '期中快到了，第四章夹逼和两个重要极限还是迷迷糊糊。\n有没有大佬愿意讲讲，可以付费请教或者帮你带一周早饭orz'),
    ('running track', 'DAILY', '晚自习后的操场，风很舒服',
     '十点下自习去跑了三圈，人不多，耳机里放着歌，突然觉得大学生活也没那么赶。\n明天继续。'),
    ('noodles bowl', 'DAILY', '二食堂新开的麻辣香锅，实名推荐',
     '二楼拐角新开的窗口，称重计价，牛肉丸给得很实在。\n中辣刚好，米饭管够，人均13块。晚高峰要排队，建议错峰。'),
    (None, 'LOST_FOUND', None,
     '捡到一张校园卡，姓氏里带“晓”，已交到南区宿管站，失主带学生证去认领即可。'),
    ('library interior', 'DAILY', '三教四楼西侧是自习宝藏位置',
     '人比一楼大厅少一半，插座管够，靠窗还有夕阳。\n唯一缺点是热水机在三楼，打水要跑一层。'),
    (None, 'ASK_HELP', None,
     '大二想找暑期实习，简历投了十几家都没回音。\n有学长学姐愿意帮忙看看简历吗？可以请咖啡，万分感谢！'),
    ('night market food', 'DAILY', '北门夜市第3家烤冷面，加蛋加肠12块',
     '老板娘手不抖，酱给得足。周四去人最少不用排队。\n旁边那家炸串踩雷，又贵又咸，避雷。'),
    ('campus rain', 'DAILY', '下雨天教学楼之间的连廊路线分享',
     '一教→二教→图书馆全程有连廊，基本不用打伞。\n暴雨天亲测有效，就是二教拐角人多，注意错峰。'),
    (None, 'LOST_FOUND', None,
     '昨晚在二食堂二楼吃饭，蓝牙耳机（白色充电仓，贴了星星贴纸）落餐桌了。\n有同学捡到请留言，重谢！'),
    ('train station', 'ASK_HELP', '周六上午拼车去高铁站，还差2人',
     '周六上午9点北区门口出发，人均15左右，行李多也不怕。\n拼上的同学在评论区留联系方式，我拉群。'),
    ('dorm room', 'DAILY', '宿舍改造好物清单（亲测版）',
     '床帘遮光布、免打孔置物架、显示器增高架，三件套下来一百出头。\n宿舍幸福感直接翻倍，链接我整理在评论区。'),
    ('morning jog park', 'DAILY', '晨跑打卡第7天，樱花道早上六点半没人',
     '空气里全是草味，跑完去东食堂吃了头炉包子。\n坚持一周了，感觉白天精神好很多，组队晨跑的扣1。'),
    (None, 'LOST_FOUND', '钥匙丢了！挂着小熊挂件的一串',
     '大概丢在篮球场到二食堂这一路，钥匙上有小熊挂件和三个银色钥匙扣。\n捡到的同学球球联系我，配钥匙+门禁卡要两百多呜呜。'),
    ('basketball court', 'DAILY', '校运会开始报名了，4x100还缺两人',
     '我们院接力队还缺两个男生（女生也可以报混合组）。\n不为名次，就为班费报销的队服哈哈。想跑的评论区见。'),
    ('english textbook', 'ASK_HELP', '四级听力总是走神，求练法',
     '做真题的时候听两道题就开始想中午吃什么……\n有没有过来人分享下听力提分方法，精听到底怎么做啊？'),
]

# 日常话题：12 个食堂窗口 / 8 家奶茶 / 10 个随手拍场景 / 宿舍+天气+周边等
CANTEEN = ['麻辣香锅', '兰州拉面', '黄焖鸡米饭', '铁板烧', '石锅拌饭', '重庆小面',
           '港式烧腊', '自选快餐', '炸鸡汉堡', '韩式拌饭', '猪肚鸡', '螺蛳粉']
CANTEEN_NOTE = [
    '称重计价很实在，人均13左右。', '汤头是真熬出来的，不是粉冲的。',
    '分量给得足，米饭管够。', '晚高峰要排队，建议错峰去。',
    '辣度可以自己选，微辣刚刚好。', '阿姨手不抖，勺勺都是肉。',
]
TEA_SHOPS = ['古茗', '蜜雪冰城', '茶百道', '一点点', '沪上阿姨', '瑞幸', '库迪', '霸王茶姬']
TEA_NOTE = [
    '新出的轻乳茶意外好喝，三分糖正合适。', '学生证有折扣，性价比起飞。',
    '下午三点后基本不用排队。', '加料建议只加一种，多了就串味。',
    '冰的比热的好喝，亲测。', ' 点单小程序上有券，记得先领。',
]
SCENES = ['图书馆前的草坪', '教学楼天台', '樱花道', '南门夜市', '小西湖',
          '操场看台', '银杏道', '体育馆外场', '东门早餐摊', '后山小路']
SCENE_NOTE = [
    '下午五点的光线最适合拍照。', '早上人少，安静得能听到鸟叫。',
    '随手一拍就是壁纸，推荐都去看看。', '周末人多，工作日去体验最好。',
    '带杯咖啡坐一下午都不会腻。', '秋天这个时候去，是一年里最好看的时候。',
]
DAILY_MISC = [
    ('今天降温成功把我的秋裤逼出来了', '早晨的风已经带刀了，骑车必须戴手套。\n各位注意保暖，感冒了期末周很难受的。', 'campus winter'),
    ('昨晚的晚霞全员出片', '宿舍楼顶拍的，天是橘子汽水色。\n这种时候总觉得大学生活真好看。', 'sunset sky'),
    ('快递站中午排队20分钟，建议大家错峰', '实测上午10点和晚上8点几乎不用等。\n驿站新规取件码要截图，别像我一样白跑。', 'parcel boxes'),
    ('图书馆闭馆音乐居然是生日快乐歌', '每天十点准时响，全馆人齐刷刷收拾东西还挺壮观的。\n有知道为什么的吗？', 'library interior'),
    ('宿舍洗衣机终于换新的了', '老那台脱水像飞机起飞的机器光荣退休。\n新机器扫码就能用，一次3块。', 'washing machine'),
    ('一场秋雨一场寒，骑车通勤的注意了', '南门那段路积水严重，绕行东区比较好。\n别问我怎么知道的，鞋袜全湿。', 'campus rain'),
    ('周三晚上的露天电影，人比想象的多', '放的是老片，但氛围拉满。\n建议自带小马扎，草地蚊子也多，防蚊喷雾必备。', 'outdoor cinema'),
    ('西区新开的自习室体验报告', '单人桌+护眼灯+免费茶水，一天15。\n唯一的缺点是要提前一天预约。', 'study room'),
    ('南门那家理发店的Tony老师换人了', '新师傅认真多了，会问你要留多长。\n学生价25，比校门口那家强。', 'barber shop'),
    ('早八人的命也是命', '八点的课七点五十还在排队买包子。\n建议包子铺开个早八专属窗口。', 'breakfast street'),
    ('操场夜跑注意事项（血的教训）', '逆时针跑别变道，弯道超车先出声。\n穿浅色衣服，深色的在夜里真的看不见。', 'running track night'),
    ('学校后山的日落绝了', '爬二十分钟就到观景台，人很少。\n记得天黑前下来，路上没灯。', 'mountain sunset'),
    ('期末周的图书馆，一座难求', '早上八点半到就已经坐了一半。\n三楼研讨间可以预约，小组复习的好去处。', 'library study'),
    ('雨后的操场有青草味', '跑了两圈就下雨了，躲在看台看完整场雨。\n意外的治愈时间。', 'rain field'),
    ('校园里的猫界关系图持续更新', '橘猫和大白上周打了一架，今天又一起晒太阳。\n猫的心思别猜。', 'street cat'),
    ('超市新品测评：饭团三连', '海苔肉松款最好吃，金枪鱼款踩雷。\n微波炉叮45秒口感最佳。', 'rice ball'),
    ('公告栏上的考研上岸喜报看麻了', '祝所有刷到这条的同学明年也能上榜。\n先去背单词了。', 'notice board'),
    ('学校新装的直饮水机真的方便', '三楼四楼都有了，45度水温刚好。\n再也不用背水壶爬楼了。', 'water dispenser'),
    ('体育馆羽毛球场地预约攻略', '小程序每天早上7点放第三天的场。\n热门时段手速要快，亲测7点01就没了。', 'badminton court'),
    ('食堂阿姨今日份投喂', '看我排队排得可怜，多给了一勺菜。\n人间温暖从食堂开始。', 'canteen food'),
    ('校园地图冷知识：三教有两条近路', '从东侧连廊穿过去比绕广场快五分钟。\n赶早八的都懂。', 'campus path'),
    ('夜跑回来路过东门夜市，忍住没吃', '忍住了烤冷面，没忍住糖炒栗子。\n明天继续夜跑，后天也去夜市。', 'night market'),
    ('宿舍断电前的十分钟在干什么', '全员充电狂魔，插线板插满了。\n建议学校把断电时间延到十二点（做梦）。', 'dorm night'),
    ('周末的网球课体验课报名了', '零基础教学，球拍可以借。\n运动完手臂已经不是自己的了。', 'tennis court'),
    ('银杏道拍照的人已经开始排队了', '摄影社的学长在帮路人免费拍。\n出片率很高，想去的朋友抓紧。', 'ginkgo avenue'),
    ('奶茶店第二杯半价到底谁发明的', '一个人喝一杯，两个人喝两杯。\n数学上无解，钱包上有解。', 'milk tea'),
    ('今天在食堂吃到妈妈的味道了', '番茄炒蛋是甜口的，一眼南方做法。\n突然想家了。', 'home cooking'),
    ('期中之后的第一次社团活动', '纳新结束第一次全员到齐，气氛很好。\n大一大二的学弟学妹都很有活力。', 'club activity'),
    ('修自行车的大爷又救了我一命', '半路掉链子，大爷三秒钟装好，收两块钱。\n北门车棚一直在，风雨无阻。', 'bicycle repair'),
    ('第一次在学校看到刺猬', '晚上十点在花坛边上，缩成一团超可爱。\n不要摸不要喂，看着就好。', 'hedgehog'),
    ('期中成绩出来了，有人欢喜有人愁', '高数差点挂，最后捞回来了。\n开始预习才是硬道理，与君共勉。', 'exam paper'),
]

LOST_ITEMS = ['雨伞', '保温杯', '充电宝', '蓝牙耳机', '钥匙', '校园卡', '电子表', '高数课本', '数据线', '水杯', '饭卡']
LOST_PLACES = ['三教', '二食堂', '图书馆', '操场看台', '东区教室', '南门快递站', '体育馆', '一教', '实验楼', '篮球场边']
LOST_TRAIT = ['颜色是黑色的', '上面有卡通贴纸', '是透明的', '挂着一个小挂件', '牌子是白色的', '有轻微磨损']

ASK_HELP_POOL = [
    ('求推荐好的学习听歌歌单', '背书的时候听歌容易被歌词带跑，纯音乐有没有推荐的？\n评论区求分享，学习效率就差一个歌单了。', 'headphones music'),
    ('有没有人组队打比赛的', '大数据方向的竞赛，还差一个前端队友。\n要求不高，能沟通能出活就行，获奖有综测加分。', 'teamwork laptop'),
    ('求一份期末复习时间表模板', '总是复习着复习着就刷手机去了。\n有没有自控力强的同学分享一下时间表怎么排的？', 'planner notebook'),
    ('选课求建议：两个课撞时间了', '一个是口碑很好的选修，一个是专业课。\n学长学姐们当年怎么权衡的？', 'course schedule'),
    ('如何礼貌地拒绝无用社交', '室友天天拉着打游戏到凌晨，自己又不好意思拒绝。\n求一个不伤感情的说法。', 'dorm bed'),
    ('健身房新人求带', '办了卡但不知道从哪练起。\n有没有热心的健身大佬愿意带我两次，器械使用方法看了视频还是懵。', 'gym'),
    ('考研自习室怎么申请', '听说三楼有考研专区，需要什么条件吗？\n现在开始准备会不会太晚，有点焦虑。', 'postgraduate exam'),
    ('求问户口/档案相关手续怎么办', '放假要把档案转到人才市场，流程完全看不懂。\n办过的同学求指路，一点点踩坑经验都行。', 'documents office'),
    ('二手书在哪里买最划算', '开学季教材全买新的太贵了。\n除了校内二手群，还有别的渠道吗？', 'used books'),
    ('摄影零基础求入门建议', '手机拍的总是灰蒙蒙的。\n想入门摄影，先买相机还是先学构图？', 'camera'),
    ('四六级考试报名什么时候截止', '差点忘了这回事，有没有记得的同学说下时间。\n顺便求一个六级作文模板。', 'exam hall'),
    ('实验室科研助理招人吗', '想提前接触科研项目，锻炼一下。\n有在实验室帮忙的同学说说渠道吗？', 'laboratory'),
    ('怎么在学校收养一只猫', '不是真养，就是想固定投喂楼下那只。\n有没有喂猫的前辈分享下经验和猫粮推荐。', 'feeding cat'),
    ('面试总挂，求面试技巧', '简历能过，一到面试就紧张说不出话。\n有没有面霸同学分享下心态调整方法。', 'job interview'),
    ('求推荐适合宿舍的小功率电器', '600W以下能煮点什么的。\n用过的同学说说哪些好用不容易被查', 'small appliance'),
    ('表白墙怎么投稿', '想感谢一下捡到我钱包的同学。\n不知道走什么渠道，求告知。', 'message board'),
]

# 商品池：(分类, 名称, 基础价, 关键词)
PRODUCT_POOL = [
    ('DIGITAL', 'iPhone 13 128G', 2399, 'iphone smartphone'),
    ('DIGITAL', 'iPhone 12 mini', 1599, 'iphone smartphone'),
    ('DIGITAL', '小米14', 2899, 'xiaomi smartphone'),
    ('DIGITAL', 'Redmi Note13', 899, 'smartphone'),
    ('DIGITAL', 'iPad 9代 64G', 1450, 'ipad tablet'),
    ('DIGITAL', 'iPad Air 5 蓝色', 3299, 'ipad tablet'),
    ('DIGITAL', 'AirPods Pro 2', 1299, 'airpods'),
    ('DIGITAL', 'AirPods 3', 799, 'airpods'),
    ('DIGITAL', '小米手环8', 199, 'smartwatch'),
    ('DIGITAL', 'Apple Watch S8', 1899, 'smartwatch'),
    ('DIGITAL', '罗技G304 无线鼠标', 99, 'computer mouse'),
    ('DIGITAL', '罗技K380 蓝牙键盘', 149, 'computer keyboard'),
    ('DIGITAL', '达尔优机械键盘 87键', 189, 'mechanical keyboard'),
    ('DIGITAL', '小米蓝牙音箱', 129, 'bluetooth speaker'),
    ('DIGITAL', '联想小新14 锐龙版', 3499, 'laptop'),
    ('DIGITAL', 'MacBook Air M1', 4599, 'macbook'),
    ('DIGITAL', 'Anker 充电宝 2万毫安', 129, 'power bank'),
    ('DIGITAL', '倍思 65W 氮化镓充电器', 89, 'charger'),
    ('DIGITAL', 'Kindle Paperwhite 4', 599, 'ebook reader'),
    ('DIGITAL', '闪迪U盘 128G', 49, 'usb flash drive'),
    ('DIGITAL', '罗技M720 双模鼠标', 139, 'computer mouse'),
    ('DIGITAL', '小度智能屏 Air', 199, 'smart display'),
    ('DIGITAL', 'Switch Lite 珊瑚色', 899, 'nintendo switch'),
    ('DIGITAL', 'PS4 Slim 1TB', 1199, 'playstation console'),
    ('DIGITAL', '硕方读卡器三合一', 25, 'card reader'),
    ('DIGITAL', '桌面手机支架 金属款', 15, 'phone stand'),
    ('DIGITAL', '华为FreeBuds 5', 499, 'wireless earbuds'),
    ('DIGITAL', '漫步者头戴式耳机', 199, 'headphones'),
    ('DIGITAL', '希捷移动硬盘 1T', 359, 'external hard drive'),
    ('DIGITAL', '小米台灯Pro', 149, 'desk lamp'),
    ('DIGITAL', '绿联拓展坞 六合一', 119, 'usb hub'),
    ('DIGITAL', '佳能M50二代 微单', 3899, 'mirrorless camera'),
    ('DIGITAL', '富士拍立得mini11', 599, 'instant camera'),
    ('DIGITAL', '大疆Osmo Pocket 2', 1599, 'gimbal camera'),
    ('DIGITAL', '灵眸手机稳定器', 249, 'phone gimbal'),
    ('DIGITAL', '三星256G TF卡', 89, 'micro sd card'),
    ('DIGITAL', '罗技C920 摄像头', 299, 'webcam'),
    ('DIGITAL', '小米体脂秤', 79, 'smart scale'),
    ('DIGITAL', '网易云音乐人会员年卡（兑换码）', 68, 'music streaming'),
    ('DIGITAL', '机械革命游戏本 RTX4060', 5499, 'gaming laptop'),
    ('BOOKS', '张宇考研数学36讲', 68, 'mathematics textbook'),
    ('BOOKS', '汤家凤1800题', 45, 'math workbook'),
    ('BOOKS', '考研英语黄皮书', 40, 'english book'),
    ('BOOKS', '六级真题刷题卷', 25, 'exam paper'),
    ('BOOKS', '四级真题逐句精解', 20, 'exam paper'),
    ('BOOKS', '线性代数教材（同济版）', 15, 'mathematics textbook'),
    ('BOOKS', '高等数学（同济版上下册）', 18, 'mathematics textbook'),
    ('BOOKS', '数据结构（C语言版）', 22, 'programming book'),
    ('BOOKS', '操作系统考研指导', 25, 'computer science book'),
    ('BOOKS', '毛概教材 2023版', 10, 'university textbook'),
    ('BOOKS', '马原教材', 10, 'university textbook'),
    ('BOOKS', '剑桥雅思真题18', 55, 'ielts book'),
    ('BOOKS', '托福官方指南', 60, 'toefl book'),
    ('BOOKS', '《人类简史》', 28, 'history book'),
    ('BOOKS', '《三体》全三册', 45, 'science fiction book'),
    ('BOOKS', '《活着》', 15, 'novel book'),
    ('BOOKS', '《明朝那些事儿》全套', 35, 'chinese history book'),
    ('BOOKS', '《经济学原理》曼昆', 40, 'economics book'),
    ('BOOKS', '《心理学与生活》', 30, 'psychology book'),
    ('BOOKS', '《Python编程：从入门到实践》', 35, 'python book'),
    ('BOOKS', '《算法导论》', 55, 'algorithm book'),
    ('BOOKS', '《平凡的世界》三部曲', 42, 'novel book'),
    ('BOOKS', '《小王子》中英双语', 18, 'classic novel'),
    ('BOOKS', '《万历十五年》', 22, 'history book'),
    ('BOOKS', '大学物理教材及习题册', 25, 'physics textbook'),
    ('BOOKS', '有机化学教材（邢其毅）', 30, 'chemistry book'),
    ('BOOKS', '《哈利·波特》英文原版1-3', 65, 'harry potter books'),
    ('BOOKS', '《红楼梦》人民文学版', 30, 'chinese classic book'),
    ('BOOKS', '日语N2红宝书', 28, 'language learning book'),
    ('BOOKS', '法语入门教材', 25, 'language learning book'),
    ('BOOKS', '《金字塔原理》', 26, 'business book'),
    ('BOOKS', '《艺术的故事》', 45, 'art book'),
    ('BOOKS', '乐理基础知识教程', 20, 'music theory book'),
    ('BOOKS', '《漫威漫画精选集》', 35, 'comic books'),
    ('BOOKS', '《国家地理》过刊10本', 40, 'national geographic magazine'),
    ('DAILY', '人体工学椅', 159, 'office chair'),
    ('DAILY', '手摇升降桌 1.2m', 220, 'standing desk'),
    ('DAILY', '床帘遮光布 含支架', 39, 'bed curtain'),
    ('DAILY', '免打孔置物架 三层', 29, 'storage shelf'),
    ('DAILY', '宿舍小台灯 三档调光', 35, 'desk lamp'),
    ('DAILY', '手持挂烫机', 89, 'garment steamer'),
    ('DAILY', '半导体小冰箱 20L', 399, 'mini fridge'),
    ('DAILY', '桌面小风扇 USB', 25, 'usb fan'),
    ('DAILY', '保温壶 1L', 30, 'thermos'),
    ('DAILY', '脸盆架 可折叠', 15, 'foldable rack'),
    ('DAILY', '收纳箱三件套', 45, 'storage box'),
    ('DAILY', '落地晾衣架', 19, 'clothes rack'),
    ('DAILY', '桌面化妆镜 带灯', 25, 'makeup mirror'),
    ('DAILY', '宿舍床垫 90cm 软硬适中', 99, 'mattress'),
    ('DAILY', '泡脚桶 带按摩', 35, 'foot bath'),
    ('DAILY', '电热水壶 1.5L', 49, 'electric kettle'),
    ('DAILY', '香薰小夜灯', 29, 'aroma lamp'),
    ('DAILY', '晴雨两用伞', 20, 'umbrella'),
    ('DAILY', '蓝牙闹钟音箱', 39, 'alarm clock'),
    ('DAILY', '挂式全身镜', 45, 'full length mirror'),
    ('DAILY', '抽屉式收纳柜 五层', 75, 'drawer storage'),
    ('DAILY', '宿舍吊椅 闲置', 60, 'hanging chair'),
    ('DAILY', '电热毯 单人', 55, 'electric blanket'),
    ('DAILY', '加湿器 3L', 49, 'humidifier'),
    ('DAILY', '桌面绿萝盆栽+陶瓷盆', 15, 'potted plant'),
    ('DAILY', '挂钩免钉粘贴一套', 9, 'adhesive hooks'),
    ('DAILY', '针灸推拿按摩枕', 65, 'massage pillow'),
    ('DAILY', '野餐垫 防潮加厚', 30, 'picnic blanket'),
    ('DAILY', '行李箱 24寸', 180, 'luggage'),
    ('DAILY', '双肩背包 大容量', 89, 'backpack'),
    ('DAILY', '暖手宝充电两用', 35, 'hand warmer'),
    ('DAILY', '宿舍门后挂架', 18, 'door hook'),
    ('DAILY', '熨斗+烫衣板', 50, 'iron board'),
    ('DAILY', '卡座式鞋架', 28, 'shoe rack'),
    ('DAILY', '桌上书架 简易', 32, 'desktop bookshelf'),
    ('SPORTS', '捷安特ATX 720 山地车', 850, 'mountain bike'),
    ('SPORTS', '美利达通勤自行车', 600, 'city bicycle'),
    ('SPORTS', '专业双翘滑板', 180, 'skateboard'),
    ('SPORTS', '可调哑铃套装 15kg', 90, 'dumbbells'),
    ('SPORTS', 'TPE瑜伽垫 加厚', 35, 'yoga mat'),
    ('SPORTS', '斯伯丁篮球 七号球', 60, 'basketball'),
    ('SPORTS', '训练足球 五号', 50, 'soccer ball'),
    ('SPORTS', '尤尼克斯羽毛球拍一对', 120, 'badminton racket'),
    ('SPORTS', 'Wilson网球拍', 95, 'tennis racket'),
    ('SPORTS', '红双喜乒乓球拍一对', 40, 'table tennis paddle'),
    ('SPORTS', '计数跳绳', 12, 'jump rope'),
    ('SPORTS', '运动护膝一对', 25, 'knee brace'),
    ('SPORTS', '泳镜泳帽套装', 30, 'swimming goggles'),
    ('SPORTS', '李宁跑步鞋 42码', 199, 'running shoes'),
    ('SPORTS', '两秒自动开帐篷 3-4人', 150, 'camping tent'),
    ('SPORTS', '钓鱼竿套装 3.6米', 110, 'fishing rod'),
    ('SPORTS', '露营折叠桌椅套装', 95, 'camping chair'),
    ('SPORTS', '护腕护踝套装', 22, 'sports braces'),
    ('SPORTS', '引体向上门上单杠', 68, 'pull up bar'),
    ('SPORTS', '平衡车 九成新', 320, 'balance bike'),
    ('SPORTS', '拳击手套+沙袋', 130, 'boxing gloves'),
    ('SPORTS', '腰旗橄榄球套装', 45, 'flag football'),
    ('SPORTS', '骑行头盔+手套', 75, 'cycling helmet'),
    ('SPORTS', '登山包 40L', 160, 'hiking backpack'),
    ('SPORTS', '飞盘 专业175g', 28, 'frisbee'),
    ('TICKETS', '周杰伦演唱会看台票两张', 1200, 'concert crowd'),
    ('TICKETS', '五月天演唱会看台一张', 855, 'concert stage'),
    ('TICKETS', '电影院通兑券两张', 60, 'cinema'),
    ('TICKETS', '游乐园学生票', 150, 'amusement park'),
    ('TICKETS', '话剧《雷雨》学生票', 80, 'theatre stage'),
    ('TICKETS', '音乐节早鸟票单日', 180, 'music festival'),
    ('TICKETS', '博物馆特展门票', 45, 'museum exhibition'),
    ('TICKETS', 'livehouse 演出票', 120, 'livehouse'),
    ('TICKETS', '脱口秀开放麦门票', 60, 'comedy stage'),
    ('TICKETS', '海洋馆双人票', 199, 'aquarium'),
    ('TICKETS', '植物园年卡', 88, 'botanical garden'),
    ('TICKETS', '密室逃脱双人券', 128, 'escape room'),
    ('OTHER', '手绘校园地图明信片套装', 18, 'postcards'),
    ('OTHER', '校运会纪念围巾 全新', 30, 'scarf'),
    ('OTHER', '盲盒手办 娃已拆盒', 45, 'figure toy'),
    ('OTHER', '毛绒玩偶 60cm大只', 25, 'plush toy'),
    ('OTHER', '桌游全套（狼人杀+UNO）', 55, 'board game'),
    ('OTHER', '装饰挂画 北欧风', 20, 'wall art'),
    ('OTHER', '多肉植物三盆+工具', 22, 'succulent plants'),
    ('OTHER', '香薰蜡烛 意外买的', 22, 'scented candle'),
    ('OTHER', '尤克里里 21寸 初学', 160, 'ukulele'),
    ('OTHER', '卡林巴拇指琴 17音', 78, 'kalimba'),
    ('OTHER', '拼图1000片 已完成一次', 30, 'jigsaw puzzle'),
    ('OTHER', '星巴克随行杯 全新', 65, 'travel mug'),
    ('OTHER', '手账本+胶带套装', 26, 'journal notebook'),
    ('OTHER', '双人帐篷闲置', 120, 'tent camping'),
    ('OTHER', '传统象棋+围棋二合一', 35, 'chess set'),
]

PRODUCT_CONDITION = [
    ('95新 无拆无修', '一直贴膜带壳用，功能一切正常。\n已清理干净，附相关配件，面交可验货。'),
    ('九成新', '用了不长，外观保持得很好，只有轻微使用痕迹。\n出是因为闲置，配件齐全，诚心要可以小刀。'),
    ('8成新 有轻微使用痕迹', '正常使用的痕迹，不影响任何功能。\n价格已经很低了，适合预算有限的同学。'),
    ('几乎全新 只开封用过几次', '买来用了没几次就闲置了，接近全新状态。\n原包装都在，送人自用都合适。'),
    ('功能完好 外观有岁月痕迹', '用了挺久，但有感情了，质量经过验证。\n便宜出给需要的人，不退不换，可当面测试。'),
]

PRODUCT_LOCATIONS = ['南区宿舍6栋楼下', '图书馆南门', '北区车棚', '东区宿舍12栋', '市体育中心东门',
                     '南区快递站门口', '研究生公寓楼下', '东区滑板场旁', '图书馆西门', '南区宿舍3栋楼下',
                     '北区快递柜旁', '大学生活动中心门口', '西区食堂门口', '南门岗亭']

GENERIC_COMMENTS = [
    '帮顶，希望早点找到/找到主人！', '这波分享太实用了，收藏了。', '同感！我也是这么想的。',
    '感谢楼主，正好需要这个信息。', '蹲一个后续。', '评论区都是人才，笑死。',
    '楼主有心了，感谢整理。', '已转发给室友，大家都觉得有用。', '这个必须点赞。',
    '来了来了，前排支持。', '每次刷到这种帖子心情都很好。', '实名羡慕了。',
    '学到了学到了。', '楼主是懂生活的。', '附议，+1。', '太真实了哈哈哈。',
    '码住，周末就去。', '希望学校多一点这样的地方。', '问个题外话，这是在哪拍的？',
    '观感很好，支持一下。', '看着就很有食欲/很想买，冲了。', '一句话：靠谱！',
    '楼主更新了吗？想看后续。', '默默点赞，不想说话。', '这就是我们学校吗，爱了。',
    '评论区蹲一个详细攻略。', '刚经历完同样的过程，理解楼主。', '初来乍到，感谢前辈指路。',
    '真的会谢，太及时了。', '好帖，帮推。', '楼主好眼光。', '看完立刻行动了。',
    '这条要火，先占座。', '世界是个巨大的草台班子，但你这个是真靠谱。', '笑不活了，太真实。',
    '收下我的膝盖。', '这价格真的假的，太香了。', '明天就去试试！', '深深共鸣。',
    '在校四年第一次知道还有这地方。', '顶上去，让更多人看到。', '楼主好人一生平安。',
    '求同去，有组队的吗？', '已收藏，慢慢看。', '不失为一个好办法。',
    '评论区有懂行的吗？', '我上次也遇到过，楼主说的是对的。', '基本同意，补充一点：错峰真的重要。',
    '感谢分享，很有帮助。', '这条信息的含金量还在上升。',
]
DAILY_COMMENTS = [
    '这地方我也常去，体验一致。', '拍得真好看，构图绝了。', '看着看着就饿了/想去了。',
    '这是本学期最实用的一帖。', '同款体验，自从知道这里生活幸福感都高了。', '笔记做起来了。',
    '谢谢楼主的情报网。', '已经在去了的路上了。', '风评大好，冲。', '这文案我给满分。',
]
LOST_COMMENTS = [
    '帮你留意了，捡到会第一时间联系！', '帮顶！希望早日物归原主。', '我有见过类似的，私信你细节。',
    '楼主别急，一般都会被好心人捡到的。', '可以顺便去宿管站和失物招领柜看看。', '帮转发到班级群了。',
    '同校的路过，祝早日找回。', '天下无贼，人间自有真情在。', '建议再发一条表白墙。',
    '蹲一个好消息。',
]
ASK_COMMENTS = [
    '这题我会，私信你了详细步骤。', '过来人建议：越早开始越好。', '同问！蹲一个大佬。',
    '楼主加油，都经历过这个阶段。', '评论区已经有大佬出手了，学习一下。', '可以看看往届的资料，图书馆就有。',
    '我认识相关方向的同学，帮你问问。', '别慌，这个问题其实不难。', '建议先列个提纲再动手。',
    '需要的话我可以把笔记拍给你。',
]
REPLY_POOL = [
    '同意楼上，亲测有效。', '楼上说得太对了，补充一个细节。', '哈哈哈笑死我了楼上。',
    '+10086，就是这么回事。', '谢谢楼上，学到了。', '楼上真善人，点赞。',
    '我也是这么想的，居然被抢答了。', '楼上这方法我试过，管用！', '好耶，那我也试试。',
    '楼上是隐藏大佬吧。', '已被楼上说服。', '同楼上，抱团取暖。',
    '楼上醒目！这条建议值五毛。', '这个补充很关键，谢谢。', '感谢指路，少走弯路。',
]


def build_content():
    """生成全部待发布内容（帖子/商品），返回 (posts, products)"""
    rng = random.Random(20261007)
    posts = []   # (kw, cat, title, content, img_variant)
    products = []  # (cat, title, desc, price, loc, kw)

    # --- 帖子 ---
    for kw, cat, title, content in HAND_POSTS:
        posts.append((kw, cat, title, content))
    for i, w in enumerate(CANTEEN):
        posts.append(('canteen food', 'DAILY', '食堂窗口测评：二楼%s' % w,
                      '今天中午试了新开的%s窗口。\n%s\n总体%d星，会回购。'
                      % (w, rng.choice(CANTEEN_NOTE), rng.randint(3, 5))))
    for i, t in enumerate(TEA_SHOPS):
        posts.append(('milk tea', 'DAILY', '%s新品尝鲜报告' % t,
                      '下课顺路买了%s的当季新品。\n%s\n热量警告，但快乐无价。'
                      % (t, rng.choice(TEA_NOTE))))
    for s in SCENES:
        posts.append(({'图书馆前的草坪': 'campus lawn', '教学楼天台': 'rooftop view',
                       '樱花道': 'cherry blossom', '南门夜市': 'night market',
                       '小西湖': 'campus lake', '操场看台': 'stadium seats',
                       '银杏道': 'ginkgo avenue', '体育馆外场': 'stadium exterior',
                       '东门早餐摊': 'breakfast street', '后山小路': 'forest path'}.get(s, 'campus scenery'),
                      'DAILY', '校园随手拍：%s' % s,
                      '%s\n%s' % (rng.choice(SCENE_NOTE), rng.choice(SCENE_NOTE))))
    for n in range(8, 26):
        posts.append(('morning jog', 'DAILY', '晨跑打卡第%d天' % n,
                      '今天六点半准时出门，绕操场%d圈。\n%s'
                      % (rng.randint(3, 6), rng.choice([
                          '今天状态好，配速提了10秒。', '起不来的时候想想包子，就起来了。',
                          '跑步这件事，穿上鞋就成功了一半。', '遇上了同样晨跑的同学，互相打了个招呼。',
                          '天越来越亮得晚了，注意保暖。']))))
    for b in ['一教', '二教', '三教', '图书馆', '西区教学楼', '逸夫楼']:
        posts.append(('classroom', 'DAILY', '%s自习占座指南（实测）' % b,
                      '亲测%s的空座率：工作日上午高，下午爆满。\n%s'
                      % (b, rng.choice([
                          '四楼比一楼人少，插座集中在靠窗排。',
                          '研讨区安静程度一般，赶作业去普通区。',
                          '热水机在二楼，打水顺手活动颈椎。',
                          '晚上十点清场，别学我上次被锁在里面（并不是）。']))))
    for item in LOST_ITEMS:
        place = rng.choice(LOST_PLACES)
        posts.append(({'雨伞': 'umbrella', '保温杯': 'thermos', '充电宝': 'power bank',
                       '蓝牙耳机': 'wireless earbuds', '钥匙': 'keys', '校园卡': 'id card',
                       '电子表': 'wristwatch', '高数课本': 'mathematics textbook',
                       '数据线': 'cable', '水杯': 'water bottle', '饭卡': 'id card'}[item],
                      'LOST_FOUND', '寻物启事：%s丢在%s附近' % (item, place),
                      '昨天下午把%s落在%s了，特征：%s。\n有捡到的同学联系我，有奶茶答谢！'
                      % (item, place, rng.choice(LOST_TRAIT))))
        posts.append((None, 'LOST_FOUND', '拾物招领：捡到一把%s相关物品' % place,
                      '今天在%s捡到%s，已交到宿管站/安全起见先自己保管。\n失主描述特征来认领。'
                      % (place, rng.choice(['一个钥匙串', '一张卡', '一副耳机', '一个水杯']))))
    for item in ['黑色雨伞', '白色充电宝', '粉色水杯', '黑色钱包', '灰色围巾', '蓝色头盔']:
        place = rng.choice(LOST_PLACES)
        posts.append((None, 'LOST_FOUND', '失物招领：捡到%s' % item,
                      '在%s附近捡到%s，特征明显就不细说了。\n失主带着证据来认领，确认后归还。'
                      % (place, item)))
    for title, content, kw in ASK_HELP_POOL:
        posts.append((kw, 'ASK_HELP', title, content))
    for title, content, kw in DAILY_MISC:
        posts.append((kw, 'DAILY', title, content))
    for i in range(1, 31):
        posts.append(('campus night', 'DAILY', '校园夜话 vol.%d：今晚聊点啥' % i,
                      '%s\n评论区聊起来。' % rng.choice([
                          '如果给你一个不熬夜的夜晚，你会做什么？',
                          '最近单曲循环的一首歌是什么？',
                          '食堂最好吃的一道菜，用一句话形容。',
                          '大学四年最后悔和最庆幸的事各一件。',
                          '你手机里存了多少张校园天空的照片？'])))
    for i, club in enumerate(['摄影社', '羽毛球社', '动漫社', '辩论队', '吉他社', '话剧社', '推理社', '徒步社']):
        posts.append(('club activity', 'DAILY', '%s招新答疑帖' % club,
                      '很多同学私信问%s的入社门槛。\n统一回复：零基础可入，每周一次活动，设备可借。\n本周日纳新摊位见。' % club))

    # --- 商品 ---
    for cat, name, price, kw in PRODUCT_POOL:
        cond, desc = PRODUCT_CONDITION[rng.randrange(len(PRODUCT_CONDITION))]
        p = max(5, int(price * rng.uniform(0.88, 1.05)))
        cond_word = cond.split()[0]
        title = ('%s %s' % (name, cond_word) if rng.random() < 0.5 else name)[:30]
        products.append((cat, title,
                         '%s\n%s\n面交优先，见货付款，可小刀。' % (cond, desc), p,
                         rng.choice(PRODUCT_LOCATIONS), kw))

    return posts, products


def main():
    skip_users = '--skip-users' in sys.argv
    rng = random.Random(42)

    print('== 1/4 用户')
    tokens = {}
    for phone, nick in USERS:
        tokens[nick] = register_or_login(phone, nick)[0]
    for phone, nick in [ME]:
        tokens[nick] = register_or_login(phone, nick)[0]

    nicks = list(tokens.keys())
    toks = [tokens[k] for k in nicks]

    print('== 2/4 充值钱包（商品交易托管用）')
    for nick, tok in tokens.items():
        r = api('POST', '/api/wallet/recharge', tok, {'amount': 5000})
        print('  %s recharge -> %s' % (nick, r.get('info')))

    posts_data, products_data = build_content()
    print('== 3/4 发内容（帖子 %d / 商品 %d）' % (len(posts_data), len(products_data)))
    posts, products = [], []

    def pub_post(tok, nick, item):
        kw, cat, title, content = item
        images = [upload_real(tok, kw, rng.randrange(2))] if kw else None
        r = api('POST', '/api/community/posts', tok,
                {'category': cat, 'title': title, 'content': content, 'images': images})
        if r.get('code') == '200':
            posts.append(r['data'])
        else:
            print('  [fail] 帖子「%s」-> %s' % (title, r.get('info')))

    def pub_product(tok, nick, item):
        cat, title, desc, price, loc, kw = item
        r = api('POST', '/api/trade/products', tok,
                {'title': title, 'description': desc, 'price': price,
                 'tradeLocation': loc, 'category': cat,
                 'images': [upload_real(tok, kw, rng.randrange(2))]})
        if r.get('code') == '200':
            products.append(r['data'])
        else:
            print('  [fail] 商品「%s」-> %s' % (title, r.get('info')))

    for i, item in enumerate(posts_data):
        pub_post(toks[i % len(toks)], nicks[i % len(nicks)], item)
        if i % 40 == 39:
            print('  ...帖子进度 %d/%d' % (i + 1, len(posts_data)))
    for i, item in enumerate(products_data):
        pub_product(toks[i % len(toks)], nicks[i % len(nicks)], item)
        if i % 40 == 39:
            print('  ...商品进度 %d/%d' % (i + 1, len(products_data)))
    print('== 4/4 互动')
    # 帖子点赞：每帖 3~6 个
    for pid in posts:
        k = rng.randint(3, 6)
        for tok in rng.sample(toks, k):
            api('POST', '/api/community/posts/%d/like' % pid, tok)

    # 评论：每帖 3~6 条（按分类池 + 通用池，帖内不重复、评论人不与楼主重复）
    comments = []  # (post_id, root_comment_id)
    for i, pid in enumerate(posts):
        cat = posts_data[i % len(posts_data)][1] if i < len(posts_data) else 'DAILY'
        pool = {'DAILY': DAILY_COMMENTS, 'LOST_FOUND': LOST_COMMENTS,
                'ASK_HELP': ASK_COMMENTS}.get(cat, GENERIC_COMMENTS)
        k = rng.randint(3, 6)
        texts = rng.sample(pool, min(k, len(pool))) + rng.sample(GENERIC_COMMENTS, 2)
        rng.shuffle(texts)
        texts = texts[:k]
        author_ids = list(range(len(toks)))
        rng.shuffle(author_ids)
        root_ids = []
        for j, text in enumerate(texts):
            tok = toks[author_ids[j % len(author_ids)]]
            r = api('POST', '/api/community/posts/%d/comments' % pid, tok, {'content': text})
            if r.get('code') == '200' and (r.get('data') or {}).get('commentId'):
                root_ids.append(r['data']['commentId'])
        comments.append((pid, root_ids))
        if (i + 1) % 40 == 0:
            print('  ...评论进度 %d/%d 帖' % (i + 1, len(posts)))

    # 楼中楼：约一半的帖子补 1~2 条回复
    for i, (pid, root_ids) in enumerate(comments):
        if not root_ids or rng.random() < 0.5:
            continue
        for cid in root_ids[:rng.randint(1, min(2, len(root_ids)))]:
            tok = toks[rng.randrange(len(toks))]
            api('POST', '/api/community/comments/%d/replies' % cid, tok,
                {'content': rng.choice(REPLY_POOL)})
        if (i + 1) % 60 == 0:
            print('  ...回复进度 %d/%d 帖' % (i + 1, len(comments)))

    # 商品收藏：每件 1~3 人
    for pid in products:
        for tok in rng.sample(toks, rng.randint(1, 3)):
            api('POST', '/api/trade/products/%d/favorite' % pid, tok)

    # 评论点赞
    liked = 0
    for pid, root_ids in comments:
        for cid in root_ids:
            if rng.random() < 0.4:
                for tok in rng.sample(toks, rng.randint(1, 2)):
                    r = api('POST', '/api/community/comments/%d/like' % cid, tok)
                    if r.get('code') == '200':
                        liked += 1
    print('  评论点赞完成 %d 次' % liked)

    print('== 结果核对')
    for path, label in [('/api/community/posts?page=1&pageSize=1', '帖子'),
                        ('/api/trade/products?page=1&pageSize=1', '商品')]:
        r = api('GET', path)
        d = r.get('data') or {}
        print('  %s 总数 %s（%s）' % (label, d.get('total'), r.get('info')))


if __name__ == '__main__':
    main()
