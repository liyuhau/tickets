
# data-sync-service · 全球旅游产品同业交易平台 Demo

> 整体技术架构（Spring Boot + Nacos + Dubbo + Spring Cloud Gateway + Kafka + Debezium + 统一返回/异常 + **MySQL/MyBatis 持久化**）
> 落地到「全球旅游产品同业交易平台」业务场景，覆盖 **机票 + 酒店** 两类产品。

## 业务定位

同业 B2B 渠道（如 OTA、海外分销商、企业出行平台）通过本平台：
1. **查询** 全球机票座位余量 / 酒店房间余量（带实时价格）
2. **占用** 库存生成预订单（同业渠道单号幂等去重，民航单订单乘机人数上限 9）
3. **同步** 订单 / 产品变更，通过 binlog → Debezium → Kafka 实时落到 ES（搜索）/ Redis（缓存）/ 数仓（BI）

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| `common-core` | - | 统一返回 `R<T>` / 统一异常处理 / 统一线程池管理 / 线程诊断监控 / HttpClient / 鉴权上下文，自动装配 |
| `inventory-api` | - | Dubbo 接口契约：`InventoryFacade` + `StockDTO` + `ProductType` |
| `gateway-service` | 8080 | API 网关，路由 `/api/bookings/**`、`/api/inventory/**`、`/api/users/**` |
| `user-service` | 8081 | 同业账号 |
| `order-service` | 8082 | 预订中心（MySQL `booking` 表 + MyBatis）；Dubbo 调用产品中心 |
| `inventory-service` | 8083 | 产品中心（MySQL `product` 表 + MyBatis + 悲观/乐观锁）；提供 Dubbo Service + HTTP |
| `data-sync-consumer` | 8090 | 订阅 Debezium CDC 主题，落 ES/Redis/数仓 |

## 数据库

**全部业务表 DDL + 索引 + 测试数据**：见 [`docs/database.md`](docs/database.md)

简要：
| 表 | 归属 | 主要字段 |
|---|---|---|
| `product` | inventory-service | product_id(PK) · type · name · travel_date · price_cents · stock · version |
| `booking` | order-service | id(PK) · channel + channel_order_no(UNIQUE 幂等) · product_id · quantity · total_price_cents · status |
| `user` | user-service | id(PK) · name(UNIQUE) · channel · status |

容器首次启动自动执行 `infra/mysql-init/01-schema.sql`，包含建表 + 6 个产品 + 3 个同业账号 + 2 笔预订单。

## 一、最小启动

```powershell
# 1. 启动 MySQL（首启自动建表 + 灌测试数据）+ Nacos + Kafka 链路（含 Zookeeper）
docker compose up -d mysql nacos zookeeper kafka

# 2. 在 Nacos 控制台 http://localhost:8848/nacos (nacos/nacos) 导入 infra/nacos-config/ 下 4 个 yaml

# 3. 启动 4 个 Spring Boot 应用
mvn -pl :inventory-service,:order-service,:user-service,:gateway-service spring-boot:run
```

### 1.1 注册中心可切换（Nacos / Zookeeper）

Dubbo 默认走 **Nacos** 注册中心，也可一键切换到 **Zookeeper**（与 Kafka 共用 ZK 集群）：

```powershell
# 切换到 Zookeeper 注册中心（无需改代码，环境变量即可）
$env:DUBBO_REGISTRY = "zookeeper://127.0.0.1:2181"
mvn -pl :inventory-service spring-boot:run

# 可视化查看 ZK 节点：启动 ZooNavigator（可选）
docker compose --profile zk-ui up -d zoonavigator
# 浏览器打开 http://localhost:9001  → Connection: zookeeper:2181
```

> 配置位置：每个服务的 `application.yaml` → `dubbo.registry.address: ${DUBBO_REGISTRY:nacos://...}`，
> 不传环境变量则默认 Nacos。

## 二、调用示例（统一返回 R<T>）

