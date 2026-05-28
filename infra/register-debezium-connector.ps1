# 注册 Debezium MySQL Source Connector (PowerShell 版)
# 使用：powershell -File infra/register-debezium-connector.ps1
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8083/connectors" `
  -ContentType "application/json" `
  -InFile "$here/debezium-mysql-connector.json"
Write-Host "已注册。检查：Invoke-RestMethod http://localhost:8083/connectors"
