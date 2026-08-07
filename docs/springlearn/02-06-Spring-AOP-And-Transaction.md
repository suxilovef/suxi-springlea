---
title: 02-06-Spring-AOP-And-Transaction
date: 2026-07-12 18:30:00
description: Spring AOP And Transaction
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-aop-transaction.svg
rank: 74
top_img: false
---
# Spring AOP 与声明式事务

> 本文承接 `02-05-Spring-AOP-Invocation-Chain.md`，专门解释 `@Transactional` 为什么本质上依赖 Spring AOP。  
> 主线是：`@EnableTransactionManagement` 注册事务基础设施 -> 生成事务 Advisor -> 匹配 `@Transactional` 方法 -> `TransactionInterceptor` 进入 AOP 拦截器链。  
> 边界说明：本文只讲"事务如何接入 AOP"。事务的传播行为、隔离级别、回滚规则、事务同步等完整语义见 `03-05-Spring-Transaction.md`。

---

## 一、先建立主线：事务为什么依赖 AOP

声明式事务的写法通常很简单：

```java
@Transactional
public void createOrder() {
    // business code
}
```

但 Spring 不会直接修改这个方法的字节码。

它的核心思路仍然是代理：

```text
调用方
  -> 事务代理对象
      -> TransactionInterceptor
          -> 开启事务
          -> 调用目标方法
          -> 提交或回滚事务
```

所以可以先记住一句话：

> `@Transactional` 的本质，是 Spring 通过 AOP 代理把 `TransactionInterceptor` 插入到目标方法调用链中。

这也是为什么事务会出现很多和 AOP 一样的失效场景（自调用、非 Spring Bean、`final`/`private` 方法、调用时机太早等）——其原理与排查见 `02-07-Spring-AOP-Limitations.md`，本文只展开事务特有的部分。

---

## 二、事务接入 AOP 的三个核心组件

声明式事务接入 Spring AOP，最关键的是三个组件：

| 组件 | 作用 |
|------|------|
| `BeanFactoryTransactionAttributeSourceAdvisor` | 事务 Advisor，连接 Pointcut 和 Advice |
| `TransactionAttributeSourcePointcut` | 判断哪些方法有事务属性 |
| `TransactionInterceptor` | 真正执行事务逻辑的 MethodInterceptor |

它们之间的关系可以简化为：

```text
BeanFactoryTransactionAttributeSourceAdvisor
  -> Pointcut: TransactionAttributeSourcePointcut
  -> Advice: TransactionInterceptor
```

也就是说，事务完全符合前面讲过的模型：

```text
Advisor = Pointcut + Advice
```

只不过这里的 Advice 是事务拦截器。

---

## 三、事务基础设施是如何注册的

普通 Spring 项目通常通过下面注解开启事务：

```java
@EnableTransactionManagement
@Configuration
public class TransactionConfig {
}
```

Spring Boot 项目中，只要满足数据源、事务管理器等条件，通常会自动启用事务相关配置。

`@EnableTransactionManagement` 的核心作用是导入事务配置类，注册事务 AOP 需要的基础设施。

可以简化理解为注册这些 Bean：

```text
AutoProxyRegistrar / ProxyTransactionManagementConfiguration
  -> InfrastructureAdvisorAutoProxyCreator
  -> BeanFactoryTransactionAttributeSourceAdvisor
  -> TransactionAttributeSource
  -> TransactionInterceptor
```

这里有两个重点：

1. 事务也需要自动代理创建器参与 Bean 生命周期。
2. 事务会注册一个 Advisor，用于匹配 `@Transactional` 方法。

### 和普通 `@Aspect` 的区别

普通切面来自：

```text
@Aspect
  -> 解析成 Advisor
```

声明式事务来自：

```text
@EnableTransactionManagement / 自动配置
  -> 注册事务 Advisor
```

来源不同，但进入 AOP 后的模型一致：

```text
Advisor 匹配 Bean
  -> 创建代理
  -> 方法调用进入 MethodInterceptor 链
```

---

## 四、BeanFactoryTransactionAttributeSourceAdvisor

`BeanFactoryTransactionAttributeSourceAdvisor` 是事务接入 AOP 的 Advisor。

