# Spring Boot 专题：SpringApplication 启动流程源码深度解析

> 本文是第 5 阶段 Spring Boot 核心机制的第一篇文档。前四个阶段已经讲清 Spring Framework 容器、AOP、MVC、事务和数据访问。
>
> 本文只关注 Spring Boot 如何在这些机制之上完成启动编排。`refresh()` 的 13 步细节不再重复，重点是 `SpringApplication.run()` 在 `refresh()` 前后做了什么。

---

## 一、先建立启动主线

### 1.1 Spring Boot 启动的本质

最常见的启动代码：

```java
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

表面上只有一行 `run()`，实际上它完成了一整套启动编排：

```text
创建 SpringApplication
  -> 判断应用类型
  -> 加载启动扩展点
  -> 准备 Environment
  -> 创建 ApplicationContext
  -> 加载主配置类
  -> 调用 refresh()
  -> 启动内嵌 Web 服务器
  -> 执行 Runner
  -> 发布启动完成事件
```

一句话概括：

```text
SpringApplication 是 Spring Boot 的启动编排器，ApplicationContext 仍然是最终的 Spring 容器
```

### 1.2 Boot 和 Spring Framework 的职责边界

| 层次 | 主要职责 |
|---|---|
| Spring Framework | BeanDefinition、Bean 创建、依赖注入、AOP、`refresh()` |
| Spring Boot | 环境准备、容器类型选择、自动配置导入、内嵌服务器、启动事件、Runner |

因此学习顺序应该是：

```text
先理解 Spring 容器如何启动
再理解 Spring Boot 如何准备并驱动这个容器
```

---

## 二、@SpringBootApplication 是什么

### 2.1 组合注解

`@SpringBootApplication` 主要组合了：

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
public @interface SpringBootApplication {
}
```

三者职责：

| 注解 | 职责 |
|---|---|
| `@SpringBootConfiguration` | 标记当前类是 Boot 主配置类，本质上是 `@Configuration` |
| `@EnableAutoConfiguration` | 导入 Spring Boot 自动配置候选类 |
| `@ComponentScan` | 从启动类所在包向下扫描组件 |

### 2.2 启动类位置为什么重要

默认扫描范围由启动类所在包决定。

推荐结构：

```text
com.example.app
  Application.java
  controller/
  service/
  repository/
  config/
```

如果启动类放在过深的包中，上层或同级其他包中的 Bean 可能无法被扫描。

---

## 三、SpringApplication 创建阶段

### 3.1 静态 run() 只是便捷入口

简化代码：

```java
public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
    return run(new Class<?>[] { primarySource }, args);
}

public static ConfigurableApplicationContext run(Class<?>[] primarySources, String[] args) {
    return new SpringApplication(primarySources).run(args);
}
```

真正的两个阶段是：

```text
new SpringApplication(primarySources)
SpringApplication#run(args)
```

### 3.2 构造阶段做了什么

`SpringApplication` 构造阶段主要完成：

```text
1. 保存 primarySources
2. 根据 classpath 推断 WebApplicationType
3. 加载 BootstrapRegistryInitializer
4. 加载 ApplicationContextInitializer
5. 加载 ApplicationListener
6. 推断 mainApplicationClass
```

这个阶段还没有创建 `ApplicationContext`，也还没有创建业务 Bean。

### 3.3 primarySources

`primarySources` 通常就是启动类。

后续 `prepareContext()` 会把它作为配置源加载进容器，最终变成 BeanDefinition。

---

## 四、WebApplicationType 如何推断

### 4.1 三种应用类型

| 类型 | 含义 | 常见容器 |
|---|---|---|
| `NONE` | 非 Web 应用 | `AnnotationConfigApplicationContext` |
| `SERVLET` | Servlet Web 应用 | `AnnotationConfigServletWebServerApplicationContext` |
| `REACTIVE` | 响应式 Web 应用 | `AnnotationConfigReactiveWebServerApplicationContext` |

### 4.2 推断依据

Spring Boot 会根据 classpath 中的关键类判断应用类型。

简化理解：

```text
有 WebFlux DispatcherHandler，且没有 Spring MVC DispatcherServlet 和 Jersey ServletContainer
  -> REACTIVE

有 jakarta.servlet.Servlet 和 ConfigurableWebApplicationContext
  -> SERVLET

都不满足
  -> NONE
```

注意：`SERVLET` 应用不要求必须使用 Spring MVC，例如 Jersey 应用也可能被推断为 Servlet Web 应用。

