# data-sync-consumer ES 层说明

## 作用
- 订阅 Debezium CDC 消息
- 将 `booking` / `user` / `product` 变更写入 Elasticsearch
- 维护 `booking_wide` 宽表索引
- 支持首次全量回填与后续增量同步

## 依赖关系
- 数据源：MySQL `bizdb` 的 binlog
- 中间层：Debezium → Kafka
- 同步层：`data-sync-consumer`
- 目标层：Elasticsearch `booking_wide`

## 启动前准备
1. 启动 Elasticsearch
2. 确认 `ES_URIS`
3. 确认 `booking-wide-index.json` 已准备好
4. 确认 `data-sync-consumer/src/main/resources/bootstrap.yml` 中的 ES 配置已生效
5. 如需首次回填，准备好 MySQL 连接与 `CDC_REINDEX_ENABLED=true`

## 索引创建
推荐先手工创建一次索引，避免首次运行时因权限或映射问题导致启动失败：

```bash
bash infra/create-booking-wide-index.sh
```

Windows 环境可执行：

```powershell
powershell -File infra/create-booking-wide-index.ps1
```

## 索引结构说明
- 索引名：`booking_wide`
- 主键：`booking.id`
- 类型策略：
  - 标识/状态字段使用 `keyword`
  - 数值字段使用 `long` / `integer`
  - 文本搜索字段使用 `text`
- 映射：建议固定为显式 mapping，避免 CDC 字段漂移造成索引膨胀

## 自动建索引
应用启动时会执行 `ElasticsearchBootstrap`：
- 检查 `booking_wide` 是否存在
- 不存在则创建索引并写入 mapping
- 已存在则直接跳过

## 全量回填
首次上线或 ES 数据丢失时，可开启全量回填：

```powershell
$env:CDC_REINDEX_ENABLED = "true"
mvn -pl data-sync-consumer -am spring-boot:run
```

回填逻辑会从 MySQL 扫描 `booking` 表，重新写入 `booking_wide` 文档。

## 增量同步
CDC 消息到达后：
1. `CdcEventListener` 解析 Kafka 消息
2. `WideTableSyncer` 组装宽表文档
3. `BookingWideEsService` 完成 ES upsert/delete
4. `user` / `product` 维度变化会触发宽表批量刷新

## 查询验证
- 使用 `booking_wide` 索引查询订单宽表
- 检查文档是否包含 booking / user / product 三类字段
- 可通过 Kibana / curl / ES Console 验证

示例：

```bash
curl http://localhost:9200/booking_wide/_search
```

## 常见问题
- **索引不存在**：先执行索引创建脚本，或检查自动建索引权限
- **连接失败**：确认 `ES_URIS` 与账号密码配置正确
- **回填为空**：确认 `CDC_REINDEX_ENABLED=true` 且 MySQL `booking` 表有数据
- **字段缺失**：确认 CDC 消息字段名与 `BookingWideDoc` 映射一致

## 回滚建议
如果需要临时停用 ES 写入：
- 关闭 `data-sync-consumer`
- 或在配置中临时关闭消费侧 ES 同步开关
- ES 索引可保留，用于后续恢复
