# 注册 Debezium MySQL Source Connector (PowerShell 版)
# 使用：powershell -File infra/register-debezium-connector.ps1
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$connectUrl = "http://localhost:8083/connectors"

# 1. 主库 connector（user / product / booking 未分片表）
Invoke-RestMethod -Method Post -Uri $connectUrl -ContentType "application/json" -InFile "$here/debezium-mysql-connector.json"
Write-Host "✅ bizdb-connector 已注册"

# 2. 分片库 0 connector（bizdb_0.booking_0 / booking_1）
Invoke-RestMethod -Method Post -Uri $connectUrl -ContentType "application/json" -InFile "$here/debezium-shard0-connector.json"
Write-Host "✅ bizdb0-sharding-connector 已注册"

# 3. 分片库 1 connector（bizdb_1.booking_0 / booking_1）
Invoke-RestMethod -Method Post -Uri $connectUrl -ContentType "application/json" -InFile "$here/debezium-shard1-connector.json"
Write-Host "✅ bizdb1-sharding-connector 已注册"

Write-Host "检查：Invoke-RestMethod $connectUrl"
