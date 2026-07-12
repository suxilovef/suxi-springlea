package com.sw.yang.springlea.ioc.beancreation.exp03;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付宝支付实现，无 @Primary 标记。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service("alipayPaymentService")
public class AlipayPaymentService implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(AlipayPaymentService.class);

    @Override
    public String pay() {
        log.info("支付宝支付");
        return "alipay";
    }
}
