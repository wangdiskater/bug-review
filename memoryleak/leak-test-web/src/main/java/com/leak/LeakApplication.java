package com.leak;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @Author dwang
 * @Description TODO
 * @create 2025/1/21 18:35
 * @Modified By:
 */
@SpringBootApplication()
@EnableDubbo
public class LeakApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeakApplication.class,args);
    }


}
