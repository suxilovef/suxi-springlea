package com.sw.yang.springlea.ioc.beancreation.exp05;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 【正确方案】Singleton 通过 ObjectProvider 获取 Prototype。
 * <p>
 * 每次调用 {@link #export()} 时通过 provider 获取新的 ExportTask 实例。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class ExportServiceWithProvider {

    private static final Logger log = LoggerFactory.getLogger(ExportServiceWithProvider.class);

    private final ObjectProvider<ExportTask> taskProvider;

    public ExportServiceWithProvider(ObjectProvider<ExportTask> taskProvider) {
        this.taskProvider = taskProvider;
    }

    public String export() {
        ExportTask task = taskProvider.getObject();
        log.info("ExportServiceWithProvider.export() → task.id = {}", task.getId());
        return task.getId();
    }
}
