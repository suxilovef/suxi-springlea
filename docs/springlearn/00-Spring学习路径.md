---
title: Spring九阶段学习路线
date: 2026-07-09 20:30:00
description: Spring深入学习路线
tags:
  - Spring
categories:
  - Spring
sticky: 100
realm: wujing
cover: /image/post_cover/wujing-spring-roadmap.svg
rank: 90
top_img: false
---

# Spring九阶段学习路线

## 1. 路线定位

这份文档用于指导 Spring 体系从基础使用、核心源码、Spring Boot 自动配置，到 Spring Cloud 微服务治理的完整学习路线。

学习目标不是“会用几个注解”，而是达到下面几个能力：

- 能讲清楚 Spring IOC、AOP、事务、MVC 的核心机制。
- 能从源码主线解释 Bean 如何被发现、创建、注入、初始化和代理。
- 能理解 Spring Boot 自动配置、Starter、配置绑定和生产治理能力。
- 能独立排查 Bean 找不到、循环依赖、事务失效、AOP 失效、自动配置不生效等问题。
- 能搭建 Spring Cloud 微服务基础架构，包括注册发现、配置中心、网关、Feign 调用、负载均衡。
- 能理解熔断限流、链路追踪、灰度发布、配置刷新、服务治理等生产问题。
- 能结合真实 Java 项目做架构分析、问题定位和面试表达。

这条路线分成三层：

```text
Spring Framework
  -> 解决单体应用内部对象管理、Web、事务、AOP、数据访问

Spring Boot
  -> 解决工程化、自动配置、快速启动、外部化配置、监控运维

Spring Cloud
  -> 解决微服务之间的注册发现、配置、调用、网关、治理和观测
```

## 2. 总体阶段

| 阶段 | 名称 | 核心目标 | 完成后的水平 |
| --- | --- | --- | --- |
| 第 1 阶段 | Spring 基础与容器认知 | 理解 IOC、DI、BeanFactory、ApplicationContext、BeanDefinition | 能讲清 Spring 管理对象的基本原理 |
| 第 2 阶段 | IOC 启动与 Bean 生命周期 | 掌握 `refresh()`、配置解析、Bean 创建、依赖注入、循环依赖 | 能读懂 Spring IOC 主流程 |
| 第 3 阶段 | AOP、事务与扩展点 | 掌握动态代理、BeanPostProcessor、事务拦截、事件机制 | 能解释事务和 AOP 失效问题 |
| 第 4 阶段 | Spring Web MVC 与数据访问 | 掌握 DispatcherServlet、参数解析、异常处理、MyBatis/JPA、声明式事务 | 能独立分析 Web 请求和数据库事务链路 |
| 第 5 阶段 | Spring Boot 核心机制 | 掌握启动流程、自动配置、Starter、配置绑定、条件装配 | 能解释 Boot 为什么能开箱即用 |
| 第 6 阶段 | Spring Boot 工程化与生产治理 | 掌握 Actuator、日志、监控、测试、打包、优雅停机、性能优化 | 能把 Boot 应用做到可运行、可观测、可维护 |
| 第 7 阶段 | Spring Cloud 微服务基础 | 掌握注册中心、配置中心、OpenFeign、LoadBalancer、Gateway | 能搭建基础微服务架构 |
| 第 8 阶段 | Spring Cloud 服务治理 | 掌握熔断限流、链路追踪、安全、灰度、分布式事务、消息集成 | 能处理微服务生产治理问题 |
| 第 9 阶段 | 项目实战、源码回看与面试体系 | 结合 SiniCube 和真实项目建立完整排查、设计、表达能力 | 接近高级 Java 后端 Spring 体系能力 |

完成前 3 个阶段，可以认为具备 Spring Framework 核心源码基础。

完成前 6 个阶段，可以认为具备 Spring Boot 高级应用和工程化能力。

完成全部 9 个阶段，才算完整覆盖 Spring、Spring Boot、Spring Cloud 的深入学习。

## 3. 总学习顺序

建议严格按下面顺序推进：

```text
01 Spring 基础与容器认知
  -> 02 IOC 启动与 Bean 生命周期
  -> 03 AOP、事务与扩展点
  -> 04 Spring Web MVC 与数据访问
  -> 05 Spring Boot 核心机制
  -> 06 Spring Boot 工程化与生产治理
  -> 07 Spring Cloud 微服务基础
  -> 08 Spring Cloud 服务治理
  -> 09 项目实战、源码回看与面试体系
```

