---
title: 02-03-Spring-AOP-AutoProxyCreator
date: 2026-07-12 16:45:00
description: Spring AOP AutoProxyCreator
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-aop-autoproxy.svg
rank: 77
top_img: false
---
# Spring AOP 自动代理创建器

> 本文承接 `02-02-Spring-AOP-Proxy.md`，重点回答：Spring 在什么时候、由谁、根据什么条件把普通 Bean 包装成代理 Bean。  
> 主线是：注册自动代理创建器 -> 参与 Bean 生命周期 -> 查找并匹配 Advisor -> 创建代理 -> 处理提前代理与常见问题。

---

## 一、先建立主线：AutoProxyCreator 解决什么问题

上一篇讲的是代理对象如何工作，这一篇讲代理对象如何被 Spring 自动创建。

核心结论可以先记住：

> `AnnotationAwareAspectJAutoProxyCreator` 本质是一个 `BeanPostProcessor`，它在 Bean 初始化后判断当前 Bean 是否匹配 Advisor，匹配则创建代理对象并把代理对象放回容器。

完整流程可以压缩成：

```text
注册自动代理创建器
  -> 发现 Advisor
  -> Bean 初始化后判断是否需要代理
  -> 匹配当前 Bean 可应用的 Advisor
  -> 创建 ProxyFactory
  -> 生成 JDK / CGLIB 代理
  -> 容器暴露代理对象
```

这篇文章要解决三个问题：

1. 自动代理创建器是怎么进入容器的。
2. 它如何找到 `@Aspect` 和 `Advisor`。
3. 它如何判断某个 Bean 是否需要创建代理。

### 什么是 AutoProxyCreator

`AutoProxyCreator` 可以直译为“自动代理创建器”。

它的职责不是执行业务增强，而是在 Bean 生命周期中自动创建代理对象。

几个概念要区分开：

| 组件 | 核心职责 |
|------|----------|
| `AutoProxyCreator` | 判断 Bean 是否需要代理，并创建代理对象 |
| `Advisor` | 封装切点和增强逻辑 |
| `Pointcut` | 判断类和方法是否匹配 |
| `Advice` | 表示增强逻辑 |
| `MethodInterceptor` | 运行时真正执行的拦截器 |
| `ProxyFactory` | 根据代理配置生成代理对象 |

可以把它理解为一个代理加工厂：

```text
原始 Bean
  -> AutoProxyCreator 判断是否需要增强
      -> 不需要：返回原始 Bean
      -> 需要：返回代理 Bean
```

---

## 二、核心继承结构与职责边界

Spring 中最常见的自动代理创建器是：

```text
AnnotationAwareAspectJAutoProxyCreator
```

它的继承结构可以简化为：

```text
AnnotationAwareAspectJAutoProxyCreator
  -> AspectJAwareAdvisorAutoProxyCreator
    -> AbstractAdvisorAutoProxyCreator
      -> AbstractAutoProxyCreator
        -> ProxyProcessorSupport
          -> ProxyConfig
```

每一层职责不同：

| 类 | 主要职责 |
|----|----------|
| `ProxyConfig` | 保存代理相关配置，如 `proxyTargetClass`、`exposeProxy` |
| `ProxyProcessorSupport` | 提供代理类加载器、接口评估等基础能力 |
| `AbstractAutoProxyCreator` | 实现 `BeanPostProcessor` 主流程，负责包装 Bean |
| `AbstractAdvisorAutoProxyCreator` | 基于 Advisor 判断 Bean 是否需要代理 |
| `AspectJAwareAdvisorAutoProxyCreator` | 增加 AspectJ 语义处理，如排序和特殊 Advisor 处理 |
| `AnnotationAwareAspectJAutoProxyCreator` | 支持解析 `@Aspect` 注解风格的切面 |

读源码时最重要的是三层：

```text
AnnotationAwareAspectJAutoProxyCreator
  -> 支持 @Aspect
AbstractAdvisorAutoProxyCreator
  -> 查找和匹配 Advisor
AbstractAutoProxyCreator
  -> 接入 Bean 生命周期并创建代理
```

### 为什么能参与 Bean 生命周期

关键原因是 `AbstractAutoProxyCreator` 实现了 `SmartInstantiationAwareBeanPostProcessor`。

它可以参与两个重要阶段：

```text
getEarlyBeanReference()
postProcessAfterInitialization()
```

普通场景下，主要在初始化后创建代理：

```text
postProcessAfterInitialization()
  -> wrapIfNecessary()
  -> createProxy()
```

循环依赖场景下，可能提前暴露代理：

```text
getEarlyBeanReference()
  -> wrapIfNecessary()
  -> createProxy()
```

---

## 三、自动代理创建器是如何注册的

