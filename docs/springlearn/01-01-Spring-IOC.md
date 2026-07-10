---
title: 01-01-Spring-IOC
date: 2026-07-09 20:07:05
description: Spring-IOC
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/spring-ioc-min.svg
rank: 89
top_img: false
---
# Spring IOC 知识点（源码深度解析）

> 本文档基于 Spring Boot 3.3.9 + Java 17 + Spring Framework 6.1.17，覆盖 Spring IOC 面试高频知识点和源码核心流程。
> 标注 📖 的为面试高频补充知识点；标注 🔬 的为 Spring 源码深度解析。

---

## 一、IOC 核心概念

### 1.1 什么是 IOC（控制反转）

**一句话概括**：IOC（Inversion of Control）是将**对象的创建、组装、管理**的控制权从调用方转移给容器，调用方只需声明依赖，容器负责注入。

```
传统方式（控制权在调用方）:
  UserService service = new UserServiceImpl();  // 调用方自己 new
  service.setUserDao(new UserDaoImpl());         // 调用方自己组装

IOC 方式（控制权在容器）:
  @Service
  public class UserService {
      @Autowired
      private UserDao userDao;  // 声明依赖，容器负责注入
  }
```

**IOC 不是一种技术，而是一种设计思想**。Spring IOC 是 IOC 思想的具体实现，通过 **DI（依赖注入）** 完成。IOC 和 DI 的关系是**目的与手段**的关系。

### 1.2 什么是 DI（依赖注入）

DI（Dependency Injection）是 IOC 最常见的实现方式。Spring 的 DI 有三种注入方式：

| 注入方式 | 实现 | 优点 | 缺点 |
|---------|------|------|------|
| **构造器注入** | `@Autowired` 在构造器上 | 依赖不可变、保证不为 null、便于单元测试 | 参数多时代码冗长 |
| **Setter 注入** | `@Autowired` 在 setter 上 | 可选依赖、可重新配置 | 对象可能处于不完整状态 |
| **字段注入** | `@Autowired` 在字段上 | 代码简洁、无冗余样板 | 无法不可变、难以单元测试、隐藏依赖 |

```java
// 构造器注入（✅ 推荐，Spring 官方推荐）
@Service
public class UserService {
    private final UserDao userDao;
    private final RoleDao roleDao;

    public UserService(UserDao userDao, RoleDao roleDao) {
        this.userDao = userDao;
        this.roleDao = roleDao;
    }
}

// 字段注入（⚠️ 最常见但非最佳）
@Service
public class UserService {
    @Autowired
    private UserDao userDao;
}
```

### 1.3 IOC 容器是什么

Spring IOC 容器是一个管理 Bean 的工厂，负责**读取配置 → 创建 Bean → 组装 Bean → 管理 Bean 生命周期**。

```
┌──────────────────────────────────────────────────────────────────┐
│                       Spring IOC 容器                             │
│                                                                   │
│   ┌─────────────┐     ┌──────────────────┐                       │
│   │ BeanFactory  │ ←── │ ApplicationContext│  ← 用户使用            │
│   │ (底层接口)    │     │ (高级容器)          │                       │
│   └─────────────┘     └──────────────────┘                       │
│          │                     │                                   │
│          ▼                     ▼                                   │
│   ┌──────────────────────────────────────────┐                   │
│   │              BeanDefinition 注册表          │                   │
│   │  ┌──────────┬──────────┬──────────┐       │                   │
│   │  │userService│ userDao  │ roleDao  │  ...  │                   │
│   │  └──────────┴──────────┴──────────┘       │                   │
│   └──────────────────────────────────────────┘                   │
│          │                                                         │
│          ▼                                                         │
│   ┌──────────────────────────────────────────┐                   │
│   │              单例池 (一级缓存)               │                   │
│   │  ┌──────────┬──────────┬──────────┐       │                   │
│   │  │userService│ userDao  │ roleDao  │  ...  │                   │
│   │  │ (实例)    │ (实例)    │ (实例)    │       │                   │
│   │  └──────────┴──────────┴──────────┘       │                   │
│   └──────────────────────────────────────────┘                   │
│                                                                   │
│   输入：POJO + 配置元数据（XML / 注解 / Java Config）                 │
│   输出：可运行的系统（所有依赖组装完成）                               │
└──────────────────────────────────────────────────────────────────┘
```

### 1.4 BeanFactory vs ApplicationContext

| 维度 | BeanFactory | ApplicationContext |
|------|------------|-------------------|
| **层级** | 底层容器接口 | 高级容器，继承 BeanFactory |
| **实例化时机** | 延迟加载（首次 getBean 时才创建） | 默认预初始化（容器启动时创建非懒加载的 singleton Bean） |
| **功能** | 仅基础 DI | 完整企业功能（AOP、事件、国际化、资源加载） |
| **内存** | 启动快，内存占用小 | 启动慢，但运行期间无创建开销 |
| **使用** | 资源受限环境（移动端、小程序） | 企业应用（绝大多数情况） |

```java
// BeanFactory：延迟加载
BeanFactory factory = new DefaultListableBeanFactory();
// userService 此时并未创建，直到 getBean() 被调用

// ApplicationContext：预实例化
ApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
// 容器启动完成时，非懒加载的 singleton Bean 已经创建完毕
```

---

## 二、Spring IOC 容器启动流程 — 源码深度解析 🔬

> 本节是 IOC 源码中最核心的部分。理解 `refresh()` 方法的 13 个步骤，就理解了 Spring 容器的一半。

### 2.1 refresh() 整体流程图

