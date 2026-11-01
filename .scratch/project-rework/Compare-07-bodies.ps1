<#
Ticket 07 zero-behaviour-change evidence (ASCII only).

For every java file the relocation preserved, compare the OLD content (git HEAD of
backend/marketing-activity/...) with the NEW content (work tree of backend/marketing/...)
after normalising away exactly what the relocation is allowed to change:

  * the package root  com.betterschool.activity  <->  com.betterschool.marketing
  * de-dup class redirects (types.*.enums.ResponseCode -> types.common.ResponseCode, ...)
  * all whitespace and all comments
  * the package / import lines

Anything still differing is a real content delta and must be explained in the ticket Answer.
#>
[CmdletBinding()]
param([string]$RepoRoot = 'C:\Users\a1368\Desktop\better_school')
$ErrorActionPreference = 'Stop'
Set-Location $RepoRoot

$mktRoot = Join-Path $RepoRoot 'backend\marketing'
$actRoot = Join-Path $RepoRoot 'backend\marketing-activity'

# The activity-side originals are deleted from the work tree, so enumerate them from git HEAD.
$moved = New-Object System.Collections.Generic.List[object]
$gitPaths = git ls-tree -r --name-only HEAD -- backend/marketing-activity
foreach ($p in $gitPaths) {
  if ($p -notmatch '\.java$') { continue }
  if ($p -notmatch '/marketing-activity-(types|api|domain|infrastructure|trigger)/') { continue }
  $rel = $p.Substring('backend/marketing-activity/'.Length)
  $rel2 = ($rel -replace '^marketing-activity-', 'marketing-') -replace '/com/betterschool/activity/', '/com/betterschool/marketing/'
  $moved.Add([pscustomobject]@{ Old=('backend/marketing-activity/' + $rel); New=('backend/marketing/' + $rel2) })
}

function Normalize([string]$text) {
  $out = New-Object System.Collections.Generic.List[string]
  $inBlock = $false
  foreach ($line in ($text -split "`r?`n")) {
    $t = $line.Trim()
    if ($inBlock) { if ($t -match '\*/') { $inBlock = $false }; continue }
    if ($t -eq '') { continue }
    if ($t -match '^\s*package\s') { continue }
    if ($t -match '^\s*import\s') { continue }
    if ($t.StartsWith('/**')) { if ($t -notmatch '\*/') { $inBlock = $true }; continue }
    if ($t.StartsWith('/*'))  { if ($t -notmatch '\*/') { $inBlock = $true }; continue }
    if ($t.StartsWith('//'))  { continue }
    if ($t.StartsWith('*'))   { continue }
    $out.Add(($t -replace '\s+', ' '))
  }
  ($out -join '')
}

