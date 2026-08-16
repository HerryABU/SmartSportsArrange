package com.sports.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置 — 配合 open-in-view 处理 Hibernate 懒加载字段的 JSON 序列化
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        Hibernate6Module module = new Hibernate6Module();
        // 移除FORCE_LAZY_LOADING以避免N+1查询问题
        // 懒加载应在事务边界内处理，而不是在Jackson序列化时强制加载
        return module;
    }
}
