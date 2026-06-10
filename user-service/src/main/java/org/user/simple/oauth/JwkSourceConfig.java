package org.user.simple.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * JWT 签名密钥工厂：替换 {@link AuthorizationServerConfig} 中"启动时随机生成 RSA"的开发实现。
 *
 * <p>三种模式（{@link OAuth2KeyProperties.Source}）：</p>
 * <ul>
 *   <li>{@code RANDOM}   —— 开发默认；进程内随机生成，重启换 kid，<b>存量 token 全部失效</b></li>
 *   <li>{@code KEYSTORE} —— 生产推荐；PKCS12/JKS 文件 + 密码（来自 K8s Secret / Vault / KMS 解密）</li>
 *   <li>{@code PEM}      —— PEM 私钥 + 公钥两个文件；适合 cert-manager / Let's Encrypt 风格的密钥发放</li>
 * </ul>
 *
 * <p>本类不直接调 AWS KMS，而是约定 <b>"KMS 把密钥/密码投递成文件"</b>这一通用接口
 * （Vault Agent、External Secrets Operator、kms decrypt + tmpfs 都遵循它），
 * 避免应用代码绑定特定云厂商。如需真正"私钥永不出 KMS"，需要替换
 * {@code JwtEncoder} 用 KMS-backed 签名器（属高合规场景，不在本 demo 范围）。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(OAuth2KeyProperties.class)
public class JwkSourceConfig {


    @Bean
    public JWKSource<SecurityContext> jwkSource(OAuth2KeyProperties props,
                                                ResourceLoader resourceLoader) {
        try {
            RSAKey rsaJwk = switch (props.getSource()) {
                case KEYSTORE -> loadFromKeystore(props, resourceLoader);
                case PEM      -> loadFromPem(props, resourceLoader);
                case RANDOM   -> generateRandom(props);
            };
            log.info("[OAuth2] JWT signing key loaded: source={}, kid={}",
                    props.getSource(), rsaJwk.getKeyID());
            return new ImmutableJWKSet<>(new JWKSet(rsaJwk));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init JWT signing key", e);
        }
    }

    // ---------- keystore (PKCS12 / JKS) ----------
    private RSAKey loadFromKeystore(OAuth2KeyProperties props, ResourceLoader rl) throws Exception {
        OAuth2KeyProperties.Keystore ks = props.getKeystore();
        if (ks.getLocation() == null) {
            throw new IllegalStateException("oauth2.key.keystore.location is required when source=keystore");
        }
        Resource res = rl.getResource(ks.getLocation());
        // PKCS12 是跨平台事实标准；如果文件后缀 .jks 则按 JKS 加载
        String type = ks.getLocation().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = res.getInputStream()) {
            store.load(in, ks.getPassword() == null ? null : ks.getPassword().toCharArray());
        }
        String alias = ks.getAlias() != null ? ks.getAlias() : firstAlias(store);
        char[] keyPwd = (ks.getKeyPassword() != null ? ks.getKeyPassword() : ks.getPassword()).toCharArray();
        PrivateKey priv = (PrivateKey) store.getKey(alias, keyPwd);
        Certificate cert = store.getCertificate(alias);
        if (priv == null || cert == null) {
            throw new IllegalStateException("Alias '" + alias + "' not found in keystore " + ks.getLocation());
        }
        return new RSAKey.Builder((RSAPublicKey) cert.getPublicKey())
                .privateKey((RSAPrivateKey) priv)
                .keyID(props.getKeyId())
                .build();
    }

    private static String firstAlias(KeyStore store) throws Exception {
        var aliases = store.aliases();
        if (!aliases.hasMoreElements()) {
            throw new IllegalStateException("Keystore is empty");
        }
        return aliases.nextElement();
    }

    // ---------- PEM (PKCS8 private + X509 public) ----------
    private RSAKey loadFromPem(OAuth2KeyProperties props, ResourceLoader rl) throws Exception {
        OAuth2KeyProperties.Pem pem = props.getPem();
        if (pem.getPrivateLocation() == null || pem.getPublicLocation() == null) {
            throw new IllegalStateException("oauth2.key.pem.{private,public}-location are required when source=pem");
        }
        byte[] privDer = readPemBytes(rl.getResource(pem.getPrivateLocation()));
        byte[] pubDer  = readPemBytes(rl.getResource(pem.getPublicLocation()));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(privDer));
        PublicKey  pub  = kf.generatePublic(new X509EncodedKeySpec(pubDer));
        return new RSAKey.Builder((RSAPublicKey) pub)
                .privateKey((RSAPrivateKey) priv)
                .keyID(props.getKeyId())
                .build();
    }

    /** 去掉 ----BEGIN/END---- 头尾后 base64 解码；同时容忍 Windows 换行 */
    private static byte[] readPemBytes(Resource res) throws Exception {
        String text;
        try (InputStream in = res.getInputStream()) {
            text = new String(in.readAllBytes());
        }
        String body = text.replaceAll("-----BEGIN [^-]+-----", "")
                          .replaceAll("-----END [^-]+-----", "")
                          .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    // ---------- random（仅开发） ----------
    private RSAKey generateRandom(OAuth2KeyProperties props) throws Exception {
        log.warn("[OAuth2] !!! Using RANDOM RSA key — restart will INVALIDATE all live tokens. " +
                "Set oauth2.key.source=keystore|pem in production.");
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        String kid = (props.getKeyId() == null || props.getKeyId().isBlank())
                ? UUID.randomUUID().toString()
                : props.getKeyId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                .privateKey((RSAPrivateKey) kp.getPrivate())
                .keyID(kid)
                .build();
    }
}
