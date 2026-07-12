package com.sw.yang.springlea.ioc.beancreation.exp02;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 实验二核心 Bean：观察 Spring Bean 完整初始化顺序。
 * <p>
 * 预期执行顺序（与文章第十四章对照）：
 * <ol>
 *   <li>BeanNameAware.setBeanName()</li>
 *   <li>BeanClassLoaderAware.setBeanClassLoader()</li>
 *   <li>BeanFactoryAware.setBeanFactory()</li>
 *   <li>ApplicationContextAware.setApplicationContext()（由 ApplicationContextAwareProcessor 触发）</li>
 *   <li>@PostConstruct 方法（由 InitDestroyAnnotationBeanPostProcessor 触发）</li>
 *   <li>InitializingBean.afterPropertiesSet()</li>
 *   <li>自定义 init-method（通过 @Bean(initMethod) 配置）</li>
 *   <li>BeanPostProcessor.postProcessAfterInitialization() — 此处通常创建 AOP 代理</li>
 * </ol>
 * <p>
 * 断点观察：
 * <ul>
 *   <li>{@code AbstractAutowireCapableBeanFactory.invokeAwareMethods()}</li>
 *   <li>{@code ApplicationContextAwareProcessor.postProcessBeforeInitialization()}</li>
 *   <li>{@code InitDestroyAnnotationBeanPostProcessor.postProcessBeforeInitialization()}</li>
 *   <li>{@code AbstractAutowireCapableBeanFactory.invokeInitMethods()}</li>
 *   <li>{@code AbstractAutowireCapableBeanFactory.initializeBean()} — 观察整体流程</li>
 * </ul>
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
public class LifecycleBean implements BeanNameAware,
        BeanClassLoaderAware,
        BeanFactoryAware,
        ApplicationContextAware,
        InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LifecycleBean.class);

    private String stepLog = "";

    // ──────────────────── 第一组：invokeAwareMethods() 直接调用 ────────────────────

    @Override
    public void setBeanName(String name) {
        log.info("[1] BeanNameAware.setBeanName() → beanName = {}", name);
        stepLog += "BeanNameAware → ";
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        log.info("[2] BeanClassLoaderAware.setBeanClassLoader()");
        stepLog += "BeanClassLoaderAware → ";
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        log.info("[3] BeanFactoryAware.setBeanFactory()");
        stepLog += "BeanFactoryAware → ";
    }

    // ──────────────────── 第二组：ApplicationContextAwareProcessor 调用 ────────────────────

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        log.info("[4] ApplicationContextAware.setApplicationContext()");
        stepLog += "ApplicationContextAware → ";
    }

    // ──────────────────── @PostConstruct（由 InitDestroyAnnotationBeanPostProcessor 触发） ────────────────────

    @PostConstruct
    public void postConstruct() {
        log.info("[5] @PostConstruct");
        stepLog += "@PostConstruct → ";
    }

    // ──────────────────── InitializingBean ────────────────────

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("[6] InitializingBean.afterPropertiesSet()");
        stepLog += "InitializingBean → ";
    }

    // ──────────────────── 自定义 init-method（由 @Bean(initMethod) 指定） ────────────────────

    public void customInit() {
        log.info("[7] 自定义 init-method: customInit()");
        stepLog += "customInit";
    }

    public String getStepLog() {
        return stepLog;
    }
}
