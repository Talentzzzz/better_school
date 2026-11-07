# 三台服务器从零到出数（tar 镜像 + 两份 compose）

> 应用是「打好包的 tar 镜像」传上服务器再 `docker load`，服务器上不需要 JDK、Maven 和源码。
> 中间件与应用各一份 compose：`docker-compose-middleware.yml` / `docker-compose-app.yml`。
> 设计说明与指标口径见 [README.md](README.md)。

```
开发机（有 Docker + JDK21 + Maven）──build──> lt-inventory-app-1.0.0.tar
                                                   │ scp
        ┌──────────────────────────────────────────┼──────────────────────────┐
        ▼                                          ▼                          ▼
  中间件机 47.93.120.76                      应用机 101.201.49.50        施压机 47.93.122.40
           内网 172.31.88.255                        内网 172.31.89.1         内网 172.31.89.0
  docker-compose-middleware.yml            docker-compose-app.yml       JMeter + scripts
  MySQL / Redis / RocketMQ                 lt-app（镜像已 load）
```

三台机（阿里云同 VPC，服务间互访走内网，IP 已写死在 compose 里）：

| 机器 | 公网（ssh/scp 用） | 内网（服务互访用） | 角色 |
|---|---|---|---|
| 中间件机 | 47.93.120.76 | 172.31.88.255 | MySQL / Redis / RocketMQ |
| 应用机 | 101.201.49.50 | 172.31.89.1 | lt-app |
| 施压机 | 47.93.122.40 | 172.31.89.0 | JMeter |

**机器规格**：三台都是 **4C8G**，compose 里的默认参数已按这个规格配好：

| 机器 | 内存分配 | 说明 |
|---|---|---|
| 中间件机 | MySQL buffer pool 2G + broker 堆 1.5G + namesrv 512M + Redis | 剩余给系统页缓存 |
| 应用机 | JVM 4G（`-Xms4g -Xmx4g`） | 剩 4G 给堆外/page cache/系统 |
| 施压机 | JMeter 堆 2G | 4C 下 400 线程基本是产生压力上限 |
**时间同步**：三台机执行一次 `ntpdate ntp.aliyun.com`（合并的时间格用 DB 时间，不受应用机时钟影响）。

---

## 第 1 步 · 应用镜像 tar 包（已经打好了）

`loadtesting/lt-inventory-app-1.0.0.tar`（约 280 MB）就在目录里，**可以直接用，跳过这一步**。

需要重新打（改了代码 / 改 JVM 参数）时：

```bash
cd loadtesting
bash scripts/build-image.sh        # 用 bash 执行，Windows 的 Git Bash 下也能直接跑
```

它做三件事：`mvn package` → `docker build -f app/Dockerfile.runtime` → `docker save`，
产出 `lt-inventory-app-1.0.0.tar`（未压缩，约 280 MB）。

> 只需要 `eclipse-temurin:21-jre-alpine` 这一个基础镜像。如果开发机拉不动它，
> 先把 Docker 的代理配好，或者在有网的机器上 `docker pull eclipse-temurin:21-jre-alpine`
> 后 `docker save` 过来。

## 第 2 步 · 把文件发到三台机

```bash
# 先打一个不含镜像包的小包（约 300K），三台机都发它
tar cf lt-server.tar --exclude='*.tar' loadtesting

for H in 47.93.120.76 101.201.49.50 47.93.122.40; do
  scp lt-server.tar root@$H:/opt/
  ssh root@$H 'cd /opt && tar xf lt-server.tar'
done

# 应用机额外需要镜像包（约 280 MB）
scp loadtesting/lt-inventory-app-1.0.0.tar root@101.201.49.50:/opt/loadtesting/
```

三台机都装 Docker（只需要一次）：

```bash
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker
docker version && docker compose version
```

---

## 第 3 步 · 中间件机（47.93.120.76）

### 3.1 配置

```bash
cd /opt/loadtesting
# 不用改任何东西：IP、端口、密码、MySQL 调参、broker 配置全部写在 compose 里，
# 也不挂载宿主机文件，把 compose 放上来就能起。
```

### 3.2 起中间件

```bash
cd /opt/loadtesting
docker compose -f docker-compose-middleware.yml up -d

# 等 30~60 秒（MySQL 首次初始化要建库建表）
docker compose -f docker-compose-middleware.yml ps
```

期望四个 Up：`lt-mysql (healthy)`、`lt-redis`、`lt-rocketmq-namesrv`、`lt-rocketmq-broker`。

Redis 保持官方默认持久化（RDB），数据目录挂在 `lt-redis-data` 卷上，容器重建不丢；
每轮压测的库存仍然由 `/api/admin/reset` 重置，不依赖 Redis 里残留的数据。

