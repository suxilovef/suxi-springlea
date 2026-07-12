---
title: 01-02-Spring-IOC-ConfigurationClassPostProcessor
date: 2026-07-09 20:07:05
description: Spring-IOC-ConfigurationClassPostProcessor
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-ccpp.svg
rank: 88
top_img: false
---
# Spring IOC 专题：ConfigurationClassPostProcessor 深度学习文档

> 本文基于 Spring 源码学习整理。示例版本背景：Spring Boot `3.3.9`、Spring Framework `6.1.17`、Java `17`。
>
> `ConfigurationClassPostProcessor` 是 Spring 注解驱动 IOC 的核心入口。理解它，才能真正理解 `@Configuration`、`@ComponentScan`、`@Bean`、`@Import`、Spring Boot 自动配置、`@EnableXxx` 系列注解如何变成容器中的 `BeanDefinition`。

---

## 一、先给结论

### 1.1 ConfigurationClassPostProcessor 是什么

**一句话概括**：`ConfigurationClassPostProcessor` 是 Spring 容器启动早期执行的一个 `BeanDefinitionRegistryPostProcessor`，负责解析配置类，并把注解配置转换成 `BeanDefinition` 注册到容器。

它处理的核心注解包括：

- `@Configuration`
- `@Component`
- `@ComponentScan`
- `@Bean`
- `@Import`
- `@ImportResource`
- `@PropertySource`
- `@Conditional`

这些注解背后都与 `ConfigurationClassPostProcessor` 的配置类解析链路有关。

### 1.2 它在 refresh() 哪一步工作

`ConfigurationClassPostProcessor` 工作在 `AbstractApplicationContext.refresh()` 的第 5 步：

```java
invokeBeanFactoryPostProcessors(beanFactory);
```

整体位置如下：

```text
SpringApplication.run()
  └── refreshContext()
      └── AbstractApplicationContext.refresh()
          ├── prepareRefresh()
          ├── obtainFreshBeanFactory()
          ├── prepareBeanFactory()
          ├── postProcessBeanFactory()
          ├── invokeBeanFactoryPostProcessors()  ← ConfigurationClassPostProcessor 在这里执行
          ├── registerBeanPostProcessors()
          ├── initMessageSource()
          ├── initApplicationEventMulticaster()
          ├── onRefresh()
          ├── registerListeners()
          ├── finishBeanFactoryInitialization()
          └── finishRefresh()
```

**关键时序**：

- `ConfigurationClassPostProcessor` 执行时，大多数普通业务 Bean 还没有实例化。
- 它操作的是 `BeanDefinition`，不是 Bean 实例。
- 它执行完成后，`@ComponentScan` 扫描出的 Bean、`@Bean` 方法定义的 Bean、`@Import` 导入的 Bean 才真正进入 BeanDefinition 注册表。
- 后续 `finishBeanFactoryInitialization()` 才会根据这些 BeanDefinition 创建单例 Bean。

### 1.3 为什么它重要

没有 `ConfigurationClassPostProcessor`，注解驱动的 Spring 基本无法工作：

```java
@Configuration
class AppConfig {
    @Bean
    UserService userService() {
        return new UserService();
    }
}
```

如果没有它，Spring 只知道有一个 `AppConfig` 类，不知道：

- `AppConfig` 是配置类
- `userService()` 是一个 `@Bean` 工厂方法
- `@ComponentScan` 要扫描哪些包
- `@Import` 要导入哪些类
- `@Conditional` 是否应该跳过某些 Bean
- Spring Boot 自动配置类应该如何加载
- `@EnableFeignClients` 这类注解如何注册额外 BeanDefinition

---

## 二、它的类型体系

### 2.1 类声明

源码中的核心声明可以抽象理解为：

```java
public class ConfigurationClassPostProcessor
        implements BeanDefinitionRegistryPostProcessor,
                   PriorityOrdered,
                   ResourceLoaderAware,
                   BeanClassLoaderAware,
                   EnvironmentAware {
}
```

几个接口含义如下：

| 接口 | 作用 |
|------|------|
| `BeanDefinitionRegistryPostProcessor` | 可以在 Bean 实例化前注册、修改 BeanDefinition |
| `BeanFactoryPostProcessor` | `BeanDefinitionRegistryPostProcessor` 的父接口，可以修改 BeanFactory |
| `PriorityOrdered` | 保证它在同类后处理器中优先执行 |
| `EnvironmentAware` | 获取环境变量，用于 `@Profile`、`@Conditional`、属性解析 |
| `ResourceLoaderAware` | 加载 classpath、文件、XML 等资源 |
| `BeanClassLoaderAware` | 获取类加载器，用于读取类元信息 |

### 2.2 为什么它必须很早执行

它必须早于普通 Bean 实例化，因为它要先把“配置”翻译成“Bean 定义”：

```text
注解配置
  ↓
ConfigurationClassPostProcessor 解析
  ↓
BeanDefinition 注册表
  ↓
BeanFactory 根据 BeanDefinition 创建 Bean
```

如果普通 Bean 已经开始创建，再去解析 `@ComponentScan` 或 `@Bean`，就会出现依赖找不到、BeanDefinition 缺失、自动配置不完整等问题。

---

## 三、它是怎么被注册进容器的

### 3.1 AnnotationConfigUtils 注册内置处理器

当使用注解驱动的上下文或 Spring Boot 启动时，Spring 会注册一组基础设施 BeanDefinition。

核心方法是：

```java
AnnotationConfigUtils.registerAnnotationConfigProcessors(registry);
```

其中会注册：

```text
org.springframework.context.annotation.internalConfigurationAnnotationProcessor
  → ConfigurationClassPostProcessor

org.springframework.context.annotation.internalAutowiredAnnotationProcessor
  → AutowiredAnnotationBeanPostProcessor

org.springframework.context.annotation.internalCommonAnnotationProcessor
  → CommonAnnotationBeanPostProcessor

org.springframework.context.event.internalEventListenerProcessor
  → EventListenerMethodProcessor

org.springframework.context.event.internalEventListenerFactory
  → DefaultEventListenerFactory
```

也就是说，`ConfigurationClassPostProcessor` 自己也是一个 BeanDefinition，只是它是基础设施 Bean。

### 3.2 它的 Bean 名称

Spring 内部固定名称：

```java
org.springframework.context.annotation.internalConfigurationAnnotationProcessor
```

调试时可以在 BeanDefinition 注册表中搜索这个名字。

### 3.3 Spring Boot 中的触发链路

Spring Boot 启动时：

```text
DemoApplication.main()
  └── SpringApplication.run(DemoApplication.class, args)
      └── 创建 ApplicationContext
          ├── 创建 AnnotatedBeanDefinitionReader
          │   └── 注册 ConfigurationClassPostProcessor 等基础设施 BeanDefinition
          ├── 加载 primarySources
          │   └── 注册 DemoApplication 的 BeanDefinition
          └── refresh()
              └── invokeBeanFactoryPostProcessors()
                  └── 执行 ConfigurationClassPostProcessor
```

注意：在 `refresh()` 之前，通常只注册了启动类、少量基础设施类和上下文自身需要的定义。大量业务 Bean 是后续被 `ConfigurationClassPostProcessor` 解析出来的。

---

## 四、核心入口方法

`ConfigurationClassPostProcessor` 有两个关键阶段。

### 4.1 postProcessBeanDefinitionRegistry()

这是最核心的方法，负责解析配置类并注册新的 BeanDefinition。

简化流程：

```java
@Override
public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    processConfigBeanDefinitions(registry);
}
```

它做的事情包括：

- 找出候选配置类
- 解析配置类
- 处理 `@ComponentScan`
- 处理 `@Import`
- 处理 `@Bean`
- 处理 `@PropertySource`
- 处理 `@ImportResource`
- 注册解析出来的 BeanDefinition

### 4.2 postProcessBeanFactory()

这个方法在 `postProcessBeanDefinitionRegistry()` 之后执行，主要负责增强配置类。

简化流程：

```java
@Override
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    enhanceConfigurationClasses(beanFactory);
    beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
}
```

关键点：

- 对 full 模式的 `@Configuration` 类生成 CGLIB 子类。
- 保证配置类中 `@Bean` 方法互相调用时返回容器中的单例 Bean。
- 添加 `ImportAwareBeanPostProcessor`，让被 `@Import` 导入的类可以感知导入来源。

---

## 五、完整源码链路

### 5.1 总览链路

```text
AbstractApplicationContext.refresh()
  └── invokeBeanFactoryPostProcessors(beanFactory)
      └── PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors()
          └── invokeBeanDefinitionRegistryPostProcessors()
              └── ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry()
                  └── processConfigBeanDefinitions(registry)
                      ├── 找候选配置类
                      ├── ConfigurationClassParser.parse()
                      │   ├── parse()
                      │   └── processConfigurationClass()
                      │       └── doProcessConfigurationClass()
                      │           ├── 处理 @PropertySource
                      │           ├── 处理 @ComponentScan
                      │           ├── 处理 @Import
                      │           ├── 处理 @ImportResource
                      │           ├── 处理 @Bean 方法
                      │           └── 处理父类
                      └── ConfigurationClassBeanDefinitionReader.loadBeanDefinitions()
                          ├── 注册 @Bean 方法对应的 BeanDefinition
                          ├── 注册 @Import 导入类对应的 BeanDefinition
                          └── 注册 ImportedBeanDefinitionRegistrar 添加的 BeanDefinition
```

### 5.2 processConfigBeanDefinitions()

这是主流程方法。

核心逻辑可以拆成 6 步：

```text
1. 从 registry 中拿到所有 BeanDefinition 名称
2. 判断哪些 BeanDefinition 是配置类候选
3. 按 @Order / Ordered 排序
4. 创建 ConfigurationClassParser
5. 解析配置类，生成 ConfigurationClass 集合
6. 使用 ConfigurationClassBeanDefinitionReader 注册解析结果
```

伪代码：

```java
public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
    List<BeanDefinitionHolder> configCandidates = new ArrayList<>();

    String[] candidateNames = registry.getBeanDefinitionNames();

    for (String beanName : candidateNames) {
        BeanDefinition bd = registry.getBeanDefinition(beanName);

        if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, metadataReaderFactory)) {
            configCandidates.add(new BeanDefinitionHolder(bd, beanName));
        }
    }

    configCandidates.sort(...);

    ConfigurationClassParser parser = new ConfigurationClassParser(...);
    parser.parse(configCandidates);
    parser.validate();

    Set<ConfigurationClass> configClasses = parser.getConfigurationClasses();

    ConfigurationClassBeanDefinitionReader reader =
        new ConfigurationClassBeanDefinitionReader(...);
    reader.loadBeanDefinitions(configClasses);
}
```

### 5.3 为什么要循环解析

`ConfigurationClassPostProcessor` 的 `processConfigBeanDefinitions()` 使用外层 `do...while`，直到注册表中不再出现新的、尚未解析的配置类候选。

需要区分两种递归机制：

1. `ConfigurationClassParser` 内部递归：直接 `@Import` 的配置类，以及 `@ComponentScan` 扫描出的配置类候选，通常会在当前解析轮次继续调用 `parse()`。
2. `ConfigurationClassPostProcessor` 外层循环：`ConfigurationClassBeanDefinitionReader` 落地模型或调用 `ImportBeanDefinitionRegistrar` 后，注册表可能新增配置类 BeanDefinition，这些候选需要下一轮解析。

例如 registrar 在模型读取阶段动态注册了另一个配置类：

```java
@Configuration
@Import(ExtraConfigRegistrar.class)
class AppConfig {
}

class ExtraConfigRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        registry.registerBeanDefinition(
            "extraConfig", new AnnotatedGenericBeanDefinition(ExtraConfig.class));
    }
}

@Configuration
class ExtraConfig {
    @Bean
    UserService userService() {
        return new UserService();
    }
}
```

`ExtraConfig` 的 BeanDefinition 在 reader 调用 registrar 时才加入注册表。外层循环会识别这个新增候选并继续解析，直到没有新的配置类。

---

## 六、配置类候选是如何判断的

### 6.1 Full 模式与 Lite 模式

Spring 会把配置类分成两类：

