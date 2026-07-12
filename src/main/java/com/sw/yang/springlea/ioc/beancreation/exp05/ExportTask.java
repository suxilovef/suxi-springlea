package com.sw.yang.springlea.ioc.beancreation.exp05;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Prototype Bean：每次获取应产生新实例。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ExportTask {

    private static final Logger log = LoggerFactory.getLogger(ExportTask.class);

    private final String id = UUID.randomUUID().toString().substring(0, 8);

    public ExportTask() {
        log.info("ExportTask 实例化 → id = {}", id);
    }

    public String getId() {
        return id;
    }
}