库 `lt_inventory` 由 MySQL 镜像自动创建，**4 张表由应用首次启动时执行 `schema.sql` 自动建好**，
所以中间件机只起容器就行，不用手工导 SQL。

### 3.3 放行端口

```bash
# firewalld（CentOS / RHEL / openEuler）
firewall-cmd --permanent --add-port=3306/tcp --add-port=6379/tcp \
  --add-port=9876/tcp --add-port=10911/tcp --add-port=10909/tcp
firewall-cmd --reload

# 或 ufw（Ubuntu / Debian）
ufw allow 3306,6379,9876,10909,10911/tcp
```

### 3.4 验收（四条，全绿再往下）

```bash
# ① 库表建好、调参生效（期望 flush_log=2、max_conn=2000、4 张表）
docker exec lt-mysql mysql -uroot -plt123456 -e \
  "SHOW TABLES FROM lt_inventory; SELECT @@innodb_flush_log_at_trx_commit AS flush_log, @@max_connections AS max_conn;"

# ② Redis 通了
docker exec lt-redis redis-cli -a lt123456 --no-auth-warning ping

# ③ broker 注册到 namesrv 了（地址必须是 172.31.88.255，不能是 127.0.0.1）
docker exec lt-rocketmq-broker sh -c \
  'cd /home/rocketmq/rocketmq-5.5.0/bin && sh mqadmin clusterList -n 127.0.0.1:9876'

# ④ 从应用机上验证网络可达（在应用机执行）
#    mysql -h172.31.88.255 -uroot -plt123456 -e "select 1"
#    redis-cli -h 172.31.88.255 -a lt123456 ping
```

③ 里出现 `172.31.88.255:10911` 才算对；如果是 `127.0.0.1:10911`，说明 broker 拿错了地址，
执行 `docker compose -f docker-compose-middleware.yml up -d --force-recreate rocketmq-broker` 重建。

> 可选：看消息堆积，`http://47.93.120.76:8180`
> ```bash
> docker compose -f docker-compose-middleware.yml --profile tools up -d rocketmq-dashboard
> ```

---

## 第 4 步 · 应用机（101.201.49.50）

### 4.1 导入镜像

```bash
cd /opt/loadtesting
docker load -i lt-inventory-app-1.0.0.tar
docker images | grep lt-inventory-app      # 看到 lt-inventory-app 1.0.0
```

### 4.2 配置并启动

```bash
cd /opt/loadtesting
# IP 已写死在 compose 里，无需任何配置

docker compose -f docker-compose-app.yml up -d
docker logs -f lt-app        # 看到「库存压测环境就绪」后 Ctrl+C
```

启动时不会再构建任何东西——`docker compose` 直接用 load 进来的镜像。
如果中间件还没起好，应用会因连不上库反复重启，这是正常的，等中间件就绪后自己会起来。

### 4.3 冒烟：扣一单、看账

```bash
curl -s localhost:8080/actuator/health; echo

curl -s -X POST "localhost:8080/api/admin/reset?skuId=1001&stock=100&buckets=1"; echo

for S in db redis_mq merge; do
  curl -s -X POST localhost:8080/api/inventory/deduct \
    -H 'Content-Type: application/json' \
    -d "{\"strategy\":\"$S\",\"orderNo\":\"smoke-$S\"}"; echo
done

sleep 5
curl -s "localhost:8080/api/admin/stats?skuId=1001" | python3 -m json.tool
```

期望 `balanced=true`、`oversold=false`、`drained=true`，`sold=3`（db/redis_mq 记在 `issued`，
merge 记在 `waiting`）、`surplus=97`。

---

## 第 5 步 · 施压机（47.93.122.40）

### 5.1 装 JMeter 5.6.3

```bash
cd /opt
curl -fsSLO https://dlcdn.apache.org/jmeter/binaries/apache-jmeter-5.6.3.tgz
tar xzf apache-jmeter-5.6.3.tgz
export PATH=/opt/apache-jmeter-5.6.3/bin:$PATH
jmeter -v
ulimit -n 65535                          # 压测要开大量连接
export HEAP="-Xms2g -Xmx2g"              # 4C8G 施压机，堆给 2G
```

下载不动就在开发机下好再 `scp` 上去。

### 5.2 一条命令跑完一轮

```bash
cd /opt/loadtesting
chmod +x scripts/*.sh
export APP_HOST=172.31.89.1        # 应用机内网 IP
export THREADS=200 RAMP=30 DURATION=120 STOCK=1000000

./scripts/run-test.sh db
./scripts/run-test.sh redis_mq
./scripts/run-test.sh merge
```

脚本依次做：**重置 → 等 3 秒 → 施压 → 轮询等台账排空 → 打印这一轮的账**，
采样文件在 `results/result-<策略>-<时间>.jtl`。

`STOCK=1000000` 是「全量成交模式」：库存远大于请求量，比的是纯吞吐。
要测抢购风暴就把库存改小：

