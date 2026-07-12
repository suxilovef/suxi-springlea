package com.sw.yang.springlea.ioc.beancreation.exp01;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实验一配置：扫描当前包下的 DemoService 和 UserService。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp01Config.class)
public class Exp01Config {
}
