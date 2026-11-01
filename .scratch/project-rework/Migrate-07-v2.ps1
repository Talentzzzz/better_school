<#
Ticket 07 mechanical relocation (v2, ASCII only).

v1 bug: the rewrite rules were built with nested array literals, which Windows PowerShell
5.1 flattens, so $r[0] / $r[1] indexed single characters and turned "c" into "o".
v2 builds the rules as PSCustomObjects with named properties and asserts the shape
before touching any file.

Steps: (1) collect the files to move, (2) move + delete de-dup copies, (3) naive FQCN
prefix swap, (4) exact-FQCN redirects, (5) package-declaration rewrites.
#>
[CmdletBinding()]
param(
  [string]$RepoRoot = 'C:\Users\a1368\Desktop\better_school',
  [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)

$srcRoot = Join-Path $RepoRoot 'backend\marketing-activity'
$dstRoot = Join-Path $RepoRoot 'backend\marketing'
$OLD = 'com.betterschool.activity'
$NEW = 'com.betterschool.marketing'

# ---------------------------------------------------------------- rules ----
# kind Fqcn : exact fully-qualified class name replacement
# kind Pkg  : replacement inside a "package <x>;" declaration only
$rules = @(
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.lottery.exception.AppException";                       New="$NEW.types.common.AppException" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.referral.exception.AppException";                      New="$NEW.types.common.AppException" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.lottery.enums.ResponseCode";                           New="$NEW.types.common.ResponseCode" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.referral.enums.ResponseCode";                          New="$NEW.types.common.ResponseCode" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.lottery.enums.OutboxStatus";                           New="$NEW.types.common.OutboxStatus" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.referral.enums.OutboxStatus";                          New="$NEW.types.common.OutboxStatus" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.lottery.common.Constants";                             New="$NEW.types.lottery.Constants" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.types.referral.common.Constants";                            New="$NEW.types.referral.Constants" }
  [pscustomobject]@{ Kind='Pkg';  Old="$NEW.types.lottery.common";                                       New="$NEW.types.lottery" }
  [pscustomobject]@{ Kind='Pkg';  Old="$NEW.types.referral.common";                                      New="$NEW.types.referral" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.api.lottery.response.Response";                              New="$NEW.api.common.response.Response" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.api.referral.response.Response";                             New="$NEW.api.common.response.Response" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.api.lottery.response.PageResult";                            New="$NEW.api.common.response.PageResult" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.api.referral.response.PageResult";                           New="$NEW.api.common.response.PageResult" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.api.lottery.dto.UserBasicResponseDTO";                       New="$NEW.api.common.dto.UserBasicResponseDTO" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.api.referral.dto.UserBasicResponseDTO";                      New="$NEW.api.common.dto.UserBasicResponseDTO" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.domain.lottery.adapter.port.marketing.IUserQueryPort";       New="$NEW.domain.common.adapter.port.marketing.IUserQueryPort" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.domain.referral.adapter.port.marketing.IUserQueryPort";      New="$NEW.domain.common.adapter.port.marketing.IUserQueryPort" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.domain.lottery.model.valobj.marketing.UserBasicVO";          New="$NEW.domain.common.model.valobj.marketing.UserBasicVO" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.domain.referral.model.valobj.marketing.UserBasicVO";         New="$NEW.domain.common.model.valobj.marketing.UserBasicVO" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.domain.lottery.model.valobj.LotteryDrawnMessage";            New="$NEW.types.common.LotteryDrawnMessage" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.domain.lottery.model.valobj.ReferralGroupedMessage";         New="$NEW.types.common.ReferralGroupedMessage" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.domain.referral.model.valobj.ReferralGroupedMessage";        New="$NEW.types.common.ReferralGroupedMessage" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.lottery.dao.outbox.po.OutboxPO";              New="$NEW.infrastructure.common.dao.outbox.po.OutboxPO" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.referral.dao.outbox.po.OutboxPO";             New="$NEW.infrastructure.common.dao.outbox.po.OutboxPO" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.lottery.dao.outbox.IOutboxDao";               New="$NEW.infrastructure.common.dao.outbox.IOutboxDao" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.referral.dao.outbox.IOutboxDao";              New="$NEW.infrastructure.common.dao.outbox.IOutboxDao" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.lottery.outbox.OutboxMessageStore";           New="$NEW.infrastructure.common.outbox.OutboxMessageStore" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.referral.outbox.OutboxMessageStore";          New="$NEW.infrastructure.common.outbox.OutboxMessageStore" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.lottery.mq.OutboxMessageSender";              New="$NEW.infrastructure.common.outbox.OutboxMessageSender" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.referral.mq.OutboxMessageSender";             New="$NEW.infrastructure.common.outbox.OutboxMessageSender" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.lottery.security.JwtAuthenticationFilter";    New="$NEW.infrastructure.common.security.JwtAuthenticationFilter" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.referral.security.JwtAuthenticationFilter";   New="$NEW.infrastructure.common.security.JwtAuthenticationFilter" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.lottery.security.JwtService";                 New="$NEW.infrastructure.common.security.JwtService" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.referral.security.JwtService";                New="$NEW.infrastructure.common.security.JwtService" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.lottery.adapter.port.marketing.UserQueryPort";  New="$NEW.infrastructure.common.adapter.port.marketing.UserQueryPort" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.infrastructure.referral.adapter.port.marketing.UserQueryPort"; New="$NEW.infrastructure.common.adapter.port.marketing.UserQueryPort" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.trigger.lottery.http.GlobalExceptionHandler";                New="$NEW.trigger.common.http.GlobalExceptionHandler" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.trigger.referral.http.GlobalExceptionHandler";               New="$NEW.trigger.common.http.GlobalExceptionHandler" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.trigger.lottery.http.security.SecurityUtils";                New="$NEW.trigger.common.http.security.SecurityUtils" }
  [pscustomobject]@{ Kind='Fqcn'; Old="$OLD.trigger.referral.http.security.SecurityUtils";               New="$NEW.trigger.common.http.security.SecurityUtils" }
)