### 4.3 为什么要先判断类型

因为不同类型应用需要创建不同的 `ApplicationContext`，其启动服务器的方式也不同。

---

## 五、SpringApplication.run() 总流程

### 5.1 主流程伪代码

```java
public ConfigurableApplicationContext run(String... args) {
    BootstrapContext bootstrapContext = createBootstrapContext();
    configureHeadlessProperty();

    SpringApplicationRunListeners listeners = getRunListeners(args);
    listeners.starting(bootstrapContext, mainApplicationClass);

    ApplicationArguments applicationArguments = new DefaultApplicationArguments(args);
    ConfigurableEnvironment environment = prepareEnvironment(
            listeners, bootstrapContext, applicationArguments);

    Banner printedBanner = printBanner(environment);
    ConfigurableApplicationContext context = createApplicationContext();

    prepareContext(bootstrapContext, context, environment,
            listeners, applicationArguments, printedBanner);

    refreshContext(context);
    afterRefresh(context, applicationArguments);

    listeners.started(context, timeTakenToStartup);
    callRunners(context, applicationArguments);
    listeners.ready(context, timeTakenToReady);
    return context;
}
```

实际源码会随 Spring Boot 版本有少量参数和计时逻辑差异，但这条主线不变。

### 5.2 主流程分组

```text
启动扩展准备
  -> starting

环境准备
  -> ApplicationArguments
  -> Environment
  -> Banner

容器准备
  -> createApplicationContext
  -> prepareContext

Spring 容器启动
  -> refreshContext

Boot 启动收尾
  -> started
  -> callRunners
  -> ready
```

---

## 六、prepareEnvironment() 环境准备

### 6.1 ApplicationArguments

命令行参数会封装为 `ApplicationArguments`。

例如：

```text
java -jar app.jar --server.port=9090 --spring.profiles.active=prod input.txt
```

可区分为：

- Option arguments：`--server.port=9090`。
- Non-option arguments：`input.txt`。

### 6.2 ConfigurableEnvironment

`Environment` 是 Spring 对配置环境的抽象，管理：

- PropertySource。
- Profile。
- 系统环境变量。
- JVM 系统属性。
- 命令行参数。
- 配置文件加载结果。

`04-04-SpringBoot-配置绑定.md` 会详细展开配置源优先级和 `Binder`，本文只关注它在启动时的位置。

### 6.3 application.yml 何时进入 Environment

Environment 初步创建后，`SpringApplicationRunListeners#environmentPrepared()` 会发布环境准备事件。

Spring Boot 通过 `EnvironmentPostProcessorApplicationListener` 调用一组 `EnvironmentPostProcessor`，其中关键实现是：

```text
ConfigDataEnvironmentPostProcessor
```

简化链路：

```text
prepareEnvironment()
  -> listeners.environmentPrepared()
    -> ApplicationEnvironmentPreparedEvent
      -> EnvironmentPostProcessorApplicationListener
        -> ConfigDataEnvironmentPostProcessor
          -> 加载 application.properties / application.yml
          -> 处理 Profile 和 spring.config.import
          -> 把结果加入 PropertySources
```

这里只说明配置文件进入 Environment 的时机，配置源优先级和对象绑定留到 `04-04-SpringBoot-配置绑定.md`。

### 6.4 environmentPrepared 事件

Environment 准备完成后，启动监听器会收到环境已准备事件。

这个阶段：

- `ApplicationContext` 可能还没有完全准备。
- 业务 Bean 还没有创建。
- 适合处理早期配置和日志系统初始化。

---

## 七、createApplicationContext() 创建容器

### 7.1 容器类型选择

Spring Boot 会根据 `WebApplicationType` 创建对应容器。

Servlet Web 应用常见容器：

```text
AnnotationConfigServletWebServerApplicationContext
```

非 Web 应用常见容器：

```text
AnnotationConfigApplicationContext
```

### 7.2 此时容器还没有 refresh

`createApplicationContext()` 只是创建上下文对象。

此时：

- BeanFactory 还没有完成全部后置处理。
- 非懒加载单例还没有创建。
- 内嵌 Web 服务器还没有启动。

---

## 八、prepareContext() 容器准备

### 8.1 主要职责

`prepareContext()` 主要做：

```text
1. 把 Environment 设置给 ApplicationContext
2. 应用 ApplicationContextInitializer
3. 通知 contextInitialized
4. 注册启动参数、Banner 等单例
5. 把 primarySources 加载为 BeanDefinition
6. 通知 contextPrepared / contextLoaded
```

