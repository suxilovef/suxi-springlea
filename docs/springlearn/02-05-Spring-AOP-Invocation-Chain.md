---
title: 02-05-Spring-AOP-Invocation-Chain
date: 2026-07-12 18:00:00
description: Spring AOP Invocation Chain
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/spring-aop-min.svg
rank: 75
top_img: false
---
# Spring AOP 拦截器链执行过程

> 本文承接 `02-04-Spring-AOP-Advisor-Advice-Pointcut.md`，专门分析 Spring AOP 方法调用时，拦截器链是如何执行的。  
> 主线是：代理对象拦截方法调用 -> 获取 MethodInterceptor 链 -> 创建 MethodInvocation -> `ReflectiveMethodInvocation#proceed()` 递归推进。

---

## 一、先建立主线：AOP 方法调用到底执行了什么

前面已经讲过，Spring AOP 最终会把不同类型的 Advice 适配成 `MethodInterceptor`。

方法真正被调用时，执行链可以简化成：

```text
调用代理对象方法
  -> 进入 JDK / CGLIB 代理入口
  -> 获取当前方法匹配的 MethodInterceptor 链
  -> 创建 MethodInvocation
  -> 调用 proceed()
  -> 依次执行每个 MethodInterceptor
  -> 最后调用目标方法
```

这一篇重点看一个方法：

```text
ReflectiveMethodInvocation#proceed()
```

它是理解 Spring AOP 运行时链式调用的关键。

---

## 二、代理入口如何进入拦截器链

不管是 JDK 动态代理还是 CGLIB 代理，入口不同，但最终都会走向同一个模型。

### JDK 动态代理入口

JDK 动态代理入口是：

```text
JdkDynamicAopProxy#invoke()
```

简化流程：

```text
proxy.method()
  -> JdkDynamicAopProxy.invoke()
  -> 获取 interceptorsAndDynamicMethodMatchers
  -> 创建 ReflectiveMethodInvocation
  -> invocation.proceed()
```

### CGLIB 代理入口

CGLIB 代理入口是：

```text
CglibAopProxy.DynamicAdvisedInterceptor#intercept()
```

简化流程：

```text
proxy.method()
  -> DynamicAdvisedInterceptor.intercept()
  -> 获取 interceptorsAndDynamicMethodMatchers
  -> 创建 CglibMethodInvocation
  -> invocation.proceed()
```

可以看到，两者最终都会抽象成：

```text
MethodInvocation + MethodInterceptor 链
```

所以真正的重点不是代理入口，而是 `proceed()` 如何推进。

---

## 三、MethodInvocation 是什么

`MethodInvocation` 表示一次方法调用上下文。

它通常包含：

| 信息 | 含义 |
|------|------|
| `proxy` | 当前代理对象 |
| `target` | 目标对象 |
| `method` | 当前被调用的方法 |
| `arguments` | 方法参数 |
| `targetClass` | 目标类类型 |
| `interceptorsAndDynamicMethodMatchers` | 当前方法匹配到的拦截器链 |
| `currentInterceptorIndex` | 当前执行到第几个拦截器 |

可以把它理解成一次调用现场：

```text
MethodInvocation
  -> 谁调用
  -> 调哪个方法
  -> 参数是什么
  -> 有哪些拦截器
  -> 当前执行到哪里
```

### 为什么需要 MethodInvocation

因为 `@Around` 这类增强需要主动控制是否继续执行后续链路：

```java
@Around("execution(* com.example..*Service.*(..))")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    System.out.println("before");
    Object result = joinPoint.proceed();
    System.out.println("after");
    return result;
}
```

这里的 `joinPoint.proceed()`，底层本质就是继续推进当前方法调用链。

---

## 四、ReflectiveMethodInvocation#proceed() 核心逻辑

`ReflectiveMethodInvocation#proceed()` 可以简化成下面的伪代码：

```java
public Object proceed() throws Throwable {
    if (currentInterceptorIndex == interceptors.size() - 1) {
        return invokeJoinpoint();
    }

    Object interceptor = interceptors.get(++currentInterceptorIndex);

    if (interceptor is MethodInterceptor) {
        return interceptor.invoke(this);
    }

    return proceed();
}
```

核心点只有两个：

1. 每调用一次 `proceed()`，索引 `currentInterceptorIndex` 向后推进一位。
2. 所有拦截器执行完后，才真正调用目标方法。

可以用这条链路理解：

```text
proceed()
  -> interceptor 0.invoke(invocation)
      -> invocation.proceed()
          -> interceptor 1.invoke(invocation)
              -> invocation.proceed()
                  -> target.method()
```

这就是 Spring AOP 拦截器链的递归推进模型。

---

## 五、拦截器链为什么像洋葱模型

假设有两个 `@Around` 切面：

```java
@Around("execution(* com.example..*Service.*(..))")
public Object around1(ProceedingJoinPoint pjp) throws Throwable {
    System.out.println("around1 before");
    Object result = pjp.proceed();
    System.out.println("around1 after");
    return result;
}

@Around("execution(* com.example..*Service.*(..))")
public Object around2(ProceedingJoinPoint pjp) throws Throwable {
    System.out.println("around2 before");
    Object result = pjp.proceed();
    System.out.println("around2 after");
    return result;
}
```

如果执行顺序是 `around1 -> around2 -> target`，输出会类似：

```text
around1 before
around2 before
target method
around2 after
around1 after
```

原因是调用链展开后是：

