package com.sw.yang.springlea.ioc.beancreation.exp08;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 实验八核心 Bean：在 @PostConstruct 中主动抛出异常，模拟创建失败。
 * <p>
 * 调试时观察（参考文章第二十章）：
 * <ul>
 *   <li>{@code BeanCreationException} 的嵌套原因（从最内层 Caused by 开始排查）</li>
 *   <li>{@code singletonsCurrentlyInCreation} 是否移除了该 Bean 名称</li>
 *   <li>{@code singletonFactories} / {@code earlySingletonObjects} 三级缓存是否清理</li>
 *   <li>{@code dependentBeanMap} 依赖记录是否清理</li>
 * </ul>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class FaultyBean {

    private static final Logger log = LoggerFactory.getLogger(FaultyBean.class);

    public FaultyBean() {
        log.info("FaultyBean 构造器执行（正常）");
    }

    @PostConstruct
    public void init() {
        log.info("FaultyBean.@PostConstruct → 即将抛出异常！");
        throw new IllegalStateException("init failed: 模拟 @PostConstruct 异常");
    }
}
