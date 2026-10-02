[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runDirectory = Join-Path $projectRoot '.run'

if (-not (Test-Path -LiteralPath $runDirectory)) {
    Write-Host '没有找到 AI-Mall 本地运行记录。'
    exit 0
}

Get-ChildItem -LiteralPath $runDirectory -Filter '*.pid' | ForEach-Object {
    $processId = [int](Get-Content -LiteralPath $_.FullName -Raw)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -like "*$projectRoot*") {
        Stop-Process -Id $processId -Force
        Write-Host "已停止 $($_.BaseName)（PID $processId）"
    }
    Remove-Item -LiteralPath $_.FullName -Force
}

# Start-Process 在部分 Windows 环境下会返回启动器 PID；再按本项目固定端口清理真实监听进程。
$demoPorts = 5173, 5174, 8080, 8081, 8083, 8084, 8085, 8087
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $demoPorts -contains $_.LocalPort } |
    Select-Object -ExpandProperty OwningProcess -Unique |
    ForEach-Object {
        $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $_" -ErrorAction SilentlyContinue
        if ($listenerProcess -and $listenerProcess.CommandLine -like "*$projectRoot*") {
            Stop-Process -Id $_ -Force
            Write-Host "已停止监听进程（PID $_）"
        }
    }
