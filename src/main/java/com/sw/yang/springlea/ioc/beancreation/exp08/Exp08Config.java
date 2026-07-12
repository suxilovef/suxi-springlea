package com.sw.yang.springlea.ioc.beancreation.exp08;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实验八配置：扫描 FaultyBean、HealthyBean 和 DependentOnFaultyBean。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp08Config.class)
public class Exp08Config {
}
