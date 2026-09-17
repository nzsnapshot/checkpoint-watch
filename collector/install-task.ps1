# Registers (or removes) the Scheduled Task that keeps the home collector running.
#
#   powershell -ExecutionPolicy Bypass -File install-task.ps1          # install and start
#   powershell -ExecutionPolicy Bypass -File install-task.ps1 -Remove  # stop and remove
#
# The task runs run-windows.ps1 as the current user, hidden, whenever that user logs on, with
# no time limit and an automatic restart if the collector ever exits. It needs no admin rights
# and no stored password: it only runs while the user is logged on, which is also the only time
# a home PC is reliably awake.
#
# Windows PowerShell 5.1 compatible. ASCII only.

param(
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'

$TaskName = 'CheckpointWatchCollector'

$CollectorDir = $PSScriptRoot
if ([string]::IsNullOrEmpty($CollectorDir)) {
    $CollectorDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
$Runner = Join-Path $CollectorDir 'run-windows.ps1'

$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

if ($Remove) {
    if ($null -eq $existing) {
        Write-Host "$TaskName is not installed."
        exit 0
    }
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "$TaskName removed."
    exit 0
}

if (-not (Test-Path -LiteralPath $Runner)) {
    throw "run-windows.ps1 not found next to this script ($CollectorDir)."
}
if ($null -eq (Get-Command 'node' -ErrorAction SilentlyContinue)) {
    throw 'node is not on PATH. Install Node.js 20 or newer first.'
}
if (-not (Test-Path -LiteralPath (Join-Path $CollectorDir 'node_modules'))) {
    throw 'node_modules is missing. Run "npm install" and "npx playwright install chromium" in the collector directory first.'
}

$powershell = Join-Path $PSHOME 'powershell.exe'
$arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $Runner + '"'

$action = New-ScheduledTaskAction -Execute $powershell -Argument $arguments -WorkingDirectory $CollectorDir

$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user

# PT0S is "no limit" for the execution time; the collector is a service loop and never finishes.
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

if ($null -ne $existing) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}

Register-ScheduledTask `
    -TaskName $TaskName `
    -Description 'Checkpoint Watch home collector: reads the public Facebook page every few minutes and publishes feed.json to the data branch.' `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Principal $principal | Out-Null

Start-ScheduledTask -TaskName $TaskName

Start-Sleep -Seconds 3
$task = Get-ScheduledTask -TaskName $TaskName
Write-Host "$TaskName installed and started. State: $($task.State)"
Write-Host "Logs: $(Join-Path $CollectorDir 'state\collector.log')"
