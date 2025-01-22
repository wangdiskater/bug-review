package com.example;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @Author dwang
 * @Description TODO
 * @create 2025/1/22 9:59
 * @Modified By:
 */
@SpringBootApplication
@EnableDubbo(scanBasePackages = "com.example") // 修改为你的服务实现所在的包路径
public class ProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
        System.out.println("Provider is running...");
    }
}