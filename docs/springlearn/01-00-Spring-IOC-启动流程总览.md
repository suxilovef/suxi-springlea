---
title: 01-00-Spring-IOC-启动流程总览
date: 2026-11-06 00:00:00
description: Spring-IOC-启动流程总览
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-ioc.svg
rank: 90
top_img: false
---
# Spring IOC 启动全流程总览：refresh 地图与扩展点时序

> 基准：Spring Boot `3.3.9` / Spring Framework `6.1.17` / Java `17`。
>
> 本文是 01 系列的**地图篇**。01-01 讲容器概念，01-02 讲 BeanDefinition 从哪里来，01-03 讲 Bean 怎么创建；本文只回答一个问题：**这些机制在 `refresh()` 的什么位置执行，为什么必须按这个顺序执行。**
>
> 文中方法调用顺序用 `javap -c` 反编译本地 `spring-context` / `spring-beans` 6.1.17 核对过。标出类名和方法名，是为了让你能直接对着源码下断点，而不是背一张图。

---

## 0. 先给结论：refresh 是一条状态推进链，不是 12 个并列步骤

把 `refresh()` 当成 12 个平铺步骤去背，最容易背成“顺序题”；真正要抓住的是下面 6 个状态阶段（按 refresh 的执行顺序排列）：

| 顺序 | 状态阶段 | 对应步骤 | 这一步解决什么 | 顺序错位的后果 |
|---|---|---|---|---|
| 1 | BeanFactory 基础设施就绪 | ②③④ | 类加载器、表达式解析、内置 BeanPostProcessor、子类作用域 | ⑤ 在“裸” BeanFactory 上运行，缺少环境和依赖解析能力 |
| 2 | BeanDefinition 全量就绪 | `register()` + ⑤ | 配置类、扫描结果、`@Bean` 全部变成元数据 | ⑥ / ⑪ 找不到用户 BPP 和业务 Bean 的定义 |
| 3 | BeanPostProcessor 就绪 | ⑥ | 把 BPP 实例化并接进 BeanFactory 的拦截链 | 先创建的单例会绕过 AOP、事务、`@PostConstruct` |
| 4 | 事件与生命周期基础设施就绪 | ⑦⑧⑨⑩ | 消息源、事件广播器、子类刷新、监听器注册 | 早期事件没人接收，监听器注册不完整 |
| 5 | 单例就绪 | ⑪ | 按 BeanDefinition 批量创建非懒加载单例 | 容器看起来“启动了”，实际没有业务 Bean |
| 6 | 容器对外就绪 | ⑫ | 启动生命周期处理器，发布 `ContextRefreshedEvent` | 事件早于 Bean 就绪发出，监听器拿到半成品容器 |

注意两个容易混淆的“顺序”：

```text
执行顺序（refresh 内部）：
②③④ → ⑤ → ⑥ → ⑦⑧⑨⑩ → ⑪ → ⑫
例外：register() 在 refresh 之前，先把配置类登记成 BeanDefinition；
      但扫描结果和 @Bean 定义要等到 ⑤ 才全量就绪。

依赖顺序（为什么不能交换）：
BeanDefinition（⑤）
    ├─> BPP 的 BeanDefinition
    │     └─> ⑥ BPP 实例就绪
    └─> 业务单例的 BeanDefinition
          └─> ⑪ 单例就绪
                └─> ⑫ 对外就绪
```

也就是说，表格第 1 行是“执行上更早”，第 2 行是“依赖上更基础”；**不能把第 2 行挪到第一行，否则就变成了“在未准备好的 BeanFactory 上执行 BFPP”**。BeanDefinition 是后续所有实例化步骤的前提，但它本身也要依赖 BeanFactory 基础设施才能被完整解析。

三个“为什么”：

1. **BFPP 操作的是 BeanDefinition，BPP 操作的是 Bean 实例。** 前者在元数据层工作，后者在实例层工作，所以 ⑤ 必须先于 ⑥ / ⑪。
2. **元数据层必须先于扩展点层和实例层稳定。** `ConfigurationClassPostProcessor` 在 ⑤ 里解析 `@ComponentScan`、`@Bean`、`@Import`；如果 BPP 先注册，用户 BPP 的定义可能还没解析出来。
3. **实例层必须等所有 BPP 就位。** BPP 是“拦截器”，拦截器没装好就开始创建对象，先创建的对象就永远失去了被增强的机会 —— 这是 AOP / 事务失效的一类启动期根因。

---

## 1. 总图（修正版）

下面这张图以你给的流程图为骨架，但把几处顺序和归属改成了源码中的实际行为。带 ⚠️ 的地方在第七节有集中解释。