$redirects = @(
  'com.betterschool.marketing.types.lottery.enums.ResponseCode|com.betterschool.marketing.types.common.ResponseCode',
  'com.betterschool.marketing.types.referral.enums.ResponseCode|com.betterschool.marketing.types.common.ResponseCode',
  'com.betterschool.marketing.types.lottery.exception.AppException|com.betterschool.marketing.types.common.AppException',
  'com.betterschool.marketing.types.referral.exception.AppException|com.betterschool.marketing.types.common.AppException',
  'com.betterschool.marketing.types.lottery.enums.OutboxStatus|com.betterschool.marketing.types.common.OutboxStatus',
  'com.betterschool.marketing.types.referral.enums.OutboxStatus|com.betterschool.marketing.types.common.OutboxStatus',
  'com.betterschool.marketing.types.lottery.common.Constants|com.betterschool.marketing.types.lottery.Constants',
  'com.betterschool.marketing.types.referral.common.Constants|com.betterschool.marketing.types.referral.Constants',
  'com.betterschool.marketing.api.lottery.response.Response|com.betterschool.marketing.api.common.response.Response',
  'com.betterschool.marketing.api.referral.response.Response|com.betterschool.marketing.api.common.response.Response',
  'com.betterschool.marketing.api.lottery.response.PageResult|com.betterschool.marketing.api.common.response.PageResult',
  'com.betterschool.marketing.api.referral.response.PageResult|com.betterschool.marketing.api.common.response.PageResult',
  'com.betterschool.marketing.api.lottery.dto.UserBasicResponseDTO|com.betterschool.marketing.api.common.dto.UserBasicResponseDTO',
  'com.betterschool.marketing.api.referral.dto.UserBasicResponseDTO|com.betterschool.marketing.api.common.dto.UserBasicResponseDTO',
  'com.betterschool.marketing.domain.lottery.adapter.port.marketing.IUserQueryPort|com.betterschool.marketing.domain.common.adapter.port.marketing.IUserQueryPort',
  'com.betterschool.marketing.domain.referral.adapter.port.marketing.IUserQueryPort|com.betterschool.marketing.domain.common.adapter.port.marketing.IUserQueryPort',
  'com.betterschool.marketing.domain.lottery.model.valobj.marketing.UserBasicVO|com.betterschool.marketing.domain.common.model.valobj.marketing.UserBasicVO',
  'com.betterschool.marketing.domain.referral.model.valobj.marketing.UserBasicVO|com.betterschool.marketing.domain.common.model.valobj.marketing.UserBasicVO',
  'com.betterschool.marketing.domain.lottery.model.valobj.LotteryDrawnMessage|com.betterschool.marketing.types.common.LotteryDrawnMessage',
  'com.betterschool.marketing.domain.lottery.model.valobj.ReferralGroupedMessage|com.betterschool.marketing.types.common.ReferralGroupedMessage',
  'com.betterschool.marketing.domain.referral.model.valobj.ReferralGroupedMessage|com.betterschool.marketing.types.common.ReferralGroupedMessage',
  'com.betterschool.marketing.infrastructure.lottery.dao.outbox.po.OutboxPO|com.betterschool.marketing.infrastructure.common.dao.outbox.po.OutboxPO',
  'com.betterschool.marketing.infrastructure.referral.dao.outbox.po.OutboxPO|com.betterschool.marketing.infrastructure.common.dao.outbox.po.OutboxPO',
  'com.betterschool.marketing.infrastructure.lottery.dao.outbox.IOutboxDao|com.betterschool.marketing.infrastructure.common.dao.outbox.IOutboxDao',
  'com.betterschool.marketing.infrastructure.referral.dao.outbox.IOutboxDao|com.betterschool.marketing.infrastructure.common.dao.outbox.IOutboxDao',
  'com.betterschool.marketing.infrastructure.lottery.outbox.OutboxMessageStore|com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageStore',
  'com.betterschool.marketing.infrastructure.referral.outbox.OutboxMessageStore|com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageStore',
  'com.betterschool.marketing.infrastructure.lottery.mq.OutboxMessageSender|com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageSender',
  'com.betterschool.marketing.infrastructure.referral.mq.OutboxMessageSender|com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageSender',
  'com.betterschool.marketing.infrastructure.lottery.security.JwtService|com.betterschool.marketing.infrastructure.common.security.JwtService',
  'com.betterschool.marketing.infrastructure.referral.security.JwtService|com.betterschool.marketing.infrastructure.common.security.JwtService',
  'com.betterschool.marketing.infrastructure.lottery.security.JwtAuthenticationFilter|com.betterschool.marketing.infrastructure.common.security.JwtAuthenticationFilter',
  'com.betterschool.marketing.infrastructure.referral.security.JwtAuthenticationFilter|com.betterschool.marketing.infrastructure.common.security.JwtAuthenticationFilter',
  'com.betterschool.marketing.trigger.lottery.http.security.SecurityUtils|com.betterschool.marketing.trigger.common.http.security.SecurityUtils',
  'com.betterschool.marketing.trigger.referral.http.security.SecurityUtils|com.betterschool.marketing.trigger.common.http.security.SecurityUtils'
)