不要过早进入 Spring Cloud。Spring Cloud 的很多问题，本质上仍然依赖 Spring IOC、AOP、Boot 自动配置、配置环境和 Bean 条件装配。

## 4. 第 1 阶段：Spring 基础与容器认知

对应当前文档：

```text
01-01-Spring-IOC.md
```

核心目标：

- 理解 Spring 解决的核心问题是对象创建、依赖装配和生命周期管理。
- 理解 IOC 和 DI 的关系。
- 理解 BeanFactory 和 ApplicationContext 的区别。
- 理解 BeanDefinition 是 Spring 管理 Bean 的元数据。
- 理解 singleton、prototype、lazy-init、primary、qualifier 等基础概念。

必须掌握：

- IOC 不是技术细节，而是一种控制权反转思想。
- DI 是 Spring 落地 IOC 的主要方式。
- ApplicationContext 是 BeanFactory 的高级扩展。
- BeanDefinition 描述 Bean 的 class、scope、lazy、factoryMethod、依赖关系等信息。
- Spring 创建 Bean 之前，必须先有 BeanDefinition。

必做实验：

- 写一个最小 Spring Boot Demo。
- 定义 `@Service`、`@Repository`、`@Configuration`、`@Bean`。
- 在启动后打印所有 BeanDefinition 名称。
- 分别使用构造器注入、字段注入、Setter 注入。
- 制造一个 `NoSuchBeanDefinitionException`。
- 制造一个 `NoUniqueBeanDefinitionException`，再用 `@Primary` 和 `@Qualifier` 解决。

过关标准：

- 能解释 IOC 和 DI 的区别。
- 能解释 BeanFactory 和 ApplicationContext 的区别。
- 能说明 BeanDefinition 和 Bean 实例不是一回事。
- 能说清楚为什么推荐构造器注入。

## 5. 第 2 阶段：IOC 启动与 Bean 生命周期

对应当前文档：

```text
01-01-Spring-IOC.md
01-02-Spring-IOC-ConfigurationClassPostProcessor.md
01-03-Spring-IOC-Bean-Creation.md
```

核心目标：

- 掌握 `AbstractApplicationContext.refresh()` 主流程。
- 掌握注解配置如何变成 BeanDefinition。
- 掌握 Bean 从 `getBean()` 到可用对象的完整生命周期。
- 掌握依赖注入和循环依赖的核心机制。

必须掌握：

- `refresh()` 是 Spring 容器启动主线。
- `invokeBeanFactoryPostProcessors()` 负责执行 BeanFactory 后置处理器。
- `ConfigurationClassPostProcessor` 负责解析 `@Configuration`、`@ComponentScan`、`@Bean`、`@Import`。
- `finishBeanFactoryInitialization()` 负责创建非懒加载单例 Bean。
- Bean 生命周期包括实例化、属性注入、Aware、初始化、后置处理、销毁。
- 三级缓存解决的是部分单例 Bean 的循环依赖，不解决构造器循环依赖。

必做实验：

- 在 `AbstractApplicationContext.refresh()` 打断点。
- 在 `ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry()` 打断点。
- 观察 `beanDefinitionMap` 什么时候增加业务 Bean。
- 跟踪一个 `@Bean` 方法什么时候被解析、什么时候被真正调用。
- 跟踪一个 `@Autowired` 字段在哪里被注入。
- 制造 setter 循环依赖，观察三级缓存。
- 制造构造器循环依赖，观察启动失败。

过关标准：

- 能画出 `refresh()` 主流程。
- 能解释 `@ComponentScan` 和 `@Bean` 的执行时机。
- 能说明 Bean 实例化、属性注入、初始化的区别。
- 能完整讲出 Spring 三级缓存解决循环依赖的过程。

## 6. 第 3 阶段：AOP、事务与扩展点

对应当前文档：

```text
02-01-Spring-AOP.md
02-02-Spring-AOP-Proxy.md
02-03-Spring-AOP-AutoProxyCreator.md
02-04-Spring-AOP-Advisor-Advice-Pointcut.md
02-05-Spring-AOP-Invocation-Chain.md
02-06-Spring-AOP-And-Transaction.md
02-07-Spring-AOP-Limitations.md
```

