param(
    [string]$BaseUrl = "http://localhost:8080/agent/customer",
    [string]$Keyword = "coffee",
    [switch]$PrepareWorkflow,
    [string]$OrderSn = "",
    [string]$Reason = "",
    [string]$Description = ""
)

$ErrorActionPreference = "Stop"
$mcpEndpoint = "$($BaseUrl.TrimEnd('/'))/mcp"
$statusEndpoint = "$($BaseUrl.TrimEnd('/'))/api/v1/knowledge/status"
$headers = @{
    Accept = "application/json, text/event-stream"
    "MCP-Protocol-Version" = "2025-06-18"
}

function Invoke-Mcp($Message, [switch]$Initialize) {
    $requestHeaders = @{} + $headers
    if ($Initialize) { $requestHeaders.Remove("MCP-Protocol-Version") | Out-Null }
    $json = $Message | ConvertTo-Json -Depth 20 -Compress
    Invoke-RestMethod -Method Post -Uri $mcpEndpoint -Headers $requestHeaders `
        -ContentType "application/json" -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
}

function Assert-JsonRpcResponse($Response, [int]$ExpectedId) {
    if ($Response.jsonrpc -ne "2.0" -or $Response.id -ne $ExpectedId -or $Response.error) {
        throw "MCP returned an unexpected JSON-RPC response for id $ExpectedId."
    }
}

# A GET status probe confirms the public knowledge service without sending member or admin credentials.
$status = Invoke-RestMethod -Method Get -Uri $statusEndpoint
if ($null -eq $status.data.online -and $null -eq $status.online) { throw "Knowledge status response did not include online state." }
Write-Output "knowledge status: reachable; knowledge base fields present=$([bool]($status.data.knowledgeBase -or $status.knowledgeBase))"

$initialize = Invoke-Mcp @{ jsonrpc = "2.0"; id = 1; method = "initialize"; params = @{
    protocolVersion = "2025-06-18"
    capabilities = @{}
    clientInfo = @{ name = "ai-mall-upgrade-smoke"; version = "1" }
} } -Initialize
Assert-JsonRpcResponse $initialize 1
if ($initialize.result.protocolVersion -ne "2025-06-18") { throw "Unexpected MCP protocol version." }
Write-Output "MCP initialize: $($initialize.result.protocolVersion)"

$initializedBody = @{ jsonrpc = "2.0"; method = "notifications/initialized" } | ConvertTo-Json -Compress
$initializedResponse = Invoke-WebRequest -Method Post -Uri $mcpEndpoint -Headers $headers `
    -ContentType "application/json" -Body ([System.Text.Encoding]::UTF8.GetBytes($initializedBody))
if ([int]$initializedResponse.StatusCode -ne 202) { throw "notifications/initialized expected HTTP 202." }

$ping = Invoke-Mcp @{ jsonrpc = "2.0"; id = 2; method = "ping" }
Assert-JsonRpcResponse $ping 2
Write-Output "MCP ping: ok"

$listed = Invoke-Mcp @{ jsonrpc = "2.0"; id = 3; method = "tools/list" }
Assert-JsonRpcResponse $listed 3
$toolNames = @($listed.result.tools | ForEach-Object { $_.name })
if ($toolNames -notcontains "search_products") { throw "Public search_products tool was not listed." }
if ($toolNames -contains "place_order" -or $toolNames -contains "cancel_order" -or $toolNames -contains "create_after_sale") {
    throw "A direct write tool is exposed by MCP."
}
Write-Output "MCP anonymous tools/list: $($toolNames -join ', ')"

$search = Invoke-Mcp @{ jsonrpc = "2.0"; id = 4; method = "tools/call"; params = @{
    name = "search_products"
    arguments = @{ keyword = $Keyword; page = 1; pageSize = 3 }
} }
Assert-JsonRpcResponse $search 4
if ($search.result.isError) { throw "Public product search returned an MCP tool error." }
Write-Output "MCP public product search: ok (read-only)"

if ($PrepareWorkflow) {
    if ([string]::IsNullOrWhiteSpace($OrderSn)) { $OrderSn = Read-Host "Member order number" }
    if ([string]::IsNullOrWhiteSpace($Reason)) { $Reason = Read-Host "After-sale reason" }
    if ([string]::IsNullOrWhiteSpace($OrderSn) -or [string]::IsNullOrWhiteSpace($Reason)) { throw "An order number and reason are required." }
    $secureToken = Read-Host "Verified raw member JWT without Bearer prefix (input hidden)" -AsSecureString
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    try { $memberToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer) }
    if ([string]::IsNullOrWhiteSpace($memberToken)) { throw "A member token is required for workflow preparation." }
    $headers.Authorization = "Bearer $memberToken"
    if (@($listed.result.tools | Where-Object name -eq "prepare_after_sale").Count -eq 0) {
        $authedList = Invoke-Mcp @{ jsonrpc = "2.0"; id = 5; method = "tools/list" }
        Assert-JsonRpcResponse $authedList 5
        $toolNames = @($authedList.result.tools | ForEach-Object { $_.name })
    }
    if ($toolNames -notcontains "prepare_after_sale") { throw "The supplied member token did not expose prepare_after_sale." }
    $draft = Invoke-Mcp @{ jsonrpc = "2.0"; id = 6; method = "tools/call"; params = @{
        name = "prepare_after_sale"
        arguments = @{ order_sn = $OrderSn; reason = $Reason; description = $Description }
    } }
    Assert-JsonRpcResponse $draft 6
    if ($draft.result.isError) { throw "After-sale draft preparation failed." }
    $draftState = $draft.result.content[0].text | ConvertFrom-Json
    Write-Output "After-sale draft prepared: status=$($draftState.status), taskId=$($draftState.taskId), version=$($draftState.version). No confirmation was sent."
    $headers.Remove("Authorization") | Out-Null
    $memberToken = $null
}

Write-Output "Upgrade smoke checks passed. This script does not submit orders, returns, or refunds."
