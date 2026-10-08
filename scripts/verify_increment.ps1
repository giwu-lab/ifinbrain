$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'

# --- login (default local dev credential, documented in AGENTS.md) ---
$loginBody = @{ username = 'admin'; password = 'Admin@123' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body $loginBody
$token = $login.data.accessToken
if (-not $token) { Write-Output 'LOGIN_FAILED'; exit 1 }
$h = @{ Authorization = "Bearer $token" }
Write-Output 'LOGIN_OK'

# --- report meta ---
$rpt = Invoke-RestMethod -Method Get -Uri "$base/api/bi/report/1" -Headers $h
$d = $rpt.data
Write-Output ("REPORT name={0} pageSize={1} maxRows={2} queryId={3}" -f $d.name, $d.pageSize, $d.maxRows, $d.queryId)
$params = $d.defaultParams
if ($null -eq $params) { $params = @{} }
Write-Output ("PARAMS " + (($params | ConvertTo-Json -Compress)))

# --- simulate 3 consecutive incremental pages, exactly like frontend loadMore ---
$prevLast = $null
foreach ($off in 0, 50, 100, 150) {
    $body = @{ params = $params; offset = $off; size = 50 } | ConvertTo-Json -Depth 5
    $resp = Invoke-RestMethod -Method Post -Uri "$base/api/bi/report/1/run" -Headers $h -ContentType 'application/json' -Body $body
    $rows = @($resp.data.rows)
    $n = $rows.Count
    $first = if ($n -gt 0) { $rows[0][0] } else { '-' }
    $last = if ($n -gt 0) { $rows[$n - 1][0] } else { '-' }
    $cont = if ($null -ne $prevLast -and $n -gt 0) { if ([string]$rows[0][0] -eq [string]([int]$prevLast + 1)) { 'CONTIGUOUS' } else { "GAP(prev=$prevLast)" } } else { 'N/A' }
    Write-Output ("PAGE offset={0} count={1} first={2} last={3} hasMore={4} capped={5} {6} dur={7}ms" -f $off, $n, $first, $last, $resp.data.hasMore, $resp.data.capped, $cont, $resp.data.durationMs)
    if ($n -gt 0) { $prevLast = $rows[$n - 1][0] }
}

# --- max_rows guard: maxRows=50000, so offset 50000 must be empty+capped ---
$body = @{ params = $params; offset = 50000; size = 50 } | ConvertTo-Json -Depth 5
$resp = Invoke-RestMethod -Method Post -Uri "$base/api/bi/report/1/run" -Headers $h -ContentType 'application/json' -Body $body
$rows = @($resp.data.rows)
Write-Output ("GUARD offset=50000 count={0} hasMore={1} capped={2} dur={3}ms" -f $rows.Count, $resp.data.hasMore, $resp.data.capped, $resp.data.durationMs)

# --- past guard: offset beyond max must return empty, no data cost ---
$body = @{ params = $params; offset = 60000; size = 50 } | ConvertTo-Json -Depth 5
$resp = Invoke-RestMethod -Method Post -Uri "$base/api/bi/report/1/run" -Headers $h -ContentType 'application/json' -Body $body
$rows = @($resp.data.rows)
Write-Output ("PASTGUARD offset=60000 count={0} hasMore={1} capped={2} dur={3}ms" -f $rows.Count, $resp.data.hasMore, $resp.data.capped, $resp.data.durationMs)

Write-Output 'DONE'
