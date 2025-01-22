package com.example.provider;

import com.exmaple.api.GreetingService;
import org.apache.dubbo.config.annotation.Service;
/**
 * @Author dwang
 * @Description TODO
 * @create 2025/1/22 9:58
 * @Modified By:
 */


@Service() // 暴露服务
public class GreetingServiceImpl implements GreetingService {
    @Override
    public String sayHello(String name) {
        return "Hello, " + name;
    }
}