```text
new AnnotationConfigApplicationContext(Config.class)
├─ GenericApplicationContext 构造：创建 DefaultListableBeanFactory
├─ 创建 AnnotatedBeanDefinitionReader
│    └─ 注册注解基础设施 BeanDefinition（含 ConfigurationClassPostProcessor）
├─ 创建 ClassPathBeanDefinitionScanner
├─ register(Config.class)                    ← 只登记 BeanDefinition，不创建 Bean
│   ⚠️ 普通容器没有 ApplicationContextInitializer（那是 Spring Boot 的机制）
└─ refresh()                                 ← AbstractApplicationContext#refresh
    ├─ ① prepareRefresh()
    │    ├─ initPropertySources()
    │    ├─ environment.validateRequiredProperties()
    │    ├─ 保存 earlyApplicationListeners
    │    └─ 准备 earlyApplicationEvents 缓存
    ├─ ② obtainFreshBeanFactory()
    │    └─ ⚠️ 注解容器不新建 BeanFactory，只标记“已刷新”并设置 serializationId
    ├─ ③ prepareBeanFactory(beanFactory)
    │    ├─ setBeanClassLoader / BeanExpressionResolver / ResourceEditorRegistrar
    │    ├─ 注册 ApplicationContextAwareProcessor
    │    ├─ 注册 ApplicationListenerDetector
    │    ├─ ignoreDependencyInterface(...)
    │    └─ registerResolvableDependency(...)、注册 environment 等
    ├─ ④ postProcessBeanFactory(beanFactory)   ← 模板方法，子类扩展
    ├─ ⑤ invokeBeanFactoryPostProcessors(beanFactory)
    │    ├─ BeanDefinitionRegistryPostProcessor：先执行（新增 BeanDefinition）
    │    │    ├─ PriorityOrdered → Ordered → 其余
    │    │    └─ ConfigurationClassPostProcessor 在这里解析配置类
    │    ├─ 执行上述 BDRPP 的 postProcessBeanFactory(...)
    │    └─ BeanFactoryPostProcessor：后执行（修改已有 BeanDefinition）
    │         └─ PriorityOrdered → Ordered → 其余
    │    ⚠️ 此时业务单例还没有创建；但 BDRPP/BFPP 自身是被实例化过的 Bean
    ├─ ⑥ registerBeanPostProcessors(beanFactory)
    │    ├─ 实例化并注册 BPP：PriorityOrdered → Ordered → 其余
    │    ├─ MergedBeanDefinitionPostProcessor 重新排到链尾
    │    └─ ApplicationListenerDetector 重新排到链尾
    ├─ ⑦ initMessageSource()
    ├─ ⑧ initApplicationEventMulticaster()
    ├─ ⑨ onRefresh()                           ← 模板方法
    │    └─ 注解容器为空实现；Web 容器在这里创建 WebServer
    ├─ ⑩ registerListeners()
    │    ├─ 注册编程式添加的 ApplicationListener
    │    ├─ 登记容器中的 ApplicationListener Bean 名称
    │    └─ 发布 earlyApplicationEvents 中缓存的事件
    ├─ ⑪ finishBeanFactoryInitialization(beanFactory)
    │    ├─ 初始化 ConversionService
    │    ├─ 添加内嵌值解析器（占位符 ${...}）
    │    ├─ 初始化 LoadTimeWeaverAware
    │    ├─ setTempClassLoader(null)
    │    ├─ freezeConfiguration()              ← 冻结 BeanDefinition 的“名称快照”
    │    └─ preInstantiateSingletons()
    │         └─ 遍历单例、非抽象、非懒加载 Bean → getBean → doGetBean
    │              ├─ getSingleton(beanName)                        // 命中直接返回
    │              └─ getSingleton(beanName, () -> createBean(...))
    │                   ├─ createBean
    │                   │    ├─ resolveBeforeInstantiation
    │                   │    │    ├─ determineTargetType → predictBeanType
    │                   │    │    └─ postProcessBeforeInstantiation（返回非 null 则短路）
    │                   │    │         └─ 短路时只再走 postProcessAfterInitialization
    │                   │    └─ doCreateBean
    │                   │         ├─ createBeanInstance
    │                   │         │    └─ determineCandidateConstructors（推断构造器）
    │                   │         ├─ applyMergedBeanDefinitionPostProcessors
    │                   │         │    └─ postProcessMergedBeanDefinition（缓存 @Autowired / @PostConstruct 元数据）
    │                   │         ├─ addSingletonFactory(...)    ← 三级缓存工厂，先注册不执行
    │                   │         │    └─ 循环依赖时才会调用 getEarlyBeanReference
    │                   │         ├─ populateBean
    │                   │         │    ├─ postProcessAfterInstantiation
    │                   │         │    └─ postProcessProperties（@Autowired / @Resource 注入）
    │                   │         └─ initializeBean
    │                   │              ├─ invokeAwareMethods
    │                   │              │    └─ BeanNameAware → BeanClassLoaderAware → BeanFactoryAware
    │                   │              ├─ postProcessBeforeInitialization
    │                   │              │    ├─ ApplicationContextAwareProcessor
    │                   │              │    │    └─ EnvironmentAware → ... → ApplicationContextAware
    │                   │              │    └─ @PostConstruct
    │                   │              ├─ InitializingBean#afterPropertiesSet
    │                   │              ├─ 自定义 init-method
    │                   │              └─ postProcessAfterInitialization
    │                   │                   └─ AOP 代理在这里生成（若已有早期代理则复用）
    │                   └─ addSingleton(beanName, exposedObject) → singletonObjects
    │         └─ 全部创建完后：SmartInitializingSingleton#afterSingletonsInstantiated
    └─ ⑫ finishRefresh()
         ├─ resetCommonCaches()
         ├─ clearResourceCaches()
         ├─ initLifecycleProcessor()
         ├─ lifecycleProcessor.onRefresh()      ← SmartLifecycle#start()
         └─ publishEvent(ContextRefreshedEvent)
              ⚠️ 这里不注册 JVM 关闭钩子；registerShutdownHook() 是另一个独立方法

// ========== 销毁阶段 ==========
close()
└─ doClose()
    ├─ publishEvent(ContextClosedEvent)
    ├─ lifecycleProcessor.onClose()            ← SmartLifecycle#stop()
    ├─ destroyBeans()
    │    └─ 每个单例：
    │         ├─ DestructionAwareBeanPostProcessor#postProcessBeforeDestruction
    │         │    └─ 其中 @PreDestroy 由 CommonAnnotationBeanPostProcessor 触发
    │         ├─ DisposableBean#destroy()
    │         └─ 自定义 destroy-method
    ├─ closeBeanFactory()
    ├─ onClose()                               ← 模板方法
    └─ resetCommonCaches()

// ========== 独立扩展点：FactoryBean ==========
getBean("&xxx") → 返回 FactoryBean 本身
getBean("xxx")  → 调用 FactoryBean#getObject()，返回产品对象
```

