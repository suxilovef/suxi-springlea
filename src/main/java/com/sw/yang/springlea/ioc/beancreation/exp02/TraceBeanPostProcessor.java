package com.sw.yang.springlea.ioc.beancreation.exp02;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 自定义 BeanPostProcessor，用于观察初始化前/后两个回调的位置。
 * <p>
 * 注意：此 Processor 在 {@link LifecycleBean} 之前创建，因此不会干扰 LifecycleBean 的创建。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Component
public class TraceBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(TraceBeanPostProcessor.class);

    @Override
    @Nullable
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof LifecycleBean) {
            log.info("[BPP-before] postProcessBeforeInitialization() → beanName = {}", beanName);
        }
        return bean;
    }

    @Override
    @Nullable
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof LifecycleBean) {
            log.info("[8] [BPP-after] postProcessAfterInitialization() → beanName = {}", beanName);
        }
        return bean;
    }
}