自动代理创建器必须先被注册成 BeanPostProcessor，后续才能参与其他 Bean 的生命周期。

### `@EnableAspectJAutoProxy` 入口

普通 Spring 项目常见入口是：

```java
@EnableAspectJAutoProxy
@Configuration
public class AopConfig {
}
```

它会导入：

```text
AspectJAutoProxyRegistrar
```

注册逻辑最终会进入：

```text
AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary()
```

注册的核心 Bean 是：

```text
AnnotationAwareAspectJAutoProxyCreator
```

### Spring Boot 入口

Spring Boot 中，如果引入 AOP 相关依赖，通常由自动配置完成注册。

典型依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

常见配置：

```yaml
spring:
  aop:
    auto: true
    proxy-target-class: true
```

### 自动代理创建器的升级机制

Spring 可能有多个入口注册自动代理创建器，例如事务、AOP、Advisor 自动代理等。

为了避免重复注册，Spring 使用固定 BeanName：

```text
org.springframework.aop.config.internalAutoProxyCreator
```

并通过升级机制保留能力更强的实现。

可以简单理解为：

```text
InfrastructureAdvisorAutoProxyCreator
  < AspectJAwareAdvisorAutoProxyCreator
  < AnnotationAwareAspectJAutoProxyCreator
```

如果后续注册的自动代理创建器能力更强，就会替换 BeanDefinition 的 class。

---

## 四、Bean 生命周期中的代理包装流程

自动代理创建器真正发挥作用的位置，是 Bean 初始化后的后置处理。

核心入口：

```java
AbstractAutoProxyCreator#postProcessAfterInitialization(Object bean, String beanName)
```

简化流程：

```text
postProcessAfterInitialization()
  -> wrapIfNecessary(bean, beanName, cacheKey)
      -> 判断是否跳过
      -> 查找可应用 Advisor
      -> 有 Advisor：创建代理
      -> 无 Advisor：返回原始 Bean
```

### `wrapIfNecessary()` 是核心判断

`wrapIfNecessary()` 解决的是一个问题：

> 当前 Bean 到底需不需要被代理？

简化逻辑：

```text
如果当前 Bean 已经被处理过
  -> 返回原始 Bean
如果当前 Bean 是基础设施类
  -> 跳过
如果 shouldSkip(beanClass, beanName)
  -> 跳过
查找当前 Bean 可应用的 Advisor
如果没有 Advisor
  -> 返回原始 Bean
如果有 Advisor
  -> createProxy()
```

### 哪些 Bean 不会被代理

常见跳过场景：

| 场景 | 原因 |
|------|------|
| 基础设施 Bean | Spring AOP 内部组件不应该再被代理 |
| Aspect 自身 | 切面类本身通常不作为业务目标代理 |
| 没有匹配 Advisor | 没有增强逻辑需要应用 |
| 已经提前代理过 | 避免循环依赖场景重复代理 |

所以 Spring 不会给所有 Bean 都创建代理，只会代理真正命中 Advisor 的 Bean。

---

## 五、Advisor 的发现、匹配与排序

一个 Bean 是否需要代理，不是看它有没有注解，也不是看它是不是 Service，而是看有没有 Advisor 能应用到它。

核心过程：

```text
找到候选 Advisor
  -> 判断 Pointcut 是否匹配当前 Bean
  -> 多个 Advisor 排序
  -> 组成拦截器链
```

### Advisor 是怎么找到的

入口通常在：

```text
AbstractAdvisorAutoProxyCreator#findCandidateAdvisors()
```

它会从容器中查找 Advisor 类型的 Bean。

对于 `@Aspect` 注解风格，`AnnotationAwareAspectJAutoProxyCreator` 会额外解析切面类，把通知方法转换成 Advisor。

### `@Aspect` 是怎么变成 Advisor 的

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

Spring 会把这个切面解析成类似下面的模型：

```text
Advisor
  -> Pointcut: execution(* com.example..*Service.*(..))
  -> Advice: before()
```

也就是说，运行时真正参与匹配的不是 `@Before` 注解本身，而是解析后得到的 Advisor。

### Pointcut 如何判断当前 Bean 能不能代理

候选 Advisor 找到后，需要判断它能不能应用到当前 Bean。

核心判断包括：

- 类是否可能匹配。
- 方法是否可能匹配。
- 当前 Advisor 是否适用于当前 Bean。

可以简化理解为：

```text
Pointcut 匹配类
  -> Pointcut 匹配方法
  -> 匹配成功则 Advisor 可应用
```

如果一个 Bean 没有任何可应用 Advisor，就不会创建代理。

### 多个 Advisor 谁先执行

当一个 Bean 同时匹配多个 Advisor 时，Spring 会排序。

常见控制方式：

