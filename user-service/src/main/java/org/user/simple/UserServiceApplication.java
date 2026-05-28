package org.user.simple;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * User Service 启动类。
 * <p>职责：</p>
 * <ul>
 *   <li>OAuth2 Authorization Server，颁发 JWT</li>
 *   <li>同业账号 / 客户端注册管理（演示用静态注册，可扩展为 DB 持久化）</li>
 * </ul>
 */
@SpringBootApplication
@MapperScan("org.user.simple.auth")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
