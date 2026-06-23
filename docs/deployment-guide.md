# 部署与上线操作指南

> 适用场景：本地开发环境、联调环境、测试环境、演示环境部署。
>
> 目标：按照本文档执行后，可完成 MySQL / Nacos / Kafka / Zookeeper / Redis / Elasticsearch / 各 Spring Boot 服务 / CDC 的完整启动与验证。
> 阅读建议：先看 `docs/index.md` 和 `docs/architecture-explained.md`，再结合 `docs/database.md` 与 `docs/es-layer.md` 执行部署。

---

## 1. 部署前准备

### 1.0 先做哪些操作

部署前建议按下面顺序完成检查：

1. 确认本机已安装并可用 `Docker`、`JDK 17`、`Maven 3.9+`
2. 确认项目源码完整，`pom.xml` 可被 Maven 正常识别
3. 确认 `docker-compose.yml` 中的端口没有被本机其他服务占用
4. 确认 `infra/mysql-init/`、`infra/nacos-config/`、`infra/debezium-mysql-connector.json` 文件存在
5. 确认将要部署的环境变量、Nacos 地址、Dubbo 注册中心地址已准备好

### 1.1 基础依赖

- Docker Desktop 或 Docker Engine
- JDK 17
- Maven 3.9+
- PowerShell（Windows）
- 可访问 Nacos 控制台与 MySQL 容器

### 1.2 环境验证命令

```powershell
java -version
mvn -version
docker -v
docker compose version
```

如果这些命令有一个不可用，先补齐本机环境再继续。

### 1.3 目录要求

请确认项目结构完整，重点目录如下：

- `infra/mysql-init/`
- `infra/nacos-config/`
- `infra/debezium-mysql-connector.json`
- `docker-compose.yml`
- `pom.xml`

### 1.4 配置确认

重点检查以下配置是否与目标环境一致：

- `docker-compose.yml` 中的端口映射
- `infra/nacos-config/common-shared.yaml`
- 各服务 `bootstrap.yml`
- `gateway-service` 的内部签名密钥配置
- `user-service` 的 OAuth2 客户端和 RSA 私钥配置
- `DUBBO_REGISTRY` 环境变量

### 1.5 端口占用检查

```powershell
netstat -ano | findstr ":8080"
netstat -ano | findstr ":8081"
netstat -ano | findstr ":8082"
netstat -ano | findstr ":8083"
netstat -ano | findstr ":8848"
netstat -ano | findstr ":2181"
netstat -ano | findstr ":3306"
netstat -ano | findstr ":9092"
```

如果端口已被占用，需要先释放端口或修改 `docker-compose.yml` / 服务配置。

### 1.6 Maven 依赖预热（可选）

如果是第一次在这台机器上构建，建议先做一次依赖预热：

```powershell
mvn -q -DskipTests dependency:go-offline
```

这样后续启动和编译会更快，也能提前暴露依赖下载问题。

---

## 2. 一键启动基础设施

### 2.1 启动基础组件

```powershell
docker compose up -d mysql nacos zookeeper kafka redis elasticsearch
```

### 2.2 验证容器状态

```powershell
docker ps
```

重点确认：
- `mysql` 正常
- `nacos` 正常
- `zookeeper` 正常
- `kafka` 正常
- `redis` 正常
- `elasticsearch` 正常

---

## 3. 初始化数据库

### 3.1 首次启动

`mysql-init` 脚本会在容器首次启动时执行，用于：
- 创建 `bizdb`
- 建表
- 灌入测试数据

### 3.2 手工验证

```powershell
docker exec -it mysql mysql -uroot -proot bizdb -e "SHOW TABLES; SELECT COUNT(*) FROM product; SELECT COUNT(*) FROM booking; SELECT COUNT(*) FROM user;"
```

如果表不存在或数据为空，说明初始化失败，需要检查：
- `docker-compose.yml`
- `infra/mysql-init/01-schema.sql`
- MySQL 日志

