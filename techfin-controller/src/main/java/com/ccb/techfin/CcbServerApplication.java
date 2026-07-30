package com.ccb.techfin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 *
 * @author qiuhaoquan
 * @since 2026-07-23
 */
@SpringBootApplication(scanBasePackages = "com.ccb.techfin")
@EnableScheduling
public class CcbServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcbServerApplication.class, args);
    }
}
