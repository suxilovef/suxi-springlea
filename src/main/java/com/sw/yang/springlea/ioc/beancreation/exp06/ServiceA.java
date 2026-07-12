package com.sw.yang.springlea.ioc.beancreation.exp06;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 循环依赖实验 — 服务 A。
 * <p>
 * 调试断点建议（条件：beanName.equals("serviceA") || beanName.equals("serviceB")）：
 * <ol>
 *   <li>{@code DefaultSingletonBeanRegistry.getSingleton(String, boolean)} — 观察三级缓存查询</li>
 *   <li>{@code AbstractAutowireCapableBeanFactory.doCreateBean()} — 观察 addSingletonFactory 和 populateBean</li>
 *   <li>{@code DefaultSingletonBeanRegistry.addSingleton()} — 观察入一级缓存</li>
 * </ol>
 * <p>
 * 关键观察变量：singletonObjects、earlySingletonObjects、singletonFactories、singletonsCurrentlyInCreation
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class ServiceA {

    private static final Logger log = LoggerFactory.getLogger(ServiceA.class);

    @Autowired
    private ServiceB serviceB;

    public ServiceA() {
        log.info("ServiceA 构造器执行");
    }

    public String whoAmI() {
        return "ServiceA";
    }

    public String callB() {
        return "A → " + serviceB.whoAmI();
    }
}
