# ADR-001: Elasticsearch 宽表层方案决策

- **状态**：Accepted
- **日期**：2026-06-23
- **范围**：`data-sync-consumer` / `reindex-job` / `docs/es-layer.md`

> 阅读建议：先看 `docs/index.md` 和 `docs/architecture-explained.md`，再看本文件理解 ES 宽表层的设计决策、企业级增项与演进方向。

## 1. 背景

本项目需要将 MySQL `bizdb` 中的 `booking` / `user` / `product` 变更同步到 Elasticsearch，支持：

- 订单、用户、产品的跨表联合查询
- 客服/运营的宽表检索
- CDC 增量同步
- 首次上线或故障后的全量回填

早期存在两种实现路线：
1. 在 CDC 消费者里直接通过 HTTP 调 ES REST API
2. 使用类型化文档 + Repository + Service 的结构化实现

## 2. 决策

采用 **方案 B** 作为主实现：
- `BookingWideDoc`：类型化宽表文档
- `BookingWideRepository`：Repository 层
- `BookingWideEsService`：统一 ES 操作入口
- `ElasticsearchBootstrap`：启动时索引初始化
- `reindex-job`：独立全量回填 Job
- `WideTableSyncer`：只负责 CDC 编排与宽表聚合

保留 `CdcEventListener` 作为 Kafka CDC 消费入口，但不让它承担具体 ES 访问细节。

## 3. 为什么选择这个方案

### 3.1 编译期更安全
类型化文档和 Repository 能让字段、结构、索引映射更早暴露问题，降低运行时错误概率。

### 3.2 维护成本更低
ES 写入、删除、回填、初始化都收口到明确组件，避免逻辑分散在监听器里。

### 3.3 更适合企业级长期运行
企业系统通常需要：
- 索引版本管理
- 回填重建
- 批量写入
- 失败补偿
- 健康检查
- 查询服务层

方案 B 更容易逐步补齐这些能力。

### 3.4 职责边界清晰
- `data-sync-consumer`：只负责 CDC 增量同步
- `reindex-job`：只负责 MySQL → ES 全量回填
- `WideTableSyncer`：只负责宽表聚合和维度缓存
- `BookingWideEsService`：只负责 ES CRUD

## 4. 被拒绝的备选方案

### 4.1 方案 A：CDC 中直接 HTTP 写 ES
**结论：拒绝作为主方案**

问题：
- 逻辑耦合到监听器
- 运行时字符串拼接，容易出错
- 维护性差
- 不利于重建、补偿、治理

适用场景：
- 快速验证
- 临时兼容
- Demo

### 4.2 在 CDC 消费服务里直接做 JDBC 回填
**结论：拒绝长期保留**

问题：
- 增量与全量职责混杂
- 依赖膨胀
- 容易拖慢 CDC 主链路
- 运维边界不清

因此已将回填能力拆到独立 `reindex-job` 模块。

## 5. 对现有系统的影响

### 5.1 正面影响
- ES 层结构更稳定
- 增量和全量分离
- 文档与代码一致性更高
- 后续补批量写入、索引版本、查询服务更容易

### 5.2 需要持续补齐的能力
- 批量写入 `bulkUpsert`
- 批量删除 `bulkDelete`
- 查询服务层
- 写入失败补偿
- 回填分页与断点续跑
- ES 健康检查与就绪控制
- 索引 alias 版本化

## 6. 当前已落地的结构

### 增量同步
- `CdcEventListener`
- `WideTableSyncer`
- `BookingWideEsService`

### 全量回填
- `reindex-job`
- `BookingWideReindexJob`

### 索引治理
- `ElasticsearchBootstrap`
- `booking_wide_v1` 显式 mapping

## 7. 企业级升级方向

### 7.1 索引版本化
- `booking_wide_v1` / `booking_wide_v2`
- alias：`booking_wide`

### 7.2 批量写入
- 增加 `bulkUpsert` / `bulkDelete`
- CDC 先聚合批次，再写 ES

### 7.3 回填分页
- `reindex-job` 采用分页扫描、批次提交、可重跑

### 7.4 补偿机制
- 失败记录落库/落队列
- 增加重放 Job 或补偿服务

### 7.5 健康检查
- ES 健康检查
- readiness / liveness
- 索引存在性与映射版本检查

### 7.6 查询服务层
- 查询订单宽表
- 查询用户订单历史
- 查询产品关联订单
- 分页聚合查询

## 8. 后续行动

