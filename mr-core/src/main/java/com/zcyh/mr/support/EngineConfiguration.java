package com.zcyh.mr.support;

import java.util.Properties;

/**
 * 读取配置文件通用类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/17 9:07
 */

public class EngineConfiguration {
    private static final Properties config = new Properties();

    private volatile static EngineConfiguration instance = null;

    private EngineConfiguration() {
    }

    public static EngineConfiguration getInstance() {
        if (instance == null) {
            synchronized (EngineConfiguration.class) {
                if (instance == null) {
                    instance = new EngineConfiguration();
                }
            }
        }
        return instance;
    }

    public String getValue(String key) {
        if (config.containsKey(key)) {
            return config.getProperty(key);
        }
        return "";
    }

    public void setValue(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        if (value == null) {
            config.remove(key);
        } else {
            config.setProperty(key, value);
        }
    }

    public boolean getRequiredBoolean(String key) {
        String value = getValue(key);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("运行时布尔配置缺失或非法: " + key);
    }
}