---

## 2. 构造阶段：`new` 的时候发生了什么

### 2.1 四个常用构造器，行为不同

| 构造器 | 行为 |
|---|---|
| `AnnotationConfigApplicationContext()` | 只创建 BeanFactory、reader、scanner；需要手动 `register()` / `scan()` + `refresh()` |
| `AnnotationConfigApplicationContext(Class<?>... classes)` | `this()` → `register(classes)` → `refresh()` |
| `AnnotationConfigApplicationContext(String... basePackages)` | `this()` → `scan(basePackages)` → `refresh()` |
| `AnnotationConfigApplicationContext(DefaultListableBeanFactory)` | 使用外部传入的 BeanFactory，再创建 reader/scanner |

你图里的写法 `new AnnotationConfigApplicationContext(配置类)` 走的是第二个构造器，反编译后只有三步：

```text
invokespecial <init>()          // 创建 BeanFactory / reader / scanner
invokevirtual register(...)     // 登记配置类 BeanDefinition
invokevirtual refresh()         // 启动容器
```

### 2.2 ⚠️ 普通容器不执行 `ApplicationContextInitializer`

`ApplicationContextInitializer` 是 Spring Boot 在 `SpringApplication.prepareContext()` 里调用的扩展点：

```text
SpringApplication.run()
  → prepareContext()
      → applyInitializers(context)
      → context.refresh()
```

`AnnotationConfigApplicationContext` 本身没有任何地方调用它。你图里把它放在“refresh 之前”不算错——Boot 确实在 refresh 之前执行；但它**不属于这个类的启动流程**。把这两条链路混在一起，后面排查 Boot 启动问题时会找不到断点位置。

### 2.3 `AnnotatedBeanDefinitionReader` 在构造时注册了什么

`AnnotatedBeanDefinitionReader` 的构造器会调用 `AnnotationConfigUtils.registerAnnotationConfigProcessors(...)`，注册注解容器的“基础设施 BeanDefinition”，典型包括：

- `ConfigurationClassPostProcessor`：`BeanDefinitionRegistryPostProcessor`，⑤ 里解析 `@Configuration`、`@ComponentScan`、`@Bean`、`@Import`
- `AutowiredAnnotationBeanPostProcessor`：处理 `@Autowired` / `@Value`
- `CommonAnnotationBeanPostProcessor`：处理 `@Resource`、`@PostConstruct`、`@PreDestroy`
- `EventListenerMethodProcessor`：处理 `@EventListener`

注意：这里**只是登记 BeanDefinition**，这些后处理器在 ⑤ / ⑥ 阶段才会被实例化。

---

## 3. refresh 十二步：每一步为什么在这个位置

### ① `prepareRefresh()`

做三件事：

1. 记录启动时间、设置 `closed=false`、`active=true`
2. `initPropertySources()` + `environment.validateRequiredProperties()`
3. 准备 `earlyApplicationListeners` 和 `earlyApplicationEvents`

**设计动机**：容器还没刷新完，事件广播器（⑧）也还没初始化，但此时可能有组件想发布事件。Spring 的做法不是立刻广播，而是先把事件缓存到 `earlyApplicationEvents`，等 `registerListeners()`（⑩）时再统一发出。

### ② `obtainFreshBeanFactory()`

抽象方法链是：

```text
AbstractApplicationContext#obtainFreshBeanFactory()
  → refreshBeanFactory()
  → getBeanFactory()
```

