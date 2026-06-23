# data-sync-service · 全球旅游产品同业交易平台 Demo

> 整体技术架构：Spring Boot + Nacos + Dubbo + Spring Cloud Gateway + Kafka + Debezium + MySQL/MyBatis + Redis + Elasticsearch + 数仓
> 业务场景：全球旅游产品同业交易平台，覆盖 **机票** 和 **酒店** 两类产品。

## 最终交付总目录

> 建议先从这里开始阅读，再按需跳转到各文档。

- `docs/index.md`：最终交付总目录页 / 阅读导航
- `docs/architecture-explained.md`：架构全链路解析、代码关联、组件职责
- `docs/database.md`：数据库 DDL、索引、测试数据、运维 SQL
- `docs/es-layer.md`：Elasticsearch 宽表层 ADR、企业级增项、索引治理
- `docs/deployment-guide.md`：部署前准备、启动顺序、验证清单、常见问题

## 业务定位

同业 B2B 渠道（如 OTA、海外分销商、企业出行平台）通过本平台完成：
1. **查询** 全球机票座位余量 / 酒店房间余量（含实时价格）
2. **占用** 库存并生成预订单（同业渠道单号幂等去重，机票乘机人数上限 9，酒店房间数按库存校验）
3. **同步** 订单 / 产品 / 用户变更，通过 MySQL binlog → Debezium → Kafka 实时落地到 ES（搜索）/ Redis（缓存）/ 数仓（BI）

## 架构与文档索引

- `docs/architecture-explained.md`：全链路架构、鉴权、Dubbo、Nacos、Zookeeper、CDC、线程池说明
- `docs/database.md`：业务库 DDL、索引、测试数据、CDC 覆盖表说明
- `docs/es-layer.md`：Elasticsearch 宽表层 ADR、索引治理、回填与企业级改造说明
- `docs/deployment-guide.md`：部署与上线操作指南（基础设施、配置导入、启动顺序、验证清单）
- `infra/mysql-init/`：容器首次启动执行的建表与测试数据脚本
- `infra/nacos-config/`：Nacos 配置中心导入模板

## ES 企业级增项

- `data-sync-consumer`：CDC 增量同步主链路
- `reindex-job`：独立全量回填 Job
- `docs/es-layer.md`：Elasticsearch 宽表层 ADR（方案 B 主线、企业级增项、后续演进）
- 索引版本建议：`booking_wide_v1` / `booking_wide_v2` + alias `booking_wide`
- 后续能力：批量写入、批量删除、补偿机制、健康检查、分页回填、查询服务层

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| `common-core` | - | 统一返回 `R<T>` / 统一异常处理 / 线程池治理 / 线程诊断 / HttpClient / AuthContext 自动装配 |
| `inventory-api` | - | Dubbo 接口契约：`InventoryFacade` + `StockDTO` + `ProductType` |
| `gateway-service` | 8080 | API 网关：OAuth2 资源服务器、JWT 校验、吊销检查、身份透传、路由转发、Sentinel 限流 |
| `user-service` | 8081 | 授权服务（AS）：客户端管理、JWT 颁发、令牌吊销、JWK 暴露 |
| `order-service` | 8082 | 预订中心：MySQL `booking` 表 + MyBatis；通过 Dubbo 调用产品中心扣减库存 |
| `inventory-service` | 8083 | 产品中心：MySQL `product` 表 + MyBatis；提供 Dubbo Service + HTTP 查询 |
| `data-sync-consumer` | 8090 | CDC 消费：订阅 Debezium 主题，落 ES / Redis / 数仓 |
| `reindex-job` | - | 独立回填 Job：MySQL `booking` 全量重建 ES `booking_wide` |

## 数据库

业务库为 `bizdb`，DDL / 索引 / 测试数据详见 [`docs/database.md`](docs/database.md)。

核心表：

| 表 | 归属 | 主要字段 |
|---|---|---|
| `product` | inventory-service | `product_id(PK)` · `type` · `name` · `travel_date` · `price_cents` · `stock` · `version` |
| `booking` | order-service | `id(PK)` · `channel + channel_order_no(UNIQUE)` · `product_id` · `quantity` · `total_price_cents` · `status` |
| `user` | user-service | `id(PK)` · `name(UNIQUE)` · `channel` · `status` |
| `oauth2_registered_client` | user-service | OAuth2 客户端持久化 |
| `oauth2_authorization` | user-service | 授权、访问令牌、刷新令牌、吊销状态 |

容器首次启动会自动执行 `infra/mysql-init/01-schema.sql`，完成建表和基础测试数据初始化。

## 一、最小启动

### 1. 启动基础设施

```powershell
docker compose up -d mysql nacos zookeeper kafka redis elasticsearch
```

### 2. 导入 Nacos 配置

在 Nacos 控制台 `http://localhost:8848/nacos`（`nacos/nacos`）导入 `infra/nacos-config/` 下的配置：
- `common-shared.yaml`
- `gateway-service.yaml`
- `inventory-service.yaml`
- `order-service.yaml`

### 3. 启动 Spring Boot 服务

```powershell
mvn -pl :user-service,:inventory-service,:order-service,:gateway-service spring-boot:run
```

### 4. 启动 CDC 消费者