```bash
# 查询机票余量
curl http://localhost:8080/api/inventory/FLT-CA1234-20260601-Y

# 创建预订单（携程 2 张机票）
curl -X POST http://localhost:8080/api/bookings -H "Content-Type: application/json" `
  -d '{"channel":"CTRIP","channelOrderNo":"C20260520-00002","userId":1001,"productId":"FLT-CA1234-20260601-Y","quantity":2,"passengerName":"LI SI","passengerIdNo":"E11111111"}'

# 同一渠道单号再发一次 → 幂等命中，返回原单
curl -X POST http://localhost:8080/api/bookings -H "Content-Type: application/json" `
  -d '{"channel":"CTRIP","channelOrderNo":"C20260520-00002",...}'

# 验证 MySQL：库存从 30 → 28，新增一条 booking
docker exec -it mysql mysql -uroot -proot bizdb -e "SELECT product_id, stock FROM product WHERE product_id='FLT-CA1234-20260601-Y'; SELECT id, channel, channel_order_no, status FROM booking;"
```

## 三、完整 CDC 链路（订单变更 → ES/Redis/数仓）

```powershell
docker compose up -d
powershell -File infra/register-debezium-connector.ps1
mvn -pl data-sync-consumer -am spring-boot:run
```

Debezium 已配置捕获 `bizdb.user / bizdb.product / bizdb.booking` 三张表，
任何业务写入都会通过 binlog → Kafka → consumer 同步到 ES / Redis / 数仓。

```
[CDC] table=booking op=c after={"id":3,"channel":"CTRIP",...}
[ES]    upsert booking/3
[Redis] evict booking:3
[DW]    ods_booking op=c ...
```

## 四、统一返回 / 异常

`common-core` 模块 + `@RestControllerAdvice` 全局兜底：

| code 段 | 含义 |
|---|---|
| 0 | 成功 |
| 1xxx | 通用错误（参数、签名） |
| 2xxx | 业务错误（余量不足、产品不存在、订单不存在） |
| 2999 | 下游 RPC 调用失败 |
| 5xxx | 服务内部错误 |

## 六、鉴权（OAuth2，标准 Authorization Server + Resource Server）

**总体方案：**
```
┌──────────────┐    ① POST /api/oauth2/token (client_credentials)
│ 同业渠道客户端 │ ─────────────────────────────────────────┐
└──────────────┘                                          │
                                                          ▼
                                            ┌────────────────────────┐
                                            │ user-service (AS:8081) │
                                            │ Spring Authorization   │
                                            │ Server 1.2             │
                                            │   /oauth2/token        │
                                            │   /oauth2/jwks         │
                                            └──────────┬─────────────┘
                                                       │ ② 返回 RSA 签名 JWT
                                                       ▼
                                              { access_token, expires_in, scope }
┌──────────────┐    ③ 业务请求 + Authorization: Bearer <jwt>
│ 同业渠道客户端 │ ──────────────────────────►   Gateway (RS:8080)
└──────────────┘                              │
                                              │  Spring Security WebFlux
                                              │   Resource Server 自动从
                                              │   issuer-uri 拉 JWK 验签
                                              │
                                              │  验签通过 → IdentityPropagationFilter
                                              │    把 sub / channel / name 写入
                                              │    X-User-* Header 转发下游
                                              │  失败 → 401 { code:1401, ... }
                                              ▼
                            order-service / inventory-service
                                AuthContextFilter 还原 ThreadLocal
                                Controller 用 AuthContext.current()
```

### 6.1 注册的同业渠道客户端（演示）

| client_id | client_secret | grant_type | scope | userId | channel |
|---|---|---|---|---|---|
| `ctrip-channel` | `ctrip-secret` | client_credentials | booking.read / booking.write / inventory.read | 1001 | CTRIP |
| `fliggy-channel` | `fliggy-secret` | client_credentials | 同上 | 1002 | FLIGGY |
| `expedia-channel` | `expedia-secret` | client_credentials | 同上 | 1003 | EXPEDIA |

> 客户端密钥用 BCrypt 加密存储；生产应改为 MySQL `RegisteredClientRepository` 实现 + KMS 托管 RSA 私钥。

### 6.2 拿 access_token（client_credentials）

通过网关（推荐）：
```bash
curl -X POST http://localhost:8080/api/oauth2/token `
  -u "ctrip-channel:ctrip-secret" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  -d "grant_type=client_credentials&scope=booking.write inventory.read"
