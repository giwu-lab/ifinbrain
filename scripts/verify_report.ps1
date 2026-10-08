$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$base = 'http://localhost:8080/api'

# 1. login (token field is data.accessToken, not data.token)
$body = '{"username":"admin","password":"Admin@123"}'
$login = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body $body
$token = $login.data.accessToken
if (-not $token) { throw "login failed" }
Write-Host "token ok (len=$($token.Length))"

$h = @{ Authorization = "Bearer $token" }

# 2. report detail
$r = Invoke-RestMethod -Uri "$base/bi/report/1" -Headers $h
Write-Host "report: code=$($r.data.code) queryId=$($r.data.queryId) pageSize=$($r.data.pageSize) maxRows=$($r.data.maxRows)"
Write-Host "header: $($r.data.header | ConvertTo-Json -Compress)"
Write-Host "defaultParams: $($r.data.defaultParams | ConvertTo-Json -Compress)"

# 3. param whitelist: undeclared param must be ignored, not error
Write-Host "`n=== 3. param whitelist (extra undeclared param) ==="
$b1 = '{"params":{"minId":"1","maxId":"300","__evil":"1"},"offset":0,"size":5}'
$r1 = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body $b1
Write-Host "rows=$($r1.data.rows.Count) hasMore=$($r1.data.hasMore) nextOffset=$($r1.data.nextOffset) cols=$($r1.data.columns.Count)"
Write-Host "col0=$($r1.data.columns[0].key) / $($r1.data.columns[0].label) / $($r1.data.columns[0].type)"
Write-Host "row0=$($r1.data.rows[0] -join ' | ')"

# 4. real paging: page at offset 0 and offset 200 must not overlap
Write-Host "`n=== 4. real paging: offset=0 vs offset=200 ==="
$a = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"1","maxId":"50000"},"offset":0,"size":200}'
$b = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"1","maxId":"50000"},"offset":200,"size":200}'
Write-Host "page0 rows=$($a.data.rows.Count) first=$($a.data.rows[0][0]) last=$($a.data.rows[-1][0]) hasMore=$($a.data.hasMore)"
Write-Host "page1 rows=$($b.data.rows.Count) first=$($b.data.rows[0][0]) last=$($b.data.rows[-1][0]) hasMore=$($b.data.hasMore)"
if ($a.data.rows[-1][0] -eq $b.data.rows[0][0]) { Write-Host "FAIL paging: boundary identical" } else { Write-Host "OK paging: boundary differs" }

# 5. hasMore correctness on last page
Write-Host "`n=== 5. hasMore on final page ==="
$c = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"1","maxId":"300"},"offset":0,"size":200}'
Write-Host "300 rows / page 200: rows=$($c.data.rows.Count) hasMore=$($c.data.hasMore) capped=$($c.data.capped)"
$d = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"1","maxId":"300"},"offset":200,"size":200}'
Write-Host "page2: rows=$($d.data.rows.Count) hasMore=$($d.data.hasMore) capped=$($d.data.capped)"

# 6. max_rows guard
Write-Host "`n=== 6. max_rows guard (offset beyond cap) ==="
$sw = [Diagnostics.Stopwatch]::StartNew()
$e = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"1","maxId":"50000"},"offset":999999,"size":200}'
$sw.Stop()
Write-Host "rows=$($e.data.rows.Count) hasMore=$($e.data.hasMore) capped=$($e.data.capped) durationMs=$($e.data.durationMs) wall=$($sw.ElapsedMilliseconds)ms"

# 7. missing required param must be rejected
Write-Host "`n=== 7. missing required param ==="
try {
  $null = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"","maxId":"300"},"offset":0,"size":5}'
  Write-Host "FAIL not rejected"
} catch {
  Write-Host "OK rejected HTTP=$($_.Exception.Response.StatusCode.value__)"
}

# 8. bad number param
Write-Host "`n=== 8. non-numeric value for number param ==="
try {
  $null = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"abc","maxId":"300"},"offset":0,"size":5}'
  Write-Host "FAIL not rejected"
} catch {
  Write-Host "OK rejected HTTP=$($_.Exception.Response.StatusCode.value__)"
}

# 9. deep offset sanity: skip 25000 rows
Write-Host "`n=== 9. deep offset 25000 ==="
$sw2 = [Diagnostics.Stopwatch]::StartNew()
$f = Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h -ContentType 'application/json; charset=utf-8' -Body '{"params":{"minId":"1","maxId":"50000"},"offset":25000,"size":200}'
$sw2.Stop()
Write-Host "rows=$($f.data.rows.Count) first=$($f.data.rows[0][0]) last=$($f.data.rows[-1][0]) hasMore=$($f.data.hasMore) durationMs=$($f.data.durationMs) wall=$($sw2.ElapsedMilliseconds)ms"

# 10. no perm for viewer role check: query-options needs report:create
Write-Host "`n=== 10. query-options ==="
$qo = Invoke-RestMethod -Uri "$base/bi/report/query-options" -Headers $h
Write-Host "options=$($qo.data.Count) last=$($qo.data[-1].id):$($qo.data[-1].name) params=$($qo.data[-1].params.Count)"
