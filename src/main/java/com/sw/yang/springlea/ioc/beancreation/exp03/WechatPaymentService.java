package com.sw.yang.springlea.ioc.beancreation.exp03;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 微信支付实现，标注 @Primary 作为默认候选。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Primary
@Service("wechatPaymentService")
public class WechatPaymentService implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(WechatPaymentService.class);

    @Override
    public String pay() {
        log.info("微信支付");
        return "wechat";
    }
}