```
AbstractApplicationContext.refresh()  — Spring 容器的"一键启动键"

┌─────────────────────────────────────────────────────────────────────┐
│  ① prepareRefresh()              — 准备刷新上下文                      │
│     ├── 设置启动时间、关闭标志、激活标志                                │
│     ├── initPropertySources()    — 初始化属性源（可由子类覆盖）           │
│     └── 校验必要属性                                                  │
├─────────────────────────────────────────────────────────────────────┤
│  ② obtainFreshBeanFactory()      — 获取或刷新 BeanFactory              │
│     ├── AbstractRefreshableApplicationContext：重建工厂并加载定义       │
│     ├── GenericApplicationContext：复用构造阶段创建的 BeanFactory        │
│     └── 返回 ConfigurableListableBeanFactory                         │
├─────────────────────────────────────────────────────────────────────┤
│  ③ prepareBeanFactory()          — 准备 BeanFactory                   │
│     ├── 设置 ClassLoader（类加载器）                                    │
│     ├── 设置 SpEL 解析器                                               │
│     ├── 添加 ApplicationContextAwareProcessor — Aware 回调处理         │
│     └── 注册环境相关的单例（environment, systemProperties）              │
├─────────────────────────────────────────────────────────────────────┤
│  ④ postProcessBeanFactory()      — BeanFactory 后置处理（空实现）       │
│     └── 子类覆盖：如 Web 容器注册 request/session 作用域                │
├─────────────────────────────────────────────────────────────────────┤
│  ⑤ invokeBeanFactoryPostProcessors() — ★ 执行 BeanFactoryPostProcessor │
│     ├── BeanDefinitionRegistryPostProcessor：修改 BeanDefinition      │
│     │   └── ConfigurationClassPostProcessor — 解析 @Configuration     │
│     │                                              @ComponentScan     │
│     └── BeanFactoryPostProcessor：修改 BeanFactory 属性                 │
├─────────────────────────────────────────────────────────────────────┤
│  ⑥ registerBeanPostProcessors()  — 注册 BeanPostProcessor             │
│     └── 从容器中找到所有 BPP → 创建实例 → 排序 → 注册到 BeanFactory       │
│         注意：此时大多数普通业务 Bean 仍未创建                              │
├─────────────────────────────────────────────────────────────────────┤
│  ⑦ initMessageSource()           — 初始化国际化资源                     │
├─────────────────────────────────────────────────────────────────────┤
│  ⑧ initApplicationEventMulticaster() — 初始化事件广播器                 │
├─────────────────────────────────────────────────────────────────────┤
│  ⑨ onRefresh()                   — 留给子类的扩展点                     │
│     └── Spring Boot：启动内嵌 Web 服务器（Tomcat / Undertow）            │
├─────────────────────────────────────────────────────────────────────┤
│  ⑩ registerListeners()           — 注册事件监听器                      │
├─────────────────────────────────────────────────────────────────────┤
│  ⑪ finishBeanFactoryInitialization() — ★ 初始化所有单例 Bean           │
│     ├── preInstantiateSingletons() — 实例化所有非延迟的 Bean            │
│     │   └── getBean(beanName)     — ★ Bean 创建的完整流程（见第三章）     │
│     └── 触发 SmartInitializingSingleton 回调                          │
├─────────────────────────────────────────────────────────────────────┤
│  ⑫ finishRefresh()               — 完成刷新                           │
│     ├── 清除缓存                                                       │
│     ├── 初始化 LifecycleProcessor                                    │
│     └── 发布 ContextRefreshedEvent — ★ 容器启动完成事件                  │
├─────────────────────────────────────────────────────────────────────┤
│  ⑬ resetCommonCaches()           — finally 中清理公共缓存（正常/异常都会调用）│
└─────────────────────────────────────────────────────────────────────┘
```

**打断点建议**：从 `AbstractApplicationContext.refresh()` 开始。XML 容器可跟进 `obtainFreshBeanFactory()` 观察定义加载；注解容器应重点跟进 `invokeBeanFactoryPostProcessors()` 中的配置类解析；最后进入 `finishBeanFactoryInitialization()` 观察 Bean 创建。

### 2.2 第②步深入：BeanDefinition 加载全链路 🔬

这是“Spring 如何读取你的配置”的核心环节。不同 `ApplicationContext` 的 BeanDefinition 来源并不相同。以注解驱动的 `AnnotationConfigApplicationContext` 为例：

```
AnnotationConfigApplicationContext(AppConfig.class)
  │
  ├── ① this()
  │     ├── GenericApplicationContext 构造阶段创建 DefaultListableBeanFactory
  │     ├── 创建 AnnotatedBeanDefinitionReader
  │     │     └── AnnotationConfigUtils.registerAnnotationConfigProcessors()
  │     │           ├── ConfigurationClassPostProcessor
  │     │           ├── AutowiredAnnotationBeanPostProcessor
  │     │           ├── CommonAnnotationBeanPostProcessor
  │     │           └── EventListenerMethodProcessor 等基础设施定义
  │     └── 创建 ClassPathBeanDefinitionScanner
  │           └── 供 context.scan(...) 编程式扫描使用
  │
  ├── ② register(AppConfig.class)
  │     └── 将 AppConfig 自身注册为 BeanDefinition
  │
  └── ③ refresh()
        └── invokeBeanFactoryPostProcessors()
              └── ConfigurationClassPostProcessor 开始解析注解配置
```

**为什么 ConfigurationClassPostProcessor 是核心？**

它虽然是 BeanFactoryPostProcessor，但它的真正魔力在于：解析所有 `@Configuration` 类上的 `@ComponentScan`、`@Bean`、`@Import` 等注解，将它们转换为 BeanDefinition 并注册到容器。

```
ConfigurationClassPostProcessor.postProcessBeanDefinitionRegistry()
  │
  └── ConfigurationClassPostProcessor.processConfigBeanDefinitions()
        │
        ├── ConfigurationClassParser.parse()
        │     ├── 解析 @ComponentScan
        │     │     └── ComponentScanAnnotationParser.parse()
        │     │           └── ClassPathBeanDefinitionScanner.doScan()
        │     │                 ├── 通过 ASM 读取 .class 元数据
        │     │                 └── 注册 ScannedGenericBeanDefinition
        │     ├── 解析 @Import / @ImportResource / @PropertySource
        │     └── 收集 @Bean 方法并构建 ConfigurationClass 模型
        │
        └── ConfigurationClassBeanDefinitionReader.loadBeanDefinitions()
              ├── 将 @Bean 方法注册为 ConfigurationClassBeanDefinition
              ├── 注册 imported 配置类
              ├── 加载 @ImportResource
              └── 调用 ImportBeanDefinitionRegistrar
```

**面试要点**：构造 `AnnotationConfigApplicationContext` 时只是创建 reader/scanner 并注册启动配置类及基础设施 BeanDefinition；`@ComponentScan`、`@Bean`、`@Import` 的真正解析发生在 `invokeBeanFactoryPostProcessors()` 阶段。

### 2.3 第⑤步深入：BeanFactoryPostProcessor 执行顺序 🔬

```
invokeBeanFactoryPostProcessors()
  │
  ├── 1. 执行通过 API 手动传入的 BeanDefinitionRegistryPostProcessor
  │
  ├── 2. 执行容器中的 BeanDefinitionRegistryPostProcessor
  │     ├── PriorityOrdered
  │     │     └── ConfigurationClassPostProcessor 在这里执行 ★
  │     ├── Ordered
  │     └── 普通处理器（循环发现本阶段新增的 BDRPP）
  │
  ├── 3. 调用上述 BDRPP 的 postProcessBeanFactory()
  │
  └── 4. 执行其余 BeanFactoryPostProcessor
        └── PriorityOrdered → Ordered → 普通
```

**面试陷阱**：`@Configuration` 类本身会被 `ConfigurationClassPostProcessor` 再次解析，这意味着如果你的 `@Configuration` 类也实现了 `BeanFactoryPostProcessor`，它的 `postProcessBeanFactory()` 方法不会在第一次扫描阶段自动执行，而是需要等到它自己的 Bean 实例被创建之后。这就是为什么 Spring 官方建议**不要**在 `@Configuration` 类上同时实现 `BeanFactoryPostProcessor`。

### 2.4 第⑪步深入：preInstantiateSingletons() 🔬

