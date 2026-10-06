# Interactive local bootstrap. The database password is masked and kept only in process memory.
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $workspaceRoot
$runDirectory = Join-Path $workspaceRoot '.run'
$mysqlExe = 'C:\Program Files\MySQL\MySQL Server 9.7\bin\mysql.exe'
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $mysqlExe) -or -not (Test-Path -LiteralPath $javaExe)) { throw 'MySQL client or JDK not found.' }
if (Get-NetTCPConnection -LocalPort 8087 -State Listen -ErrorAction SilentlyContinue) { Write-Host 'Member service already listening on 8087.'; exit 0 }
$dbUsername = Read-Host 'Native MySQL username (Enter for root)'
if ([string]::IsNullOrWhiteSpace($dbUsername)) { $dbUsername = 'root' }
if ($dbUsername -notmatch '^[A-Za-z0-9_]{1,32}$') { throw 'Invalid database username.' }
$securePassword = Read-Host 'Native MySQL password (masked; never sent to chat)' -AsSecureString
$secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$previousMysqlPwd = $env:MYSQL_PWD
$previousDatabasePwd = $env:MALL_DB_PASSWORD
$previousDatabaseUser = $env:MALL_DB_USERNAME
$previousJwtSecret = $env:JWT_SECRET
try {
    $env:MYSQL_PWD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    $null = & $mysqlExe --protocol=TCP --host=127.0.0.1 --port=3306 "--user=$dbUsername" --connect-timeout=5 --batch --skip-column-names '--execute=SELECT 1;' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Database authentication failed; no password or data changed.' }
    $env:MALL_DB_PASSWORD = $env:MYSQL_PWD
    $env:MALL_DB_USERNAME = $dbUsername
    if ([string]::IsNullOrEmpty($env:JWT_SECRET)) { $env:JWT_SECRET = 'default-dev-secret-change-in-prod' }
    $javaArgs = @('-Xms48m','-Xmx128m','-XX:MaxMetaspaceSize=160m','-XX:MaxDirectMemorySize=32m','-XX:ActiveProcessorCount=2',
        '-jar','mall-portal/target/mall-portal-1.0-SNAPSHOT.jar','--spring.profiles.active=dev',
        '--spring.config.additional-location=optional:file:./.run/member-local-portal.properties','--server.port=8087',
        '--spring.datasource.druid.initial-size=1','--spring.datasource.druid.min-idle=1','--spring.datasource.druid.max-active=5',
        '--management.health.mongo.enabled=false')
    $portal = Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $workspaceRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runDirectory 'ui-portal.log') -RedirectStandardError (Join-Path $runDirectory 'ui-portal.err.log')
    $portal.Id | Set-Content -LiteralPath (Join-Path $runDirectory 'ui-portal.pid')
    Write-Host 'Database authentication succeeded. Member service is starting; no password file was saved.'
} finally {
    $env:MYSQL_PWD = $previousMysqlPwd
    $env:MALL_DB_PASSWORD = $previousDatabasePwd
    $env:MALL_DB_USERNAME = $previousDatabaseUser
    $env:JWT_SECRET = $previousJwtSecret
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
    $securePassword.Dispose()
}