两类容器的实现完全不同：

| 容器 | `refreshBeanFactory()` 的行为 |
|---|---|
| `AnnotationConfigApplicationContext`（继承 `GenericApplicationContext`） | BeanFactory 在构造器里已经创建好；这里只做“已刷新”标记和 `serializationId` 设置，**不新建 BeanFactory** |
| `ClassPathXmlApplicationContext`（继承 `AbstractRefreshableApplicationContext`） | 每次 refresh 都创建新的 `DefaultListableBeanFactory`，然后加载 XML BeanDefinition |

所以对注解容器来说，② 更像“确认 BeanFactory 可用”，而不是“创建 BeanFactory”。

### ③ `prepareBeanFactory()`

这一步给 BeanFactory 装“基础能力”：

- 类加载器、`StandardBeanExpressionResolver`、`ResourceEditorRegistrar`
- **`ApplicationContextAwareProcessor`**：负责 `EnvironmentAware`、`EmbeddedValueResolverAware`、`ResourceLoaderAware`、`ApplicationEventPublisherAware`、`MessageSourceAware`、`ApplicationStartupAware`、`ApplicationContextAware`
- `ApplicationListenerDetector`
- `ignoreDependencyInterface(...)`：避免把 `BeanNameAware` 这类接口当成可注入依赖
- `registerResolvableDependency(...)`：让 `BeanFactory`、`ResourceLoader`、`ApplicationEventPublisher`、`ApplicationContext` 可以被直接注入
- `LoadTimeWeaverAwareProcessor` 等

**为什么在这里注册 `ApplicationContextAwareProcessor`**：`ApplicationContextAware` 不是通过 `invokeAwareMethods()` 回调的，而是通过 BPP 链回调的。这个 BPP 必须在所有普通 Bean 创建之前存在，所以要在 ③ 就装进去，不能等到 ⑥。

### ④ `postProcessBeanFactory()`

模板方法，给子类一个“在 BFPP/BPP 执行前调整 BeanFactory”的钩子。典型用途：

- Web 容器注册 request / session 作用域
- 子类补充容器特有的基础设施

普通 `AnnotationConfigApplicationContext` 在这里基本是空实现。

### ⑤ `invokeBeanFactoryPostProcessors()`

这一步是**元数据层的总入口**，顺序要拆细：

```text
1. 执行显式添加的 BDRPP（addBeanFactoryPostProcessor 传入的）
2. 从 BeanFactory 中找出 BDRPP，按 PriorityOrdered → Ordered → 其余 依次执行
   └─ ConfigurationClassPostProcessor 在这一组里解析配置类
3. 执行所有 BDRPP 的 postProcessBeanFactory(...)
4. 执行普通 BFPP：PriorityOrdered → Ordered → 其余
```

两类后处理器的区别：

| 类型 | 操作对象 | 典型动作 |
|---|---|---|
| `BeanDefinitionRegistryPostProcessor` | BeanDefinition 注册表 | 新增 BeanDefinition（`@ComponentScan`、`@Bean`、`@Import`） |
| `BeanFactoryPostProcessor` | 已注册的 BeanDefinition | 修改属性、替换占位符、调整作用域 |

**为什么是 ⑤ 而不是更晚**：后续 ⑥ 要找出所有 BPP 的 BeanDefinition，⑪ 要按 BeanDefinition 创建 Bean；如果配置类还没解析，用户的 BPP 和业务 Bean 根本不在注册表里。

> ⚠️ 修正：“这一步只有 Bean 定义，还没有 Bean 实例”这句话**对业务单例成立，对后处理器本身不成立**。BDRPP/BFPP 自身就是通过 `getBean()` 实例化出来的；`ConfigurationClassPostProcessor` 在 `postProcessBeanFactory` 里还会对 `@Configuration` 类做 CGLIB 增强（默认 `proxyBeanMethods=true` 时）。

### ⑥ `registerBeanPostProcessors()`

做三件事：

1. 从 BeanFactory 中找出所有 `BeanPostProcessor` 的 Bean 名称
2. 按 `PriorityOrdered` → `Ordered` → 其余分组，分别排序、实例化、注册
3. 把 `MergedBeanDefinitionPostProcessor` 和 `ApplicationListenerDetector` 重新排到链尾

**为什么必须早于 ⑪**：BPP 是 Bean 创建过程的拦截器。AOP 代理（`AbstractAutoProxyCreator`）、`@Autowired` 注入（`AutowiredAnnotationBeanPostProcessor`）、`@PostConstruct`（`CommonAnnotationBeanPostProcessor`）全部依赖这一步完成注册。

**一个容易踩的坑**：BPP 本身也是 Bean，如果某个 BPP 在字段上 `@Autowired` 注入了业务 Bean，就会导致业务 Bean 在 ⑥ 阶段被提前创建——这时其他 BPP 可能还没注册完，AOP / 事务就可能失效。所以自定义 BPP 应尽量只依赖容器基础设施，不要注入业务 Bean。