| 类型 | 判断条件 | 是否 CGLIB 增强 | 典型场景 |
|------|----------|----------------|----------|
| Full Configuration | 标注 `@Configuration(proxyBeanMethods = true)` | 是 | 正式配置类 |
| Lite Configuration | 标注 `@Component`、`@ComponentScan`、`@Import`、`@ImportResource`，或存在 `@Bean` 方法 | 否 | 普通组件内声明 `@Bean` |

### 6.2 Full 模式示例

```java
@Configuration
public class AppConfig {

    @Bean
    public UserDao userDao() {
        return new UserDao();
    }

    @Bean
    public UserService userService() {
        return new UserService(userDao());
    }
}
```

默认 `proxyBeanMethods = true`，Spring 会给 `AppConfig` 生成 CGLIB 代理。

当 `userService()` 调用 `userDao()` 时，不会直接执行普通 Java 方法创建新对象，而是被代理拦截，返回容器里的单例 `userDao`。

### 6.3 Lite 模式示例

```java
@Component
public class AppComponent {

    @Bean
    public UserDao userDao() {
        return new UserDao();
    }
}
```

这个类不是 full 配置类，不会被 CGLIB 增强。`@Bean` 方法仍会被解析成 BeanDefinition，但类内部直接调用该方法时就是普通 Java 调用。

## 七、@ComponentScan 是如何被解析的

### 7.1 扫描入口

常见配置：

```java
@ComponentScan(value = {"com.example"})
```

这意味着 Spring 会扫描 `com.example` 下所有符合条件的组件类。

解析链路：

```text
ConfigurationClassParser.doProcessConfigurationClass()
  └── 读取 @ComponentScan / @ComponentScans
      └── ComponentScanAnnotationParser.parse()
          └── ClassPathBeanDefinitionScanner.doScan()
              ├── findCandidateComponents(basePackage)
              ├── 判断 @Component / @Service / @Repository / @Controller
              ├── 应用 includeFilters / excludeFilters
              ├── 生成 ScannedGenericBeanDefinition
              └── registry.registerBeanDefinition(beanName, beanDefinition)
```

### 7.2 扫描时是否会加载类

默认情况下，Spring 扫描 classpath 时主要通过 ASM 读取 `.class` 文件元数据，不需要真正加载类。

这点很重要：

- 扫描速度更快
- 避免过早触发静态代码块
- 避免不必要的类初始化
- 可以在类未真正加载前判断注解信息

### 7.3 扫描出来的 BeanDefinition 长什么样

对于：

```java
@Service
public class UserService {
}
```

扫描后会注册类似：

```text
beanName: userService
beanClassName: com.example.xxx.UserService
scope: singleton
lazyInit: false
autowireCandidate: true
```

此时只是注册 BeanDefinition，并没有创建 `UserService` 实例。

---

## 八、@Bean 是如何被解析的

### 8.1 @Bean 方法的处理时机

`@Bean` 方法不是在扫描阶段直接执行，而是在解析配置类时记录下来，然后注册为 BeanDefinition。

链路：

```text
ConfigurationClassParser.doProcessConfigurationClass()
  └── retrieveBeanMethodMetadata()
      └── 收集 @Bean 方法元数据

ConfigurationClassBeanDefinitionReader.loadBeanDefinitions()
  └── loadBeanDefinitionsForBeanMethod()
      └── 注册 ConfigurationClassBeanDefinition
```

### 8.2 @Bean 方法不是立即调用

例如：

```java
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

在 `ConfigurationClassPostProcessor` 阶段，Spring 不会调用这个方法，只会注册定义：

```text
factoryBeanName = appConfig
factoryMethodName = restTemplate
```

真正调用发生在后续：

```text
finishBeanFactoryInitialization()
  └── preInstantiateSingletons()
      └── getBean("restTemplate")
          └── createBean()
              └── instantiateUsingFactoryMethod()
                  └── 调用 restTemplate()
```

### 8.3 @Bean 方法上的常见属性

```java
@Bean(
    name = "restTemplate",
    initMethod = "init",
    destroyMethod = "close",
    autowireCandidate = true
)
@Primary
@Lazy
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

这些信息都会进入 BeanDefinition，影响后续 Bean 创建和依赖注入。

---

## 九、@Import 是如何被解析的

`@Import` 是 Spring 扩展能力最强的入口之一，也是 Spring Boot 自动配置和 `@EnableXxx` 注解的底层基础。

### 9.1 @Import 支持三种类型

| 类型 | 说明 | 例子 |
|------|------|------|
| 普通类 | 直接作为配置类或 Bean 注册 | `@Import(UserConfig.class)` |
| `ImportSelector` | 返回要导入的类名数组 | Spring Boot 自动配置 |
| `ImportBeanDefinitionRegistrar` | 手动向 registry 注册 BeanDefinition | Feign、AOP、MapperScan 常用 |

### 9.2 导入普通类

```java
@Configuration
@Import(UserConfig.class)
public class AppConfig {
}
```

`UserConfig` 会被当成配置类继续解析。

### 9.3 ImportSelector

```java
public class MyImportSelector implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        return new String[] {
            "com.example.demo.UserConfig"
        };
    }
}
```

特点：

- 输入是当前导入类的注解元数据
- 输出是一批类名
- 输出的类会继续参与配置类解析

Spring Boot 的自动配置机制就是通过 `DeferredImportSelector` 的延迟导入能力实现的。

### 9.4 ImportBeanDefinitionRegistrar

```java
public class MyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata importingClassMetadata,
            BeanDefinitionRegistry registry) {

        RootBeanDefinition bd = new RootBeanDefinition(UserService.class);
        registry.registerBeanDefinition("userService", bd);
    }
}
```

特点：

- 可以直接操作 `BeanDefinitionRegistry`
- 适合动态注册 BeanDefinition
- 很多 `@EnableXxx` 注解底层都使用它

常见的 `@EnableXxx` 注解：

```java
@EnableScheduling
@EnableAsync
```

这些注解通常会通过 `@Import` 导入 registrar 或 selector，再由 `ConfigurationClassPostProcessor` 解析并触发注册逻辑。

### 9.5 DeferredImportSelector 与自动配置

Spring Boot 自动配置不是普通 `ImportSelector`，而是延迟导入选择器：

```text
@SpringBootApplication
  └── @EnableAutoConfiguration
      └── @Import(AutoConfigurationImportSelector.class)
```

`AutoConfigurationImportSelector` 实现了 `DeferredImportSelector`。

为什么要延迟？

- 先解析用户自己的配置类
- 再解析自动配置类
- 用户配置优先，自动配置兜底
- `@ConditionalOnMissingBean` 才能正确判断用户是否已经定义 Bean

Spring Boot 3.x 自动配置候选主要来自：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

老版本常见的 `META-INF/spring.factories` 仍可能在部分扩展点中出现，但讲 Spring Boot 3.x 自动配置时，应优先以 `AutoConfiguration.imports` 为主。

---

## 十、@Conditional 是如何生效的

### 10.1 条件判断入口

`ConfigurationClassPostProcessor` 解析配置类时，会使用 `ConditionEvaluator` 判断条件。

常见注解：

```java
@Conditional(MyCondition.class)
@Profile("dev")
@ConditionalOnClass(RedisTemplate.class)
@ConditionalOnMissingBean(CacheManager.class)
@ConditionalOnProperty(prefix = "cache", name = "enabled", havingValue = "true")
```

### 10.2 条件判断有两个阶段

Spring 条件判断分两类：

| 阶段 | 枚举 | 说明 |
|------|------|------|
| 配置类解析阶段 | `ConfigurationPhase.PARSE_CONFIGURATION` | 决定配置类是否需要继续解析 |
| Bean 注册阶段 | `ConfigurationPhase.REGISTER_BEAN` | 决定 `@Bean` 是否注册 |

示例：

```java
@Configuration
@ConditionalOnClass(RedisTemplate.class)
public class RedisConfig {

    @Bean
    @ConditionalOnMissingBean
    public CacheManager cacheManager() {
        return new RedisCacheManager();
    }
}
```

含义：

- 如果 classpath 没有 `RedisTemplate`，整个 `RedisConfig` 都跳过。
- 如果已经有 `CacheManager` BeanDefinition，`cacheManager()` 这个 `@Bean` 跳过。

### 10.3 为什么自动配置要后解析

以 `@ConditionalOnMissingBean` 为例：

```java
@Bean
@ConditionalOnMissingBean
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

如果自动配置先解析，它会认为容器中没有 `RestTemplate`，于是注册默认 Bean。

但如果用户配置后面才解析：

```java
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

就可能出现默认 Bean 与用户 Bean 冲突。

所以 Spring Boot 通过 `DeferredImportSelector` 让自动配置延后处理，保证用户配置优先被看见。

---

## 十一、@PropertySource 与 Environment

配置类中可以声明：

```java
@PropertySource(value = "classpath:application-extra.properties")
```

解析链路：

```text
ConfigurationClassParser.doProcessConfigurationClass()
  └── 读取 @PropertySource
      └── PropertySourceRegistry / Environment
          └── 加入 MutablePropertySources
```

这使得后续可以通过 `Environment` 获取属性。

例如：

```java
@Component
public class MyProperties {

    private final Environment environment;

    public MyProperties(Environment environment) {
        this.environment = environment;
    }

    public String getValue(String key) {
        return environment.getProperty(key);
    }
}
```

这里要注意：`@PropertySource` 的解析发生在配置类解析阶段，它影响的是 `Environment` 中的属性源，而不是直接创建业务 Bean。

---

## 十二、ConfigurationClassParser 详解

### 12.1 它负责什么

`ConfigurationClassParser` 负责把一个候选配置类解析成 `ConfigurationClass` 模型。

它的主要职责是构建中间模型，而不是把 `@Bean`、imported 配置类等结果直接落地为 BeanDefinition：

```text
BeanDefinition
  ↓
ConfigurationClassParser
  ↓
ConfigurationClass
  ├── beanMethods
  ├── importedResources
  ├── importBeanDefinitionRegistrars
  └── importedBy
```

之后再由 `ConfigurationClassBeanDefinitionReader` 把这些模型转换为 BeanDefinition。需要注意，处理 `@ComponentScan` 时，Parser 会委托 `ClassPathBeanDefinitionScanner`，扫描器会在解析过程中直接注册扫描到的 BeanDefinition。

### 12.2 doProcessConfigurationClass()

核心方法是：

```java
protected final SourceClass doProcessConfigurationClass(
        ConfigurationClass configClass,
        SourceClass sourceClass,
        Predicate<String> filter) {
}
```

可以理解为按顺序处理：

```text
1. 如果配置类内部有成员类，先处理成员类
2. 处理 @PropertySource
3. 处理 @ComponentScan
4. 处理 @Import
5. 处理 @ImportResource
6. 处理 @Bean 方法
7. 处理接口默认方法中的 @Bean
8. 处理父类
```

### 12.3 为什么父类也要处理

配置类可能继承父类：

```java
public class BaseConfig {
    @Bean
    public BaseService baseService() {
        return new BaseService();
    }
}

@Configuration
public class AppConfig extends BaseConfig {
}
```

Spring 需要继续向上查找父类中的 `@Bean` 方法。

---

## 十三、ConfigurationClassBeanDefinitionReader 详解

### 13.1 它负责什么

`ConfigurationClassBeanDefinitionReader` 负责把解析后的 `ConfigurationClass` 落地到 BeanDefinition 注册表。

它处理：

- 配置类自身
- `@Bean` 方法
- `@ImportResource` XML
- `ImportBeanDefinitionRegistrar`
- imported 配置类

### 13.2 @Bean 方法如何变成 BeanDefinition

例如：

```java
@Bean("restTemplate")
public RestTemplate getRestTemplate() {
    return new RestTemplate();
}
```

注册后类似：

```text
ConfigurationClassBeanDefinition
  beanName = restTemplate
  factoryBeanName = appConfig
  factoryMethodName = getRestTemplate
  autowireMode = constructor
  role = application
```

`factoryBeanName` 和 `factoryMethodName` 是理解 `@Bean` 的关键。

它说明：这个 Bean 不是通过构造器直接 new 出来的，而是通过另一个 Bean 的工厂方法创建的。

### 13.3 imported 配置类如何注册

如果配置类是通过 `@Import` 导入的，Spring 会为它生成 BeanDefinition，并记录导入关系。

这样后续 `ImportAware` 可以知道自己是被哪个类导入的。

---

## 十四、配置类增强：为什么 @Configuration 会被 CGLIB 代理

