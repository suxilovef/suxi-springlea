---
title: 02-02-Spring-AOP-Proxy
date: 2026-07-12 00:00:00
description: Spring AOP Proxy
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-aop-proxy.svg
rank: 78
top_img: false
---
# Spring AOP 代理机制

> 本文承接 `02-01-Spring-AOP.md`，只聚焦一个问题：Spring AOP 的代理对象到底如何工作。  
> 主线是：为什么需要代理 -> 代理对象是什么 -> JDK/CGLIB 如何选择 -> 方法调用如何进入增强链 -> 哪些场景会绕过代理。

---

## 一、先建立主线：Spring AOP 为什么依赖代理

Spring AOP 的核心不是“修改业务类”，而是在运行期把目标 Bean 包装成代理 Bean。

业务代码看起来调用的是 Service：

```java
userService.createUser(request);
```

但如果这个 Bean 命中了切面、事务、缓存等增强，容器中暴露给调用方的通常不是原始对象，而是代理对象：

```text
调用方
  -> 代理对象 Proxy
      -> 日志 / 权限 / 事务 / 缓存等增强逻辑
      -> 目标对象 Target
```

所以 Spring AOP 最重要的一句话是：

> 只有调用经过代理对象，Spring AOP 才有机会在目标方法前后织入增强逻辑。

这句话可以解释后面大部分问题：

- 为什么自调用会失效。
- 为什么 `private` 方法无法被增强。
- 为什么构造器里调用增强方法不生效。
- 为什么 JDK 动态代理按实现类注入可能失败。
- 为什么 `final` 类和 `final` 方法会影响 CGLIB 代理。

Spring AOP 选择代理模式，主要是为了和 IOC 容器天然结合：Bean 创建完成后，容器可以统一决定是否把原始 Bean 替换成代理 Bean。

---

## 二、代理对象、目标对象与调用入口

理解 Spring AOP 前，先区分两个对象：

| 名称 | 含义 |
|------|------|
| `Target` | 原始业务对象，也就是开发者写的类实例 |
| `Proxy` | Spring 根据目标对象和增强逻辑创建出来的代理对象 |

例如：

```java
@Service
public class UserService {

    public void createUser() {
        System.out.println("create user");
    }
}
```

`UserService` 的原始实例就是 `Target`。如果它命中了某个切面，Spring 最终注入给其他 Bean 的可能是 `Proxy`。

可以打印运行时类型验证：

```java
System.out.println(userService.getClass());
```

可能输出：

```text
class jdk.proxy2.$Proxy58
```

也可能输出：

```text
class com.example.UserService$$SpringCGLIB$$0
```

前者是 JDK 动态代理，后者是 CGLIB 代理。

### `this` 与 `target` 的区别

AOP 表达式里经常看到：

```java
@Pointcut("this(com.example.UserService)")
@Pointcut("target(com.example.UserService)")
```

两者关注点不同：

| 表达式 | 判断对象 |
|--------|----------|
| `this` | 当前代理对象的类型 |
| `target` | 被代理的目标对象类型 |

在 CGLIB 代理中，代理对象是目标类子类，所以 `this` 和 `target` 往往都容易匹配。

在 JDK 动态代理中，代理对象只实现接口，不继承目标实现类，所以 `this(实现类)` 可能匹配不上。

---

## 三、JDK 动态代理与 CGLIB 代理

Spring AOP 常用两种代理技术：

- JDK 动态代理：基于接口生成代理对象。
- CGLIB 代理：基于目标类生成子类代理对象。

### JDK 动态代理

JDK 动态代理要求目标类至少实现一个接口。

示例：

```java
public interface UserService {
    void createUser();
}

@Service
public class UserServiceImpl implements UserService {
    @Override
    public void createUser() {
        System.out.println("create user");
    }
}
```

生成的代理对象大致是：

```text
class jdk.proxy2.$Proxy58 implements UserService
```

它的特点是：

- 代理对象实现接口。
- 代理对象不继承目标实现类。
- 调用入口是 `InvocationHandler#invoke()`。

因此下面这种注入方式更稳妥：

```java
private final UserService userService;
```

而不是：

```java
private final UserServiceImpl userService;
```

如果 Spring 使用的是 JDK 动态代理，按实现类注入可能失败，因为代理对象不是实现类的子类。

### CGLIB 代理

CGLIB 代理不要求目标类实现接口，它通过生成目标类子类来完成代理。

例如：

```text
UserService$$SpringCGLIB$$0 extends UserService
```

它的特点是：

- 代理对象继承目标类。
- 可以代理没有接口的类。
- 调用入口是 CGLIB 的 `MethodInterceptor#intercept()`。
- 无法代理 `final` 类和 `final` 方法。

