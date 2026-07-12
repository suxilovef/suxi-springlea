package com.sw.yang.springlea.ioc.beancreation.exp01;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 实验一核心 Bean：验证构造器与字段注入时机。
 * <p>
 * 断点观察：
 * <ul>
 *   <li>{@code createBeanInstance()} — 构造器执行时 userService 为 null</li>
 *   <li>{@code populateBean() → postProcessProperties()} — 字段注入发生在此处</li>
 *   <li>{@code @PostConstruct} — userService 已注入完毕</li>
 * </ul>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Getter
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    @Autowired
    private UserService userService;

    public DemoService() {
        log.info("[构造器] DemoService 构造器执行，userService = {}", userService);
        // 此时 userService 必然为 null，因为字段注入尚未发生
    }

    @PostConstruct
    public void init() {
        log.info("[@PostConstruct] userService = {}", userService);
        // 此时 userService 已完成注入
    }

}
