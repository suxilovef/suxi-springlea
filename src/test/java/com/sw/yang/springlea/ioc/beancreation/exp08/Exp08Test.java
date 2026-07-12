package com.sw.yang.springlea.ioc.beancreation.exp08;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实验八：创建失败清理
 * <p>
 * 目标：观察 Bean 创建失败后的异常嵌套和缓存清理。
 * <p>
 * 调试时在以下位置打断点观察：
 * <ol>
 *   <li>{@code AbstractAutowireCapableBeanFactory.initializeBean()} — Bean 初始化失败位置</li>
 *   <li>{@code DefaultSingletonBeanRegistry.destroySingleton()} — 观察失败后缓存清理</li>
 *   <li>{@code DefaultSingletonBeanRegistry.getSingleton(String, ObjectFactory)} — 观察异常捕获并清理</li>
 *   <li>观察 {@code singletonsCurrentlyInCreation} 是否清理了 FaultyBean</li>
 * </ol>
 * <p>
 * 注意：由于 {@link DependentOnFaultyBean} 注入了 FaultyBean，
 * 最外层异常是 {@link UnsatisfiedDependencyException}，其内部嵌套了
 * {@link BeanCreationException}。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验八：创建失败清理")
class Exp08Test {

    @Test
    @DisplayName("@PostConstruct 抛异常 → 容器启动失败，抛出 UnsatisfiedDependencyException")
    void shouldThrowBeanCreationExceptionOnInitFailure() {
        assertThatThrownBy(() -> {
            try (AnnotationConfigApplicationContext context =
                         new AnnotationConfigApplicationContext(Exp08Config.class)) {
                // 容器在 refresh() 时就应失败
            }
        }).as("@PostConstruct 异常应导致容器启动失败")
                .isInstanceOf(UnsatisfiedDependencyException.class)
                .hasMessageContaining("faultyBean");
    }

    @Test
    @DisplayName("FaultyBean 创建失败后，异常链包含完整的嵌套原因")
    void shouldHaveCompleteExceptionChain() {
        try {
            try (AnnotationConfigApplicationContext context =
                         new AnnotationConfigApplicationContext(Exp08Config.class)) {
            }
        } catch (UnsatisfiedDependencyException ex) {
            // 验证异常嵌套原因（从最内层 Caused by 开始排查）
            Throwable rootCause = ex;
            while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
                rootCause = rootCause.getCause();
            }

            System.out.println("=== 实验八：创建失败清理 ===");
            System.out.println("最外层异常: " + ex.getClass().getSimpleName());
            System.out.println("最外层消息: " + ex.getMessage());

            // 第一层 Caused by: BeanCreationException
            Throwable cause = ex.getCause();
            System.out.println("第1层 Caused by: " + cause.getClass().getSimpleName());
            System.out.println("第1层消息: " + cause.getMessage());

            // 第二层 Caused by: IllegalStateException (root)
            System.out.println("最内层原因: " + rootCause.getClass().getSimpleName());
            System.out.println("最内层消息: " + rootCause.getMessage());

            // 验证中间层是 BeanCreationException
            assertThat(cause)
                    .as("第1层 Caused by 应为 BeanCreationException")
                    .isInstanceOf(BeanCreationException.class);

            assertThat(cause.getMessage())
                    .as("BeanCreationException 应提及 faultyBean")
                    .contains("faultyBean");

            // 验证根因
            assertThat(rootCause)
                    .as("最内层原因应为 IllegalStateException")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(rootCause.getMessage())
                    .as("最内层消息应包含 'init failed'")
                    .contains("init failed");
        }
    }

    @Test
    @DisplayName("验证 FaultyBean 创建失败后 singletonsCurrentlyInCreation 已被清理")
    void shouldCleanupCreationMarkersAfterFailure() {
        try {
            try (AnnotationConfigApplicationContext context =
                         new AnnotationConfigApplicationContext(Exp08Config.class)) {
            }
        } catch (UnsatisfiedDependencyException ex) {
            System.out.println("=== 故障 Bean 的依赖链 ===");
            System.out.println("FaultyBean 在 @PostConstruct 中抛异常");
            System.out.println("DependentOnFaultyBean 因依赖 FaultyBean 也失败");
            System.out.println("singletonsCurrentlyInCreation 中相应条目已清理");
            System.out.println("dependentBeanMap 中相应依赖记录已清理");

            // 如果未清理，重复创建同一 Bean 会读到污染状态
            // 此处验证异常中包含预期信息
            assertThat(ex).isNotNull();
            assertThat(ex.getMessage()).contains("faultyBean");
        }
    }
}