### ⑦ `initMessageSource()`

如果容器里存在名为 `messageSource` 的 Bean，就用它；否则创建一个 `DelegatingMessageSource` 作为默认实现，供 `ApplicationContext#getMessage()` 使用。

### ⑧ `initApplicationEventMulticaster()`

如果存在名为 `applicationEventMulticaster` 的 Bean，就用它；否则创建 `SimpleApplicationEventMulticaster`。可以给它配置 `TaskExecutor` 实现异步事件。

**为什么在 ⑩ 之前**：⑩ 要把监听器注册到广播器里，广播器必须先存在。

### ⑨ `onRefresh()`

模板方法：

- `AnnotationConfigApplicationContext`：空实现
- `ServletWebServerApplicationContext`：在这里创建内嵌 WebServer（Tomcat / Jetty / Undertow）
- `GenericApplicationContext` 子类可自定义刷新动作

### ⑩ `registerListeners()`

三件事：

1. 把编程式添加的 `ApplicationListener` 注册到广播器
2. 把容器中 `ApplicationListener` 类型的 Bean 名称登记到广播器
3. 把 ① 中缓存的 `earlyApplicationEvents` 发布出去

**注意**：监听器 Bean 不在这里全部实例化；广播器在真正发布事件时通过 Bean 名称获取它们。这样做可以避免监听器依赖的 Bean 过早创建。

### ⑪ `finishBeanFactoryInitialization()`

这是容器启动里最长的一步，做五件事：

1. 初始化 `conversionService`（如果存在）
2. 添加内嵌值解析器，处理 `${...}` 占位符
3. 初始化所有 `LoadTimeWeaverAware`
4. `setTempClassLoader(null)`，释放临时类加载器
5. `freezeConfiguration()`，然后 `preInstantiateSingletons()`

**`freezeConfiguration()` 到底是什么**：它把 `configurationFrozen` 置为 `true`，并缓存一份 `frozenBeanDefinitionNames` 名称快照，同时清空元数据缓存。它冻结的是**BeanDefinition 视图（尤其是名称列表和类型匹配缓存）**，不是“禁止注册新 BeanDefinition”；后续注册或移除 BeanDefinition 时会清空这个快照。

**`preInstantiateSingletons()` 的遍历规则**：

```text
for (beanName : beanDefinitionNames) {
    如果是 abstract                     → 跳过
    如果不是 singleton                  → 跳过
    如果是 lazyInit                     → 跳过
    如果是 FactoryBean                  → 先创建 FactoryBean 本身
        如果是 SmartFactoryBean 且 isEagerInit → 再调用 getBean(beanName) 创建产品
    否则                                → getBean(beanName)
}
全部创建完后：
for (beanName : beanDefinitionNames) {
    如果单例是 SmartInitializingSingleton → afterSingletonsInstantiated()
}
```

### ⑫ `finishRefresh()`

源码顺序（6.1.17）：

```text
resetCommonCaches()
clearResourceCaches()
initLifecycleProcessor()
getLifecycleProcessor().onRefresh()      // SmartLifecycle#start()
publishEvent(new ContextRefreshedEvent(this))
```

**⚠️ 这里没有“注册关闭钩子”**。`AbstractApplicationContext` 另有一个 `registerShutdownHook()` 方法，Boot 的 `SpringApplication.run()` 会在 refresh 之后调用它；普通 `new AnnotationConfigApplicationContext(...)` 不会自动注册。

**`ContextRefreshedEvent` 的时机含义**：它表示“所有非懒加载单例已经创建完成 + 生命周期处理器已启动”。如果监听器依赖某个懒加载 Bean，不要假设它在此时已经存在。

---

## 4. Bean 创建阶段：把最容易画错的顺序逐行校正

`getBean()` 的主线是：

```text
getBean
  → doGetBean
      → getSingleton(beanName)                     // 命中则直接返回
      → getSingleton(beanName, ObjectFactory)      // 未命中时进入创建
          → createBean
              → resolveBeanClass
              → resolveBeforeInstantiation
              → doCreateBean
                  → createBeanInstance
                  → applyMergedBeanDefinitionPostProcessors
                  → addSingletonFactory            // 注册三级缓存工厂
                  → populateBean
                  → initializeBean
                  → 返回 exposedObject
          → addSingleton(beanName, exposedObject)  // 由 getSingleton 执行，进入一级缓存
```

注意：`addSingleton` 不在 `doCreateBean` 内部，而是在外层 `DefaultSingletonBeanRegistry#getSingleton(String, ObjectFactory)` 中，等 `createBean` 返回后才把成品放进 `singletonObjects`。

### 4.1 实例化前：`createBean` 里的两个分支

```text
resolveBeforeInstantiation
  ├─ determineTargetType → predictBeanType（SmartInstantiationAwareBeanPostProcessor）
  └─ postProcessBeforeInstantiation（InstantiationAwareBeanPostProcessor）
       ├─ 返回 null     → 走正常 doCreateBean
       └─ 返回非 null   → 直接 applyBeanPostProcessorsAfterInitialization，然后返回
```

