# Pagination depth benchmark - ASCII output only.
# Purpose: measure whether report paging stays fast as offset grows.
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'

$body = @{ username = 'admin'; password = 'Admin@123' } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$api/api/auth/login" -Method Post `
  -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
$h = @{ Authorization = "Bearer $($login.data.accessToken)" }

function Run($offset, $size, $withTotal) {
  $p = @{ minId = '1'; maxId = '50000' }
  $j = @{ offset = $offset; size = $size; params = $p; withTotal = $withTotal } | ConvertTo-Json -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($j)
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $r = Invoke-RestMethod -Uri "$api/api/bi/report/1/run" -Method Post -Headers $h `
    -ContentType 'application/json; charset=utf-8' -Body $bytes
  $sw.Stop()
  [pscustomobject]@{
    offset = $offset
    size = $size
    rows = $r.data.rows.Count
    total = $r.data.total
    wallMs = $sw.ElapsedMilliseconds
    serverMs = $r.data.durationMs
  }
}

Write-Output '=== deep-offset cost (the key question) ==='
Write-Output 'offset      size   rows    total  wall(ms)  server(ms)'
foreach ($cfg in @(
  @(0, 50, $true),
  @(50, 50, $false),
  @(500, 50, $false),
  @(2500, 50, $false),
  @(10000, 50, $false),
  @(25000, 50, $false),
  @(49950, 50, $false)
)) {
  $x = Run $cfg[0] $cfg[1] $cfg[2]
  Write-Output ("{0,-10}  {1,-5}  {2,-6}  {3,-6}  {4,-8}  {5}" -f `
    $x.offset, $x.size, $x.rows, $x.total, $x.wallMs, $x.serverMs)
}

Write-Output ''
Write-Output '=== COUNT(*) cost on first page (runs every fresh query) ==='
for ($i = 1; $i -le 3; $i++) {
  $x = Run 0 50 $true
  Write-Output ("  attempt {0}: wall={1}ms server={2}ms total={3}" -f $i, $x.wallMs, $x.serverMs, $x.total)
}
