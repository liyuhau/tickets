#!/usr/bin/env bash
# 注册 Debezium MySQL Source Connectors（主库 + 分片库）
# 使用：bash infra/register-debezium-connector.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
CONNECT_URL="http://localhost:8083/connectors"

# 1. 主库 connector（user / product / booking 未分片表）
curl -i -X POST -H "Accept: application/json" -H "Content-Type: application/json" \
  "$CONNECT_URL" -d @"$HERE/debezium-mysql-connector.json"
echo -e "\n✅ bizdb-connector 已注册"

# 2. 分片库 0 connector（bizdb_0.booking_0 / booking_1）
curl -i -X POST -H "Accept: application/json" -H "Content-Type: application/json" \
  "$CONNECT_URL" -d @"$HERE/debezium-shard0-connector.json"
echo -e "\n✅ bizdb0-sharding-connector 已注册"

# 3. 分片库 1 connector（bizdb_1.booking_0 / booking_1）
curl -i -X POST -H "Accept: application/json" -H "Content-Type: application/json" \
  "$CONNECT_URL" -d @"$HERE/debezium-shard1-connector.json"
echo -e "\n✅ bizdb1-sharding-connector 已注册"

echo "查看：curl $CONNECT_URL | jq"
echo "查看 CDC 主题：docker exec -it kafka kafka-topics --bootstrap-server kafka:29092 --list | grep ^cdc"