### 14.1 问题场景

```java
@Configuration
public class AppConfig {

    @Bean
    public UserDao userDao() {
        return new UserDao();
    }

    @Bean
    public UserService userService() {
        return new UserService(userDao());
    }
}
```

如果没有 CGLIB 代理，`userService()` 里调用 `userDao()` 就是普通 Java 方法调用，会创建一个新的 `UserDao`，而不是容器中的单例。

### 14.2 CGLIB 增强后的效果

Spring 会把 `AppConfig` 增强成子类，拦截 `@Bean` 方法调用：

```text
userService()
  └── 调用 userDao()
      └── 被 CGLIB 拦截
          ├── 容器中已有 userDao → 返回已有单例
          └── 尚无 userDao 实例 → 通过 BeanFactory 创建并放入单例池
```

### 14.3 proxyBeanMethods = false

```java
@Configuration(proxyBeanMethods = false)
public class AppConfig {
}
```

这叫轻量模式：

- 不生成 CGLIB 代理
- 启动更快
- 适合 `@Bean` 方法之间没有互相调用的配置类
- 如果 `@Bean` 方法互相调用，会产生普通 Java 方法调用语义

Spring Boot 很多自动配置类都使用：

```java
@AutoConfiguration
public class CacheAutoConfiguration {
}
```

`@AutoConfiguration` 本身已经组合了 `@Configuration(proxyBeanMethods = false)`，不需要重复声明。自动配置类通常通过方法参数注入依赖，而不是直接调用另一个 `@Bean` 方法。

推荐写法：

```java
@Bean
public UserService userService(UserDao userDao) {
    return new UserService(userDao);
}
```

不推荐写法：

```java
@Bean
public UserService userService() {
    return new UserService(userDao());
}
```

---

## 十五、与 BeanFactoryPostProcessor 执行顺序的关系

### 15.1 执行顺序总览

`invokeBeanFactoryPostProcessors()` 中大致顺序：

```text
1. 执行手动添加的 BeanDefinitionRegistryPostProcessor
2. 执行容器中的 PriorityOrdered BeanDefinitionRegistryPostProcessor
   └── ConfigurationClassPostProcessor 在这里
3. 执行容器中的 Ordered BeanDefinitionRegistryPostProcessor
4. 执行容器中的普通 BeanDefinitionRegistryPostProcessor
5. 执行这些处理器的 postProcessBeanFactory()
6. 执行普通 BeanFactoryPostProcessor
```

### 15.2 为什么要分 PriorityOrdered / Ordered / 普通

因为某些后处理器必须先执行。

`ConfigurationClassPostProcessor` 必须尽早执行，因为只有它先完成扫描，其他通过 `@Component` 声明的 `BeanFactoryPostProcessor` 才能被发现。

例如：

```java
@Component
public class MyPostProcessor implements BeanFactoryPostProcessor {
}
```

如果没有先执行配置类扫描，容器根本不知道 `MyPostProcessor` 是一个 `BeanFactoryPostProcessor`。

### 15.3 为什么 BeanFactoryPostProcessor 里不建议依赖普通 Bean

`BeanFactoryPostProcessor` 工作在普通 Bean 实例化之前。

如果在这个阶段调用 `getBean()` 强行获取普通 Bean：

```java
@Component
public class MyPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        UserService userService = beanFactory.getBean(UserService.class); // 不推荐
    }
}
```

此阶段 `AutowiredAnnotationBeanPostProcessor` 尚未完成注册，因此也不能依赖 BFPP 字段上的 `@Autowired` 自动注入普通业务 Bean。

容易导致：

- Bean 过早实例化
- 跳过某些 BeanPostProcessor
- AOP 代理未生效
- 配置类增强时序异常
- 隐蔽的启动问题

更推荐：

```java
@Component
public class MyPostProcessor implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }
}
```

在后处理器阶段优先使用：

- `Environment`
- `ResourceLoader`
- `BeanDefinitionRegistry`
- `ConfigurableListableBeanFactory`
- BeanDefinition 元数据

---

## 十六、专题一：@Import 的真实源码调用链

前面的章节已经介绍了 `@Import` 支持普通类、`ImportSelector` 和
`ImportBeanDefinitionRegistrar`。这一节继续深入到
`ConfigurationClassParser` 内部，观察不同导入类型如何进入不同处理分支。

### 16.1 源码入口

`@Import` 的入口位于 `doProcessConfigurationClass()`：

```text
ConfigurationClassParser.doProcessConfigurationClass()
  └── getImports(sourceClass)
      └── processImports(
              configClass,
              sourceClass,
              importCandidates,
              exclusionFilter,
              true)
```

`configClass` 是正在构建的配置类模型，`sourceClass` 是当前读取的类，
`importCandidates` 是从当前类及其组合注解中找到的导入候选。

### 16.2 getImports() 为什么需要递归

实际项目中的 `@Import` 往往隐藏在组合注解中：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(MyFeatureSelector.class)
public @interface EnableMyFeature {
}

@Configuration
@EnableMyFeature
public class AppConfig {
}
```

Parser 需要递归遍历注解关系：

```text
AppConfig
  └── @EnableMyFeature
      └── @Import(MyFeatureSelector.class)
          └── 得到 MyFeatureSelector
```

可以把其职责理解成：

```java
Set<SourceClass> getImports(SourceClass sourceClass) {
    Set<SourceClass> imports = new LinkedHashSet<>();
    collectImports(sourceClass, imports, new HashSet<>());
    return imports;
}
```

`collectImports()` 使用 visited 集合防止组合注解之间无限递归，并保持导入顺序。

需要注意：

- 它读取的是注解元数据，不是扫描类路径上的全部配置类。
- 一个组合注解可以继续组合另一个带有 `@Import` 的注解。
- 得到的是 `SourceClass`，此时还没有创建目标类的 BeanDefinition。

### 16.3 processImports() 的四个核心分支

```text
importCandidate
  ├── DeferredImportSelector
  │   └── 暂存到 DeferredImportSelectorHandler
  ├── 普通 ImportSelector
  │   ├── 立即执行 selectImports()
  │   └── 对返回结果递归调用 processImports()
  ├── ImportBeanDefinitionRegistrar
  │   └── 暂存到 ConfigurationClass.importBeanDefinitionRegistrars
  └── 普通类
      ├── 记录 importedBy 关系
      └── 作为 ConfigurationClass 继续递归解析
```

#### 16.3.1 ImportSelector 分支

Parser 通过 `ParserStrategyUtils.instantiateClass()` 创建 selector：

```text
实例化 ImportSelector
  ├── 使用 ResourceLoader 的 ClassLoader
  ├── 回调 EnvironmentAware
  ├── 回调 BeanClassLoaderAware
  ├── 回调 ResourceLoaderAware
  └── 执行 selectImports(importingClassMetadata)
```

普通 `ImportSelector` 会立即执行：

```java
String[] importClassNames = selector.selectImports(metadata);
```

返回值会重新包装为 `SourceClass`，然后再次进入 `processImports()`。因此 selector
返回的类仍然可能是另一个 selector、registrar 或普通配置类。

#### 16.3.2 DeferredImportSelector 分支

如果 selector 实现了 `DeferredImportSelector`，当前阶段不会直接调用
`selectImports()`，而是交给：

```java
deferredImportSelectorHandler.handle(configClass, deferredImportSelector);
```

它会在普通配置类解析基本完成后统一排序和分组。详细过程见下一专题。

#### 16.3.3 ImportBeanDefinitionRegistrar 分支

Registrar 不会在 `processImports()` 中立即注册 BeanDefinition，而是先保存到当前
`ConfigurationClass`：

```java
configClass.addImportBeanDefinitionRegistrar(registrar, importingClassMetadata);
```

真正调用发生在 Reader 阶段：

```text
ConfigurationClassBeanDefinitionReader
  └── loadBeanDefinitionsForConfigurationClass()
      └── loadBeanDefinitionsFromRegistrars()
          └── registrar.registerBeanDefinitions(metadata, registry)
```

必须区分：

```text
Parser 阶段：识别并保存 registrar
Reader 阶段：让 registrar 修改 BeanDefinitionRegistry
```

#### 16.3.4 普通导入类分支

普通类会被转换成 `ConfigurationClass`，然后重新执行：

```java
processConfigurationClass(asConfigClass(importCandidate), exclusionFilter);
```

即使导入类没有标注 `@Configuration`，它也会成为配置类候选，可以继续声明
`@Bean`、`@Import` 等内容。

### 16.4 ImportStack 如何检测循环导入

Parser 内部维护 `ImportStack`：

```text
ImportStack
  ├── 继承 ArrayDeque<ConfigurationClass>
  ├── 保存当前 @Import 调用栈
  ├── 实现 ImportRegistry
  └── 保存 importedClass → importingClassMetadata 映射
```

循环示例：

```java
@Configuration
@Import(ConfigB.class)
class ConfigA {
}

@Configuration
@Import(ConfigA.class)
class ConfigB {
}
```

```text
push ConfigA
  └── 解析 ConfigB
      └── 准备再次解析 ConfigA
          └── importStack 已经包含 ConfigA
              └── 抛出 CircularImportProblem
```

`ImportStack` 不只是循环检测栈。配置类增强阶段注册的
`ImportAwareBeanPostProcessor` 也会使用 ImportRegistry，让实现了
`ImportAware` 的 Bean 得到“谁导入了我”的注解元数据。

### 16.5 exclusionFilter 的作用

`processImports()` 会携带 `Predicate<String> exclusionFilter`：

```text
候选类名
  ├── 命中过滤器 → 不转换 SourceClass，不继续解析
  └── 未命中过滤器 → 进入 selector / registrar / 普通类分支
```

多个 selector 的过滤器可以通过 `or()` 合并。Spring Boot 会利用这个能力减少不必要的
自动配置类元数据读取和类加载。

### 16.6 Parser 与 Reader 的职责边界

| 导入类型 | Parser 阶段 | Reader 阶段 |
|----------|-------------|-------------|
| 普通类 | 递归解析成 ConfigurationClass | 必要时注册导入类自身的 BeanDefinition |
| ImportSelector | 执行并递归解析返回类名 | 注册最终 ConfigurationClass 中的定义 |
| DeferredImportSelector | 延迟、排序、分组后递归解析 | 注册最终配置模型 |
| ImportBeanDefinitionRegistrar | 只实例化并保存 | 调用 registerBeanDefinitions() |

所以，`@Import` 的核心不是“立即注册一个 Bean”，而是扩展配置模型，再由 Reader
统一把模型落入 BeanDefinitionRegistry。

### 16.7 推荐断点

```text
ConfigurationClassParser.getImports()
ConfigurationClassParser.collectImports()
ConfigurationClassParser.processImports()
ConfigurationClassParser$ImportStack.registerImport()
ConfigurationClassParser.processConfigurationClass()
ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsFromRegistrars()
```

重点观察：

```text
configClass
currentSourceClass
importCandidates
importStack
exclusionFilter
configClass.importBeanDefinitionRegistrars
```

---

## 十七、专题二：DeferredImportSelector 的排序与分组

普通 `ImportSelector` 在被发现时立即执行，而 `DeferredImportSelector` 要等当前一轮
普通配置类解析基本完成后再执行。

它解决的不只是“晚一点导入”，还提供了：

- selector 排序
- selector 分组
- 组内候选聚合
- 排除规则合并
- 对最终导入结果统一排序和去重

### 17.1 延迟处理发生在哪里

主流程可以简化为：

```text
ConfigurationClassParser.parse(configCandidates)
  ├── 逐个 parse(candidate)
  │   └── 遇到 DeferredImportSelector 时调用 handle()
  └── deferredImportSelectorHandler.process()
      ├── 排序 selector
      ├── 注册到 groupingHandler
      └── processGroupImports()
```

延迟导入仍然发生在 Parser 阶段，并不是等到 Bean 实例化阶段才执行。

### 17.2 DeferredImportSelectorHandler 的状态设计

Handler 内部维护：

```java
List<DeferredImportSelectorHolder> deferredImportSelectors;
```

一个 Holder 保存两项信息：

```text
DeferredImportSelectorHolder
  ├── configurationClass：是谁声明了这个 selector
  └── importSelector：具体的 DeferredImportSelector 实例
```

`handle()` 有两个状态：

```text
deferredImportSelectors != null
  └── 正常收集，加入列表

