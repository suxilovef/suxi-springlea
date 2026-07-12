package com.sw.yang.springlea.ioc.beancreation.exp07;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 实验七：带 @Transactional 的 ServiceA，与 ServiceBWithAop 形成循环依赖。
 * <p>
 * 关键观察点：
 * <ul>
 *   <li>{@code AbstractAutoProxyCreator.getEarlyBeanReference()} — 是否提前创建代理</li>
 *   <li>{@code AbstractAutoProxyCreator.postProcessAfterInitialization()} — 普通代理创建</li>
 *   <li>B 中持有的 A 类型 → 应为代理（CGLIB/JDK）</li>
 *   <li>一级缓存中 A 的类型 → 也应为代理</li>
 * </ul>
 * <p>
 * 调试时关注：exposedObject == bean 还是代理？earlySingletonReference 是什么？
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class ServiceAWithTx {

    private static final Logger log = LoggerFactory.getLogger(ServiceAWithTx.class);

    @Autowired
    private ServiceBWithAop serviceB;

    public ServiceAWithTx() {
        log.info("ServiceAWithTx 构造器执行");
    }

    @Transactional
    public String doTransactional() {
        log.info("ServiceAWithTx.doTransactional() 执行");
        return "Tx-A";
    }

    public String callB() {
        return "A-Tx → " + serviceB.whoAmI();
    }

    public String whoAmI() {
        return "ServiceAWithTx";
    }
}