```
直接打 AS：
```bash
curl -X POST http://localhost:8081/oauth2/token `
  -u "ctrip-channel:ctrip-secret" `
  -d "grant_type=client_credentials&scope=booking.write"
```
返回：
```json
{
  "access_token": "eyJraWQiOiI...<RSA 签名的 JWT>...",
  "scope": "booking.write inventory.read",
  "token_type": "Bearer",
  "expires_in": 299
}
```

解码 JWT payload（仅展示关键字段）：
```json
{
  "sub": "1001",
  "channel": "CTRIP",
  "name": "CTRIP-TRAVEL-AGENCY-A",
  "userId": 1001,
  "iss": "http://127.0.0.1:8081",
  "aud": ["ctrip-channel"],
  "scope": ["booking.write","inventory.read"],
  "exp": 1747...
}
```

### 6.3 携带 token 调业务接口

```bash
$token = (curl -s -X POST http://localhost:8080/api/oauth2/token `
            -u "ctrip-channel:ctrip-secret" `
            -d "grant_type=client_credentials" | ConvertFrom-Json).access_token

curl -X POST http://localhost:8080/api/bookings `
  -H "Authorization: Bearer $token" -H "Content-Type: application/json" `
  -d '{"channelOrderNo":"C20260520-00010","productId":"FLT-CA1234-20260601-Y","quantity":2,"passengerName":"LI SI","passengerIdNo":"E11111111"}'
```
> `userId` / `channel` 由 token 透传，请求体里的值会被服务端忽略，杜绝越权。

### 6.4 关键端点

| 端点 | 用途 | 谁调用 |
|---|---|---|
| `POST {issuer}/oauth2/token` | 颁发 token | 同业客户端 |
| `GET  {issuer}/oauth2/jwks` | 公钥（验签用） | 网关启动时自动拉 |
| `GET  {issuer}/.well-known/oauth-authorization-server` | 元数据 | 网关自动发现 |

