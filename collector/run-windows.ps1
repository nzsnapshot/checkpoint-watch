# Checkpoint Watch home collector - the thing the Scheduled Task actually runs.
#
# Kept deliberately small: work out where we are, find node, run the collector, and send
# whatever it prints to state\service.log. The collector keeps its own rotated log in
# state\collector.log; this one is only here to catch anything that goes wrong before the
# collector's own logging gets going.
#
# Windows PowerShell 5.1 compatible. ASCII only.

$ErrorActionPreference = 'Continue'

$CollectorDir = $PSScriptRoot
if ([string]::IsNullOrEmpty($CollectorDir)) {
    $CollectorDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}

Set-Location -LiteralPath $CollectorDir

$StateDir = Join-Path $CollectorDir 'state'
if (-not (Test-Path -LiteralPath $StateDir)) {
    New-Item -ItemType Directory -Path $StateDir -Force | Out-Null
}

$LogFile = Join-Path $StateDir 'service.log'

# This file only ever gets a copy of what the collector prints, so trimming it on each start
# is enough to stop it growing for ever.
if (Test-Path -LiteralPath $LogFile) {
    $existing = Get-Item -LiteralPath $LogFile
    if ($existing.Length -gt 1048576) {
        Remove-Item -LiteralPath $LogFile -Force -ErrorAction SilentlyContinue
    }
}

$stamp = (Get-Date).ToString('s')

$NodeCommand = Get-Command 'node' -ErrorAction SilentlyContinue
if ($null -eq $NodeCommand) {
    Add-Content -LiteralPath $LogFile -Value ($stamp + ' node was not found on PATH; cannot start the collector.')
    exit 1
}

$Entry = Join-Path $CollectorDir 'src\index.js'
if (-not (Test-Path -LiteralPath $Entry)) {
    Add-Content -LiteralPath $LogFile -Value ($stamp + ' src\index.js is missing from ' + $CollectorDir)
    exit 1
}

Add-Content -LiteralPath $LogFile -Value ($stamp + ' starting the collector')

& $NodeCommand.Source $Entry *>> $LogFile

exit $LASTEXITCODE