### 8.2 ApplicationContextInitializer

`ApplicationContextInitializer` 在 `refresh()` 前执行。

```java
public interface ApplicationContextInitializer<C extends ConfigurableApplicationContext> {
    void initialize(C applicationContext);
}
```

适合：

- 调整容器属性。
- 注册早期 PropertySource。
- 在 Bean 创建前准备容器。

不适合直接依赖普通业务 Bean，因为此时容器尚未刷新。

### 8.3 主配置类何时进入容器

`primarySources` 会在这个阶段被加载。

```text
Application.class
  -> AnnotatedBeanDefinitionReader
  -> BeanDefinition
  -> BeanDefinitionRegistry
```

后续 `ConfigurationClassPostProcessor` 会在 `refresh()` 中解析这个主配置类。

---

## 九、refreshContext() 与 Spring refresh()

### 9.1 Boot 在这里把控制权交给 Spring

`refreshContext(context)` 最终会进入：

```text
AbstractApplicationContext#refresh()
```

到这里，前面 IOC 阶段学过的内容开始生效：

- BeanFactoryPostProcessor 执行。
- `ConfigurationClassPostProcessor` 解析配置类。
- BeanPostProcessor 注册。
- 非懒加载单例创建。
- AOP 代理创建。

本文不重复 `refresh()` 13 步，只记住边界：

```text
refresh() 之前：Spring Boot 在准备容器
refresh() 之中：Spring Framework 在启动容器
refresh() 之后：Spring Boot 在完成启动收尾
```

### 9.2 自动配置在哪个阶段导入

`@EnableAutoConfiguration` 位于主配置类上。

它会在 `ConfigurationClassPostProcessor` 解析配置类时触发自动配置导入。

详细过程放在 `04-02-SpringBoot-自动配置.md`。

---

## 十、内嵌 Web 服务器如何启动

### 10.1 创建与启动是两个步骤

对 Servlet Web 应用，内嵌 Tomcat / Jetty / Undertow 的创建和启动都发生在容器 `refresh()` 过程中，但不是同一个步骤。

创建 WebServer：

```text
AbstractApplicationContext#refresh()
  -> onRefresh()
    -> ServletWebServerApplicationContext#onRefresh()
      -> createWebServer()
        -> ServletWebServerFactory#getWebServer()
```

真正启动 WebServer：

```text
AbstractApplicationContext#refresh()
  -> finishRefresh()
    -> ServletWebServerApplicationContext#finishRefresh()
      -> startWebServer()
        -> WebServer#start()
```

### 10.2 WebServerFactory 从哪里来

Spring Boot 自动配置会根据 classpath 提供 `ServletWebServerFactory`。

例如使用 Tomcat 时，常见实现是：

```text
TomcatServletWebServerFactory
```

它负责创建内嵌 Tomcat 对应的 `WebServer` 对象；容器后续在 `finishRefresh()` 阶段调用 `WebServer#start()` 完成启动。

### 10.3 DispatcherServlet 如何注册

Web 服务器启动时，Spring Boot 会把 `ServletContextInitializer` 应用到 ServletContext。

`DispatcherServletRegistrationBean` 会把 `DispatcherServlet` 注册到 Servlet 容器。

这与 MVC 阶段的主线衔接：

```text
Spring Boot 启动内嵌 Servlet 容器并注册 DispatcherServlet
Spring MVC 负责 DispatcherServlet 收到请求后的处理链路
```

---

## 十一、Runner 启动后任务

### 11.1 CommandLineRunner

```java
@Component
public class DataInitializer implements CommandLineRunner {

    @Override
    public void run(String... args) {
        // 启动后初始化任务
    }
}
```

### 11.2 ApplicationRunner

```java
@Component
public class ArgumentPrinter implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        // 可区分 option 和 non-option 参数
    }
}
```

### 11.3 执行时机

```text
ApplicationContext refresh 完成
  -> started
  -> CommandLineRunner / ApplicationRunner
  -> ready
```

此时绝大多数单例 Bean 已经创建，可以注入 Service、Repository 等 Bean。

### 11.4 Runner 使用建议

- 任务应该可重入。
- 不要长时间阻塞启动线程。
- 多个 Runner 用 `@Order` 或 `Ordered` 明确顺序。
- Runner 抛出异常会导致应用启动失败。
- 大型数据初始化更适合专用迁移工具或后台任务。

---

## 十二、Spring Boot 启动事件