```
DefaultListableBeanFactory.preInstantiateSingletons()
  │
  └── for (String beanName : beanDefinitionNames)
        │
        ├── 跳过抽象类、延迟加载、作用域非 singleton 的 Bean
        │
        ├── if (isFactoryBean(beanName))
        │     └── getBean(FACTORY_BEAN_PREFIX + beanName)
        │          // 创建 FactoryBean 本身，而非其 getObject() 返回的对象
        │
        └── getBean(beanName)  ← ★ 普通 Bean 的完整创建流程（见第三章）
              │
              ├── 从缓存获取（singletonObjects）
              ├── 缓存未命中 → createBean()
              │     ├── resolveBeforeInstantiation() // InstantiationAwareBeanPostProcessor
              │     ├── doCreateBean()
              │     │     ├── createBeanInstance()    // ① 实例化
              │     │     ├── populateBean()          // ② 属性注入 ★
              │     │     └── initializeBean()        // ③ 初始化 ★
              │     │           ├── invokeAwareMethods()
              │     │           ├── applyBeanPostProcessorsBeforeInitialization()
              │     │           │     └── @PostConstruct 在这里由相关 BPP 调用
              │     │           ├── invokeInitMethods()
              │     │           │     └── InitializingBean + 自定义 init-method
              │     │           └── applyBeanPostProcessorsAfterInitialization()
              │     │                 └── 通常在这里生成最终 AOP 代理
              │     └── 放入 singletonObjects 缓存
              └── 返回 Bean 实例
```

---

## 三、Bean 的创建流程 — 三级缓存与循环依赖 🔬

> 这是 Spring IOC 源码中最精彩的设计，也是面试必考点。

### 3.1 三级缓存定义

```java
// DefaultSingletonBeanRegistry.java — Spring 源码
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

    // 一级缓存：完全初始化的 Bean（成品池）
    private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

    // 二级缓存：早期暴露的 Bean（半成品，属性未注入）
    private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

    // 三级缓存：Bean 工厂对象（用于生成早期代理）
    private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

    // 正在创建中的 Bean 名称集合（用于检测循环依赖）
    private final Set<String> singletonsCurrentlyInCreation = Collections.newSetFromMap(new ConcurrentHashMap<>(16));
}
```

**三级缓存的作用**：

| 缓存 | 级别 | 存放内容 | 作用 |
|------|------|---------|------|
| singletonObjects | 一级 | 完整的 Bean 实例 | 所有单例 Bean 的最终存放位置 |
| earlySingletonObjects | 二级 | 未完成注入的早期 Bean | 循环依赖时提供中间状态的对象 |
| singletonFactories | 三级 | ObjectFactory（能生成 Bean 的工厂） | 允许在 Bean 创建早期暴露其引用，**支持生成代理对象** |

### 3.2 为什么是三级而不是二级？

这是面试官最爱追问的问题。关键在于 **AOP 代理的时机**。

```
如果只有两级缓存（去掉 singletonFactories）：

  A 依赖 B，B 依赖 A（循环依赖）

  1. A 创建 → 实例化 A → 放入二级缓存（此时还是原始对象）→ 注入 B
  2. B 创建 → 实例化 B → 从二级缓存拿到 A（原始对象）→ 注入 A → B 完成
  3. A 完成注入 B → A 初始化 → AOP 创建 A 的代理对象

  问题：B 持有的 A 是原始对象，而容器中的 A 是代理对象 → 不一致！★★★

三级缓存解决：

  1. A 创建 → 实例化 A → 放入三级缓存（lambda: getEarlyBeanReference）→ 注入 B
  2. B 创建 → 实例化 B → 从三级缓存获取 A
     → 调用 getEarlyBeanReference() → ★ 在这里判断 A 是否需要 AOP 代理
     → 如果需要，创建早期代理对象返回给 B
     → 放入二级缓存，从三级缓存移除
  3. A 完成注入 B → A 初始化 → AOP 后置处理
     → 发现早期代理已存在 → 使用早期代理（不重复创建）★

  结果：B 持有的 A 和容器中的 A 是同一个代理对象 → 一致 ✅
```

**面试话术**："三级缓存的核心价值不是解决普通循环依赖，而是解决 **AOP 代理下的循环依赖**。`singletonFactories` 中的 `getEarlyBeanReference()` 允许在 Bean 初始化早期就决定是否需要代理，确保循环依赖双方拿到的都是正确版本。"

### 3.3 doCreateBean() 完整流程 🔬

```java
// AbstractAutowireCapableBeanFactory.java — Spring 源码核心逻辑
protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {

    // ──────────── 阶段一：实例化（创建原始对象） ────────────
    BeanWrapper instanceWrapper = createBeanInstance(beanName, mbd, args);
    Object bean = instanceWrapper.getWrappedInstance();

    // ──────────── 阶段二：允许后置处理器修改 BeanDefinition ────────────
    // MergedBeanDefinitionPostProcessor.postProcessMergedBeanDefinition()
    // 例如：AutowiredAnnotationBeanPostProcessor 在这里解析 @Autowired 元数据
    applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);

    // ──────────── 阶段三：提前暴露引用（解决循环依赖的关键） ────────────
    boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences
            && isSingletonCurrentlyInCreation(beanName));
    if (earlySingletonExposure) {
        // ★ 将 lambda 放入三级缓存
        addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
    }

    // ──────────── 阶段四：依赖注入（属性填充） ────────────
    Object exposedObject = bean;
    populateBean(beanName, mbd, instanceWrapper);  // ★ @Autowired 注入在这里发生
    //     └── InstantiationAwareBeanPostProcessor.postProcessProperties()
    //         └── AutowiredAnnotationBeanPostProcessor 处理 @Autowired

    // ──────────── 阶段五：初始化 ────────────
    exposedObject = initializeBean(beanName, exposedObject, mbd);

    // ──────────── 阶段六：循环依赖检查 ────────────
    if (earlySingletonExposure) {
        Object earlySingletonReference = getSingleton(beanName, false);
        if (earlySingletonReference != null) {
            // 如果 initializeBean() 返回了不同的对象（被代理了），但早期暴露的是另一个对象
            // 则以早期暴露的为准，保证引用一致性
            if (exposedObject == bean) {
                exposedObject = earlySingletonReference;
            }
        }
    }

    return exposedObject;
}
```

### 3.4 循环依赖场景全景图谱

> 以下“可解决”描述指 Spring Framework 在 `allowCircularReferences = true` 时的能力。Spring Boot 3.3.9 默认禁止循环依赖；除非显式设置 `spring.main.allow-circular-references=true`，否则普通字段/Setter 循环依赖也会启动失败。生产代码应优先通过重构消除循环依赖，而不是开启该配置。

```
场景一：普通单例 Bean 的字段/Setter 循环依赖 → Framework 条件可解决
  A ←→ B  两者都是普通对象，无需代理

场景二：AOP 代理下的字段/Setter 循环依赖 → Framework 条件可解决
  A ←→ B  其中 A 被 @Transactional / @Aspect 代理

场景三：构造器循环依赖 → ❌ 无法解决 ★
  class A { public A(B b) {} }
  class B { public B(A a) {} }
  原因：构造器注入发生在实例化阶段，此时对象还没有创建出来，无法提前暴露到缓存

场景四：prototype 循环依赖 → ❌ 无法解决
  原因：prototype Bean 不会被放入缓存，每次都是新创建

场景五：@Async + 循环依赖 → ❌ 可能无法解决
  原因：早期暴露的对象可能与 @Async 后处理器生成的最终代理不一致

场景六：@Lazy 打破构造器循环依赖 → ✅ 可解决
  class A { public A(@Lazy B b) {} }
  原理：注入的不是 B 本身，而是 B 的代理，代理在首次使用时才真正获取 B
```

