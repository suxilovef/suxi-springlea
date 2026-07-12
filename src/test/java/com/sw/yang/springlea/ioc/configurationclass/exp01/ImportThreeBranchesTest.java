package com.sw.yang.springlea.ioc.configurationclass.exp01;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验一：验证 @Import 的三个分支
 * <p>
 * 同时观察普通导入类、ImportSelector 和 ImportBeanDefinitionRegistrar 三种导入方式。
 * 预期三个 Bean 都能在容器中找到。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Version: 1.0
 */
class ImportThreeBranchesTest {

    @Test
    void shouldRegisterBeansViaAllThreeImportBranches() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ImportRootConfig.class)) {

            // 普通类导入：PlainImportedConfig → plainBean
            assertThat(context.containsBean("plainBean"))
                    .as("@Import 普通类 → PlainImportedConfig 应注册 plainBean")
                    .isTrue();

            // ImportSelector 导入：TraceImportSelector → SelectorImportConfig → selectorBean
            assertThat(context.containsBean("selectorBean"))
                    .as("@Import ImportSelector → SelectorImportConfig 应注册 selectorBean")
                    .isTrue();

            // ImportBeanDefinitionRegistrar 导入：TraceRegistrar → registrarBean
            assertThat(context.containsBean("registrarBean"))
                    .as("@Import ImportBeanDefinitionRegistrar → TraceRegistrar 应注册 registrarBean")
                    .isTrue();
        }
    }

    @Test
    void shouldCreatePlainBeanInstance() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ImportRootConfig.class)) {

            Object bean = context.getBean("plainBean");
            assertThat(bean).isInstanceOf(PlainBean.class);
        }
    }

    @Test
    void shouldCreateSelectorBeanInstance() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ImportRootConfig.class)) {

            Object bean = context.getBean("selectorBean");
            assertThat(bean).isInstanceOf(SelectorBean.class);
        }
    }

    @Test
    void shouldCreateRegistrarBeanInstance() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ImportRootConfig.class)) {

            Object bean = context.getBean("registrarBean");
            assertThat(bean).isInstanceOf(RegistrarBean.class);
        }
    }
}
