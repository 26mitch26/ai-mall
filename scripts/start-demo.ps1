[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipInfrastructure,
    [switch]$SkipMemberFrontend,
    [switch]$EnableSearch,
    [switch]$EnableRabbitMq,
    [switch]$EnableKafka,
    [switch]$EnableMonitoring
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runDirectory = Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

$previousDatabaseUrl = $env:MALL_DB_URL
$previousDatabaseUser = $env:MALL_DB_USERNAME
$previousDatabasePassword = $env:MALL_DB_PASSWORD
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
$previousMavenOpts = $env:MAVEN_OPTS

try {
    # Use an actual JDK executable. Oracle's javapath shim can return a launcher PID
    # that differs from the JVM PID later used by stop-demo.ps1.
    $jdkCandidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $jdkCandidates += Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    $jdkCandidates += Get-Command java.exe -All -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandType -eq 'Application' -and $_.Source -notmatch '\\javapath\\|\\WindowsApps\\' } |
        Select-Object -ExpandProperty Source
    $javaPath = $null
    foreach ($candidate in $jdkCandidates) {
        if ([string]::IsNullOrWhiteSpace($candidate) -or -not (Test-Path -LiteralPath $candidate)) { continue }
        $candidateHome = Split-Path -Parent (Split-Path -Parent $candidate)
        if (Test-Path -LiteralPath (Join-Path $candidateHome 'bin\javac.exe')) {
            $javaPath = (Resolve-Path -LiteralPath $candidate).Path
            $env:JAVA_HOME = $candidateHome
            $env:Path = (Join-Path $candidateHome 'bin') + ';' + $previousPath
            break
        }
    }
    if (-not $javaPath) {
        throw '未找到 JDK 的 bin\java.exe 与 bin\javac.exe。请将 JAVA_HOME 指向 JDK 21 后重试。'
    }
    if ([string]::IsNullOrWhiteSpace($env:MAVEN_OPTS)) {
        $env:MAVEN_OPTS = '-Xms128m -Xmx1536m'
    }

if ([string]::IsNullOrEmpty($env:MALL_DB_PASSWORD)) {
    $securePassword = Read-Host '请输入本机 MySQL 密码（只注入本次启动的子进程）' -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $env:MALL_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}
if ([string]::IsNullOrEmpty($env:MALL_DB_URL)) {
    $env:MALL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/mall?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
}
if ([string]::IsNullOrEmpty($env:MALL_DB_USERNAME)) {
    $env:MALL_DB_USERNAME = 'root'
}

if (-not $SkipInfrastructure) {
    $composeArgs = @('--project-directory', $projectRoot, '-f', (Join-Path $projectRoot 'infra\docker-compose.yml'))
    $infrastructureServices = @('redis', 'minio', 'etcd', 'milvus', 'neo4j', 'mongo')
    if ($EnableSearch) {
        $composeArgs += @('--profile', 'search')
        $infrastructureServices += @('elasticsearch', 'elasticsearch-ik-setup')
    }
    if ($EnableRabbitMq) {
        $composeArgs += @('--profile', 'messaging')
        $infrastructureServices += 'rabbitmq'
    }
    if ($EnableKafka) {
        $composeArgs += @('--profile', 'kafka')
        $infrastructureServices += @('zookeeper', 'kafka')
    }
    if ($EnableMonitoring) {
        $composeArgs += @('--profile', 'monitoring')
        $infrastructureServices += @('prometheus', 'grafana')
    }
    $composeArgs += @('up', '-d', '--wait', '--wait-timeout', '150') + $infrastructureServices
    & docker compose @composeArgs
    if ($LASTEXITCODE -ne 0) {
        throw 'Agent 基础设施启动失败。请确认 Docker Desktop、Milvus 和 Neo4j 容器状态。'
    }
}

if (-not $SkipBuild) {
    & mvn -f (Join-Path $projectRoot 'pom.xml') `
        -pl ai-gateway,mall-admin,mall-portal,agent-customer,agent-ops,agent-test `
        -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw '后端构建失败。'
    }
}

