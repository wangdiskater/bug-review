package com.leak.remote;

import org.apache.dubbo.config.annotation.Reference;
import org.springframework.stereotype.Component;

/**
 * 远程调用实现，装饰器模式
 * @author liaoShiRong
 * @date 2023/9/19
 */
@Component("hsmSjjRemoteImpl")
public class HsmSjjRemoteImpl implements HsmSjjRemote{

    @Reference(url = "${hsm.url}",check = false,loadbalance = "${hsm.loadbalance}")
    private HsmSjjRemote hsmSjjRemote;


    @Override
    public byte[] send(byte[] data) {
        return hsmSjjRemote.send(data);
    }
}