### 3.5 getBean() / doGetBean() 的完整调用链 🔬

```java
// AbstractBeanFactory.getBean() → doGetBean()
protected <T> T doGetBean(String name, @Nullable Class<T> requiredType,
        @Nullable Object[] args, boolean typeCheckOnly) {

    // ① 转换 Bean 名称（处理别名、&前缀）
    String beanName = transformedBeanName(name);

    // ② ★ 从三级缓存中获取
    Object sharedInstance = getSingleton(beanName);
    //    ├── singletonObjects（一级缓存）→ 找到直接返回
    //    ├── earlySingletonObjects（二级缓存）→ 找到直接返回
    //    └── singletonFactories（三级缓存）→ 调用 ObjectFactory.getObject()
    //          └── 生成的 Bean 放入二级缓存，从三级缓存移除

    if (sharedInstance != null && args == null) {
        // ③ 处理 FactoryBean：如果 Bean 是 FactoryBean，调用 getObject()
        bean = getObjectForBeanInstance(sharedInstance, name, beanName, null);
        return bean;
    }

    // ④ 检查循环依赖（prototype 作用域）
    if (isPrototypeCurrentlyInCreation(beanName)) {
        throw new BeanCurrentlyInCreationException(beanName); // 循环依赖异常
    }

    // ⑤ 如果当前容器没有此 BeanDefinition，委派给父容器
    BeanFactory parentBeanFactory = getParentBeanFactory();
    if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
        return parentBeanFactory.getBean(name);
    }

    // ⑥ 标记 bean 正在创建中（循环依赖检测的基础）
    markBeanAsCreated(beanName);

    // ⑦ 合并 BeanDefinition（子定义 + 父定义）
    RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

    // ⑧ 先创建依赖的 Bean（@DependsOn）
    String[] dependsOn = mbd.getDependsOn();
    for (String dep : dependsOn) {
        getBean(dep); // 递归创建
    }

    // ⑨ 根据作用域创建 Bean
    if (mbd.isSingleton()) {
        // 单例：getSingleton() → createBean()
        //      → 放入 singletonObjects
        sharedInstance = getSingleton(beanName, () -> {
            return createBean(beanName, mbd, args);
        });
        bean = getObjectForBeanInstance(sharedInstance, name, beanName, mbd);
    }
    else if (mbd.isPrototype()) {
        // 原型：每次创建新实例
        Object prototypeInstance = createBean(beanName, mbd, args);
        bean = getObjectForBeanInstance(prototypeInstance, name, beanName, mbd);
    }
    else {
        // 其他作用域（request, session, application）
        String scopeName = mbd.getScope();
        Scope scope = this.scopes.get(scopeName);
        Object scopedInstance = scope.get(beanName, () -> {
            return createBean(beanName, mbd, args);
        });
        bean = getObjectForBeanInstance(scopedInstance, name, beanName, mbd);
    }

    return bean;
}
```

**打断点建议**：`doGetBean()` 是跟踪单个 Bean 创建的最佳入口。可以在 `getSingleton(beanName)` 的缓存查询处、`createBean()` 调用处、以及 `getObjectForBeanInstance()` 处分别打断点，跟踪 Bean 从缓存到创建的全过程。

---

## 四、依赖注入的实现机制 🔬

### 4.1 @Autowired 的处理流程

```java
// AutowiredAnnotationBeanPostProcessor — @Autowired 的核心处理器
// 它实现了 InstantiationAwareBeanPostProcessor

// 第一步：在 postProcessMergedBeanDefinition() 中解析需要注入的元数据
//   对于每个 Bean，扫描其字段和方法，找到所有 @Autowired / @Value 标注的注入点
//   将元数据缓存到 InjectionMetadataCache 中

// 第二步：在 postProcessProperties() 中执行实际的注入
public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
    // ① 获取缓存的注入元数据
    InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);

    // ② 执行注入
    metadata.inject(bean, beanName, pvs);
    //     ├── AutowiredFieldElement.inject()    — 字段注入
    //     │     └── beanFactory.resolveDependency()
    //     │           └── doResolveDependency()
    //     │                 ├── 处理 @Value → 解析 SpEL
    //     │                 ├── 处理集合注入 → 按类型找到所有匹配 Bean
    //     │                 └── 处理单值注入 → 按类型找到唯一 Bean
    //     └── AutowiredMethodElement.inject()   — setter 方法注入

    return pvs;
}
```

### 4.2 按类型查找依赖 — resolveDependency() 🔬

```java
// DefaultListableBeanFactory.doResolveDependency()
public Object doResolveDependency(DependencyDescriptor descriptor, ...) {

    // ① 尝试快捷方式：如果描述符可直接解析，直接返回
    Object shortcut = descriptor.resolveShortcut(this);
    if (shortcut != null) return shortcut;

    Class<?> type = descriptor.getDependencyType();

    // ② 处理 @Value 注解
    Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
    if (value != null) {
        // 解析表达式 ${...} 或 SpEL #{...}
        return resolveEmbeddedValue((String) value);
    }

    // ③ 处理集合/数组/Map 类型
    //    → 找到 type 的所有匹配 Bean → 返回 List/Map
    Object multipleBeans = resolveMultipleBeans(descriptor, ...);

    // ④ 处理单值类型 — ★ 核心逻辑
    Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
    //     └── 从容器中找到所有 type 类型的 Bean 名称
    //         └── 过滤掉自身、不匹配的 Bean

    if (matchingBeans.isEmpty()) {
        // ⑤ 没有找到匹配的 Bean
        if (isRequired(descriptor)) {
            raiseNoMatchingBeanFound(type, ...); // → NoSuchBeanDefinitionException
        }
        return null;
    }

    if (matchingBeans.size() > 1) {
        // ⑥ 多个候选 Bean — 候选已先经过 @Qualifier 等规则过滤，再进行消歧
        autowiredBeanName = determineAutowireCandidate(matchingBeans, descriptor);
        //     ├── @Primary
        //     ├── @Priority
        //     └── resolvableDependencies 或依赖名称匹配
        if (autowiredBeanName == null) {
            throw new NoUniqueBeanDefinitionException(type, matchingBeans.keySet());
        }
    }

    // ⑦ 唯一匹配 → 调用 getBean() —— 递归创建！
    return getBean(autowiredBeanName);
}
```

### 4.3 @Resource 与 @Autowired 的区别 📖

| 维度 | @Autowired | @Resource |
|------|-----------|-----------|
| **来源** | Spring 注解 | Jakarta Common Annotations（`jakarta.annotation`） |
| **注入方式** | 默认按类型（byType） | 默认按名称（byName） |
| **处理器** | AutowiredAnnotationBeanPostProcessor | CommonAnnotationBeanPostProcessor |
| **required** | 支持 `required = false` | 不支持，始终注入 |
| **查找逻辑** | 按类型筛选，结合 `@Qualifier`、`@Primary` 等消歧 | 显式名称严格按名称；默认名称在允许回退时可按类型匹配 |
| **适用位置** | 构造器、字段、setter、方法参数 | 字段、setter |

