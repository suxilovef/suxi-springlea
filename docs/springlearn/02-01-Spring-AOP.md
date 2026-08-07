---
title: 02-01-Spring-AOP
date: 2026-07-09 20:07:05
description: Spring-AOP
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-aop.svg
rank: 79
top_img: false
---
# Spring AOP 知识点

> 本文聚焦 Spring AOP 的通用原理、源码链路与面试高频问题，按 `Spring Boot 3.3.9 + Java 17 + Spring Framework 6.2.11` 的学习上下文整理。  
> 说明：版本标注用于学习与调试上下文，实际项目的官方配套关系仍应以 BOM 与依赖树为准。

---

## 本系列结构索引

本文是本系列的开篇（总览篇），后续按下面顺序递进：

```text
02-01 AOP 总览        -> 概念、术语、宏观链路（本文）
02-02 代理机制        -> 代理对象如何工作、JDK/CGLIB（对比 SSOT）
02-03 自动代理创建    -> 代理对象如何被 Spring 创建
02-04 元模型          -> Advisor / Advice / Pointcut 的关系
02-05 拦截器链        -> proceed() 运行时推进
02-06 事务与 AOP      -> @Transactional 接入
02-07 边界与排查      -> 失效场景、排查路径（SSOT）
```

公共知识（JDK/CGLIB 对比、失效场景、调试断点、切面顺序）都在唯一一篇中完整说明，其余各篇只保留与自身主题直接相关的部分并互相引用。

---

## 一、AOP 核心概念

### 1.1 什么是 AOP

AOP（Aspect-Oriented Programming，面向切面编程）用于把日志、事务、权限、缓存、限流这类横切关注点从业务代码中抽离出来，避免在每个 Service / Controller 中重复编写同类逻辑。

它和 OOP 的关注点不同：

- OOP 解决的是纵向职责拆分。
- AOP 解决的是横向通用逻辑复用。

### 1.2 核心术语

| 英文术语 | 中文名 | 含义 |
|------|------|------|
| `JoinPoint` | 连接点 | 程序执行过程中可被切面拦截的位置 |
| `Pointcut` | 切入点（切点） | 用来筛选哪些 `JoinPoint` 会被拦截 |
| `Advice` | 通知（增强） | 在连接点前后执行的增强逻辑 |
| `Aspect` | 切面 | `Pointcut + Advice` 的组合 |
| `Weaving` | 织入 | 把切面织入目标对象的过程 |
| `Target` | 目标对象 | 原始业务对象 |
| `Proxy` | 代理对象 | 织入增强后的代理对象 |

### 1.3 五种常见 Advice

| 类型 | 注解 | 时机 | 典型用途 |
|------|------|------|----------|
| Before | `@Before` | 方法执行前 | 参数检查、审计 |
| AfterReturning | `@AfterReturning` | 方法正常返回后 | 记录结果、补充日志 |
| AfterThrowing | `@AfterThrowing` | 方法抛异常后 | 异常监控、告警 |
| After | `@After` | 方法结束后 | 资源清理，类似 `finally` |
| Around | `@Around` | 方法前后全包裹 | 事务、缓存、限流、耗时统计 |

执行顺序通常可以理解为：

```text
@Around(before)
  -> @Before
  -> 目标方法
  -> @AfterReturning / @AfterThrowing
  -> @After
-> @Around(after)
```

`@Around` 能力最强，但也最容易写错，尤其是漏掉 `proceed()`、吞异常、错误修改返回值。

---

## 二、Spring AOP 与 AspectJ 的区别

| 维度 | Spring AOP | AspectJ |
|------|-----------|---------|
| 织入时机 | 运行时 | 编译期 / 类加载期 / 运行时 |
| 实现方式 | 动态代理 | 字节码增强 |
| 连接点范围 | 仅方法执行 | 方法、字段、构造器、静态初始化等 |
| 依赖 | Spring 容器 | AspectJ 编译器 / Weaver |
| 复杂度 | 低 | 高 |
| 典型场景 | 事务、缓存、权限、日志 | 更底层、更广范围的横切需求 |

结论：

- 绝大多数业务系统场景，Spring AOP 足够。
- 只有在“方法级拦截不够用”时，才需要考虑 AspectJ。

---

## 三、Spring AOP 的核心执行链

> 本节重点不是记类名，而是理解“什么时候注册自动代理创建器、什么时候创建代理、调用时如何进入拦截器链”。

### 3.1 入口：开启自动代理

常见入口有两类：

1. 显式配置 `@EnableAspectJAutoProxy`
2. 通过 Spring Boot 的 AOP 自动配置启用

`@EnableAspectJAutoProxy` 的关键点是：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(AspectJAutoProxyRegistrar.class)
public @interface EnableAspectJAutoProxy {
    boolean proxyTargetClass() default false;
    boolean exposeProxy() default false;
}
```

它本身不创建代理，而是借助 `AspectJAutoProxyRegistrar` 向容器注册自动代理创建器。

### 3.2 注册自动代理创建器

核心链路可以概括为：

```text
@EnableAspectJAutoProxy
  -> @Import(AspectJAutoProxyRegistrar)
  -> registerBeanDefinitions()
  -> 注册 AnnotationAwareAspectJAutoProxyCreator 的 BeanDefinition
