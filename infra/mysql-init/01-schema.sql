-- =====================================================================
-- 旅游同业交易平台 业务库初始化脚本
-- 容器首次启动时自动执行（docker-compose 已挂载到
--   /docker-entrypoint-initdb.d/01-schema.sql）
-- 详细字段说明请见 docs/database.md
-- =====================================================================

USE bizdb;

-- ---------------------------------------------------------------------
-- 1. 同业账号
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 2. 旅游产品（机票/酒店）
-- ---------------------------------------------------------------------
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

-- ---------------------------------------------------------------------
-- 3. 预订单
-- ---------------------------------------------------------------------
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
  status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/TICKETED/CONFIRMED/CANCELLED/REFUNDED',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_channel_order (channel, channel_order_no),
  KEY idx_user (user_id),
  KEY idx_product_date (product_id, travel_date),
  KEY idx_status_ctime (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B2B 旅游预订单';

-- ---------------------------------------------------------------------
-- 4. 种子数据
-- ---------------------------------------------------------------------
INSERT INTO `user` (id, name, channel, email) VALUES
  (1001, 'CTRIP-TRAVEL-AGENCY-A',  'CTRIP',   'a@ctrip.example'),
  (1002, 'FLIGGY-PARTNER-B',       'FLIGGY',  'b@fliggy.example'),
  (1003, 'EXPEDIA-DIST-C',         'EXPEDIA', 'c@expedia.example')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO `product` (product_id, type, name, travel_date, price_cents, stock) VALUES
  -- 机票
  ('FLT-CA1234-20260601-Y', 'FLIGHT', '国航 CA1234 北京→上海 Y舱',  '2026-06-01',   89000, 30),
  ('FLT-MU5678-20260601-C', 'FLIGHT', '东航 MU5678 上海→东京 C舱',  '2026-06-01',  580000,  8),
  ('FLT-SQ802-20260602-Y',  'FLIGHT', '新航 SQ802 新加坡→北京 Y舱', '2026-06-02',  320000, 20),
  -- 酒店
  ('HTL-SHA-MARRIOTT-DLX-20260601', 'HOTEL', '上海万豪酒店 豪华大床房', '2026-06-01', 128000, 12),
  ('HTL-TYO-IMPERIAL-STE-20260602', 'HOTEL', '东京帝国酒店 行政套房',   '2026-06-02', 360000,  5),
  ('HTL-SIN-MBS-EXE-20260601',      'HOTEL', '新加坡金沙酒店 行政房',   '2026-06-01', 220000, 18)
ON DUPLICATE KEY UPDATE stock = VALUES(stock), price_cents = VALUES(price_cents);

INSERT INTO `booking`
  (channel, channel_order_no, user_id, product_id, product_type, quantity, travel_date,
   passenger_name, passenger_id_no, unit_price_cents, total_price_cents, currency, status)
VALUES
  ('CTRIP',   'C20260520-00001', 1001, 'FLT-CA1234-20260601-Y',
   'FLIGHT', 2, '2026-06-01', 'ZHANG SAN', 'E12345678', 89000, 178000, 'CNY', 'CONFIRMED'),
  ('EXPEDIA', 'EXP-9001',        1003, 'HTL-TYO-IMPERIAL-STE-20260602',
   'HOTEL',  1, '2026-06-02', 'JOHN SMITH','G98765432',360000, 360000, 'CNY', 'CREATED')
ON DUPLICATE KEY UPDATE status = VALUES(status);

-- ---------------------------------------------------------------------
-- 5. Debezium 账号权限
-- ---------------------------------------------------------------------
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
