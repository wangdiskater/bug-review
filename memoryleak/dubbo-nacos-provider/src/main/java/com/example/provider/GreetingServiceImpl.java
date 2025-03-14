package com.example.provider;

import com.exmaple.api.GreetingService;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * @ClassName GreetingServiceImpl
 * @Description TODO
 * @Author Dwang
 * @Date 2025/3/14 10:38
 * @Update
 */
@DubboService
public class GreetingServiceImpl implements GreetingService {
    @Override
    public String sayHello(String name) {
        return "Hello, " + name;
    }
}
