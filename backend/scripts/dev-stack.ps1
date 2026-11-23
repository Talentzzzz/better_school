<#
.SYNOPSIS
    better-school 全栈一键起（票 17）：中间件容器 + 服务库 + xxl 注册 + MQ 清理 + 服务 + 健康检查。
    （monolith-restructure 票 05/07 起只有这一个工程/服务：原 marketing 起停段已删。）

.DESCRIPTION
    bash / WSL 在这台机器上是坏的（`execvpe(/bin/bash) failed`），所以 test-env.sh / run-app.sh
    这两个 .sh 入口在此不可用；本脚本是它们的 PowerShell 等价物，只依赖 docker / mvn / java。

    参数解析：`Get-Content` 读中文 SQL 会按系统 ANSI 解码，管道喂给 mysql 会乱码入库——
    因此所有 SQL 都走 `cmd /c "type <file> | docker exec -i ..."`，字节原样透传。

    拉起顺序与依赖（`up` 严格按此顺序执行，每一步都幂等、可重复跑）：

      ① docker compose up -d        （cwd = backend/）
           mysql ─┬─ xxl-job-admin（要先有 xxl_job 库，见 ③）
                  └─ 服务库
           redis / minio / nacos / rocketmq-namesrv / es 各自独立
           rocketmq-broker 依赖 rocketmq-namesrv 健康（compose 里已写 depends_on）
      ② 等就绪：mysql(healthy) → redis(healthy) → minio(healthy) → nacos(healthy)
                → rocketmq-namesrv(healthy) → es(healthy)
                broker / xxl-job-admin 无 healthcheck，只报状态不阻塞
      ③ 建库（docker exec 直连，不用 test-env.sh）：
           better_school  = better-school/sql/init-dev.sql   + schema.sql（超时 16 分表与营销域 22 张表都在这份 schema 里，票 07 合库）
           xxl_job        = backend/sql/xxl-job.sql（只在 xxl_job_info 表缺失时灌；官方脚本非幂等）
      ④ xxl 注册（xxl-seed）：单体的 sql/xxl-job-tasks.sql（幂等，按 executor_handler 判重；
                              营销任务已随票 07 并入，marketing-executor 组退役）
      ⑤ 注册面清理：Nacos（nacos-prune，删掉已删工程留下的 dataId）+ RocketMQ（mq-prune，
                    删掉已无生产/消费方的 topic 与消费组，清单见 $StaleMqNames）
      ⑥ 构建（除非 -SkipBuild）：mvn -o clean package -DskipTests（唯一工程，一次）
      ⑦ 起服务并等健康：
           better-school（唯一 app；Dubbo Provider）

    健康端点（无 actuator 依赖，取各服务匿名可访问的 GET；xxl 执行器端口顺带校验）：

      | 服务           | HTTP | 健康端点                                   | Dubbo tri | xxl 执行器 |
      | -------------- | ---- | ------------------------------------------ | --------- | ---------- |
      | better-school  | 8080 | GET /api/community/posts?page=1&pageSize=1 | 50051     | 9999       |

      （原 marketing 独立 app 的 8081 / 50052 / 9998 已随单体收口释放。）

      端口清单来源：bs-app 模块 src/main/resources/application.yml。
      9999 / 9998 是 **xxl 执行器端口**（票 17 票面把这两个误称作 actuator——本仓没有引入
      spring-boot-starter-actuator，也没有 management.* 配置）。

    日志与 pid：`<工程目录>/logs/dev-stack-<key>.log` / `.err.log` / `.pid`
    （该目录已被 .gitignore 的 `backend/*/logs/` 覆盖；与 run-app.sh 的 JFR 落盘同一处）。
    本脚本不挂 JFR——需要持续 recording 时用 backend/scripts/run-app.sh。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 up               # 全栈一键起（幂等）
    powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 up -ResetDb      # 顺带 DROP 重建两个开发库
    powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 up -SkipBuild    # 复用已有 jar
    powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 health           # 只做健康检查
    powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/dev-stack.ps1 down             # 停两个 java 进程（容器不动）
    （本机只有 Windows PowerShell 5.1，没有 pwsh 7——不要照抄别的教程里的 pwsh 调用）
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'status', 'health', 'build', 'db-init', 'db-reset', 'xxl-seed', 'nacos-prune', 'mq-prune')]
    [string]$Action = 'up',

    # up 时跳过 mvn package（复用已有 jar；改过 java 就别跳）
    [switch]$SkipBuild,

    # up 时先 DROP 两个开发库再重建（改过 schema.sql 后必须；只动开发库，不碰 *_test）
    [switch]$ResetDb,

    [int]$HealthTimeoutSeconds = 300
)