deferredImportSelectors == null
  └── 当前正在处理延迟导入
      └── 新发现的 selector 立即建立临时分组并处理
```

这个设计用于处理“延迟导入结果中又发现新的延迟 selector”的嵌套场景，避免新 selector
丢失或必须重新启动整个 Parser。

### 17.3 为什么先排序再分组

`process()` 会先复制待处理列表并暂时把字段置为 `null`，然后：

```java
deferredImports.sort(DEFERRED_IMPORT_COMPARATOR);
deferredImports.forEach(groupingHandler::register);
groupingHandler.processGroupImports();
```

排序会考虑 selector 的 `Ordered`、`@Order` 等信息。先排序可以让扩展点明确控制处理先后，
而不是依赖配置类扫描出来的偶然顺序。

处理完成后，Handler 会重新创建收集列表，以便继续接收后续解析轮次发现的 selector。

### 17.4 DeferredImportSelectorGroupingHandler

GroupingHandler 维护两个关键 Map：

```text
groupings
  key   = selector.getImportGroup() 返回的 Group 类型
          如果没有 Group，则使用 selector holder 自身
  value = DeferredImportSelectorGrouping

configurationClasses
  key   = importingClass 的 AnnotationMetadata
  value = 对应 ConfigurationClass
```

注册过程：

```text
DeferredImportSelectorHolder
  ├── 获取 selector.getImportGroup()
  ├── 创建或复用 Group
  ├── 加入 DeferredImportSelectorGrouping
  └── 建立 metadata → ConfigurationClass 映射
```

如果 selector 没有声明 Group，每个 selector 会形成自己的默认分组，不会和其他 selector
随意聚合。

### 17.5 Group 的两阶段协议

`DeferredImportSelector.Group` 有两个关键方法：

```java
void process(AnnotationMetadata metadata, DeferredImportSelector selector);

Iterable<Entry> selectImports();
```

Grouping 会先把组中每个 selector 交给 `process()`：

```text
selector1 → group.process(metadata1, selector1)
selector2 → group.process(metadata2, selector2)
selector3 → group.process(metadata3, selector3)
```

最后统一调用：

```java
group.selectImports();
```

返回的每个 `Group.Entry` 包含：

```text
AnnotationMetadata：这个导入结果属于哪个 importing class
importClassName：最终要导入的配置类名称
```

GroupingHandler 再把每个 Entry 重新交给 `processImports()`，因此最终候选仍然遵守普通
`@Import` 的递归解析规则。

### 17.6 排除过滤器如何合并

同一个 Group 中可能存在多个 selector，每个 selector 都可以返回自己的
`getExclusionFilter()`。

Spring 会把这些过滤器合并：

```java
mergedFilter = defaultFilter
    .or(selector1Filter)
    .or(selector2Filter);
```

后续将候选类名转换为 `SourceClass` 和递归解析时都会携带这个过滤器。

### 17.7 Spring Boot 3.3.9 自动配置链路

Spring Boot 的关键链路是：

```text
@SpringBootApplication
  └── @EnableAutoConfiguration
      └── @Import(AutoConfigurationImportSelector.class)
          └── DeferredImportSelector
              └── AutoConfigurationGroup
```

`AutoConfigurationImportSelector` 的主要步骤：

```text
getAutoConfigurationEntry(annotationMetadata)
  ├── 判断自动配置是否启用
  ├── 读取 @EnableAutoConfiguration 属性
  ├── getCandidateConfigurations()
  ├── removeDuplicates()
  ├── getExclusions()
  ├── checkExcludedClasses()
  ├── configurations.removeAll(exclusions)
  ├── getConfigurationClassFilter().filter(configurations)
  └── fireAutoConfigurationImportEvents()
```

Spring Boot 3.3.9 的候选类主要通过：

```java
ImportCandidates.load(AutoConfiguration.class, classLoader)
```

读取：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

候选列表进入 `AutoConfigurationGroup` 后，Group 会：

```text
1. 保存每个 importing class 对应的 AutoConfigurationEntry
2. 聚合所有 exclusions
3. 移除被排除的自动配置
4. 使用 AutoConfigurationSorter 排序
5. 返回 Group.Entry 列表
```

排序会综合处理 `@AutoConfigureBefore`、`@AutoConfigureAfter`、
`@AutoConfigureOrder` 等关系。

### 17.8 “用户配置优先，自动配置兜底”的精确含义

不能简单理解为“所有用户 Bean 都已经实例化，然后才解析自动配置”。此时还没有进入普通
单例 Bean 创建阶段。

更准确的描述是：

```text
先解析普通配置模型
  ↓
再处理 DeferredImportSelector，补充自动配置模型
  ↓
Reader 按配置模型注册 BeanDefinition
  ↓
@ConditionalOnMissingBean 等注册阶段条件检查当前 BeanDefinitionRegistry
```

自动配置能够“后退”，依赖以下机制共同作用：

- 延迟导入时机
- 自动配置排序
- Reader 的 BeanDefinition 注册顺序
- `REGISTER_BEAN` 阶段条件判断
- `OnBeanCondition` 对现有 BeanDefinition 的搜索

### 17.9 推荐断点

```text
ConfigurationClassParser$DeferredImportSelectorHandler.handle()
ConfigurationClassParser$DeferredImportSelectorHandler.process()
ConfigurationClassParser$DeferredImportSelectorGroupingHandler.register()
ConfigurationClassParser$DeferredImportSelectorGroupingHandler.processGroupImports()
ConfigurationClassParser$DeferredImportSelectorGrouping.getImports()
AutoConfigurationImportSelector.getAutoConfigurationEntry()
AutoConfigurationImportSelector.getCandidateConfigurations()
AutoConfigurationImportSelector$AutoConfigurationGroup.process()
AutoConfigurationImportSelector$AutoConfigurationGroup.selectImports()
```

重点观察：

```text
deferredImportSelectors
groupings
configurationClasses
exclusionFilter
AutoConfigurationEntry.configurations
AutoConfigurationEntry.exclusions
```

---

## 十八、专题三：ConfigurationClassParser 的内部状态模型

要真正看懂 Parser，不能只跟方法调用，还要理解它在解析过程中维护的状态。

### 18.1 核心状态总览

可以把 Parser 看成一个“配置类语法树构建器”：

| 状态 | 作用 |
|------|------|
| `configurationClasses` | 保存已经构建或正在合并的 ConfigurationClass 模型 |
| `knownSuperclasses` | 记录某个父类已经归属于哪个配置类，避免重复解析 |
| `importStack` | 保存 @Import 调用栈、循环检测和导入来源关系 |
| `deferredImportSelectorHandler` | 收集和处理 DeferredImportSelector |
| `componentScanParser` | 把 @ComponentScan 属性转换为实际扫描操作 |
| `conditionEvaluator` | 在解析阶段和注册阶段判断是否跳过 |
| `propertySourceRegistry` | 处理并记录 @PropertySource 描述信息 |
| `metadataReaderFactory` | 通过 ASM 读取未加载类的注解元数据 |

这些状态共同保证 Parser 能够处理：

- 配置类递归
- 组合注解
- 重复导入
- 父类继承
- 循环导入
- 延迟自动配置
- 条件跳过

### 18.2 SourceClass 解决了什么问题

`doProcessConfigurationClass()` 的参数不是直接使用 `Class<?>`，而是使用
`SourceClass`。

可以将其理解成下面的抽象：

```text
SourceClass
  ├── source
  │   ├── 已经加载的 Class
  │   └── MetadataReader
  └── AnnotationMetadata
```

这样设计的原因是：Spring 在解析大量候选类时，希望尽量通过 ASM 读取 `.class` 文件，
而不是立即触发 JVM 类加载。

```text
只需要注解、方法、接口、父类名称
  └── 优先使用 MetadataReader

策略类需要真正实例化
  └── 再加载 ImportSelector、Registrar、Condition 等类型
```

减少过早类加载可以避免：

- 静态初始化代码提前执行
- 可选依赖不在 classpath 时直接失败
- 大量无用 Class 进入 JVM
- 自动配置条件还未判断就加载目标类

### 18.3 processConfigurationClass() 的入口状态机

核心逻辑可以分成三步：

```text
1. ConditionEvaluator 判断 PARSE_CONFIGURATION
2. 检查 configurationClasses 中是否已有同名配置模型
3. 沿当前类和父类调用 doProcessConfigurationClass()
```

第一步：

```java
if (conditionEvaluator.shouldSkip(
        configClass.getMetadata(), PARSE_CONFIGURATION)) {
    return;
}
```

配置类在这里被跳过后，它的 `@ComponentScan`、`@Import`、`@Bean` 都不会继续解析。

### 18.4 同一个配置类为什么可能被发现多次

同一配置类可能通过不同路径进入 Parser：

```text
AppConfig 直接注册 UserConfig
@ComponentScan 又扫描到 UserConfig
@Import 又导入 UserConfig
另一个配置类再次 @Import UserConfig
```

Parser 会检查 `configurationClasses` 中是否已经存在对应模型。

处理规则可以概括为：

| 已存在模型 | 新模型 | 结果 |
|------------|--------|------|
| imported | imported | 合并 importedBy 来源，避免重复解析 |
| 普通直接注册 | imported | 保留直接注册模型，忽略较弱的导入模型 |
| imported | 普通直接注册 | 移除旧导入模型，使用显式注册模型重新解析 |

“显式注册优先于被导入”是因为直接注册的 BeanDefinition 可能携带更完整的 beanName、
Scope、来源和用户定制属性。

### 18.5 ConfigurationClass 是解析结果，不是 BeanDefinition

`ConfigurationClass` 是中间模型，主要包含：

```text
配置类 AnnotationMetadata
配置类 Resource
beanName
importedBy 集合
BeanMethod 集合
ImportedResource 集合
ImportBeanDefinitionRegistrar 集合
```

解析过程是在不断给该模型增加信息：

```text
发现 @Bean
  └── addBeanMethod()

发现 @ImportResource
  └── addImportedResource()

发现 Registrar
  └── addImportBeanDefinitionRegistrar()

发现重复导入来源
  └── mergeImportedBy()
```

直到 Reader 阶段，这些模型才会转换成真正的 BeanDefinition。

### 18.6 knownSuperclasses 为什么单独缓存

`doProcessConfigurationClass()` 最后会返回父类 `SourceClass`，外层使用循环继续处理：

```java
do {
    sourceClass = doProcessConfigurationClass(configClass, sourceClass, filter);
}
while (sourceClass != null);
```

例如：

```java
class BaseConfig {
    @Bean
    public BaseService baseService() {
        return new BaseService();
    }
}

@Configuration
class OrderConfig extends BaseConfig {
}
```

Parser 需要把父类中的 `@Bean` 方法加入 `OrderConfig` 的模型。但是多个配置类可能继承
同一个父类，所以使用 `knownSuperclasses` 防止父类元数据被反复解析。

通常以下父类不会继续处理：

- `java.*` 开头的系统类
- 已经被 exclusionFilter 排除的类
- 已经存在于 knownSuperclasses 的类

### 18.7 @ComponentScan 为什么会影响 Parser 状态

`ClassPathBeanDefinitionScanner` 会在解析期间直接注册扫描结果：

```text
doProcessConfigurationClass()
  └── componentScanParser.parse()
      └── scanner.doScan()
          └── registry.registerBeanDefinition()
```

扫描完成后，Parser 会检查这些新 BeanDefinition 是否也是配置类候选。如果是，就在当前
Parser 轮次继续调用 `parse()`。

这和 Reader 注册 Registrar 结果后触发 CCPP 外层 `do...while` 不同：

```text
扫描得到配置类候选
  └── Parser 当前轮次递归

Reader / Registrar 新增配置类 BeanDefinition
  └── CCPP 外层下一轮解析
```

### 18.8 Parser 生命周期总图

```text
创建 ConfigurationClassParser
  ↓
parse(configCandidates)
  ↓
processConfigurationClass()
  ├── 条件判断
  ├── 重复模型合并
  └── SourceClass 循环
      └── doProcessConfigurationClass()
          ├── 成员配置类
          ├── @PropertySource
          ├── @ComponentScan
          ├── @Import
          ├── @ImportResource
          ├── @Bean
          ├── 接口默认 @Bean 方法
          └── 父类
  ↓
process DeferredImportSelector
  ↓
validate()
  ↓