```bash
STOCK=500 ./scripts/run-test.sh merge      # 500 件库存，看谁不超卖
```

### 5.3 只跑 JMeter（自己控制节奏）

```bash
jmeter -n -t jmeter/inventory-deduct.jmx \
  -Jhost=172.31.89.1 -Jport=8080 \
  -Jthreads=200 -Jramp=30 -Jduration=120 \
  -Jstrategy=merge \
  -l results/my-run.jtl

jmeter -g results/my-run.jtl -o results/html-my-run    # 输出目录必须不存在
```

### 5.4 加压节奏

1. 50 线程跑 30 秒，确认零错误、`drained=true`；
2. 200 线程跑 2 分钟，记这一轮（**这是 4C8G 的主档位**）；
3. 找拐点时 200 → 300 → 400 递增，每档 2 分钟，观察 p99 是否突然抬升；
4. 每个策略至少 3 轮取中位数。

> 4C8G 上不要按 800 线程压：施压机自己先成瓶颈（CPU 跑满、连接排队），
> 这时 JMeter 的 Throughput 反映的是施压机的极限，不是服务的。400 是这套硬件的实际上限。

---

## 第 6 步 · 每一轮的固定动作

```
□ 上一轮 JMeter 已退出
□ ./scripts/run-test.sh <strategy>          # 内部已 reset
□ 记 JMeter 的 Throughput / Avg / p95 / p99 / 错误率
□ 记 stats 的 counters["ok:<strategy>"]      # 真正的受理数
□ 记 stats 的 merge_rows / merge_runs        # 写放大
□ 轮询到 invariant.drained=true，记下用了多少秒（收敛时间）
□ 确认 balanced=true 且 oversold=false
□ 换策略前等 5 秒
```

### 顺手记「DB 到底写了多少行」

中间件机上，压测前后各执行一次，差值就是这一轮的真实写入量：

```bash
docker exec lt-mysql mysql -uroot -plt123456 -N -e \
  "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_rows_updated','Innodb_rows_inserted','Com_update','Com_insert');"
```

预期：`db` / `redis_mq` 的 `Innodb_rows_updated` 增量 ≈ 受理数；`merge` 的增量 ≈ 合并次数，
差 2~3 个数量级——这是「合并扣减」最硬的证据，比 QPS 更有说服力。

### 顺手看「Tomcat 线程有没有打满」

施压机或应用机上执行，压测进行中采样：

```bash
# 上限（应为 800 / 20000）
curl -s localhost:8080/actuator/metrics/tomcat.threads.config.max
curl -s localhost:8080/actuator/metrics/tomcat.connections.config.max
# 当前忙碌线程：持续贴着上限 = 应用层已经是瓶颈，不是 DB
curl -s localhost:8080/actuator/metrics/tomcat.threads.busy
```

`tomcat.threads.busy` 长期等于 `config.max`，说明请求在 Tomcat 里排队，
此时 QPS 平台与 DB 无关，先把 `TOMCAT_THREADS` 调大或查后端慢在哪。

---

## 第 7 步 · 怎么判断这一轮有效

| 检查项 | 期望 | 不满足说明 |
|---|---|---|
| `counters["ok:<strategy>"]` | 全量模式 ≈ JMeter 采样数；抢购模式 == STOCK | 少了 = 有请求失败；多了 = 超卖 |
| `invariant.balanced` | `true` | 台账三量之和 != total |
| `invariant.oversold` | `false` | 超卖，立刻停 |
| `invariant.drained` | 最终 `true` | 没收敛，QPS 不可信 |
| JMeter 错误率 | 0% | 看应用日志（连接池打满 / MQ 不可用） |

三个常被误读的点：

1. **JMeter 的「成功率」不是受理率**：卖完返回 200 + `accepted=false`，JMeter 仍记成功。
   受理数看 `stats.counters["ok:xxx"]`。
2. **`redis_mq` 的 `drained` 会长时间 false**：消费端追不上，不是故障；
   它恰好说明 MQ 只削峰、不提升落库吞吐。
3. **`db` 的 p99 远高于均值**是行锁排队的正常形态。

---

## 第 8 步 · 调参速查

所有参数都在 compose 文件里，改完重启对应的 compose 即可（应用参数不需要重新打镜像）：

```bash
docker compose -f docker-compose-app.yml up -d          # 应用机
docker compose -f docker-compose-middleware.yml up -d   # 中间件机
```