```java
@Order(1)
@Aspect
@Component
public class FirstAspect {
}

@Order(2)
@Aspect
@Component
public class SecondAspect {
}
```

执行顺序可以理解为：

```text
优先级高的环绕在外层
  -> 调用下一个拦截器
      -> 调用目标方法
  -> 返回后再执行外层后置逻辑
```

所以 `@Around` 场景下，顺序表现类似洋葱模型。

---

## 六、代理对象如何创建并执行增强

当 `wrapIfNecessary()` 判断当前 Bean 需要增强后，会调用代理创建逻辑。

简化流程：

```text
createProxy()
  -> 创建 ProxyFactory
  -> 设置 targetSource
  -> 添加 Advisor
  -> 决定 JDK / CGLIB
  -> getProxy()
```

这部分和上一篇衔接：

```text
AutoProxyCreator 判断要不要代理
ProxyFactory 决定怎么创建代理
JDK/CGLIB 负责生成代理对象
MethodInterceptor 链负责运行时增强
```

### Advisor 如何变成 MethodInterceptor

创建代理时保存的是 Advisor，但方法调用时真正执行的是 `MethodInterceptor`。

运行期会把 Advisor 转换成拦截器链：

```text
Advisor
  -> Advice
      -> MethodInterceptor
```

例如：

| 注解 | 运行时适配 |
|------|------------|
| `@Before` | 前置拦截器 |
| `@AfterReturning` | 返回后拦截器 |
| `@AfterThrowing` | 异常拦截器 |
| `@Around` | 环绕拦截器 |

方法调用时大致是：

```text
proxy.method()
  -> 获取当前方法的 MethodInterceptor 链
  -> ReflectiveMethodInvocation.proceed()
  -> 按顺序执行拦截器
  -> 调用 target.method()
```

### 和事务 AOP 的关系

声明式事务本质上也依赖 Spring AOP 代理。

例如：

```java
@Transactional
public void createOrder() {
}
```

事务会被封装成 Advisor：

```text
BeanFactoryTransactionAttributeSourceAdvisor
  -> TransactionAttributeSourcePointcut
  -> TransactionInterceptor
```

所以事务和普通切面的底层主线是一致的：

```text
Advisor 匹配 Bean
  -> 创建代理
  -> 方法调用进入 TransactionInterceptor
  -> 开启事务 / 提交事务 / 回滚事务
```

---

## 七、循环依赖、提前代理与缓存

自动代理创建器最容易绕晕人的地方，是它和循环依赖、三级缓存结合在一起。

普通场景：

```text
Bean 初始化完成后
  -> postProcessAfterInitialization()
  -> 创建代理
```

循环依赖场景：

```text
Bean 尚未初始化完成
  -> getEarlyBeanReference()
  -> 可能提前创建代理
  -> 其他 Bean 注入的是提前代理引用
```

### 为什么需要提前代理

假设 A 和 B 循环依赖，A 最终又需要 AOP 代理。

如果 B 注入的是 A 的原始对象，而容器最终暴露的是 A 的代理对象，就会出现引用不一致。

因此 Spring 需要在某些循环依赖场景下提前暴露代理对象。

### 几个重要缓存

`AbstractAutoProxyCreator` 中常见缓存：

| 缓存 | 作用 |
|------|------|
| `advisedBeans` | 记录某个 Bean 是否需要代理 |
| `earlyProxyReferences` | 记录已经提前暴露过代理的 Bean，避免重复代理 |
| `proxyTypes` | 缓存代理类型 |
| `targetSourcedBeans` | 记录自定义 TargetSource 的 Bean |

其中最关键的是：

```text
earlyProxyReferences
```

它用于避免这种情况：

```text
getEarlyBeanReference() 已经创建过代理
postProcessAfterInitialization() 又创建一次代理
```

---

## 八、调试、问题排查与面试总结

自动代理创建器的问题排查，建议按链路倒推：

```text
是否注册 AutoProxyCreator
  -> 是否找到 Advisor
  -> Pointcut 是否匹配 Bean
  -> 是否创建代理
  -> 调用是否经过代理
```

### 常见不生效问题定位

| 问题 | 排查方向 |
|------|----------|
| 没有注册自动代理创建器 | 检查是否启用 AOP，是否引入 `spring-boot-starter-aop` |
| 切面类没有被扫描到 | 检查 `@Aspect` 类是否也是 Spring Bean |
| Pointcut 没匹配上 | 检查包路径、方法签名、表达式范围 |
| Bean 被跳过代理 | 检查是否是基础设施 Bean、切面自身或已提前处理 |
| 调用绕过代理 | 检查是否自调用、构造器调用、直接 new 对象 |

### 调试断点建议

建议按顺序看：

