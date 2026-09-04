# ============================================================
# Cleanup old dist-* build folders. Keeps the newest N.
#
# Usage:  .\cleanup-dist.ps1 [keep] [go]
#           keep = how many newest folders to keep (default 5)
#           go   = actually delete; omit for dry-run
#
# Examples:
#   .\cleanup-dist.ps1              - dry run, keep newest 5
#   .\cleanup-dist.ps1 5 go         - really delete, keep newest 5
#   .\cleanup-dist.ps1 10 go        - keep newest 10
#
# If blocked by execution policy, run:
#   powershell -ExecutionPolicy Bypass -File .\cleanup-dist.ps1
# or (current session only):
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
#
# NOTE: two legacy folders are NOT matched by the dist-* pattern
# and are never touched by this script:
#   dist       (old default output dir)
#   dist_bak   (old backup)
# Remove them manually if you no longer need them.
#
# Compatible with Windows PowerShell 5.1 (no PS7-only syntax used).
# ============================================================
param(
    [int]$Keep = 5,
    [string]$Go = ""
)

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $root

if ($Keep -lt 1) {
    Write-Host "ERROR: keep count must be >= 1"
    exit 1
}

# @() wrapper so .Count works even when only one folder matches
$dirs = @(Get-ChildItem -LiteralPath . -Directory -Filter "dist-*" |
          Sort-Object LastWriteTime -Descending)

if ($dirs.Count -eq 0) {
    Write-Host "No dist-* folders found."
    exit 0
}

if ($Go -eq "go") {
    Write-Host "=== DELETING, keeping newest $Keep ==="
} else {
    Write-Host "=== DRY RUN, keeping newest $Keep ==="
}
Write-Host ""

$i = 0
$delCount = 0
foreach ($d in $dirs) {
    $i++
    if ($i -le $Keep) {
        Write-Host "[KEEP] $($d.Name)"
    } else {
        $delCount++
        if ($Go -eq "go") {
            Write-Host "[DELETE] $($d.Name)"
            try {
                Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction Stop
            } catch {
                Write-Host "  ! FAILED: $($d.Name) - $($_.Exception.Message)"
            }
        } else {
            Write-Host "[WOULD DELETE] $($d.Name)"
        }
    }
}

Write-Host ""
Write-Host "Total dist-* folders: $($dirs.Count)  Kept: $Keep  Removed: $delCount"

if ($Go -ne "go") {
    Write-Host ""
    Write-Host "Nothing was deleted. To really delete, run:"
    Write-Host "  .\cleanup-dist.ps1 $Keep go"
}
