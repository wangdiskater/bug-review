package com.exmaple;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @Author dwang
 * @Description TODO
 * @create 2025/1/22 10:25
 * @Modified By:
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.exmaple") // 替换为你的包路径
public class ConsumerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
