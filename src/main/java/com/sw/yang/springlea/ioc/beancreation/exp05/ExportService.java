package com.sw.yang.springlea.ioc.beancreation.exp05;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 【陷阱演示】Singleton 直接注入 Prototype。
 * <p>
 * ExportService 只创建一次，因此 ExportTask 也只在创建时解析一次。
 * 后续调用 {@link #export()} 不会获得新的 ExportTask。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    @Autowired
    private ExportTask task;

    public String export() {
        log.info("ExportService.export() → task.id = {}", task.getId());
        return task.getId();
    }
}
