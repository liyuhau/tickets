# 项目最终交付总目录页

> 这是整个 `data-sync-service` 的统一阅读入口。建议先看目录，再按顺序阅读各文档。

---

## 1. 阅读顺序

### 1.1 快速了解项目
1. `README.md`
2. `docs/index.md`（本文件）

### 1.2 深入理解架构
1. `docs/architecture-explained.md`
2. `docs/database.md`
3. `docs/es-layer.md`

### 1.3 部署与验证
1. `docs/deployment-guide.md`
2. `infra/mysql-init/01-schema.sql`
3. `infra/nacos-config/`
4. `infra/debezium-mysql-connector.json`

---

## 2. 文档用途说明

| 文档 | 作用 | 适合谁看 |
|---|---|---|
| `README.md` | 项目总览、模块导航、最小启动、核心链路 | 所有人 |
| `docs/index.md` | 最终交付总目录页、阅读导航 | 所有人 |
| `docs/architecture-explained.md` | 架构全链路解析、代码关联、组件职责 | 后端/架构/测试/运维 |
| `docs/database.md` | 数据库 DDL、索引、测试数据、运维 SQL | 后端/DBA/测试 |
| `docs/es-layer.md` | ES 宽表层 ADR、企业级增项、索引治理 | 后端/架构/运维 |
| `docs/deployment-guide.md` | 部署前准备、启动顺序、验证清单、常见问题 | 运维/后端/测试 |

---

## 3. 模块与文档映射

| 模块 | 对应文档 | 说明 |
|---|---|---|
| `gateway-service` | `docs/architecture-explained.md` | 鉴权、路由、Header 透传 |
| `user-service` | `docs/architecture-explained.md` | OAuth2 授权、吊销、JWK |
| `order-service` | `docs/database.md`、`docs/architecture-explained.md` | 订单表、Dubbo 调用 |
| `inventory-service` | `docs/database.md`、`docs/architecture-explained.md` | 库存表、乐观锁、Dubbo Provider |
| `data-sync-consumer` | `docs/es-layer.md`、`docs/architecture-explained.md` | CDC 消费、ES 宽表、Redis 维度缓存 |
| `reindex-job` | `docs/es-layer.md`、`docs/deployment-guide.md` | 全量回填 Job、分页重建 |
| `infra/` | `docs/deployment-guide.md` | 基础设施、MySQL 初始化、Nacos 配置、Debezium |

---

## 4. 关键链路导航

### 4.1 登录 / 鉴权
- 入口：`README.md`
- 细节：`docs/architecture-explained.md`
- 相关代码：`user-service`、`gateway-service`、`common-core`

### 4.2 订单 / 库存交易
- 入口：`README.md`
- 细节：`docs/architecture-explained.md`
- 数据：`docs/database.md`

### 4.3 CDC 同步 / ES 宽表
- 入口：`README.md`
- 细节：`docs/es-layer.md`
- 相关代码：`data-sync-consumer`、`reindex-job`

### 4.4 部署 / 验证
- 入口：`README.md`
- 细节：`docs/deployment-guide.md`
- 资源：`infra/`

---

## 5. 最终交付包清单

- `README.md`
- `docs/index.md`
- `docs/architecture-explained.md`
- `docs/database.md`
- `docs/es-layer.md`
- `docs/deployment-guide.md`
- `infra/mysql-init/01-schema.sql`
- `infra/nacos-config/`
- `infra/debezium-mysql-connector.json`

---

## 6. 备注

如果你是第一次接触这个项目，建议先看：
1. `README.md`
2. `docs/index.md`
3. `docs/architecture-explained.md`

如果你准备部署，建议先看：
1. `docs/index.md`
2. `docs/deployment-guide.md`
3. `docs/es-layer.md`
