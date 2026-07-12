package com.sw.yang.springlea.ioc.beancreation.exp03;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多候选依赖的消费者，展示不同消歧方式。
 * <p>
 * 断点观察：
 * <ul>
 *   <li>{@code DefaultListableBeanFactory.determineAutowireCandidate()} — 观察消歧逻辑</li>
 *   <li>{@code DefaultListableBeanFactory.doResolveDependency()} — 观察依赖解析全过程</li>
 * </ul>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class PaymentConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentConsumer.class);

    // 场景 A：无 @Qualifier，依赖 @Primary 消歧 → 应注入 WechatPaymentService
    @Autowired
    private PaymentService paymentService;

    // 场景 B：使用 @Qualifier 指定 Bean 名称 → 应注入 AlipayPaymentService
    @Autowired
    @Qualifier("alipayPaymentService")
    private PaymentService alipayService;

    // 场景 C：字段名与 BeanName 匹配（"wechatPaymentService"）→ 与 @Primary 一致
    @Autowired
    private PaymentService wechatPaymentService;

    // 场景 D：集合注入 → 获取所有 PaymentService 实现
    @Autowired
    private List<PaymentService> allPaymentServices;

    @PostConstruct
    public void report() {
        log.info("=== 多候选依赖注入结果 ===");
        log.info("paymentService（@Primary）      → {}", paymentService.pay());
        log.info("alipayService（@Qualifier）     → {}", alipayService.pay());
        log.info("wechatPaymentService（字段名）   → {}", wechatPaymentService.pay());
        log.info("allPaymentServices 数量          → {}", allPaymentServices.size());
        allPaymentServices.forEach(s -> log.info("  - {}", s.pay()));
    }

    public PaymentService getPaymentService() {
        return paymentService;
    }

    public PaymentService getAlipayService() {
        return alipayService;
    }

    public PaymentService getWechatPaymentService() {
        return wechatPaymentService;
    }

    public List<PaymentService> getAllPaymentServices() {
        return allPaymentServices;
    }
}