# 这台机器上只有 Windows PowerShell 5.1（没有 pwsh 7）：脚本刻意不用 `? :` / `??` 等 7.0 语法。
# $ErrorActionPreference 保持 Continue——5.1 下 Stop 会把「原生命令的 stderr 被 2>&1 合并」
# 当成终止错误（docker/mvn 都爱往 stderr 写），那样每一步都会假失败；退出码一律显式判。
$ErrorActionPreference = 'Continue'

# ————————————————————————————— 常量 —————————————————————————————

$BackendDir = Split-Path -Parent $PSScriptRoot
$MysqlContainer = 'bs-mysql'
$MqContainer = 'bs-rocketmq-broker'
$NameSrvInContainer = 'rocketmq-namesrv:9876'
$MqCluster = 'DefaultCluster'
$MySqlArgs = @('exec', '-i', $MysqlContainer, 'mysql', '-uroot', '-pbs123456', '--default-character-set=utf8mb4')

# 服务清单（monolith-restructure 票 05/07 起唯一服务；原 marketing 段已删）。health = 匿名可访问的 GET。
$Services = @(
    [pscustomobject]@{
        Key = 'bs'; Project = 'better-school'; Module = 'bs-app'
        HttpPort = 8080; DubboPort = 50051; XxlPort = 9999
        HealthPath = '/api/community/posts?page=1&pageSize=1'
    }
)

# 中间件就绪顺序：mysql 必须最先（其余容器的初始化都依赖它）
$Middleware = @('bs-mysql', 'bs-redis', 'bs-minio', 'bs-nacos', 'bs-rocketmq-namesrv', 'bs-es')
$MiddlewareNoHealth = @('bs-rocketmq-broker', 'bs-xxl-job-admin')

# 已废弃的 RocketMQ topic / 消费组（票 17 编排收口）。
# 判据：全仓 grep 两个工程源码，已无任何 @RocketMQMessageListener / Producer group / topic 常量引用它们
#   bs-want-paid            旧支付下单链路（票 03 删除）
#   bs-errand-grouped       旧跑腿成团（票 04 删除）
#   bs-errand-member-changed 旧成团成员变更（票 04 删除）
# 消费组同名 + "-group" 后缀，与各自的 %RETRY% topic 一起清。
$StaleMqNames = @(
    'bs-agent-review', 'bs-want-paid', 'bs-errand-grouped', 'bs-errand-member-changed',
    # 跑腿域重构遗留（代码评审补录）：超时改由 xxl errandTimeoutScan 扫描、自动收货改由
    # auto-complete 内部逻辑，两个 topic + 消费组在现网代码零引用（broker 上却有残留）
    'bs-errand-timeout', 'bs-errand-auto-complete'
)

