package com.sw.yang.springlea.ioc.configurationclass.exp02;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 实验二：验证 DeferredImportSelector 的分组与延迟处理。
 * <p>
 * 断点顺序：
 * <ol>
 *   <li>DeferredImportSelectorHandler.handle() — selector 被暂存</li>
 *   <li>DeferredImportSelectorHandler.process() — 排序 selector</li>
 *   <li>DeferredImportSelectorGroupingHandler.register() — 按 Group 分组</li>
 *   <li>TraceGroup.process() — 收集每个 selector 的导入结果</li>
 *   <li>TraceGroup.selectImports() — 返回最终候选</li>
 *   <li>DeferredImportSelectorGroupingHandler.processGroupImports() — 再交给 processImports()</li>
 * </ol>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Version: 1.0
 */
@Configuration
@Import(TraceDeferredSelector.class)
public class DeferredRootConfig {
}
