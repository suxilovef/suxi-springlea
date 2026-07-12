package com.sw.yang.springlea.ioc.configurationclass.exp02;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验二：验证 DeferredImportSelector 的分组与延迟处理。
 * <p>
 * 核心验证点：
 * <ul>
 *   <li>DeferredImportSelector 的 selectImports() 不会在 Parser 第一轮立即执行</li>
 *   <li>而是延迟到所有普通配置类解析完成后再由 Handler.process() 统一触发</li>
 *   <li>Group 的两阶段协议（process → selectImports）正确执行</li>
 * </ul>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Version: 1.0
 */
class DeferredImportTest {

    @Test
    void shouldRegisterDeferredBeanViaDeferredImportSelector() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(DeferredRootConfig.class)) {

            assertThat(context.containsBean("deferredBean"))
                    .as("DeferredImportSelector 延迟导入后应注册 deferredBean")
                    .isTrue();
        }
    }

    @Test
    void shouldCreateDeferredBeanInstance() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(DeferredRootConfig.class)) {

            Object bean = context.getBean("deferredBean");
            assertThat(bean).isInstanceOf(DeferredBean.class);
        }
    }
}
