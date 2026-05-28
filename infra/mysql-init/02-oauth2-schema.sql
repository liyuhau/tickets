-- ================================================================
-- Spring Authorization Server 1.2.x 持久化所需的 3 张表
-- 见官方源码：
--   org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql
--   org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql
--   org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql
-- 业务库 bizdb 首启时会被 docker-entrypoint-initdb.d 自动执行
-- ================================================================

USE bizdb;

-- ----- 1. 颁发出去的授权（含 access_token / refresh_token / 吊销标记） -----
CREATE TABLE IF NOT EXISTS oauth2_authorization (
    id                              VARCHAR(100) NOT NULL,
    registered_client_id            VARCHAR(100) NOT NULL,
    principal_name                  VARCHAR(200) NOT NULL,
    authorization_grant_type        VARCHAR(100) NOT NULL,
    authorized_scopes               VARCHAR(1000) DEFAULT NULL,
    attributes                      BLOB         DEFAULT NULL,
    state                           VARCHAR(500) DEFAULT NULL,
    authorization_code_value        BLOB         DEFAULT NULL,
    authorization_code_issued_at    TIMESTAMP    NULL DEFAULT NULL,
    authorization_code_expires_at   TIMESTAMP    NULL DEFAULT NULL,
    authorization_code_metadata     BLOB         DEFAULT NULL,
    access_token_value              BLOB         DEFAULT NULL,
    access_token_issued_at          TIMESTAMP    NULL DEFAULT NULL,
    access_token_expires_at         TIMESTAMP    NULL DEFAULT NULL,
    access_token_metadata           BLOB         DEFAULT NULL,
    access_token_type               VARCHAR(100) DEFAULT NULL,
    access_token_scopes             VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value             BLOB         DEFAULT NULL,
    oidc_id_token_issued_at         TIMESTAMP    NULL DEFAULT NULL,
    oidc_id_token_expires_at        TIMESTAMP    NULL DEFAULT NULL,
    oidc_id_token_metadata          BLOB         DEFAULT NULL,
    refresh_token_value             BLOB         DEFAULT NULL,
    refresh_token_issued_at         TIMESTAMP    NULL DEFAULT NULL,
    refresh_token_expires_at        TIMESTAMP    NULL DEFAULT NULL,
    refresh_token_metadata          BLOB         DEFAULT NULL,
    user_code_value                 BLOB         DEFAULT NULL,
    user_code_issued_at             TIMESTAMP    NULL DEFAULT NULL,
    user_code_expires_at            TIMESTAMP    NULL DEFAULT NULL,
    user_code_metadata              BLOB         DEFAULT NULL,
    device_code_value               BLOB         DEFAULT NULL,
    device_code_issued_at           TIMESTAMP    NULL DEFAULT NULL,
    device_code_expires_at          TIMESTAMP    NULL DEFAULT NULL,
    device_code_metadata            BLOB         DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'OAuth2 颁发出去的授权记录（包含吊销信息）';

-- ----- 2. 用户对客户端的授权同意（authorization_code 流程用） -----
CREATE TABLE IF NOT EXISTS oauth2_authorization_consent (
    registered_client_id  VARCHAR(100) NOT NULL,
    principal_name        VARCHAR(200) NOT NULL,
    authorities           VARCHAR(1000) NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'OAuth2 用户授权同意记录';

-- ----- 3. 已注册的 OAuth2 客户端（DB 化方案；本项目仍走 InMemory，预留这张表） -----
CREATE TABLE IF NOT EXISTS oauth2_registered_client (
    id                            VARCHAR(100) NOT NULL,
    client_id                     VARCHAR(100) NOT NULL,
    client_id_issued_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_secret                 VARCHAR(200) DEFAULT NULL,
    client_secret_expires_at      TIMESTAMP    NULL DEFAULT NULL,
    client_name                   VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types     VARCHAR(1000) NOT NULL,
    redirect_uris                 VARCHAR(1000) DEFAULT NULL,
    post_logout_redirect_uris     VARCHAR(1000) DEFAULT NULL,
    scopes                        VARCHAR(1000) NOT NULL,
    client_settings               VARCHAR(2000) NOT NULL,
    token_settings                VARCHAR(2000) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'OAuth2 已注册客户端';