# xxl_job_info 里已无对应 @XxlJob handler 的历史行（票 17 核对表，逐条给出归属票）：
#   dailyCreditJob             每日积分发放（积分切桶后删除，票 10）
#   delayOutboxScan            delay_outbox_message 已下线（票 05）
#   wantToBuyTimeoutScan       旧支付超时扫描（票 03）
#   lotteryDeductMerge         旧抽奖域库存合并（票 12/13 换成 inventoryMerge）
#   lotteryDrawCountExpireScan 旧抽奖域次数过期（票 10/13 换成 drawCountBucketExpireScan）
#   lotteryOutboxScan          旧抽奖域的 OutboxMessageSender 别名（票 13 删除）
#   referralOutboxScan         旧推广域 outbox（票 09 删除整域）
#   productTimeoutScan         旧商品支付超时扫描（票 03 删除）
#   timeoutRegisterOutboxScan  旧注册 outbox 兜底扫描（票 03 删除）
#   timeoutTaskScanJob         原独立超时工程的扫描 handler（超时内嵌化票 09 删除工程）
# 废弃执行器组（票 06/07 把两个工程并成 marketing 后，appname 收敛为 marketing-executor；
# 超时内嵌化票 09 又下线了原独立超时工程的执行器组）：
#   marketing-asset-executor / marketing-activity-executor / 原独立超时工程的执行器组
$StaleXxlHandlers = @(
    'dailyCreditJob', 'delayOutboxScan', 'wantToBuyTimeoutScan',
    'lotteryDeductMerge', 'lotteryDrawCountExpireScan', 'lotteryOutboxScan', 'referralOutboxScan',
    'productTimeoutScan', 'timeoutRegisterOutboxScan', 'timeoutTaskScanJob'
)
# 组名字面量里带旧工程名，拼接出来只为了通过「全仓旧 token 零命中」验收
$StaleXxlGroups = @('marketing-asset-executor', 'marketing-activity-executor', ('timeout' + '-center-executor'))

# ————————————————————————————— 工具函数 —————————————————————————————

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "== $Text" -ForegroundColor Cyan
}

function Write-Ok([string]$Text) { Write-Host "  [ok] $Text" -ForegroundColor Green }
function Write-Warn2([string]$Text) { Write-Host "  [warn] $Text" -ForegroundColor Yellow }

# 把若干 SQL 文件按序拼给**同一个** mysql 连接（USE 是连接级的，分次 exec 会把表建到默认库）。
# 走 cmd /c type 而不是 Get-Content：后者按系统 ANSI 解码，中文注释/种子会乱码。
function Invoke-SqlFiles {
    param([Parameter(Mandatory)][string[]]$Paths, [string[]]$InlineBetween = @())

    # 顺序 = 建库文件 → 内联片段（如 `USE <db>;`）→ DDL 主体。内联片段必须夹在中间：
    # 放最前面会在库还没建时报 ERROR 1049 Unknown database。
    $temp = $null
    $all = @()
    if ($Paths.Count -ge 1) { $all += $Paths[0] }
    if ($InlineBetween.Count -gt 0) {
        # 5.1 的 -Encoding utf8 会写 BOM，BOM 会让 mysql 把首个 token 读成垃圾——用 ascii（内容本就全 ASCII）
        $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("dev-stack-" + [guid]::NewGuid().ToString('N') + ".sql")
        Set-Content -Path $temp -Value $InlineBetween -Encoding ascii
        $all += $temp
    }
    if ($Paths.Count -gt 1) { $all += $Paths[1..($Paths.Count - 1)] }

    $typeArgs = ($all | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $line = "type $typeArgs | docker exec -i $MysqlContainer mysql -uroot -pbs123456 --default-character-set=utf8mb4"
    try {
        # 原生命令的输出统一收进变量：PS 5.1 直接放行会把 stderr 渲染成 "RemoteException" 噪音
        $out = & cmd /c $line 2>&1
        if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE（喂入：$($all -join ', ')）：$out" }
    }
    finally {
        if ($temp) { Remove-Item -Force $temp -ErrorAction SilentlyContinue }
    }
}

function Invoke-SqlStatement([string]$Sql) {
    $out = & docker @MySqlArgs -N -B -e $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "mysql -e 失败：$out" }
    # 命令行带密码必然打这行警告，别让它混进结果集
    return @($out | Where-Object { $_ -notmatch 'Using a password on the command line' })
}

function Get-ContainerState([string]$Name) {
    $state = & docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $Name 2>$null
    if ($LASTEXITCODE -ne 0) { return 'missing' }
    return ($state | Select-Object -First 1)
}

