package com.sw.yang.springlea.ioc.beancreation.exp05;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验五：Singleton 注入 Prototype
 * <p>
 * 目标：验证 singleton 直接注入 prototype 的陷阱，以及 ObjectProvider 的正确方案。
 * <p>
 * 调试时在以下位置打断点观察：
 * <ol>
 *   <li>{@code AbstractBeanFactory.doGetBean()} — 观察 singleton vs prototype 分支</li>
 *   <li>{@code AbstractAutowireCapableBeanFactory.populateBean()} — 观察注入发生时 prototype 的解析</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验五：Singleton 注入 Prototype")
class Exp05Test {

    @Test
    @DisplayName("【陷阱】Singleton 直接注入 Prototype → 多次调用拿到同一实例")
    void shouldUseSamePrototypeWhenInjectedDirectly() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp05Config.class)) {

            ExportService service = context.getBean(ExportService.class);

            String id1 = service.export();
            String id2 = service.export();

            assertThat(id1)
                    .as("Singleton 直接注入 Prototype：两次调用返回同一实例（陷阱！）")
                    .isEqualTo(id2);
        }
    }

    @Test
    @DisplayName("【正确】ObjectProvider 每次获取新 Prototype 实例")
    void shouldGetNewPrototypeWithObjectProvider() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp05Config.class)) {

            ExportServiceWithProvider service = context.getBean(ExportServiceWithProvider.class);

            String id1 = service.export();
            String id2 = service.export();

            assertThat(id1)
                    .as("ObjectProvider 每次获取新的 Prototype 实例")
                    .isNotEqualTo(id2);
        }
    }

    @Test
    @DisplayName("直接 getBean(ExportTask.class) 每次获取不同实例")
    void shouldGetDifferentInstancesFromGetBean() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp05Config.class)) {

            ExportTask task1 = context.getBean(ExportTask.class);
            ExportTask task2 = context.getBean(ExportTask.class);

            assertThat(task1.getId())
                    .as("每次 getBean(Prototype) 应返回不同实例")
                    .isNotEqualTo(task2.getId());
        }
    }
}
