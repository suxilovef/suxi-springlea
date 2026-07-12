package com.sw.yang.springlea.ioc.beancreation.exp08;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 健康 Bean：用于验证 FaultyBean 创建失败后，不依赖它的其他 Bean 仍然正常。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class HealthyBean {

    private static final Logger log = LoggerFactory.getLogger(HealthyBean.class);

    public HealthyBean() {
        log.info("HealthyBean 构造器执行（正常）");
    }

    public String whoAmI() {
        return "I am HealthyBean";
    }
}