### 两种代理方式对比

| 维度 | JDK 动态代理 | CGLIB 代理 |
|------|--------------|------------|
| 生成方式 | 基于接口生成代理类 | 基于目标类生成子类 |
| 是否需要接口 | 需要 | 不需要 |
| 代理对象类型 | 接口实现类 | 目标类子类 |
| 方法入口 | `InvocationHandler#invoke()` | `MethodInterceptor#intercept()` |
| 典型限制 | 不能按实现类注入 | 不能代理 `final` 类 / 方法 |

### Spring 如何选择代理方式

核心判断逻辑可以简化为：

```text
如果配置了 proxyTargetClass=true
  -> 使用 CGLIB
否则如果目标类实现了接口
  -> 优先使用 JDK 动态代理
否则
  -> 使用 CGLIB
```

常见强制 CGLIB 的方式：

```java
@EnableAspectJAutoProxy(proxyTargetClass = true)
```

Spring Boot 中也可以通过配置控制：

```yaml
spring:
  aop:
    proxy-target-class: true
```

### Spring Boot 的默认行为

Spring Boot 2.x 起 `spring.aop.proxy-target-class` 默认值为 `true`，即 **Spring Boot 应用默认强制使用 CGLIB 类代理**，会覆盖上述"有接口时优先 JDK"的默认逻辑。

因此 Boot 项目中常见的是 `$$SpringCGLIB$$` 代理类名，且有两个连带影响：

- 按实现类注入通常没问题（CGLIB 代理是目标类的子类）。
- 目标类写成 `final` 会直接导致代理创建失败（`Cannot subclass final class`）。

---

## 四、代理方法调用链是怎么执行的

代理对象创建完成后，真正发生在运行期的是“方法调用被代理拦截”。

两种代理入口不同，但最终都会进入 Spring AOP 的拦截器链。

### JDK 动态代理调用链

JDK 动态代理的入口是：

```java
JdkDynamicAopProxy#invoke(Object proxy, Method method, Object[] args)
```

简化调用链如下：

```text
调用接口方法
  -> jdk.proxy2.$ProxyXX
  -> JdkDynamicAopProxy.invoke()
  -> 获取当前方法匹配的拦截器链
  -> ReflectiveMethodInvocation.proceed()
  -> 依次执行 MethodInterceptor
  -> 调用目标方法
```

关键点是：代理对象不会直接调用目标方法，而是先拿到当前方法对应的拦截器链。

### CGLIB 代理调用链

CGLIB 代理入口是：

```java
DynamicAdvisedInterceptor#intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy)
```

简化调用链如下：

```text
调用代理子类方法
  -> UserService$$SpringCGLIB$$0
  -> DynamicAdvisedInterceptor.intercept()
  -> 获取当前方法匹配的拦截器链
  -> CglibMethodInvocation.proceed()
  -> 依次执行 MethodInterceptor
  -> 调用目标方法
```

因此，不管是 JDK 动态代理还是 CGLIB，最终运行模型都是：

```text
代理入口
  -> Advisor 匹配出来的 MethodInterceptor 链
  -> 目标方法
```

---

## 五、代理对象什么时候创建

代理对象通常不是在方法第一次调用时才创建的，而是在 Bean 生命周期中提前创建。

典型时机是 Bean 初始化之后：

```text
Bean 实例化
  -> 属性填充
  -> 初始化回调
  -> BeanPostProcessor.postProcessAfterInitialization()
  -> 判断是否需要 AOP 代理
  -> 返回原始对象或代理对象
```

源码入口在下一篇重点展开：

```text
AbstractAutoProxyCreator#postProcessAfterInitialization()
  -> wrapIfNecessary()
  -> createProxy()
```

所以本文只需要记住：

> `02-02` 讲代理对象如何工作，`02-03-Spring-AOP-AutoProxyCreator.md` 讲代理对象如何被 Spring 自动创建。

---

## 六、代理失效的典型场景

代理失效不要死记场景，统一按一个判断标准看：

> 这次调用有没有经过 Spring 暴露出来的代理对象？

如果没有经过代理对象，AOP 就没有机会拦截。

### 自调用为什么会失效

示例：

```java
@Service
public class UserService {

    public void outer() {
        this.inner();
    }

    @Transactional
    public void inner() {
        System.out.println("inner");
    }
}
```

外部调用 `outer()` 时，调用链是：

```text
调用方
  -> 代理对象
      -> outer()
          -> this.inner()
```

`this.inner()` 的 `this` 是目标对象自身，不是代理对象，所以不会再次进入代理逻辑。

这就是自调用失效的根本原因。

### 如何解决自调用失效

