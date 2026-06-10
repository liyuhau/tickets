# 全球旅游产品同业交易平台 — 架构全链路解析

> 本文档从代码层面、逻辑层面、组件关联方式三个维度，完整解释本系统的核心技术架构。

---

## 目录

- [一、系统全景图](#一系统全景图)
- [二、OAuth2 认证体系](#二oauth2-认证体系)
- [三、用户信息传递：网关 → Header → ThreadLocal → 静态 get](#三用户信息传递网关--header--threadlocal--静态-get)
- [四、Nacos 注册中心 + 配置中心](#四nacos-注册中心--配置中心)
- [五、Dubbo RPC 服务调用](#五dubbo-rpc-服务调用)
- [六、ZooKeeper 使用](#六zookeeper-使用)
- [七、CDC + Kafka + 数仓 数据同步](#七cdc--kafka--数仓-数据同步)
- [八、各组件关联方式总表](#八各组件关联方式总表)
- [九、统一线程池管理](#九统一线程池管理)
- [十、线程诊断与监控](#十线程诊断与监控)

---

## 一、系统全景图

```
                  ┌──────────────┐
                  │   Client     │
                  │  前端 / APP   │
                  └──────┬───────┘
                         │ HTTP (Bearer Token)
                         ▼
                ┌──────────────────┐
                │   API Gateway    │  ← JWT验签 + 吊销检查 + Header注入 + Sentinel限流
                │  (gateway-service)│
                └──────┬───────────┘
                       │
      ┌────────────────┼────────────────┐
      ▼                                 ▼
┌──────────────┐                 ┌──────────────┐
│ Order Service │                 │ User Service │
│ 订单服务       │                 │ (AS 授权服务)  │
└──────┬────────┘                 └──────────────┘
       │  Dubbo RPC
       ▼
┌──────────────┐
│ Inventory    │
│ 产品库存服务   │
└──────┬────────┘
       │ MySQL写入
       ▼
┌──────────────┐
│   MySQL       │ ← binlog (ROW格式)
└──────┬────────┘
       │
       ▼
┌──────────────┐
│  Debezium    │ ← CDC引擎，伪装MySQL Slave
└──────┬────────┘
       ▼
┌──────────────┐
│   Kafka       │ ← 消息缓冲 + 持久化
└──────┬────────┘
       │
       ▼
┌──────────────────┐
│ data-sync-consumer│ ← 消费CDC消息，分发到下游
└──────┬───────────┘
       │
   ┌───┼───────────┐
   ▼   ▼           ▼
  ES  Redis      数仓(BI)
```

---

## 二、OAuth2 认证体系

### 2.1 整体流程

```
① Client → POST /api/oauth2/token (Basic Auth: client_id:client_secret)
② Gateway 白名单放行 → 转发到 user-service (AS)
③ AS 验证 client_id + client_secret → 签发 JWT（RSA私钥签名）
④ JWT claims 里塞入 channel / userId / name
⑤ 返回 access_token 给 Client
```

### 2.2 AS 端关键代码

#### `AuthorizationServerConfig.java`

```java
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(OAuth2ClientsProperties.class)
public class AuthorizationServerConfig {

    // 1. AS 安全过滤链（注册 /oauth2/token 等标准端点）
    @Bean @Order(1)
    public SecurityFilterChain asSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        return http.formLogin(Customizer.withDefaults()).build();
    }

    // 2. 客户端仓储（yaml 配置 → 启动时 upsert 到 DB）
    @Bean
    public RegisteredClientRepository registeredClientRepository(
            JdbcTemplate jdbcTemplate, PasswordEncoder encoder, OAuth2ClientsProperties props) {
        JdbcRegisteredClientRepository repo = new JdbcRegisteredClientRepository(jdbcTemplate);
        for (ClientDef c : props.getClients()) {
            // 按 clientId 幂等 upsert
            repo.save(buildRegisteredClient(c, encoder));
        }
        return repo;
    }

    // 3. JWT 自定义声明（把 channel/userId/name 写进 token）
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(OAuth2ClientsProperties props) {
        return ctx -> {
            if (!ACCESS_TOKEN.equals(ctx.getTokenType())) return;
            ClientDef c = byId.get(ctx.getRegisteredClient().getClientId());
            ctx.getClaims().claims(claims -> {
                claims.put("channel", c.getChannel());   // "CTRIP"
                claims.put("userId",  c.getUserId());    // 1001
                claims.put("name",    c.getName());      // "CTRIP-TRAVEL-AGENCY-A"
            });
            ctx.getClaims().subject(String.valueOf(c.getUserId()));
        };
    }
}
```

#### `@ConfigurationProperties(prefix = "oauth2")`

作用：把 yaml/Nacos 中 `oauth2.clients[*]` 的配置**自动映射**到 Java 对象。

```yaml
oauth2:
  clients:
    - client-id: ctrip-channel
      client-secret: ${CTRIP_SECRET:ctrip-secret}
      channel: CTRIP
      user-id: 1001
      name: CTRIP-TRAVEL-AGENCY-A
      scopes: [booking.read, booking.write]
      grant-types: [client_credentials, refresh_token]
      access-token-ttl: PT30M       # ISO-8601: 30分钟
      refresh-token-ttl: P7D        # 7天
```

Spring Boot 自动绑定规则：
- `client-id`（kebab-case）→ `clientId`（camelCase）
- `PT30M` → `java.time.Duration`（内置 Converter）
- `${CTRIP_SECRET:ctrip-secret}` → 取环境变量，没有用默认值

#### `JdbcRegisteredClientRepository`

这是 Spring Authorization Server 提供的**基于数据库的客户端仓储**实现：
- 对应数据库表 `oauth2_registered_client`
- 存储 client_id、加密后的 client_secret、允许的 scope/grant_type 等
- AS 每次验证 `/oauth2/token` 请求时，从这个表查客户端信息

### 2.3 JWT 结构

签发后的 JWT 解码：

```json
{
  "header": { "alg": "RS256", "kid": "xxx" },
  "payload": {
    "sub": "1001",
    "iss": "http://127.0.0.1:8081",
    "client_id": "ctrip-channel",
    "scope": ["booking.read", "booking.write"],
    "channel": "CTRIP",
    "userId": 1001,
    "name": "CTRIP-TRAVEL-AGENCY-A",
    "exp": 1748600000,
    "iat": 1748598200
  },
  "signature": "RSA256签名..."
}
```

---

## 三、用户信息传递：网关 → Header → ThreadLocal → 静态 get

### 3.1 为什么用这种方式（企业主流做法）

| 优势 | 说明 |
|---|---|
| 业务解耦 | 下游服务不需要引入 Spring Security，不需要解析 JWT |
| 跨语言兼容 | Header 是协议级，下游可以是 Go/Python/Node |
| 职责单一 | 网关做认证，业务服务做业务 |
| 性能 | 下游不再验签，省 CPU |
| 可观测 | Header 在 Nginx/SkyWalking 日志里直接可见 |

### 3.2 全链路四步

```
步骤1: 网关 JWT 验签
    GatewaySecurityConfig → oauth2ResourceServer().jwt()
    Spring Security 从 AS 的 /oauth2/jwks 拉公钥验签

步骤2: 网关注入 Header
    IdentityPropagationFilter → ReactiveSecurityContextHolder 取 JWT
    → exchange.getRequest().mutate()
        .header("X-User-Id", jwt.getSubject())
        .header("X-User-Channel", jwt.getClaim("channel"))
        .header("X-User-Name", jwt.getClaim("name"))

步骤3: 下游 Filter 写 ThreadLocal
    AuthContextFilter.doFilterInternal()
    → req.getHeader("X-User-Id") → AuthContext.set(new AuthContext(...))
    → MDC.put("userId", uid)    // 日志自动带 userId
    → finally: AuthContext.clear() + MDC.remove()

步骤4: 业务代码直接 get
    AuthContext.current().getUserId()   // 一行搞定
    AuthContext.current().getChannel()
    AuthContext.current().getName()
```

### 3.3 `AuthContext.java` — ThreadLocal 容器

```java
public final class AuthContext {
    public static final String HEADER_USER_ID      = "X-User-Id";
    public static final String HEADER_USER_CHANNEL = "X-User-Channel";
    public static final String HEADER_USER_NAME    = "X-User-Name";

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final String channel;
    private final String name;

    public static void set(AuthContext ctx) { HOLDER.set(ctx); }
    public static AuthContext current()     { return HOLDER.get(); }
    public static void clear()              { HOLDER.remove(); }
}
```

**ThreadLocal 原理**：挂在每个 JVM `Thread` 对象上的一个 Map。同线程可见、跨线程不可见、线程池必须 clear。

### 3.4 `AuthContextFilter.java` — Header → ThreadLocal

```java
public class AuthContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String uid = req.getHeader(AuthContext.HEADER_USER_ID);
        if (uid != null && !uid.isBlank()) {
            try {
                AuthContext.set(new AuthContext(
                        Long.valueOf(uid),
                        req.getHeader(AuthContext.HEADER_USER_CHANNEL),
                        req.getHeader(AuthContext.HEADER_USER_NAME)));
                MDC.put("userId", uid);
            } catch (NumberFormatException ignored) {}
        }
        try {
            chain.doFilter(req, res);
        } finally {
            AuthContext.clear();
            MDC.remove("userId");
        }
    }
}
```

**为什么继承 `OncePerRequestFilter`？**
- 保证一次 HTTP 请求只执行一次（forward/error dispatch 不会重复触发）
- 防止 ThreadLocal 被提前 clear
- 参数直接是 `HttpServletRequest`（不需要手动强转）

**为什么 `finally` 必须 `clear()`？**
- Tomcat 线程池会复用线程
- 不清理 → 下一个请求会拿到上个用户的信息 → **用户串号安全事故**

**MDC 是什么？**
- `Mapped Diagnostic Context`，SLF4J 提供的基于 ThreadLocal 的日志上下文
- `MDC.put("userId", uid)` 后，logback pattern 中 `%X{userId}` 自动输出该值
- 所有日志自动带 userId，排查问题时在 ELK 按 userId 过滤即可

### 3.5 Dubbo RPC 透传 — `DubboAuthContextFilter.java`

HTTP Header 不会自动传到 Dubbo RPC，需要 SPI Filter：

```java
@Activate(group = {CONSUMER, PROVIDER}, order = -10000)
public class DubboAuthContextFilter implements Filter {
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        if (isConsumer) {
            // Consumer 侧：ThreadLocal → Dubbo Attachment
            AuthContext ctx = AuthContext.current();
            invocation.setAttachment("x-user-id", String.valueOf(ctx.getUserId()));
            invocation.setAttachment("x-user-channel", ctx.getChannel());
            invocation.setAttachment("x-user-name", ctx.getName());
            return invoker.invoke(invocation);
        }
        // Provider 侧：Dubbo Attachment → ThreadLocal
        String uid = invocation.getAttachment("x-user-id");
        AuthContext.set(new AuthContext(Long.valueOf(uid), ...));
        MDC.put("userId", uid);
        try { return invoker.invoke(invocation); }
        finally { AuthContext.clear(); MDC.remove("userId"); }
    }
}
```

SPI 注册文件 `META-INF/dubbo/org.apache.dubbo.rpc.Filter`：
```
dubboAuthContext=org.common.auth.DubboAuthContextFilter
```

### 3.6 网关三道关卡

| 顺序 | Filter | order | 作用 |
|---|---|---|---|
| 1 | Spring Security JWT Filter | -100 | 验签 + 验 exp + 验 issuer |
| 2 | `JwtRevocationFilter` | -50 | 调 AS introspect 检查 token 是否被吊销 |
| 3 | `IdentityPropagationFilter` | -50 | 从 SecurityContext 提取 JWT claims → 注入 X-User-* Header |

### 3.7 Token 吊销机制

```java
// JwtRevocationFilter
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String token = extractBearer(exchange.getRequest());
    Boolean cached = activeCache.getIfPresent(token);     // Caffeine 本地缓存
    if (cached != null) return cached ? chain.filter(exchange) : reject(exchange);
    return introspect(token)                               // POST /oauth2/introspect → AS
        .flatMap(active -> {
            activeCache.put(token, active);                 // 缓存结果 10 秒
            return active ? chain.filter(exchange) : reject(exchange);
        });
}
```

---

## 四、Nacos 注册中心 + 配置中心

### 4.1 Nacos 在系统中的双重角色

```
                            Nacos Server (127.0.0.1:8848)
                           ┌─────────────────────────────────┐
                           │  注册中心 (Discovery)             │
                           │    gateway-service → 8080        │
                           │    order-service → 8082          │
                           │    inventory-service → 8083      │
                           │    user-service → 8081           │
                           ├─────────────────────────────────┤
                           │  配置中心 (Config)                │
                           │    common-shared.yaml            │
                           │    gateway-service.yaml          │
                           │    order-service.yaml            │
                           │    inventory-service.yaml        │
                           └─────────────────────────────────┘
```

### 4.2 `bootstrap.yml` — 为什么需要它

```
Spring Boot 启动顺序：
① bootstrap.yml 加载（最先）         ← 知道 Nacos 在哪
② 连接 Nacos Config，拉取远程配置     ← 从 Nacos 拿到业务配置
③ application.yml 加载
④ 合并所有配置 → 创建 Spring 容器
```

**必须最先加载**：不先知道 Nacos 地址，就无法拉配置。

### 4.3 配置拉取规则

以 `order-service` 为例：

```yaml
# bootstrap.yml
spring:
  application:
    name: order-service              # ← 决定自动拉取的 dataId
  cloud:
    nacos:
      config:
        file-extension: yaml         # ← 后缀
        shared-configs:
          - data-id: common-shared.yaml   # ← 显式共享配置
```

自动拉取的 dataId 列表：
1. `order-service.yaml`（`${app-name}.${file-extension}`）
2. `order-service-dev.yaml`（`${app-name}-${profile}.${file-extension}`）
3. `common-shared.yaml`（shared-configs 显式声明）

优先级：① > ② > ③ > 本地 application.yaml > bootstrap.yml

### 4.4 动态刷新 — `@RefreshScope`

```java
@Component
@RefreshScope    // ← 配置变更时 Bean 自动重建
public class OrderDynamicProperties {
    @Value("${order.max-quantity-per-request:100}")
    private int maxQuantityPerRequest;
}
```

Nacos 控制台改 `order-service.yaml` 里的值 → gRPC 推送变更 → Spring 发布 `RefreshEvent` → `@RefreshScope` Bean 销毁重建 → 字段重新注入 → **无需重启服务**。

### 4.5 服务发现 — 网关路由

```yaml
# gateway application.yaml
spring.cloud.gateway.routes:
  - uri: lb://order-service          # ← lb:// = LoadBalancer 协议
    predicates:
      - Path=/api/bookings/**
```

当请求到来时：
1. 网关调 `NamingService.selectInstances("order-service")`
2. Nacos 返回实例列表：`[10.0.0.2:8082, 10.0.0.5:8082]`
3. Spring Cloud LoadBalancer 轮询选一个
4. 转发请求

### 4.6 Nacos 配置文件清单

| Data ID | 作用 | 谁拉取 |
|---|---|---|
| `common-shared.yaml` | 日志级别、OAuth2 issuer-uri、Actuator 端点 | 所有服务 |
| `order-service.yaml` | 单订单上限、风控开关 | order-service |
| `inventory-service.yaml` | 库存告警阈值、默认币种 | inventory-service |
| `gateway-service.yaml` | 网关路由规则（动态路由，改了不用重启） | gateway-service |

---

## 五、Dubbo RPC 服务调用

### 5.1 模块分工

```
inventory-api (纯接口模块 — "合同")
├── InventoryFacade.java        ← 接口契约
├── StockDTO.java               ← 传输对象 (必须 Serializable)
├── InventoryRpcException.java  ← 跨进程异常
├── InventoryRpc.java           ← 常量 (VERSION)
└── ProductType.java            ← 枚举

order-service (Consumer 消费方)
└── @DubboReference InventoryFacade → Dubbo 生成代理对象

inventory-service (Provider 提供方)
└── @DubboService InventoryFacadeImpl → 真实实现
```

### 5.2 Provider 配置（inventory-service）

```yaml
dubbo:
  application:
    name: inventory-service
  registry:
    address: ${DUBBO_REGISTRY:nacos://127.0.0.1:8848}   # 注册到 Nacos
  protocol:
    name: dubbo          # TCP 长连接协议
    port: -1             # 自动分配端口
  provider:
    timeout: 3000        # 执行超时 3s
  scan:
    base-packages: org.inventory.service   # 扫描 @DubboService
```

### 5.3 Consumer 配置（order-service）

```yaml
dubbo:
  application:
    name: order-service
  registry:
    address: ${DUBBO_REGISTRY:nacos://127.0.0.1:8848}   # 同一个 Nacos
  consumer:
    timeout: 3000        # 等待响应最多 3s
    check: false         # 启动时不检查 Provider（允许后启动）
    retries: 0           # 不重试（deduct 非幂等）
```

### 5.4 运行时调用流程

```
OrderController.create()
    │
    ▼ inventoryClient.deduct("p001", 2)
    │
    ▼ DubboAuthContextFilter (Consumer): ThreadLocal → Attachment
    │
    ▼ Dubbo Proxy 序列化：接口名 + 方法名 + 参数 + attachment
    │
    │ ──── TCP ────►
    │
    ▼ DubboAuthContextFilter (Provider): Attachment → ThreadLocal
    │
    ▼ InventoryFacadeImpl.deduct("p001", 2) 执行
    │   AuthContext.current().getUserId() = 1001  ← 可用！
    │
    ▼ 返回 StockDTO → 序列化 → TCP → 反序列化 → 给 OrderController
```

### 5.5 Sentinel 熔断保护

```java
@SentinelResource(value = "inventory.deduct", blockHandler = "deductBlocked")
public StockDTO deduct(String productId, Integer qty) {
    return inventoryFacade.deduct(productId, qty);   // 正常走远程调用
}

public StockDTO deductBlocked(String productId, Integer qty, BlockException ex) {
    throw new BusinessException(TOO_MANY_REQUESTS, "下单流量已达上限");  // 限流时快速失败
}
```

### 5.6 异常传递链路

```
Provider: throw InventoryRpcException(1502, "余量不足")
    ↓ Dubbo 序列化通过 TCP 传回
Consumer: catch (InventoryRpcException e)
    ↓ 转换
    throw new BusinessException(e.getCode(), e.getMessage())
    ↓ 冒泡到 Controller
GlobalExceptionHandler → {"code": 1502, "message": "余量不足"}
```

---

## 六、ZooKeeper 使用

### 6.1 在本系统中的两个角色

| 角色 | 必要性 | 说明 |
|---|---|---|
| **Kafka 元数据协调** | 必需 | Broker 注册/Controller 选举/Topic 元数据 |
| **Dubbo 备用注册中心** | 可选 | 通过环境变量切换，与 Nacos 二选一 |

### 6.2 Docker 配置

```yaml
zookeeper:
  image: confluentinc/cp-zookeeper:7.5.0
  ports:
    - "2181:2181"                      # 客户端端口
  environment:
    ZOOKEEPER_CLIENT_PORT: 2181
    ZOOKEEPER_TICK_TIME: 2000          # 心跳基本单位 2s
  healthcheck:
    test: ["CMD-SHELL", "echo ruok | nc -w 2 localhost 2181 | grep -q imok"]
```

### 6.3 Kafka 依赖 ZK

```yaml
kafka:
  depends_on:
    zookeeper:
      condition: service_healthy        # ZK 健康才启动 Kafka
  environment:
    KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
```

### 6.4 Dubbo 切换到 ZK

```yaml
# application.yaml
dubbo:
  registry:
    address: ${DUBBO_REGISTRY:nacos://127.0.0.1:8848}
```

切换方式（零代码改动）：
```powershell
$env:DUBBO_REGISTRY = "zookeeper://127.0.0.1:2181"
java -jar order-service.jar
```

### 6.5 Maven 依赖链

```
dubbo-registry-zookeeper (Dubbo 的 ZK 适配器)
  └── curator-framework (ZK 高级客户端：自动重连/重试)
       └── curator-recipes (分布式锁/选举工具箱)
            └── zookeeper (原生 TCP 客户端)
```

### 6.6 Dubbo 在 ZK 中的节点结构

```
/dubbo
  └── org.inventory.api.InventoryFacade
        ├── providers
        │     └── dubbo://10.0.0.3:20880/...?version=1.0.0  (EPHEMERAL 临时节点)
        ├── consumers
        │     └── consumer://10.0.0.2:0/...
        ├── configurators
        └── routers
```

**临时节点**：服务挂掉 → session 超时 → 节点自动删除 → Consumer 收到 Watcher 通知 → 摘除实例。

---

## 七、CDC + Kafka + 数仓 数据同步

### 7.1 为什么要 CDC

```
❌ 传统双写：
   bookingMapper.insert(order);    // 写 MySQL
   esClient.index(order);          // 写 ES（挂了怎么办？）
   redis.set(key, order);          // 写 Redis（超时怎么办？）
   dwClient.send(order);           // 发数仓（失败回滚 MySQL？）

✅ CDC：
   bookingMapper.insert(order);    // 只写 MySQL，完事！
   // binlog → Debezium → Kafka → Consumer → ES/Redis/数仓（异步、解耦、不丢）
```

核心价值：
- **零侵入**：不改业务代码
- **最终一致**：binlog 是已提交的事实
- **业务解耦**：下游故障不影响下单
- **可扩展**：新增下游只加 Consumer，不碰业务

### 7.2 MySQL 配置（CDC 前提）

```yaml
# docker-compose.yml
mysql:
  command:
    - --server-id=223344            # 实例唯一 ID
    - --log-bin=mysql-bin           # 开启 binlog！
    - --binlog-format=ROW           # ROW 格式（记录每行变更前后值）
    - --binlog-row-image=FULL       # 记录所有列（不仅变更列）
    - --gtid-mode=ON                # 全局事务 ID（断点续传）
    - --enforce-gtid-consistency=ON
```

Debezium 专用账号：
```sql
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
  ON *.* TO 'debezium'@'%';
```

| 权限 | 用途 |
|---|---|
| `REPLICATION SLAVE` | 伪装从库订阅 binlog |
| `REPLICATION CLIENT` | 查询 binlog 位置 |
| `SELECT` | 全量快照时 SELECT 表数据 |

### 7.3 Kafka 配置

```yaml
kafka:
  environment:
    KAFKA_BROKER_ID: 1
    KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
    KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
    # kafka:29092 → 容器内通信（Debezium 用）
    # localhost:9092 → 宿主机通信（data-sync-consumer 用）
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"     # Debezium 写入时自动创建 Topic
```

### 7.4 Debezium (Kafka Connect) 配置

```yaml
connect:
  image: debezium/connect:2.5
  environment:
    BOOTSTRAP_SERVERS: kafka:29092              # Kafka 地址
    CONFIG_STORAGE_TOPIC: connect_configs       # Connector 配置存这里
    OFFSET_STORAGE_TOPIC: connect_offsets       # binlog 偏移量存这里（断点续传）
    STATUS_STORAGE_TOPIC: connect_statuses      # 运行状态存这里
    KEY_CONVERTER: ...JsonConverter
    VALUE_CONVERTER: ...JsonConverter
```

### 7.5 Debezium Connector 注册配置

`infra/debezium-mysql-connector.json`：

```json
{
  "name": "bizdb-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",
    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "dbz",
    "database.server.id": "184054",              // 伪装从库 ID（不能和 MySQL 的 223344 重复）
    "topic.prefix": "cdc",                       // Topic 前缀
    "database.include.list": "bizdb",            // 监听哪些库
    "table.include.list": "bizdb.user,bizdb.product,bizdb.booking",  // 监听哪些表
    "schema.history.internal.kafka.bootstrap.servers": "kafka:29092",
    "schema.history.internal.kafka.topic": "schema-history.bizdb",
    "include.schema.changes": "false",
    "snapshot.mode": "initial"                   // 首次全量快照，然后转增量
  }
}
```

**Topic 命名规则**：`{topic.prefix}.{database}.{table}`
- `cdc.bizdb.booking`
- `cdc.bizdb.product`
- `cdc.bizdb.user`

注册命令：
```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8083/connectors" `
  -ContentType "application/json" `
  -InFile "infra/debezium-mysql-connector.json"
```

### 7.6 Kafka 中的消息格式

当执行 `INSERT INTO booking ...` 后，`cdc.bizdb.booking` Topic 收到：

```json
{
  "payload": {
    "op": "c",                    // c=create, u=update, d=delete, r=snapshot read
    "source": {
      "connector": "mysql",
      "db": "bizdb",
      "table": "booking",
      "ts_ms": 1748500000000
    },
    "before": null,               // INSERT 没有旧值
    "after": {                    // 新插入的完整行数据
      "id": 3,
      "channel": "CTRIP",
      "product_id": "FLT-CA1234-20260601-Y",
      "quantity": 2,
      "status": "CREATED",
      ...
    }
  }
}
```

### 7.7 `data-sync-consumer` — 消费端

#### 配置

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: data-sync-consumer       # 消费组（多实例自动分区均衡）
      auto-offset-reset: earliest        # 首次从最早消息开始（不丢数据）

cdc:
  topics: cdc.bizdb.user,cdc.bizdb.booking,cdc.bizdb.product
  redis:
    key-template: "%s:%s"                # {table}:{id}，如 booking:3
  dw:
    table-prefix: "ods_"                 # 数仓 ODS 层前缀
```

#### `CdcEventListener.java` — 核心逻辑

```java
@KafkaListener(topics = "#{'${cdc.topics}'.split(',')}")
public void onMessage(String message) {
    JsonNode payload = MAPPER.readTree(message).get("payload");
    String op = payload.path("op").asText();          // 操作类型
    String table = payload.path("source").path("table").asText();  // 表名
    JsonNode after = payload.get("after");            // 变更后数据
    JsonNode before = payload.get("before");          // 变更前数据

    switch (op) {
        case "c", "u", "r" -> {
            syncToEs(table, after);       // PUT ES 文档（upsert）
            evictRedis(table, after);     // 删 Redis 缓存
            appendDw(table, op, ...);     // 写数仓
        }
        case "d" -> {
            deleteFromEs(table, before);  // 删 ES 文档
            evictRedis(table, before);    // 删 Redis 缓存
            appendDw(table, op, ...);     // 写数仓
        }
    }
}
```

#### 写 ES（搜索系统）

```java
private void syncToEs(String table, JsonNode after) {
    String id = idOf(after);
    String url = esBaseUrl + "/" + table + "/_doc/" + id;
    // PUT http://localhost:9200/booking/_doc/3
    rest.put(url, after.toString());   // upsert: 存在则更新，不存在则创建
}
```

#### 清 Redis 缓存（Cache-Aside 模式）

```java
private void evictRedis(String table, JsonNode row) {
    String key = String.format("%s:%s", table, idOf(row));  // booking:3
    redis.delete(key);  // 删缓存，下次读请求回源查 MySQL
}
```

为什么是"删"不是"写"？
- 删缓存 + 下次读回源 = 保证一致性
- 直接写缓存可能因顺序问题导致脏数据

#### 写数仓

```java
private void appendDw(String table, String op, JsonNode before, JsonNode after) {
    log.info("[DW] {}{} op={} before={} after={}", dwTablePrefix, table, op, before, after);
    // 生产中：写 ClickHouse / Hive / StarRocks
}
```

数仓分层：
```
ODS (原始数据, CDC 实时写入) → DWD (明细层) → DWS (汇总层) → ADS (BI 报表)
```

### 7.8 各下游系统的业务意义

| 系统 | 解决什么问题 | 典型场景 |
|---|---|---|
| **Elasticsearch** | MySQL `LIKE` 慢/不支持分词 | "搜索 6月上海飞东京的机票" |
| **Redis** | MySQL 读 QPS 有限 | "查产品余量"（缓存 < 1ms） |
| **数仓** | 业务库不能跑大查询（锁表/拖慢业务） | "本月各渠道 GMV"、"退订率趋势" |

---

## 八、各组件关联方式总表

| 从 | 到 | 关联方式 |
|---|---|---|
| yaml 配置 | `OAuth2ClientsProperties` | `@ConfigurationProperties(prefix="oauth2")` |
| Properties | DB `oauth2_registered_client` 表 | `registeredClientRepository()` 启动时 upsert |
| AS 签名 | 网关验签 | **同一把 RSA 密钥对**（AS 私钥签，网关拉公钥验） |
| 网关配置 | AS 地址 | `issuer-uri: http://127.0.0.1:8081` |
| 网关 JWT 解析 | `IdentityPropagationFilter` | `ReactiveSecurityContextHolder`（Reactor Context） |
| 网关注入 Header | 下游读 Header | **同名字符串** `X-User-Id` |
| `AuthContextFilter` | `AuthContext.current()` | **同一个 ThreadLocal** |
| Consumer Attachment | Provider Attachment | **Dubbo 协议**附加参数（跨网络） |
| `DubboAuthContextFilter` | Dubbo 框架 | **SPI 文件** + `@Activate` 注解 |
| 服务注册 | 网关路由 `lb://` | Nacos Discovery |
| 配置拉取 | `bootstrap.yml` | `spring.cloud.nacos.config.server-addr` |
| MySQL binlog | Debezium | `REPLICATION SLAVE` 权限 + ROW 格式 binlog |
| Debezium | Kafka | `topic.prefix` + 表名 → Topic 名 |
| Kafka | Consumer | `@KafkaListener(topics=...)` |
| Consumer | ES/Redis/数仓 | REST API / RedisTemplate / 日志 |
| Kafka | ZooKeeper | Broker 注册/Controller 选举/Topic 元数据 |
| Dubbo | ZooKeeper/Nacos | `dubbo.registry.address` 前缀决定 |

---

## 九、统一线程池管理

### 9.1 背景：为什么需要统一管理

在企业级微服务中，线程池散落在各个框架和业务代码中：

| 来源 | 线程名前缀 | 谁在管 |
|---|---|---|
| Tomcat | `http-nio-` | Spring Boot 自动配置 |
| Dubbo | `DubboServerHandler-`、`dubbo-` | Dubbo 框架 |
| Kafka Consumer | `kafka-`、`org.springframework.kafka-` | Spring Kafka |
| HikariCP | `HikariPool-` | 数据源自动配置 |
| Nacos | `com.alibaba.nacos.*` | Nacos Client |
| Spring 默认 `@Async` | `SimpleAsyncTaskExecutor-` | **未池化！每次 new Thread** |
| Spring 默认 `@Scheduled` | `scheduling-1` | **单线程！一个卡住全挂** |

**问题**：
1. Spring 默认的 `@Async` 用 `SimpleAsyncTaskExecutor`，**不池化**，每个任务都 new 一个线程，生产环境可能 OOM
2. Spring 默认的 `@Scheduled` 只有 **1 个线程**，一个定时任务阻塞就影响所有其他定时任务
3. 业务代码散落 `Executors.newFixedThreadPool()` 难以统一监控、缺乏优雅关闭

**统一线程池管理解决**：
- 全局注册、统一命名、集中监控
- 参数可通过 Nacos 动态调整
- REST 接口运行时查看指标 + 热调参

### 9.2 架构总览

```
┌────────────────────────────────────────────────────────────────┐
│               ThreadPoolAutoConfiguration                      │
│               (common-core 自动装配)                            │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐  │
│  │ bizThreadPool│  │asyncThreadPool│  │  schedThreadPool   │  │
│  │ 核心业务      │  │ @Async 默认   │  │  @Scheduled 默认   │  │
│  │  core=8      │  │  core=4       │  │  core=4            │  │
│  │  max=32      │  │  max=16       │  │  ScheduledExecutor │  │
│  │  queue=256   │  │  queue=512    │  │                    │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬───────────┘  │
│         │                 │                    │              │
│         └─────────────────┼────────────────────┘              │
│                           ▼                                    │
│                 ThreadPoolRegistry（全局注册表）                 │
│                           │                                    │
│              ┌────────────┼────────────────┐                   │
│              ▼                             ▼                   │
│   ThreadPoolMonitorController    ThreadDiagnosticController    │
│    GET /diag/thread-pools         GET /diag/threads            │
│    PUT /diag/thread-pools/{n}     （JVM 全量线程分析）           │
│    （托管池运行时指标+动态调参）                                  │
└────────────────────────────────────────────────────────────────┘
```

### 9.3 三个线程池的职责划分

| Bean 名 | 用途 | 适用场景 | 拒绝策略 |
|---|---|---|---|
| `bizThreadPool` | 核心业务并行任务 | CDC 同步 ES/Redis、批量导出、并行调用外部 API | CallerRunsPolicy（不丢任务） |
| `asyncThreadPool` | `@Async` 默认执行器 | 事件发布、日志上报、邮件通知 | CallerRunsPolicy |
| `schedThreadPool` | `@Scheduled` 定时任务 | 定时对账、补偿重试、缓存刷新 | — |

### 9.4 涉及文件清单

```
common-core/
├── src/main/java/org/common/thread/
│   ├── ThreadPoolProperties.java          # @ConfigurationProperties 外部化参数
│   ├── ThreadPoolRegistry.java            # 全局注册表（注册/快照/动态调参）
│   ├── ThreadPoolAutoConfiguration.java   # 自动装配（创建3个池 + 注册 + @EnableAsync/Scheduling）
│   └── ThreadPoolMonitorController.java   # REST 监控端点
├── src/main/java/org/common/diag/
│   └── ThreadDiagnosticController.java    # JVM 全量线程诊断端点
└── src/main/resources/META-INF/spring/
    └── ...AutoConfiguration.imports       # SPI 注册

infra/nacos-config/
└── common-shared.yaml                    # 线程池参数（Nacos 动态下发）

data-sync-consumer/
└── src/main/java/org/sync/listener/
    └── CdcEventListener.java             # 实际使用 bizThreadPool 的业务代码
```

### 9.5 关键代码解析

#### 9.5.1 `ThreadPoolProperties` —— 外部化参数

```java
@ConfigurationProperties(prefix = "thread-pool")
public class ThreadPoolProperties {
    private PoolDef biz   = new PoolDef(8, 32, 256, 60, "biz-pool-");
    private PoolDef async = new PoolDef(4, 16, 512, 120, "async-pool-");
    private ScheduleDef schedule = new ScheduleDef(4, "sched-pool-");
    // getter/setter ...
}
```

- `@ConfigurationProperties(prefix = "thread-pool")`：Spring Boot 自动将 yaml 中 `thread-pool.*` 绑定到此对象
- 构造函数提供默认值，**即使不配任何 yaml 也能正常启动**
- 通过 Nacos `common-shared.yaml` 下发，所有服务统一生效；单个服务可在自己的配置中覆盖

对应的 Nacos 配置（`common-shared.yaml`）：

```yaml
thread-pool:
  biz:
    core-size: ${THREAD_POOL_BIZ_CORE:8}     # 核心线程数
    max-size: ${THREAD_POOL_BIZ_MAX:32}       # 最大线程数
    queue-capacity: ${THREAD_POOL_BIZ_QUEUE:256}  # 等待队列长度
    keep-alive-seconds: 60                     # 空闲线程存活时间
    thread-name-prefix: biz-pool-              # 线程名前缀（日志排查关键）
  async:
    core-size: 4
    max-size: 16
    queue-capacity: 512
    thread-name-prefix: async-pool-
  schedule:
    core-size: 4
    thread-name-prefix: sched-pool-
```

#### 9.5.2 `ThreadPoolAutoConfiguration` —— 自动装配

```java
@AutoConfiguration
@EnableAsync          // 启用 @Async 注解
@EnableScheduling     // 启用 @Scheduled 注解
@EnableConfigurationProperties(ThreadPoolProperties.class)
@Import(ThreadPoolMonitorController.class)
public class ThreadPoolAutoConfiguration implements AsyncConfigurer {

    @Bean(name = "bizThreadPool", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor bizThreadPool(ThreadPoolRegistry registry) {
        // 从 props 读参数 → 创建池 → 注册到 Registry → 返回
    }

    @Bean(name = "asyncThreadPool", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor asyncThreadPool(ThreadPoolRegistry registry) { ... }

    @Bean(name = "schedThreadPool", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler schedThreadPool(ThreadPoolRegistry registry) { ... }

    @Override
    public Executor getAsyncExecutor() {
        return asyncThreadPool(threadPoolRegistry());  // @Async 默认走 asyncThreadPool
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("[AsyncPool] method={}: {}", method.getName(), ex.getMessage(), ex);
    }
}
```

**核心设计点**：

| 设计 | 原因 |
|---|---|
| `implements AsyncConfigurer` | 让 `@Async` 默认走 `asyncThreadPool`，而非 Spring 的单线程 |
| `destroyMethod = "shutdown"` | JVM 关闭时等待任务完成（优雅停机） |
| `setWaitForTasksToCompleteOnShutdown(true)` | 配合 `awaitTerminationSeconds=30`，最多等 30 秒 |
| `CallerRunsPolicy` | 队列满了由提交线程自己执行，保证任务不丢（但会阻塞调用方） |
| `@ConditionalOnMissingBean` (Registry) | 业务服务可自定义 Registry 覆盖 |
| `@Import(ThreadPoolMonitorController.class)` | 监控 Controller 跟随自动装配注册 |

#### 9.5.3 `ThreadPoolRegistry` —— 全局注册表

```java
public class ThreadPoolRegistry {
    private final Map<String, ThreadPoolTaskExecutor>  executors  = new ConcurrentHashMap<>();
    private final Map<String, ThreadPoolTaskScheduler> schedulers = new ConcurrentHashMap<>();

    // 注册
    public void register(String name, ThreadPoolTaskExecutor executor) { ... }

    // 运行时快照（活跃数、队列深度、完成数等）
    public List<Map<String, Object>> snapshot() { ... }

    // 动态调参（热生效，不重启）
    public Map<String, Object> resize(String name, Integer coreSize, Integer maxSize) { ... }
}
```

**动态调参安全性**：调参时先调大 `maxPoolSize` 再调小 `corePoolSize`，避免出现 `core > max` 抛 `IllegalArgumentException`。

#### 9.5.4 实际使用示例 —— `CdcEventListener`

改造前（串行阻塞 Kafka Consumer 线程）：

```java
@KafkaListener(topics = "...")
public void onMessage(String message) {
    syncToEs(table, after);      // 阻塞
    evictRedis(table, after);    // 阻塞
    appendDw(table, op, ...);    // 阻塞
}
```

改造后（并行提交到 bizThreadPool）：

```java
@Qualifier("bizThreadPool")
private final ThreadPoolTaskExecutor bizPool;

@KafkaListener(topics = "...")
public void onMessage(String message) {
    // 解析 CDC 消息...
    bizPool.submit(() -> syncToEs(table, after));     // 异步
    bizPool.submit(() -> evictRedis(table, after));   // 异步
    bizPool.submit(() -> appendDw(table, op, ...));   // 异步
    bizPool.submit(() -> syncWideTable(table, ...));  // 异步
}
```

**效果**：Kafka Consumer 线程只做消息解析和分发，IO 密集的 ES/Redis/DW 写入由 `biz-pool-*` 线程并行执行，消费吞吐量大幅提升。

#### 9.5.5 业务服务如何使用

**方式一：注入 bizThreadPool 显式提交任务**

```java
@Service
public class SomeService {

    @Autowired @Qualifier("bizThreadPool")
    private ThreadPoolTaskExecutor bizPool;

    public void batchExport(List<Long> ids) {
        List<Future<byte[]>> futures = ids.stream()
            .map(id -> bizPool.submit(() -> generatePdf(id)))
            .toList();
        // 收集结果...
    }
}
```

**方式二：@Async 注解（自动走 asyncThreadPool）**

```java
@Service
public class NotificationService {

    @Async  // 走 asyncThreadPool，无需指定 value
    public void sendBookingNotification(Long orderId) {
        // 发邮件 / 推消息 ...
    }
}
```

**方式三：@Scheduled 定时任务（自动走 schedThreadPool）**

```java
@Component
public class ReconciliationJob {

    @Scheduled(cron = "0 */5 * * * ?")  // 走 schedThreadPool（4线程），不再单线程
    public void reconcile() {
        // 对账逻辑 ...
    }
}
```

### 9.6 监控端点

#### `ThreadPoolMonitorController`（托管池运行时指标）

| 端点 | 说明 |
|---|---|
| `GET /diag/thread-pools` | 所有托管线程池运行时快照 |
| `GET /diag/thread-pools/{name}` | 指定池详情 |
| `GET /diag/thread-pools/summary` | 简要摘要（一行一池） |
| `PUT /diag/thread-pools/{name}?coreSize=16&maxSize=64` | **运行时动态调参**（热生效） |

示例响应（`GET /diag/thread-pools`）：

```json
{
  "code": 0,
  "data": [
    {
      "name": "bizThreadPool",
      "type": "ThreadPoolTaskExecutor",
      "corePoolSize": 8,
      "maxPoolSize": 32,
      "activeCount": 3,
      "poolSize": 8,
      "largestPoolSize": 12,
      "queueCapacity": 256,
      "queueSize": 0,
      "queueRemainingCapacity": 256,
      "completedTaskCount": 1547,
      "taskCount": 1550,
      "queueUsagePercent": "0.0%",
      "isShutdown": false
    }
  ]
}
```

动态调参示例：

```bash
# 运行时调大业务线程池（不重启服务）
curl -X PUT "http://localhost:8082/diag/thread-pools/bizThreadPool?coreSize=16&maxSize=64"
```

### 9.7 SPI 自动装配机制

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── org.common.CommonAutoConfiguration           # 统一异常 + AuthContextFilter + 线程诊断
├── org.common.mp.MybatisPlusAutoConfiguration    # MyBatis-Plus
└── org.common.thread.ThreadPoolAutoConfiguration # 统一线程池（本章新增）
```

任何微服务只要 `pom.xml` 依赖了 `common-core`，Spring Boot 启动时自动：
1. 读取 `thread-pool.*` 参数（来自 Nacos / application.yaml / 默认值）
2. 创建 3 个线程池 Bean → 注册到 `ThreadPoolRegistry`
3. 启用 `@Async` / `@Scheduled` 注解支持
4. 注册 `/diag/thread-pools` 监控端点

---

## 十、线程诊断与监控

### 10.1 功能概述

`ThreadDiagnosticController` 提供 JVM 全量线程分析，覆盖**所有线程**（不仅是托管池），包含 Tomcat、Dubbo、Kafka、HikariCP、Nacos、ZooKeeper 等框架线程。

| 端点 | 功能 |
|---|---|
| `GET /diag/threads` | 全景概览（JVM 指标 + 状态分布 + 线程池分组 + 死锁检测） |
| `GET /diag/threads/dump` | 完整线程堆栈（支持 keyword / state 过滤） |
| `GET /diag/threads/pools` | 按线程池分组（每个池的线程列表） |
| `GET /diag/threads/deadlocks` | 专项死锁检测 |
| `GET /diag/threads/top?n=10` | CPU 消耗 Top-N 线程 |

### 10.2 线程分类规���

通过线程名前缀自动归类到对应的池（`POOL_RULES`）：

```
Tomcat          ← http-nio-*、tomcat-*
Dubbo           ← DubboServerHandler-*、dubbo-*、NettyServerWorker-*
Kafka           ← kafka-*、org.springframework.kafka-*
HikariCP        ← HikariPool-*
Nacos           ← com.alibaba.nacos.*、nacos-*
Sentinel        ← sentinel-*
Netty           ← nioEventLoop*、reactor-*
Biz-Pool        ← biz-pool-*         ← 统一线程���·业务
Async-Pool      ← async-pool-*       ← 统一线程池·异步
Sched-Pool      ← sched-pool-*       ← 统一线程池·定时
Lettuce-Redis   ← lettuce-*
ZooKeeper       ← ZkClient-*、SendThread、EventThread、curator-*
GC-JVM          ← GC *、Reference Handler、Finalizer、DestroyJavaVM
Other           ← 未匹配到的
```

### 10.3 两个 Controller 的区别

| | `ThreadDiagnosticController` | `ThreadPoolMonitorController` |
|---|---|---|
| 路径前缀 | `/diag/threads` | `/diag/thread-pools` |
| 视角 | JVM **全量线程**（含框架、GC） | 仅 **业务自建** 的 3 个池 |
| 指标 | 状态分布、堆栈、死锁、CPU Top-N | 活跃数、队列深度、完成数、使用率 |
| 调参 | ❌ 只读 | ✅ `PUT` 动态调整 core/max |
| 适用场景 | 排查死锁、CPU 飙高、线程泄漏 | 运营监控、容量评估、参数调优 |

### 10.4 使用示例

```bash
# ——— 全景概览 ———
curl http://localhost:8082/diag/threads

# ——— 只看 Dubbo 相关线程 ———
curl "http://localhost:8082/diag/threads/dump?keyword=dubbo"

# ——— 只看 BLOCKED 状态（排查死锁/争锁） ———
curl "http://localhost:8082/diag/threads/dump?state=BLOCKED"

# ——— 死锁专项检测 ———
curl http://localhost:8082/diag/threads/deadlocks

# ——— CPU 消耗 Top 10 ———
curl "http://localhost:8082/diag/threads/top?n=10"

# ——— 看业务线程池状态 ———
curl http://localhost:8082/diag/thread-pools/summary

# ——— 运行时调大线程池（不重启） ———
curl -X PUT "http://localhost:8082/diag/thread-pools/bizThreadPool?coreSize=16&maxSize=64"
```

### 10.5 安全说明

- 网关已将 `/diag/**` 加入安全白名单（无需 Token），方便运维快速排查
- **生产环境建议**：通过 Nginx / 网关规则限制只允许内网 IP 访问 `/diag/**`

---

## 附：启动顺序

```powershell
# 1. 基础设施
docker compose up -d

# 2. 等待所有容器健康
docker compose ps

# 3. 注册 CDC Connector
powershell -File infra/register-debezium-connector.ps1

# 4. 启动微服务（顺序无强依赖，但建议）
java -jar user-service.jar          # AS (8081)
java -jar gateway-service.jar       # 网关 (8080)
java -jar inventory-service.jar     # 产品 (8083)
java -jar order-service.jar         # 订单 (8082)
java -jar data-sync-consumer.jar    # CDC消费 (8090)

# 5. 测试
# 获取 Token
curl -X POST http://localhost:8080/api/oauth2/token \
  -u ctrip-channel:ctrip-secret \
  -d "grant_type=client_credentials&scope=booking.read booking.write"

# 下单
curl -X POST http://localhost:8080/api/bookings \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"productId":"FLT-CA1234-20260601-Y","quantity":1,"channelOrderNo":"TEST-001"}'

# 观察 data-sync-consumer 日志：
# [CDC] table=booking op=c after={...}
# [ES]  upsert booking/5
# [Redis] evict booking:5
# [DW] ods_booking op=c after={...}
```
