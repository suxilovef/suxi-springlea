package com.sw.yang.springlea.ioc.configurationclass.exp01;

import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

/**
 * @Author: jiu.fang
 * @CreateTime: 2026-07-11
 * @Description:
 * @Version: 1.0
 */
public class TraceImportSelector implements ImportSelector {


    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        return new String[]{SelectorImportConfig.class.getName()};
    }
}