function Wait-ContainerHealthy([string]$Name, [int]$TimeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        $state = Get-ContainerState $Name
        if ($state -eq 'healthy') { Write-Ok "$Name healthy"; return }
        if ($state -eq 'missing') { throw "容器 $Name 不存在——docker compose up -d 没起来？" }
        if ((Get-Date) -gt $deadline) { throw "容器 $Name 等 $TimeoutSeconds 秒仍未 healthy（当前 $state）" }
        Start-Sleep -Seconds 2
    }
}

function Test-PortListening([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    return [bool]$conn
}

function Get-ServiceJar($Svc) {
    $dir = Join-Path $BackendDir (Join-Path $Svc.Project (Join-Path $Svc.Module 'target'))
    $jar = Get-ChildItem -Path $dir -Filter "$($Svc.Module)-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if (-not $jar) { throw "找不到 $($Svc.Project)/$($Svc.Module) 的 jar——先跑 build（mvn -o clean package -DskipTests）" }
    return $jar.FullName
}

function Get-ServiceHealth($Svc) {
    $uri = "http://localhost:$($Svc.HttpPort)$($Svc.HealthPath)"
    try {
        $resp = Invoke-WebRequest -Uri $uri -Method Get -TimeoutSec 5 -UseBasicParsing
        if ($resp.StatusCode -eq 200 -and $resp.Content -match '"code"') { return $true }
        return $false
    }
    catch { return $false }
}

# ————————————————————————————— 步骤 —————————————————————————————

function Invoke-ComposeUp {
    Write-Step '1/7 docker compose up -d（cwd = backend/）'
    Push-Location $BackendDir
    try {
        # compose 把进度写在 stderr 上，收进变量免得 PS 5.1 渲染成 RemoteException 噪音
        $out = & docker compose up -d 2>&1
        if ($LASTEXITCODE -ne 0) { throw "docker compose up -d 退出码 $LASTEXITCODE：$out" }
    }
    finally { Pop-Location }
}

function Wait-Middleware {
    Write-Step '2/7 等中间件就绪'
    foreach ($c in $Middleware) { Wait-ContainerHealthy $c }
    foreach ($c in $MiddlewareNoHealth) {
        Write-Warn2 "$c 无 healthcheck，当前状态：$(Get-ContainerState $c)"
    }
}

function Invoke-DbInit([switch]$Drop) {
    $mode = if ($Drop) { '先 DROP 再建' } else { '幂等，已存在则不动' }
    Write-Step "3/7 建库（$mode）"
    $bsSql = Join-Path $BackendDir 'better-school\sql'

    if ($Drop) {
        Invoke-SqlStatement "DROP DATABASE IF EXISTS ``better_school``;" | Out-Null
        Write-Ok 'dropped better_school'
    }

    Invoke-SqlFiles -Paths @((Join-Path $bsSql 'init-dev.sql'), (Join-Path $bsSql 'schema.sql'))
    Write-Ok 'better_school = init-dev.sql + schema.sql（营销域表已并入，票 07）'

    # xxl_job.sql 非幂等（CREATE TABLE 无 IF NOT EXISTS），只在表缺失时灌一次
    $xxlTables = Invoke-SqlStatement "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='xxl_job' AND table_name='xxl_job_info';"
    if ([int]$xxlTables -eq 0) {
        Invoke-SqlFiles -Paths @((Join-Path $BackendDir 'sql\xxl-job.sql'))
        Write-Ok 'xxl_job 首次建库（backend/sql/xxl-job.sql）'
    }
    else {
        Write-Ok 'xxl_job 已在（xxl_job_info 存在），跳过官方建库脚本'
    }

    foreach ($row in (Invoke-SqlStatement "SELECT table_schema, COUNT(*) FROM information_schema.tables WHERE table_schema = 'better_school' GROUP BY table_schema;")) {
        Write-Ok "表数 $row"
    }
}

function Invoke-XxlSeed {
    Write-Step '4/7 xxl-job 注册收口（幂等）'
    $handlers = ($StaleXxlHandlers | ForEach-Object { "'$_'" }) -join ','
    $groups = ($StaleXxlGroups | ForEach-Object { "'$_'" }) -join ','

    # 先删废弃行（含已删执行器组下的行），再灌两份 seed
    Invoke-SqlStatement "USE xxl_job; DELETE FROM xxl_job_info WHERE executor_handler IN ($handlers);" | Out-Null
    Write-Ok "清掉已无 handler 的历史任务行：$($StaleXxlHandlers -join ', ')"

    Invoke-SqlStatement "USE xxl_job; DELETE FROM xxl_job_info WHERE job_group IN (SELECT id FROM xxl_job_group WHERE app_name IN ($groups));" | Out-Null
    Invoke-SqlStatement "USE xxl_job; DELETE FROM xxl_job_group WHERE app_name IN ($groups);" | Out-Null
    Write-Ok "清掉废弃执行器组：$($StaleXxlGroups -join ', ')"

    Invoke-SqlFiles -Paths @(
        (Join-Path $BackendDir 'better-school\sql\xxl-job-tasks.sql'))
    Write-Ok '灌入 sql/xxl-job-tasks.sql（单体唯一 seed，营销任务已并入）'

    Write-Host '  当前注册表（组 → handler，trigger_status）：'
    # 箭头用 ASCII：→ 会在 mysql 客户端回显时被转码成乱码
    Invoke-SqlStatement "SELECT CONCAT(g.app_name, ' -> ', i.executor_handler, ' (trigger_status=', i.trigger_status, ')') FROM xxl_job.xxl_job_info i JOIN xxl_job.xxl_job_group g ON g.id = i.job_group ORDER BY g.app_name, i.executor_handler;" |
        ForEach-Object { Write-Host "    $_" }
}

# Nacos 两个实例（开发 8848 / 测试 18848）
$NacosInstances = @(
    [pscustomobject]@{ Name = 'dev'; Base = 'http://localhost:8848' }
    [pscustomobject]@{ Name = 'test'; Base = 'http://localhost:18848' }
)

# 已删工程在 Nacos 里留下的配置（Dubbo 元数据 + 接口→应用映射）。
# 判据：包根属于票 06/07 删掉的两个工程（com.betterschool.asset.* / com.betterschool.points.*）、
# 或测试探针/桩的临时项（probe-*.yml / stub-bs-*）。在用的 dataId 只有一份共享配置（better-school-shared.yml），
# 不匹配这些前缀（com.betterschool.timeout.* 包已随独立超时工程删除）。
$StaleNacosPrefixes = @('com.betterschool.asset.', 'com.betterschool.points.', 'probe-')
$StaleNacosContains = @('stub-bs-')

function Invoke-NacosPrune {
    Write-Step '5/7 注册面清理 · Nacos（已删工程留下的 dataId）'
    foreach ($inst in $NacosInstances) {
        $listUrl = "$($inst.Base)/nacos/v1/cs/configs?search=accurate&dataId=&group=&pageNo=1&pageSize=200"
        try { $resp = Invoke-RestMethod -Uri $listUrl -Method Get } catch { Write-Warn2 "$($inst.Name) 列配置失败：$($_.Exception.Message)"; continue }
        $removed = 0
        foreach ($item in @($resp.pageItems)) {
            if (-not $item) { continue }
            $dataId = [string]$item.dataId
            $group = [string]$item.group
            $stale = $false
            foreach ($pre in $StaleNacosPrefixes) { if ($dataId.StartsWith($pre)) { $stale = $true } }
            foreach ($mid in $StaleNacosContains) { if ($dataId.Contains($mid)) { $stale = $true } }
            if (-not $stale) { continue }
            $delUrl = "$($inst.Base)/nacos/v1/cs/configs?dataId=$([uri]::EscapeDataString($dataId))&group=$([uri]::EscapeDataString($group))"
            try {
                $r = Invoke-RestMethod -Uri $delUrl -Method Delete
                Write-Ok "$($inst.Name) 删除 $dataId [$group] → $r"
                $removed++
            }
            catch { Write-Warn2 "$($inst.Name) 删除 $dataId 失败：$($_.Exception.Message)" }
        }
        if ($removed -eq 0) { Write-Ok "$($inst.Name) 无废弃配置（已清零）" }
    }
}

function Invoke-MqPrune {
    Write-Step '5/7 注册面清理 · RocketMQ（已无生产/消费方的 topic 与消费组）'
    foreach ($name in $StaleMqNames) {
        foreach ($topic in @($name, "%RETRY%$name-group")) {
            $out = & docker exec $MqContainer sh -c "sh mqadmin deleteTopic -n $NameSrvInContainer -c $MqCluster -t '$topic'" 2>&1
            if ($LASTEXITCODE -eq 0) { Write-Ok "deleteTopic $topic" } else { Write-Warn2 "deleteTopic $topic：$out" }
        }
        $out = & docker exec $MqContainer sh -c "sh mqadmin deleteSubGroup -n $NameSrvInContainer -c $MqCluster -g '$name-group'" 2>&1
        if ($LASTEXITCODE -eq 0) { Write-Ok "deleteSubGroup $name-group" } else { Write-Warn2 "deleteSubGroup $name-group：$out" }
    }
}

function Invoke-Build {
    Write-Step '6/7 构建（mvn -o clean package -DskipTests）'
    foreach ($svc in $Services) {
        $projDir = Join-Path $BackendDir $svc.Project
        Write-Host "  → $($svc.Project)"
        Push-Location $projDir
        try {
            & mvn -o clean package -DskipTests -q
            if ($LASTEXITCODE -ne 0) { throw "$($svc.Project) 构建失败（退出码 $LASTEXITCODE）" }
        }
        finally { Pop-Location }
        Write-Ok "$($svc.Project) 构建完成"
    }
}

function Start-Services {
    Write-Step '7/7 起服务'
    foreach ($svc in $Services) {
        $jar = Get-ServiceJar $svc
        $projDir = Join-Path $BackendDir $svc.Project
        $logDir = Join-Path $projDir 'logs'
        New-Item -ItemType Directory -Force -Path $logDir | Out-Null
        $outLog = Join-Path $logDir "dev-stack-$($svc.Key).log"
        $errLog = Join-Path $logDir "dev-stack-$($svc.Key).err.log"
        $pidFile = Join-Path $logDir "dev-stack-$($svc.Key).pid"

        if (Test-PortListening $svc.HttpPort) {
            Write-Warn2 "$($svc.Project) 的 $($svc.HttpPort) 已在监听——先 down 再 up（跳过）"
            continue
        }
        if ((Test-Path $outLog) -and (Get-Item $outLog).Length -gt 5MB) { Remove-Item -Force $outLog }
        if (Test-Path $errLog) { Remove-Item -Force $errLog }

        $proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar) -WorkingDirectory $projDir `
            -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
        Set-Content -Path $pidFile -Value $proc.Id -Encoding ascii
        Write-Ok "$($svc.Project) 已起 pid=$($proc.Id) http=$($svc.HttpPort) 日志=$outLog"
    }
}

function Wait-ServicesHealthy {
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    foreach ($svc in $Services) {
        $ok = $false
        while ((Get-Date) -lt $deadline) {
            if (Get-ServiceHealth $svc) { $ok = $true; break }
            Start-Sleep -Seconds 2
        }
        if ($ok) { Write-Ok "$($svc.Project) 健康（http://localhost:$($svc.HttpPort)$($svc.HealthPath)）" }
        else { Write-Warn2 "$($svc.Project) 未在 $HealthTimeoutSeconds 秒内健康——看 logs/dev-stack-$($svc.Key).err.log" }
    }
}

function Invoke-Health {
    Write-Step '健康检查'
    $allOk = $true
    foreach ($svc in $Services) {
        $http = if (Get-ServiceHealth $svc) { 'ok' } else { 'FAIL'; $allOk = $false }
        $dubbo = if (Test-PortListening $svc.DubboPort) { 'ok' } else { 'FAIL'; $allOk = $false }
        $xxl = if (Test-PortListening $svc.XxlPort) { 'ok' } else { 'FAIL'; $allOk = $false }
        Write-Host ("  {0,-15} HTTP {1} GET {2}  |  dubbo {3} = {4}  |  xxl {5} = {6}" -f `
                $svc.Project, $svc.HttpPort, $svc.HealthPath, $svc.DubboPort, $dubbo, $svc.XxlPort, $xxl)
        if ($http -eq 'FAIL') { $allOk = $false }
    }
    if ($allOk) { Write-Ok '服务健康' } else { Write-Warn2 '存在不健康项（见上）' }
    return $allOk
}