---

## 4. 导入 Nacos 配置

### 4.1 必须导入的配置

请先在 Nacos 控制台导入：
- `common-shared.yaml`
- `gateway-service.yaml`
- `inventory-service.yaml`
- `order-service.yaml`
- `user-service.yaml`（如果单独提供）

### 4.2 导入步骤

1. 打开 `http://localhost:8848/nacos`
2. 使用 `nacos / nacos` 登录
3. 进入“配置管理”
4. 按文件名创建配置
5. 格式选择 `YAML`
6. Group 使用 `DEFAULT_GROUP`

### 4.3 导入后检查

确保以下内容生效：
- `spring.cloud.nacos.server-addr`
- `spring.security.oauth2.resourceserver.jwt.issuer-uri`
- `dubbo.registry.address`
- `thread-pool` 参数
- `security.*` 参数

---

## 5. 启动顺序

### 5.1 推荐顺序

1. 基础设施：MySQL / Nacos / Zookeeper / Kafka / Redis / ES
2. 授权服务：`user-service`
3. 产品服务：`inventory-service`
4. 订单服务：`order-service`
5. 网关：`gateway-service`
6. CDC 消费者：`data-sync-consumer`

### 5.2 启动命令

```powershell
mvn -pl :user-service,:inventory-service,:order-service,:gateway-service spring-boot:run
mvn -pl :data-sync-consumer -am spring-boot:run
```

如果需要分开启动，也可以逐个模块执行。

---

## 6. 启动后检查项

### 6.1 服务健康检查

- `user-service`：`http://localhost:8081`
- `gateway-service`：`http://localhost:8080`
- `inventory-service`：`http://localhost:8083`
- `order-service`：`http://localhost:8082`
- `data-sync-consumer`：看日志是否正常消费 Kafka

### 6.2 Nacos 检查

- 服务是否注册成功
- 配置是否加载成功
- 路由规则是否生效

### 6.3 Dubbo 检查

确认 `order-service` 能调用 `inventory-service`：
- 查询库存接口可访问
- 下单接口能成功扣减库存

---

## 7. CDC 注册

### 7.1 注册 Debezium Connector

```powershell
powershell -File infra/register-debezium-connector.ps1
```

这一步的目的不是单纯“注册一个连接器”，而是把 MySQL 的 binlog 变更正式接入 CDC 链路。注册成功后，`data-sync-consumer` 才能收到 `booking` / `user` / `product` 三张表的变更消息。

### 7.2 检查 Connector 状态

通过 Kafka Connect API 或日志确认：
- `bizdb.user`
- `bizdb.product`
- `bizdb.booking`

是否已进入监听。

这一步建议和 `docs/es-layer.md` 一起看：`es-layer.md` 说明的是 ES 层为什么拆成 `data-sync-consumer` 和 `reindex-job`，而这里说明的是部署时如何把 CDC 链路真正拉起来。

### 7.3 ES 与回填检查

- `data-sync-consumer`：CDC 增量同步
- `reindex-job`：独立全量回填 Job
- `docs/es-layer.md`：ES 宽表层 ADR 与企业级增项说明
- `BookingWideReindexJob`：分页回填、批次提交、可重跑

这一段的核心是把“增量同步”和“全量回填”分开验证：
- 增量验证 Kafka 消费是否正常
- 回填验证 MySQL → ES 的重建链路是否正常

### 7.4 ES 健康检查建议

如果需要更稳妥的上线验证，建议额外检查：
- Elasticsearch 是否可连通
- `booking_wide_v1` 是否存在
- 别名 `booking_wide` 是否指向正确索引
- `data-sync-consumer` 的 actuator 健康状态

这部分和 `docs/es-layer.md` 的“企业级升级方向”对应，属于上线前的最低安全线。

---

## 8. 业务验证

### 8.1 获取 Token