### 12.1 典型事件时间线

```text
ApplicationStartingEvent
  -> ApplicationEnvironmentPreparedEvent
  -> ApplicationContextInitializedEvent
  -> ApplicationPreparedEvent
  -> ApplicationStartedEvent
  -> ApplicationReadyEvent
```

启动失败时：

```text
ApplicationFailedEvent
```

### 12.2 事件的边界

| 事件 | 可依赖的上下文 |
|---|---|
| Starting | 容器尚未创建，只能做极早期操作 |
| EnvironmentPrepared | Environment 已准备，普通 Bean 仍不可用 |
| ContextInitialized | ApplicationContext 已创建并应用 Initializer |
| Prepared | 主配置源已加载为 BeanDefinition，尚未 refresh；配置类和自动配置还未完成全量解析 |
| Started | refresh 完成，Runner 执行前 |
| Ready | Runner 执行完成，应用就绪 |
| Failed | 启动链路失败 |

### 12.3 事件监听不要滥用

启动事件适合框架扩展和基础设施，普通业务初始化优先考虑 Runner 或明确的业务编排。

---

## 十三、SpringApplication 扩展点对比

| 扩展点 | 时机 | 适合场景 |
|---|---|---|
| `BootstrapRegistryInitializer` | ApplicationContext 之前 | 启动引导阶段资源 |
| `ApplicationContextInitializer` | Context 创建后、refresh 前 | 调整容器和 PropertySource |
| `ApplicationListener` | 启动事件阶段 | 监听启动生命周期 |
| `SpringApplicationRunListener` | 接收 run 主流程各阶段回调 | 框架级启动监听 |
| `ApplicationRunner` | refresh 后 | 需要结构化启动参数的任务 |
| `CommandLineRunner` | refresh 后 | 简单启动后任务 |

选择原则：

```text
需要多早执行？
此时 ApplicationContext 是否存在？
是否需要使用普通 Bean？
```

---

## 十四、启动失败处理

### 14.1 handleRunFailure()

`run()` 主流程中的异常会进入失败处理：

```text
SpringApplication#handleRunFailure()
  -> 注册退出码
  -> 通知 failed
  -> 关闭 ApplicationContext
  -> 重新抛出异常
```

### 14.2 FailureAnalyzer

Spring Boot 会尝试把一些常见异常转换成更友好的启动失败说明。

常见输出：

```text
APPLICATION FAILED TO START

Description:
...

Action:
...
```

这比只看最外层异常更容易定位问题。

### 14.3 排查原则

- 先看 `Description` 和 `Action`。
- 再找第一个有业务意义的 `Caused by`。
- 不要只看异常栈最后一行。
- 启用 debug 日志查看条件装配结果。

---

## 十五、常见启动问题排查

### 15.1 Bean 没有被扫描

检查：

- 启动类包位置。
- `@ComponentScan` 范围。
- 模块依赖是否存在。
- 条件注解是否过滤了 Bean。

### 15.2 Web 服务器没有启动

检查：

- 是否引入 Web Starter。
- `WebApplicationType` 是否被设置为 `NONE`。
- 内嵌服务器依赖是否被排除。
- 应用是 Servlet 还是 Reactive。

### 15.3 端口被占用

典型失败：

```text
Web server failed to start. Port 8080 was already in use.
```

处理：

- 停止占用端口的进程。
- 修改 `server.port`。
- 测试中使用随机端口。

### 15.4 Runner 导致启动失败

Runner 中抛出异常，应用不会进入 ready 状态。

排查时不要误以为“Web 服务器已启动就算启动成功”。

---

## 十六、源码断点建议

### 16.1 SpringApplication 构造阶段

```text
SpringApplication#SpringApplication
WebApplicationType#deduceFromClasspath
SpringApplication#deduceMainApplicationClass
```

观察：主配置类、应用类型、Initializer、Listener。

### 16.2 run() 主流程

```text
SpringApplication#run
SpringApplication#getRunListeners
SpringApplication#prepareEnvironment
EnvironmentPostProcessorApplicationListener#onApplicationEvent
ConfigDataEnvironmentPostProcessor#postProcessEnvironment
SpringApplication#createApplicationContext
SpringApplication#prepareContext
SpringApplication#refreshContext
SpringApplication#callRunners
```

### 16.3 容器和 Web 服务器

