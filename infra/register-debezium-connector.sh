#!/usr/bin/env bash
# 注册 Debezium MySQL Source Connector
# 使用：bash infra/register-debezium-connector.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
curl -i -X POST -H "Accept: application/json" -H "Content-Type: application/json" \
  http://localhost:8083/connectors \
  -d @"$HERE/debezium-mysql-connector.json"
echo
echo "已注册。查看：curl http://localhost:8083/connectors | jq"
echo "查看 CDC 主题（示例）：docker exec -it kafka kafka-topics --bootstrap-server kafka:29092 --list | grep ^cdc"
