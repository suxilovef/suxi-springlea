package com.sw.yang.springlea.ioc.beancreation.exp02;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验二：初始化顺序
 * <p>
 * 目标：验证 Aware → @PostConstruct → InitializingBean → init-method → BPP 的精确顺序。
 * <p>
 * 调试时在以下位置打断点观察：
 * <ol>
 *   <li>{@code AbstractAutowireCapableBeanFactory.invokeAwareMethods()} — 第一组 Aware</li>
 *   <li>{@code ApplicationContextAwareProcessor.postProcessBeforeInitialization()} — 第二组 Aware</li>
 *   <li>{@code InitDestroyAnnotationBeanPostProcessor.postProcessBeforeInitialization()} — @PostConstruct</li>
 *   <li>{@code AbstractAutowireCapableBeanFactory.invokeInitMethods()} — afterPropertiesSet + customInit</li>
 *   <li>{@code TraceBeanPostProcessor.postProcessAfterInitialization()} — BPP 后处理</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验二：初始化顺序")
class Exp02Test {

    @Test
    @DisplayName("验证 Bean 初始化各阶段按预期顺序执行")
    void shouldExecuteLifecycleCallbacksInCorrectOrder() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp02Config.class)) {

            LifecycleBean bean = context.getBean(LifecycleBean.class);
            assertThat(bean).isNotNull();

            String stepLog = bean.getStepLog();

            // 验证顺序：各阶段按数字 1-7 依次执行
            int posBeanName = stepLog.indexOf("BeanNameAware");
            int posClassLoader = stepLog.indexOf("BeanClassLoaderAware");
            int posBeanFactory = stepLog.indexOf("BeanFactoryAware");
            int posAppCtx = stepLog.indexOf("ApplicationContextAware");
            int posPostConstruct = stepLog.indexOf("@PostConstruct");
            int posInitializing = stepLog.indexOf("InitializingBean");
            int posCustomInit = stepLog.indexOf("customInit");

            assertThat(posBeanName)
                    .as("BeanNameAware 应最先执行")
                    .isLessThan(posClassLoader);

            assertThat(posClassLoader)
                    .as("BeanClassLoaderAware 应在 BeanNameAware 之后")
                    .isLessThan(posBeanFactory);

            assertThat(posBeanFactory)
                    .as("BeanFactoryAware 应在 BeanClassLoaderAware 之后")
                    .isLessThan(posAppCtx);

            assertThat(posAppCtx)
                    .as("ApplicationContextAware 应在 BeanFactoryAware 之后")
                    .isLessThan(posPostConstruct);

            assertThat(posPostConstruct)
                    .as("@PostConstruct 应在 ApplicationContextAware 之后")
                    .isLessThan(posInitializing);

            assertThat(posInitializing)
                    .as("InitializingBean 应在 @PostConstruct 之后")
                    .isLessThan(posCustomInit);

            assertThat(posCustomInit)
                    .as("自定义 init-method 应在最后")
                    .isGreaterThan(posInitializing);
        }
    }

    @Test
    @DisplayName("验证 TraceBeanPostProcessor 已在容器中")
    void shouldRegisterTraceBeanPostProcessor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp02Config.class)) {

            assertThat(context.containsBean("traceBeanPostProcessor"))
                    .as("TraceBeanPostProcessor 应在容器中注册")
                    .isTrue();
        }
    }
}
