---
title: 02-04-Spring-AOP-Advisor-Advice-Pointcut
date: 2026-07-12 17:30:00
description: Spring AOP Advisor Advice Pointcut
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-aop-advisor.svg
rank: 76
top_img: false
---
# Spring AOP Advisor、Advice、Pointcut

> 本文承接 `02-03-Spring-AOP-AutoProxyCreator.md`，专门拆解 Spring AOP 的元模型：`Advisor`、`Advice`、`Pointcut`、`MethodInterceptor` 到底是什么关系。  
> 主线是：Advisor = Pointcut + Advice -> Pointcut 负责匹配 -> Advice 表示增强逻辑 -> MethodInterceptor 是运行时执行形态。

---

## 一、先建立主线：Advisor 到底是什么

在 Spring AOP 中，`Advisor` 是一个非常核心的概念。

一句话概括：

> `Advisor` = `Pointcut` + `Advice`。

它同时回答两个问题：

| 问题 | 对应组件 |
|------|----------|
| 哪些类、哪些方法需要增强 | `Pointcut` |
| 命中后执行什么增强逻辑 | `Advice` |

整体关系如下：

```text
@Aspect / @Transactional / 手动 Advisor
  -> 解析成 Advisor
      -> Pointcut 判断是否匹配目标方法
      -> Advice 表示增强逻辑
          -> 适配成 MethodInterceptor
```

前面 `02-03-Spring-AOP-AutoProxyCreator.md` 讲过，自动代理创建器判断一个 Bean 是否需要代理，本质就是看：

```text
当前 Bean 是否存在可应用的 Advisor
```

所以 `Advisor` 是 Spring AOP 连接“匹配规则”和“增强执行”的桥梁。

---

## 二、Advice：增强逻辑的抽象

`Advice` 表示增强逻辑，也就是在目标方法前后要执行的动作。

常见注解式 Advice：

| 注解 | 含义 |
|------|------|
| `@Before` | 目标方法执行前执行 |
| `@After` | 目标方法结束后执行，无论正常还是异常 |
| `@AfterReturning` | 目标方法正常返回后执行 |
| `@AfterThrowing` | 目标方法抛出异常后执行 |
| `@Around` | 包围目标方法执行，能力最强 |

示例：

```java
@Aspect
@Component
public class LogAspect {

    @Before("execution(* com.example..*Service.*(..))")
    public void before() {
        System.out.println("before");
    }
}
```

这里的 `before()` 方法体就是增强逻辑。

但 Spring AOP 内部不会直接拿 `@Before` 方法裸跑，而是会解析成统一对象模型：

```text
@Before 方法
  -> Advice
      -> Advisor 中的一部分
```

### Advice 不是匹配规则

`Advice` 只表示“要做什么”，不负责判断“对谁做”。

例如：

```java
@Before("execution(* com.example..*Service.*(..))")
public void before() {
    System.out.println("before");
}
```

这里实际包含两部分：

| 内容 | 角色 |
|------|------|
| `execution(* com.example..*Service.*(..))` | `Pointcut` |
| `before()` 方法体 | `Advice` |

所以不要把 Advice 和 Pointcut 混在一起。

---

## 三、Pointcut：匹配规则的抽象

`Pointcut` 负责判断一个类或方法是否需要被增强。

它通常包含两个层面的匹配：

```text
Pointcut
  -> ClassFilter：判断目标类是否可能匹配
  -> MethodMatcher：判断目标方法是否匹配
```

示例：

```java
@Before("execution(* com.example..*Service.*(..))")
public void before() {
}
```

这个表达式表示：

```text
匹配 com.example 包及子包下
所有以 Service 结尾的类中的所有方法
```

### 常见 Pointcut 表达式

| 表达式 | 作用 |
|--------|------|
| `execution(...)` | 按方法签名匹配，最常用 |
| `within(...)` | 按类型范围匹配 |
| `this(...)` | 按代理对象类型匹配 |
| `target(...)` | 按目标对象类型匹配 |
| `args(...)` | 按运行时参数类型匹配 |
| `@annotation(...)` | 匹配带指定注解的方法 |
| `@within(...)` | 匹配带指定注解的类中的方法 |
| `bean(...)` | 按 Spring Bean 名称匹配 |

常用示例：

```java
@Pointcut("execution(* com.example..service..*(..))")
public void serviceMethods() {
}

@Pointcut("@annotation(org.springframework.transaction.annotation.Transactional)")
public void transactionalMethods() {
}
```

### 静态匹配与动态匹配

Spring AOP 会尽量先做静态匹配。

```text
能提前判断的：创建代理或构建拦截器链时判断
依赖运行时参数的：方法调用时再判断
```

例如 `execution(...)` 多数可以静态判断，而 `args(...)` 可能依赖运行时参数。

---

## 四、Advisor：组合 Pointcut 和 Advice

`Advisor` 把 `Pointcut` 和 `Advice` 组合成一条完整增强规则。

常见接口：

```java
public interface Advisor {
    Advice getAdvice();
}
```

带 Pointcut 的 Advisor：

```java
public interface PointcutAdvisor extends Advisor {
    Pointcut getPointcut();
}
```

所以一个 `PointcutAdvisor` 可以理解为：

```text
PointcutAdvisor
  -> Pointcut：匹配哪些方法
  -> Advice：匹配后执行什么增强
```

