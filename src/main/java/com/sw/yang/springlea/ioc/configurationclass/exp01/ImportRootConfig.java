package com.sw.yang.springlea.ioc.configurationclass.exp01;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Description: 实验一 - 验证 @Import 的三个分支（普通类、ImportSelector、ImportBeanDefinitionRegistrar）
 * @Version: 1.0
 *
 * PlainImportedConfig：普通类分支
 * TraceImportSelector：ImportSelector 类分支
 * TraceRegistrar：ImportBeanDefinitionRegistrar 分支
 */
@Configuration
@Import({
        PlainImportedConfig.class,
        TraceImportSelector.class,
        TraceRegistrar.class
})
public class ImportRootConfig {

}
