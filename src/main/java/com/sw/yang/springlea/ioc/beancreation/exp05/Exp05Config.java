package com.sw.yang.springlea.ioc.beancreation.exp05;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 实验五配置：扫描 ExportService 和 ExportTask。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp05Config.class)
public class Exp05Config {
}
