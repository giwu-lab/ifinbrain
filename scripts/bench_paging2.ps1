# Paging scaling curve - ASCII only.
# Repeat each offset to separate real scaling from warm-cache noise.
$ErrorActionPreference = 'Stop'
$api = 'http://localhost:8080'
$body = @{ username = 'admin'; password = 'Admin@123' } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$api/api/auth/login" -Method Post `
  -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
$h = @{ Authorization = "Bearer $($login.data.accessToken)" }

function Bench($offset, $size) {
  $j = @{ offset = $offset; size = $size; withTotal = $false
          params = @{ minId = '1'; maxId = '50000' } } | ConvertTo-Json -Compress
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($j)
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $r = Invoke-RestMethod -Uri "$api/api/bi/report/1/run" -Method Post -Headers $h `
    -ContentType 'application/json; charset=utf-8' -Body $bytes
  $sw.Stop()
  [pscustomobject]@{ server = [int]$r.data.durationMs; wall = $sw.ElapsedMilliseconds; rows = $r.data.rows.Count }
}

# warm up
Bench 0 50 | Out-Null

Write-Output '=== server-side cost vs offset (3 reps, median) ==='
Write-Output 'offset    rows   serverMs(med of 3)   wallMs(med)'
foreach ($off in @(0, 1000, 5000, 10000, 20000, 30000, 40000, 49950)) {
  $samples = 1..3 | ForEach-Object { Bench $off 50 }
  $med = ($samples | Sort-Object server)[[int]($samples.Count / 2)]
  $medw = ($samples | Sort-Object wall)[[int]($samples.Count / 2)]
  Write-Output ("{0,-8}  {1,-6}  {2,-18}  {3}" -f $off, $med.rows, $med.server, $medw)
}

Write-Output ''
Write-Output '=== does the page SIZE affect deep-offset cost? (offset fixed at 40000) ==='
Write-Output 'size    serverMs(med of 3)'
foreach ($size in @(20, 50, 100, 200)) {
  $samples = 1..3 | ForEach-Object { Bench 40000 $size }
  $med = ($samples | Sort-Object server)[[int]($samples.Count / 2)]
  Write-Output ("{0,-7}  {1}" -f $size, $med.server)
}