核心目标：

- 理解 Spring AOP 基于运行时动态代理。
- 理解 JDK 动态代理和 CGLIB 的区别。
- 理解 AOP 代理在 Bean 生命周期中的创建时机。
- 理解事务本质是 AOP 的典型应用。
- 理解 BeanPostProcessor、BeanFactoryPostProcessor 等扩展点。

必须掌握：

- `AnnotationAwareAspectJAutoProxyCreator` 是 AOP 自动代理创建器。
- AOP 代理通常在 Bean 初始化后的 `postProcessAfterInitialization()` 生成。
- 方法调用进入代理对象后，会进入 `MethodInterceptor` 链。
- `@Transactional` 依赖 `TransactionInterceptor`。
- 自调用、非 Spring Bean、private/final 方法、异常被吞都会导致 AOP 或事务失效。
- `BeanFactoryPostProcessor` 操作 BeanDefinition，`BeanPostProcessor` 操作 Bean 实例。

必做实验：

- 写一个自定义 `@Aspect`。
- 分别观察 JDK 代理和 CGLIB 代理类名称。
- 制造一次自调用导致切面失效。
- 制造一次 `@Transactional` 失效。
- 在 `AnnotationAwareAspectJAutoProxyCreator.wrapIfNecessary()` 打断点。
- 在 `ReflectiveMethodInvocation.proceed()` 打断点。
- 写一个简单的 `BeanPostProcessor`，观察 Bean 初始化前后回调。

过关标准：

- 能解释 Spring AOP 和 AspectJ 的区别。
- 能解释 AOP 代理什么时候创建。
- 能解释事务为什么会失效。
- 能说明 BeanFactoryPostProcessor 和 BeanPostProcessor 的区别。

## 7. 第 4 阶段：Spring Web MVC 与数据访问

对应当前文档：

```text
03-01-Spring-MVC.md
03-02-Spring-MVC-HandlerMapping-HandlerAdapter.md
03-03-Spring-MVC-ArgumentResolver-ReturnValueHandler.md
03-04-Spring-MVC-Exception-Interceptor-Validation.md
03-05-Spring-Transaction.md
03-06-Spring-Data-MyBatis-JPA.md
```

核心目标：

- 理解一个 HTTP 请求如何进入 Controller。
- 理解 Spring MVC 参数解析、返回值处理、异常处理。
- 理解声明式事务如何包裹数据库操作。
- 理解 MyBatis、JPA、JdbcTemplate 与 Spring 的集成方式。

必须掌握：

- `DispatcherServlet` 是 Spring MVC 请求入口。
- `HandlerMapping` 负责找处理器。
- `HandlerAdapter` 负责调用 Controller。
- `HandlerMethodArgumentResolver` 负责参数解析。
- `HttpMessageConverter` 负责 JSON 序列化和反序列化。
- `ControllerAdvice` 负责全局异常处理。
- `PlatformTransactionManager` 是事务管理抽象。
- MyBatis Mapper 通常通过扫描注册代理 Bean。

必做实验：

- 写一个 Controller，跟踪一次请求到方法调用。
- 自定义一个 `HandlerInterceptor`。
- 自定义一个全局异常处理器。
- 自定义一个请求参数校验失败场景。
- 跟踪 `@RequestBody` 如何反序列化。
- 写一个事务方法，观察事务开启、提交、回滚。
- 制造运行时异常和受检异常，观察事务回滚差异。
- 跟踪一个 MyBatis Mapper 代理对象是如何注入到 Service 的。

过关标准：

- 能画出 Spring MVC 请求处理流程。
- 能解释参数解析和返回值处理机制。
- 能解释声明式事务如何生效。
- 能排查 Controller 不生效、参数绑定失败、事务不回滚等问题。

## 8. 第 5 阶段：Spring Boot 核心机制

建议后续新增文档：

```text
04-01-SpringBoot-启动流程.md
04-02-SpringBoot-自动配置.md
04-03-SpringBoot-Starter.md
04-04-SpringBoot-配置绑定.md
```

核心目标：

- 理解 Spring Boot 如何基于 Spring Framework 封装工程化能力。
- 掌握 `SpringApplication.run()` 启动流程。
- 掌握自动配置、条件装配、Starter 原理。
- 掌握外部化配置、Profile、配置绑定。

必须掌握：

