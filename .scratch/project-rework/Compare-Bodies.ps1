$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$repo = 'C:\Users\a1368\Desktop\better_school'
$planJson = [System.IO.File]::ReadAllText((Join-Path $repo '.scratch\project-rework\06-fileplan.json'), $enc)
$plan = $planJson | ConvertFrom-Json

function Strip($text) {
    $lines = $text -split "`r?`n"
    $keep = @()
    foreach ($l in $lines) {
        $t = $l.TrimEnd()
        if ($t -match '^package\s') { continue }
        if ($t -match '^import\s') { continue }
        $keep += $t
    }
    return ($keep -join "`n")
}

# 旧 FQCN -> 新 FQCN（用 fileplan 推：旧根 com.betterschool.asset + 类名）
$pairs = @()
foreach ($m in $plan.moves) {
    $oldPkg = [regex]::Match($m.src, 'src/main/java/(.+)/[^/]+\.java$')
    $oldTestPkg = [regex]::Match($m.src, 'src/test/java/(.+)/[^/]+\.java$')
    $p = if ($oldPkg.Success) { $oldPkg.Groups[1].Value } elseif ($oldTestPkg.Success) { $oldTestPkg.Groups[1].Value } else { $null }
    if (-not $p) { continue }
    $p = $p -replace '/', '.'
    $cls = [System.IO.Path]::GetFileNameWithoutExtension($m.src)
    $pairs += , @("$p.$cls", "$($m.pkg).$cls", $m)
}
$pairs = $pairs | Sort-Object { - ($_[0].Length) }

$same = 0
$diff = @()
foreach ($pair in $pairs) {
    $oldFq = $pair[0]; $newFq = $pair[1]; $m = $pair[2]
    $gitPath = "HEAD:backend/marketing-asset/" + ($m.src -replace '\\', '/')
    $oldText = (& git show $gitPath 2>&1 | Out-String)
    if (-not $oldText -or $oldText -match 'fatal:') { $diff += "GIT-MISS $gitPath"; continue }
    # 把旧内容里的所有 FQCN 按映射改成新 FQCN
    foreach ($q in $pairs) { $oldText = $oldText.Replace($q[0], $q[1]) }
    $newPath = Join-Path (Join-Path $repo 'backend\marketing') $m.dst
    $newText = [System.IO.File]::ReadAllText($newPath, $enc)
    $a = Strip $oldText
    $b = Strip $newText
    if ($a -ceq $b) { $same++ } else { $diff += $m.dst }
}
Write-Host "identical after FQCN+import normalisation: $same / $($pairs.Count)"
if ($diff.Count -gt 0) {
    Write-Host "--- differ (expected: 文件顶部注释含旧模块名/人工修正项) ---"
    $diff | ForEach-Object { Write-Host "  $_" }
}
