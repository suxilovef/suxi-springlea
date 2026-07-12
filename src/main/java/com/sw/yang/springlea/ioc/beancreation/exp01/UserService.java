package com.sw.yang.springlea.ioc.beancreation.exp01;

import org.springframework.stereotype.Service;

/**
 * 被注入的依赖 Bean，用于验证构造器执行时字段注入尚未发生。
 *
 * @Author: jiu.fang
 * @CreateTime: 2026-07-12
 * @Version: 1.0
 */
@Service
public class UserService {

    public String whoAmI() {
        return "I am UserService";
    }
}