# ------------------------------------------------------------- guardrails ---
$bad = @($rules | Where-Object {
  ($_.PSObject.Properties.Name -notcontains 'Kind') -or
  ($_.PSObject.Properties.Name -notcontains 'Old')  -or
  ($_.PSObject.Properties.Name -notcontains 'New')  -or
  ($_.Old -isnot [string]) -or ($_.Old.Length -lt 12) -or (-not $_.Old.Contains('.')) -or
  ($_.New -isnot [string]) -or ($_.New.Length -lt 12) -or (-not $_.New.Contains('.'))
})
if ($bad.Count -gt 0) { throw ("rule shape assertion failed on {0} entries - refusing to touch files" -f $bad.Count) }
Write-Host ("rules: {0} (shape asserted)" -f $rules.Count)
$rules = $rules | Sort-Object -Property @{ Expression = { $_.Old.Length }; Descending = $true }

# ------------------------------------------------- 1. files to move/delete ---
$deleteRel = @(
  'marketing-activity-types/src/main/java/com/betterschool/activity/types/lottery/exception/AppException.java'
  'marketing-activity-types/src/main/java/com/betterschool/activity/types/referral/exception/AppException.java'
  'marketing-activity-types/src/main/java/com/betterschool/activity/types/lottery/enums/ResponseCode.java'
  'marketing-activity-types/src/main/java/com/betterschool/activity/types/referral/enums/ResponseCode.java'
  'marketing-activity-types/src/main/java/com/betterschool/activity/types/lottery/enums/OutboxStatus.java'
  'marketing-activity-types/src/main/java/com/betterschool/activity/types/referral/enums/OutboxStatus.java'
  'marketing-activity-api/src/main/java/com/betterschool/activity/api/lottery/response/Response.java'
  'marketing-activity-api/src/main/java/com/betterschool/activity/api/referral/response/Response.java'
  'marketing-activity-api/src/main/java/com/betterschool/activity/api/lottery/response/PageResult.java'
  'marketing-activity-api/src/main/java/com/betterschool/activity/api/referral/response/PageResult.java'
  'marketing-activity-api/src/main/java/com/betterschool/activity/api/lottery/dto/UserBasicResponseDTO.java'
  'marketing-activity-api/src/main/java/com/betterschool/activity/api/referral/dto/UserBasicResponseDTO.java'
  'marketing-activity-domain/src/main/java/com/betterschool/activity/domain/lottery/adapter/port/marketing/IUserQueryPort.java'
  'marketing-activity-domain/src/main/java/com/betterschool/activity/domain/referral/adapter/port/marketing/IUserQueryPort.java'
  'marketing-activity-domain/src/main/java/com/betterschool/activity/domain/lottery/model/valobj/marketing/UserBasicVO.java'
  'marketing-activity-domain/src/main/java/com/betterschool/activity/domain/referral/model/valobj/marketing/UserBasicVO.java'
  'marketing-activity-domain/src/main/java/com/betterschool/activity/domain/lottery/model/valobj/LotteryDrawnMessage.java'
  'marketing-activity-domain/src/main/java/com/betterschool/activity/domain/lottery/model/valobj/ReferralGroupedMessage.java'
  'marketing-activity-domain/src/main/java/com/betterschool/activity/domain/referral/model/valobj/ReferralGroupedMessage.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/lottery/dao/outbox/IOutboxDao.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/referral/dao/outbox/IOutboxDao.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/lottery/dao/outbox/po/OutboxPO.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/referral/dao/outbox/po/OutboxPO.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/lottery/outbox/OutboxMessageStore.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/referral/outbox/OutboxMessageStore.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/lottery/mq/OutboxMessageSender.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/referral/mq/OutboxMessageSender.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/lottery/security/JwtService.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/referral/security/JwtService.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/lottery/security/JwtAuthenticationFilter.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/referral/security/JwtAuthenticationFilter.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/lottery/adapter/port/marketing/UserQueryPort.java'
  'marketing-activity-infrastructure/src/main/java/com/betterschool/activity/infrastructure/referral/adapter/port/marketing/UserQueryPort.java'
  'marketing-activity-trigger/src/main/java/com/betterschool/activity/trigger/lottery/http/GlobalExceptionHandler.java'
  'marketing-activity-trigger/src/main/java/com/betterschool/activity/trigger/referral/http/GlobalExceptionHandler.java'
  'marketing-activity-trigger/src/main/java/com/betterschool/activity/trigger/lottery/http/security/SecurityUtils.java'
  'marketing-activity-trigger/src/main/java/com/betterschool/activity/trigger/referral/http/security/SecurityUtils.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/MarketingActivityApplication.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/config/FqcnAnnotationBeanNameGenerator.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/config/RocketMQConsumerConfig.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/config/TransactionConfig.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/config/VirtualThreadRocketMQListenerContainerRegistrar.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/config/XxlJobConfig.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/config/SecurityConfig.java'
  'marketing-activity-app/src/main/java/com/betterschool/activity/config/MybatisConfig.java'
  'marketing-activity-app/src/test/java/com/betterschool/activity/integration/support/VtConsumeThreadProbe.java'
  'marketing-activity-app/src/test/java/com/betterschool/activity/integration/support/VtWebThreadProbe.java'
  'marketing-activity-app/src/test/java/com/betterschool/activity/integration/ConsumeHandlerVirtualThreadIntegrationTest.java'
  'marketing-activity-app/src/test/java/com/betterschool/activity/integration/WebHandlerVirtualThreadIntegrationTest.java'
)

