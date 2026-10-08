$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$base = 'http://localhost:8080/api'
$login = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body '{"username":"admin","password":"Admin@123"}'
$h = @{ Authorization = "Bearer $($login.data.accessToken)" }

function Run($body) {
  (Invoke-RestMethod -Uri "$base/bi/report/1/run" -Method Post -Headers $h `
     -ContentType 'application/json; charset=utf-8' -Body $body).data
}

Write-Host "=== A. max_rows=50000 触顶：最后一页 offset=49800 ==="
$d = Run '{"params":{"minId":"1","maxId":"50000"},"offset":49800,"size":200}'
Write-Host "rows=$($d.rows.Count) hasMore=$($d.hasMore) capped=$($d.capped) nextOffset=$($d.nextOffset)"

Write-Host "`n=== B. 正好在上限前一页 offset=49600 ==="
$e = Run '{"params":{"minId":"1","maxId":"50000"},"offset":49600,"size":200}'
Write-Host "rows=$($e.rows.Count) hasMore=$($e.hasMore) capped=$($e.capped) last=$($e.rows[-1][0])"

Write-Host "`n=== C. 参数收窄后 capped 应为 false ==="
$f = Run '{"params":{"minId":"1","maxId":"1000"},"offset":800,"size":200}'
Write-Host "rows=$($f.rows.Count) hasMore=$($f.hasMore) capped=$($f.capped) last=$($f.rows[-1][0])"

Write-Host "`n=== D. size 上限夹紧(传 99999 应夹到 2000) ==="
$g = Run '{"params":{"minId":"1","maxId":"50000"},"offset":0,"size":99999}'
Write-Host "returned rows=$($g.rows.Count) (expect 2000) hasMore=$($g.hasMore)"

Write-Host "`n=== E. size=1 最小页 ==="
$h1 = Run '{"params":{"minId":"1","maxId":"50000"},"offset":0,"size":1}'
Write-Host "rows=$($h1.rows.Count) hasMore=$($h1.hasMore)"

Write-Host "`n=== F. offset 负数应被夹到 0 ==="
$i = Run '{"params":{"minId":"1","maxId":"50000"},"offset":-5,"size":3}'
Write-Host "offset=$($i.offset) first=$($i.rows[0][0])"

Write-Host "`n=== G. 连续翻 3 页，边界应严格首尾相接 ==="
$p1 = Run '{"params":{"minId":"1","maxId":"50000"},"offset":0,"size":100}'
$p2 = Run '{"params":{"minId":"1","maxId":"50000"},"offset":100,"size":100}'
$p3 = Run '{"params":{"minId":"1","maxId":"50000"},"offset":200,"size":100}'
Write-Host "p1: $($p1.rows[0][0])..$($p1.rows[-1][0])  next=$($p1.nextOffset)"
Write-Host "p2: $($p2.rows[0][0])..$($p2.rows[-1][0])  next=$($p2.nextOffset)"
Write-Host "p3: $($p3.rows[0][0])..$($p3.rows[-1][0])  next=$($p3.nextOffset)"
if ($p1.rows[-1][0] -eq $p2.rows[0][0]) { Write-Host "FAIL p1/p2 overlap" }
elseif ($p2.rows[-1][0] -eq $p3.rows[0][0]) { Write-Host "FAIL p2/p3 overlap" }
else { Write-Host "OK no overlap across 3 pages" }

Write-Host "`n=== H. 空结果集 ==="
$j = Run '{"params":{"minId":"99999","maxId":"99999"},"offset":0,"size":200}'
Write-Host "rows=$($j.rows.Count) hasMore=$($j.hasMore) capped=$($j.capped)"
