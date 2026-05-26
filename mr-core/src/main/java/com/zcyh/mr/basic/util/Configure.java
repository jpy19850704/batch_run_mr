package com.zcyh.mr.basic.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 读取配置文件通用类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/17 9:07
 */

public class Configure {
    private static final Logger log = LoggerFactory.getLogger(Configure.class);
    private static Properties config = null;

    private volatile static Configure instance = null;

    private Configure() {
        String fn = "config.properties";
        init(fn);
    }

    public static Configure getInstance() {
        if (instance == null) {
            synchronized (Configure.class) {
                if (instance == null) {
                    instance = new Configure();
                }
            }
        }
        return instance;
    }

    public void init(String filePath) {
        config = new Properties();
        try {
            ClassLoader CL = this.getClass()
                                 .getClassLoader();
            InputStream in;
            if (CL != null) {
                in = CL.getResourceAsStream(filePath);
            } else {
                in = ClassLoader.getSystemResourceAsStream(filePath);
            }
            config.load(in);
            if (in != null) {
                in.close();
            }
        } catch (FileNotFoundException e) {
            log.warn("配置文件未找到: {}", filePath);
        } catch (Exception e) {
            log.error("配置文件读取失败: {}", filePath, e);
        }
    }


    public String getValue(String key) {
        if (config == null) {
            config = new Properties();
        }
        if (config.containsKey(key)) {
            String value = config.getProperty(key);
            return value;
        } else {
            return "";
        }
    }

    public void setValue(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        if (config == null) {
            config = new Properties();
        }
        if (value == null) {
            config.remove(key);
        } else {
            config.setProperty(key, value);
        }
    }

    public int getValueInt(String key) {
        String value = getValue(key);
        int valueInt = 0;
        try {
            valueInt = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("配置项转换为整数失败: key={}, value={}", key, value, e);
            return valueInt;
        }
        return valueInt;
    }
}
