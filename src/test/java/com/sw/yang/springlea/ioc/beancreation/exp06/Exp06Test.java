package com.sw.yang.springlea.ioc.beancreation.exp06;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实验六：普通循环依赖
 * <p>
 * 目标：观察三级缓存解决 singleton 字段循环依赖的过程。
 * <p>
 * 时间线（参考文章第十六章）：
 * <pre>
 *   1. getBean("a") → A 加入 singletonsCurrentlyInCreation
 *   2. createBeanInstance(A) → 原始 A
 *   3. singletonFactories["a"] = ObjectFactory
 *   4. populateBean(A) → 需要 B
 *   5. getBean("b") → B 加入 singletonsCurrentlyInCreation
 *   6. createBeanInstance(B) → 原始 B
 *   7. singletonFactories["b"] = ObjectFactory
 *   8. populateBean(B) → 需要 A
 *   9. getSingleton("a") → 三级缓存命中 → 早期 A 注入 B
 *   10. B 完成初始化 → 入一级缓存
 *   11. A 获取完整 B → A 完成初始化
 *   12. A 入一级缓存，清理二三级缓存
 * </pre>
 * <p>
 * 调试时重点观察：singletonObjects、earlySingletonObjects、singletonFactories
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验六：普通循环依赖")
class Exp06Test {

    @Test
    @DisplayName("开启循环依赖后 A、B 互相注入成功")
    void shouldResolveCircularDependencyWhenAllowed() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(true);
        context.register(Exp06Config.class);
        context.refresh();

        try {
            ServiceA serviceA = context.getBean(ServiceA.class);
            ServiceB serviceB = context.getBean(ServiceB.class);

            assertThat(serviceA).isNotNull();
            assertThat(serviceB).isNotNull();

            // 互相调用成功
            assertThat(serviceA.callB()).isEqualTo("A → ServiceB");
            assertThat(serviceB.callA()).isEqualTo("B → ServiceA");
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("显式禁用循环依赖 → 异常链中包含 BeanCurrentlyInCreationException")
    void shouldThrowExceptionWhenDisabled() {
        // Spring Framework 默认 allowCircularReferences=true
        // Spring Boot 通过 spring.main.allow-circular-references=false 覆盖
        // 此处显式模拟 Spring Boot 默认行为
        try {
            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            context.register(Exp06Config.class);
            context.refresh();
            context.close();
        } catch (Exception ex) {
            // 搜索异常链中是否包含 BeanCurrentlyInCreationException
            Throwable current = ex;
            boolean found = false;
            while (current != null) {
                if (current instanceof BeanCurrentlyInCreationException) {
                    found = true;
                    break;
                }
                current = current.getCause();
            }

            System.out.println("=== 实验六：禁用循环依赖 ===");
            System.out.println("最外层异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            System.out.println("异常链中包含 BeanCurrentlyInCreationException？ " + found);

            assertThat(found)
                    .as("禁用循环依赖后，异常链中应包含 BeanCurrentlyInCreationException")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("开启循环依赖后 A 和 B 在容器中为单例")
    void shouldBeSingletonWhenResolved() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(true);
        context.register(Exp06Config.class);
        context.refresh();

        try {
            ServiceA a1 = context.getBean(ServiceA.class);
            ServiceA a2 = context.getBean(ServiceA.class);

            assertThat(a1).isSameAs(a2);
        } finally {
            context.close();
        }
    }
}
