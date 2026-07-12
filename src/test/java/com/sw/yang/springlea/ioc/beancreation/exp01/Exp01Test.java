package com.sw.yang.springlea.ioc.beancreation.exp01;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验一：构造器与字段注入时机
 * <p>
 * 目标：证明字段注入发生在构造器之后。
 * <p>
 * 调试时在以下位置打断点观察：
 * <ol>
 *   <li>{@code AbstractAutowireCapableBeanFactory.createBeanInstance()} — 观察构造器执行</li>
 *   <li>{@code AbstractAutowireCapableBeanFactory.populateBean()} — 观察属性填充入口</li>
 *   <li>{@code AutowiredAnnotationBeanPostProcessor.postProcessProperties()} — 观察字段注入执行</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验一：构造器与字段注入时机")
class Exp01Test {

    @Test
    @DisplayName("构造器中 userService 为 null，@PostConstruct 中 userService 已注入")
    void shouldInjectFieldAfterConstructor() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp01Config.class)) {

            DemoService demoService = context.getBean(DemoService.class);

            // 最终从容器获取时，userService 一定已注入
            assertThat(demoService.getUserService())
                    .as("容器就绪后 userService 应已注入")
                    .isNotNull();

            assertThat(demoService.getUserService().whoAmI())
                    .isEqualTo("I am UserService");
        }
    }

    @Test
    @DisplayName("验证 UserService 独立可用")
    void shouldHaveUserServiceAvailable() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp01Config.class)) {

            assertThat(context.containsBean("userService"))
                    .as("UserService 应在容器中注册")
                    .isTrue();
        }
    }
}
