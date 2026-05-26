package com.zcyh.mr.basic.util;

import org.slf4j.Logger;

/**
 * 日志配置类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class MRLib {
    static Logger logger ;

    public final static void setLogger(final Logger logger) {
        MRLib.logger = logger;
    }
}
