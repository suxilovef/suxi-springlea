package com.sw.yang.springlea.ioc.configurationclass.exp01;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.NonNull;

/**
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Description:
 * @Version: 1.0
 */
public class TraceRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(
            @NonNull AnnotationMetadata metadata,
            BeanDefinitionRegistry registry) {
        registry.registerBeanDefinition(
                "registrarBean",
                new RootBeanDefinition(RegistrarBean.class));
    }

}
