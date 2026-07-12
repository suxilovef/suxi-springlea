package com.sw.yang.springlea.ioc.configurationclass.exp01;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Description:
 * @Version: 1.0
 */
@Configuration(proxyBeanMethods = false)
public class PlainImportedConfig {
    @Bean
    PlainBean plainBean(){
        return new PlainBean();
    }
}
