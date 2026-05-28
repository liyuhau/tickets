package org.user.simple.auth;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 同业账号 Mapper：仅做登录场景的极简查询。
 * <p>注：演示项目不存储密码字段；生产应增加 password_hash + salt，并对接 SSO/OAuth2。</p>
 */
@Mapper
public interface AuthUserMapper {

    @Select("SELECT id, name, channel, status FROM user WHERE name = #{name} LIMIT 1")
    Map<String, Object> findByName(@Param("name") String name);
}
