package com.sw.yang.springlea.ioc.beancreation.exp02;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实验二配置：注册 LifecycleBean 并指定自定义 init-method。
 * <p>
 * 使用 @Bean(initMethod = "customInit") 而非 @Service，便于控制初始化方法声明。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp02Config.class)
public class Exp02Config {

    @Bean(initMethod = "customInit")
    public LifecycleBean lifecycleBean() {
        return new LifecycleBean();
    }
}
