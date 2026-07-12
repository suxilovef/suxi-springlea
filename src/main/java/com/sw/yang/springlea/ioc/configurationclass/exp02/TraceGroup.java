package com.sw.yang.springlea.ioc.configurationclass.exp02;

import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.type.AnnotationMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验二：自定义 DeferredImportSelector.Group。
 * <p>
 * Group 提供两阶段协议：
 * <ol>
 *   <li>逐个调用 {@code process()} 收集每个 selector 的导入结果</li>
 *   <li>统一调用 {@code selectImports()} 返回最终导入候选</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Version: 1.0
 */
public class TraceGroup implements DeferredImportSelector.Group {

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void process(AnnotationMetadata metadata, DeferredImportSelector selector) {
        for (String className : selector.selectImports(metadata)) {
            entries.add(new Entry(metadata, className));
        }
    }

    @Override
    public Iterable<Entry> selectImports() {
        return entries;
    }
}
