package com.sw.yang.springlea.ioc.beancreation.exp06;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实验六配置：扫描 ServiceA 和 ServiceB。
 * <p>
 * 注意：循环依赖需要 {@code spring.main.allow-circular-references=true}，
 * 测试中通过 {@code DefaultListableBeanFactory.setAllowCircularReferences(true)} 开启。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp06Config.class)
public class Exp06Config {
}