```

`AnnotationAwareAspectJAutoProxyCreator` 是整个 Spring AOP 的关键基础设施，它本质上是一个 `BeanPostProcessor`。

这意味着：

- 它会参与普通 Bean 的初始化过程。
- 它可以在 Bean 初始化完成后，判断当前 Bean 是否需要代理。

### 3.3 代理创建、类型选择与调用链（宏观链路）

Spring AOP 代理不是在扫描到 `@Aspect` 的那一刻就创建，而是在目标 Bean 初始化后创建。整个链路可以压缩成一张宏观图：

```text
Bean 初始化完成
  -> postProcessAfterInitialization()
  -> 判断是否有 Advisor 匹配
  -> 有则创建代理（ProxyFactory）
  -> 代理对象替换原始 Bean
      -> 方法调用进入 MethodInterceptor 链
      -> proceed() 递归推进
      -> 最终调用目标方法
```

代理类型选择、方法调用细节与 Spring Boot 默认行为见 `02-02-Spring-AOP-Proxy.md`；`wrapIfNecessary()` 等创建时机细节见 `02-03-Spring-AOP-AutoProxyCreator.md`。

---

## 四、JDK 动态代理与 CGLIB

Spring AOP 常用两种代理：JDK 动态代理（基于接口）与 CGLIB（基于目标类生成子类）。

完整对比表、选择逻辑与 Spring Boot 默认 CGLIB 的行为说明见 `02-02-Spring-AOP-Proxy.md`；两种代理各自的失效边界见 `02-07-Spring-AOP-Limitations.md`。

---

## 五、常见失效场景

本节只给出失效场景索引，不展开细节；原理、解决方式与排查在各篇 SSOT 位置：

| 场景 | 一句话原因 | 详见 |
|------|-----------|------|
| 自调用 | `this` 调用不经过代理对象 | 02-02 §六（原理）、02-07 §三（解决） |
| 非 Spring Bean | 手动 `new` 不经过容器 / BeanPostProcessor | 02-07 §二 |
| 方法不可代理 | `private` / `final` / `static` 的代理限制 | 02-07 §五、§六 |
| 调用时机太早 | 构造器 / `@PostConstruct` 时代理尚未创建 | 02-07 §四 |
| 异常被吞 | 事务拦截器看不到异常，不会回滚 | 02-06 §八 |

---

## 六、设计与性能建议

### 6.1 Pointcut 要尽量收敛

不建议：

```java
@Pointcut("execution(* com.example..*.*(..))")
public void tooBroad() {}
```

更推荐：

```java
@Pointcut("execution(* com.example..service.*.*(..))")
public void serviceLayer() {}

@Pointcut("@annotation(org.springframework.transaction.annotation.Transactional)")
public void transactionalMethod() {}
```

原因：

- Bean 初始化时需要做匹配判断
- Pointcut 越宽，匹配成本越高
- 范围越大，误拦截风险越高

### 6.2 切面尽量无状态

Aspect 默认通常是单例 Bean，不应在实例字段中保存请求级数据：

```java
@Aspect
@Component
public class DangerousAspect {
    private String currentUser; // 不安全
}
```

如果确实需要线程隔离数据，用 `ThreadLocal`，并在结束后及时 `remove()`。

### 6.3 Advice 中避免重操作

比如：

- 无条件序列化全部参数
- 每次都做反射扫描
- 每次都创建大量临时对象

更稳妥的写法是：

- 先判断日志级别
- 能缓存的元数据提前缓存
- 只在必要时读取方法签名与注解

### 6.4 `@Around` 中保持异常语义

推荐模板：

```java
@Around("...")
public Object around(ProceedingJoinPoint pjp) throws Throwable {
    try {
        return pjp.proceed();
    } catch (Throwable ex) {
        log.error("business error", ex);
        throw ex;
    } finally {
        cleanup();
    }
}
```

---

## 七、调试建议

调试 Spring AOP 的总体思路：

1. **看代理何时创建**：在 Bean 初始化后断点确认是否进入代理创建流程（注册与创建断点见 02-03）。
2. **看拦截器链何时执行**：在方法调用时断点确认是否进入拦截器链（代理入口断点见 02-02，链路推进见 02-05）。
3. **先写最小可调试 Demo**：一个 Service + 一个 Aspect + 一个 `@Around` + 一个能触发自调用问题的方法，最容易看清代理创建和执行链。

各主题的具体断点与全链路断点总表见 02-07 §十。

---

## 八、面试高频快答

### 8.1 Spring AOP 的原理是什么

一句话版本：

> Spring AOP 基于动态代理，在 Bean 初始化后由 `BeanPostProcessor` 判断是否需要代理；方法调用时通过拦截器链执行增强逻辑。

### 8.2 Spring AOP 和 AspectJ 的区别

一句话版本：

> Spring AOP 是运行时代理，只支持方法级连接点；AspectJ 是字节码织入，能力更强但复杂度更高。

### 8.4 `@Around` 和 `@Before/@After` 怎么选

- 只做前置/后置固定逻辑，用 `@Before` / `@After`
- 需要控制是否执行目标方法、修改入参、改返回值、统一异常处理，用 `@Around`

> 注：`@Transactional` 为什么会失效的完整答案见 02-07；多个切面的顺序控制见 02-07 §八。

---

## 九、延伸阅读

1. `AbstractAutoProxyCreator`
2. `AnnotationAwareAspectJAutoProxyCreator`
3. `DefaultAopProxyFactory`
4. `ReflectiveMethodInvocation`
5. `TransactionInterceptor`

---

> 本文档编写于 2026-07-09，版本上下文为 `Spring Boot 3.3.9 + Java 17 + Spring Framework 6.2.11`。