`postProcessBeforeInstantiation` 的返回值短路是一个很强的扩展点：返回对象后，Spring 不再执行构造器、属性填充和常规初始化，只再走一次“初始化后”处理。AOP 默认不走这条短路，而是在 `postProcessAfterInitialization` 里创建代理。

### 4.2 实例化：`createBeanInstance`

```text
createBeanInstance
  ├─ determineCandidateConstructors（SmartInstantiationAwareBeanPostProcessor）
  │    └─ AutowiredAnnotationBeanPostProcessor 在这里推断构造器
  ├─ 反射调用构造器 new 对象
  └─ 返回 BeanWrapper
```

### 4.3 实例化后、属性填充前：`applyMergedBeanDefinitionPostProcessors`

```text
applyMergedBeanDefinitionPostProcessors
  └─ MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
       ├─ AutowiredAnnotationBeanPostProcessor：缓存 @Autowired / @Value 元数据
       ├─ CommonAnnotationBeanPostProcessor：缓存 @PostConstruct / @PreDestroy / @Resource 元数据
       └─ ApplicationListenerDetector：记录 ApplicationListener 单例
```

**⚠️ 这个回调发生在实例化之后，不是之前。** 因为它需要拿到真实的 Bean 类型（构造器推断结果），才能解析字段 / 方法上的注入元数据。你原图把它画在 `predictBeanType` 之前，会导致两个误解：

1. 以为注解元数据在实例化前就已经缓存好；
2. 以为 `@Autowired` 注入发生在实例化前。

实际顺序是：**先 `new`，再缓存元数据，再 `populateBean` 注入**。

### 4.4 循环依赖：`addSingletonFactory` 先于 `populateBean`

```text
if (earlySingletonExposure) {
    addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
}
populateBean(...);
initializeBean(...);
```

`addSingletonFactory` 只是把 `ObjectFactory` 放进三级缓存 `singletonFactories`，**并不会立刻执行**。只有发生循环依赖、另一个 Bean 通过 `getSingleton(beanName, true)` 来取早期引用时，才会调用 `getEarlyBeanReference`。

三级缓存的分工：

| 缓存 | 内容 | 作用 |
|---|---|---|
| `singletonObjects`（一级） | 初始化完成的成品 Bean | 正常注入直接命中 |
| `earlySingletonObjects`（二级） | 从三级缓存工厂取出的早期引用 | 避免同一个早期 Bean 被重复创建 |
| `singletonFactories`（三级） | `ObjectFactory` 工厂 | 按需生成早期引用，必要时提前生成 AOP 代理 |

`SmartInstantiationAwareBeanPostProcessor#getEarlyBeanReference` 对 AOP 的意义：如果这个 Bean 需要被代理，早期引用必须是代理对象，否则注入到别人手里的是原始对象，后续调用会绕过增强。`AbstractAutoProxyCreator` 会在这里提前创建代理，并在 `postProcessAfterInitialization` 时复用，避免重复代理。

**⚠️ 你原图把它画在 `initializeBean` 之后，是错误的。** 它是在 `populateBean` 之前注册的工厂；真正调用时机取决于是否发生循环依赖。

### 4.5 属性填充：`populateBean`

```text
populateBean
  ├─ InstantiationAwareBeanPostProcessor#postProcessAfterInstantiation
  │    └─ 返回 false 则跳过后续属性填充
  └─ InstantiationAwareBeanPostProcessor#postProcessProperties
       └─ AutowiredAnnotationBeanPostProcessor 注入 @Autowired / @Value
       └─ CommonAnnotationBeanPostProcessor 注入 @Resource
```

### 4.6 初始化：`initializeBean`

```text
initializeBean
  ├─ invokeAwareMethods
  │    ├─ BeanNameAware#setBeanName
  │    ├─ BeanClassLoaderAware#setBeanClassLoader
  │    └─ BeanFactoryAware#setBeanFactory
  ├─ applyBeanPostProcessorsBeforeInitialization
  │    ├─ ApplicationContextAwareProcessor
  │    │    ├─ EnvironmentAware
  │    │    ├─ EmbeddedValueResolverAware
  │    │    ├─ ResourceLoaderAware
  │    │    ├─ ApplicationEventPublisherAware
  │    │    ├─ MessageSourceAware
  │    │    ├─ ApplicationStartupAware
  │    │    └─ ApplicationContextAware
  │    └─ CommonAnnotationBeanPostProcessor → @PostConstruct
  ├─ invokeInitMethods
  │    ├─ InitializingBean#afterPropertiesSet
  │    └─ 自定义 init-method
  └─ applyBeanPostProcessorsAfterInitialization
       └─ AbstractAutoProxyCreator → AOP 代理
```

**⚠️ Aware 回调不是一条连续的链。** 源码把它拆成了两段：

- `invokeAwareMethods()`：只处理 `BeanNameAware`、`BeanClassLoaderAware`、`BeanFactoryAware`
- `postProcessBeforeInitialization()`：由 `ApplicationContextAwareProcessor` 处理 `ApplicationContextAware` 及其他环境类 Aware

