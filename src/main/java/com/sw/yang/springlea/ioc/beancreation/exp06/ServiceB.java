package com.sw.yang.springlea.ioc.beancreation.exp06;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 循环依赖实验 — 服务 B。
 * <p>
 * B 在 populateBean 阶段触发对 A 的依赖解析，此时 A 尚未完成创建，
 * 因此会触发三级缓存机制。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class ServiceB {

    private static final Logger log = LoggerFactory.getLogger(ServiceB.class);

    @Autowired
    private ServiceA serviceA;

    public ServiceB() {
        log.info("ServiceB 构造器执行");
    }

    public String whoAmI() {
        return "ServiceB";
    }

    public String callA() {
        return "B → " + serviceA.whoAmI();
    }
}
