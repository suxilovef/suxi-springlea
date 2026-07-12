package com.sw.yang.springlea.ioc.beancreation.exp04;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实验四配置：扫描 CarFactoryBean。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp04Config.class)
public class Exp04Config {
}