1. 保持方案 B 作为 ES 层主线
2. 引入批量写入与批量删除
3. 为回填 Job 增加分页、批次处理和可重跑能力
4. 引入索引版本与 alias 机制
5. 增加 ES 健康检查与告警
6. 补齐查询服务层

## 9. 结论

对于当前项目，**方案 B 是更适合长期维护和企业级演进的 Elasticsearch 主方案**。它比“CDC 里直接 HTTP 写 ES”更清晰、更稳定，也更容易扩展到批量、补偿、查询和治理能力。

---

## 附：当前 ES 层设计说明

### 主方案

本项目采用方案 B 作为 ES 层主实现：
- `BookingWideDoc`：类型化宽表文档
- `BookingWideRepository`：Repository 层
- `BookingWideEsService`：统一 ES 操作入口
- `ElasticsearchBootstrap`：启动时索引初始化
- `reindex-job`：独立全量回填 Job
- `WideTableSyncer`：只负责 CDC 编排与宽表聚合

### 同步流程

1. `CdcEventListener` 订阅 Kafka CDC 消息
2. `WideTableSyncer` 聚合宽表字段并刷新维度缓存
3. `BookingWideEsService` 承接 ES 写入/删除
4. `ElasticsearchBootstrap` 负责索引检查与创建
5. `BookingWideReindexJob` 负责首次全量回填

### 当前 ES 层已经做对的部分

- 方案选择正确：已经从“CDC 里直接 HTTP 写 ES”收口到“类型化文档 + 服务层”
- 职责拆分方向正确：`CdcEventListener`、`WideTableSyncer`、`BookingWideEsService`、`ElasticsearchBootstrap`、`reindex-job` 已经有了基本边界
- 文档索引已对齐：README、架构文档、数据库文档、ES 文档、部署文档能够互相跳转

### 目前还不够企业级的地方

#### `BookingWideEsService` 还偏轻
当前主要只做：
- `upsert`
- `deleteById`

企业级还应继续补：
- 批量写入 `bulkUpsert`
- 批量删除 `bulkDelete`
- 查询接口 `searchBy...`
- 写入失败重试
- 死信重放 / 补偿接口

#### 缺少真正的批量写入
现在仍以单条写为主。企业级 ES 更推荐：
- CDC 消息先入缓冲
- 按批组装
- 批量写 ES
- 定时 flush / 异步 flush

#### 维度刷新还不够正式
当前 `user/product` 维度变化场景还没有真正的企业级批量刷新实现。后续应明确三种策略之一：
- 重建相关文档
- ES 原生 `update_by_query`
- 查询时聚合，不做宽表批量刷新

#### 回填 Job 需要分页/分批
全量回填不应直接 `SELECT * FROM booking` 后一次性灌入，而应：
- 分页查询
- 批次处理
- 失败可重试
- 可断点继续

#### 缺少 ES 写入失败补偿
企业级必须考虑：
- 重试队列
- 死信记录
- 失败持久化
- 重放任务

#### 缺少 ES 健康检查与就绪控制
建议增加：
- ES 健康检查
- readiness / liveness
- ES 不可用时的降级或启动失败策略

#### 索引版本管理还可更好
建议将：
- `booking_wide`
升级为：
- `booking_wide_v1`
- `booking_wide_v2`
- alias：`booking_wide`

#### Mapping 还可进一步收紧
建议继续明确：
- 哪些字段是 `keyword`
- 哪些字段是 `text`
- 日期格式
- 是否关闭动态映射

#### 缺少 ES 查询服务层
除了写 ES，还应有读 ES 的能力，例如：
- 按订单号查宽表
- 按用户查订单
- 按产品查订单
- 按日期 / 渠道聚合统计

### 当前项目里更推荐的路线

如果目标是长期稳定运行，建议采用：
1. **增量同步**：交给 `data-sync-consumer`
2. **全量回填**：交给独立 `reindex-job`
3. **ES 写入**：统一交给 `BookingWideEsService`
4. **宽表组装**：交给 `WideTableSyncer`
5. **索引初始化**：交给 `ElasticsearchBootstrap`
6. **查询能力**：后续补独立查询服务层

### 结论

当前 ES 层已经从“能跑”进化到“结构正确”，但要达到真正企业级，还需继续补齐：
- 回填分页
- 批量写入
- 补偿机制
- 健康检查
- 索引版本
- 查询服务

如果继续演进，推荐以“方案 B”为长期主线，保留清晰的职责边界和可运维能力。