```powershell
curl -X POST http://localhost:8080/api/oauth2/token `
  -u "ctrip-channel:ctrip-secret" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=client_credentials&scope=booking.write inventory.read"
```

这一步是在验证 `user-service` 的授权能力，以及网关到授权服务的路由是否正常。拿到 token 后，才能继续验证网关鉴权、Header 透传和下游服务访问。

### 8.2 调用下单接口

```powershell
curl -X POST http://localhost:8080/api/bookings `
  -H "Authorization: Bearer <token>" `
  -H "Content-Type: application/json" `
  -d '{"channel":"CTRIP","channelOrderNo":"C20260520-00002","userId":1001,"productId":"FLT-CA1234-20260601-Y","quantity":2,"passengerName":"LI SI","passengerIdNo":"E11111111"}'
```

这一步同时会触发多条链路：
- 网关鉴权
- `order-service` 创建订单
- `inventory-service` 扣减库存
- MySQL binlog 变化进入 CDC
- `data-sync-consumer` 更新 ES / Redis / 数仓

所以它是整个项目最重要的端到端验证点。

### 8.3 观察 CDC

检查：
- Kafka 中是否出现对应 topic 消息
- `data-sync-consumer` 日志是否显示同步 ES / Redis / 数仓

如果你需要验证 ES 回填，则再看 `reindex-job` 日志是否有分页批次输出。这样可以把“增量”与“回填”分开判断，避免把问题混在一起。

---

## 9. 常见部署问题

### 9.1 Nacos 连不上

检查：
- `bootstrap.yml` 中的 `server-addr`
- Nacos 容器是否启动
- 账号密码是否正确

这个问题通常发生在“配置中心还没起来，但服务已经尝试拉配置”的阶段，所以先看基础设施，再看业务服务。

### 9.2 Dubbo 调用失败

检查：
- `DUBBO_REGISTRY` 是否正确
- Provider 是否先启动
- Nacos / Zookeeper 中是否可见服务实例

如果是订单服务调用库存失败，优先看 `inventory-service` 是否注册成功；如果是服务发现没问题但调用仍失败，再看超时和接口契约是否一致。

### 9.3 JWT 校验失败

检查：
- `issuer-uri` 是否正确
- 网关是否能访问 `user-service` 的 JWK 地址
- RSA 私钥 / 公钥是否匹配

这个问题通常意味着“授权服务和网关的密钥链路不一致”，要么是 `user-service` 发的 token 不对，要么是网关拿到的公钥不是同一套。

### 9.4 CDC 没有消息

检查：
- MySQL binlog 是否开启
- Debezium Connector 是否注册成功
- Kafka topic 是否存在
- `data-sync-consumer` 是否在消费组中

如果 MySQL 已经有变更但 Kafka 没消息，通常要先回到 `infra/debezium-mysql-connector.json` 和 MySQL binlog 配置本身排查。

### 9.5 ES 没有数据

检查：
- `data-sync-consumer` 是否消费到消息
- `ElasticsearchBootstrap` 是否创建了索引
- `booking_wide_v1` 与 alias 是否正确
- `reindex-job` 是否执行过分页回填

这一步和 `docs/es-layer.md` 是一一对应的：文档里写的“索引版本化、回填分页、健康检查”，在部署时都要逐项验证。

---

## 10. 部署后的回归验证清单

- [ ] MySQL 表和测试数据已生成
- [ ] Nacos 配置已导入
- [ ] `user-service` 已成功启动
- [ ] `gateway-service` 已成功启动
- [ ] `order-service` / `inventory-service` 已成功注册 Dubbo
- [ ] `data-sync-consumer` 已成功消费 CDC
- [ ] 通过网关可以完成下单
- [ ] 数据库库存扣减正确
- [ ] Kafka 中有 CDC 事件
- [ ] ES / Redis / 数仓同步链路正常
- [ ] `reindex-job` 可以独立执行分页回填
- [ ] `booking_wide_v1` 索引存在且可查询
- [ ] 健康检查返回正常
