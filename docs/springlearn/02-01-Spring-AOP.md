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

> 本文聚焦 Spring AOP 的通用原理、源码链路与面试高频问题，按 `Spring Boot 3.3.9 + Java 17 + Spring Framework 6.2.11` 的学习上下文整理。`。  
> 说明：版本标注用于学习与调试上下文，实际项目的官方配套关系仍应以 BOM 与依赖树为准。

---

## 一、AOP 核心概念

### 1.1 什么是 AOP

AOP（Aspect-Oriented Programming，面向切面编程）用于把日志、事务、权限、缓存、限流这类横切关注点从业务代码中抽离出来，避免在每个 Service / Controller 中重复编写同类逻辑。

它和 OOP 的关注点不同：

- OOP 解决的是纵向职责拆分。
- AOP 解决的是横向通用逻辑复用。

### 1.2 核心术语

| 术语 | 含义 |
|------|------|
| `JoinPoint` | 程序执行过程中可被切面拦截的位置 |
| `Pointcut` | 用来筛选哪些 `JoinPoint` 会被拦截 |
| `Advice` | 在连接点前后执行的增强逻辑 |
| `Aspect` | `Pointcut + Advice` 的组合 |
| `Weaving` | 把切面织入目标对象的过程 |
| `Target` | 原始业务对象 |
| `Proxy` | 织入增强后的代理对象 |

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

### 3.3 在 Bean 初始化后创建代理

Spring AOP 代理不是在扫描到 `@Aspect` 的那一刻就创建，而是在目标 Bean 初始化后创建。

典型链路：

```text
AbstractAutowireCapableBeanFactory.initializeBean()
  -> applyBeanPostProcessorsAfterInitialization()
  -> AnnotationAwareAspectJAutoProxyCreator.postProcessAfterInitialization()
  -> wrapIfNecessary()
  -> createProxy()
```

其中几个关键动作：

1. 找出当前容器中所有可用的 `Advisor`
2. 判断哪些 `Advisor` 能匹配当前 Bean
3. 如果存在匹配项，则使用 `ProxyFactory` 创建代理
4. 返回代理对象替换原始 Bean

### 3.4 代理类型选择

Spring AOP 会根据目标类型和配置选择代理方式：

- 目标类实现接口时，可用 JDK 动态代理
- 没有接口，或显式要求类代理时，使用 CGLIB

在 Spring Boot 的默认 AOP 配置下，通常会走类代理路径，即更常见地看到 `$$SpringCGLIB$$`。

### 3.5 方法调用时如何执行切面

代理创建完成后，真正的方法调用会进入拦截器链：

```text
代理对象.method()
  -> 获取当前方法对应的 Advisor
  -> 转换为 MethodInterceptor 链
  -> ReflectiveMethodInvocation.proceed()
  -> 按责任链顺序执行各个拦截器
  -> 最终调用目标方法
```

可以把 `proceed()` 理解为“递归推进责任链”：

- 还有下一个拦截器，就继续调用下一个
- 没有下一个拦截器，就真正调用目标方法

---

## 四、JDK 动态代理与 CGLIB

| 维度 | JDK 动态代理 | CGLIB |
|------|--------------|-------|
| 前提 | 目标类实现接口 | 不要求接口 |
| 原理 | 生成接口代理类 | 生成目标类子类 |
| 限制 | 只能代理接口方法 | 不能代理 `final` 类 / `final` 方法 |
| 常见标识 | `$Proxy...` | `$$SpringCGLIB$$...` |

常见判断题：

- `private` 方法不能被 Spring AOP 代理
- `final` 方法不能被 CGLIB 覆盖增强
- `this.xxx()` 的内部调用不会经过代理

---

## 五、常见失效场景

### 5.1 自调用失效

这是最常见的 AOP 面试题。

```java
@Service
public class UserService {

    public void methodA() {
        this.methodB();
    }