```text
around1.invoke()
  -> proceed()
      -> around2.invoke()
          -> proceed()
              -> target.method()
          -> around2 after
  -> around1 after
```

所以越靠前的拦截器，越在外层。

---

## 六、不同 Advice 在链中的执行位置

不同 Advice 最终都会适配成 `MethodInterceptor`，但语义不同。

| Advice | 执行特点 |
|--------|----------|
| `@Before` | 在调用 `proceed()` 前执行逻辑，然后继续链路 |
| `@AfterReturning` | 先调用 `proceed()`，正常返回后执行逻辑 |
| `@AfterThrowing` | 先调用 `proceed()`，捕获异常后执行逻辑并继续抛出 |
| `@After` | 使用 `finally` 语义，方法结束一定执行 |
| `@Around` | 自己决定何时、是否调用 `proceed()` |

`@Before` 可以理解为：

```java
public Object invoke(MethodInvocation invocation) throws Throwable {
    before();
    return invocation.proceed();
}
```

`@AfterReturning` 可以理解为：

```java
public Object invoke(MethodInvocation invocation) throws Throwable {
    Object result = invocation.proceed();
    afterReturning(result);
    return result;
}
```

`@AfterThrowing` 可以理解为：

```java
public Object invoke(MethodInvocation invocation) throws Throwable {
    try {
        return invocation.proceed();
    } catch (Throwable ex) {
        afterThrowing(ex);
        throw ex;
    }
}
```

所以 `@Around` 能力最强，也最容易破坏调用语义。

---

## 七、proceed() 不调用会发生什么

`@Around` 中如果不调用 `proceed()`，目标方法就不会执行。

示例：

```java
@Around("execution(* com.example..*Service.*(..))")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    System.out.println("skip target");
    return null;
}
```

调用链会停在当前拦截器：

```text
proxy.method()
  -> around interceptor
      -> 不调用 proceed()
      -> target.method() 不执行
```

所以 `@Around` 中要特别注意：

- 是否调用了 `proceed()`。
- 是否正确返回 `proceed()` 的结果。
- 是否保持异常语义，不要随意吞异常。

---

## 八、异常如何在拦截器链中传播

拦截器链本质是嵌套调用，所以异常会从内层向外层抛出。

```text
around1.invoke()
  -> around2.invoke()
      -> target.method() throws exception
  -> around2 捕获或抛出
-> around1 捕获或抛出
```

如果没有拦截器吞掉异常，异常会一直抛回调用方。

这也是 `@AfterThrowing` 能工作的原因。

注意：

> 如果某个环绕通知吞掉异常，外层拦截器和调用方看到的语义都会被改变。

这对事务尤其重要，因为事务回滚通常依赖异常继续向外传播。

事务细节放到 `02-06-Spring-AOP-And-Transaction.md` 继续展开。

---

## 九、调试 ReflectiveMethodInvocation

建议断点：

```text
ReflectiveMethodInvocation#proceed()
JdkDynamicAopProxy#invoke()
CglibAopProxy.DynamicAdvisedInterceptor#intercept()
DefaultAdvisorChainFactory#getInterceptorsAndDynamicInterceptionAdvice()
```

重点观察：

| 变量 | 观察点 |
|------|--------|
| `method` | 当前调用的方法 |
| `targetClass` | 目标类 |
| `interceptorsAndDynamicMethodMatchers` | 当前方法匹配到的拦截器链 |
| `currentInterceptorIndex` | 当前执行到第几个拦截器 |
| `arguments` | 当前调用参数 |

调试时可以重点看 `currentInterceptorIndex` 的变化：

```text
-1 -> 0 -> 1 -> 2 -> ... -> target method
```

---

## 十、面试高频快答

**Spring AOP 拦截器链是怎么执行的？**

> 代理对象拦截方法调用后，会获取当前方法匹配的 MethodInterceptor 链，创建 MethodInvocation，并通过 `proceed()` 按索引递归推进拦截器，最后调用目标方法。

**`ReflectiveMethodInvocation#proceed()` 的核心逻辑是什么？**

> 每次调用 `proceed()` 都会把当前拦截器索引向后推进一位，执行下一个 MethodInterceptor；当拦截器全部执行完后，调用目标方法。

**为什么多个 `@Around` 像洋葱模型？**

> 因为外层拦截器先执行前置逻辑，再通过 `proceed()` 进入内层拦截器，目标方法返回后再逐层执行后置逻辑。

**`@Around` 不调用 `proceed()` 会怎样？**

> 后续拦截器和目标方法都不会执行，调用链会在当前环绕通知处终止。

**异常在 AOP 链中怎么传播？**

> 异常从目标方法或内层拦截器向外层逐层抛出，除非某个拦截器主动捕获并吞掉异常。

---

## 十一、本篇总结

本文的核心是理解 `proceed()` 的链式推进：

```text
proxy.method()
  -> interceptor0.invoke(invocation)
      -> invocation.proceed()
          -> interceptor1.invoke(invocation)
              -> invocation.proceed()
                  -> target.method()
```

必须掌握的点：

- Spring AOP 运行时执行的是 `MethodInterceptor` 链。
- `MethodInvocation` 保存一次方法调用的上下文。
- `ReflectiveMethodInvocation#proceed()` 通过索引推进拦截器链。
- `@Around` 是否调用 `proceed()` 决定后续链路是否继续。
- 异常会沿着拦截器嵌套结构从内向外传播。
