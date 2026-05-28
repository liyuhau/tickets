package org.common.mp;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Instant;

/**
 * MyBatis-Plus 自动填充：
 * <ul>
 *   <li>INSERT 时：create_time + update_time</li>
 *   <li>UPDATE 时：update_time</li>
 * </ul>
 * 业务 Entity 字段标 {@code @TableField(fill = INSERT/INSERT_UPDATE)} 即可生效。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATE_TIME = "createTime";
    private static final String UPDATE_TIME = "updateTime";

    @Override
    public void insertFill(MetaObject meta) {
        Instant now = Instant.now();
        strictInsertFill(meta, CREATE_TIME, Instant.class, now);
        strictInsertFill(meta, UPDATE_TIME, Instant.class, now);
    }

    @Override
    public void updateFill(MetaObject meta) {
        strictUpdateFill(meta, UPDATE_TIME, Instant.class, Instant.now());
    }
}
