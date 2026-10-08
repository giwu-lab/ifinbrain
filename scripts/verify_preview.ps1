# NOTE: keep this script pure ASCII.
# PowerShell reads .ps1 as GBK unless the file has a UTF-8 BOM, so non-ASCII
# comments get mangled and can break quote pairing. All output is ASCII too.
#
# Judge by body.code, NOT the HTTP status: this project wraps business and
# validation errors in an HTTP 200 envelope, so the status code carries no
# signal here. Result.ok() => code 0. Anything else => code 400 with a message.
$ErrorActionPreference = 'Continue'
$b = 'http://localhost:8080'
$lg = Invoke-RestMethod -Method Post -Uri "$b/api/auth/login" -ContentType 'application/json' -Body (@{username='admin';password='Admin@123'} | ConvertTo-Json)
$t = $lg.data.accessToken
$tmp = "$env:TEMP\pv3.json"

$cases = @(
  @{ n = 'baseline size=50';        j = '{ "queryId":15, "params":{"minId":"1","maxId":"50000"}, "size":50 }';   exp = 0 },
  @{ n = 'size omitted -> 50';      j = '{ "queryId":15, "params":{"minId":"1","maxId":"50000"} }';            exp = 0 },
  @{ n = 'size=1 (min boundary)';    j = '{ "queryId":15, "params":{"minId":"1","maxId":"50000"}, "size":1 }';   exp = 0 },
  @{ n = 'size=2000 (max boundary)'; j = '{ "queryId":15, "params":{"minId":"1","maxId":"50000"}, "size":2000 }';exp = 0 },
  @{ n = 'size=0 (min violation)';   j = '{ "queryId":15, "params":{"minId":"1","maxId":"50000"}, "size":0 }';   exp = 400 },
  @{ n = 'size=2001 (max violation)';j = '{ "queryId":15, "params":{"minId":"1","maxId":"50000"}, "size":2001 }';exp = 400 },
  @{ n = 'queryId missing';          j = '{ "params":{}, "size":10 }';                                          exp = 400 },
  @{ n = 'queryId null';             j = '{ "queryId":null, "params":{}, "size":10 }';                            exp = 400 },
  @{ n = 'queryId=99999 not exist';  j = '{ "queryId":99999, "params":{}, "size":10 }';                           exp = 400 },
  @{ n = 'required param missing';   j = '{ "queryId":15, "params":{}, "size":10 }';                              exp = 400 },
  @{ n = 'undeclared key ignored';   j = '{ "queryId":15, "params":{"minId":"1","maxId":"50000","__evil":"x"}, "size":10 }'; exp = 0 },
  @{ n = 'queryId=8 (no-arg query)'; j = '{ "queryId":8, "params":{}, "size":10 }';                               exp = 0 }
)

$pass = 0
$fail = 0
foreach ($c in $cases) {
    [System.IO.File]::WriteAllText($tmp, $c.j, (New-Object System.Text.UTF8Encoding($false)))
    $raw = curl.exe -s -X POST "$b/api/bi/report/preview" -H "Authorization: Bearer $t" -H "Content-Type: application/json" --data-binary "@$tmp"
    $o = $raw | ConvertFrom-Json
    $code = $o.code
    if ($code -eq $c.exp) { $pass++ } else { $fail++ }
    $mark = if ($code -eq $c.exp) { 'PASS' } else { 'FAIL' }
    $extra = ''
    if ($code -eq 0) { $extra = " rows=$(@($o.data.rows).Count) cols=$(@($o.data.columns).Count)" }
    $msg = ''
    if ($code -ne 0) { $msg = " msg=$($o.message)" }
    Write-Output ("{0,-5} {1,-26} code={2} (exp {3}){4}{5}" -f $mark, $c.n, $code, $c.exp, $extra, $msg)
}

# The core promise: preview must never create a report row.
$hdr = @{ Authorization = "Bearer $t" }
$before = @((Invoke-RestMethod -Method Get -Uri "$b/api/bi/report" -Headers $hdr).data).Count
[System.IO.File]::WriteAllText($tmp, '{ "queryId":15, "params":{"minId":"1","maxId":"50000"}, "size":50 }', (New-Object System.Text.UTF8Encoding($false)))
curl.exe -s -o NUL -X POST "$b/api/bi/report/preview" -H "Authorization: Bearer $t" -H "Content-Type: application/json" --data-binary "@$tmp"
$after = @((Invoke-RestMethod -Method Get -Uri "$b/api/bi/report" -Headers $hdr).data).Count
if ($before -eq $after) { $pass++ } else { $fail++ }
$mark = if ($before -eq $after) { 'PASS' } else { 'FAIL' }
Write-Output ("{0,-5} {1,-26} report_count {2} -> {3}" -f $mark, 'no-persist guarantee', $before, $after)
Write-Output "RESULT pass=$pass fail=$fail"