```java
// @Autowired：默认按类型
@Autowired
private UserService userService;  // 按类型找 UserService

@Autowired
@Qualifier("userServiceImpl")     // 按名称精确定位
private UserService userService;

// @Resource：显式指定 name 时按该名称查找
@Resource(name = "userServiceImpl")
private UserService userService;
```

**实际使用建议**：Spring 项目中常优先使用 `@Autowired` 或构造器注入，因为它们与 Spring 生态集成更自然；如果项目希望降低对 Spring 注解的直接依赖，可以考虑 `@Resource`。

---

## 五、Bean 生命周期详解

### 5.1 完整生命周期图

```
┌────────────────────────────────────────────────────────────────────────┤
│                         Spring Bean 完整生命周期                          │
├────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. 实例化 (Instantiation)                                              │
│     └── createBeanInstance()                                            │
│          ├── 通过 Supplier 回调（编程式注册的 instanceSupplier）           │
│          ├── 通过 FactoryMethod（如 @Bean 方法）                          │
│          ├── 通过构造器实例化（候选构造器、单构造器或默认构造器）             │
│          └── InstantiationAwareBeanPostProcessor 可在此返回代理替代实例化   │
│                                                                         │
│  2. 属性注入 (Populate)                                                 │
│     └── populateBean()                                                  │
│          ├── InstantiationAwareBeanPostProcessor.postProcessProperties() │
│          │     └── AutowiredAnnotationBeanPostProcessor → @Autowired 注入│
│          │     └── CommonAnnotationBeanPostProcessor → @Resource 注入    │
│          └── 按名称自动装配（byName）                                     │
│                                                                         │
│  3. Aware 回调                                                          │
│     └── invokeAwareMethods()                                            │
│          ├── BeanNameAware.setBeanName()                                │
│          ├── BeanClassLoaderAware.setBeanClassLoader()                  │
│          └── BeanFactoryAware.setBeanFactory()                          │
│                                                                         │
│  4. BeanPostProcessor 前置处理（初始化前）                                │
│     └── applyBeanPostProcessorsBeforeInitialization()                   │
│          └── ApplicationContextAwareProcessor                           │
│                ├── EnvironmentAware                                     │
│                ├── EmbeddedValueResolverAware                           │
│                ├── ResourceLoaderAware                                  │
│                ├── ApplicationEventPublisherAware                       │
│                ├── MessageSourceAware                                   │
│                └── ApplicationContextAware ★                            │
│          └── InitDestroyAnnotationBeanPostProcessor                     │
│                └── 调用 @PostConstruct                                  │
│                                                                         │
│  5. 初始化 (Initialization)                                             │
│     └── invokeInitMethods()                                             │
│          ├── InitializingBean.afterPropertiesSet()                      │
│          └── 自定义 init-method（@Bean(initMethod = "init")）              │
│                                                                         │
│  6. BeanPostProcessor 后置处理（初始化后）★                               │
│     └── applyBeanPostProcessorsAfterInitialization()                    │
│          └── AbstractAutoProxyCreator.wrapIfNecessary()                 │
│                └── 通常在此生成最终 AOP 代理；循环依赖时可能提前暴露代理       │
│                                                                         │
│  ═══════════════ Bean 就绪，可被使用 ═══════════════                      │
│                                                                         │
│  7. 销毁 (Destruction)                                                  │
│     └── destroy()                                                       │
│          ├── @PreDestroy 方法                                           │
│          ├── DisposableBean.destroy()                                   │
│          └── 自定义 destroy-method                                      │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
```

### 5.2 生命周期中的关键时序陷阱 📖

```
陷阱 1：构造器中不能使用 @Autowired 注入的依赖
  @Service
  public class UserService {
      @Autowired
      private UserDao userDao;  // ❌ 此时还是 null！

      public UserService() {
          userDao.find();  // NPE！构造器先于 @Autowired 执行
      }
  }

  正确方式：使用构造器注入
  @Service
  public class UserService {
      private final UserDao userDao;
      public UserService(UserDao userDao) {  // ✅ 构造器参数由 Spring 传入
          this.userDao = userDao;
          userDao.find();  // 安全
      }
  }

陷阱 2：@PostConstruct 中 this.xxx() 不走 AOP
  @Service
  public class UserService {
      @PostConstruct
      public void init() {
          this.transactionalMethod();  // ❌ 事务不生效！代理尚未创建
      }

      @Transactional
      public void transactionalMethod() { ... }
  }

陷阱 3：ApplicationContextAware 在 @PostConstruct 之前执行
  顺序：构造器 → @Autowired → ApplicationContextAware → @PostConstruct
  所以 @PostConstruct 中可以使用 ApplicationContext
```

---

## 六、Bean 作用域 📖

### 6.1 五种作用域

| 作用域 | 说明 | 使用场景 |
|--------|------|---------|
| **singleton** | 每个 IoC 容器中仅一个实例 | 默认，无状态 Bean（Service、Dao、Controller） |
| **prototype** | 每次获取都创建新实例 | 有状态 Bean、每次任务独立的处理器 |
| **request** | 每个 HTTP 请求一个实例 | Web 应用中的请求级数据 |
| **session** | 每个 HTTP Session 一个实例 | 用户会话数据 |
| **application** | 每个 ServletContext 一个实例 | Web 应用全局数据 |

### 6.2 singleton vs prototype 的选择

```java
// singleton（默认）— 无状态，Spring 容器中仅一份
// 常见于 Controller、Service、Repository
@Service
public class UserService {
    // 无状态，所有线程共享 → 线程安全（前提是不持有可变状态）
}

// prototype — 有状态，每次获取都是新实例
@Scope("prototype")
@Service
public class ExportTask {
    private final List<Object> taskData = new ArrayList<>(); // 实例级状态
    // 每次任务获取一个新实例 → 不同任务互不干扰
}
```

**面试要点**：singleton Bean 中注入 prototype Bean 时，prototype 不会每次都创建新实例（因为 singleton 只在创建时注入一次）。解决方案：

```java
// 方案 1：注入 ApplicationContext，每次手动获取
@Autowired
private ApplicationContext ctx;
public void doExport() {
    ExportService service = ctx.getBean(ExportService.class); // 每次获取新实例
}

// 方案 2：@Lookup 注解（Spring 会通过 CGLIB 生成方法体）
@Lookup
public ExportService getExportService() { return null; }

// 方案 3：使用 ObjectFactory / ObjectProvider
@Autowired
private ObjectProvider<ExportService> provider;
public void doExport() {
    ExportService service = provider.getObject(); // 每次获取新实例
}
```

### 6.3 自定义作用域 📖

