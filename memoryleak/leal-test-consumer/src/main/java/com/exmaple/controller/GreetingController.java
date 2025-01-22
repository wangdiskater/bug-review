package com.exmaple.controller;

import com.exmaple.api.GreetingService;
import org.apache.dubbo.config.annotation.Reference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author dwang
 * @Description TODO
 * @create 2025/1/22 10:24
 * @Modified By:
 */
@RestController
public class GreetingController {

    @Reference(
            url = "dubbo://127.0.0.1:20880" // 直连提供者的 URL

    )
    private GreetingService greetingService;

    @GetMapping("/hello")
    public String sayHello(@RequestParam String name) {
        return greetingService.sayHello(name);
    }
}