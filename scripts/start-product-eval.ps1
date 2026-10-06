param([switch]$Ablation, [Alias('FullSemantic')][switch]$Retrieval)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$runPath = Join-Path $root '.run'
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
$pidPath = Join-Path $runPath 'eval-customer.pid'
if (Test-Path -LiteralPath $pidPath) {
    $oldId = [int](Get-Content -LiteralPath $pidPath)
    $old = Get-CimInstance Win32_Process -Filter "ProcessId=$oldId" -ErrorAction SilentlyContinue
    if ($old -and $old.Name -eq 'java.exe' -and $old.CommandLine -match 'agent-customer[/\\]target[/\\]agent-customer-1.0-SNAPSHOT.jar') {
        Stop-Process -Id $oldId
    } elseif ($old) { throw 'Recorded PID belongs to another process; refusing to stop it.' }
}
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw 'JAVA_HOME must point to JDK 21.' }
$argsList = @('-Xms96m', '-Xmx256m', '-XX:MaxMetaspaceSize=192m', '-XX:MaxDirectMemorySize=64m',
    '-XX:ActiveProcessorCount=4', '-Dio.netty.allocator.numDirectArenas=2', '-jar',
    'agent-customer/target/agent-customer-1.0-SNAPSHOT.jar', '--server.port=8083',
    '--ai.memory.archive-enabled=false', '--ai.customer.semantic-cache.enabled=false',
    '--ai.rag.reranker.semantic-feature-enabled=false', '--milvus.client.host=127.0.0.1',
    '--ai.model.llm.model=ai-mall-eval-qwen3:0.6b', '--ai.model.llm.context-tokens=2048',
    '--ai.model.llm.keep-alive=0', '--ai.model.llm.gpu-layers=0',
    '--ai.model.llm.release-embedding-before-generation=true')
$argsList += @('--ai.model.llm.base-url=http://localhost:11434/api/chat', '--spring.ai.openai.base-url=http://localhost:11434')
if (-not $Retrieval) {
    $argsList += @('--spring.profiles.active=eval-low-memory')
}
if ($Ablation) {
    $argsList += @('--ai.customer.explicit-handoff.enabled=false', '--ai.customer.policy-direct.enabled=false')
}
$process = Start-Process -FilePath $java -ArgumentList $argsList -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runPath 'eval-customer.out.log') `
    -RedirectStandardError (Join-Path $runPath 'eval-customer.err.log')
$process.Id | Set-Content -LiteralPath $pidPath
Write-Output "Evaluation server PID $($process.Id). Await readiness before requests."
$readyUntil = (Get-Date).AddSeconds(45)
do {
    try {
        $null = Invoke-RestMethod -Uri 'http://localhost:8083/api/v1/knowledge/status' -TimeoutSec 2
        Write-Output 'Evaluation server ready.'
        exit 0
    } catch {
        if ($process.HasExited) { throw 'Evaluation server exited during startup. Inspect .run logs.' }
        Start-Sleep -Seconds 1
    }
} while ((Get-Date) -lt $readyUntil)
throw 'Evaluation server did not become ready within 45 seconds.'