$appTestMap = @(
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/LotteryBucketMergeIntegrationTest.java';  To='marketing-app/src/test/java/com/betterschool/marketing/integration/lottery/LotteryBucketMergeIntegrationTest.java';  Sub='lottery' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/LotteryCatalogIntegrationTest.java';      To='marketing-app/src/test/java/com/betterschool/marketing/integration/lottery/LotteryCatalogIntegrationTest.java';      Sub='lottery' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/LotteryDrawDegradeIntegrationTest.java';  To='marketing-app/src/test/java/com/betterschool/marketing/integration/lottery/LotteryDrawDegradeIntegrationTest.java';  Sub='lottery' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/LotteryDrawIntegrationTest.java';         To='marketing-app/src/test/java/com/betterschool/marketing/integration/lottery/LotteryDrawIntegrationTest.java';         Sub='lottery' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/LotteryExchangeIntegrationTest.java';     To='marketing-app/src/test/java/com/betterschool/marketing/integration/lottery/LotteryExchangeIntegrationTest.java';     Sub='lottery' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/LotteryUserPrizeIntegrationTest.java';    To='marketing-app/src/test/java/com/betterschool/marketing/integration/lottery/LotteryUserPrizeIntegrationTest.java';    Sub='lottery' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/ReferralGroupIntegrationTest.java';       To='marketing-app/src/test/java/com/betterschool/marketing/integration/referral/ReferralGroupIntegrationTest.java';       Sub='referral' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/ReferralInviteCodeIntegrationTest.java';  To='marketing-app/src/test/java/com/betterschool/marketing/integration/referral/ReferralInviteCodeIntegrationTest.java';  Sub='referral' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/ReferralInviteeListIntegrationTest.java'; To='marketing-app/src/test/java/com/betterschool/marketing/integration/referral/ReferralInviteeListIntegrationTest.java'; Sub='referral' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/support/LotteryApiTestBase.java';         To='marketing-app/src/test/java/com/betterschool/marketing/integration/support/LotteryApiTestBase.java';         Sub='support' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/support/ReferralApiTestBase.java';        To='marketing-app/src/test/java/com/betterschool/marketing/integration/support/ReferralApiTestBase.java';        Sub='support' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/support/StubPointsExchangePort.java';     To='marketing-app/src/test/java/com/betterschool/marketing/integration/support/StubPointsExchangePort.java';     Sub='support' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/support/StubUserQueryPort.java';          To='marketing-app/src/test/java/com/betterschool/marketing/integration/support/StubUserQueryPort.java';          Sub='support' }
  [pscustomobject]@{ From='marketing-activity-app/src/test/java/com/betterschool/activity/integration/support/SwitchablePrizeBucketPort.java';  To='marketing-app/src/test/java/com/betterschool/marketing/integration/support/SwitchablePrizeBucketPort.java';  Sub='support' }
)

