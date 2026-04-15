package com.zcyh.mr.springboot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * 运行时路径属性桥接配置。
 * 将 Spring 配置同步到 System Property，供 mr-core 非 Spring 类读取。
 */
@Configuration
public class RuntimePathPropertyConfig {

    @Value("${mr.sobol.cache.dir:./data/sobol-cache}")
    private String sobolCacheDir;

    @Value("${mr.mc.path.dir:./data/mc_path}")
    private String mcPathDir;

    @PostConstruct
    public void applyRuntimePathProperties() {
        applyIfNotBlank("mr.sobol.cache.dir", sobolCacheDir);
        applyIfNotBlank("mr.mc.path.dir", mcPathDir);
    }

    private void applyIfNotBlank(String key, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        System.setProperty(key, trimmed);
    }
}