本文只解释"为什么失效"，三个解决方式（拆分到另一个 Bean / 注入自身代理 / `AopContext.currentProxy()`）的代码与对比见 `02-07-Spring-AOP-Limitations.md` 的自调用解决方案总表。

### `final`、`private` 与构造器调用限制

CGLIB 代理通过子类重写方法实现拦截，因此：

- `final` 类无法被继承，不能生成 CGLIB 子类代理。
- `final` 方法无法被子类重写，不能被 CGLIB 拦截。
- `private` 方法不能被子类重写，也不是外部代理调用入口。
- 构造器执行时代理对象还没创建完成，构造器中调用增强方法不会触发 AOP。

示例：

```java
@Service
public class UserService {

    public UserService() {
        init();
    }

    @Transactional
    public void init() {
        System.out.println("init");
    }
}
```

`init()` 在构造器里被调用时，代理对象尚未完成创建，因此不会触发事务增强。

### 代理与类型注入问题

如果目标类实现了接口，Spring 默认可能使用 JDK 动态代理：

```text
UserServiceImpl implements UserService
Proxy implements UserService
```

此时代理对象不是 `UserServiceImpl` 类型。

所以推荐面向接口注入：

```java
private final UserService userService;
```

避免按实现类注入：

```java
private final UserServiceImpl userService;
```

如果确实需要按类代理，可以考虑强制 CGLIB，但更推荐从设计上面向接口依赖。

---

## 七、调试、Demo 与面试总结

### 调试代理对象的几个方法

打印代理类名：

```java
System.out.println(bean.getClass());
```

判断是否是 AOP 代理：

```java
AopUtils.isAopProxy(bean);
AopUtils.isJdkDynamicProxy(bean);
AopUtils.isCglibProxy(bean);
```

获取目标类型：

```java
AopUtils.getTargetClass(bean);
```

建议断点（本文只关注"代理入口"这一段；链路推进 `ReflectiveMethodInvocation#proceed()` 见 02-05，全量断点汇总见 02-07 §十）：

```text
JdkDynamicAopProxy#invoke()
CglibAopProxy.DynamicAdvisedInterceptor#intercept()
```

### 一个最小可验证 Demo

Service：

```java
@Service
public class UserService {

    public void outer() {
        System.out.println("outer");
        inner();
    }

    public void inner() {
        System.out.println("inner");
    }
}
```

Aspect：

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

Runner：

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
        userService.outer();
    }
}
```

观察重点：

- 外部调用 `outer()` 会触发 AOP。
- `outer()` 内部直接调用 `inner()` 不会再次触发 AOP。
- 打印出来的 `userService.getClass()` 不是普通业务类。

### 面试高频快答

**Spring AOP 为什么使用代理？**

> Spring AOP 通过运行期代理对象在目标方法前后插入增强逻辑，不直接修改业务类字节码。

**JDK 动态代理和 CGLIB 的区别？**

> JDK 动态代理基于接口生成代理对象，CGLIB 基于目标类生成子类代理；JDK 代理不能按实现类注入，CGLIB 不能代理 `final` 类和 `final` 方法。

**Spring 如何选择代理方式？**

> 如果强制 `proxyTargetClass=true` 则使用 CGLIB；否则有接口时默认优先 JDK 动态代理，没有接口时使用 CGLIB。

**为什么自调用会导致 AOP 失效？**

> 因为自调用是目标对象内部通过 `this` 调用，没有经过 Spring 暴露的代理对象。

**为什么构造器里调用 AOP 方法不生效？**

> 构造器执行时 Bean 还没有完成生命周期处理，代理对象尚未创建或尚未暴露，调用不会经过代理。

### 源码阅读路线

建议按下面顺序阅读：

```text
AopProxy
  -> JdkDynamicAopProxy
  -> CglibAopProxy
  -> ProxyFactory
  -> AdvisedSupport
  -> ReflectiveMethodInvocation
```

读源码时抓住两个问题：

1. 代理对象是 JDK 还是 CGLIB 创建的。
2. 方法调用如何进入 `MethodInterceptor` 链。

### 本篇总结

本文只讲代理机制本身，可以总结成一条链路：

```text
目标对象 Target
  -> Spring 判断代理方式
  -> 创建 JDK/CGLIB 代理对象 Proxy
  -> 调用经过 Proxy
  -> 进入 MethodInterceptor 链
  -> 调用目标方法
```

必须掌握的核心点：

- Spring AOP 生效的前提是调用经过代理对象。
- JDK 动态代理基于接口，CGLIB 基于目标类子类。
- 自调用、`private`、`final`、构造器调用都可能绕过或限制代理。
- 代理对象创建时机属于自动代理创建器的职责，下一篇继续展开。