$services = @(
    @{ Name = 'mall-admin'; Port = 8081; Xmx = '512m'; Jar = 'mall-admin\target\mall-admin-1.0-SNAPSHOT.jar' },
    @{ Name = 'mall-portal'; Port = 8087; Xmx = '768m'; Jar = 'mall-portal\target\mall-portal-1.0-SNAPSHOT.jar' },
    @{ Name = 'agent-customer'; Port = 8083; Xmx = '1024m'; Jar = 'agent-customer\target\agent-customer-1.0-SNAPSHOT.jar' },
    @{ Name = 'agent-ops'; Port = 8084; Xmx = '512m'; Jar = 'agent-ops\target\agent-ops-1.0-SNAPSHOT.jar' },
    @{ Name = 'agent-test'; Port = 8085; Xmx = '512m'; Jar = 'agent-test\target\agent-test-1.0-SNAPSHOT.jar' },
    @{ Name = 'ai-gateway'; Port = 8080; Xmx = '384m'; Jar = 'ai-gateway\target\ai-gateway-1.0-SNAPSHOT.jar' }
)

foreach ($service in $services) {
    $listener = Get-NetTCPConnection -LocalPort $service.Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        Write-Host "复用已运行的 $($service.Name)（端口 $($service.Port)）"
        continue
    }

    $jarPath = Join-Path $projectRoot $service.Jar
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "找不到构建产物：$jarPath"
    }
    $stdout = Join-Path $runDirectory "$($service.Name).log"
    $stderr = Join-Path $runDirectory "$($service.Name).error.log"
    $javaArguments = @('-Xms64m', "-Xmx$($service.Xmx)", '-jar', $jarPath, '--spring.profiles.active=dev')
    $process = Start-Process -FilePath $javaPath -ArgumentList $javaArguments `
        -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Set-Content -LiteralPath (Join-Path $runDirectory "$($service.Name).pid") -Value $process.Id -Encoding ascii
}

if (-not $SkipFrontend) {
    $frontendDirectory = Join-Path $projectRoot 'frontend'
    if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules'))) {
        & npm --prefix $frontendDirectory ci
        if ($LASTEXITCODE -ne 0) {
            throw '后台前端依赖安装失败。'
        }
    }
    $frontendListener = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue
    if ($frontendListener) {
        Write-Host '复用已运行的前端（端口 5173）'
    }
    else {
        $viteScript = Join-Path $frontendDirectory 'node_modules\vite\bin\vite.js'
        $frontendProcess = Start-Process -FilePath (Get-Command node).Source -ArgumentList $viteScript,'--host','127.0.0.1' `
            -WorkingDirectory $frontendDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runDirectory 'frontend.log') `
            -RedirectStandardError (Join-Path $runDirectory 'frontend.error.log')
        Set-Content -LiteralPath (Join-Path $runDirectory 'frontend.pid') -Value $frontendProcess.Id -Encoding ascii
    }
}

if (-not $SkipMemberFrontend) {
    $memberFrontendDirectory = Join-Path $projectRoot 'frontend-app'
    $memberFrontendListener = Get-NetTCPConnection -LocalPort 5174 -State Listen -ErrorAction SilentlyContinue
    if ($memberFrontendListener) {
        Write-Host '复用已运行的商城会员端（端口 5174）'
    }
    elseif (Test-Path -LiteralPath (Join-Path $memberFrontendDirectory 'node_modules')) {
        $memberFrontendProcess = Start-Process -FilePath 'npm.cmd' -ArgumentList 'run','dev:h5','--','--host','127.0.0.1','--port','5174' `
            -WorkingDirectory $memberFrontendDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $runDirectory 'frontend-app.log') `
            -RedirectStandardError (Join-Path $runDirectory 'frontend-app.error.log')
        Set-Content -LiteralPath (Join-Path $runDirectory 'frontend-app.pid') -Value $memberFrontendProcess.Id -Encoding ascii
    }
}

$healthUrls = @(
    'http://localhost:8080/actuator/health',
    'http://localhost:8081/actuator/health',
    'http://localhost:8083/actuator/health',
    'http://localhost:8084/actuator/health',
    'http://localhost:8085/actuator/health',
    'http://localhost:8087/actuator/health'
)
$deadline = (Get-Date).AddSeconds(120)
do {
    Start-Sleep -Seconds 2
    $allHealthy = $true
    foreach ($healthUrl in $healthUrls) {
        try {
            $serviceHealth = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
            if ($serviceHealth.status -ne 'UP') { $allHealthy = $false }
        }
        catch {
            $allHealthy = $false
        }
    }
} while (-not $allHealthy -and (Get-Date) -lt $deadline)

if (-not $allHealthy) {
    throw "服务未在 120 秒内全部就绪，请检查 $runDirectory 下的日志。"
}

# Keep the demo knowledge aligned with source files without clearing shared Redis data.
# The service exposes each active source's contentHash; unchanged documents are skipped,
# changed/new files are published through the regular idempotent ingest endpoint.
$knowledgeBaseUrl = 'http://localhost:8083/api/v1/knowledge'
$indexedResponse = Invoke-RestMethod -Uri "$knowledgeBaseUrl/documents" -TimeoutSec 10
$indexedHashes = @{}
foreach ($indexedDocument in @($indexedResponse.documents)) {
    if ($indexedDocument.source) {
        $indexedHashes[[string]$indexedDocument.source] = [string]$indexedDocument.contentHash
    }
}

$demoDocuments = @(
    @{ source='docs/knowledge/refund-policy.md'; type='policy'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/shipping-policy.md'; type='policy'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/payment-faq.md'; type='faq'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/after-sale-process.md'; type='policy'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/member-benefits.md'; type='policy'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/inspection-exchange-guide.md'; type='policy'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/invoice-warranty.md'; type='policy'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/promotion-rules.md'; type='policy'; chunkStrategy='sentence' },
    @{ source='docs/knowledge/account-security.md'; type='policy'; chunkStrategy='sentence' }
)
foreach ($document in $demoDocuments) {
    # PowerShell 5.1 Get-Content -Raw can serialize with an ETS wrapper; read the
    # UTF-8 file directly. Match Java String.trim() (U+0000..U+0020) before hashing.
    $document.content = [System.IO.File]::ReadAllText((Join-Path $projectRoot $document.source), [System.Text.Encoding]::UTF8)
    $trimCharacters = [char[]](0..32)
    $normalizedContent = $document.content.Trim($trimCharacters)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $contentHashBytes = $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($normalizedContent))
    }
    finally {
        $sha256.Dispose()
    }
    $contentHash = [System.BitConverter]::ToString($contentHashBytes).Replace('-', '').ToLowerInvariant()
    if ($indexedHashes.ContainsKey($document.source) -and $indexedHashes[$document.source] -eq $contentHash) {
        Write-Host "知识文件未变化，跳过：$($document.source)"
        continue
    }

    $documentJson = $document | ConvertTo-Json -Compress
    $documentBytes = [System.Text.Encoding]::UTF8.GetBytes($documentJson)
    $ingestResult = Invoke-RestMethod -Method Post -Uri "$knowledgeBaseUrl/ingest" `
        -ContentType 'application/json; charset=utf-8' -Body $documentBytes -TimeoutSec 120
    $indexedHashes[$document.source] = [string]$ingestResult.contentHash
}