- `@SpringBootApplication` 包含 `@SpringBootConfiguration`、`@EnableAutoConfiguration`、`@ComponentScan`。
- `@EnableAutoConfiguration` 通过 `@Import(AutoConfigurationImportSelector.class)` 导入自动配置。
- Spring Boot 3 使用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 决定自动配置是否生效。
- Starter 的本质是依赖管理加自动配置。
- `Environment` 管理配置源，`Binder` 负责绑定配置属性。

必做实验：

- 跟踪 `SpringApplication.run()`。
- 打开 `debug=true` 查看自动配置报告。
- 写一个自定义 AutoConfiguration。
- 写一个自定义 Starter。
- 使用 `@ConditionalOnMissingBean` 验证用户 Bean 覆盖默认 Bean。
- 使用 `@ConfigurationProperties` 绑定配置。
- 分别用 `application.yml`、环境变量、启动参数覆盖配置。

过关标准：

- 能解释 Spring Boot 为什么能开箱即用。
- 能解释自动配置和普通配置类的关系。
- 能写出一个最小自定义 Starter。
- 能排查自动配置为什么生效或不生效。

## 9. 第 6 阶段：Spring Boot 工程化与生产治理

建议后续新增文档：

```text
05-01-SpringBoot-Actuator-监控.md
05-02-SpringBoot-日志与配置治理.md
05-03-SpringBoot-测试与部署.md
05-04-SpringBoot-性能优化.md
```

核心目标：

- 从“会写 Boot 应用”进入“能上线和治理 Boot 应用”。
- 掌握监控、日志、健康检查、优雅停机、测试、打包部署。
- 掌握启动性能和运行性能的常见优化方向。

必须掌握：

- Actuator 的 health、metrics、info、env、beans、conditions。
- Micrometer 指标体系。
- 日志级别、日志链路字段、日志脱敏。
- Profile 和多环境配置。
- 优雅停机和线程池关闭。
- `@SpringBootTest`、切片测试、MockMvc、Testcontainers。
- 可执行 jar、Docker 镜像、容器环境变量。
- 启动慢的常见原因：扫描范围大、自动配置多、Bean 初始化慢、外部连接慢。

必做实验：

- 开启 Actuator 并查看健康检查和指标。
- 接入 Prometheus 指标格式。
- 配置不同 Profile。
- 写 Controller、Service、Repository 的测试。
- 打包成 jar 并使用不同参数启动。
- 模拟优雅停机。
- 分析一次启动耗时。
- 使用 `/actuator/conditions` 排查自动配置。

过关标准：

- 能设计 Boot 应用基础监控项。
- 能排查启动慢和 Bean 初始化慢。
- 能写出可维护的测试结构。
- 能说明生产环境不应该暴露哪些 Actuator 端点。

## 10. 第 7 阶段：Spring Cloud 微服务基础

建议后续新增文档：

```text
06-01-SpringCloud-注册发现.md
06-02-SpringCloud-配置中心.md
06-03-SpringCloud-OpenFeign.md
06-04-SpringCloud-Gateway.md
```

核心目标：

- 从单体 Spring Boot 应用进入微服务体系。
- 掌握注册发现、配置中心、服务调用、负载均衡、API 网关。
- 理解 Spring Cloud 不替代 Spring Boot，而是建立在 Boot 之上。

必须掌握：

- 注册中心的核心职责是服务实例注册、发现和健康状态维护。
- 配置中心的核心职责是集中管理配置和动态刷新。
- OpenFeign 是声明式 HTTP 客户端。
- Spring Cloud LoadBalancer 负责客户端负载均衡。
- Gateway 负责统一入口、路由、断言、过滤器。
- Nacos、Eureka、Consul、Spring Cloud Config 的定位差异。
- 微服务调用链路中的超时、重试、负载均衡和异常传递。

必做实验：

- 搭建一个注册中心。
- 启动两个服务实例并完成服务发现。
- 使用 OpenFeign 调用远程服务。
- 配置客户端负载均衡。
- 搭建 Gateway 并配置路由。
- 写一个 Gateway 全局过滤器。
- 使用配置中心管理应用配置。
- 修改配置并验证刷新效果。

过关标准：

- 能搭建最小微服务系统。
- 能解释服务注册和服务发现的区别。
- 能解释 Feign、LoadBalancer、Gateway 的职责边界。
- 能排查服务无法注册、无法发现、远程调用失败、网关路由失败。