```powershell
mvn -pl :data-sync-consumer -am spring-boot:run
```

### 5. 验证链路

- 网关：`http://localhost:8080`
- 授权服务：`http://localhost:8081`
- 预订服务：`http://localhost:8082`
- 产品服务：`http://localhost:8083`
- CDC 消费者：`http://localhost:8090`（如开放健康检查）

### 1.1 注册中心可切换（Nacos / Zookeeper）

Dubbo 默认走 **Nacos** 注册中心，也可切换到 **Zookeeper**：

```powershell
$env:DUBBO_REGISTRY = "zookeeper://127.0.0.1:2181"
mvn -pl :inventory-service spring-boot:run
```

- 默认值配置在各服务 `application.yaml` / `bootstrap.yml` 中
- 不传环境变量时默认使用 Nacos
- Zookeeper 同时也可用于 Dubbo 注册中心和 Kafka 依赖的协调集群

## 二、调用示例（统一返回 `R<T>`）

```bash
# 查询机票余量
curl http://localhost:8080/api/inventory/FLT-CA1234-20260601-Y

# 创建预订单（携程 2 张机票）
curl -X POST http://localhost:8080/api/bookings -H "Content-Type: application/json" `
  -d '{"channel":"CTRIP","channelOrderNo":"C20260520-00002","userId":1001,"productId":"FLT-CA1234-20260601-Y","quantity":2,"passengerName":"LI SI","passengerIdNo":"E11111111"}'
```

## 三、完整 CDC 链路（订单变更 → ES / Redis / 数仓）

```powershell
docker compose up -d mysql nacos zookeeper kafka redis elasticsearch
powershell -File infra/register-debezium-connector.ps1
mvn -pl data-sync-consumer -am spring-boot:run
mvn -pl reindex-job -am spring-boot:run
```

Debezium 捕获 `bizdb.user` / `bizdb.product` / `bizdb.booking` 的 binlog 变更并推送 Kafka，`data-sync-consumer` 再分别写入：
- **ES**：搜索与宽表查询
- **Redis**：热点缓存与快速失效
- **数仓**：BI 报表、离线分析、历史统计

## 四、统一返回 / 异常

`common-core` 提供统一返回 `R<T>` 和全局异常处理：

| code 段 | 含义 |
|---|---|
| 200 | 成功 |
| 1xxx | 通用错误（参数、签名、认证） |
| 2xxx | 业务错误（余量不足、产品不存在、订单不存在） |
| 4xxx | 下游服务调用错误 |
| 5xxx | 服务内部错误 |

## 五、Dubbo / Nacos / Zookeeper

- `common-shared.yaml` 负责 Dubbo 的统一超时、重试、线程池和注册中心基础配置
- `order-service`、`inventory-service` 作为 Dubbo Provider
- 网关和业务服务通过 Nacos 读取共享配置与服务发现信息
- 如需切换 Dubbo 注册中心，优先修改 `DUBBO_REGISTRY` 环境变量

## 六、鉴权（OAuth2 Authorization Server + Resource Server）

### 6.1 总体方案

```text
客户端 → 网关 /api/oauth2/token → user-service(AS)
     → 返回 JWT(access_token)
     → 携带 Bearer Token 访问业务接口
     → 网关验签 + 吊销检查 + 身份透传
     → 下游通过 AuthContext 获取用户信息
```

### 6.2 关键点

- `user-service` 负责授权、客户端持久化、JWK 暴露、令牌吊销
- `gateway-service` 负责 JWT 验签、token 吊销检查、Header 透传和网关路由
- `common-core` 负责下游 `AuthContext` 还原和统一异常返回

### 6.3 客户端与令牌

- `ctrip-channel` / `fliggy-channel` / `expedia-channel` 为演示客户端
- 令牌中会写入 `sub`、`channel`、`name`、`userId` 等声明
- 生产建议使用 MySQL `JdbcRegisteredClientRepository` 和 KMS / Vault 管理 RSA 私钥

### 6.4 刷新、吊销与内省

- `refresh_token`：适用于授权码模式的长会话刷新
- `revoke`：吊销 refresh/access token
- `introspect`：用于检查令牌状态，支持强一致吊销场景

### 6.5 网关到下游的用户信息传递

1. 网关校验 JWT
2. `IdentityPropagationFilter` 追加 `X-User-*` Header
3. 下游 `AuthContextFilter` 还原 `ThreadLocal`
4. 业务代码通过 `AuthContext.current()` 直接读取当前用户

## 七、目录结构

```text
data-sync-service/
├── docs/
│   ├── architecture-explained.md
│   ├── database.md
│   ├── es-layer.md
│   └── deployment-guide.md
├── infra/
│   ├── mysql-init/
│   ├── nacos-config/
│   └── debezium-mysql-connector.json
├── common-core/
├── inventory-api/
├── gateway-service/
├── user-service/
├── order-service/
├── inventory-service/
└── data-sync-consumer/
```

## 八、后续优化建议

- 补充网关、Dubbo、CDC 的集成测试
- 增加更完善的 Prometheus / Grafana 指标监控
- 为 `order-service` 和 `inventory-service` 增加更细粒度的幂等与补偿机制
- 如需要跨表大宽表查询，可继续沉淀 ES 宽表模型