Write-Host 'AI-Mall 本地演示环境已启动。' -ForegroundColor Green
Write-Host '后台地址：http://localhost:5173  账号：admin / Admin@123'
Write-Host '商城会员端：http://localhost:5174/#/pages/public/login  账号：demo / Demo@123'
Write-Host '网关健康检查：http://localhost:8080/actuator/health'
Write-Host 'Agent 服务：客服 8083 / 运维 8084 / 自动化测试 8085'
}
finally {
    if ($null -eq $previousDatabaseUrl) { Remove-Item Env:MALL_DB_URL -ErrorAction SilentlyContinue }
    else { $env:MALL_DB_URL = $previousDatabaseUrl }
    if ($null -eq $previousDatabaseUser) { Remove-Item Env:MALL_DB_USERNAME -ErrorAction SilentlyContinue }
    else { $env:MALL_DB_USERNAME = $previousDatabaseUser }
    if ($null -eq $previousDatabasePassword) { Remove-Item Env:MALL_DB_PASSWORD -ErrorAction SilentlyContinue }
    else { $env:MALL_DB_PASSWORD = $previousDatabasePassword }
    if ($null -eq $previousJavaHome) { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue }
    else { $env:JAVA_HOME = $previousJavaHome }
    $env:Path = $previousPath
    if ($null -eq $previousMavenOpts) { Remove-Item Env:MAVEN_OPTS -ErrorAction SilentlyContinue }
    else { $env:MAVEN_OPTS = $previousMavenOpts }
}
