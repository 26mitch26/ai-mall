[CmdletBinding()]
param(
    [string]$MySqlBin = 'C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe',
    [string]$DatabaseHost = '127.0.0.1',
    [int]$DatabasePort = 3306,
    [string]$DatabaseUser = 'root',
    [switch]$ResetDemoData
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$initSql = Join-Path $projectRoot 'infra\mysql\init.sql'

if (-not (Test-Path -LiteralPath $MySqlBin)) {
    throw "找不到 MySQL 客户端：$MySqlBin"
}
if (-not (Test-Path -LiteralPath $initSql)) {
    throw "找不到初始化脚本：$initSql"
}

$password = $env:MALL_DB_PASSWORD
if ([string]::IsNullOrEmpty($password)) {
    $securePassword = Read-Host '请输入本机 MySQL 密码' -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}

$previousPassword = $env:MYSQL_PWD
$env:MYSQL_PWD = $password
try {
    $databaseExists = & $MySqlBin `
        '--protocol=TCP' `
        "--host=$DatabaseHost" `
        "--port=$DatabasePort" `
        "--user=$DatabaseUser" `
        '--batch' '--skip-column-names' `
        "--execute=SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='mall';"
    if ($LASTEXITCODE -ne 0) {
        throw '无法连接本机 MySQL，请检查账号、密码和 3306 端口。'
    }
    if ([int]$databaseExists -gt 0 -and -not $ResetDemoData) {
        throw '检测到已有 mall 数据库。为避免覆盖现有演示数据，请先备份，再显式使用 -ResetDemoData。'
    }

    Get-Content -LiteralPath $initSql -Raw -Encoding utf8 | & $MySqlBin `
        '--protocol=TCP' `
        "--host=$DatabaseHost" `
        "--port=$DatabasePort" `
        "--user=$DatabaseUser" `
        '--default-character-set=utf8mb4' `
        '--show-warnings'
    if ($LASTEXITCODE -ne 0) {
        throw "数据库初始化失败，mysql 退出码：$LASTEXITCODE"
    }

    & $MySqlBin `
        '--protocol=TCP' `
        "--host=$DatabaseHost" `
        "--port=$DatabasePort" `
        "--user=$DatabaseUser" `
        '--default-character-set=utf8mb4' `
        '--table' `
        '--execute=SELECT VERSION() AS mysql_version; USE mall; SELECT COUNT(*) AS products FROM pms_product; SELECT username AS admin_user FROM ums_admin; SELECT username AS member_user FROM ums_member;'
    if ($LASTEXITCODE -ne 0) {
        throw '数据库初始化后的校验失败。'
    }

    # 数据库种子账号被刷新后，清理对应 Redis 缓存，避免旧密码继续生效。
    $redisContainer = & docker ps --filter 'name=^/ai-mall-redis$' --format '{{.Names}}' 2>$null
    if ($redisContainer -eq 'ai-mall-redis') {
        & docker exec ai-mall-redis redis-cli DEL `
            'mall:ums:admin:admin' 'mall:ums:resourceList:1' 'mall:ums:member:demo' | Out-Null
    }
}
finally {
    if ($null -eq $previousPassword) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
    else {
        $env:MYSQL_PWD = $previousPassword
    }
    $password = $null
}

Write-Host 'AI-Mall 本地数据库已初始化完成。' -ForegroundColor Green
Write-Host '后台演示账号：admin / Admin@123'
Write-Host '商城演示账号：demo / Demo@123'