它负责把事务的匹配规则和执行逻辑组合起来。

简化结构：

```text
BeanFactoryTransactionAttributeSourceAdvisor
  -> TransactionAttributeSourcePointcut
  -> TransactionInterceptor
```

其中：

| 部分 | 作用 |
|------|------|
| `TransactionAttributeSourcePointcut` | 判断方法是否有事务属性 |
| `TransactionInterceptor` | 执行事务开启、提交、回滚逻辑 |

当自动代理创建器处理某个 Bean 时，会判断这个事务 Advisor 是否能应用到当前 Bean。

如果 Bean 中存在匹配事务 Pointcut 的方法，就会创建代理对象。

---

## 五、TransactionAttributeSourcePointcut 如何匹配方法

事务 Pointcut 的核心不是普通的 `execution(...)` 表达式，而是判断方法上是否存在事务属性。

事务属性通常来自：

```java
@Transactional
```

可能标在：

- 接口方法上。
- 实现类方法上。
- 类上。
- 父类或接口层级上。

可以简化理解为：

```text
TransactionAttributeSourcePointcut
  -> 查询 TransactionAttributeSource
      -> 当前方法是否能解析到事务属性
          -> 能：匹配
          -> 不能：不匹配
```

示例：

```java
@Service
public class OrderService {

    @Transactional
    public void createOrder() {
    }
}
```

`createOrder()` 能解析出事务属性，所以事务 Advisor 可以应用到这个方法。

### 类级别 `@Transactional`

如果注解放在类上：

```java
@Transactional
@Service
public class OrderService {

    public void createOrder() {
    }
}
```

类中的 public 方法通常也能继承事务属性。

但具体解析优先级和继承规则比这里更细，本文不展开完整事务注解解析，只关注它如何接入 AOP。

---

## 六、TransactionInterceptor 如何进入调用链

在代理创建阶段，事务 Advisor 会被加入代理配置。

方法调用时，事务 Advisor 会转换成拦截器：

```text
BeanFactoryTransactionAttributeSourceAdvisor
  -> TransactionInterceptor
```

调用链可以简化为：

```text
orderService.createOrder()
  -> 代理对象
  -> MethodInterceptor 链
      -> TransactionInterceptor.invoke()
          -> 创建或加入事务
          -> invocation.proceed()
              -> 目标方法 createOrder()
          -> 提交事务
          -> 异常时回滚事务
```

所以 `TransactionInterceptor` 本质就是一个 `MethodInterceptor`。

它和普通 `@Around` 很像：

```java
public Object invoke(MethodInvocation invocation) throws Throwable {
    // 开启事务
    try {
        Object result = invocation.proceed();
        // 提交事务
        return result;
    } catch (Throwable ex) {
        // 回滚事务
        throw ex;
    }
}
```

真实源码更复杂，会处理事务管理器、事务属性、回滚规则、事务同步等，但主线就是这样。

---

## 七、为什么 @Transactional 会自调用失效

因为 `@Transactional` 也是 AOP 代理，所以它遵守同样的代理规则。

示例：

```java
@Service
public class OrderService {

    public void createOrder() {
        saveOrder();
    }

    @Transactional
    public void saveOrder() {
        System.out.println("save order");
    }
}
```

外部调用 `createOrder()` 时：

```text
调用方
  -> 代理对象
      -> createOrder()
          -> this.saveOrder()
```

`this.saveOrder()` 没有经过代理对象，因此不会进入 `TransactionInterceptor`。

解决方式和普通 AOP 一致（拆分到另一个 Bean / 注入自身代理 / `AopContext.currentProxy()`），代码与对比见 `02-07-Spring-AOP-Limitations.md` 的自调用解决方案总表。核心思路是让 `saveOrder()` 的调用重新经过代理对象。

---

## 八、异常语义为什么会影响事务回滚

事务拦截器通常根据异常判断提交还是回滚。

简化模型：

```text
目标方法正常返回
  -> 提交事务

目标方法抛出需要回滚的异常
  -> 回滚事务
```

因此，如果业务代码吞掉异常，事务拦截器就看不到异常。

示例：