```java
// 实现 Scope 接口，实现 get() 和 remove() 方法
// 注册到 Spring 容器
@Component
public class ThreadScope implements Scope {
    private final ThreadLocal<Map<String, Object>> threadScope = ThreadLocal.withInitial(HashMap::new);

    @Override
    public Object get(String name, ObjectFactory<?> objectFactory) {
        Map<String, Object> scope = threadScope.get();
        return scope.computeIfAbsent(name, k -> objectFactory.getObject());
    }

    @Override
    public Object remove(String name) {
        return threadScope.get().remove(name);
    }
}
```

---

## 七、FactoryBean 与 BeanFactory 📖

### 7.1 概念对比

| 维度 | BeanFactory | FactoryBean |
|------|------------|-------------|
| **是什么** | Spring IOC 容器的顶层接口 | 一种特殊的 Bean，用于创建复杂对象 |
| **职责** | 管理所有 Bean 的创建和依赖 | 自定义单个 Bean 的创建逻辑 |
| **核心方法** | `getBean()` | `getObject()` |
| **使用者** | Spring 框架内部 | 开发者和框架（如 MyBatis、Feign） |

### 7.2 FactoryBean 使用示例

```java
// 项目中类似的使用模式（工作流引擎中的复杂对象构建）
@Component
public class SqlSessionFactoryBean implements FactoryBean<SqlSessionFactory> {

    @Override
    public SqlSessionFactory getObject() throws Exception {
        // 复杂的创建逻辑
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    @Override
    public Class<?> getObjectType() {
        return SqlSessionFactory.class;
    }

    @Override
    public boolean isSingleton() {
        return true;  // 单例
    }
}

// 获取方式：
// 1. getBean("sqlSessionFactoryBean") → 返回 SqlSessionFactory（getObject() 的返回值）
// 2. getBean("&sqlSessionFactoryBean") → 返回 SqlSessionFactoryBean 本身 ★
```

**面试要点**：
- `getBean("myFactoryBean")` → 返回 `getObject()` 的对象
- `getBean("&myFactoryBean")` → 返回 FactoryBean 本身（前缀 `&` 是 `BeanFactory.FACTORY_BEAN_PREFIX`）
- MyBatis 的 Mapper 接口、Feign 的客户端接口都是通过 FactoryBean 创建的代理

---

## 八、@Configuration 与 @Bean 的全量模式 vs 轻量模式 🔬

### 8.1 全量模式（Full Mode）

```java
@Configuration  // ★ proxyBeanMethods = true（默认）
public class AppConfig {

    @Bean
    public UserDao userDao() {
        return new UserDaoImpl();
    }

    @Bean
    public UserService userService() {
        // 这里调用 userDao()，返回的是容器中的单例 Bean，而非 new UserDaoImpl()
        return new UserService(userDao());
    }
}
```

**原理**：`@Configuration(proxyBeanMethods = true)` 会使 Spring 通过 CGLIB 生成 AppConfig 的子类代理：

```java
// CGLIB 生成的代理类（伪代码）
public class AppConfig$$SpringCGLIB$$0 extends AppConfig {

    @Override
    public UserDao userDao() {
        // 检查容器中是否有 userDao Bean
        if (beanFactory.containsSingleton("userDao")) {
            return beanFactory.getBean(UserDao.class);  // 返回已有单例
        }
        return super.userDao();  // 首次调用，执行原始方法
    }

    @Override
    public UserService userService() {
        return super.userService(); // 调用父类方法（父类中调 userDao() 已走代理）
    }
}
```

### 8.2 轻量模式（Lite Mode）

```java
@Component  // 或者 @Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    public UserDao userDao() {
        return new UserDaoImpl();
    }

    @Bean
    public UserService userService() {
        // ❌ 这样写每次都会创建新的 UserDaoImpl！
        return new UserService(userDao());
    }
}
```

推荐改为方法参数注入：

```java
@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    public UserDao userDao() {
        return new UserDaoImpl();
    }

    @Bean
    public UserService userService(UserDao userDao) {
        return new UserService(userDao);
    }
}
```

**选择建议**：
- `proxyBeanMethods = true`（默认）：需要在 `@Bean` 方法间保持单例语义 → 更安全，但多一次 CGLIB 代理生成
- `proxyBeanMethods = false`：`@Bean` 方法间无直接调用依赖 → 避免配置类 CGLIB 增强开销

---

## 九、Spring IOC 扩展点 🔬

Spring IOC 提供了丰富的扩展点，理解这些扩展点是开发框架级功能的基础。

### 9.1 BeanFactoryPostProcessor — 修改 BeanDefinition

```java
// 在所有 BeanDefinition 加载完成后、Bean 实例化之前执行
// 适合修改 BeanDefinition 的元数据
@Component
public class MyBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 修改 BeanDefinition、注册新的 Bean、修改属性等
    }
}
```

### 9.2 BeanPostProcessor — 修改 Bean 实例

```java
// 在每个 Bean 初始化前后执行
// Spring AOP、@Autowired 都是基于此实现
public interface BeanPostProcessor {
    // 初始化前（在 @PostConstruct 之前）
    default Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }
    // 初始化后（在 @PostConstruct 之后）→ AOP 代理在此生成
    default Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
```

### 9.3 InstantiationAwareBeanPostProcessor — 控制实例化

```java
// BeanPostProcessor 的子接口，更底层的控制
public interface InstantiationAwareBeanPostProcessor extends BeanPostProcessor {

    // ★ 实例化前：可在此返回代理对象，绕过默认的构造器实例化
    default Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) {
        return null; // null = 继续默认流程
    }

    // 实例化后：可在此修改 Bean（如属性值处理）
    default boolean postProcessAfterInstantiation(Object bean, String beanName) {
        return true; // true = 继续属性注入
    }

    // 属性注入阶段：处理 @Autowired、@Value 等 ★
    default PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        return pvs;
    }
}
```

### 9.4 扩展点实践示例

```java
// 自定义 BeanPostProcessor 的典型场景：
// 1. 租户上下文注入
// 2. 数据权限控制
// 3. 自定义注解的自动装配

// 实战模板：自定义注解处理器
@Component
public class CustomAnnotationBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        // 扫描 bean 上的自定义注解
        Field[] fields = bean.getClass().getDeclaredFields();
        for (Field field : fields) {
            if (field.isAnnotationPresent(CustomInject.class)) {
                field.setAccessible(true);
                // 注入自定义值
            }
        }
        return bean;
    }
}
```

### 9.5 扩展点执行顺序总结

```
容器启动:
  1. BeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry()
  2. 同一批 BeanDefinitionRegistryPostProcessor.postProcessBeanFactory()
  3. 其余 BeanFactoryPostProcessor.postProcessBeanFactory()

Bean 生命周期:
  4. InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation()  ← 可返回代理
  5. 实例化（构造器 / FactoryMethod）
  6. MergedBeanDefinitionPostProcessor.postProcessMergedBeanDefinition()   ← 解析注入元数据
  7. InstantiationAwareBeanPostProcessor.postProcessAfterInstantiation()   ← 决定是否继续属性填充
  8. InstantiationAwareBeanPostProcessor.postProcessProperties()           ← @Autowired 注入
  9. Aware 接口回调
  10. BeanPostProcessor.postProcessBeforeInitialization()                  ← 包含 @PostConstruct
  11. InitializingBean → init-method
  12. BeanPostProcessor.postProcessAfterInitialization()                   ← 通常生成最终 AOP 代理

容器关闭:
  13. @PreDestroy → DisposableBean → destroy-method
```