## 11. 第 8 阶段：Spring Cloud 服务治理

建议后续新增文档：

```text
07-01-SpringCloud-熔断限流.md
07-02-SpringCloud-链路追踪与观测.md
07-03-SpringCloud-安全与网关认证.md
07-04-SpringCloud-分布式事务与消息.md
07-05-SpringCloud-灰度发布与配置治理.md
```

核心目标：

- 从“微服务能跑”进入“微服务能治理”。
- 掌握故障隔离、熔断限流、链路追踪、认证鉴权、配置治理、灰度发布。
- 理解分布式系统中的一致性、可用性和复杂度取舍。

必须掌握：

- 熔断、限流、降级、隔离、重试的区别。
- Sentinel、Resilience4j 的定位。
- Micrometer、Tracing、OpenTelemetry、Zipkin、SkyWalking 的关系。
- Gateway 鉴权、Token 透传、用户上下文传递。
- 分布式事务常见方案：本地消息表、可靠消息、TCC、Saga、Seata。
- 消息驱动和异步解耦。
- 灰度发布、权重路由、版本路由。
- 配置刷新和配置回滚风险。

必做实验：

- 给接口增加限流规则。
- 制造下游超时，观察熔断和降级。
- 配置 Feign 超时和重试。
- 接入链路追踪，查看一次跨服务调用。
- 在 Gateway 做统一鉴权。
- 设计一个用户上下文透传方案。
- 实现一个消息最终一致性实验。
- 实现一个灰度路由实验。

过关标准：

- 能解释微服务雪崩如何发生。
- 能设计接口限流和降级策略。
- 能定位一次跨服务调用慢在哪里。
- 能说明分布式事务为什么复杂，以及不同方案适用边界。

## 12. 第 9 阶段：项目实战、源码回看与面试体系

对应当前项目：

```text
SiniCube
Spring-IOC-Sinicube.md
```

核心目标：

- 把 Spring、Spring Boot、Spring Cloud 的知识落回真实项目。
- 能从项目结构、启动类、模块依赖、配置、Bean、AOP、事务、外部组件接入角度分析系统。
- 建立面试表达和生产排查能力。

必须掌握：

- 启动类位置决定默认扫描范围。
- 多模块 Maven 依赖决定哪些类能被扫描和加载。
- 第三方 Starter 本质上通过自动配置提供 Bean。
- Controller、Service、Mapper、Aspect、Config 都有不同的注册链路。
- 事务、权限、日志、租户、审计通常依赖 AOP 或拦截器。
- Nacos、MinIO、Shiro、MyBatis Plus 等框架都通过 Spring 容器接入。

必做实验：

- 找到项目启动类并分析扫描范围。
- 找一个 Controller，说明它如何进入容器。
- 找一个 Service，说明依赖如何注入。
- 找一个 Mapper，说明代理如何注册。
- 找一个事务方法，判断是否会生成代理。
- 找一个配置类，判断是否来自自动配置。
- 制造一个 Bean 冲突并解决。
- 制造一个自动配置不生效问题并排查。
- 根据项目写一份 Spring 面试串讲稿。

过关标准：

- 能基于真实项目解释 Spring 启动链路。
- 能独立排查 Bean、AOP、事务、自动配置问题。
- 能把源码机制和项目实践对应起来。
- 能形成一套完整的 Spring 面试回答体系。

## 13. 最终能力定位

如果只完成前 3 阶段：

```text
Spring Framework 核心能力
能解释 IOC、AOP、事务、Bean 生命周期和常见失效问题
```

如果完成前 6 阶段：

```text
Spring Boot 高级工程能力
能理解自动配置、Starter、配置治理、监控、测试和生产部署
```

如果完成前 8 阶段：

```text
Spring Cloud 微服务架构能力
能搭建和治理注册发现、配置中心、服务调用、网关、熔断限流、链路追踪
```

如果完成全部 9 阶段：

```text
高级 Java 后端 Spring 体系能力
具备源码理解、工程落地、微服务治理、项目排查和面试表达的综合能力
```

真正达到架构师水平，还需要继续积累：

- 多个真实项目的演进经验。
- 线上故障处理经验。
- 性能压测和容量评估经验。
- 中间件组合治理经验。
- 从源码定位疑难问题的经验。