getConfigurationClasses()
```

### 18.9 推荐断点与观察变量

```text
ConfigurationClassParser.processConfigurationClass()
ConfigurationClassParser.doProcessConfigurationClass()
ConfigurationClassParser.asSourceClass()
ConfigurationClassParser$SourceClass.getAnnotations()
ConfigurationClassParser$SourceClass.getSuperClass()
ConfigurationClassParser.validate()
```

重点观察：

```text
configurationClasses
knownSuperclasses
configClass.importedBy
configClass.beanMethods
sourceClass.source
sourceClass.metadata
```

---

## 十九、专题四：ConfigurationClassBeanDefinitionReader 的落地细节

Parser 得到的是 `ConfigurationClass` 中间模型，Reader 才负责把模型转成容器真正能够
使用的 BeanDefinition。

### 19.1 Reader 的总体流程

```text
loadBeanDefinitions(configurationModel)
  └── loadBeanDefinitionsForConfigurationClass(configClass)
      ├── TrackedConditionEvaluator.shouldSkip(configClass)
      ├── registerBeanDefinitionForImportedConfigurationClass()
      ├── 遍历 BeanMethod
      │   └── loadBeanDefinitionsForBeanMethod()
      ├── loadBeanDefinitionsFromImportedResources()
      └── loadBeanDefinitionsFromRegistrars()
```

这说明 Reader 不只处理 `@Bean`，还负责：

- 注册被 `@Import` 导入的配置类自身
- 处理 `@ImportResource`
- 调用 `ImportBeanDefinitionRegistrar`
- 在注册阶段再次执行条件判断

### 19.2 导入配置类自身如何注册

通过 `@Import(SomeConfig.class)` 导入的配置类，可能还没有自己的 BeanDefinition。
Reader 会为它创建：

```java
AnnotatedGenericBeanDefinition configBeanDef =
        new AnnotatedGenericBeanDefinition(metadata);
```

然后依次处理：

```text
ScopeMetadataResolver
  ↓
设置 scope
  ↓
ImportBeanNameGenerator 生成 beanName
  ↓
processCommonDefinitionAnnotations()
  ↓
必要时创建 ScopedProxy
  ↓
注册到 BeanDefinitionRegistry
  ↓
把 beanName 回写 ConfigurationClass
```

beanName 回写非常关键，因为非静态 `@Bean` 方法的 BeanDefinition 需要通过该名称找到
配置类实例。

### 19.3 @Bean 方法的名称与别名

例如：

```java
@Bean(name = {userService, userServiceAlias})
public UserService createUserService() {
    return new UserService();
}
```

Reader 的规则是：

```text
name 数组第一个值 → BeanDefinition 的 beanName
其余值           → registry 中的 alias
未显式指定 name  → 方法名 createUserService
```

这里要区分三个名称：

```text
Java 方法名：createUserService
Bean 名称：userService
Bean 别名：userServiceAlias
```

### 19.4 ConfigurationClassBeanDefinition 保存了什么

Reader 为 `@Bean` 方法创建的并不是普通 `RootBeanDefinition`，而是其内部子类：

```text
ConfigurationClassBeanDefinition
  ├── RootBeanDefinition
  ├── AnnotatedBeanDefinition
  ├── configuration class 的 AnnotationMetadata
  ├── @Bean 方法的 MethodMetadata
  └── derivedBeanName
```

这个标记子类的用途包括：

- 保留注解元数据
- 保留工厂方法元数据
- 判断 BeanDefinition 是否来自配置类
- 在重名覆盖判断中区分外部定义和配置类定义
- 支持后续工厂方法解析和诊断

构造时还会调用：

```java
setLenientConstructorResolution(false);
```

表示工厂方法重载解析使用严格模式，避免多个近似匹配方法被宽松选择。

### 19.5 静态 @Bean 与实例 @Bean 的 BeanDefinition 差异

#### 19.5.1 静态 @Bean

```java
@Bean
public static UserService userService() {
    return new UserService();
}
```

BeanDefinition 保存：

```text
beanClass / beanClassName = 配置类
uniqueFactoryMethodName   = userService
factoryBeanName           = null
```

容器可以直接通过配置类的静态方法创建对象，不需要先创建配置类实例。

#### 19.5.2 实例 @Bean

```java
@Bean
public UserService userService(UserDao userDao) {
    return new UserService(userDao);
}
```

BeanDefinition 保存：

```text
factoryBeanName         = 配置类 Bean 名称
uniqueFactoryMethodName = userService
```

对应 Spring 6.1.17 源码中的关键设置：

```java
beanDef.setFactoryBeanName(configClass.getBeanName());
beanDef.setUniqueFactoryMethodName(methodName);
beanDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
```

后续创建 `userService` 时，BeanFactory 会：

```text
先 getBean(factoryBeanName)
  ↓
得到配置类实例
  ↓
解析 userService(UserDao) 工厂方法
  ↓
解析方法参数 UserDao
  ↓
反射调用工厂方法
```

如果元数据来自已加载类，Reader 还可以提前写入 `resolvedFactoryMethod`，减少后续方法匹配。

### 19.6 为什么设置 AUTOWIRE_CONSTRUCTOR

Reader 会设置：

```java
beanDef.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
```

这里不是说 `@Bean` 返回类型必须使用构造器注入，而是告诉 BeanFactory：创建该定义时，
工厂方法参数应按照构造器参数解析的依赖注入算法处理。

```java
@Bean
public OrderService orderService(
        OrderRepository repository,
        ObjectProvider<DiscountService> discountService) {
    return new OrderService(repository, discountService);
}
```

`repository` 和 `discountService` 都会进入 BeanFactory 的依赖解析流程，而不是由 Java
编译器或配置类自己提供。

### 19.7 通用注解、生命周期与候选资格

Reader 会处理 `@Bean` 方法和元注解上的通用属性：

```text
@Lazy
@Primary
@DependsOn
@Role
@Description
```

同时读取 `@Bean` 自身属性：

```java
@Bean(
    name = client,
    autowireCandidate = false,
    initMethod = start,
    destroyMethod = close
)
```

对应写入：

```text
autowireCandidate
initMethodName
destroyMethodName
```

这些属性此时只是保存到 BeanDefinition，真正调用初始化和销毁方法发生在 Bean 生命周期阶段。

### 19.8 Scope 与 ScopedProxy

Reader 会读取 `@Scope`：

```java
@Bean
@Scope(value = request, proxyMode = ScopedProxyMode.TARGET_CLASS)
public RequestContext requestContext() {
    return new RequestContext();
}
```

处理流程：

```text
读取 scopeName
  ↓
设置到原始 ConfigurationClassBeanDefinition
  ↓
proxyMode != NO
  ↓
ScopedProxyCreator.createScopedProxy()
  ↓
注册代理 BeanDefinition
  ↓
原始目标通常使用 scopedTarget. 前缀保存
```

因此最终注册到原 beanName 下的可能是代理定义，而不是最初创建的目标定义。

### 19.9 BeanDefinition 覆盖判断

Reader 注册前会调用：

```java
isOverriddenByExistingDefinition(beanMethod, beanName)
```

它需要处理：

- 多个配置类声明同名 `@Bean`
- 一个配置类中存在重载 `@Bean` 方法
- XML 或手工 BeanDefinition 与 `@Bean` 重名
- BeanDefinitionRegistry 是否允许覆盖
- `@Bean` 名称是否与配置类自身 beanName 冲突

如果 registry 不允许覆盖，Reader 会抛出 `BeanDefinitionStoreException`，而不是静默替换。

不要把“BeanDefinition 覆盖”与“`@Primary` 候选选择”混为一谈：

```text
覆盖：多个定义争夺同一个 beanName
@Primary：多个不同 beanName 的候选争夺同一个依赖点
```

### 19.10 TrackedConditionEvaluator 为什么需要跟踪导入关系

配置类可能没有直接条件，但它是被另一个已经条件失败的配置类导入的：

```text
ParentAutoConfiguration 条件失败
  └── @Import(ChildConfiguration.class)
      └── ChildConfiguration 也应跳过
```

`TrackedConditionEvaluator` 会递归检查 `importedBy`：

```text
配置类有导入来源
  ├── 所有导入来源都被跳过 → 当前配置类跳过
  └── 至少一个来源未跳过   → 再判断当前类 REGISTER_BEAN 条件
```

如果配置类被跳过，Reader 还会移除已经存在的配置类 BeanDefinition，并清理
ImportRegistry 中的导入关系。

### 19.11 推荐断点与 BeanDefinition 检查项

```text
ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsForConfigurationClass()
ConfigurationClassBeanDefinitionReader.registerBeanDefinitionForImportedConfigurationClass()
ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsForBeanMethod()
ConfigurationClassBeanDefinitionReader.isOverriddenByExistingDefinition()
ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsFromRegistrars()
ConfigurationClassBeanDefinitionReader$TrackedConditionEvaluator.shouldSkip()
```

检查一个 `@Bean` 定义时，至少观察：

```text
beanName
beanClassName
factoryBeanName
factoryMethodName
resolvedFactoryMethod
scope
lazyInit
primary
autowireCandidate
initMethodNames
destroyMethodNames
resourceDescription
source
```

---

## 二十、专题五：ConfigurationClassEnhancer 的两个拦截器

Full 模式配置类增强并不只是“生成一个 CGLIB 子类”。真正重要的是增强类如何得到
BeanFactory，以及 `@Bean` 方法调用如何在“执行原方法”和“从容器取 Bean”之间切换。

### 20.1 增强发生在什么时候

增强位于 `ConfigurationClassPostProcessor.postProcessBeanFactory()`：

```text
postProcessBeanDefinitionRegistry()
  └── 解析配置类并注册 BeanDefinition

postProcessBeanFactory()
  ├── enhanceConfigurationClasses(beanFactory)
  └── addBeanPostProcessor(new ImportAwareBeanPostProcessor(...))
```

`enhanceConfigurationClasses()` 会遍历 BeanDefinition，找到标记为 Full Configuration
的定义，然后：

```text
原始配置类 Class
  ↓
ConfigurationClassEnhancer.enhance()
  ↓
生成 CGLIB 子类
  ↓
BeanDefinition.setBeanClass(enhancedClass)
```

因此后续 BeanFactory 创建的配置类实例，真实类型已经是增强子类。

Spring 还会标记需要保留目标类信息，避免后续类型推断只看到代理类而丢失原始配置类型。

### 20.2 增强类的大致结构

可以把生成类理解成：

```java
class AppConfig$$SpringCGLIB extends AppConfig
        implements EnhancedConfiguration {

    private BeanFactory $$beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.$$beanFactory = beanFactory;
        super.setBeanFactory(beanFactory); // 原类实现 BeanFactoryAware 时
    }

    @Override
    public UserService userService() {
        // 进入 BeanMethodInterceptor
    }
}
```

实际生成逻辑主要由三个 Callback 组成：

```text
BeanMethodInterceptor
BeanFactoryAwareMethodInterceptor
NoOp.INSTANCE
```

前两个负责特殊逻辑，其他普通方法走 NoOp。

### 20.3 BeanFactoryAwareMethodInterceptor

增强类必须持有当前 BeanFactory，否则拦截 `@Bean` 方法时无法调用 `getBean()`。

`BeanFactoryAwareMethodInterceptor` 只匹配 `setBeanFactory(BeanFactory)`：

```text
配置类实例创建
  ↓
Aware 回调阶段调用 setBeanFactory()
  ↓
拦截器把 BeanFactory 写入 $$beanFactory 字段
  ↓
如果原配置类实现了 BeanFactoryAware
  └── 再调用父类 setBeanFactory()
```

因此用户自己实现 `BeanFactoryAware` 不会破坏框架注入，框架字段和用户回调都会执行。

### 20.4 BeanMethodInterceptor 的匹配条件

它主要拦截：

```text
不是 Object 的方法
不是 setBeanFactory()
方法被 @Bean 标注
```

进入拦截器后首先确定 Bean 名称：

```java
String beanName = BeanAnnotationHelper.determineBeanNameFor(beanMethod);
```

如果 `@Bean` 同时配置了 ScopedProxy，拦截器还需要区分代理 beanName 和真正的
`scopedTarget.` 目标 beanName。

### 20.5 最核心判断：是谁调用了 @Bean 方法

同一个 `@Bean` 方法有两种完全不同的调用来源。

#### 场景一：容器正在创建这个 Bean

```text
BeanFactory 创建 userService
  ↓