---

## 十、生产最佳实践

### 10.1 注入方式的选择

```java
// ❌ 字段注入 — 虽然简洁，但有很多缺点
@Service
public class UserService {
    @Autowired
    private UserDao userDao;          // 不能声明为 final
    @Autowired
    private RoleService roleService;  // 依赖越来越多时难以发现
    // 问题：单元测试需要反射注入，依赖过多时没有编译期告警
}

// ✅ 构造器注入 — Spring 官方推荐
@Service
public class UserService {
    private final UserDao userDao;
    private final RoleService roleService;

    public UserService(UserDao userDao, RoleService roleService) {
        this.userDao = userDao;
        this.roleService = roleService;
    }
    // 优点：
    // 1. 依赖不可变（final）
    // 2. 构造器中保证不为 null
    // 3. 依赖过多时构造器会臃肿 → 提示需要重构
    // 4. 单元测试可以直接 new 并传入 mock
}

// ✅ 构造器注入的 Lombok 简化版
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserDao userDao;  // final → Lombok 生成构造器
    private final RoleService roleService;
}
```

### 10.2 避免 Bean 名称冲突

```java
// ❌ 危险：同类型多个 Bean，未指定名称
@Component
public class MessageService { }

@Component
public class AliyunMessageService extends MessageService { }
// Spring Boot 启动时可能不报错，但 @Autowired 注入时抛出 NoUniqueBeanDefinitionException

// ✅ 方案 1：指定 Bean 名称
@Component("messageService")
public class MessageServiceImpl implements IMessageService { }

@Component("aliyunMessageService")
public class AliyunMessageServiceImpl implements IMessageService { }

// ✅ 方案 2：注入时指定名称
@Autowired
@Qualifier("messageService")
private IMessageService messageService;

// ✅ 方案 3：使用 @Primary
@Component
@Primary  // 默认使用的实现
public class DefaultMessageService implements IMessageService { }

// ✅ 方案 4：集合注入
@Autowired
private List<IMessageService> messageServices;  // 注入所有实现
```

### 10.3 构造器循环依赖的最佳解决方案

```java
// 场景：A 和 B 互相依赖，且都使用构造器注入
@Service
public class ServiceA {
    private final ServiceB serviceB;

    public ServiceA(ServiceB serviceB) {
        this.serviceB = serviceB;
    }
}

@Service
public class ServiceB {
    private final ServiceA serviceA;

    public ServiceB(ServiceA serviceA) {
        this.serviceA = serviceA;
    }
}
// 启动报错：BeanCurrentlyInCreationException ★

// ✅ 解决方案 1：@Lazy 打破循环
@Service
public class ServiceA {
    private final ServiceB serviceB;
    public ServiceA(@Lazy ServiceB serviceB) {
        this.serviceB = serviceB;  // 注入的是代理，首次调用时才真正获取
    }
}

// ✅ 解决方案 2：重构设计（最推荐）
// 构造器循环依赖往往是设计问题的信号
// - 提取第三个类 C，A 和 B 都依赖 C
// - 将部分功能合并到一个类
// - 使用事件驱动解耦（@EventListener / ApplicationEventPublisher）
```

### 10.4 Bean 的懒加载策略

```java
// 全局懒加载
spring:
  main:
    lazy-initialization: true  # 所有 Bean 延迟初始

// 包级别懒加载
@ComponentScan(basePackages = "com.example", lazyInit = true)

// 单个 Bean 懒加载
@Component
@Lazy
public class HeavyInitService { ... }

// 仅在特定场景注入懒 Bean
@Autowired
@Lazy
private HeavyInitService heavyInitService;  // 实际使用时才创建
```

### 10.5 优雅关闭最佳实践

```java
// 1. 实现 DisposableBean 或 @PreDestroy
@Service
public class ResourceService implements DisposableBean {

    @Override
    public void destroy() {
        // 释放外部资源：数据库连接池、文件句柄、网络连接等
        closeResources();
    }
}

// 2. 配合 Spring Boot 优雅关闭配置
// application.yml
server:
  shutdown: graceful  # 优雅关闭
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 每个阶段超时 30 秒

// 3. 线程池的优雅关闭
@PreDestroy
public void shutdown() {
    executor.shutdown();
    try {
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            executor.shutdownNow();  // 超时后强制关闭
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
    }
}
```

### 10.6 避免在 @Configuration 中使用字段注入

```java
// ❌ 不推荐：字段注入
@Configuration
public class AppConfig {
    @Autowired
    private DataSource dataSource;  // 字段注入

    @Bean
    public JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource);
    }
}

// ✅ 推荐：方法参数注入
@Configuration
public class AppConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
```

### 10.7 启动性能优化

```java
// 1. 缩小扫描范围
// ❌
@ComponentScan(basePackages = "com.example")
// ✅
@ComponentScan(basePackages = {"com.example.order", "com.example.user"})

// 2. 排除不需要自动配置的类
@SpringBootApplication(exclude = {
    SomeUnusedAutoConfiguration.class
})

// 3. 使用 @Lazy 延迟初始化非核心 Bean

// 4. 关闭不必要的 @Configuration 代理
@Configuration(proxyBeanMethods = false)  // 如果没有跨 @Bean 方法引用
public class AppConfig { ... }
```

---

## 十一、常见面试题及解答思路

### 11.1 Spring IOC 的原理是什么？

**答题框架**：

1. **一句话总结**：IOC 是一种设计思想，将对象创建和依赖管理的控制权从程序转移到 Spring 容器。容器通过读取配置元数据（注解/XML）生成 BeanDefinition，再根据 BeanDefinition 创建 Bean 实例并完成依赖注入。

2. **展开核心流程**：
   - 容器启动 → `refresh()` → 加载 BeanDefinition（`ConfigurationClassPostProcessor`）
   - `invokeBeanFactoryPostProcessors()` → 解析 `@ComponentScan`、`@Bean` 等
   - `finishBeanFactoryInitialization()` → `preInstantiateSingletons()` → `getBean()`
   - `getBean()` → `createBean()` → `doCreateBean()`
     - `createBeanInstance()` → 实例化
     - `populateBean()` → 依赖注入
     - `initializeBean()` → 初始化 + AOP 代理

3. **关键组件**：`DefaultListableBeanFactory`（容器核心）、`BeanDefinition`（Bean 元数据）、`BeanPostProcessor`（扩展点）、三级缓存（循环依赖）

### 11.2 Spring 如何解决循环依赖？

**答题框架**：

1. **先说明前提**：Spring Framework 只有在 `allowCircularReferences = true` 时才会尝试解决部分循环依赖；Spring Boot 3.3.9 默认值为 `false`。

2. **三级缓存是核心机制**：`singletonObjects`（一级）、`earlySingletonObjects`（二级）、`singletonFactories`（三级）。