# --------------------------------------------------------- 2. collect main ---
$moved = New-Object System.Collections.Generic.List[object]
foreach ($layer in @('types','api','domain','infrastructure','trigger')) {
  $srcMod = Join-Path $srcRoot "marketing-activity-$layer"
  if (-not (Test-Path $srcMod)) { throw "missing module dir: $srcMod" }
  foreach ($f in (Get-ChildItem -Path (Join-Path $srcMod 'src') -Recurse -Filter *.java -File)) {
    $rel = $f.FullName.Substring($srcRoot.Length + 1).Replace('\','/')
    if ($deleteRel -contains $rel) { continue }
    $rel2 = $rel -replace '^marketing-activity-', 'marketing-'
    $rel2 = $rel2 -replace '/com/betterschool/activity/', '/com/betterschool/marketing/'
    if ($rel2 -eq $rel) { throw "path not rewritten: $rel" }
    $moved.Add([pscustomobject]@{ Old=$f.FullName; New=(Join-Path $dstRoot ($rel2.Replace('/','\'))); Sub='' })
  }
}
foreach ($m in $appTestMap) {
  $moved.Add([pscustomobject]@{ Old=(Join-Path $srcRoot ($m.From.Replace('/','\'))); New=(Join-Path $dstRoot ($m.To.Replace('/','\'))); Sub=$m.Sub })
}

Write-Host ("to move: {0}; to delete: {1}" -f $moved.Count, $deleteRel.Count)

# --------------------------------------------------------- 3. move/delete ---
if (-not $WhatIf) {
  foreach ($m in $moved) {
    if (-not (Test-Path $m.Old)) { throw "missing source: $($m.Old)" }
    $dir = Split-Path $m.New -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Move-Item -LiteralPath $m.Old -Destination $m.New -Force
  }
  foreach ($rel in $deleteRel) {
    $p = Join-Path $srcRoot ($rel.Replace('/','\'))
    if (Test-Path $p) { Remove-Item -LiteralPath $p -Force }
  }
}

# ------------------------------------------------------------ 4. rewrite ---
$hits = @{}
foreach ($r in $rules) { $hits[$r.Old] = 0 }
$changed = 0
foreach ($m in $moved) {
  $readFrom = if ($WhatIf) { $m.Old } else { $m.New }
  if (-not (Test-Path $readFrom)) { continue }
  $text = [System.IO.File]::ReadAllText($readFrom, $utf8)
  $orig = $text
  # 4a exact FQCN redirects FIRST (they are expressed with the old root, so they must be
  #    applied before the naive prefix swap rewrites the root)
  foreach ($r in $rules) {
    if ($r.Kind -ne 'Fqcn') { continue }
    if ($text.Contains($r.Old)) {
      $hits[$r.Old] = $hits[$r.Old] + ([regex]::Matches($text, [regex]::Escape($r.Old))).Count
      $text = $text.Replace($r.Old, $r.New)
    }
  }
  # 4b naive prefix swap (root + module path), then package declarations follow automatically
  $text = $text.Replace($OLD, $NEW)
  # 4d integration test sub-package: integration.<Leaf> -> integration.<sub>.<Leaf>
  if ($m.Sub -and $m.Sub -ne 'support') {
    $leaf = [System.IO.Path]::GetFileNameWithoutExtension($m.New)
    $text = $text.Replace("$NEW.integration.$leaf", "$NEW.integration.$($m.Sub).$leaf")
  }
  if ($text -ne $orig) {
    if (-not $WhatIf) { [System.IO.File]::WriteAllText($m.New, $text, $utf8) }
    $changed++
  }
}

Write-Host ("rewritten: {0} of {1}" -f $changed, $moved.Count)
Write-Host "--- rule hit counts (0 = redirect found no reference: investigate) ---"
foreach ($r in $rules) { if ($hits[$r.Old] -gt 0) { Write-Host ("{0,4}  [{1}] {2}" -f $hits[$r.Old], $r.Kind, $r.Old) } }
$zero = @($rules | Where-Object { $hits[$_.Old] -eq 0 })
Write-Host ("--- rules with 0 hits: {0} ---" -f $zero.Count)
$zero | ForEach-Object { Write-Host ("      [{0}] {1}" -f $_.Kind, $_.Old) }