    @Transactional
    public void methodB() {
    }
}
```

`this.methodB()` 调用的是当前对象本身，不是代理对象，因此事务、缓存、日志等 AOP 增强都不会生效。

常见解决方式：

1. 自注入代理对象
2. `AopContext.currentProxy()`
3. 拆分成两个 Bean

示例：

```java
@Service
public class UserService {

    @Autowired
    @Lazy
    private UserService self;

    public void methodA() {
        self.methodB();
    }

    @Transactional
    public void methodB() {
    }
}
```

### 5.2 方法不可代理

以下情况常常导致增强失效：

- 目标方法是 `private`
- 目标方法是 `final`
- 目标类本身是 `final`
- 方法不在代理暴露的调用路径中

### 5.3 非 Spring 容器对象

如果对象是手动 `new` 出来的，而不是从容器获取，就没有代理能力。

```java
UserService service = new UserService(); // 不会有 AOP
```

### 5.4 调用时机太早

在这些阶段调用自身方法，通常也不会触发 AOP：

- 构造器
- `@PostConstruct`
- Bean 尚未完成代理替换之前的早期阶段

原因很简单：代理对象还没创建出来。

### 5.5 异常被吞掉

尤其是事务场景：

```java
@Around("...")
public Object dangerous(ProceedingJoinPoint pjp) {
    try {
        return pjp.proceed();
    } catch (Throwable ex) {
        log.error("error", ex);
        return null;
    }
}
```

这会带来两个问题：

- 上层以为方法成功执行了
- 事务看不到异常，可能不会回滚

正确做法通常是记录后继续抛出。

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

调试 Spring AOP，建议重点盯住下面几个点：

### 7.1 看代理何时创建

可从这些类入手：

- `AnnotationAwareAspectJAutoProxyCreator`
- `AbstractAutoProxyCreator`
- `ProxyFactory`
- `DefaultAopProxyFactory`

重点方法：

- `postProcessAfterInitialization()`
- `wrapIfNecessary()`
- `createProxy()`

### 7.2 看拦截器链何时执行

重点方法：

- `ReflectiveMethodInvocation.proceed()`
- `JdkDynamicAopProxy.invoke()`
- `CglibAopProxy.DynamicAdvisedInterceptor.intercept()`

### 7.3 先写最小可调试 Demo

建议最小化场景：

1. 一个 Service
2. 一个 Aspect
3. 一个 `@Around`
4. 一个能触发自调用问题的方法

这样最容易看清代理创建和执行链。

---

## 八、面试高频快答

### 8.1 Spring AOP 的原理是什么

一句话版本：

> Spring AOP 基于动态代理，在 Bean 初始化后由 `BeanPostProcessor` 判断是否需要代理；方法调用时通过拦截器链执行增强逻辑。

### 8.2 Spring AOP 和 AspectJ 的区别

一句话版本：

> Spring AOP 是运行时代理，只支持方法级连接点；AspectJ 是字节码织入，能力更强但复杂度更高。

### 8.3 `@Transactional` 为什么会失效

高频原因：

1. 自调用
2. 非 Spring Bean
3. 方法不可代理
4. 异常被吞
5. 调用时机太早

### 8.4 `@Around` 和 `@Before/@After` 怎么选

- 只做前置/后置固定逻辑，用 `@Before` / `@After`
- 需要控制是否执行目标方法、修改入参、改返回值、统一异常处理，用 `@Around`

### 8.5 多个切面怎么控制顺序

通过：

- `@Order`
- `Ordered`

数字越小，越先进入；退出时顺序相反。

---

## 九、延伸阅读

1. `AbstractAutoProxyCreator`
2. `AnnotationAwareAspectJAutoProxyCreator`
3. `DefaultAopProxyFactory`
4. `ReflectiveMethodInvocation`
5. `TransactionInterceptor`

---

> 本文档编写于 2026-07-09，版本上下文为 `Spring Boot 3.3.9 + Java 17 + Spring Framework 6.2.11`。