通过 factoryBeanName 得到配置类增强实例
  ↓
准备反射调用 userService()
  ↓
SimpleInstantiationStrategy 记录 currentlyInvokedFactoryMethod
  ↓
进入 BeanMethodInterceptor
  ↓
isCurrentlyInvokedFactoryMethod() == true
  ↓
invokeSuper()，执行用户写的 @Bean 方法体
```

此时必须执行原方法，否则 Bean 永远没有第一次创建的机会。

#### 场景二：用户代码或另一个 @Bean 方法直接调用

```java
@Bean
public UserService userService() {
    return new UserService(userDao());
}
```

当 `userService()` 方法体调用 `userDao()` 时：

```text
配置类增强实例.userDao()
  ↓
进入 BeanMethodInterceptor
  ↓
当前容器工厂方法不是 userDao()
  ↓
resolveBeanReference()
  ↓
beanFactory.getBean(userDao)
```

所以不会简单地再次执行：

```java
return new UserDao();
```

而是返回容器管理的对象。

### 20.6 isCurrentlyInvokedFactoryMethod() 如何判断

容器调用工厂方法前，会通过 `SimpleInstantiationStrategy` 的线程上下文记录当前方法。

拦截器读取：

```java
Method currentlyInvoked =
        SimpleInstantiationStrategy.getCurrentlyInvokedFactoryMethod();
```

然后比较：

```text
方法名
参数类型数组
```

为什么不直接比较 `Method.equals()`？因为某些语言或协变返回值场景可能产生桥接方法，
只比较方法名和参数类型更稳定。

### 20.7 resolveBeanReference() 做了哪些事

当判断为普通调用时，拦截器会从 BeanFactory 获取 Bean：

```text
检查 Bean 是否已在创建中
  ↓
必要时暂时调整 currentlyInCreation 标记
  ↓
beanFactory.getBean(beanName)
  ↓
检查返回类型是否兼容 @Bean 方法声明类型
  ↓
记录 Bean 之间的 dependentBean 关系
  ↓
恢复 currentlyInCreation 标记
```

依赖关系注册示例：

```text
当前正在创建 userService
userService() 内部调用 userDao()
  ↓
registerDependentBean(userDao, userService)
```

这样销毁阶段就能保证依赖 Bean 与被依赖 Bean 的顺序正确。

### 20.8 factoryContainsBean() 为什么还检查 currentlyInCreation

判断逻辑可以概括为：

```java
beanFactory.containsBean(beanName)
    && !beanFactory.isCurrentlyInCreation(beanName)
```

如果 Bean 正在首次创建，虽然 BeanFactory 已经知道这个名称，但不能把它当成完整可复用的
Bean。此时需要执行真实工厂方法完成第一次实例化。

### 20.9 FactoryBean 的特殊增强

如果 `@Bean` 返回的是 `FactoryBean`，并且另一个 `@Bean` 方法直接调用它的
`getObject()`，普通 Java 语义可能绕过 BeanFactory 对 FactoryBean 产品对象的缓存和 Scope
管理。

因此 `BeanMethodInterceptor` 会在满足条件时增强 FactoryBean：

```text
配置类方法返回 FactoryBean
  ↓
检测 &beanName 和 beanName 都存在
  ↓
为 FactoryBean 创建接口代理或 CGLIB 代理
  ↓
拦截 getObject()
  ↓
转为 beanFactory.getBean(beanName)
```

如果 FactoryBean 类或 `getObject()` 方法是 final，无法生成相应子类代理，Spring 会退回
原对象并记录调试信息。

### 20.10 Full、Lite 与静态方法的边界

| 场景 | 是否增强配置类 | @Bean 间直接调用语义 |
|------|----------------|----------------------|
| `@Configuration(proxyBeanMethods = true)` | 是 | 转为 BeanFactory.getBean() |
| `@Configuration(proxyBeanMethods = false)` | 否 | 普通 Java 调用 |
| `@Component` 中声明 `@Bean` | 否 | 普通 Java 调用 |
| static `@Bean` 方法 | 不依赖实例拦截 | 普通静态方法调用语义 |

Full 模式的实例 `@Bean` 方法必须能够被子类覆盖。private、final 等不可覆盖方法不能提供
完整的跨 `@Bean` 方法拦截语义，并可能在配置类校验阶段报错。

### 20.11 推荐断点

```text
ConfigurationClassPostProcessor.enhanceConfigurationClasses()
ConfigurationClassEnhancer.enhance()
ConfigurationClassEnhancer.newEnhancer()
ConfigurationClassEnhancer$BeanFactoryAwareMethodInterceptor.intercept()
ConfigurationClassEnhancer$BeanMethodInterceptor.intercept()
ConfigurationClassEnhancer$BeanMethodInterceptor.resolveBeanReference()
ConfigurationClassEnhancer$BeanMethodInterceptor.factoryContainsBean()
ConfigurationClassEnhancer$BeanMethodInterceptor.isCurrentlyInvokedFactoryMethod()
ConfigurationClassEnhancer$BeanMethodInterceptor.enhanceFactoryBean()
```

重点观察：

```text
enhancedConfigInstance.getClass()
$$beanFactory
beanMethod
beanName
currentlyInvokedFactoryMethod
alreadyInCreation
beanInstance
```

---

## 二十一、专题六：条件装配与 Spring Boot 条件评估

Spring Framework 提供通用的 `@Conditional` 执行框架，Spring Boot 在其上实现
`@ConditionalOnClass`、`@ConditionalOnBean`、`@ConditionalOnMissingBean` 等条件。

学习时必须把两层区分开：

```text
Spring Framework
  └── ConditionEvaluator + Condition + ConfigurationCondition

Spring Boot
  └── SpringBootCondition + OnBeanCondition + ConditionEvaluationReport
```

### 21.1 ConditionEvaluator.shouldSkip() 的完整步骤

```text
metadata 是否存在 @Conditional
  ├── 否 → return false，不跳过
  └── 是
      ├── 推断或接收 ConfigurationPhase
      ├── 收集并实例化全部 Condition
      ├── AnnotationAwareOrderComparator.sort()
      ├── 判断 Condition 要求的阶段
      └── 任意有效 Condition.matches() == false
          └── return true，跳过
```

伪代码：

```java
boolean shouldSkip(metadata, phase) {
    if (!metadata.hasConditional()) {
        return false;
    }
    if (phase == null) {
        phase = isConfigurationCandidate(metadata)
                ? PARSE_CONFIGURATION
                : REGISTER_BEAN;
    }
    List<Condition> conditions = instantiateConditions(metadata);
    sort(conditions);
    for (Condition condition : conditions) {
        ConfigurationPhase required = getRequiredPhase(condition);
        if ((required == null || required == phase)
                && !condition.matches(context, metadata)) {
            return true;
        }
    }
    return false;
}
```

注意：`shouldSkip()` 返回 `true` 表示跳过，和 `Condition.matches()` 的语义相反。

### 21.2 ConditionContext 中有什么

```text
BeanDefinitionRegistry
ConfigurableListableBeanFactory
Environment
ResourceLoader
ClassLoader
```

不同条件使用的信息不同：

```text
@Profile / 属性条件 → Environment
类路径条件          → ClassLoader
Bean 存在性条件     → BeanFactory / Registry
资源条件            → ResourceLoader
```

条件类不是普通业务 Bean，它由 ConditionEvaluator 按策略类方式实例化，不应依赖普通
`@Autowired` 字段注入。

### 21.3 两个阶段的真实调用位置

#### PARSE_CONFIGURATION

```text
ConfigurationClassParser.processConfigurationClass()
  └── conditionEvaluator.shouldSkip(
          configClass.metadata,
          PARSE_CONFIGURATION)
```

如果失败，整个配置类不再解析：

```text
不处理 @ComponentScan
不处理 @Import
不收集 @Bean
不处理父类配置
```

适合尽早执行的条件包括 classpath、属性、应用类型和 Profile 判断。

#### REGISTER_BEAN

```text
ConfigurationClassBeanDefinitionReader
  ├── TrackedConditionEvaluator.shouldSkip(configClass)
  └── conditionEvaluator.shouldSkip(beanMethod.metadata, REGISTER_BEAN)
```

适合依赖当前 BeanDefinitionRegistry 状态的条件：

- 是否已经存在指定 Bean
- 是否缺少指定 Bean
- 是否存在唯一候选 Bean

### 21.4 ConfigurationCondition 如何声明阶段

```java
public interface ConfigurationCondition extends Condition {

    ConfigurationPhase getConfigurationPhase();
}
```

Bean 存在性条件通常返回：

```java
ConfigurationPhase.REGISTER_BEAN
```

因为 Parser 早期 BeanDefinition 还没有注册完整，过早判断容易得到错误结果。普通
`Condition` 没有阶段限制，当前阶段执行到它时就可以参与判断。

### 21.5 Condition 执行顺序

同一个元素上可以组合多个条件：

```java
@Conditional({ClasspathCondition.class, PropertyCondition.class})
class FeatureConfig {
}
```

Spring 使用 `AnnotationAwareOrderComparator` 排序，因此 Condition 可以通过
`PriorityOrdered`、`Ordered` 或 `@Order` 控制顺序。

任意一个当前阶段有效的 Condition 不匹配，元素就被跳过。条件应尽量无副作用，不要依赖
其他 Condition 是否已经执行。

### 21.6 Spring Boot 条件层

Boot 条件通常继承 `SpringBootCondition`。它把通用 `matches()` 包装成：

```text
getMatchOutcome(context, metadata)
  ↓
得到 ConditionOutcome
  ├── isMatch
  └── ConditionMessage
  ↓
记录到 ConditionEvaluationReport
  ↓
按日志级别输出匹配原因
```

`ConditionOutcome` 不只返回 true/false，还解释原因：

```text
matched：classpath 中存在目标类
did not match：缺少属性 cache.enabled
did not match：已经存在 CacheManager Bean
```

这使自动配置失败不再是黑盒。

### 21.7 OnBeanCondition 的职责

Spring Boot 3.3.9 中，`OnBeanCondition` 同时服务于：

```text
@ConditionalOnBean
@ConditionalOnSingleCandidate
@ConditionalOnMissingBean
```

它实现 `ConfigurationCondition`，主要在 `REGISTER_BEAN` 阶段工作。

匹配规范可能来自注解属性：

```text
value：Class 类型
type：类名字符串
name：Bean 名称
annotation：Bean 上的注解
parameterizedContainer：泛型容器类型
search：父子容器搜索策略
ignored / ignoredType：忽略的 Bean
```

当 `@ConditionalOnMissingBean` 没有显式指定目标，并且标在 `@Bean` 方法上时，Boot 可以
使用方法返回类型作为默认目标类型。

### 21.8 Bean 搜索不是简单 containsBean()

OnBeanCondition 需要考虑：

- 已注册的 BeanDefinition 和可预测 Bean 类型
- FactoryBean 的产品类型
- 泛型容器中的目标类型
- 是否允许搜索父容器
- Bean 是否属于有效候选
- 被 ignored / ignoredType 排除的 Bean
- `@ConditionalOnSingleCandidate` 是否存在唯一候选或 Primary 候选

`SearchStrategy` 的语义：

| 策略 | 范围 |
|------|------|
| `CURRENT` | 只搜索当前 BeanFactory |
| `ANCESTORS` | 只搜索父容器 |
| `ALL` | 当前容器和父容器 |

因此在父子 ApplicationContext 场景中，同一个条件可能得到不同结果。

### 21.9 条件报告与调试

Boot 会把条件评估结果按 source 保存：

```text
ConditionEvaluationReport
  ├── outcomes：配置类或 @Bean 方法 → ConditionAndOutcomes
  ├── exclusions：被显式排除的自动配置类
  ├── unconditionalClasses：没有条件的候选自动配置
  └── parent：父容器条件报告
```

记录入口：

```java
report.recordConditionEvaluation(source, condition, outcome);
```

source 通常是配置类名，或者 `配置类名#Bean方法名`。开启 Boot debug 后看到的
positive matches、negative matches 和 exclusions，底层就来自这份报告。

调试代码：

```java
ConditionEvaluationReport report =
        ConditionEvaluationReport.get(beanFactory);

report.getConditionAndOutcomesBySource()
        .forEach((source, outcomes) ->
                System.out.println(source + " -> " + outcomes.isFullMatch()));
```

