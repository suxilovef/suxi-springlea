package com.sw.yang.springlea.ioc.beancreation.exp07;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ServiceB：与 ServiceAWithTx 形成循环依赖。
 * <p>
 * B 在 populateBean 阶段注入 A，此时如果 A 有 @Transactional，
 * AbstractAutoProxyCreator.getEarlyBeanReference() 会提前为 A 创建代理。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class ServiceBWithAop {

    private static final Logger log = LoggerFactory.getLogger(ServiceBWithAop.class);

    @Autowired
    private ServiceAWithTx serviceA;

    public ServiceBWithAop() {
        log.info("ServiceBWithAop 构造器执行");
    }

    public String callA() {
        return "B → " + serviceA.whoAmI();
    }

    /**
     * 返回持有的 A 引用，用于检查是否为代理对象。
     */
    public ServiceAWithTx getServiceA() {
        return serviceA;
    }

    public String whoAmI() {
        return "ServiceBWithAop";
    }
}