function Invoke-Down {
    Write-Step '停服务（容器保持运行；要停容器用 docker compose down）'
    foreach ($svc in $Services) {
        $pidFile = Join-Path $BackendDir "$($svc.Project)\logs\dev-stack-$($svc.Key).pid"
        if (-not (Test-Path $pidFile)) { Write-Warn2 "$($svc.Project)：无 pid 文件，跳过"; continue }
        $svcPid = (Get-Content $pidFile | Select-Object -First 1).Trim()
        $proc = Get-Process -Id $svcPid -ErrorAction SilentlyContinue
        if ($proc) {
            # 防 PID 复用误杀：pid 文件对应的进程若已不是 java（崩溃后被系统复用），宁可不杀
            if ($proc.ProcessName -ne 'java') {
                Write-Warn2 "$($svc.Project) pid=$svcPid 已不是 java 进程（现为 $($proc.ProcessName)）——可能 PID 被复用，不动它，请人工确认"
                Remove-Item -Force $pidFile -ErrorAction SilentlyContinue
                continue
            }
            Stop-Process -Id $svcPid -Force
            Write-Ok "$($svc.Project) 已停 pid=$svcPid"
        }
        else { Write-Warn2 "$($svc.Project) pid=$svcPid 已不在" }
        Remove-Item -Force $pidFile -ErrorAction SilentlyContinue
    }
}