| 参数 | 默认 | 什么时候改 |
|---|---|---|
| `TOMCAT_THREADS` | 400 | 应用层工作线程上限（4C8G 适配值）。`tomcat.threads.busy` 长期贴顶再加 |
| `TOMCAT_MIN_SPARE` | 100 | 常驻空闲线程，避免流量起来时反复建线程 |
| `TOMCAT_MAX_CONNECTIONS` | 20000 | 全连接数（含 keep-alive）。报 `Too many open files` 先看这里和 `ulimit` |
| `TOMCAT_ACCEPT_COUNT` | 2000 | 连接打满后的排队队列，队列溢出会直接拒连 |
| `DB_POOL_SIZE` | 200 | `db` 策略的并发上限；单热点行下 200 已远超行锁并行度，不够再看 `hikaricp.connections.pending` |
| `REDIS_POOL_SIZE` | 400 | 报 `Could not get a resource from the pool` 时加大 |
| `JAVA_OPTS` | `-Xms4g -Xmx4g` | 按物理内存 50% 调；小内存改 2g |
| `BUCKET_COUNT` | 1 | 想看分桶对合并的影响就设 4/8；重置时用 `BUCKETS=` 覆盖 |
| `MERGE_WINDOW_MS` | 1000 | 合并频率。调大 = 写更少、台账更滞后 |
| `MERGE_CELLS_PER_WAKE` | 1 | 保持 1（与线上一格一事务同构） |
| `MERGE_FALLBACK_MS` | 5000 | 兜底扫描间隔，调小尾部收敛更快 |

MySQL 侧在 `docker-compose-middleware.yml` 的 `mysql.command` 里（buffer pool、`innodb-flush-log-at-trx-commit=2`、关 binlog）。
想看默认配置下的 `db` 表现，把它改成 `1` 再 `docker compose -f docker-compose-middleware.yml up -d --force-recreate mysql`。

---

## 第 9 步 · 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `docker compose -f docker-compose-app.yml up -d` 报 `pull access denied` | 镜像还没 load | `docker load -i ...tar`，`docker images` 确认 |
| 应用日志 `Communications link failure` | 连不上 MySQL | 检查中间件机安全组/防火墙是否放行 3306、`docker exec lt-mysql mysqladmin ping` |
| `No route info of this topic` | broker 没注册 / brokerIP1 不对 | 第 3.4 的 ③，地址应为 `172.31.88.255:10911` |
| `RedisConnectionFailureException` | Redis 密码/端口不对 | `docker exec lt-redis redis-cli -a lt123456 ping` |
| `redis_mq` 全 `accepted=false` 且 `mq_send_fail` 暴涨 | MQ 不可用 | 先修 broker |
| 大量 `Connection is not available ... 5000ms` | Hikari 打满 | 降 `TOMCAT_THREADS` 或升 `DB_POOL_SIZE`；这是饱和信号 |
| MySQL 报 `Too many connections` | 连接池之和超 2000 | 降 `DB_POOL_SIZE` |
| JMeter 报 `Too many open files` | 施压机句柄不足 | `ulimit -n 65535` |
| `drained` 一直 false 且 `pendingRows` 不降 | 合并线程卡住 | `docker logs lt-app \| grep -i merge`，dashboard 看 MQ 堆积 |
| broker 容器反复重启 | 自行挂了 root 拥有的数据卷 | 本工程不给 broker 挂数据卷，别加空卷到 `/home/rocketmq/store` |
| 端口被占用 | 3306/6379 已被占 | 改 compose 的端口映射后 `up -d` |

看日志：

```bash
docker logs -f lt-app
docker logs --tail 100 lt-rocketmq-broker
docker exec lt-rocketmq-broker tail -50 /home/rocketmq/logs/rocketmqlogs/broker.log
docker exec lt-mysql tail -50 /var/log/mysql/error.log
```

---

## 第 10 步 · 收尾 / 单机试跑

```bash
# 应用机
cd /opt/loadtesting && docker compose -f docker-compose-app.yml down

# 中间件机（-v 连数据卷一起删，下次启动重新建表）
cd /opt/loadtesting && docker compose -f docker-compose-middleware.yml down -v
```

**想先在一台机上跑通再上三台**（比如本机或一台测试机）——先把写死的 IP 换成本机内网 IP：

```bash
# 临时改（试跑完改回来），不能填 127.0.0.1
sed -i 's/172\.31\.88\.253/本机内网IP/g' docker-compose-middleware.yml docker-compose-app.yml
docker compose -f docker-compose-middleware.yml -f docker-compose-app.yml up -d
curl -s localhost:8080/actuator/health
```

打一台机的压测结果没有参考价值（中间件和应用互相抢 CPU），只用来验证链路。

---

## 附：中间件机也拉不到公共镜像时

在有网的机器上把三个镜像存下来，一起传过去：

```bash
docker pull mysql:9.7 redis:8.8.0 apache/rocketmq:5.5.0
docker save -o lt-middleware-images.tar mysql:9.7 redis:8.8.0 apache/rocketmq:5.5.0
scp lt-middleware-images.tar root@47.93.120.76:/opt/

# 中间件机
docker load -i /opt/lt-middleware-images.tar
```
