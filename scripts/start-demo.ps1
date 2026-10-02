[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$SkipInfrastructure,
    [switch]$SkipMemberFrontend
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runDirectory = Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

$previousDatabaseUrl = $env:MALL_DB_URL
$previousDatabaseUser = $env:MALL_DB_USERNAME
$previousDatabasePassword = $env:MALL_DB_PASSWORD

try {
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
    & docker compose -f (Join-Path $projectRoot 'infra\docker-compose.yml') `
        up -d --wait --wait-timeout 150 redis minio etcd milvus neo4j mongo
    if ($LASTEXITCODE -ne 0) {
        throw 'Agent 基础设施启动失败。请确认 Docker Desktop、Milvus 和 Neo4j 容器状态。'
    }
}

if (-not $SkipBuild) {
    & mvn -f (Join-Path $projectRoot 'pom.xml') `
        -pl ai-gateway,mall-admin,mall-portal,agent-customer,agent-ops,agent-test `
        -am clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw '后端构建失败。'
    }
}

$services = @(
    @{ Name = 'mall-admin'; Port = 8081; Jar = 'mall-admin\target\mall-admin-1.0-SNAPSHOT.jar' },
    @{ Name = 'mall-portal'; Port = 8087; Jar = 'mall-portal\target\mall-portal-1.0-SNAPSHOT.jar' },
    @{ Name = 'agent-customer'; Port = 8083; Jar = 'agent-customer\target\agent-customer-1.0-SNAPSHOT.jar' },
    @{ Name = 'agent-ops'; Port = 8084; Jar = 'agent-ops\target\agent-ops-1.0-SNAPSHOT.jar' },
    @{ Name = 'agent-test'; Port = 8085; Jar = 'agent-test\target\agent-test-1.0-SNAPSHOT.jar' },
    @{ Name = 'ai-gateway'; Port = 8080; Jar = 'ai-gateway\target\ai-gateway-1.0-SNAPSHOT.jar' }
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
    $process = Start-Process -FilePath 'java' -ArgumentList '-jar', $jarPath, '--spring.profiles.active=dev' `
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

# 首次启动时写入课设演示知识，形成可直接提问的 RAG 闭环。
$knowledgeSeedFlag = Join-Path $runDirectory 'knowledge-seeded.flag'
if (-not (Test-Path -LiteralPath $knowledgeSeedFlag)) {
    $bm25Keys = @(& docker exec ai-mall-redis redis-cli --scan --pattern 'bm25:*')
    if ($bm25Keys.Count -gt 0) {
        & docker exec ai-mall-redis redis-cli DEL @bm25Keys | Out-Null
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
        # 注意：PowerShell 5.1 下 Get-Content -Raw 返回的字符串带 ETS 装饰属性，
        # ConvertTo-Json 会把它序列化成 {"value": ...} 对象导致入库 400；
        # 统一用 File.ReadAllText 取纯字符串，并以 UTF-8 字节发送，避免编码/包装问题。
        $document.content = [System.IO.File]::ReadAllText((Join-Path $projectRoot $document.source), [System.Text.Encoding]::UTF8)
        $documentJson = $document | ConvertTo-Json -Compress
        $documentBytes = [System.Text.Encoding]::UTF8.GetBytes($documentJson)
        Invoke-RestMethod -Method Post -Uri 'http://localhost:8083/api/v1/knowledge/ingest' `
            -ContentType 'application/json; charset=utf-8' -Body $documentBytes -TimeoutSec 120 | Out-Null
    }
    Set-Content -LiteralPath $knowledgeSeedFlag -Value (Get-Date -Format o) -Encoding utf8
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
}
