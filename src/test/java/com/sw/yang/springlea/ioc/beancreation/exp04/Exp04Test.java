package com.sw.yang.springlea.ioc.beancreation.exp04;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实验四：FactoryBean
 * <p>
 * 目标：理解 FactoryBean 的产品与工厂的区别。
 * <p>
 * 调试时在以下位置打断点观察：
 * <ol>
 *   <li>{@code AbstractBeanFactory.getObjectForBeanInstance()} — 观察 FactoryBean 解引用</li>
 *   <li>{@code FactoryBeanRegistrySupport.getObjectFromFactoryBean()} — 观察产品缓存</li>
 *   <li>观察 {@code singletonObjects["carFactory"]} vs {@code factoryBeanObjectCache["carFactory"]}</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验四：FactoryBean")
class Exp04Test {

    @Test
    @DisplayName("getBean(\"carFactory\") 返回 Car 产品对象")
    void shouldReturnProductWhenGettingByBeanName() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp04Config.class)) {

            Object bean = context.getBean("carFactory");

            assertThat(bean)
                    .as("getBean(\"carFactory\") 应返回 Car 产品，而非 FactoryBean 本身")
                    .isInstanceOf(Car.class);

            Car car = (Car) bean;
            assertThat(car.getModel()).isEqualTo("Tesla Model Y");
            assertThat(car.drive()).isEqualTo("Driving Tesla Model Y");
        }
    }

    @Test
    @DisplayName("getBean(\"&carFactory\") 返回 CarFactoryBean 工厂本身")
    void shouldReturnFactoryBeanWhenUsingAmpersandPrefix() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp04Config.class)) {

            Object bean = context.getBean("&carFactory");

            assertThat(bean)
                    .as("getBean(\"&carFactory\") 应返回 FactoryBean 本身")
                    .isInstanceOf(CarFactoryBean.class);
        }
    }

    @Test
    @DisplayName("通过 BeanFactory.FACTORY_BEAN_PREFIX 常量获取工厂")
    void shouldReturnFactoryBeanViaConstant() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp04Config.class)) {

            Object bean = context.getBean(BeanFactory.FACTORY_BEAN_PREFIX + "carFactory");

            assertThat(bean).isInstanceOf(CarFactoryBean.class);
        }
    }

    @Test
    @DisplayName("按 Car 类型获取 Bean 也能拿到产品对象")
    void shouldResolveByType() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp04Config.class)) {

            Car car = context.getBean(Car.class);

            assertThat(car).isNotNull();
            assertThat(car.drive()).isEqualTo("Driving Tesla Model Y");
        }
    }

    @Test
    @DisplayName("按 FactoryBean 类型获取返回工厂本身")
    void shouldGetFactoryByFactoryBeanType() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp04Config.class)) {

            CarFactoryBean factory = context.getBean(CarFactoryBean.class);

            assertThat(factory).isNotNull();
            assertThat(factory.getObjectType()).isEqualTo(Car.class);
        }
    }

    @Test
    @DisplayName("产品对象是单例（同一 Factory 多次 getBean 返回同一 Car）")
    void shouldCacheProductForSingletonFactoryBean() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp04Config.class)) {

            Car car1 = (Car) context.getBean("carFactory");
            Car car2 = (Car) context.getBean("carFactory");

            assertThat(car1)
                    .as("FactoryBean.isSingleton()=true，产品应缓存为单例")
                    .isSameAs(car2);
        }
    }

    @Test
    @DisplayName("对非 FactoryBean 使用 & 前缀应抛出 BeanIsNotAFactoryException")
    void shouldThrowExceptionForNonFactoryBeanWithAmpersand() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp04Config.class)) {

            // Exp04Config 本身不是 FactoryBean
            assertThatThrownBy(() -> context.getBean("&exp04Config"))
                    .as("对普通 Bean 使用 & 前缀应抛异常")
                    .hasMessageContaining("FactoryBean");
        }
    }
}