### 21.10 常见误区

#### 误区一：条件会随着运行时 Bean 变化自动重新计算

大多数自动配置条件是在容器刷新和 BeanDefinition 注册阶段评估的。容器启动完成后再动态
注册一个 Bean，不会自动重新执行全部自动配置。

#### 误区二：@ConditionalOnMissingBean 只检查已创建单例

它主要检查 BeanFactory 中可见的定义和可预测类型，不要求目标 Bean 已经完成实例化。

#### 误区三：条件顺序与 Java 注解书写顺序完全一致

条件会经过 Order 排序，组合注解和元注解也会参与合并，不能依赖源码书写顺序。

#### 误区四：返回接口类型不会影响条件判断

Bean 类型预测依赖 BeanDefinition 和工厂方法返回类型。返回类型过于宽泛可能使
OnBeanCondition 无法在注册阶段准确识别具体实现类型。

### 21.11 推荐断点

```text
ConditionEvaluator.shouldSkip()
ConfigurationClassParser.processConfigurationClass()
ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsForBeanMethod()
ConfigurationClassBeanDefinitionReader$TrackedConditionEvaluator.shouldSkip()
SpringBootCondition.matches()
SpringBootCondition.getMatchOutcome()
OnBeanCondition.getMatchOutcome()
OnBeanCondition.getMatchingBeans()
ConditionEvaluationReport.recordConditionEvaluation()
```

重点观察：

```text
metadata
phase
conditions
requiredPhase
ConditionOutcome.match
ConditionOutcome.message
MatchSpec
matchingBeans
ConditionEvaluationReport.outcomes
```

---
## 二十二、专题七：七组可运行源码实验

这七组实验分别对应前面六个源码专题，并在最后用一个 Boot 自动配置实验串起完整链路。

建议把示例放在：

```text
src/test/java/com/example/ioc/configurationclass/
```

每个实验都先正常运行一次，再按照断点清单单步调试。

### 22.1 实验一：验证 @Import 的三个分支

目标：同时观察普通导入类、`ImportSelector` 和 `ImportBeanDefinitionRegistrar`。

```java
@Configuration
@Import({
        PlainImportedConfig.class,
        TraceImportSelector.class,
        TraceRegistrar.class
})
class ImportRootConfig {
}

@Configuration(proxyBeanMethods = false)
class PlainImportedConfig {
    @Bean
    PlainBean plainBean() {
        return new PlainBean();
    }
}

class TraceImportSelector implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
        return new String[]{SelectorImportedConfig.class.getName()};
    }
}

@Configuration(proxyBeanMethods = false)
class SelectorImportedConfig {
    @Bean
    SelectorBean selectorBean() {
        return new SelectorBean();
    }
}

class TraceRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata metadata,
            BeanDefinitionRegistry registry) {
        registry.registerBeanDefinition(
                "registrarBean",
                new RootBeanDefinition(RegistrarBean.class));
    }
}
```

运行：

```java
try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(ImportRootConfig.class)) {

    System.out.println(context.containsBean("plainBean"));
    System.out.println(context.containsBean("selectorBean"));
    System.out.println(context.containsBean("registrarBean"));
}
```

预期三个结果都是 `true`。

断点顺序：

```text
processImports()
  ├── PlainImportedConfig → 普通类分支
  ├── TraceImportSelector → ImportSelector 分支
  └── TraceRegistrar → Registrar 暂存分支

loadBeanDefinitionsFromRegistrars()
  └── TraceRegistrar 真正注册 registrarBean
```

观察 `configClass.importBeanDefinitionRegistrars`，确认 Registrar 在 Parser 阶段只是保存。

### 22.2 实验二：验证 DeferredImportSelector 分组

目标：观察 selector 被收集、排序、交给 Group，再把 Entry 返回 Parser。

```java
@Configuration
@Import(TraceDeferredSelector.class)
class DeferredRootConfig {
}

class TraceDeferredSelector implements DeferredImportSelector, Ordered {

    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
        return new String[]{DeferredImportedConfig.class.getName()};
    }

    @Override
    public Class<? extends Group> getImportGroup() {
        return TraceGroup.class;
    }

    @Override
    public int getOrder() {
        return 100;
    }
}

class TraceGroup implements DeferredImportSelector.Group {

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public void process(
            AnnotationMetadata metadata,
            DeferredImportSelector selector) {
        for (String className : selector.selectImports(metadata)) {
            entries.add(new Entry(metadata, className));
        }
    }

    @Override
    public Iterable<Entry> selectImports() {
        return entries;
    }
}

@Configuration(proxyBeanMethods = false)
class DeferredImportedConfig {
    @Bean
    DeferredBean deferredBean() {
        return new DeferredBean();
    }
}
```

运行并验证：

```java
try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(DeferredRootConfig.class)) {
    System.out.println(context.containsBean("deferredBean"));
}
```

断点顺序：

```text
DeferredImportSelectorHandler.handle()
DeferredImportSelectorHandler.process()
DeferredImportSelectorGroupingHandler.register()
TraceGroup.process()
TraceGroup.selectImports()
DeferredImportSelectorGroupingHandler.processGroupImports()
processImports()
```

必须观察到 `TraceGroup.process()` 晚于普通配置类的初次 parse。

### 22.3 实验三：观察 Parser 状态与重复导入合并

目标：观察同一个配置类被两个配置类导入时，Parser 如何合并 `importedBy`。

```java
@Configuration
@Import(SharedConfig.class)
class ImporterA {
}

@Configuration
@Import(SharedConfig.class)
class ImporterB {
}

@Configuration(proxyBeanMethods = false)
class SharedConfig {
    @Bean
    SharedBean sharedBean() {
        return new SharedBean();
    }
}
```

启动：

```java
AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();
context.register(ImporterA.class, ImporterB.class);
context.refresh();
```

在 `processConfigurationClass()` 中观察：

```text
第一次发现 SharedConfig
  └── 创建 ConfigurationClass，importedBy 包含 ImporterA

第二次发现 SharedConfig
  └── existingClass != null
      └── mergeImportedBy()，增加 ImporterB
```

同时观察：

```text
configurationClasses
knownSuperclasses
importStack
SharedConfig.importedBy
```

扩展实验：让 `ImporterA` 与 `ImporterB` 相互 `@Import`，观察
`CircularImportProblem` 的异常路径。

### 22.4 实验四：检查 @Bean 对应的 BeanDefinition

目标：验证 Reader 写入的工厂 Bean、工厂方法、Scope 和生命周期属性。

```java
@Configuration
class ReaderConfig {

    @Bean(
        name = {"userService", "userServiceAlias"},
        initMethod = "start",
        destroyMethod = "close"
    )
    @Lazy
    UserService createUserService(UserRepository repository) {
        return new UserService(repository);
    }

    @Bean
    UserRepository userRepository() {
        return new UserRepository();
    }
}
```

读取定义：

```java
try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(ReaderConfig.class)) {

    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
    BeanDefinition definition =
            beanFactory.getBeanDefinition("userService");

    System.out.println(definition.getFactoryBeanName());
    System.out.println(definition.getFactoryMethodName());
    System.out.println(definition.isLazyInit());
    System.out.println(Arrays.toString(context.getAliases("userService")));

    AbstractBeanDefinition abd = (AbstractBeanDefinition) definition;
    System.out.println(abd.getAutowireMode());
    System.out.println(Arrays.toString(abd.getInitMethodNames()));
    System.out.println(Arrays.toString(abd.getDestroyMethodNames()));
}
```

预期重点：

```text
factoryBeanName   = readerConfig
factoryMethodName = createUserService
lazyInit          = true
autowireMode      = AUTOWIRE_CONSTRUCTOR
alias             = userServiceAlias → userService
```

### 22.5 实验五：验证 Full 与 Lite 配置类语义

目标：直接证明 CGLIB 增强改变了跨 `@Bean` 方法调用结果。

```java
@Configuration
class FullConfig {
    @Bean
    UserDao userDao() {
        return new UserDao();
    }

    @Bean
    UserService userService() {
        return new UserService(userDao());
    }
}

@Configuration(proxyBeanMethods = false)
class LiteConfig {
    @Bean
    UserDao userDao() {
        return new UserDao();
    }

    @Bean
    UserService userService() {
        return new UserService(userDao());
    }
}
```

分别创建两个上下文并比较：

```java
try (AnnotationConfigApplicationContext full =
        new AnnotationConfigApplicationContext(FullConfig.class)) {
    FullConfig config = full.getBean(FullConfig.class);
    UserDao containerDao = full.getBean(UserDao.class);
    UserService service = full.getBean(UserService.class);

    System.out.println(config.getClass());
    System.out.println(config.userDao() == containerDao);
    System.out.println(service.getUserDao() == containerDao);
}

try (AnnotationConfigApplicationContext lite =
        new AnnotationConfigApplicationContext(LiteConfig.class)) {
    LiteConfig config = lite.getBean(LiteConfig.class);
    UserDao containerDao = lite.getBean(UserDao.class);
    UserService service = lite.getBean(UserService.class);

    System.out.println(config.getClass());
    System.out.println(config.userDao() == containerDao);
    System.out.println(service.getUserDao() == containerDao);
}
```

| 检查项 | Full | Lite |
|--------|------|------|
| 配置类是否为 CGLIB 子类 | 是 | 否 |
| `config.userDao() == containerDao` | true | false |
| `service.getUserDao() == containerDao` | true | false |

把 LiteConfig 改成方法参数注入：

```java
@Bean
UserService userService(UserDao userDao) {
    return new UserService(userDao);
}
```

此时即使不开启配置类代理，`UserService` 也会得到容器中的 `UserDao`。

断点：

```text
ConfigurationClassEnhancer.enhance()
BeanFactoryAwareMethodInterceptor.intercept()
BeanMethodInterceptor.intercept()
BeanMethodInterceptor.isCurrentlyInvokedFactoryMethod()
BeanMethodInterceptor.resolveBeanReference()
```

### 22.6 实验六：验证 PARSE 与 REGISTER 两个条件阶段

解析阶段条件：

```java
class ParsePropertyCondition implements ConfigurationCondition {
    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.PARSE_CONFIGURATION;
    }

    @Override
    public boolean matches(ConditionContext context,
            AnnotatedTypeMetadata metadata) {
        return context.getEnvironment()
                .getProperty("feature.enabled", Boolean.class, false);
    }
}
```

注册阶段条件：

```java
class DependencyDefinitionCondition implements ConfigurationCondition {
    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context,
            AnnotatedTypeMetadata metadata) {
        return context.getRegistry()
                .containsBeanDefinition("dependency");
    }
}
```

配置类：

```java
@Configuration(proxyBeanMethods = false)
@Conditional(ParsePropertyCondition.class)
class ConditionalConfig {
    @Bean
    @Conditional(DependencyDefinitionCondition.class)
    FeatureBean featureBean() {
        return new FeatureBean();
    }
}
```

运行：

```java
AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();
context.getEnvironment().getPropertySources().addFirst(
        new MapPropertySource("test", Map.of("feature.enabled", true)));
context.registerBean("dependency", Dependency.class);
context.register(ConditionalConfig.class);
context.refresh();
System.out.println(context.containsBean("featureBean"));
context.close();
```

变化测试：

```text
feature.enabled=false
  └── PARSE_CONFIGURATION 失败，ConditionalConfig 完全不解析

不注册 dependency
  └── 配置类正常解析，但 featureBean 在 REGISTER_BEAN 阶段跳过
```

### 22.7 实验七：贯通 Boot 自动配置和条件报告

自动配置：

```java
@AutoConfiguration
@ConditionalOnClass(DemoClient.class)
class DemoClientAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    DemoClient demoClient() {
        return new DemoClient("auto");
    }
}
```

测试资源文件：

```text
src/test/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

内容：

```text
com.example.ioc.configurationclass.DemoClientAutoConfiguration
```

最小启动类：

```java
@SpringBootApplication
class AutoConfigurationTestApplication {
}
```

运行并读取条件报告：

```java
SpringApplication application =
        new SpringApplication(AutoConfigurationTestApplication.class);
application.setWebApplicationType(WebApplicationType.NONE);

