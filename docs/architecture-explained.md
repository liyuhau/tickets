# 全球旅游产品同业交易平台 — 架构全链路解析

> 目标：把系统的“组件是什么、为什么这么设计、代码如何关联、请求如何流转”一次讲清楚。
>
> 适用读者：后端开发、架构设计、测试、运维、产品技术支持。
>
> 说明：本文档与 `README.md`、`docs/database.md` 共同构成项目的事实来源，README 侧重快速上手，这里侧重技术原理与代码关联。

---

## 目录

- [一、系统概览](#一系统概览)
- [二、鉴权体系：OAuth2 Authorization Server + Resource Server](#二鉴权体系oauth2-authorization-server--resource-server)
- [三、用户身份传递：网关 Header → 下游 ThreadLocal → 静态工具类](#三用户身份传递网关-header--下游-threadlocal--静态工具类)
- [四、Nacos 注册中心与配置中心](#四nacos-注册中心与配置中心)
- [五、Dubbo RPC 调用链路](#五dubbo-rpc-调用链路)
- [六、ZooKeeper 的使用场景](#六zookeeper-的使用场景)
- [七、CDC + Kafka + Redis/ES/数仓](#七cdc--kafka--redises数仓)
- [八、统一线程池与线程诊断](#八统一线程池与线程诊断)
- [九、模块职责与代码关联总表](#九模块职责与代码关联总表)

---

## 一、系统概览

### 1.1 业务目标

本项目模拟的是一个**全球旅游产品同业交易平台**。系统对外服务于 OTA、批发商、海外分销商等同业渠道，对内拆分为授权、网关、订单、库存、CDC 消费等多个服务。

系统的核心目标不是“展示一个简单 CRUD Demo”，而是演示一条完整的企业级链路：

- 外部请求先经过网关鉴权与路由
- 业务请求通过 Dubbo 调用库存服务完成实时扣减
- MySQL 业务库作为事实源
- CDC 将变更异步同步到 ES / Redis / 数仓
- 统一返回、统一异常、统一线程治理贯穿所有模块

### 1.2 系统全景图

```text
Client / 前端 / APP
        │ HTTP + Bearer Token
        ▼
API Gateway (gateway-service)
        │ 1. JWT 验签
        │ 2. 令牌吊销检查
        │ 3. 身份 Header 透传
        ▼
order-service / inventory-service / user-service
        │
        ├── Dubbo RPC
        ▼
inventory-service
        │
        ▼
MySQL (bizdb)
        │ binlog
        ▼
Debezium CDC
        ▼
Kafka
        ▼
data-sync-consumer
   ├── Elasticsearch
   ├── Redis
   └── 数仓 / BI
```

### 1.3 为什么要这样拆

- **网关独立**：统一做认证、鉴权、限流、灰度、Header 透传，避免每个服务重复实现。
- **授权服务独立**：OAuth2 令牌签发、客户端管理、吊销、JWK 暴露都是安全域能力，必须从业务服务剥离。
- **订单与库存拆分**：订单关注交易状态，库存关注可售余量，职责不同，适合通过 Dubbo 解耦。
- **CDC 独立**：数据同步不应阻塞主交易链路，因此用 binlog + Kafka 做异步最终一致。
- **统一基础能力**：`common-core` 承载通用代码，避免重复造轮子。

---

## 二、鉴权体系：OAuth2 Authorization Server + Resource Server

### 2.1 为什么使用 OAuth2

本项目使用 OAuth2 的原因是：

1. **标准化**：适配企业级客户端凭证、访问令牌、刷新令牌、吊销等通用能力。
2. **可扩展**：后续可以很容易接入第三方客户端、移动端、合作伙伴系统。
3. **边界清晰**：认证由授权服务负责，资源访问由网关和业务服务负责。
4. **兼容网关架构**：网关做 JWT 验签，下游只需要看可信 Header。

### 2.2 角色划分

| 角色 | 模块 | 职责 |
|---|---|---|
| Authorization Server | `user-service` | 颁发 token、管理客户端、暴露 JWK、处理令牌吊销 |
| Resource Server | `gateway-service` | 校验 token 合法性、做吊销检查、转发身份信息 |
| 下游业务服务 | `order-service` / `inventory-service` | 不直接验签，只读取 `AuthContext` |

### 2.3 整体流程

```text
① 客户端访问 /api/oauth2/token
② 网关白名单放行，转发到 user-service
③ user-service 校验 client_id / client_secret
④ user-service 用 RSA 私钥签发 JWT
⑤ 客户端携带 Bearer Token 访问业务接口
⑥ 网关校验 JWT、检查吊销列表、注入身份 Header
⑦ 下游服务从 Header 还原 AuthContext
```

### 2.4 代码关联

#### `user-service`
- `AuthorizationServerConfig`：OAuth2 核心配置
- `JdbcRegisteredClientRepository`：客户端持久化
- `OAuth2TokenCustomizer`：向 JWT 中写入 `channel`、`userId`、`name`
- `TokenRevocationController`：处理吊销请求
- `RedisTokenRevocationService`：记录 token 黑名单

#### `gateway-service`
- `GatewaySecurityConfig`：WebFlux 安全链
- `JwtRevocationFilter`：查询 Redis 黑名单
- `IdentityPropagationFilter`：从 JWT claims 取值并注入 Header
- `InternalApiSignatureFilter`：给透传 Header 再加一层内部签名

#### `common-core`
- `AuthContextFilter`：在下游从 Header 还原 ThreadLocal
- `AuthContext`：线程上下文容器

### 2.5 为什么 JWT 中要放业务声明

JWT 不只是“登录态令牌”，在本项目中它还承载了业务身份信息：

- `sub`：同业账号 ID
- `channel`：渠道编码，如 CTRIP / FLIGGY / EXPEDIA
- `name`：渠道名称
- `userId`：用户/账号标识

这样网关就能把 JWT 中的身份信息转换成 Header，下游服务无需再次查询数据库。

### 2.6 为什么要做吊销检查

JWT 一旦签发，如果只靠本地验签，直到过期前都可能一直可用。企业里通常需要：

- 用户退出后立即失效
- 管理员强制下线
- 风险账号封禁

因此本项目把 `jti` 存进 Redis 黑名单，网关每次请求都校验一次。

---

## 三、用户身份传递：网关 Header → 下游 ThreadLocal → 静态工具类

### 3.1 为什么不用每个服务都解析 JWT

如果所有业务服务都自己解析 JWT，会带来这些问题：

- 重复实现认证逻辑
- 每个服务都依赖 Spring Security
- 业务代码会被安全细节污染
- 多语言服务接入更困难

所以采用企业里很常见的做法：**网关注入可信 Header，下游只读上下文**。

### 3.2 全链路

```text
1. GatewaySecurityConfig 负责 JWT 验签
2. IdentityPropagationFilter 从 JWT 中提取用户信息
3. 网关注入 X-User-Id / X-User-Channel / X-User-Name
4. 下游 AuthContextFilter 从 Header 还原 AuthContext
5. 业务代码直接 AuthContext.current() 读取用户信息
```

### 3.3 `AuthContext` 的作用

`AuthContext` 本质上是一个 **ThreadLocal 容器**：

- 同一个请求线程可见
- 不同请求线程隔离
- 请求结束必须清理，否则线程池复用会串号

### 3.4 `AuthContextFilter` 为什么必须 `finally clear()`

Tomcat、Jetty、Undertow 的工作线程都是池化复用的。若不清理：

- 上一个请求的用户信息会残留到下一个请求
- 轻则日志串号，重则数据越权

所以无论业务流程成功失败，都必须在 `finally` 中执行：

- `AuthContext.clear()`
- `MDC.remove("userId")`

### 3.5 `MDC` 的意义

`MDC` 是日志上下文：

- 它和 `ThreadLocal` 一样，跟随线程传播
- 配合日志格式可以把 `userId` 自动打印到每条日志中
- 排查问题时可直接按 `userId` 检索日志链路

### 3.6 Dubbo RPC 下的身份透传

HTTP Header 不会自动进入 Dubbo RPC，所以又加了一层 Dubbo Filter：

- Consumer 侧：`ThreadLocal` → Dubbo Attachment
- Provider 侧：Dubbo Attachment → `ThreadLocal`

这样订单服务调用库存服务时，库存服务也能读到同一个 `AuthContext`。

---

## 四、Nacos 注册中心与配置中心

### 4.1 为什么既要注册中心又要配置中心

Nacos 在本项目里承担两个角色：

1. **注册中心**：告诉服务“谁在什么地址上提供什么接口”
2. **配置中心**：统一管理 `common-shared.yaml`、`gateway-service.yaml` 等配置

这两者是完全不同的能力，但经常一起使用。

### 4.2 启动顺序为什么依赖 `bootstrap.yml`

Spring Boot 的加载顺序决定了：

- `bootstrap.yml` 最早加载
- 它负责先告诉应用“Nacos 在哪里”
- 然后应用才能去 Nacos 拉远程配置

如果只写在 `application.yml`，很多场景下远程配置会来得太晚。

### 4.3 配置优先级

以 `order-service` 为例，配置来源大致按下面顺序叠加：

1. `order-service.yaml`
2. `order-service-{profile}.yaml`
3. `common-shared.yaml`
4. 本地 `application.yml`
5. 本地 `bootstrap.yml`

越靠前优先级越高。

### 4.4 动态刷新

通过 `@RefreshScope`，Nacos 配置变更后，Bean 可以重新装配而无需重启服务。

这适合：

- 风控阈值
- 开关配置
- 默认超时时间
- 线程池参数

### 4.5 路由与服务发现的关联

网关中使用 `lb://service-name` 路由时，Spring Cloud LoadBalancer 会先向 Nacos 获取实例列表，再做负载均衡选择。也就是说：

- 路由规则由网关配置负责
- 实例地址由 Nacos Discovery 提供
- 二者组合才完成一次真正的转发

---

## 五、Dubbo RPC 调用链路

### 5.1 为什么选择 Dubbo

Dubbo 适合这个项目的原因是：

- 调用快，适合服务间高频 RPC
- 支持接口契约和版本控制
- 对注册中心友好
- 能很好地传递 RPC 级上下文

### 5.2 模块职责

| 模块 | 角色 | 说明 |
|---|---|---|
| `inventory-api` | 契约模块 | 只放接口、DTO、常量、异常定义 |
| `inventory-service` | Provider | 真正实现库存扣减逻辑 |
| `order-service` | Consumer | 通过 `@DubboReference` 调库存 |

### 5.3 调用流程

```text
OrderController
  → OrderService
  → InventoryFacade.deduct()
  → Dubbo Proxy
  → 网络传输
  → InventoryFacadeImpl
```

### 5.4 为什么要分契约模块

如果接口直接写在业务服务里：

- 消费方和提供方容易互相依赖
- 升级容易冲突
- 接口版本不清晰

独立 `inventory-api` 后，接口、DTO、异常都变成了稳定契约。

### 5.5 超时、重试与幂等

- **查询类接口**：可以适度重试
- **写操作**：通常不重试，避免重复扣减
- **超时**：短一些，尽快失败，防止线程堆积

这也是为什么 `common-shared.yaml` 里要统一管理 Dubbo 默认参数。

---

## 六、ZooKeeper 的使用场景

### 6.1 为什么文档里会出现 ZooKeeper

ZooKeeper 在这个项目里主要有两个用途：

1. **Kafka 元数据协调**：Kafka 依赖 ZooKeeper 做 Broker 管理、Controller 选举等
2. **Dubbo 可切换注册中心**：项目支持 Nacos 和 ZooKeeper 二选一

### 6.2 Kafka 与 ZooKeeper 的关系

Kafka 启动时需要知道 ZooKeeper 地址，ZooKeeper 负责存储 Kafka 的协调信息。

### 6.3 Dubbo 通过环境变量切换

通过修改 `DUBBO_REGISTRY` 环境变量，可以不用改代码就切到 ZooKeeper 注册中心。

这意味着配置与代码彻底解耦。

---

## 七、CDC + Kafka + Redis/ES/数仓

### 7.1 为什么要做 CDC

CDC 的价值不是“把数据库同步出去”，而是**把业务库变成唯一事实源**。

业务写库只做一件事：

- 成功落 MySQL

后续同步全部交给：

- Debezium 监听 binlog
- Kafka 做缓冲与解耦
- `data-sync-consumer` 负责投递下游

这避免了传统“双写”里最常见的几个问题：

- 写 MySQL 成功，写 ES 失败
- 写 Redis 成功，写数仓失败
- 下游慢，拖垮主交易链路

### 7.2 CDC 主链路

```text
MySQL binlog
  → Debezium
  → Kafka topic
  → data-sync-consumer
  → ES / Redis / 数仓
```

### 7.3 为什么要同步到 ES

ES 适合：

- 搜索
- 条件筛选
- 宽表查询
- 模糊匹配

比如要查“某渠道下某日期所有机票订单”，ES 比直接跨表 join 更适合做检索型查询。

### 7.4 为什么要同步到 Redis

Redis 适合：

- 热点数据缓存
- 高并发读
- 快速失效

例如库存、产品详情、会话信息都适合缓存。

### 7.5 为什么要同步到数仓

数仓适合：

- BI 统计
- 趋势分析
- 历史报表
- 大范围聚合查询

不要让业务库承担报表压力。

### 7.6 Debezium 的意义

Debezium 不是简单的“订阅数据库”，而是利用数据库 binlog 作为变更事件源，把表级变化转成标准化事件消息。

它的优势是：

- 不改业务代码
- 能捕获已提交事务
- 支持增量和快照
- 适合企业级数据集成

---

## 八、统一线程池与线程诊断

### 8.1 为什么要统一线程池

如果线程池散落在业务代码里，常见问题是：

- 有的地方直接 `new Thread`
- 有的地方用默认 `@Async`
- 有的地方 `@Scheduled` 还是单线程
- 线程命名混乱，不好排查

统一治理可以带来：

- 命名统一
- 监控统一
- 参数可调
- 拒绝策略可控

### 8.2 常见线程来源

| 来源 | 风险 |
|---|---|
| Spring 默认 `@Async` | 不池化，可能无限创建线程 |
| Spring 默认 `@Scheduled` | 单线程，任务互相阻塞 |
| 业务代码手写 `Executors.newFixedThreadPool()` | 难统一治理 |

### 8.3 为什么要 `ThreadDiagnosticController`

线程诊断接口用于：

- 查看当前 JVM 所有线程
- 排查死锁
- 排查线程池耗尽
- 排查长时间阻塞

它属于运维/排障能力，不是业务接口。

---

## 九、模块职责与代码关联总表

| 模块 | 主要职责 | 关键代码/配置 |
|---|---|---|
| `common-core` | 通用返回、统一异常、线程池、AuthContext | `R`、`ResultCode`、`GlobalExceptionHandler`、`AuthContextFilter` |
| `gateway-service` | 鉴权、路由、Header 透传、吊销检查 | `GatewaySecurityConfig`、`JwtRevocationFilter`、`IdentityPropagationFilter` |
| `user-service` | OAuth2 授权服务 | `AuthorizationServerConfig`、`TokenRevocationController`、`JdbcRegisteredClientRepository` |
| `order-service` | 订单处理 | `OrderController`、Dubbo Consumer、MyBatis |
| `inventory-service` | 产品与库存管理 | `InventoryFacadeImpl`、MyBatis、锁控制 |
| `data-sync-consumer` | CDC 消费 | Kafka Listener、ES/Redis/数仓同步 |

---

## 十、建议的阅读顺序

如果你是第一次看这个项目，建议按下面顺序阅读：

1. `README.md`：快速了解业务与启动
2. `docs/database.md`：理解表结构和测试数据
3. `docs/architecture-explained.md`：理解请求链路和技术关联
4. 再回到 `gateway-service` / `user-service` / `common-core` 看代码

---

## 十一、后续优化方向

- 补充网关、Dubbo、CDC 的集成测试
- 增加 Prometheus / Grafana 指标监控
- 为订单、库存增加更细粒度的幂等与补偿机制
- 如需跨表大宽表查询，可继续沉淀 ES 宽表模型
