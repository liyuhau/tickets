-- =====================================================================
-- 分库分表初始化脚本
-- 创建 bizdb_0 / bizdb_1 两个库，每个库各 booking_0 / booking_1 两张表
-- 共 4 个物理分片，按 user_id 取模路由
-- =====================================================================

-- ======================== 库 0 ========================
CREATE DATABASE IF NOT EXISTS bizdb_0 DEFAULT CHARSET=utf8mb4;
USE bizdb_0;

CREATE TABLE IF NOT EXISTS `booking_0` (
  id                BIGINT       PRIMARY KEY              COMMENT '雪花算法 ID',
  channel           VARCHAR(32)  NOT NULL,
  channel_order_no  VARCHAR(64)  NOT NULL,
  user_id           BIGINT       NOT NULL,
  product_id        VARCHAR(64)  NOT NULL,
  product_type      VARCHAR(16)  NOT NULL,
  quantity          INT          NOT NULL,
  travel_date       DATE         NOT NULL,
  passenger_name    VARCHAR(128) NOT NULL DEFAULT '',
  passenger_id_no   VARCHAR(64)  NOT NULL DEFAULT '',
  unit_price_cents  BIGINT       NOT NULL,
  total_price_cents BIGINT       NOT NULL,
  currency          VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_channel_order (channel, channel_order_no),
  KEY idx_user (user_id),
  KEY idx_product_date (product_id, travel_date),
  KEY idx_status_ctime (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预订单分片 0';

CREATE TABLE IF NOT EXISTS `booking_1` LIKE `booking_0`;

-- ======================== 库 1 ========================
CREATE DATABASE IF NOT EXISTS bizdb_1 DEFAULT CHARSET=utf8mb4;
USE bizdb_1;

CREATE TABLE IF NOT EXISTS `booking_0` (
  id                BIGINT       PRIMARY KEY              COMMENT '雪花算法 ID',
  channel           VARCHAR(32)  NOT NULL,
  channel_order_no  VARCHAR(64)  NOT NULL,
  user_id           BIGINT       NOT NULL,
  product_id        VARCHAR(64)  NOT NULL,
  product_type      VARCHAR(16)  NOT NULL,
  quantity          INT          NOT NULL,
  travel_date       DATE         NOT NULL,
  passenger_name    VARCHAR(128) NOT NULL DEFAULT '',
  passenger_id_no   VARCHAR(64)  NOT NULL DEFAULT '',
  unit_price_cents  BIGINT       NOT NULL,
  total_price_cents BIGINT       NOT NULL,
  currency          VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED',
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_channel_order (channel, channel_order_no),
  KEY idx_user (user_id),
  KEY idx_product_date (product_id, travel_date),
  KEY idx_status_ctime (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预订单分片 0';

CREATE TABLE IF NOT EXISTS `booking_1` LIKE `booking_0`;

-- Debezium 权限（分库也要授权）
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
