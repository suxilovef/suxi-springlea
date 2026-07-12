package com.sw.yang.springlea.ioc.beancreation.exp03;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实验三配置：扫描当前包下的 PaymentService 实现和消费者。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp03Config.class)
public class Exp03Config {
}