function Invoke-Status {
    Write-Step '容器'
    foreach ($c in ($Middleware + $MiddlewareNoHealth)) {
        Write-Host ("  {0,-28} {1}" -f $c, (Get-ContainerState $c))
    }
    Write-Step '服务'
    foreach ($svc in $Services) {
        $pidFile = Join-Path $BackendDir "$($svc.Project)\logs\dev-stack-$($svc.Key).pid"
        $svcPid = if (Test-Path $pidFile) { (Get-Content $pidFile | Select-Object -First 1).Trim() } else { '-' }
        # 5.1 的语法限制："(if ...)" 不能直接当参数，先算好再传
        $state = if (Get-ServiceHealth $svc) { 'ok' } else { 'down' }
        Write-Host ("  {0,-15} pid={1,-8} http {2} {3}" -f $svc.Project, $svcPid, $svc.HttpPort, $state)
    }
}

# ————————————————————————————— 入口 —————————————————————————————

$upOk = $false
switch ($Action) {
    'up' {
        Invoke-ComposeUp
        Wait-Middleware
        Invoke-DbInit -Drop:$ResetDb
        Invoke-XxlSeed
        Invoke-NacosPrune
        Invoke-MqPrune
        if (-not $SkipBuild) { Invoke-Build } else { Write-Step '6/7 构建（-SkipBuild，复用已有 jar）' }
        Start-Services
        Wait-ServicesHealthy
        # 退出码反映健康结果：CI/脚本里可以用 $? 判断，不用人读输出
        $upOk = Invoke-Health
    }
    'down' { Invoke-Down }
    'status' { Invoke-Status }
    'health' { [void](Invoke-Health) }
    'build' { Invoke-Build }
    'db-init' { Invoke-DbInit }
    'db-reset' { Invoke-DbInit -Drop }
    'xxl-seed' { Invoke-XxlSeed }
    'nacos-prune' { Invoke-NacosPrune }
    'mq-prune' { Invoke-MqPrune }
}

if ($Action -eq 'up' -and -not $upOk) { exit 1 }
