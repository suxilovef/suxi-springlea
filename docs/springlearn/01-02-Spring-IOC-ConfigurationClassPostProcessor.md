---
title: 01-02-Spring-IOC-ConfigurationClassPostProcessor
date: 2026-07-09 20:07:05
description: Spring-IOC-ConfigurationClassPostProcessor
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/spring-beanfactory-postprocessor-min.svg
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

## 十六、常见面试题

### 16.1 ConfigurationClassPostProcessor 的作用是什么？

答题框架：

1. 它是一个 `BeanDefinitionRegistryPostProcessor`。
2. 工作在 `refresh()` 的 `invokeBeanFactoryPostProcessors()` 阶段。
3. 它负责解析配置类，把 `@Configuration`、`@ComponentScan`、`@Bean`、`@Import`、`@PropertySource` 等注解转换成 BeanDefinition。
4. 它还会增强 full 模式的 `@Configuration` 类，保证 `@Bean` 方法调用返回容器单例。
5. Spring Boot 自动配置、`@EnableXxx` 系列注解、组件扫描都依赖这条链路。

### 16.2 它和普通 BeanPostProcessor 有什么区别？

| 维度 | ConfigurationClassPostProcessor | BeanPostProcessor |
|------|--------------------------------|-------------------|
| 处理对象 | BeanDefinition / 配置类元数据 | Bean 实例 |
| 执行时机 | Bean 实例化之前 | Bean 初始化前后 |
| 典型作用 | 解析注解、注册 BeanDefinition | 依赖注入、AOP、初始化增强 |
| 代表方法 | `postProcessBeanDefinitionRegistry()` | `postProcessBeforeInitialization()` / `postProcessAfterInitialization()` |

### 16.3 @ComponentScan 是在什么时候执行的？

在 `refresh()` 的 `invokeBeanFactoryPostProcessors()` 阶段，由 `ConfigurationClassPostProcessor` 解析配置类时执行。

不是在启动类构造时执行，也不是在普通 Bean 创建时执行。

### 16.4 @Bean 方法什么时候执行？

`ConfigurationClassPostProcessor` 阶段只解析 `@Bean` 方法并注册 BeanDefinition，不执行方法。

真正执行发生在后续创建 Bean 时：

```text
finishBeanFactoryInitialization()
  └── getBean()
      └── instantiateUsingFactoryMethod()
          └── 调用 @Bean 方法
```

### 16.5 为什么 @Configuration 要被 CGLIB 代理？

为了保证 `@Bean` 方法之间互相调用时仍然返回容器中的单例 Bean，而不是每次普通方法调用都创建新对象。

### 16.6 Spring Boot 自动配置和它有什么关系？

`@SpringBootApplication` 包含 `@EnableAutoConfiguration`，后者通过 `@Import(AutoConfigurationImportSelector.class)` 导入自动配置选择器。

`ConfigurationClassPostProcessor` 解析 `@Import`，识别 `DeferredImportSelector`，最终加载自动配置类。

### 16.7 为什么自动配置要延迟导入？

为了让用户自定义配置先注册，再让自动配置根据 `@ConditionalOnMissingBean` 等条件决定是否生效。

这保证了“用户配置优先，自动配置兜底”。

### 16.8 为什么不要在 BeanFactoryPostProcessor 中注入普通业务 Bean？

因为该阶段普通 Bean 还没正式创建。强行注入会导致 Bean 过早初始化，可能跳过 AOP、后处理器或其他容器增强逻辑。

---

## 十七、调试断点建议

### 17.1 主链路断点

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

### 17.2 观察变量

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

### 17.3 推荐调试路径

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

## 十八、生产实践建议

### 18.1 控制 ComponentScan 范围

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

### 18.2 配置类优先使用方法参数注入

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

### 18.3 自动配置类使用 proxyBeanMethods = false

Spring Boot 自动配置类建议直接使用 `@AutoConfiguration`：

```java
@AutoConfiguration
public class CacheAutoConfiguration {
}
```

该注解已经包含 `@Configuration(proxyBeanMethods = false)`，可以避免不必要的配置类 CGLIB 增强。普通配置类在没有跨 `@Bean` 方法直接调用时，也可以显式使用 `@Configuration(proxyBeanMethods = false)`。

### 18.4 不要在 BeanFactoryPostProcessor 中提前 getBean

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

## 十九、学习路线

### 19.1 第一阶段：看懂职责

重点掌握：

- 它是 `BeanDefinitionRegistryPostProcessor`
- 它在 `invokeBeanFactoryPostProcessors()` 阶段执行
- 它把注解配置转换成 BeanDefinition

### 19.2 第二阶段：看懂五类注解

按顺序学习：

1. `@Configuration`
2. `@ComponentScan`
3. `@Bean`
4. `@Import`
5. `@Conditional`

### 19.3 第三阶段：结合最小启动类调试

从 `DemoApplication` 开始：

1. 看启动类如何成为配置类候选。
2. 看 `@ComponentScan("com.example")` 扫描结果。
3. 看 `@Bean("restTemplate")` 的 BeanDefinition。
4. 看 `@PropertySource` 的属性源注册。
5. 看 `@EnableXxx` 如何通过 `@Import` 导入额外配置。

### 19.4 第四阶段：深入 Spring Boot 自动配置

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

---

## 二十、最终总结

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
