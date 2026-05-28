package org.user.simple.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth2 签名密钥来源配置。生产严禁用 random（重启换 key 导致存量 JWT 全部失效）。
 *
 * <pre>
 * oauth2:
 *   key:
 *     source: keystore                                  # random | keystore | pem
 *     key-id: jwt-signing-key-2026                       # JWK kid（建议带轮换日期）
 *     keystore:
 *       location: file:///etc/oauth2/jwt-signing.p12     # K8s Secret 挂载，或 Vault Agent 投递
 *       password: ${KEYSTORE_PASSWORD}                   # 走环境变量，禁止明文落盘
 *       alias:    jwt-signing
 *       key-password: ${KEYSTORE_KEY_PASSWORD:${KEYSTORE_PASSWORD}}
 *     pem:
 *       private-location: file:///etc/oauth2/jwt-private.pem
 *       public-location:  file:///etc/oauth2/jwt-public.pem
 * </pre>
 *
 * <h3>生产部署：从 KMS / Vault 拿到密钥的几种姿势</h3>
 * <ol>
 *   <li><b>HashiCorp Vault Agent</b>：Sidecar 把 secret 渲染到本地文件，应用按 keystore/pem 加载</li>
 *   <li><b>K8s External Secrets</b>：从 AWS Secrets Manager / GCP Secret Manager 同步到 K8s Secret，挂载为文件</li>
 *   <li><b>AWS KMS</b>：启动时调 KMS Decrypt 解出 keystore 密码（或 envelope-encrypted 私钥），写入临时文件</li>
 *   <li><b>真正的 HSM/KMS 签名</b>：私钥永不出 KMS，写一个 KMS-backed {@link com.nimbusds.jose.crypto.RSASSASigner}
 *       的实现，每次签名都调远程 API；性能损失大，仅金融/医疗合规场景使用</li>
 * </ol>
 */
@ConfigurationProperties(prefix = "oauth2.key")
public class OAuth2KeyProperties {

    /** 密钥来源；random 仅开发，生产必须 keystore 或 pem */
    private Source source = Source.RANDOM;

    /** JWK kid 标识；建议命名形如 jwt-2026-q2，方便观察轮换 */
    private String keyId = "jwt-signing-key";

    private Keystore keystore = new Keystore();
    private Pem pem = new Pem();

    public enum Source { RANDOM, KEYSTORE, PEM }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public Keystore getKeystore() { return keystore; }
    public void setKeystore(Keystore keystore) { this.keystore = keystore; }
    public Pem getPem() { return pem; }
    public void setPem(Pem pem) { this.pem = pem; }

    public static class Keystore {
        /** 支持 Spring Resource 写法：file:/// / classpath: 等 */
        private String location;
        private String password;
        private String alias;
        private String keyPassword;

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getAlias() { return alias; }
        public void setAlias(String alias) { this.alias = alias; }
        public String getKeyPassword() { return keyPassword; }
        public void setKeyPassword(String keyPassword) { this.keyPassword = keyPassword; }
    }

    public static class Pem {
        private String privateLocation;
        private String publicLocation;

        public String getPrivateLocation() { return privateLocation; }
        public void setPrivateLocation(String privateLocation) { this.privateLocation = privateLocation; }
        public String getPublicLocation() { return publicLocation; }
        public void setPublicLocation(String publicLocation) { this.publicLocation = publicLocation; }
    }
}
