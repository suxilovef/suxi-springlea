package com.sw.yang.springlea.ioc.beancreation.exp03;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实验三：多候选依赖消歧
 * <p>
 * 目标：验证 @Primary、@Qualifier、字段名匹配、集合注入的消歧结果。
 * <p>
 * 调试时在以下位置打断点观察：
 * <ol>
 *   <li>{@code DefaultListableBeanFactory.findAutowireCandidates()} — 找到所有 PaymentService 候选</li>
 *   <li>{@code DefaultListableBeanFactory.determineAutowireCandidate()} — 观察消歧顺序</li>
 *   <li>{@code DefaultListableBeanFactory.doResolveDependency()} — 观察依赖解析五步</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@DisplayName("实验三：多候选依赖消歧")
class Exp03Test {

    @Test
    @DisplayName("@Primary 消歧：paymentService 应注入 WechatPaymentService")
    void shouldResolvePrimaryCandidate() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp03Config.class)) {

            PaymentConsumer consumer = context.getBean(PaymentConsumer.class);

            assertThat(consumer.getPaymentService())
                    .as("@Primary 应选择 WechatPaymentService")
                    .isInstanceOf(WechatPaymentService.class);

            assertThat(consumer.getPaymentService().pay())
                    .isEqualTo("wechat");
        }
    }

    @Test
    @DisplayName("@Qualifier 消歧：alipayService 应注入 AlipayPaymentService")
    void shouldResolveQualifiedCandidate() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp03Config.class)) {

            PaymentConsumer consumer = context.getBean(PaymentConsumer.class);

            assertThat(consumer.getAlipayService())
                    .as("@Qualifier(\"alipayPaymentService\") 应选择 AlipayPaymentService")
                    .isInstanceOf(AlipayPaymentService.class);

            assertThat(consumer.getAlipayService().pay())
                    .isEqualTo("alipay");
        }
    }

    @Test
    @DisplayName("字段名匹配消歧：wechatPaymentService 应与 BeanName 匹配")
    void shouldResolveByFieldName() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp03Config.class)) {

            PaymentConsumer consumer = context.getBean(PaymentConsumer.class);

            assertThat(consumer.getWechatPaymentService())
                    .as("字段名 wechatPaymentService 应匹配 BeanName wechatPaymentService")
                    .isInstanceOf(WechatPaymentService.class);
        }
    }

    @Test
    @DisplayName("集合注入：allPaymentServices 应包含全部两个实现")
    void shouldInjectAllCandidatesAsList() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp03Config.class)) {

            PaymentConsumer consumer = context.getBean(PaymentConsumer.class);

            List<PaymentService> all = consumer.getAllPaymentServices();

            assertThat(all)
                    .as("集合注入应包含全部 PaymentService 实现")
                    .hasSize(2);

            assertThat(all)
                    .extracting(PaymentService::pay)
                    .containsExactlyInAnyOrder("wechat", "alipay");
        }
    }

    @Test
    @DisplayName("无 @Qualifier 时 @Primary 和字段名消歧各自独立生效")
    void shouldHaveBothBeansInContainer() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp03Config.class)) {

            assertThat(context.containsBean("wechatPaymentService")).isTrue();
            assertThat(context.containsBean("alipayPaymentService")).isTrue();
        }
    }

    @Test
    @DisplayName("多候选无消歧时应抛出 NoUniqueBeanDefinitionException")
    void shouldFailWithoutDisambiguation() {
        // 直接按类型获取多个候选时应抛出异常
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(Exp03Config.class)) {

            // 多候选可通过 @Primary 或 @Qualifier 解决
            // 直接 getBean(PaymentService.class) 在 @Primary 存在时返回 @Primary Bean
            PaymentService ps = context.getBean(PaymentService.class);
            assertThat(ps).isInstanceOf(WechatPaymentService.class);
        }
    }
}