function Canon([string]$text) {
  $t = $text.Replace('com.betterschool.activity', 'com.betterschool.marketing')
  foreach ($r in $redirects) {
    $parts = $r -split '\|'
    $t = $t.Replace($parts[0], $parts[1])
  }
  # intentional constant renames (same code value + same info text, so behaviour-identical)
  $t = $t.Replace('ResponseCode.ACTIVITY_NOT_STARTED', 'ResponseCode.<ACTIVITY_NOT_STARTED>')
  $t = $t.Replace('ResponseCode.REFERRAL_ACTIVITY_NOT_STARTED', 'ResponseCode.<ACTIVITY_NOT_STARTED>')
  $t = $t.Replace('ResponseCode.LOTTERY_ACTIVITY_NOT_STARTED', 'ResponseCode.<ACTIVITY_NOT_STARTED>')
  $t = $t.Replace('ResponseCode.ACTIVITY_ENDED', 'ResponseCode.<ACTIVITY_ENDED>')
  $t = $t.Replace('ResponseCode.REFERRAL_ACTIVITY_ENDED', 'ResponseCode.<ACTIVITY_ENDED>')
  $t = $t.Replace('ResponseCode.LOTTERY_ACTIVITY_ENDED', 'ResponseCode.<ACTIVITY_ENDED>')
  $t = $t.Replace('ResponseCode.INSUFFICIENT_POINTS', 'ResponseCode.<INSUFFICIENT_POINTS>')
  $t = $t.Replace('ResponseCode.POINTS_INSUFFICIENT_POINTS', 'ResponseCode.<INSUFFICIENT_POINTS>')
  $t = $t.Replace('ResponseCode.LOTTERY_INSUFFICIENT_POINTS', 'ResponseCode.<INSUFFICIENT_POINTS>')
  $t = $t.Replace('ResponseCode.PARAM_ERROR', 'ResponseCode.<PARAM_ERROR>')
  $t = $t.Replace('ResponseCode.POINTS_PARAM_ERROR', 'ResponseCode.<PARAM_ERROR>')
  $t = $t.Replace('ResponseCode.LOTTERY_PARAM_ERROR', 'ResponseCode.<PARAM_ERROR>')
  # ticket-07 code-review fix: cross-domain topics moved to types.common.Constants, so a call
  # site may now use the FQN where it previously used the domain Constants (same literal).
  $t = $t.Replace('com.betterschool.marketing.types.common.Constants.REFERRAL_GROUPED_TOPIC', 'Constants.REFERRAL_GROUPED_TOPIC')
  $t = $t.Replace('com.betterschool.marketing.types.common.Constants.LOTTERY_DRAWN_TOPIC', 'Constants.LOTTERY_DRAWN_TOPIC')
  $t = $t.Replace('com.betterschool.marketing.types.common.Constants.POINTS_CHANGED_TOPIC', 'Constants.POINTS_CHANGED_TOPIC')
  Normalize $t
}

$identical = 0
$differ = New-Object System.Collections.Generic.List[object]
$missing = New-Object System.Collections.Generic.List[string]
foreach ($m in $moved) {
  $newFull = Join-Path $RepoRoot ($m.New.Replace('/','\'))
  if (-not (Test-Path $newFull)) { $missing.Add($m.New); continue }
  $oldRaw = (git show ("HEAD:" + $m.Old)) -join "`n"
  if (-not $oldRaw) { $missing.Add("(git) " + $m.Old); continue }
  $newRaw = [System.IO.File]::ReadAllText($newFull, (New-Object System.Text.UTF8Encoding($false)))
  $a = Canon $oldRaw
  $b = Canon $newRaw
  if ($a -eq $b) { $identical++ } else { $differ.Add([pscustomobject]@{ Old=$m.Old; New=$m.New; A=$a; B=$b }) }
}

Write-Host ("preserved files compared   : {0}" -f $moved.Count)
Write-Host ("identical after normalising: {0}" -f $identical)
Write-Host ("content deltas             : {0}" -f $differ.Count)
Write-Host ("missing on disk            : {0}" -f $missing.Count)
$missing | ForEach-Object { Write-Host ("   MISSING " + $_) }
if ($differ.Count -gt 0) {
  Write-Host ""
  Write-Host "--- first divergence per differing file (40 chars of context) ---"
  foreach ($d in $differ) {
    $i = 0
    while ($i -lt $d.A.Length -and $i -lt $d.B.Length -and $d.A[$i] -eq $d.B[$i]) { $i++ }
    $s = [Math]::Max(0, $i - 40)
    $aCtx = $d.A.Substring($s, [Math]::Min(80, $d.A.Length - $s))
    $bCtx = $d.B.Substring($s, [Math]::Min(80, $d.B.Length - $s))
    Write-Host ("  {0}" -f $d.New)
    Write-Host ("      OLD: ...{0}..." -f $aCtx)
    Write-Host ("      NEW: ...{0}..." -f $bCtx)
  }
}
