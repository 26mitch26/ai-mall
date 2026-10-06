param(
    [string]$Endpoint = "http://localhost:8090/mcp",
    [string]$BearerToken = ""
)

$ErrorActionPreference = "Stop"
$headers = @{
    Accept = "application/json, text/event-stream"
    "MCP-Protocol-Version" = "2025-06-18"
}
if ($BearerToken) { $headers.Authorization = "Bearer $BearerToken" }

function Invoke-Mcp($Message, [switch]$Initialize) {
    $requestHeaders = @{} + $headers
    if ($Initialize) { $requestHeaders.Remove("MCP-Protocol-Version") }
    $json = $Message | ConvertTo-Json -Depth 20 -Compress
    Invoke-RestMethod -Method Post -Uri $Endpoint -Headers $requestHeaders `
        -ContentType "application/json" -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
}

$init = Invoke-Mcp @{ jsonrpc = "2.0"; id = 1; method = "initialize"; params = @{
    protocolVersion = "2025-06-18"; capabilities = @{}; clientInfo = @{ name = "ai-mall-smoke"; version = "1" }
} } -Initialize
if ($init.result.protocolVersion -ne "2025-06-18") { throw "Initialize negotiated an unexpected protocol version." }
Write-Output "initialize: $($init.result.protocolVersion)"

$initialized = @{ jsonrpc = "2.0"; method = "notifications/initialized" } | ConvertTo-Json -Compress
$notice = Invoke-WebRequest -Method Post -Uri $Endpoint -Headers $headers -ContentType "application/json" `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($initialized))
if ([int]$notice.StatusCode -ne 202) { throw "notifications/initialized expected HTTP 202, got $($notice.StatusCode)." }
Write-Output "notifications/initialized: HTTP $($notice.StatusCode)"

$ping = Invoke-Mcp @{ jsonrpc = "2.0"; id = 2; method = "ping" }
if ($ping.id -ne 2 -or $null -eq $ping.result) { throw "ping failed." }
Write-Output "ping: ok"

$tools = Invoke-Mcp @{ jsonrpc = "2.0"; id = 3; method = "tools/list" }
$names = @($tools.result.tools | ForEach-Object { $_.name })
if ($names -notcontains "search_products") { throw "search_products is missing from tools/list." }
if ($names -contains "place_order" -or $names -contains "cancel_order" -or $names -contains "create_after_sale") {
    throw "A direct write tool is exposed."
}
Write-Output "tools/list: $($names -join ', ')"

$search = Invoke-Mcp @{ jsonrpc = "2.0"; id = 4; method = "tools/call"; params = @{
    name = "search_products"; arguments = @{ keyword = "coffee"; page = 1; pageSize = 3 }
} }
if ($search.id -ne 4 -or $search.result.isError) { throw "search_products returned an MCP error." }
Write-Output "tools/call search_products: ok"

Write-Output "Smoke checks passed for the stateless JSON-only MCP 2025-06-18 profile."
