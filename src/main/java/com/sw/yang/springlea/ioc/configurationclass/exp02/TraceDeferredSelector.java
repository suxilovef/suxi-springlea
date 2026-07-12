package com.sw.yang.springlea.ioc.configurationclass.exp02;

import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotationMetadata;

/**
 * 实验二：自定义 DeferredImportSelector。
 * <p>
 * 与普通 ImportSelector 的区别：
 * <ul>
 *   <li>实现 {@link DeferredImportSelector} 而非 {@code ImportSelector}</li>
 *   <li>声明 {@link #getImportGroup()} 返回自定义 Group 类型</li>
 *   <li>通过 {@code Ordered} 控制多个延迟 selector 之间的排序</li>
 * </ul>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Version: 1.0
 */
public class TraceDeferredSelector implements DeferredImportSelector, Ordered {

    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
        return new String[]{DeferredImportedConfig.class.getName()};
    }

    @Override
    public Class<? extends Group> getImportGroup() {
        return TraceGroup.class;
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