### 一个注解切面如何变成 Advisor

示例：

```java
@Aspect
@Component
public class LogAspect {

    @Before("execution(* com.example..*Service.*(..))")
    public void before() {
        System.out.println("before");
    }
}
```

Spring 解析后可以理解为：

```text
Advisor
  -> Pointcut: execution(* com.example..*Service.*(..))
  -> Advice: before()
```

如果一个切面里有多个通知方法，就可能解析出多个 Advisor：

```text
LogAspect
  -> Advisor 1: Pointcut + Before Advice
  -> Advisor 2: Pointcut + AfterReturning Advice
```

---

## 五、Advice 如何适配成 MethodInterceptor

`Advice` 是增强逻辑的抽象，但 Spring AOP 运行时真正执行的是 `MethodInterceptor`。

`MethodInterceptor` 的核心方法是：

```java
Object invoke(MethodInvocation invocation) throws Throwable;
```

运行时调用链只认识一种统一模型：

```text
MethodInterceptor
  -> invocation.proceed()
```

因此不同类型的 Advice 会被适配成不同的拦截器。

| Advice 类型 | 运行时适配后的形态 |
|-------------|--------------------|
| `@Before` | 前置拦截器 |
| `@AfterReturning` | 返回后拦截器 |
| `@AfterThrowing` | 异常拦截器 |
| `@Around` | 环绕拦截器，本身最接近 `MethodInterceptor` |
| `@After` | finally 风格拦截器 |

可以简化为：

```text
Advisor
  -> Advice
      -> AdvisorAdapter
          -> MethodInterceptor
```

### 为什么需要适配

不同 Advice 语义不同：

- `@Before` 只需要在目标方法前执行。
- `@AfterReturning` 只在正常返回后执行。
- `@AfterThrowing` 只在异常时执行。
- `@Around` 可以决定是否继续调用目标方法。

但拦截器链执行时，需要统一入口。

所以 Spring 把它们统一成：

```text
MethodInterceptor 链
```

这样 `ReflectiveMethodInvocation#proceed()` 只需要按顺序执行拦截器，不需要关心每个 Advice 原来是什么注解。

---

## 六、Advisor 在自动代理创建中的作用

自动代理创建器判断一个 Bean 是否需要代理，核心逻辑就是找 Advisor。这一事实可以概括为：

```text
有 Advisor 匹配当前 Bean -> 创建代理
没有 Advisor 匹配当前 Bean -> 返回原始 Bean
```

代理对象不是因为某个类上有 `@Service` 就创建，真正依据是：

```text
当前 Bean 的方法是否命中了某个 Advisor 的 Pointcut
```

`wrapIfNecessary() -> findEligibleAdvisors() -> findCandidateAdvisors() / findAdvisorsThatCanApply() -> createProxy()` 的完整调用链与各方法职责，见 `02-03-Spring-AOP-AutoProxyCreator.md`。

---

## 七、和前后几篇的关系

这一篇主要讲 AOP 的元模型，不展开完整运行时调用链。

```text
02-02-Spring-AOP-Proxy.md
  -> 代理对象如何拦截方法调用

02-03-Spring-AOP-AutoProxyCreator.md
  -> 代理对象如何被 Spring 自动创建

02-04-Spring-AOP-Advisor-Advice-Pointcut.md
  -> Advisor、Advice、Pointcut、MethodInterceptor 的关系

02-05-Spring-AOP-Invocation-Chain.md
  -> MethodInterceptor 链如何通过 proceed() 执行
```

本篇只讲到：

```text
Advice 会被适配成 MethodInterceptor
```

至于多个 `MethodInterceptor` 如何一层层执行，放到下一篇专门分析。

---

## 八、面试高频快答

**Advisor、Advice、Pointcut 分别是什么？**

> `Pointcut` 负责匹配哪些类和方法，`Advice` 表示增强逻辑，`Advisor` 把两者组合起来，形成一条完整的增强规则。

**为什么说 Advisor = Pointcut + Advice？**

> 因为一个 Advisor 既要知道“对谁增强”，也要知道“增强什么逻辑”。前者由 Pointcut 表示，后者由 Advice 表示。

**MethodInterceptor 和 Advice 是什么关系？**

> Advice 是用户视角的增强逻辑抽象，MethodInterceptor 是 Spring AOP 运行时真正执行的统一拦截器形态。

**`@Before`、`@AfterReturning` 最终怎么执行？**

> 它们会先被解析成 Advice，再通过适配器转换成 MethodInterceptor，最终放入拦截器链中执行。

**一个 Bean 是否需要代理由什么决定？**

> 主要看是否存在能应用到这个 Bean 的 Advisor，也就是 Advisor 中的 Pointcut 是否匹配当前 Bean 的方法。

---

## 九、本篇总结

本文的核心是建立 Spring AOP 元模型：

```text
Advisor
  -> Pointcut：匹配规则
  -> Advice：增强逻辑
      -> MethodInterceptor：运行时执行形态
```

必须掌握的点：

- `Advice` 只表示增强逻辑，不负责匹配。
- `Pointcut` 只表示匹配规则，不负责执行增强。
- `Advisor` 把 `Pointcut` 和 `Advice` 组合起来。
- Spring AOP 判断是否创建代理，本质是判断是否存在可应用的 Advisor。
- 运行时真正执行的是 `MethodInterceptor` 链。