所以 `ApplicationContextAware` 在 `BeanFactoryAware` 之后、`@PostConstruct` 之前，但它属于 BPP 链，不属于 `invokeAwareMethods()`。

**AOP 代理的准确位置**：`postProcessAfterInitialization`。此时属性已经注入、初始化方法已经执行，代理包裹的是“初始化完成的目标对象”。如果该 Bean 在循环依赖中已经生成早期代理，这里会复用早期代理，而不是再包一层。

### 4.7 全部单例创建完成：`SmartInitializingSingleton`

`preInstantiateSingletons()` 的创建循环结束后，会再遍历一次所有单例：

```text
if (singleton instanceof SmartInitializingSingleton) {
    ((SmartInitializingSingleton) singleton).afterSingletonsInstantiated();
}
```

它和 `InitializingBean#afterPropertiesSet` 的区别：

| 回调 | 时机 | 能保证什么 |
|---|---|---|
| `afterPropertiesSet` | 单个 Bean 初始化时 | 自己的属性已注入，其他 Bean 不一定创建完 |
| `afterSingletonsInstantiated` | 所有非懒加载单例创建完 | 可以安全地获取其他单例 Bean |

---

## 5. 销毁阶段

`close()` 调用 `doClose()`，顺序是：

```text
doClose()
  ├─ publishEvent(ContextClosedEvent)
  ├─ lifecycleProcessor.onClose()            // SmartLifecycle#stop()
  ├─ destroyBeans()
  │    └─ DefaultSingletonBeanRegistry#destroySingletons()
  │         └─ 每个单例由 DisposableBeanAdapter#destroy() 执行
  ├─ closeBeanFactory()
  ├─ onClose()
  └─ resetCommonCaches()
```

每个 Bean 的销毁顺序：

```text
DestructionAwareBeanPostProcessor#postProcessBeforeDestruction
  └─ 其中 CommonAnnotationBeanPostProcessor 触发 @PreDestroy
DisposableBean#destroy()
AutoCloseable#close()          // Spring 6 对 AutoCloseable 的支持
自定义 destroy-method
```

**注意**：`@PreDestroy` 是 BPP 回调，不是独立的生命周期阶段；`DisposableBean#destroy()` 在它之后，自定义 `destroy-method` 最后。

---

## 6. FactoryBean：独立于普通 Bean 生命周期的产品

`FactoryBean` 是一个“生产对象的工厂”，它有两个身份：

| 调用 | 返回 |
|---|---|
| `getBean("xxx")` | `FactoryBean#getObject()` 返回的产品对象 |
| `getBean("&xxx")` | `FactoryBean` 本身 |

关键差异：

1. `FactoryBean` 本身走完整 Bean 生命周期（实例化、注入、初始化、BPP）。
2. 产品对象由 `getObject()` 创建，**不走完整的 Bean 生命周期**；它只会经过 `postProcessObjectFromFactoryBean`，也就是 `BeanPostProcessor#postProcessAfterInitialization`。
3. `preInstantiateSingletons()` 遍历到 `FactoryBean` 时，先创建 `FactoryBean` 本身；只有 `SmartFactoryBean#isEagerInit()` 返回 `true` 时，才会顺带调用 `getBean(beanName)` 创建产品。

所以 `FactoryBean` 的产品可以被 AOP 增强，但不会经历 `@Autowired` 属性填充、`@PostConstruct` 等常规初始化步骤。

---

## 7. 你原图里的 6 处需要修正

| # | 原图写法 | 实际情况 | 源码依据 | 影响 |
|---|---|---|---|---|
| 1 | `new AnnotationConfigApplicationContext()` 后执行 `ApplicationContextInitializer` | 普通容器不执行；这是 Boot `SpringApplication.prepareContext()` 的机制 | `AnnotationConfigApplicationContext` 构造器只调用 `register()` + `refresh()`；`SpringApplication#prepareContext` 才调用 `applyInitializers` | 排查 Boot 启动扩展点时找错断点 |
| 2 | `postProcessMergedBeanDefinition` 在 `predictBeanType` 之前 / 实例化之前 | 在 `createBeanInstance` **之后**、`populateBean` 之前 | `AbstractAutowireCapableBeanFactory#doCreateBean`：`createBeanInstance` → `applyMergedBeanDefinitionPostProcessors` | 误以为注解元数据在实例化前就绪 |
| 3 | `BeanNameAware → BeanFactoryAware → ApplicationContextAware` 一条链，放在 `initializeBean` 之前 | `BeanNameAware` / `BeanClassLoaderAware` / `BeanFactoryAware` 在 `initializeBean` 内的 `invokeAwareMethods`；`ApplicationContextAware` 由 `ApplicationContextAwareProcessor` 在 `postProcessBeforeInitialization` 中回调 | `AbstractAutowireCapableBeanFactory#initializeBean` + `ApplicationContextAwareProcessor` | 误判 Aware 回调与 BPP 的先后关系 |
| 4 | `getEarlyBeanReference` 在 `initializeBean` 之后 | 在 `populateBean` 之前注册为三级缓存的 `ObjectFactory`，循环依赖发生时才调用 | `doCreateBean` 中 `addSingletonFactory` 位于 `populateBean` 之前；`getSingleton` 触发工厂 | 误判 AOP 早期代理的创建时机 |
| 5 | `finishRefresh` 中“注册关闭钩子” | `finishRefresh` 只做缓存清理、生命周期处理器启动、发布 `ContextRefreshedEvent`；关闭钩子在独立的 `registerShutdownHook()` | `AbstractApplicationContext#finishRefresh` / `#registerShutdownHook` | 误以为普通容器会自动优雅停机 |
| 6 | ⑤ 阶段“没有任何 Bean 实例” | 业务单例没有创建，但 BDRPP/BFPP 自身是 Bean 实例 | `PostProcessorRegistrationDelegate#invokeBeanFactoryPostProcessors` 通过 `getBean` 获取后处理器 | 误判 BFPP 中能否使用依赖注入 |