### 6.5 关键配置（Nacos `common-shared.yaml`）

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OAUTH2_ISSUER:http://127.0.0.1:8081}
oauth2:
  issuer: ${OAUTH2_ISSUER:http://127.0.0.1:8081}
```
AS 与 RS 的 `issuer-uri` **必须完全一致**，否则验签会拒绝（JWT 的 `iss` 不匹配）。

### 6.6 后续增强
1. **authorization_code + PKCE** —— 接入前端 UI 登录
2. **持久化客户端**：`JdbcRegisteredClientRepository` 改用 MySQL 表 `oauth2_registered_client`
3. **RSA 密钥外部托管**：KMS / HashiCorp Vault；启动从外部加载，避免重启换 key 导致存量 token 失效
4. **scope → 权限映射**：网关或下游加 `@PreAuthorize("hasAuthority('SCOPE_booking.write')")`
5. **接入外部 IdP**（Keycloak / Auth0 / Azure AD）：只需把 `issuer-uri` 指向外部 IdP，Gateway 配置无需任何代码改动

### 6.7 刷新 Token（refresh_token grant）

> Spring Authorization Server 1.2 内置 `refresh_token` 支持。本项目对 `ctrip-channel` / `fliggy-channel` 已开启该 grant；`expedia-channel` 仅 `client_credentials`。

**TTL & 旋转策略（`oauth2.clients[i]` 配置）：**
| 字段 | 含义 | 默认 |
|---|---|---|
| `access-token-ttl` | access_token 有效期（ISO-8601 Duration） | 30 分钟 |
| `refresh-token-ttl` | refresh_token 有效期 | 7 天 |
| `reuse-refresh-tokens` | `false`=每次刷新返回**新** refresh_token（推荐，防重放） | `false` |

**典型流程**（注意：仅 `client_credentials` grant 不会返回 refresh_token；需要先走 `authorization_code` 才有 refresh_token）：

```bash
# ① 用 authorization_code 拿到 refresh_token（前端 UI 引导用户跳转 /oauth2/authorize 略）
# 或者直接看 spring-authorization-server 文档；本项目 ctrip-channel 已配置 redirect-uri：
#   http://localhost:9000/login/oauth2/code/ctrip

# ② 用 refresh_token 换新 access_token + 新 refresh_token（旋转）
curl -X POST http://localhost:8080/api/oauth2/token `
  -u "ctrip-channel:ctrip-secret" `
  -d "grant_type=refresh_token&refresh_token=<原 refresh_token>"
```
返回：
```json
{
  "access_token":  "eyJraWQ...新的JWT...",
  "refresh_token": "新旋转的 refresh_token",   // reuseRefreshTokens=false 才有
  "token_type":    "Bearer",
  "expires_in":    1800,
  "scope":         "booking.write inventory.read"
}
```

**为什么 `client_credentials` 不发 refresh_token？**
OAuth2 RFC 6749 §4.4.3 规定：服务端到服务端的 `client_credentials` 流程**不应**返回 refresh_token——客户端可以用 client_secret 直接重新申请。本项目对它仅设置较短 access_token TTL（如 expedia-channel 15 分钟），到期后自动重新拿。

### 6.8 令牌吊销（/oauth2/revoke）

> 立即吊销一个 access_token 或 refresh_token；常见场景：渠道密钥泄露、用户登出、风控触发强制下线。

Spring Authorization Server 自动暴露 RFC 7009 端点：

```bash
# 吊销 refresh_token（推荐：连带其 access_token 一并失效）
curl -X POST http://localhost:8080/api/oauth2/revoke `
  -u "ctrip-channel:ctrip-secret" `
  -d "token=<refresh_token>&token_type_hint=refresh_token"

# 吊销 access_token（只让该 access_token 失效，refresh_token 还能换）
curl -X POST http://localhost:8080/api/oauth2/revoke `
  -u "ctrip-channel:ctrip-secret" `
  -d "token=<access_token>&token_type_hint=access_token"
```

成功返回 HTTP 200（无 body）。再用被吊销的 token 请求业务接口将得到 `401 {"code":1401,"message":"未登录或 token 无效"}`。

**注意 ⚠️：JWT 无状态校验的局限**
- AS 侧 `OAuth2AuthorizationService` 把吊销记录写入"已吊销列表"
- 但**网关（Resource Server）侧默认只验签 + exp**，**不会**回 AS 查吊销状态
- 如需「吊销立刻全量生效」，需要二选���：
  1. **改用 opaque token + introspection**（每次请求查 AS 的 `/oauth2/introspect`，性能换实时性）
  2. **保留 JWT，加短 TTL（如 5 分钟）**，最长容忍延迟 = TTL（本项目方案）
- 生产建议：access_token TTL **5–15 分钟**，refresh_token TTL **天级**，吊销主要靠 refresh_token 失效切断长期访问

#### 6.8.1 让吊销「立刻生效」—— 已经实现 ✅

本项目把方案 1 也实现了，由开关控制：

| 组件 | 作用 |
|---|---|
| `user-service/OAuth2PersistenceConfig` | `JdbcOAuth2AuthorizationService` 把所有授权 / 吊销记录持久化到 MySQL 表 `oauth2_authorization`，AS 重启不丢 |
| `infra/mysql-init/02-oauth2-schema.sql` | 自动建 3 张官方表（authorization / consent / registered_client） |
| `gateway-service/JwtRevocationFilter` | 全局过滤器，请求带 Bearer 时查 AS 的 `/oauth2/introspect`，结果用 **Caffeine 缓存**默认 10 秒 |

开关 + 参数（`gateway-service/application.yaml`）：
```yaml
security:
  introspect:
    enabled:        ${INTROSPECT_ENABLED:false}     # true=启用立刻吊销
    cache-seconds:  ${INTROSPECT_CACHE_SECONDS:10}  # 缓存秒数，0=每次实时
    cache-max-size: 50000                            # token 数量上限
    client-id:      ctrip-channel                    # 调 introspect 的客户端凭证
    client-secret:  ${INTROSPECT_CLIENT_SECRET:ctrip-secret}
```

**性能/实时性权衡：**
| 模式 | 吊销延迟 | AS 压力 | 适用 |
|---|---|---|---|
| `enabled=false`（默认） | ≤ access_token TTL | 0 | 一般业务 |
| `enabled=true, cache=10s` | ≤ 10 秒 | 业务 QPS × (1 - 命中率)，通常 < 5% | 推荐 |
| `enabled=true, cache=0` | 实时 | = 业务 QPS | 高安全场景（支付、风控） |

**启用流程：**
```powershell
# 1. AS 重启自动建表（docker-entrypoint 执行 02-oauth2-schema.sql）
docker compose up -d mysql

# 2. 启动 user-service 后，调 /oauth2/token 拿到的 token 会落库

# 3. 网关开启 introspect 校验
$env:INTROSPECT_ENABLED = "true"
mvn -pl :gateway-service spring-boot:run

# 4. 吊销 token，10 秒内所有网络节点全部失效
curl -X POST http://localhost:8080/api/oauth2/revoke `
  -u "ctrip-channel:ctrip-secret" `
  -d "token=<refresh_token>&token_type_hint=refresh_token"
```

**故障容忍：** AS 不可达时，`JwtRevocationFilter` **fail-open**（放行，仅依赖本地 JWT 验签），避免 AS 故障打爆整个网关；如需严格安全可改 fail-close（拒绝所有请求）。

### 6.9 令牌内省（/oauth2/introspect）

调试时可用：
```bash
curl -X POST http://localhost:8080/api/oauth2/introspect `
  -u "ctrip-channel:ctrip-secret" `
  -d "token=<access_token>"
```
返回：
```json
{ "active": true, "sub":"1001", "scope":"booking.write", "exp":..., "iss":"http://127.0.0.1:8081" }
```
被吊销/过期的 token 返回 `{"active": false}`。

### 6.10 RSA 签名密钥外部托管（KMS / Vault / K8s Secret）

> 默认实现每次启动随机生成 RSA → AS 重启所有 JWT 立刻失效。生产**必须**外部托管。

**代码**：`user-service/JwkSourceConfig` + `OAuth2KeyProperties`，支持 3 种来源。

| `oauth2.key.source` | 私钥载体 | 适用 |
|---|---|---|
| `random` | 启动随机生成 | 仅本地开发（默认） |
| `keystore` | PKCS12 / JKS 文件 + 密码 | **生产推荐**，跨平台 |
| `pem` | PKCS8 私钥 + X.509 公钥 PEM | cert-manager / Let's Encrypt 风格 |

**生产部署模式**（应用代码只识别"文件"，不绑定具体云厂商）：

```
┌─────────────────────┐
│ AWS KMS / GCP KMS / │
│ HashiCorp Vault /   │  ← 真正的 secret 源（私钥永不进 Git）
│ K8s External Secrets│
└──────────┬──────────┘
           │ ① decrypt / fetch
           ▼
┌─────────────────────┐
│  Vault Agent /      │  ← Sidecar 把 secret 投递成文件
│  ESO / kms-sidecar  │
└──────────┬──────────┘
           │ ② mount to tmpfs (/etc/oauth2/jwt-signing.p12)
           ▼
┌─────────────────────┐
│   user-service      │  ← 启动按 oauth2.key.keystore 加载
└─────────────────────┘
```

**生成密钥（演示）**：

```powershell
# 1. keytool 生�� PKCS12（生产用 KMS / HSM 生成，导出公钥即可）
keytool -genkeypair -alias jwt-signing -keyalg RSA -keysize 2048 `
  -storetype PKCS12 -keystore jwt-signing.p12 -storepass changeit `
  -dname "CN=oauth2,OU=demo,O=mycorp,L=BJ,S=BJ,C=CN" -validity 3650

# 2. 启用 keystore 模式
$env:OAUTH2_KEY_SOURCE        = "keystore"
$env:OAUTH2_KEYSTORE_LOCATION = "file:///D:/path/to/jwt-signing.p12"
$env:OAUTH2_KEYSTORE_PASSWORD = "changeit"
$env:OAUTH2_KEYSTORE_ALIAS    = "jwt-signing"
$env:OAUTH2_KEY_ID            = "jwt-2026-q2"   # 把日期写进 kid，便于轮换观察
mvn -pl :user-service spring-boot:run
```

启动日志会打：
```
[OAuth2] JWT signing key loaded: source=KEYSTORE, kid=jwt-2026-q2
```

**密钥轮换**（不停机）：
1. 新生成 `kid=jwt-2026-q3` 的密钥，覆盖文件
2. 滚动重启 AS 节点（金丝雀 → 全量）
3. 网关侧 OAuth2 RS 默认会拉新版 JWK Set 并缓存两份 kid，**旧 token 在 exp 内仍能验签**
4. exp 之后旧 token 自然失效

> **真·HSM 级别**（私钥永不出 KMS）：需替换 `JwtEncoder` 为 KMS-backed 签名器，每次签名调远程 API。属金融/医疗合规场景，本 demo 不展开。

### 6.11 OAuth2 客户端持久化（MySQL）

> 已替换 `InMemoryRegisteredClientRepository` → **`JdbcRegisteredClientRepository`**，3 个客户端 yaml/Nacos 配置启动自动 upsert 到 `oauth2_registered_client` 表。

| 维度 | 行为 |
|---|---|
| **数据源** | `oauth2.clients[]` yaml/Nacos 仍是单一事实源 |
| **AS 启动** | 按 `clientId` upsert（已存在复用 internal id 走 UPDATE） |
| **运维改 DB** | 直接 `UPDATE oauth2_registered_client SET ...` **立刻热生效**（除非命中 introspect Caffeine 缓存） |
| **AS 重启** | 重启会用 yaml 覆盖 DB 临时改动，保持 IaC 一致 |

**查 DB 确认 3 个客户端已落库：**
```bash
docker exec -it mysql mysql -uroot -proot -e \
  "USE bizdb; SELECT client_id, scopes, authorization_grant_types FROM oauth2_registered_client;"
```

**临时改 ctrip-channel 的 scope（不重启 AS）：**
```bash
docker exec -it mysql mysql -uroot -proot -e \
  "USE bizdb; UPDATE oauth2_registered_client \
   SET scopes='booking.read' WHERE client_id='ctrip-channel';"
# 下次 /oauth2/token 立刻只能拿到 booking.read
```

三张 OAuth2 表的协作：
```
yaml/Nacos (oauth2.clients[])         运维 / 控制台
       │                                    │
       ▼ AS 启动 upsert                       ▼
┌─────────────────────────────┐    ┌──────────────────────────┐
│ oauth2_registered_client    │    │ oauth2_authorization     │
│  (谁能拿 token)              │    │  (颁了哪些 token / 吊销)  │
└─────────────────────────────┘    └──────────────────────────┘
              ▲                                ▲
              │ JdbcRegisteredClientRepository │ JdbcOAuth2AuthorizationService
              │                                │
       ┌──────┴───────────────────────────────┴──────┐
       │       Spring Authorization Server           │
       └─────────────────────────────────────────────┘
                            ▲
                            │ POST /oauth2/token | /oauth2/revoke | /oauth2/introspect
                       客户端 / 网关
```

## 七、目录结构
```
data-sync-service/
├── docs/
│   └── database.md            ← 数据库设计文档（DDL + 索引 + 测试数据）
├── docker-compose.yml         ← MySQL/Kafka/Debezium/Nacos/ES/Redis
├── infra/
│   ├── mysql-init/01-schema.sql   ← 容器自动执行，与文档完全一致
│   ├── nacos-config/              ← 待导入 Nacos 的配置模板
│   └── debezium-mysql-connector.json
├── common-core/               统一返回/异常 + 自动装配
├── inventory-api/             Dubbo 接口契约
├── gateway-service/           API 网关
├── user-service/              同业账号
├── order-service/             预订中心（MyBatis + MySQL booking 表，含 BookingMapper.xml）
├── inventory-service/         产品中心（MyBatis + MySQL product 表，含 ProductMapper.xml）
└── data-sync-consumer/        Kafka 消费 → ES/Redis/数仓
```