3. **流程**：A 和 B 通过字段或 Setter 形成单例循环依赖
   - 创建 A → 实例化 A → 将 A 的 ObjectFactory 放入三级缓存 → 注入 B
   - 创建 B → 实例化 B → 注入 A（从三级缓存拿到 A 的早期引用）→ B 完成
   - A 拿到 B → 继续初始化 → A 完成

4. **为什么是三级不是两级**：AOP 代理问题。如果只有两级，B 中注入的 A 可能是原始对象，而容器中的 A 是代理对象，产生不一致。三级缓存通过 `getEarlyBeanReference()` 在早期决定是否暴露代理，保证引用一致。

5. **局限性**：构造器循环依赖和 prototype 循环依赖无法通过三级缓存解决。可以用 `@Lazy` 在部分场景打破依赖，但生产代码应优先重构，而不是开启循环依赖配置。

### 11.3 BeanFactory 和 ApplicationContext 的区别？

| 维度 | BeanFactory | ApplicationContext |
|------|------------|-------------------|
| 实例化时机 | 延迟加载 | 启动时预初始化 |
| 功能 | 基础 DI | AOP、事件、国际化、资源加载 |
| 内存占用 | 小 | 大 |
| 使用场景 | 资源受限 | 99% 的企业应用 |

### 11.4 @Autowired 和 @Resource 的区别？

- `@Autowired`：Spring 注解，默认按类型（byType），可配合 `@Qualifier` 按名称
- `@Resource`：JDK 注解，默认按名称（byName），找不到再按类型
- 处理器不同：`AutowiredAnnotationBeanPostProcessor` vs `CommonAnnotationBeanPostProcessor`

### 11.5 Bean 的生命周期？

```
实例化 → 属性注入 → BeanNameAware → BeanFactoryAware
→ ApplicationContextAware → BeanPostProcessor.before
→ @PostConstruct → InitializingBean → init-method
→ BeanPostProcessor.after（AOP 代理）
→ 使用 → @PreDestroy → DisposableBean → destroy-method
```

### 11.6 @Configuration 中的 proxyBeanMethods 是什么意思？

- `proxyBeanMethods = true`（默认）：CGLIB 代理配置类，保证 `@Bean` 方法间的调用返回的都是容器中的单例
- `proxyBeanMethods = false`：轻量模式，不生成代理，启动更快，但 `@Bean` 方法间调用会创建新实例

### 11.7 Spring 中使用了哪些设计模式？

| 设计模式 | Spring 中的应用 |
|---------|---------------|
| **工厂模式** | `BeanFactory`、`FactoryBean` |
| **单例模式** | Bean 的默认作用域 singleton |
| **代理模式** | AOP、`@Lazy`、`@Configuration` 的 `proxyBeanMethods` |
| **模板模式** | `JdbcTemplate`、`RestTemplate`、`AbstractApplicationContext.refresh()` |
| **观察者模式** | `ApplicationListener`、`@EventListener` |
| **责任链模式** | AOP 的拦截器链、`BeanPostProcessor` 链 |
| **策略模式** | `InstantiationStrategy`（实例化策略） |
| **装饰器模式** | `BeanWrapper` |

### 11.8 如何排查 Bean 未注入的问题？

```bash
# 1. 检查启动日志，搜索 "NoSuchBeanDefinitionException"
# 2. 确认该 Bean 的类在 @ComponentScan 扫描路径下
# 3. 确认类上有 @Component/@Service/@Repository/@Controller 注解
# 4. 如果是第三方库的 Bean，确认是否被 AutoConfiguration 引入
# 5. 检查是否存在多个同类型的 Bean（NoUniqueBeanDefinitionException）
# 6. 使用 @Autowired(required = false) 或 ObjectProvider 避免启动报错
# 7. 启动时添加 debug=true 查看自动配置报告
```

---

## 十二、扩展阅读与深入方向

1. **Spring 源码阅读路线**：`AbstractApplicationContext.refresh()` → `DefaultListableBeanFactory` → `AbstractAutowireCapableBeanFactory.doCreateBean()` → `DefaultSingletonBeanRegistry`（三级缓存）
2. **Spring Boot 自动配置原理**：`@SpringBootApplication` → `@EnableAutoConfiguration` → `AutoConfigurationImportSelector` → `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` → `xxxAutoConfiguration`
3. **Spring 6.x / Spring Boot 3.x 新特性**：AOT 编译（GraalVM Native Image）、虚拟线程支持、ProblemDetail（RFC 7807）
4. **Spring 与 Jakarta EE**：Spring 6 全面迁移到 Jakarta EE 9+ 命名空间（`javax.*` → `jakarta.*`）
5. **IOC 的替代品**：Google Guice（轻量级 DI）、Dagger 2（编译时 DI）、Micronaut（AOT DI）

---

## 十三、调试 Spring IOC 源码的断点建议

| 调试目标 | 断点位置 | 关键类 |
|---------|---------|--------|
| 容器启动入口 | `refresh()` 方法第一行 | `AbstractApplicationContext` |
| BeanDefinition 加载 | `invokeBeanFactoryPostProcessors()` | `AbstractApplicationContext` |
| @ComponentScan 解析 | `doScan()` | `ClassPathBeanDefinitionScanner` |
| @Bean 方法解析 | `loadBeanDefinitionsForConfigurationClass()` | `ConfigurationClassBeanDefinitionReader` |
| Bean 创建入口 | `doGetBean()` | `AbstractBeanFactory` |
| 三级缓存获取 | `getSingleton(beanName, true)` | `DefaultSingletonBeanRegistry` |
| Bean 实例化 | `createBeanInstance()` | `AbstractAutowireCapableBeanFactory` |
| 依赖注入 | `populateBean()` | `AbstractAutowireCapableBeanFactory` |
| @Autowired 处理 | `postProcessProperties()` | `AutowiredAnnotationBeanPostProcessor` |
| AOP 代理生成 | `wrapIfNecessary()` | `AbstractAutoProxyCreator` |
| Bean 初始化 | `initializeBean()` | `AbstractAutowireCapableBeanFactory` |
| 三级缓存 lambda | `addSingletonFactory()` | `AbstractAutowireCapableBeanFactory` |

**调试技巧**：
1. 先写一个最小 Demo（两个相互依赖的 Service），启动调试，跟着 `refresh()` 一步步走
2. 重点跟 `finishBeanFactoryInitialization()` → `preInstantiateSingletons()` → `getBean()` 这条链路
3. 观察三级缓存在循环依赖时的变化：何时放入、何时取出、何时升级
4. 使用 IDEA 的 Evaluate Expression 查看 `singletonObjects`、`earlySingletonObjects`、`singletonFactories` 的内容变化

---

> 本文档编写于 2026-06-24，基于 Spring Boot 3.3.9 + Java 17 + Spring Framework 6.1.17。
> 建议结合 Spring 源码调试，加深对 IOC 容器启动、Bean 创建、循环依赖解决机制的理解。  
> 关联阅读：Spring AOP 知识点（02-01-Spring-AOP.md）、JVM 知识点（JVM.md）。
