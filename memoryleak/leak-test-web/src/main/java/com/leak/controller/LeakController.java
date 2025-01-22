package com.leak.controller;

//import com.leak.remote.HsmSjjRemoteImpl;
import com.leak.remote.HsmSjjRemote;
import com.leak.remote.HsmSjjRemoteImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * @Author dwang
 * @Description TODO
 * @create 2025/1/21 18:35
 * @Modified By:
 */
@RestController
@RequestMapping("/")
public class LeakController {


    @Resource
    private HsmSjjRemote hsmSjjRemote;

    @GetMapping("leak")
    public String leak() {
        byte[] bytes = new byte[2];
        byte[] send = hsmSjjRemote.send(bytes);
        return new String(send);
    }
}
