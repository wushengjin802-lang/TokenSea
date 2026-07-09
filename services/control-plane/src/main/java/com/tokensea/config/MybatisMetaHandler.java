package com.tokensea.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;

@Component
public class MybatisMetaHandler implements MetaObjectHandler {
    @Override public void insertFill(MetaObject metaObject) {
        strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, OffsetDateTime.now());
        strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
    }
    @Override public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
    }
}
