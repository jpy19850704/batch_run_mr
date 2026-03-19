package com.zcyh.mr.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 应用启动入口。
 */
@SpringBootApplication(scanBasePackages = "com.zcyh.mr")
public class MrSpringBootApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrSpringBootApplication.class, args);
    }
}