此外还有两个容易混淆的点：

- **② `obtainFreshBeanFactory()` 不一定是“新建 BeanFactory”**：注解容器在 `GenericApplicationContext` 构造器里就已经创建好 BeanFactory，② 只做刷新标记；XML 容器才会新建。
- **`freezeConfiguration()` 不是“禁止注册 BeanDefinition”**：它冻结的是 BeanDefinition 名称快照和类型匹配缓存；后续注册会清空快照。

---

## 8. 一页速查

### 8.1 12 步速记

```text
① 备刷新  ② 取工厂  ③ 装基础  ④ 子类钩子
⑤ 跑 BFPP（先 BDRPP）  ⑥ 装 BPP
⑦ 消息源  ⑧ 事件广播  ⑨ 子类刷新  ⑩ 监听器
⑪ 建单例  ⑫ 发刷新事件
```

### 8.2 5 个顺序敏感点

1. **⑤ → ⑥**：BPP 的 BeanDefinition 要先被解析出来，才能实例化注册。
2. **⑥ → ⑪**：BPP 没装好就创建业务 Bean，AOP / 事务会失效。
3. **`populateBean` → `initializeBean`**：属性注入完成后才执行初始化回调。
4. **`addSingletonFactory` → `populateBean`**：三级缓存工厂在属性填充前注册，循环依赖才有早期引用可用。
5. **`postProcessAfterInitialization` → 代理可用**：AOP 代理在初始化最后一步生成。

### 8.3 建议断点

| 想观察什么 | 断点位置 |
|---|---|
| refresh 主流程 | `AbstractApplicationContext#refresh` |
| 配置类解析 / BeanDefinition 增加 | `ConfigurationClassPostProcessor#postProcessBeanDefinitionRegistry` |
| BFPP / BPP 注册顺序 | `PostProcessorRegistrationDelegate#invokeBeanFactoryPostProcessors`、`#registerBeanPostProcessors` |
| Bean 创建主线 | `AbstractAutowireCapableBeanFactory#doCreateBean` |
| 属性注入 | `AbstractAutowireCapableBeanFactory#populateBean` |
| 初始化与 AOP 代理 | `AbstractAutowireCapableBeanFactory#initializeBean`、`AbstractAutoProxyCreator#postProcessAfterInitialization` |
| 循环依赖早期引用 | `AbstractAutowireCapableBeanFactory#getEarlyBeanReference` |
| 销毁 | `DisposableBeanAdapter#destroy` |

### 8.4 自测问题

1. 为什么 `ConfigurationClassPostProcessor` 必须在普通 `BeanFactoryPostProcessor` 之前执行？
2. 为什么 `ApplicationContextAware` 不在 `invokeAwareMethods()` 里处理？
3. 如果一个自定义 `BeanPostProcessor` 注入了业务 Bean，可能引发什么启动期问题？
4. `addSingletonFactory` 注册的工厂什么时候才会真正执行？
5. 普通 `AnnotationConfigApplicationContext` 会自动注册 JVM 关闭钩子吗？
6. `FactoryBean` 的产品对象会经历 `@PostConstruct` 吗？
7. `freezeConfiguration()` 之后还能注册 BeanDefinition 吗？冻结的到底是什么？

---

## 9. 与 01 系列其他篇的关系

```text
01-00（本文）   refresh 全流程地图：每一步的位置、顺序和扩展点
  ├─ 01-01       容器概念：IOC / DI、BeanFactory / ApplicationContext、BeanDefinition
  ├─ 01-02       BeanDefinition 从哪来：ConfigurationClassPostProcessor
  └─ 01-03       Bean 怎么创建：getBean → doCreateBean → 初始化 → 循环依赖 → 销毁
```

建议阅读顺序：先读本文建立地图，再带着“这个机制在 refresh 的哪一步”去读 01-01 ~ 01-03；读完后回到本文的 8.4 自测。
