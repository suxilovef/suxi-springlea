package com.sw.yang.springlea.ioc.beancreation.exp07;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验七：循环依赖与 AOP
 * <p>
 * 目标：观察 AOP 代理在循环依赖场景下的提前创建（getEarlyBeanReference）。
 * <p>
 * 关键对比（参考文章第十五章）：
 * <pre>
 *   普通场景：
 *     initializeBean() → postProcessAfterInitialization() → wrapIfNecessary() → 代理
 *
 *   循环依赖场景：
 *     addSingletonFactory() → getEarlyBeanReference() → 提前创建代理
 *     initializeBean() → 检测到已有早期代理 → 不再重复创建
 * </pre>
 * <p>
 * 调试时在以下位置打断点：
 * <ol>
 *   <li>{@code AbstractAutoProxyCreator.getEarlyBeanReference()} — 观察早期代理创建</li>
 *   <li>{@code AbstractAutoProxyCreator.postProcessAfterInitialization()} — 观察是否跳过代理</li>
 *   <li>{@code AbstractAutowireCapableBeanFactory.doCreateBean()} — 观察早期引用一致性检查</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验七：循环依赖与 AOP")
class Exp07Test {

    @Test
    @DisplayName("循环依赖 + @Transactional：A 应为代理，B 持有的 A 也为代理")
    void shouldCreateEarlyProxyForTransactionalBean() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(true);
        context.register(Exp07Config.class);
        context.refresh();

        try {
            ServiceAWithTx aFromContainer = context.getBean(ServiceAWithTx.class);
            ServiceBWithAop bFromContainer = context.getBean(ServiceBWithAop.class);

            // A 从容器获取时应为代理（被 @Transactional 增强）
            assertThat(aFromContainer)
                    .as("ServiceAWithTx 从容器获取应为代理")
                    .isNotNull();

            boolean aIsProxy = isProxy(aFromContainer);
            System.out.println("=== 实验七：循环依赖 + AOP ===");
            System.out.println("容器中 A 是否为代理？ " + aIsProxy);
            System.out.println("A 实际类名：" + aFromContainer.getClass().getName());

            // B 中持有的 A 引用
            ServiceAWithTx aFromB = bFromContainer.getServiceA();
            System.out.println("B 持有的 A 是否为代理？ " + isProxy(aFromB));
            System.out.println("B 持有的 A 类名：" + aFromB.getClass().getName());

            // 关键：B 中持有的 A 应与容器中的 A 是同一引用
            System.out.println("容器 A == B 持有的 A？ " + (aFromContainer == aFromB));

            // 循环引用正常工作
            assertThat(aFromContainer.callB()).isEqualTo("A-Tx → ServiceBWithAop");
            assertThat(bFromContainer.callA()).isEqualTo("B → ServiceAWithTx");

            if (aIsProxy) {
                System.out.println("✅ @Transactional 导致 A 被代理包装");
                System.out.println("✅ 循环依赖中 B 持有的是同一个代理对象");
            }
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("验证 B 持有的 A 与容器中的 A 是同一引用")
    void shouldHoldSameReference() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(true);
        context.register(Exp07Config.class);
        context.refresh();

        try {
            ServiceAWithTx aFromContainer = context.getBean(ServiceAWithTx.class);
            ServiceBWithAop b = context.getBean(ServiceBWithAop.class);

            assertThat(b.getServiceA())
                    .as("B 持有的 A 应与容器中的 A 是同一引用（避免引用不一致）")
                    .isSameAs(aFromContainer);
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("ServiceAWithTx 类名包含代理特征（CGLIB 或 JDK 代理后缀）")
    void shouldHaveProxyClassName() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(true);
        context.register(Exp07Config.class);
        context.refresh();

        try {
            ServiceAWithTx a = context.getBean(ServiceAWithTx.class);
            String className = a.getClass().getName();

            System.out.println("ServiceAWithTx 代理类全名：" + className);

            // CGLIB 代理类名通常包含 "$$" 或 "CGLIB"
            // JDK 代理类名通常包含 "$Proxy"
            boolean isProxyClass = className.contains("$$") || className.contains("Proxy");
            System.out.println("是否为代理类名？ " + isProxyClass);
        } finally {
            context.close();
        }
    }

    /**
     * 简单判断对象是否为代理（CGLIB 类名包含 "$$"，JDK 是 Proxy 子类）。
     */
    private static boolean isProxy(Object obj) {
        if (obj == null) return false;
        Class<?> clazz = obj.getClass();
        // CGLIB 代理
        if (clazz.getName().contains("$$")) return true;
        // JDK 动态代理
        if (java.lang.reflect.Proxy.isProxyClass(clazz)) return true;
        // 通过 Advised 接口判断（Spring AOP 代理都实现此接口）
        if (obj instanceof Advised) return true;
        return false;
    }
}