```text
AbstractApplicationContext#refresh
ServletWebServerApplicationContext#onRefresh
ServletWebServerApplicationContext#createWebServer
TomcatServletWebServerFactory#getWebServer
ServletWebServerApplicationContext#finishRefresh
ServletWebServerApplicationContext#startWebServer
WebServer#start
```

### 16.4 启动失败

```text
SpringApplication#handleRunFailure
FailureAnalyzers#reportException
```

---

## 十七、必做实验

### 17.1 实验一：跟踪 run() 主线

1. 创建最小 Spring Boot Web 应用。
2. 在 `SpringApplication#run` 打断点。
3. 记录 `WebApplicationType`。
4. 观察 Environment 创建。
5. 观察 ApplicationContext 实际类型。
6. 跟踪到 `refreshContext()`。

过关标准：

```text
能画出 run() 在 refresh() 前后的编排流程
```

### 17.2 实验二：对比 Web 与非 Web 应用

1. 启动普通 Web 应用，记录 Context 类型。
2. 设置 `WebApplicationType.NONE`。
3. 再次启动，观察是否创建 WebServer。

### 17.3 实验三：观察启动事件

1. 注册一个 `ApplicationListener<ApplicationEvent>`。
2. 记录启动事件类型和时间。
3. 在 Runner 中打印日志。
4. 对比 Started、Runner、Ready 的顺序。

### 17.4 实验四：制造启动失败

1. 占用应用端口。
2. 启动应用。
3. 观察 FailureAnalyzer 输出。
4. 监听 `ApplicationFailedEvent`。

### 17.5 实验五：Runner 顺序和失败

1. 定义多个 Runner。
2. 用 `@Order` 设置顺序。
3. 让其中一个 Runner 抛异常。
4. 观察应用是否发布 Ready 事件。

---

## 十八、面试常问问题

### 18.1 SpringApplication.run() 做了什么？

推荐回答：

```text
SpringApplication.run() 会准备启动监听器和参数，创建并准备 Environment，
根据 WebApplicationType 创建合适的 ApplicationContext，应用 Initializer，
加载主配置类，再调用 refresh() 启动 Spring 容器。容器启动完成后，
它会执行 Runner，发布 Started 和 Ready 等启动事件。
```

### 18.2 Spring Boot 和 Spring refresh() 是什么关系？

```text
Spring Boot 负责准备 Environment、ApplicationContext、主配置类和启动扩展点，
最终仍然调用 AbstractApplicationContext#refresh() 启动 Spring 容器。
Boot 是启动编排层，refresh() 是 Spring 容器启动核心。
```

### 18.3 内嵌 Tomcat 在什么时候启动？

```text
对 Servlet Web 应用，ApplicationContext refresh 过程会调用
ServletWebServerApplicationContext#onRefresh()，再通过 ServletWebServerFactory
创建和启动内嵌 WebServer。
```

### 18.4 ApplicationRunner 和 CommandLineRunner 有什么区别？

```text
两者都在 ApplicationContext refresh 完成后执行。ApplicationRunner 接收
ApplicationArguments，可区分 option 和 non-option 参数；CommandLineRunner
直接接收原始 String[] 参数。
```

### 18.5 启动类为什么建议放在根包？

```text
@SpringBootApplication 包含 @ComponentScan，默认从启动类所在包向下扫描。
放在根包能覆盖 Controller、Service、Repository、Configuration 等业务组件，
避免因包层级不对导致 Bean 没有注册。
```

---

## 十九、本篇过关标准

学完本文后，需要能做到：

- 能说明 `@SpringBootApplication` 的组成和作用。
- 能画出 `SpringApplication.run()` 总流程。
- 能区分 Spring Boot 启动编排和 Spring `refresh()` 的职责。
- 能说明 `WebApplicationType` 如何影响 ApplicationContext 类型。
- 能说明 Environment、Initializer、Listener、Runner 的执行时机。
- 能说明内嵌 Web 服务器在什么时候创建。
- 能排查 Bean 未扫描、WebServer 未启动、端口冲突、Runner 失败等问题。
- 能在源码中从 `SpringApplication#run()` 跟踪到 `AbstractApplicationContext#refresh()`。

---

## 二十、下一篇建议

下一篇继续深入 Spring Boot 自动配置：

```text
04-02-SpringBoot-自动配置.md
```

重点拆解：

- `@EnableAutoConfiguration` 如何导入候选自动配置类。
- `AutoConfigurationImportSelector` 的选择流程。
- `AutoConfiguration.imports` 如何加载候选类。
- `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 如何判断。
- 自动配置的排序、排除和退让机制。
