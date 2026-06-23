$es = $env:ES_URIS
if ([string]::IsNullOrWhiteSpace($es)) { $es = 'http://localhost:9200' }
$index = 'booking_wide'
$mapping = Get-Content -Raw -Path (Join-Path $PSScriptRoot 'booking-wide-index.json')
Invoke-RestMethod -Method Put -Uri "$es/$index" -ContentType 'application/json' -Body $mapping | Out-Null
Write-Host "Created index: $index"
