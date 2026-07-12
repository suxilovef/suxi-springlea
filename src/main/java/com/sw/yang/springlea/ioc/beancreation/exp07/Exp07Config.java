package com.sw.yang.springlea.ioc.beancreation.exp07;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 实验七配置：开启事务管理 + 循环依赖。
 * <p>
 * 使用一个简单的空操作 TransactionManager 来触发 @Transactional 的 AOP 代理创建，
 * 不需要真实数据库连接。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Configuration
@ComponentScan(basePackageClasses = Exp07Config.class)
@EnableTransactionManagement
public class Exp07Config {

    /**
     * 空操作 TransactionManager：仅用于触发 @Transactional 的 AOP 代理创建。
     * 实际事务操作不会生效，但代理创建流程与真实场景一致。
     */
    @Bean
    public PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
