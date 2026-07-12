package com.sw.yang.springlea.ioc.beancreation.exp04;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.stereotype.Component;

/**
 * 实验四核心：Car 的 FactoryBean。
 * <p>
 * 关键点：
 * <ul>
 *   <li>{@code context.getBean("carFactory")} → 返回 {@link Car}（产品对象）</li>
 *   <li>{@code context.getBean("&carFactory")} → 返回 {@link CarFactoryBean}（工厂本身）</li>
 * </ul>
 * <p>
 * 断点观察：
 * <ul>
 *   <li>{@code AbstractBeanFactory.getObjectForBeanInstance()} — 观察 FactoryBean 解引用逻辑</li>
 *   <li>{@code FactoryBeanRegistrySupport.getObjectFromFactoryBean()} — 观察产品对象获取与缓存</li>
 *   <li>观察 {@code singletonObjects} 与 {@code factoryBeanObjectCache} 的区别</li>
 * </ul>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Component("carFactory")
public class CarFactoryBean implements FactoryBean<Car> {

    private static final Logger log = LoggerFactory.getLogger(CarFactoryBean.class);

    @Override
    public Car getObject() {
        log.info("CarFactoryBean.getObject() 被调用 → 创建 Car 产品");
        return new Car("Tesla Model Y");
    }

    @Override
    public Class<?> getObjectType() {
        return Car.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