```java
@Transactional
public void createOrder() {
    try {
        saveOrder();
        saveLog();
    } catch (Exception ex) {
        log.error("create order failed", ex);
    }
}
```

如果异常被捕获后没有继续抛出，`TransactionInterceptor` 看到的是“方法正常返回”，就可能提交事务。

所以常见建议是：

- 不要随意吞掉需要触发回滚的异常。
- 如果捕获异常后仍希望回滚，需要重新抛出异常，或显式标记回滚。
- `@Around` 切面也不要无意中吞掉异常，否则会改变事务语义。

---

## 九、事务和普通切面的顺序问题

事务拦截器也是 AOP 拦截器链中的一员。

如果一个方法同时匹配日志切面和事务切面，调用链可能类似：

```text
LogAspect
  -> TransactionInterceptor
      -> target method
```

也可能是：

```text
TransactionInterceptor
  -> LogAspect
      -> target method
```

顺序会影响：

- 日志是在事务外还是事务内打印。
- 异常是先被日志切面捕获还是先被事务拦截器处理。
- 性能统计是否包含事务开启和提交耗时。

关键是记住：

> 事务不是特殊魔法，它也是拦截器链中的一个 MethodInterceptor。

---

## 十、调试 @Transactional 的 AOP 链路

建议断点（只保留事务专属；通用代理断点见 02-07 §十 全链路断点总表）：

```text
BeanFactoryTransactionAttributeSourceAdvisor#getPointcut()
TransactionAttributeSourcePointcut#matches()
TransactionInterceptor#invoke()
```

重点观察：

| 观察点 | 含义 |
|--------|------|
| 当前 Bean 是否被代理 | 没代理就不会有事务拦截 |
| 是否存在事务 Advisor | 没有 Advisor 就无法进入 AOP 链 |
| 目标方法是否匹配事务 Pointcut | `@Transactional` 是否被解析到 |
| 拦截器链中是否有 `TransactionInterceptor` | 判断事务是否接入调用链 |
| 异常是否继续抛出 | 判断回滚语义是否被破坏 |

---

## 十一、面试高频快答

**`@Transactional` 为什么依赖 AOP？**

> 因为 Spring 通过代理对象把 `TransactionInterceptor` 插入到目标方法调用链中，在方法执行前后完成事务开启、提交和回滚。

**事务在 Spring AOP 中对应哪个 Advisor？**

> 常见的是 `BeanFactoryTransactionAttributeSourceAdvisor`，它包含事务 Pointcut 和 `TransactionInterceptor`。

**事务 Pointcut 根据什么匹配方法？**

> 它通过 `TransactionAttributeSource` 判断当前方法或类是否能解析出事务属性，比如是否存在 `@Transactional`。

**`TransactionInterceptor` 是什么？**

> 它是事务 Advice 的运行时执行形态，本质是一个 `MethodInterceptor`，负责在 `invocation.proceed()` 前后处理事务。

**为什么 `@Transactional` 自调用会失效？**

> 因为自调用没有经过 Spring 代理对象，也就不会进入 `TransactionInterceptor`。

**为什么吞异常可能导致事务不回滚？**

> 因为事务拦截器依赖异常传播来判断是否回滚，如果异常被业务代码或其他切面吞掉，事务拦截器可能认为方法正常返回。

---

## 十二、本篇总结

声明式事务接入 AOP 的主线如下：

```text
@EnableTransactionManagement / Spring Boot 自动配置
  -> 注册事务基础设施
  -> 创建 BeanFactoryTransactionAttributeSourceAdvisor
      -> TransactionAttributeSourcePointcut
      -> TransactionInterceptor
  -> 自动代理创建器匹配事务 Advisor
  -> 为命中 @Transactional 的 Bean 创建代理
  -> 方法调用进入 TransactionInterceptor
  -> 开启、提交或回滚事务
```

必须掌握的点：

- `@Transactional` 本质依赖 Spring AOP 代理。
- 事务 Advisor 同样符合 `Advisor = Pointcut + Advice` 模型。
- `TransactionInterceptor` 是事务真正执行的拦截器。
- 自调用、非 Spring Bean、异常被吞都会影响事务生效。
- 本文只讲事务如何接入 AOP，不展开完整事务传播和隔离级别体系。
