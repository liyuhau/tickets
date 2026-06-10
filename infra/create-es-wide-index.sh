#!/usr/bin/env bash
# 创建 ES 大宽表索引 booking_wide（含 mapping）
# 使用：bash infra/create-es-wide-index.sh

ES_URL="${ES_URL:-http://localhost:9200}"

curl -X PUT "$ES_URL/booking_wide" -H "Content-Type: application/json" -d '
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "id":               { "type": "long" },
      "channel":          { "type": "keyword" },
      "channelOrderNo":   { "type": "keyword" },
      "userId":           { "type": "keyword" },
      "productId":        { "type": "keyword" },
      "productType":      { "type": "keyword" },
      "quantity":         { "type": "integer" },
      "travelDate":       { "type": "date", "format": "yyyy-MM-dd||epoch_millis" },
      "passengerName":    { "type": "text", "analyzer": "standard" },
      "passengerIdNo":    { "type": "keyword" },
      "unitPriceCents":   { "type": "long" },
      "totalPriceCents":  { "type": "long" },
      "currency":         { "type": "keyword" },
      "status":           { "type": "keyword" },
      "createTime":       { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||epoch_millis" },
      "updateTime":       { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||epoch_millis" },

      "userName":           { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
      "userChannel":        { "type": "keyword" },
      "userEmail":          { "type": "keyword" },
      "userStatus":         { "type": "keyword" },

      "productName":        { "type": "text", "analyzer": "standard", "fields": { "keyword": { "type": "keyword" } } },
      "productTravelDate":  { "type": "date", "format": "yyyy-MM-dd||yyyyMMdd||epoch_millis" },
      "productPriceCents":  { "type": "long" },
      "productStock":       { "type": "integer" }
    }
  }
}'

echo -e "\n✅ booking_wide 索引创建完成"
