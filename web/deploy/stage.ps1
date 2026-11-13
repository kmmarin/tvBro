# Copyright 2026 The tvBro authors. SPDX-License-Identifier: Apache-2.0
# Stages web/ for an update deploy: LF line endings, no BOM, cache-busting ?v=<build> on
# every CSS/JS link and ES-module import, then tars it.  Usage:
#   powershell -File web\deploy\stage.ps1 -Out <dir>
# Then: scp <dir>\tvbro-web.tar + web\deploy\update_lxc.sh to the host, pct push, run update_lxc.sh.
param([Parameter(Mandatory = $true)][string]$Out)
$ErrorActionPreference = 'Stop'
$web = Split-Path -Parent $PSScriptRoot
$ver = (git -C $web rev-parse --short HEAD).Trim() + '-' + (Get-Date -Format 'MMddHHmm')
$stage = Join-Path $Out 'stage'
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$files = Get-ChildItem -Recurse -File (Join-Path $web 'frontend'), (Join-Path $web 'server'), (Join-Path $web 'deploy') |
  Where-Object { $_.FullName -notmatch '\\(vendor|data|__pycache__)\\' -and $_.Extension -ne '.ps1' }
foreach ($f in $files) {
  $rel = $f.FullName.Substring($web.Length + 1)
  $dst = Join-Path $stage $rel
  New-Item -ItemType Directory -Force (Split-Path -Parent $dst) | Out-Null
  $t = [IO.File]::ReadAllText($f.FullName).TrimStart([char]0xFEFF).Replace("`r`n", "`n")
  if ($f.Extension -eq '.js') { $t = [regex]::Replace($t, "(from\s+'\./[\w-]+\.js)'", "`$1?v=$ver'") }
  if ($f.Name -eq 'index.html') { $t = $t.Replace('__V__', $ver) }
  [IO.File]::WriteAllText($dst, $t, $utf8)
}
$tar = Join-Path $Out 'tvbro-web.tar'
if (Test-Path $tar) { Remove-Item -Force $tar }
tar -cf $tar -C $stage frontend server deploy
Write-Output "staged $ver -> $tar"
