# 数据库设计文档

> 全球旅游产品同业交易平台 - MySQL 业务库 `bizdb`
>
> 本文档汇总：表结构（DDL）+ 字段说明 + 索引策略 + 测试数据（DML），
> 与 `infra/mysql-init/01-schema.sql` 保持一致；容器首次启动会自动执行该脚本。
> 阅读建议：先看 `docs/index.md` 和 `docs/architecture-explained.md`，再看本文件了解表结构与测试数据。

---

## 1. 数据库总览

| 项 | 值 |
|---|---|
| 数据库名 | `bizdb` |
| 字符集 | `utf8mb4` |
| 引擎 | `InnoDB` |
| 时区 | `Asia/Shanghai` |
| binlog | 开启（`ROW` 格式，给 Debezium 用） |
| 账号 | `root/root`（业务）、`debezium/dbz`（CDC 只读） |

| 表名 | 归属服务 | 说明 |
|---|---|---|
| `user` | user-service | 同业账号 / 分销商 |
| `product` | inventory-service | 旅游产品（机票 + 酒店）库存 |
| `booking` | order-service | 同业 B2B 预订单 |

---

## 2. 表：`user` 同业账号

```sql
CREATE TABLE IF NOT EXISTS `user` (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(64)  NOT NULL                COMMENT '同业账号名/公司名',
  channel     VARCHAR(32)  NOT NULL DEFAULT ''     COMMENT '所属渠道: CTRIP/FLIGGY/EXPEDIA',
  email       VARCHAR(128) NOT NULL DEFAULT '',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同业账号/分销商';
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id | BIGINT | ✓ | 主键，自增 |
| name | VARCHAR(64) | ✓ | 公司名，全局唯一 |
| channel | VARCHAR(32) | ✓ | 所属同业渠道编码 |
| email | VARCHAR(128) | | 联系邮箱 |
| status | VARCHAR(16) | ✓ | ACTIVE / DISABLED |
| create_time / update_time | DATETIME | ✓ | 审计时间 |

**索引：** `uk_name(name)` 唯一索引，防重复账号。

---

## 3. 表：`product` 旅游产品库存

```sql
CREATE TABLE IF NOT EXISTS `product` (
  product_id  VARCHAR(64)  NOT NULL                COMMENT '产品编码: FLT-* / HTL-*',
  type        VARCHAR(16)  NOT NULL                COMMENT 'FLIGHT / HOTEL',
  name        VARCHAR(128) NOT NULL                COMMENT '航班/房型名称',
  travel_date DATE         NOT NULL                COMMENT '出行/入住日期',
  price_cents BIGINT       NOT NULL                COMMENT '同业结算价(分)',
  stock       INT          NOT NULL                COMMENT '可售余量',
  version     BIGINT       NOT NULL DEFAULT 0      COMMENT '乐观锁',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (product_id),
  KEY idx_type_date (type, travel_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旅游产品(机票/酒店)';
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| product_id | VARCHAR(64) | ✓ | 业务可读编码主键，如 `FLT-CA1234-20260601-Y` |
| type | VARCHAR(16) | ✓ | `FLIGHT` 或 `HOTEL` |
| name | VARCHAR(128) | ✓ | 航班/房型可读名 |
| travel_date | DATE | ✓ | 出行/入住日（机票=起飞日，酒店=入住日） |
| price_cents | BIGINT | ✓ | **同业结算价（分）**，避免浮点精度问题 |
| stock | INT | ✓ | 可售余量（机票=座位数，酒店=房间数） |
| version | BIGINT | ✓ | 乐观锁版本号，`UPDATE ... WHERE version = ?` 保护并发更新 |

**索引：**
- 主键 `product_id`：业务编码直接做主键，省一次索引回表
- `idx_type_date(type, travel_date)`：常见查询「某类型某日期可售产品」

**并发安全：**
- 写入路径：`SELECT ... FOR UPDATE` 行级悲观锁（`ProductMapper.selectByIdForUpdate`）
- 兜底：MyBatis `UPDATE ... WHERE version = ?` 乐观锁更新（`ProductMapper.updateStock`），
  返回受影响行数 0 即表示并发冲突或余量不足，Service 抛 `InventoryRpcException` 触发事务回滚

---

## 4. 表：`booking` 预订单

```sql
CREATE TABLE IF NOT EXISTS `booking` (
  id                BIGINT       PRIMARY KEY AUTO_INCREMENT,
  channel           VARCHAR(32)  NOT NULL          COMMENT '渠道: CTRIP/FLIGGY/EXPEDIA',
  channel_order_no  VARCHAR(64)  NOT NULL          COMMENT '渠道方订单号(对账/幂等)',
  user_id           BIGINT       NOT NULL          COMMENT '同业账号 ID',
  product_id        VARCHAR(64)  NOT NULL          COMMENT '产品编码',
  product_type      VARCHAR(16)  NOT NULL          COMMENT 'FLIGHT / HOTEL',
  quantity          INT          NOT NULL          COMMENT '份数(机票=乘机人数, 酒店=房间数)',
  travel_date       DATE         NOT NULL          COMMENT '出行/入住日期',
  passenger_name    VARCHAR(128) NOT NULL DEFAULT '',
  passenger_id_no   VARCHAR(64)  NOT NULL DEFAULT '',
  unit_price_cents  BIGINT       NOT NULL          COMMENT '单价(分)',
  total_price_cents BIGINT       NOT NULL          COMMENT '总价(分)',
  currency          VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED'
                    COMMENT 'CREATED/TICKETED/CONFIRMED/CANCELLED/REFUNDED',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_channel_order (channel, channel_order_no),
  KEY idx_user (user_id),
  KEY idx_product_date (product_id, travel_date),
  KEY idx_status_ctime (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B2B 旅游预订单';
```

| 字段 | 说明 |
|---|---|
| id | 内部订单号，自增 |
| channel + channel_order_no | 渠道方订单号，**联合唯一**，用于对账与幂等去重 |
| user_id | 关联 `user.id` |
| product_id / product_type | 关联 `product.product_id` 及其类型快照 |
| quantity | 份数（机票=乘机人数 1-9，酒店=房间数 1-9） |
| passenger_name / passenger_id_no | 主出行人姓名（拼音/英文） + 证件号 |
| unit_price_cents / total_price_cents | 单价 × 数量，下单时从产品中心**实时取价后落库**，避免被前端篡改 |
| currency | ISO 4217，CNY / USD / JPY 等 |
| status | 订单状态机：`CREATED → TICKETED/CONFIRMED → CANCELLED/REFUNDED` |

**索引策略：**
| 索引 | 用途 |
|---|---|
| `uk_channel_order(channel, channel_order_no)` | 幂等：同一渠道单号只生成一笔预订单 |
| `idx_user(user_id)` | 客户工单查询 |
| `idx_product_date(product_id, travel_date)` | 按产品/日期对账 |
| `idx_status_ctime(status, create_time)` | 定时任务扫描超时未支付、未出票订单 |

---

## 5. 测试数据（DML）

### 5.1 同业账号
```sql
INSERT INTO `user` (id, name, channel, email) VALUES
  (1001, 'CTRIP-TRAVEL-AGENCY-A',  'CTRIP',   'a@ctrip.example'),
  (1002, 'FLIGGY-PARTNER-B',       'FLIGGY',  'b@fliggy.example'),
  (1003, 'EXPEDIA-DIST-C',         'EXPEDIA', 'c@expedia.example')
ON DUPLICATE KEY UPDATE name = VALUES(name);
```

### 5.2 旅游产品（3 机票 + 3 酒店）

| productId | type | 名称 | 日期 | 单价(分) | 初始余量 |
|---|---|---|---|---|---|
| FLT-CA1234-20260601-Y | FLIGHT | 国航 CA1234 北京→上海 Y舱 | 2026-06-01 | 89000 | 30 |
| FLT-MU5678-20260601-C | FLIGHT | 东航 MU5678 上海→东京 C舱 | 2026-06-01 | 580000 | 8 |
| FLT-SQ802-20260602-Y | FLIGHT | 新航 SQ802 新加坡→北京 Y舱 | 2026-06-02 | 320000 | 20 |
| HTL-SHA-MARRIOTT-DLX-20260601 | HOTEL | 上海万豪酒店 豪华大床房 | 2026-06-01 | 128000 | 12 |
| HTL-TYO-IMPERIAL-STE-20260602 | HOTEL | 东京帝国酒店 行政套房 | 2026-06-02 | 360000 | 5 |
| HTL-SIN-MBS-EXE-20260601 | HOTEL | 新加坡金沙酒店 行政房 | 2026-06-01 | 220000 | 18 |

```sql
INSERT INTO `product` (product_id, type, name, travel_date, price_cents, stock) VALUES
  ('FLT-CA1234-20260601-Y', 'FLIGHT', '国航 CA1234 北京→上海 Y舱',  '2026-06-01',   89000, 30),
  ('FLT-MU5678-20260601-C', 'FLIGHT', '东航 MU5678 上海→东京 C舱',  '2026-06-01',  580000,  8),
  ('FLT-SQ802-20260602-Y',  'FLIGHT', '新航 SQ802 新加坡→北京 Y舱', '2026-06-02',  320000, 20),
  ('HTL-SHA-MARRIOTT-DLX-20260601', 'HOTEL', '上海万豪酒店 豪华大床房', '2026-06-01', 128000, 12),
  ('HTL-TYO-IMPERIAL-STE-20260602', 'HOTEL', '东京帝国酒店 行政套房',   '2026-06-02', 360000,  5),
  ('HTL-SIN-MBS-EXE-20260601',      'HOTEL', '新加坡金沙酒店 行政房',   '2026-06-01', 220000, 18)
ON DUPLICATE KEY UPDATE stock = VALUES(stock), price_cents = VALUES(price_cents);
```

### 5.3 预订单（2 笔）
```sql
INSERT INTO `booking`
  (channel, channel_order_no, user_id, product_id, product_type, quantity, travel_date,
   passenger_name, passenger_id_no, unit_price_cents, total_price_cents, currency, status)
VALUES
  ('CTRIP',   'C20260520-00001', 1001, 'FLT-CA1234-20260601-Y',
   'FLIGHT', 2, '2026-06-01', 'ZHANG SAN', 'E12345678', 89000, 178000, 'CNY', 'CONFIRMED'),
  ('EXPEDIA', 'EXP-9001',        1003, 'HTL-TYO-IMPERIAL-STE-20260602',
   'HOTEL',  1, '2026-06-02', 'JOHN SMITH','G98765432',360000, 360000, 'CNY', 'CREATED')
ON DUPLICATE KEY UPDATE status = VALUES(status);
```

---

## 6. CDC 配置（Debezium）

`product` / `booking` / `user` 三张表的 binlog 都会被 Debezium 捕获，对应 Kafka 主题：

| MySQL 表 | Kafka Topic |
|---|---|
| bizdb.user | `bizdb.bizdb.user` |
| bizdb.product | `bizdb.bizdb.product` |
| bizdb.booking | `bizdb.bizdb.booking` |

详见 `infra/debezium-mysql-connector.json` 中的 `table.include.list`。

---

## 7. 常用运维 SQL

```sql
-- 查看实时库存
SELECT product_id, name, stock, price_cents FROM product
 WHERE travel_date = '2026-06-01' ORDER BY type, product_id;

-- 渠道近 24 小时下单量
SELECT channel, COUNT(*) cnt, SUM(total_price_cents)/100.0 amount_cny
  FROM booking
 WHERE create_time >= NOW() - INTERVAL 1 DAY
 GROUP BY channel;

-- 待出票（超 30 分钟未确认）订单
SELECT id, channel, channel_order_no, status, create_time
  FROM booking
 WHERE status = 'CREATED' AND create_time < NOW() - INTERVAL 30 MINUTE;

-- 释放某产品库存（人工补偿）
UPDATE product SET stock = stock + 1 WHERE product_id = 'FLT-CA1234-20260601-Y';
```

---

## 8. 端到端验证流程

```powershell
# 1. 启动基础设施（含 MySQL，首启自动执行 01-schema.sql）
docker compose up -d mysql nacos

# 2. 验证表与种子数据
docker exec -it mysql mysql -uroot -proot bizdb -e "SHOW TABLES; SELECT COUNT(*) FROM product;"

# 3. 启动微服务
mvn -pl :inventory-service,:order-service,:gateway-service spring-boot:run

# 4. 通过网关下单
curl -X POST http://localhost:8080/api/bookings -H "Content-Type: application/json" `
  -d '{"channel":"CTRIP","channelOrderNo":"C20260520-00002","userId":1001,"productId":"FLT-CA1234-20260601-Y","quantity":2,"passengerName":"LI SI","passengerIdNo":"E11111111"}'

# 5. 验证数据
docker exec -it mysql mysql -uroot -proot bizdb -e "SELECT id, channel, channel_order_no, status FROM booking;"
docker exec -it mysql mysql -uroot -proot bizdb -e "SELECT product_id, stock FROM product WHERE product_id='FLT-CA1234-20260601-Y';"
# stock 应从 30 → 28
```