try (ConfigurableApplicationContext context =
        application.run("--debug")) {
    System.out.println(context.getBean(DemoClient.class).getSource());

    ConditionEvaluationReport report =
            ConditionEvaluationReport.get(context.getBeanFactory());
    report.getConditionAndOutcomesBySource().entrySet().stream()
            .filter(entry -> entry.getKey().contains("DemoClientAutoConfiguration"))
            .forEach(System.out::println);
}
```

再提供用户 Bean：

```java
@Configuration(proxyBeanMethods = false)
class UserClientConfig {
    @Bean
    DemoClient demoClient() {
        return new DemoClient("user");
    }
}
```

把 `UserClientConfig` 作为额外 primary source 启动，预期：

```text
DemoClient.source = user
DemoClientAutoConfiguration#demoClient
  └── @ConditionalOnMissingBean 不匹配
```

完整断点链路：

```text
AutoConfigurationImportSelector.getCandidateConfigurations()
AutoConfigurationImportSelector.getAutoConfigurationEntry()
AutoConfigurationGroup.process()
AutoConfigurationGroup.selectImports()
ConditionEvaluator.shouldSkip()
OnBeanCondition.getMatchOutcome()
ConfigurationClassBeanDefinitionReader.loadBeanDefinitionsForBeanMethod()
ConditionEvaluationReport.recordConditionEvaluation()
```

### 22.8 七组实验的验收标准

完成实验后，应能够不看文档回答：

1. 普通导入类、Selector、Registrar 分别在哪个阶段处理？
2. DeferredImportSelector 为什么需要 Handler 和 Group 两层结构？
3. Parser 如何合并重复配置类并检测循环导入？
4. 实例 `@Bean` 为什么只保存 factoryBeanName 而不保存 beanClass？
5. CGLIB 如何判断应该执行原 `@Bean` 方法还是调用 `getBean()`？
6. PARSE_CONFIGURATION 和 REGISTER_BEAN 的调用位置有什么不同？
7. Boot 如何加载、排序、过滤自动配置并记录条件结果？

如果能够结合断点独立回答以上问题，这篇文档的核心内容才算真正掌握。

---
## 二十三、常见面试题

### 23.1 ConfigurationClassPostProcessor 的作用是什么？

答题框架：

1. 它是一个 `BeanDefinitionRegistryPostProcessor`。
2. 工作在 `refresh()` 的 `invokeBeanFactoryPostProcessors()` 阶段。
3. 它负责解析配置类，把 `@Configuration`、`@ComponentScan`、`@Bean`、`@Import`、`@PropertySource` 等注解转换成 BeanDefinition。
4. 它还会增强 full 模式的 `@Configuration` 类，保证 `@Bean` 方法调用返回容器单例。
5. Spring Boot 自动配置、`@EnableXxx` 系列注解、组件扫描都依赖这条链路。

### 23.2 它和普通 BeanPostProcessor 有什么区别？

| 维度 | ConfigurationClassPostProcessor | BeanPostProcessor |
|------|--------------------------------|-------------------|
| 处理对象 | BeanDefinition / 配置类元数据 | Bean 实例 |
| 执行时机 | Bean 实例化之前 | Bean 初始化前后 |
| 典型作用 | 解析注解、注册 BeanDefinition | 依赖注入、AOP、初始化增强 |
| 代表方法 | `postProcessBeanDefinitionRegistry()` | `postProcessBeforeInitialization()` / `postProcessAfterInitialization()` |

### 23.3 @ComponentScan 是在什么时候执行的？

在 `refresh()` 的 `invokeBeanFactoryPostProcessors()` 阶段，由 `ConfigurationClassPostProcessor` 解析配置类时执行。

不是在启动类构造时执行，也不是在普通 Bean 创建时执行。

### 23.4 @Bean 方法什么时候执行？

`ConfigurationClassPostProcessor` 阶段只解析 `@Bean` 方法并注册 BeanDefinition，不执行方法。

真正执行发生在后续创建 Bean 时：

```text
finishBeanFactoryInitialization()
  └── getBean()
      └── instantiateUsingFactoryMethod()
          └── 调用 @Bean 方法
```

### 23.5 为什么 @Configuration 要被 CGLIB 代理？

为了保证 `@Bean` 方法之间互相调用时仍然返回容器中的单例 Bean，而不是每次普通方法调用都创建新对象。

### 23.6 Spring Boot 自动配置和它有什么关系？

`@SpringBootApplication` 包含 `@EnableAutoConfiguration`，后者通过 `@Import(AutoConfigurationImportSelector.class)` 导入自动配置选择器。

`ConfigurationClassPostProcessor` 解析 `@Import`，识别 `DeferredImportSelector`，最终加载自动配置类。

### 23.7 为什么自动配置要延迟导入？

为了让用户自定义配置先注册，再让自动配置根据 `@ConditionalOnMissingBean` 等条件决定是否生效。

这保证了“用户配置优先，自动配置兜底”。

### 23.8 为什么不要在 BeanFactoryPostProcessor 中注入普通业务 Bean？

因为该阶段普通 Bean 还没正式创建。强行注入会导致 Bean 过早初始化，可能跳过 AOP、后处理器或其他容器增强逻辑。

---

## 二十四、调试断点建议

### 24.1 主链路断点

| 目标 | 断点位置 | 关键类 |
|------|----------|--------|
| refresh 第 5 步 | `invokeBeanFactoryPostProcessors()` | `AbstractApplicationContext` |
| 后处理器调度 | `invokeBeanFactoryPostProcessors()` | `PostProcessorRegistrationDelegate` |
| 配置类入口 | `postProcessBeanDefinitionRegistry()` | `ConfigurationClassPostProcessor` |
| 配置类主流程 | `processConfigBeanDefinitions()` | `ConfigurationClassPostProcessor` |
| 配置类候选判断 | `checkConfigurationClassCandidate()` | `ConfigurationClassUtils` |
| 配置类解析 | `parse()` | `ConfigurationClassParser` |
| 单个配置类处理 | `doProcessConfigurationClass()` | `ConfigurationClassParser` |
| ComponentScan 解析 | `parse()` | `ComponentScanAnnotationParser` |
| classpath 扫描 | `doScan()` | `ClassPathBeanDefinitionScanner` |
| @Bean 注册 | `loadBeanDefinitionsForBeanMethod()` | `ConfigurationClassBeanDefinitionReader` |
| 配置类增强 | `enhanceConfigurationClasses()` | `ConfigurationClassPostProcessor` |

### 24.2 观察变量

调试时重点看：

```text
registry.beanDefinitionMap
registry.beanDefinitionNames
configCandidates
parser.configurationClasses
parser.importStack
parser.deferredImportSelectorHandler
sourceClass.metadata
beanMethods
importBeanDefinitionRegistrars
```

### 24.3 推荐调试路径

第一轮建议只看一个最小 Spring Boot 启动类：

```text
DemoApplication
  ├── @SpringBootApplication
  ├── @ComponentScan("com.example")
  ├── @PropertySource
  └── @Bean("restTemplate")
```

观察目标：

1. `DemoApplication` 如何被识别为配置类。
2. `@ComponentScan("com.example")` 如何扫描出业务组件。
3. `@Bean("restTemplate")` 如何注册成 BeanDefinition。
4. `@PropertySource` 如何加入 `Environment`。
5. `@EnableXxx` 类注解如何通过 `@Import` 触发 selector 或 registrar。

---

## 二十五、生产实践建议

### 25.1 控制 ComponentScan 范围

常见的大范围扫描：

```java
@ComponentScan(value = {"com.example"})
```

优点：

- 简单
- 多模块自动发现
- 适合单体应用和框架型工程

风险：

- 扫描范围大
- 启动慢
- Bean 冲突概率高
- 测试上下文加载成本高
- 容易把不该注册的类注册进容器

如果做微服务拆分或独立模块启动，建议缩小扫描范围：

```java
@ComponentScan(basePackages = {
    "com.example.order",
    "com.example.user",
    "com.example.common"
})
```

### 25.2 配置类优先使用方法参数注入

推荐：

```java
@Bean
public RestTemplate restTemplate(ActionTrackInterceptor interceptor) {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.setInterceptors(Collections.singletonList(interceptor));
    return restTemplate;
}
```

不推荐：

```java
@Autowired
private ActionTrackInterceptor actionTrackInterceptor;

@Bean
public RestTemplate restTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.setInterceptors(Collections.singletonList(actionTrackInterceptor));
    return restTemplate;
}
```

原因：

- 方法参数注入依赖更清晰
- 避免配置类字段注入
- 对 `proxyBeanMethods = false` 更友好
- 更容易单元测试

### 25.3 自动配置类使用 proxyBeanMethods = false

Spring Boot 自动配置类建议直接使用 `@AutoConfiguration`：

```java
@AutoConfiguration
public class CacheAutoConfiguration {
}
```

该注解已经包含 `@Configuration(proxyBeanMethods = false)`，可以避免不必要的配置类 CGLIB 增强。普通配置类在没有跨 `@Bean` 方法直接调用时，也可以显式使用 `@Configuration(proxyBeanMethods = false)`。

### 25.4 不要在 BeanFactoryPostProcessor 中提前 getBean

避免：

```java
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    UserService userService = beanFactory.getBean(UserService.class);
}
```

推荐操作 BeanDefinition：

```java
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    BeanDefinition bd = beanFactory.getBeanDefinition("userService");
    bd.setLazyInit(true);
}
```

---

## 二十六、学习路线

### 26.1 第一阶段：看懂职责

重点掌握：

- 它是 `BeanDefinitionRegistryPostProcessor`
- 它在 `invokeBeanFactoryPostProcessors()` 阶段执行
- 它把注解配置转换成 BeanDefinition

### 26.2 第二阶段：看懂五类注解

按顺序学习：

1. `@Configuration`
2. `@ComponentScan`
3. `@Bean`
4. `@Import`
5. `@Conditional`

### 26.3 第三阶段：结合最小启动类调试

从 `DemoApplication` 开始：

1. 看启动类如何成为配置类候选。
2. 看 `@ComponentScan("com.example")` 扫描结果。
3. 看 `@Bean("restTemplate")` 的 BeanDefinition。
4. 看 `@PropertySource` 的属性源注册。
5. 看 `@EnableXxx` 如何通过 `@Import` 导入额外配置。

### 26.4 第四阶段：深入 Spring Boot 自动配置

重点看：

```text
@SpringBootApplication
  └── @EnableAutoConfiguration
      └── @Import(AutoConfigurationImportSelector.class)
          └── DeferredImportSelector
              └── AutoConfiguration.imports
                  └── @ConditionalOnXxx
```

这条链路是 Spring Boot “约定大于配置”的核心。

### 26.5 第五阶段：完成七组源码实验

最终验收不再只看“是否读懂”，而看能否独立调试：

1. 跟踪 `processImports()` 的四个分支。
2. 解释 DeferredImportSelector 的排序和分组过程。
3. 从 Parser 状态中定位重复导入和循环导入。
4. 检查 `@Bean` 对应 BeanDefinition 的关键字段。
5. 证明 Full 与 Lite 配置类的对象语义差异。
6. 分别触发 PARSE_CONFIGURATION 与 REGISTER_BEAN。
7. 从 AutoConfiguration.imports 跟踪到 ConditionEvaluationReport。

七组实验全部完成，并能画出调用链后，再进入 Bean 创建流程和 AOP 源码学习。

---

## 二十七、最终总结

`ConfigurationClassPostProcessor` 是 Spring 注解配置体系的核心转换器。

它解决的问题是：

```text
开发者写的注解配置
  ↓
Spring 可识别的 ConfigurationClass 模型
  ↓
BeanDefinition 注册表
  ↓
BeanFactory 创建和装配 Bean
```

一句话记忆：

> `ConfigurationClassPostProcessor` 不创建业务 Bean，它负责在业务 Bean 创建之前，把配置类、扫描、导入、条件和 `@Bean` 方法全部解析成 BeanDefinition。

在 Spring Boot 应用中，它直接支撑了：

- `@ComponentScan` 的组件发现
- `@Bean` 方法的注册
- `@Import` 导入的 selector 和 registrar
- `@EnableXxx` 扩展能力的接入
- 通过组件扫描发现的后处理器的早期执行
- Spring Boot 自动配置的导入和条件判断

如果要真正掌握 Spring IOC，`refresh()` 是主线，而 `ConfigurationClassPostProcessor` 就是注解配置进入 IOC 容器的核心关口。