```text
AopConfigUtils#registerAspectJAnnotationAutoProxyCreatorIfNecessary()
AnnotationAwareAspectJAutoProxyCreator#findCandidateAdvisors()
AbstractAutoProxyCreator#postProcessAfterInitialization()
AbstractAutoProxyCreator#wrapIfNecessary()
AbstractAutoProxyCreator#createProxy()
JdkDynamicAopProxy#invoke()
CglibAopProxy.DynamicAdvisedInterceptor#intercept()
```

观察重点：

- 自动代理创建器是否注册成功。
- 当前 Bean 是否进入 `wrapIfNecessary()`。
- 找到的 Advisor 列表是否为空。
- 最终创建的是 JDK 动态代理还是 CGLIB 代理。
- 方法调用是否进入拦截器链。

### 一个最小源码观察实验

切面：

```java
@Aspect
@Component
public class LogAspect {

    @Before("execution(* com.example..UserService.*(..))")
    public void before() {
        System.out.println("before");
    }
}
```

目标 Bean：

```java
@Service
public class UserService {

    public void createUser() {
        System.out.println("create user");
    }
}
```

启动后观察：

```java
@Component
public class DemoRunner implements CommandLineRunner {

    private final UserService userService;

    public DemoRunner(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(String... args) {
        System.out.println(userService.getClass());
        userService.createUser();
    }
}
```

重点看三件事：

1. `UserService` 初始化后是否进入 `wrapIfNecessary()`。
2. 是否找到匹配 `UserService` 的 Advisor。
3. `userService.getClass()` 是否已经是代理类型。

### 源码主线图

完整主线如下：

```text
@EnableAspectJAutoProxy / Spring Boot AOP 自动配置
  -> 注册 AnnotationAwareAspectJAutoProxyCreator
  -> 作为 BeanPostProcessor 参与 Bean 生命周期
  -> postProcessAfterInitialization()
  -> wrapIfNecessary()
  -> findEligibleAdvisors()
  -> findCandidateAdvisors()
  -> findAdvisorsThatCanApply()
  -> createProxy()
  -> ProxyFactory.getProxy()
  -> JDK / CGLIB 代理对象
  -> 方法调用进入 MethodInterceptor 链
```

和前两篇的关系：

```text
02-01-Spring-AOP.md
  -> 建立 AOP 基础概念

02-02-Spring-AOP-Proxy.md
  -> 讲代理对象如何工作

02-03-Spring-AOP-AutoProxyCreator.md
  -> 讲代理对象如何被 Spring 自动创建
```

### 面试高频快答

**`AnnotationAwareAspectJAutoProxyCreator` 是什么？**

> 它是支持 `@Aspect` 注解的自动代理创建器，本质是一个 `BeanPostProcessor`，负责在 Bean 生命周期中判断 Bean 是否需要创建 AOP 代理。

**Spring AOP 代理对象什么时候创建？**

> 通常在 Bean 初始化完成后的 `postProcessAfterInitialization()` 阶段创建，由 `wrapIfNecessary()` 判断是否需要代理。

**`@Aspect` 是怎么生效的？**

> Spring 会扫描容器中的 `@Aspect` Bean，把通知方法解析成 Advisor，再由自动代理创建器匹配目标 Bean 并创建代理。

**一个 Bean 是否需要代理由什么决定？**

> 主要看是否存在能应用到该 Bean 的 Advisor，也就是切点是否匹配当前 Bean 的类或方法。

**为什么 Spring 不给所有 Bean 都创建代理？**

> 代理有成本，而且只有命中 Advisor 的 Bean 才需要增强；基础设施 Bean、切面自身、未匹配切点的 Bean 都会被跳过。

**AOP 和事务是什么关系？**

> 声明式事务通过事务 Advisor 和 `TransactionInterceptor` 接入 Spring AOP 自动代理流程，本质也是代理加拦截器链。

**循环依赖时 AOP 代理怎么处理？**

> Spring 可能通过 `getEarlyBeanReference()` 提前暴露代理对象，并用 `earlyProxyReferences` 避免初始化后重复代理。

### 本篇总结

本文重点不是记住所有类名，而是建立一条清晰主线：

```text
自动代理创建器注册进容器
  -> 参与 Bean 生命周期
  -> 找到候选 Advisor
  -> 判断当前 Bean 是否匹配
  -> 匹配则创建代理
  -> 容器暴露代理对象
  -> 方法调用进入拦截器链
```

必须掌握的核心点：

- `AutoProxyCreator` 的本质是 BeanPostProcessor。
- `wrapIfNecessary()` 是判断是否需要代理的核心方法。
- `Advisor` 是 AOP 匹配和增强的统一模型。
- `@Aspect` 最终也会被解析成 Advisor。
- 事务 AOP 和普通切面共用自动代理创建流程。
- 循环依赖场景可能通过提前代理保证引用一致。
