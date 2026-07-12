package com.sw.yang.springlea.ioc.configurationclass.exp02;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 实验二：DeferredImportSelector 延迟导入的目标配置类。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Version: 1.0
 */
@Configuration(proxyBeanMethods = false)
public class DeferredImportedConfig {

    @Bean
    DeferredBean deferredBean() {
        return new DeferredBean();
    }
}
