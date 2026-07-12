package com.sw.yang.springlea.ioc.beancreation.exp08;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 依赖 FaultyBean 的 Bean：用于验证依赖 Bean 创建失败后，
 * 依赖它的 Bean 也会受到影响。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class DependentOnFaultyBean {

    private static final Logger log = LoggerFactory.getLogger(DependentOnFaultyBean.class);

    @Autowired
    private FaultyBean faultyBean;

    public DependentOnFaultyBean() {
        log.info("DependentOnFaultyBean 构造器执行");
    }
}
