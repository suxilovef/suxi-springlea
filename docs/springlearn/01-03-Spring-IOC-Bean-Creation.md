---
title: 01-03-Spring-IOC-Bean-Creation
date: 2026-07-10 12:00:00
description: Spring-IOC-Bean-Creation
tags:
  - Spring
categories:
  - Spring
realm: wujing
cover: /image/post_cover/wujing-spring-bean-creation.svg
rank: 87
top_img: false
---
# Spring IOC 专题：Bean 创建流程源码深度解析

> 本文基于 Spring Boot `3.3.9`、Spring Framework `6.1.17`、Java `17`。
>
> 建议先完成前置学习：`01-01-Spring-IOC.md` 和 `01-02-Spring-IOC-ConfigurationClassPostProcessor.md`。前两篇解决“BeanDefinition 从哪里来”，本文集中解决“BeanDefinition 如何变成最终可使用的 Bean”。

---

## 阅读路径：先抓主线，再查专题

本文内容多，是因为 Bean 创建不是单一方法调用，而是同时牵涉主创建链路、实例化分支、依赖解析、生命周期增强、循环依赖、FactoryBean、Scope 和销毁流程。

不要按“从头背到尾”的方式读。建议分三遍：

```text
第一遍：只读第一部分，先串起 getBean → doGetBean → createBean → doCreateBean
第二遍：读第二部分，补齐实例化、依赖注入、初始化、AOP 和三级缓存
第三遍：按问题查第三、第四部分，FactoryBean、Scope、销毁、调试和面试题都属于专题
```

如果只想先掌握 Bean 创建主线，读到第八章即可。后面的内容不是新的主线，而是主线上的分支、边界和验证方法。

## 第一部分：主线必读
### 一、先建立完整认知

#### 1.1 Bean 创建到底在解决什么问题

配置类解析完成后，容器中保存的是 `BeanDefinition`，不是业务对象。

```text
注解 / XML / 编程式注册
  ↓
BeanDefinition
  ↓
getBean()
  ↓
实例化
  ↓
属性填充与依赖注入
  ↓
初始化回调
  ↓
BeanPostProcessor 增强
  ↓
最终 Bean（可能是代理对象）
```

Bean 创建流程需要同时解决：

- 应该使用哪个构造器、工厂方法或 Supplier 创建对象
- 构造器参数和字段依赖从哪里获取
- `@Autowired`、`@Value`、`@Resource` 在什么阶段执行
- `Aware`、`@PostConstruct`、`InitializingBean` 的执行顺序
- Bean 何时被 AOP 包装成代理
- singleton、prototype、自定义 Scope 如何创建
- `FactoryBean` 返回的是工厂还是产品对象
- 单例循环依赖如何暴露早期引用
- 创建失败后如何清理缓存和依赖关系
- 容器关闭时如何执行销毁回调

#### 1.2 一句话主线

> `doGetBean()` 决定“从哪里得到 Bean”，`createBean()` 决定“是否允许后处理器提前替换 Bean”，`doCreateBean()` 完成“实例化、注入、初始化和销毁注册”。

#### 1.3 必须背下来的调用链

```text
AbstractApplicationContext.refresh()
  └── finishBeanFactoryInitialization(beanFactory)
      └── DefaultListableBeanFactory.preInstantiateSingletons()
          └── getBean(beanName)
              └── AbstractBeanFactory.doGetBean()
                  ├── getSingleton(beanName)                 // 查缓存
                  ├── getMergedLocalBeanDefinition(beanName)
                  ├── 创建 @DependsOn Bean
                  └── getSingleton(beanName, ObjectFactory)
                      └── createBean(beanName, mbd, args)
                          ├── resolveBeforeInstantiation()
                          └── doCreateBean()
                              ├── createBeanInstance()       // 实例化
                              ├── applyMergedBeanDefinitionPostProcessors()
                              ├── addSingletonFactory()      // 提前暴露
                              ├── populateBean()             // 属性填充
                              ├── initializeBean()           // 初始化
                              ├── 早期引用一致性检查
                              └── registerDisposableBeanIfNecessary()
```

#### 1.4 三组容易混淆的概念

| 概念 | 含义 |
|------|------|
| 实例化 Instantiation | 通过构造器、工厂方法或 Supplier 得到原始 Java 对象 |
| 属性填充 Population | 为字段、Setter、属性和方法参数解析并注入依赖 |
| 初始化 Initialization | 执行 Aware、BeanPostProcessor、`@PostConstruct`、初始化方法和代理增强 |

```text
实例化 != 初始化
BeanDefinition != Bean
原始 Bean != 最终暴露 Bean
```

---

### 二、使用最小案例贯穿全文

#### 2.1 示例代码

```java
@Service
public class OrderService implements InitializingBean {

    private final UserService userService;

    @Autowired
    private InventoryService inventoryService;

    @Value("${order.timeout:3000}")
    private long timeout;

    public OrderService(UserService userService) {
        this.userService = userService;
    }

    @PostConstruct
    public void postConstruct() {
        System.out.println("postConstruct");
    }

    @Override
    public void afterPropertiesSet() {
        System.out.println("afterPropertiesSet");
    }

    public void customInit() {
        System.out.println("customInit");
    }

    @Transactional
    public void createOrder() {
    }
}
```

如果不使用 `@Service` 扫描，而改为通过配置类注册并指定自定义初始化方法：

```java
@Bean(initMethod = "customInit")
public OrderService orderService(UserService userService) {
    return new OrderService(userService);
}
```

同一个类不要同时使用 `@Service` 和上述 `@Bean` 注册，否则会得到两个不同的 BeanDefinition。

后续需要持续观察四个对象：

```text
BeanDefinition：描述如何创建 OrderService
原始对象 bean：构造器刚执行完的 OrderService
早期引用 earlyReference：循环依赖时提前暴露的对象或代理
最终对象 exposedObject：初始化完成后放入单例池的对象
```

#### 2.2 推荐的调试配置

```yaml
spring:
  main:
    allow-circular-references: true # 仅用于调试三级缓存，生产环境不要依赖
```

Spring Boot `3.3.9` 默认禁止循环依赖。只有研究循环依赖实验时才建议临时开启该配置。

---

### 三、Bean 创建从哪里开始

#### 3.1 refresh() 中的入口

普通非懒加载单例主要在下面的阶段创建：

```text
AbstractApplicationContext.refresh()
  └── finishBeanFactoryInitialization(beanFactory)
      ├── 设置 ConversionService
      ├── 添加嵌入值解析器
      ├── 初始化 LoadTimeWeaverAware
      ├── 冻结 BeanDefinition 配置
      └── beanFactory.preInstantiateSingletons()
```

`finishBeanFactoryInitialization()` 并不是唯一会触发 Bean 创建的地方。下面这些操作也会触发：

- 用户主动调用 `applicationContext.getBean()`
- 创建其他 Bean 时递归解析依赖
- `BeanFactoryPostProcessor` 阶段错误地提前调用 `getBean()`
- 非懒 Bean 依赖某个懒 Bean
- `@DependsOn` 要求先创建指定 Bean
- 调用 `getBean("factoryBeanName")` 获取 `FactoryBean.getObject()` 返回的对象

#### 3.2 preInstantiateSingletons() 做了什么

Spring Framework `6.1.17` 中，`DefaultListableBeanFactory.preInstantiateSingletons()` 的核心逻辑是：

```java
public void preInstantiateSingletons() throws BeansException {
    List<String> beanNames = new ArrayList<>(this.beanDefinitionNames);

    for (String beanName : beanNames) {
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);

        if (!mbd.isAbstract() && mbd.isSingleton() && !mbd.isLazyInit()) {
            if (isFactoryBean(beanName)) {
                Object bean = getBean(FACTORY_BEAN_PREFIX + beanName);
                if (bean instanceof SmartFactoryBean<?> smartFactoryBean &&
                        smartFactoryBean.isEagerInit()) {
                    getBean(beanName);
                }
            }
            else {
                getBean(beanName);
            }
        }
    }

    // 所有普通单例完成后，再回调 SmartInitializingSingleton
    for (String beanName : beanNames) {
        Object singletonInstance = getSingleton(beanName);
        if (singletonInstance instanceof SmartInitializingSingleton smartSingleton) {
            smartSingleton.afterSingletonsInstantiated();
        }
    }
}
```

关键点：

- 只主动预实例化非抽象、singleton、非 lazy 的 Bean
- prototype 不会在这里批量创建
- 普通 `FactoryBean` 先创建工厂本身，即 `getBean("&beanName")`
- `SmartFactoryBean.isEagerInit()` 返回 true 时才提前创建产品对象
- `SmartInitializingSingleton` 在所有普通单例创建完成后执行，适合做全局单例就绪后的工作

#### 3.3 lazy Bean 是否永远不创建

不是。

```java
@Lazy
@Service
public class ReportService {
}
```

它只是跳过 `preInstantiateSingletons()` 的主动创建。如果出现以下情况，仍会提前创建：

- 被非懒单例直接注入
- 被 `@DependsOn` 引用
- 用户主动调用 `getBean()`
- 某些基础设施逻辑主动查询并实例化它

如果希望注入点本身也保持懒加载，需要在注入点使用 `@Lazy`、`ObjectProvider` 或其他延迟获取机制。

---

### 四、getBean() 与 doGetBean() 主流程

#### 4.1 getBean() 只是入口

常见重载最终都会进入：

```java
protected <T> T doGetBean(
        String name,
        @Nullable Class<T> requiredType,
        @Nullable Object[] args,
        boolean typeCheckOnly) {
}
```

`doGetBean()` 的职责不是单纯“new 一个对象”，而是统一处理：

- Bean 名称转换和别名
- 单例缓存命中
- `FactoryBean` 解引用
- 父子容器委派
- BeanDefinition 合并
- `@DependsOn`
- singleton、prototype、自定义 Scope
- 类型适配

#### 4.2 第一步：转换 Bean 名称

```java
String beanName = transformedBeanName(name);
```

这一步只做一件核心事情：把外部传入的查询名，转换成容器内部真正用于查找 BeanDefinition 和缓存的 beanName。

它会处理：

- 别名转换为最终规范名
- 去掉 `&` 前缀
- 得到 FactoryBean 在容器中的真实 beanName

注意：`&` 不是 BeanName 的一部分，而是一次 `getBean()` 调用的查询语义。

假设容器中注册了一个 FactoryBean：

```java
@Component("myFactory")
class MyFactoryBean implements FactoryBean<Car> {
}
```

调用 `getBean("myFactory")` 时：

```text
原始 name = "myFactory"
transformedBeanName(name) = "myFactory"
```

Spring 用 `"myFactory"` 查到的是 `MyFactoryBean` 工厂对象。但因为原始 `name` 不带 `&`，Spring 会继续调用 `FactoryBean.getObject()`，最终返回工厂生产的 `Car`。

调用 `getBean("&myFactory")` 时：

```text
原始 name = "&myFactory"
transformedBeanName(name) = "myFactory"
```

Spring 内部仍然用 `"myFactory"` 查找，因为容器中没有名为 `"&myFactory"` 的 BeanDefinition。区别在于，原始 `name` 带了 `&`，表示调用方明确要 FactoryBean 工厂本身，所以最终返回 `MyFactoryBean`。

```text
getBean("userService")  → 普通 Bean，返回 UserService
getBean("&userService") → 普通 Bean 带 &，抛 BeanIsNotAFactoryException
getBean("myFactory")    → FactoryBean，返回 FactoryBean.getObject() 产品
getBean("&myFactory")   → FactoryBean 带 &，返回 FactoryBean 工厂本身
```

所以 `transformedBeanName(name)` 解决的是“内部按哪个名字查”，而后面的 `getObjectForBeanInstance()` 才根据原始 `name` 是否带 `&` 决定“返回工厂本身还是工厂产品”。

#### 4.3 第二步：优先查询单例缓存

```java
Object sharedInstance = getSingleton(beanName);

if (sharedInstance != null && args == null) {
    beanInstance = getObjectForBeanInstance(
        sharedInstance, name, beanName, null);
}
```

为什么缓存命中后还要调用 `getObjectForBeanInstance()`？

因为 `getSingleton(beanName)` 命中后，只能说明 Spring 找到了内部缓存里的对象，还不能直接说明这个对象就是调用方最终想要的对象。

这里传入的是转换后的 `beanName`，所以缓存中保存的可能是：

```text
singletonObjects["userService"] = UserService
singletonObjects["myFactory"]   = MyFactoryBean
```

如果命中的是普通 Bean，`getObjectForBeanInstance()` 直接返回它。如果命中的是 `FactoryBean`，还要继续看原始 `name`：

```text
原始 name = "myFactory"   → 返回 FactoryBean.getObject() 产品
原始 name = "&myFactory"  → 返回 MyFactoryBean 工厂本身
```

所以缓存命中解决的是“工厂对象或普通对象是否已经存在”，`getObjectForBeanInstance()` 解决的是“这次调用最终应该暴露哪个对象”。

#### 4.4 第三步：prototype 循环依赖检查

```java
if (isPrototypeCurrentlyInCreation(beanName)) {
    throw new BeanCurrentlyInCreationException(beanName);
}
```

prototype 每次获取都创建新实例，没有单例缓存和早期暴露机制，因此 Spring 无法使用三级缓存解决 prototype 循环依赖。

#### 4.5 第四步：父容器委派

```java
BeanFactory parentBeanFactory = getParentBeanFactory();

if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
    return parentBeanFactory.getBean(name);
}
```

规则是：

- 当前容器存在本地 BeanDefinition：使用当前容器
- 当前容器不存在：尝试父容器
- 子容器可以覆盖父容器同名 Bean
- 父容器不能反向获取子容器 Bean

#### 4.6 第五步：标记 Bean 即将创建

```java
if (!typeCheckOnly) {
    markBeanAsCreated(beanName);
}
```

这一步不创建对象，它只改变容器的内部状态：告诉 Spring “这个 Bean 现在要进入真正创建流程了”。

在此之前，容器可能只是为了类型匹配、候选判断而读取过 BeanDefinition，并缓存过一次 merged BeanDefinition。真正创建前，Spring 会把当前 Bean 记入 `alreadyCreated`，并在必要时清理旧的 merged BeanDefinition 缓存。

这样下一步 `getMergedLocalBeanDefinition(beanName)` 拿到的就是最终版本，而不是类型检查阶段留下的旧合并结果。`typeCheckOnly` 为 true 时跳过这一步，因为单纯类型检查不应该把 Bean 标记为已经进入创建阶段。

#### 4.7 第六步：获取合并后的 RootBeanDefinition

```java
RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
checkMergedBeanDefinition(mbd, beanName, args);
```

后续创建流程统一使用 `RootBeanDefinition`，因为它已经综合了：

- 父 BeanDefinition 配置
- 当前 BeanDefinition 覆盖项
- scope、lazy、dependsOn
- 构造器参数和属性值
- factoryBeanName、factoryMethodName
- initMethod、destroyMethod
- methodOverrides

#### 4.8 第七步：先创建 dependsOn Bean

```java
String[] dependsOn = mbd.getDependsOn();
for (String dep : dependsOn) {
    registerDependentBean(dep, beanName);
    getBean(dep);
}
```

`@DependsOn` 表示创建顺序和销毁顺序依赖，不等同于属性注入。

```java
@Component
@DependsOn("databaseInitializer")
public class OrderService {
}
```

Spring 还会检测 `dependsOn` 形成的显式循环，并抛出 `BeanCreationException`。

#### 4.9 第八步：根据 Scope 创建

##### singleton

```java
sharedInstance = getSingleton(beanName, () -> {
    return createBean(beanName, mbd, args);
});
```

同一个 BeanFactory 内只创建一次，并由单例注册表管理。

##### prototype

```java
beforePrototypeCreation(beanName);
Object prototypeInstance = createBean(beanName, mbd, args);
afterPrototypeCreation(beanName);
```

每次 `getBean()` 都创建新实例，容器不负责完整销毁生命周期。

##### 自定义 Scope

```java
Scope scope = this.scopes.get(scopeName);
Object scopedInstance = scope.get(beanName, () -> {
    return createBean(beanName, mbd, args);
});
```

request、session 等作用域的实例保存位置由对应 `Scope` 实现决定。

#### 4.10 doGetBean() 流程图

```text
doGetBean(name)
  ├── transformedBeanName(name)
  ├── getSingleton(beanName)
  │   └── 命中 → getObjectForBeanInstance()
  ├── prototype 循环检查
  ├── 父容器委派
  ├── markBeanAsCreated()
  ├── getMergedLocalBeanDefinition()
  ├── 创建 dependsOn Bean
  ├── singleton
  │   └── getSingleton(beanName, ObjectFactory)
  │       └── createBean()
  ├── prototype
  │   └── createBean()
  ├── custom scope
  │   └── scope.get(beanName, ObjectFactory)
  ├── getObjectForBeanInstance()
  └── adaptBeanInstance()
```

---

### 五、BeanDefinition 合并与依赖关系

#### 5.1 为什么需要 merged BeanDefinition

Spring 支持 BeanDefinition 继承：

```xml
<bean id="baseService" abstract="true">
    <property name="timeout" value="3000"/>
</bean>

<bean id="orderService" parent="baseService"
      class="com.example.OrderService"/>
```

即使主要使用注解配置，Spring 内部也统一转换为 `RootBeanDefinition`，让后续创建流程不再区分原始定义类型。

#### 5.2 合并结果什么时候缓存

`getMergedLocalBeanDefinition()` 会先查 `mergedBeanDefinitions` 缓存。

```text
缓存命中且没有 stale 标记 → 直接返回 RootBeanDefinition
缓存不存在或已经 stale     → 重新合并 BeanDefinition，再放入缓存
```

但这个缓存不是越早越好。类型检查、候选判断等流程可能提前触发合并；如果后续 BeanDefinition 又被修改，早期合并结果就可能过期。

所以真正创建前会先执行 `markBeanAsCreated(beanName)`：它相当于划线，表示“从这里开始，这个 Bean 的创建元数据应该稳定下来”。必要时会清掉旧 merged BeanDefinition，让后续创建流程基于最终定义重新合并。

#### 5.3 dependentBeanMap 与 dependenciesForBeanMap

Spring 会维护两个方向的依赖关系：

```text
dependentBeanMap:
  userService -> [orderService]

dependenciesForBeanMap:
  orderService -> [userService]
```

用途包括：

- 检测 `@DependsOn` 循环
- 创建失败时清理依赖 Bean
- 容器关闭时先销毁依赖方，再销毁被依赖方
- 判断早期引用是否已被其他 Bean 使用

---

### 六、单例创建器 getSingleton()

#### 6.1 两个同名方法承担不同职责

```java
getSingleton(beanName)
```

用于查询一级、二级、三级缓存。

```java
getSingleton(beanName, singletonFactory)
```

用于保证一个单例只被创建一次，并管理创建前后状态、异常清理和最终入池。

#### 6.2 真正创建单例的模板

```java
public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
    synchronized (this.singletonObjects) {
        Object singletonObject = this.singletonObjects.get(beanName);

        if (singletonObject == null) {
            beforeSingletonCreation(beanName);
            boolean newSingleton = false;

            try {
                singletonObject = singletonFactory.getObject();
                newSingleton = true;
            }
            catch (IllegalStateException ex) {
                // 创建期间单例可能通过其他路径已经注册
                singletonObject = this.singletonObjects.get(beanName);
                if (singletonObject == null) {
                    throw ex;
                }
            }
            finally {
                afterSingletonCreation(beanName);
            }

            if (newSingleton) {
                addSingleton(beanName, singletonObject);
            }
        }

        return singletonObject;
    }
}
```

#### 6.3 beforeSingletonCreation()

```java
protected void beforeSingletonCreation(String beanName) {
    if (!this.inCreationCheckExclusions.contains(beanName) &&
            !this.singletonsCurrentlyInCreation.add(beanName)) {
        throw new BeanCurrentlyInCreationException(beanName);
    }
}
```

它把 Bean 名称加入“正在创建集合”。重复加入通常意味着出现了无法通过早期引用处理的创建路径。

#### 6.4 创建失败为什么必须清理

创建过程中可能已经产生：

- 三级缓存 ObjectFactory
- 二级缓存早期引用
- dependentBeanMap 依赖记录
- 销毁回调
- 被递归创建的其他单例

如果失败后不清理，下一次创建会读到污染状态。

`doGetBean()` 中创建单例的回调捕获到 `BeansException` 后，会调用 `destroySingleton(beanName)` 清理缓存和销毁信息。`getSingleton()` 同时会收集 suppressed exceptions，作为 `BeanCreationException` 的关联原因帮助定位根因。

#### 6.5 addSingleton() 的最终动作

```java
protected void addSingleton(String beanName, Object singletonObject) {
    synchronized (this.singletonObjects) {
        this.singletonObjects.put(beanName, singletonObject);
        this.singletonFactories.remove(beanName);
        this.earlySingletonObjects.remove(beanName);
        this.registeredSingletons.add(beanName);
    }
}
```

Bean 进入一级缓存时，二级和三级缓存中的同名数据必须删除。

### 七、createBean()：正式创建前的最后一道门

#### 7.1 createBean() 的核心职责

```java
protected Object createBean(
        String beanName,
        RootBeanDefinition mbd,
        @Nullable Object[] args) {

    RootBeanDefinition mbdToUse = mbd;

    Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
    mbdToUse.prepareMethodOverrides();

    Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
    if (bean != null) {
        return bean;
    }

    return doCreateBean(beanName, mbdToUse, args);
}
```

可以把它理解成两步：

1. 准备 BeanClass、方法覆盖等创建元数据。
2. 给 `InstantiationAwareBeanPostProcessor` 一次“绕过默认创建流程”的机会。

#### 7.2 resolveBeforeInstantiation()

调用链：

```text
resolveBeforeInstantiation()
  ├── applyBeanPostProcessorsBeforeInstantiation()
  │   └── InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation()
  └── 如果返回非 null
      └── applyBeanPostProcessorsAfterInitialization()
```

如果某个后处理器在实例化前直接返回对象，Spring 将不会执行：

- `doCreateBean()`
- 默认构造器或工厂方法实例化
- `populateBean()`
- 普通初始化流程

这是一种“短路创建”。

#### 7.3 它是否就是普通 AOP 代理入口

不完全是。

普通基于 Bean 方法匹配的 AOP 代理，通常在 `postProcessAfterInitialization()` 中创建。`AbstractAutoProxyCreator.postProcessBeforeInstantiation()` 主要用于自定义 `TargetSource` 等可以提前确定代理目标的特殊场景。

面试时不要简单回答：“所有 AOP 代理都在实例化前创建”。

---

### 八、doCreateBean()：Bean 创建的核心模板

#### 8.1 源码主线

```java
protected Object doCreateBean(
        String beanName,
        RootBeanDefinition mbd,
        @Nullable Object[] args) {

    BeanWrapper instanceWrapper = createBeanInstance(beanName, mbd, args);
    Object bean = instanceWrapper.getWrappedInstance();
    Class<?> beanType = instanceWrapper.getWrappedClass();

    applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);

    boolean earlySingletonExposure =
        mbd.isSingleton() &&
        this.allowCircularReferences &&
        isSingletonCurrentlyInCreation(beanName);

    if (earlySingletonExposure) {
        addSingletonFactory(beanName,
            () -> getEarlyBeanReference(beanName, mbd, bean));
    }

    Object exposedObject = bean;
    populateBean(beanName, mbd, instanceWrapper);
    exposedObject = initializeBean(beanName, exposedObject, mbd);

    // 处理早期引用与最终对象一致性
    // ...

    registerDisposableBeanIfNecessary(beanName, bean, mbd);
    return exposedObject;
}
```

#### 8.2 八个关键阶段

```text
1. createBeanInstance()                    创建原始对象
2. 确定 beanType                           暂存类型信息
3. applyMergedBeanDefinitionPostProcessors 解析并缓存注入元数据
4. addSingletonFactory()                   暴露三级缓存工厂
5. populateBean()                          填充属性和依赖
6. initializeBean()                        执行初始化与代理增强
7. 早期引用一致性检查                       统一代理或检测原始对象泄露
8. registerDisposableBeanIfNecessary()     注册销毁逻辑
```

#### 8.3 为什么先解析注入元数据再暴露早期引用

`MergedBeanDefinitionPostProcessor` 会提前分析 BeanClass，例如：

- 哪些字段标注了 `@Autowired`
- 哪些方法需要依赖注入
- 哪些成员标注了 `@Resource`
- 哪些销毁方法需要注册

这一步发生在 `createBeanInstance()` 之后，因为只有对象实例化完成后，Spring 才能确定真实 Bean 类型。

顺序是：

```text
createBeanInstance()                       得到原始对象和真实 Class
applyMergedBeanDefinitionPostProcessors()  解析并缓存字段、方法、生命周期元数据
addSingletonFactory()                      必要时允许其他 Bean 拿早期引用
populateBean()                             按刚才缓存的元数据执行注入
```

关键不是“已经注入了”，而是“注入计划已经确定了”。如果后面因为循环依赖暴露早期引用，外部拿到的仍是一个尚未填充完成的对象；但 Spring 自己已经知道后续要给它注入哪些字段、调用哪些生命周期方法。

#### 8.4 为什么注册销毁回调时传入原始 bean

销毁适配器需要识别目标类上的：

- `@PreDestroy`
- `DisposableBean`
- 自定义 destroy-method

代理对象可能隐藏目标对象的具体销毁方法，因此 Spring 会结合原始对象、BeanDefinition 和后处理器保存销毁信息。

---

## 第二部分：关键分支
### 九、createBeanInstance()：到底怎样实例化对象

#### 9.1 实例化策略总览

Spring Framework `6.1.17` 的主要分支顺序如下：

```text
createBeanInstance()
  ├── instanceSupplier != null
  │   └── obtainFromSupplier()
  ├── factoryMethodName != null
  │   └── instantiateUsingFactoryMethod()
  ├── 已缓存 resolvedConstructorOrFactoryMethod
  │   ├── 需要自动装配 → autowireConstructor()
  │   └── 不需要 → instantiateBean()
  ├── determineConstructorsFromBeanPostProcessors()
  ├── AUTOWIRE_CONSTRUCTOR / 构造参数存在
  │   └── autowireConstructor()
  ├── preferredConstructors
  │   └── autowireConstructor()
  └── instantiateBean() 使用默认构造器
```

#### 9.2 Supplier 实例化

```java
RootBeanDefinition bd = new RootBeanDefinition(OrderService.class);
bd.setInstanceSupplier(() -> new OrderService(userService));
registry.registerBeanDefinition("orderService", bd);
```

对应源码入口：

```java
obtainFromSupplier(instanceSupplier, beanName, mbd);
```

`Supplier` 主要用于编程式 BeanDefinition 注册，不等同于普通 `@Bean` 方法。

#### 9.3 @Bean 工厂方法实例化

```java
@Configuration
public class AppConfig {

    @Bean
    public OrderService orderService(UserService userService) {
        return new OrderService(userService);
    }
}
```

对应 BeanDefinition 元数据：

```text
factoryBeanName = appConfig
factoryMethodName = orderService
```

调用链：

```text
createBeanInstance()
  └── instantiateUsingFactoryMethod()
      └── ConstructorResolver.instantiateUsingFactoryMethod()
          ├── 获取 factoryBean
          ├── 查找同名候选方法
          ├── 解析方法参数
          ├── 选择最佳 factoryMethod
          └── InstantiationStrategy.instantiate()
```

静态工厂方法没有 `factoryBeanName`，直接在 BeanClass 上调用静态方法。

#### 9.4 默认构造器实例化

如果没有 Supplier、工厂方法、需要自动装配的构造器或显式构造参数，最终进入：

```java
instantiateBean(beanName, mbd);
```

默认使用 `InstantiationStrategy`：

```text
SimpleInstantiationStrategy
  └── 普通反射构造

CglibSubclassingInstantiationStrategy
  └── 存在 lookup-method / replaced-method 时生成 CGLIB 子类
```

#### 9.5 BeanWrapper 的作用

实例化结果不是直接返回对象，而是包装为 `BeanWrapper`：

```java
BeanWrapper bw = new BeanWrapperImpl(beanInstance);
```

它负责：

- 保存包装对象
- 获取属性描述符
- 类型转换
- 设置属性值
- 支持嵌套属性路径
- 集成 ConversionService 和 PropertyEditor

---

### 十、构造器选择与参数解析

#### 10.1 单构造器为什么可以不写 @Autowired

```java
@Service
public class OrderService {

    private final UserService userService;

    public OrderService(UserService userService) {
        this.userService = userService;
    }
}
```

当类只有一个非默认构造器时，`AutowiredAnnotationBeanPostProcessor.determineCandidateConstructors()` 会把它作为候选构造器。

#### 10.2 多构造器规则

```java
@Service
public class OrderService {

    public OrderService() {
    }

    @Autowired
    public OrderService(UserService userService) {
    }
}
```

核心规则：

- 可以存在多个 `@Autowired(required = false)` 构造器
- 最多只能有一个 required 构造器
- required 构造器存在时，不允许再声明其他 `@Autowired` 构造器
- 有默认构造器时，它可以作为非 required 候选的回退
- 没有 `@Autowired` 且只有一个带参构造器时，使用该构造器

#### 10.3 ConstructorResolver 如何选择最佳构造器

候选构造器不会简单按参数数量匹配，核心过程是：

```text
1. 收集候选构造器
2. 参数多的构造器优先尝试
3. 解析显式 constructorArgumentValues
4. 对缺失参数创建 DependencyDescriptor
5. 调用 resolveDependency() 自动装配参数
6. 计算类型差异权重 typeDifferenceWeight
7. 选择权重最低的构造器
8. 权重相同且无法消歧时抛出歧义异常
9. 缓存 resolvedConstructorOrFactoryMethod 和参数信息
```

#### 10.4 贪婪构造器不等于无条件选择参数最多的

参数更多的构造器只有在所有依赖都能够解析、类型转换成功且权重更优时才会被选中。

```java
@Autowired(required = false)
public OrderService(UserService userService, MissingService missingService) {
}

@Autowired(required = false)
public OrderService(UserService userService) {
}
```

如果 `MissingService` 不存在且第一个构造器不是 required，Spring 可以继续尝试参数较少的候选构造器。

#### 10.5 构造器循环依赖为什么无法解决

```text
创建 A
  └── 调用 A(B) 前必须先得到 B
      └── 创建 B
          └── 调用 B(A) 前必须先得到 A
              └── 此时 A 尚未完成实例化，无法暴露引用
```

三级缓存的放入时机在 `createBeanInstance()` 之后，因此构造器执行完成前不存在可暴露的 A 对象。

---

### 十一、MergedBeanDefinitionPostProcessor

#### 11.1 为什么需要这个阶段

实例化完成后，Spring 已经知道真实 Bean 类型，可以让后处理器解析类级元数据：

```java
applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
```

典型实现：

| 实现 | 主要职责 |
|------|----------|
| `AutowiredAnnotationBeanPostProcessor` | 解析 `@Autowired`、`@Value` 字段和方法 |
| `CommonAnnotationBeanPostProcessor` | 解析 `@Resource` 等通用注解 |
| `InitDestroyAnnotationBeanPostProcessor` | 解析 `@PostConstruct`、`@PreDestroy` |

#### 11.2 此时只是解析，不是注入

以 `AutowiredAnnotationBeanPostProcessor` 为例：

```text
postProcessMergedBeanDefinition()
  └── findAutowiringMetadata()
      ├── 扫描字段
      ├── 扫描方法
      ├── 构建 AutowiredFieldElement
      ├── 构建 AutowiredMethodElement
      └── 缓存 InjectionMetadata
```

真正注入发生在后面的 `postProcessProperties()`。

#### 11.3 为什么元数据要缓存

反射扫描字段和方法成本较高。对于 prototype Bean，如果每次创建都重新扫描，会产生额外开销。

缓存通常以 beanName 或目标 Class 为键；类发生变化或缓存失效时才重新构建。

---

### 十二、populateBean()：属性填充与依赖注入

#### 12.1 完整顺序

```text
populateBean()
  ├── InstantiationAwareBeanPostProcessor.postProcessAfterInstantiation()
  │   └── 返回 false 可终止后续属性填充
  ├── AUTOWIRE_BY_NAME
  │   └── autowireByName()
  ├── AUTOWIRE_BY_TYPE
  │   └── autowireByType()
  ├── InstantiationAwareBeanPostProcessor.postProcessProperties()
  │   ├── @Autowired
  │   ├── @Value
  │   └── @Resource
  ├── checkDependencies()
  └── applyPropertyValues()
```

#### 12.2 postProcessAfterInstantiation() 可以阻止注入

```java
if (!bp.postProcessAfterInstantiation(bean, beanName)) {
    return;
}
```

这是框架级扩展点。返回 false 后，Spring 不再继续普通属性填充。

业务项目一般不应随意使用，否则容易造成字段为空且排查困难。

#### 12.3 byName 与 byType 是 BeanDefinition 级自动装配

```java
mbd.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_NAME);
mbd.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
```

它们与 `@Autowired` 不是同一套入口：

- byName / byType 由 BeanDefinition 的 autowireMode 驱动
- `@Autowired` 由 `AutowiredAnnotationBeanPostProcessor` 驱动

现代注解项目主要使用后者。

#### 12.4 @Autowired 字段注入

```text
AutowiredAnnotationBeanPostProcessor.postProcessProperties()
  └── InjectionMetadata.inject()
      └── AutowiredFieldElement.inject()
          ├── 创建 DependencyDescriptor
          ├── beanFactory.resolveDependency()
          ├── registerDependentBean()
          └── field.set(bean, value)
```

因此字段注入一定发生在构造器执行之后。

#### 12.5 @Autowired 方法注入

```java
@Autowired
public void configure(UserService userService, AuditService auditService) {
}
```

Spring 会分别解析每个方法参数，然后通过反射调用该方法。

Setter 注入只是方法注入的一种常见形式，方法名称不必须以 `set` 开头。

#### 12.6 @Value 的本质

```java
@Value("${order.timeout:3000}")
private long timeout;
```

它仍然通过 DependencyDescriptor 解析，只是候选值来自：

```text
AutowireCandidateResolver.getSuggestedValue()
  ├── resolveEmbeddedValue()          处理 ${...}
  ├── evaluateBeanDefinitionString()  处理 #{...}
  └── TypeConverter.convertIfNecessary()
```

#### 12.7 applyPropertyValues()

BeanDefinition 中显式配置的属性最终在这里写入：

```text
MutablePropertyValues
  ├── RuntimeBeanReference → resolveValueIfNecessary()
  ├── TypedStringValue → 类型转换
  ├── 集合、数组、Map → 递归解析
  └── BeanWrapper.setPropertyValues()
```

---

### 十三、resolveDependency()：依赖到底怎样找到

#### 13.1 doResolveDependency() 五步

Spring Framework `6.1.17` 的主线：

```text
Step 1：descriptor.resolveShortcut()
  └── 如果依赖描述符已经记录了目标 beanName，直接 getBean(beanName, type)

Step 2：处理 @Value
  └── 占位符 / SpEL / 类型转换

Step 3：处理 Stream、数组、Collection、List、Set、Map
  └── resolveMultipleBeans()

Step 4：查找并确定单个候选
  ├── findAutowireCandidates()
  └── determineAutowireCandidate()

Step 5：解析最终候选实例并校验类型
  └── descriptor.resolveCandidate() → getBean()
```

Step 1 不是直接复用上一次注入得到的对象，而是复用“上次已经确定的 Bean 名称”。

例如某个 `@Autowired` 字段第一次解析后确认应该注入 `userService`，后续同一注入点可以通过 `ShortcutDependencyDescriptor` 直接调用 `getBean("userService", UserService.class)`，避免再次走完整候选筛选和消歧。

#### 13.2 findAutowireCandidates()

它先找到目标类型的候选 Bean 名称，然后逐个判断：

- 不能把当前 Bean 自己作为普通依赖注入给自己
- 泛型 `ResolvableType` 是否匹配
- `AutowireCandidateResolver.isAutowireCandidate()` 是否允许
- `@Qualifier` 是否匹配
- 是否为 fallback 候选
- 是否来自父容器

#### 13.3 多候选的消歧顺序

Spring Framework `6.1.17` 的 `determineAutowireCandidate()` 顺序是：

```text
1. @Primary
2. @Priority（数值越小优先级越高）
3. resolvableDependencies 中直接注册的依赖
4. Bean 名称与注入点名称匹配
5. 仍无法确定 → NoUniqueBeanDefinitionException
```

这里容易误解：`@Qualifier` 通常不是在这一步“排序”，而是在前面的 `findAutowireCandidates()` 中先过滤候选。

也就是说，流程更像这样：

```text
先过滤：类型、泛型、@Qualifier、autowireCandidate 等条件
再消歧：@Primary → @Priority → resolvableDependencies → 注入点名称匹配
```

字段名匹配发生得比很多人想象得晚。比如字段名是 `alipayPaymentService`，但另一个候选标了 `@Primary`，最终仍会优先选择 `@Primary`。

#### 13.4 示例

```java
public interface PaymentService {
}

@Service("wechatPaymentService")
@Primary
public class WechatPaymentService implements PaymentService {
}

@Service("alipayPaymentService")
public class AlipayPaymentService implements PaymentService {
}
```

```java
@Autowired
private PaymentService paymentService;
```

使用 `@Primary` Bean。

```java
@Autowired
@Qualifier("alipayPaymentService")
private PaymentService paymentService;
```

候选过滤后只剩支付宝实现。

#### 13.5 集合注入

```java
@Autowired
private List<PaymentService> paymentServices;
```

流程：

```text
resolveMultipleBeans()
  ├── 查找全部 PaymentService
  ├── 逐个 resolveCandidate() 创建 Bean
  ├── 转换为目标集合类型
  └── 使用依赖比较器排序
```

可以通过 `@Order`、`Ordered` 或 `@Priority` 影响集合顺序。

#### 13.6 ObjectProvider 为什么能延迟创建

```java
private final ObjectProvider<ReportService> provider;
```

注入时得到的是 Provider，不一定立即调用 `getBean(ReportService)`。只有执行：

```java
provider.getObject();
provider.getIfAvailable();
provider.orderedStream();
```

才会进一步解析目标 Bean。

---

### 十四、initializeBean()：初始化与代理增强

#### 14.1 精确调用顺序

```java
protected Object initializeBean(
        String beanName,
        Object bean,
        @Nullable RootBeanDefinition mbd) {

    invokeAwareMethods(beanName, bean);

    Object wrappedBean = bean;
    wrappedBean = applyBeanPostProcessorsBeforeInitialization(
        wrappedBean, beanName);

    invokeInitMethods(beanName, wrappedBean, mbd);

    wrappedBean = applyBeanPostProcessorsAfterInitialization(
        wrappedBean, beanName);

    return wrappedBean;
}
```

#### 14.2 Aware 回调分成两组

第一组由 `invokeAwareMethods()` 直接调用：

```text
BeanNameAware
BeanClassLoaderAware
BeanFactoryAware
```

第二组由 `ApplicationContextAwareProcessor` 在初始化前阶段调用：

```text
EnvironmentAware
EmbeddedValueResolverAware
ResourceLoaderAware
ApplicationEventPublisherAware
MessageSourceAware
ApplicationStartupAware
ApplicationContextAware
```

#### 14.3 @PostConstruct 在哪里执行

`@PostConstruct` 不在 `invokeInitMethods()` 中执行。

它由 `InitDestroyAnnotationBeanPostProcessor` 的初始化前回调触发：

```text
applyBeanPostProcessorsBeforeInitialization()
  └── InitDestroyAnnotationBeanPostProcessor
      └── invokeInitMethods()
          └── @PostConstruct
```

这里的 `invokeInitMethods()` 是后处理器自己的方法，不是 `AbstractAutowireCapableBeanFactory.invokeInitMethods()`，两者不要混淆。

#### 14.4 Spring 初始化方法顺序

```text
1. BeanNameAware / BeanClassLoaderAware / BeanFactoryAware
2. ApplicationContextAwareProcessor 处理其他 Aware
3. @PostConstruct
4. InitializingBean.afterPropertiesSet()
5. 自定义 init-method
6. BeanPostProcessor.postProcessAfterInitialization()
   └── AbstractAutoProxyCreator 通常在这里返回最终 AOP 代理
```

#### 14.5 自定义初始化方法

```java
@Bean(initMethod = "customInit")
public OrderService orderService() {
    return new OrderService();
}
```

或 XML：

```xml
<bean id="orderService"
      class="com.example.OrderService"
      init-method="customInit"/>
```

如果自定义方法名与 `afterPropertiesSet()` 相同，Spring 会避免无意义的重复调用。

#### 14.6 为什么 @PostConstruct 中事务通常不生效

```java
@PostConstruct
public void init() {
    this.createOrder();
}

@Transactional
public void createOrder() {
}
```

原因有两层：

- `this.createOrder()` 是对象内部调用，不经过代理
- `@PostConstruct` 执行时最终代理通常尚未完成创建

如果初始化逻辑必须使用事务，考虑拆分到另一个 Bean，或在应用就绪事件之后通过代理调用。

### 十五、AOP 代理究竟在哪个阶段生成

#### 15.1 普通场景

正常情况下：

```text
initializeBean()
  └── applyBeanPostProcessorsAfterInitialization()
      └── AbstractAutoProxyCreator.postProcessAfterInitialization()
          └── wrapIfNecessary()
              ├── 查找匹配 Advisor
              ├── 创建代理工厂 ProxyFactory
              ├── 选择 JDK / CGLIB
              └── 返回代理对象
```

此时：

```text
bean           = 原始对象
exposedObject  = 代理对象
```

最终一级缓存中保存的是 `exposedObject`。

#### 15.2 循环依赖场景为什么可能提前创建代理

如果 A 依赖 B，B 又依赖 A：

```text
A 实例化完成
  ↓
A 的 ObjectFactory 放入三级缓存
  ↓
A 开始注入 B
  ↓
B 创建并尝试注入 A
  ↓
B 从 A 的三级缓存工厂获取早期引用
  ↓
getEarlyBeanReference(A)
  ↓
AbstractAutoProxyCreator 提前为 A 创建代理
```

#### 15.3 getEarlyBeanReference()

```java
protected Object getEarlyBeanReference(
        String beanName,
        RootBeanDefinition mbd,
        Object bean) {

    Object exposedObject = bean;

    for (SmartInstantiationAwareBeanPostProcessor bp :
            getBeanPostProcessorCache().smartInstantiationAware) {
        exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);
    }

    return exposedObject;
}
```

`AbstractAutoProxyCreator` 是最重要的实现之一。它会记录该 Bean 已参与早期代理流程，避免初始化后再次创建另一个代理。

#### 15.4 早期引用一致性检查

`doCreateBean()` 初始化完成后会再次检查：

```java
Object earlySingletonReference = getSingleton(beanName, false);

if (earlySingletonReference != null) {
    if (exposedObject == bean) {
        exposedObject = earlySingletonReference;
    }
    else if (!this.allowRawInjectionDespiteWrapping &&
            hasDependentBean(beanName)) {
        // 检查是否有其他 Bean 注入了原始对象
        // 如果最终对象又被包装成了不同代理，则抛异常
    }
}
```

主要处理两种情况：

##### 情况一：初始化后没有产生新对象

```text
exposedObject == bean
```

说明最终初始化没有再包装对象。如果之前存在早期代理，应把最终暴露对象替换为该早期代理。

##### 情况二：初始化后产生新代理，但其他 Bean 已拿到原始对象

此时容器外部可能同时存在两个版本：

```text
B 持有原始 A
容器持有代理 A
```

默认情况下 Spring 会抛出 `BeanCurrentlyInCreationException`，而不是静默接受不一致引用。

---

### 十六、三级缓存与循环依赖完整推演

#### 16.1 三级缓存定义

```java
// 一级缓存：完整单例
private final Map<String, Object> singletonObjects;

// 二级缓存：已经生成的早期引用
private final Map<String, Object> earlySingletonObjects;

// 三级缓存：可以按需生成早期引用的工厂
private final Map<String, ObjectFactory<?>> singletonFactories;
```

| 缓存 | 保存内容 | 进入时机 | 移除时机 |
|------|----------|----------|----------|
| 一级缓存 | 最终单例对象 | Bean 完整创建成功后 | 容器销毁或创建失败清理 |
| 二级缓存 | 已生成的早期对象或代理 | 第一次真正请求早期引用时 | Bean 进入一级缓存时 |
| 三级缓存 | `ObjectFactory` | 原始对象实例化后、属性填充前 | 转入二级缓存或一级缓存时 |

#### 16.2 查询缓存的真实条件

```java
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    Object singletonObject = this.singletonObjects.get(beanName);

    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        singletonObject = this.earlySingletonObjects.get(beanName);

        if (singletonObject == null && allowEarlyReference) {
            ObjectFactory<?> singletonFactory =
                this.singletonFactories.get(beanName);

            if (singletonFactory != null) {
                singletonObject = singletonFactory.getObject();
                this.earlySingletonObjects.put(beanName, singletonObject);
                this.singletonFactories.remove(beanName);
            }
        }
    }

    return singletonObject;
}
```

只有 Bean 正在创建时，才会继续查询二级和三级缓存。正常使用一个已完成 Bean 时只需要查询一级缓存。

#### 16.3 A、B 字段循环依赖时间线

假设：

```java
@Service
class A {
    @Autowired
    private B b;
}

@Service
class B {
    @Autowired
    private A a;
}
```

并且已临时开启循环依赖：

```text
spring.main.allow-circular-references=true
```

完整时间线：

```text
1. getBean("a")
2. 一级缓存没有 A
3. A 加入 singletonsCurrentlyInCreation
4. createBeanInstance(A) → 得到原始 A
5. singletonFactories["a"] = () -> getEarlyBeanReference(A)
6. populateBean(A) → 需要 B

7. getBean("b")
8. B 加入 singletonsCurrentlyInCreation
9. createBeanInstance(B) → 得到原始 B
10. singletonFactories["b"] = () -> getEarlyBeanReference(B)
11. populateBean(B) → 需要 A

12. getBean("a")
13. 一级缓存没有 A
14. A 正在创建，二级缓存也没有 A
15. 从三级缓存获取 A 的 ObjectFactory
16. 调用 getEarlyBeanReference(A)
17. 结果放入 earlySingletonObjects["a"]
18. 删除 singletonFactories["a"]
19. 将早期 A 注入 B

20. B 完成初始化并进入一级缓存
21. A 获取完整 B
22. A 完成初始化
23. A 使用早期引用进行一致性处理
24. A 进入一级缓存
25. 删除 A 的二级、三级缓存记录
```

#### 16.4 为什么需要三级而不是二级

如果实例化完成后直接把原始对象放进二级缓存：

```text
B 获取到原始 A
A 初始化后又被包装为代理 A
```

会产生引用不一致。

如果实例化完成后立即为所有 Bean 创建代理，也可以避免部分问题，但会导致：

- 没发生循环依赖的 Bean 也被过早判断代理
- 代理创建时机与正常生命周期不一致
- 某些后处理器尚未完成必要准备
- 无法做到“只有真正需要早期引用时才调用”

三级缓存保存 `ObjectFactory`，把“是否生成早期代理”的决定推迟到其他 Bean 真正请求该引用的时刻。

#### 16.5 Spring 能解决哪些循环依赖

| 场景 | Framework 能力 | Boot 3.3.9 默认行为 |
|------|----------------|--------------------|
| singleton 字段/Setter 循环 | 开启 allowCircularReferences 后可能解决 | 默认失败 |
| singleton + 普通 AOP 代理 | 早期代理机制可能解决 | 默认失败 |
| 构造器循环 | 无法通过三级缓存解决 | 失败 |
| prototype 循环 | 无法解决 | 失败 |
| `@Lazy` 代理打断依赖 | 可以避免立即获取真实对象 | 可用 |
| `@Async` 等晚期代理 | 可能出现早期对象与最终代理不一致 | 应重构 |

#### 16.6 最佳实践

不要把三级缓存当作业务架构工具。

循环依赖通常意味着：

- 职责边界不清晰
- 双向调用过多
- 领域服务拆分不合理
- 缺少中介对象或事件机制

优先方案：

1. 提取共同依赖到第三个服务。
2. 使用领域事件或应用事件解耦。
3. 将查询与写入职责拆开。
4. 使用 `ObjectProvider` 处理真正需要的延迟查找。
5. 最后才考虑 `@Lazy`，不要直接开启全局循环依赖。

---

## 第三部分：专题扩展
### 十七、FactoryBean 创建流程

#### 17.1 FactoryBean 是什么

```java
public interface FactoryBean<T> {
    T getObject() throws Exception;
    Class<?> getObjectType();
    default boolean isSingleton() {
        return true;
    }
}
```

它本身是一个由 Spring 管理的 Bean，但它还能生产另一个对象。

#### 17.2 两种获取方式

```java
context.getBean("myFactory");
```

返回 `FactoryBean.getObject()` 的产品。

```java
context.getBean("&myFactory");
```

返回 `FactoryBean` 工厂本身。

#### 17.3 getObjectForBeanInstance()

```text
getObjectForBeanInstance(beanInstance, name, beanName, mbd)
  ├── name 以 & 开头
  │   ├── beanInstance 不是 FactoryBean → BeanIsNotAFactoryException
  │   └── 返回 FactoryBean 本身
  ├── beanInstance 不是 FactoryBean
  │   └── 返回普通 Bean
  └── beanInstance 是 FactoryBean
      └── getObjectFromFactoryBean()
```

#### 17.4 产品对象缓存

FactoryBean 产品有独立缓存：

```java
private final Map<String, Object> factoryBeanObjectCache;
```

不要与 `singletonObjects` 混淆：

```text
singletonObjects["myFactory"]
  = FactoryBean 工厂实例

factoryBeanObjectCache["myFactory"]
  = FactoryBean.getObject() 返回的产品
```

只有满足以下条件时，产品对象才会缓存：

- FactoryBean 自身是容器单例
- `FactoryBean.isSingleton()` 返回 true

#### 17.5 产品对象也会执行后处理器

```text
FactoryBean.getObject()
  ↓
postProcessObjectFromFactoryBean()
  ↓
BeanPostProcessor.postProcessAfterInitialization()
```

产品对象通常不会走完整的普通 Bean 创建模板，但仍可能被后处理器包装。

#### 17.6 SmartFactoryBean

`SmartFactoryBean` 增加：

- `isPrototype()`
- `isEagerInit()`

在 `preInstantiateSingletons()` 阶段，只有 `isEagerInit()` 为 true，Spring 才会主动创建产品对象。

---

### 十八、Scope、懒加载与跨作用域注入

#### 18.1 singleton 注入 prototype 的陷阱

```java
@Service
public class ExportService {

    private final ExportTask task;

    public ExportService(ExportTask task) {
        this.task = task;
    }
}

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class ExportTask {
}
```

`ExportService` 只创建一次，所以 `ExportTask` 也只在创建 `ExportService` 时解析一次。后续调用不会自动获得新的 prototype。

解决方案：

```java
private final ObjectProvider<ExportTask> taskProvider;

public void export() {
    ExportTask task = taskProvider.getObject();
}
```

还可以使用：

- `@Lookup`
- scoped proxy
- 注入 `ObjectFactory<T>`
- 重新设计对象边界

#### 18.2 scoped proxy

```java
@RequestScope
public class RequestContext {
}
```

单例 Bean 注入的通常不是当前请求真实对象，而是一个代理：

```text
singleton Bean
  ↓ 持有
RequestContext proxy
  ↓ 每次方法调用
当前 Request Scope 中的真实对象
```

因此代理本身可以安全地作为 singleton 依赖存在。

#### 18.3 @Lazy 的两种含义

##### 标注在 Bean 上

```java
@Lazy
@Service
public class ReportService {
}
```

表示这个 BeanDefinition 在容器启动的 `preInstantiateSingletons()` 阶段不主动创建。

但如果某个非懒单例直接依赖它：

```java
@Service
public class OrderService {
    public OrderService(ReportService reportService) {
    }
}
```

Spring 为了创建 `OrderService`，仍然需要先拿到真实 `ReportService`，所以 `ReportService` 可能在启动期被依赖解析提前创建。

##### 标注在注入点上

```java
public OrderService(@Lazy ReportService reportService) {
}
```

表示注入的不是立即创建出来的真实 `ReportService`，而是一个延迟代理。

```text
创建 OrderService 时：注入 ReportService proxy
第一次调用 proxy 方法时：proxy 再去 getBean("reportService")
```

所以 Bean 上的 `@Lazy` 控制“容器是否主动创建它”，注入点上的 `@Lazy` 控制“当前依赖是否立刻解析真实对象”。

#### 18.4 ObjectProvider 与 @Lazy 的选择

| 需求 | 推荐方式 |
|------|----------|
| 调用方不关心延迟机制，只想像普通对象一样使用 | `@Lazy` 代理 |
| 需要判断 Bean 是否存在 | `ObjectProvider.getIfAvailable()` |
| 每次获取 prototype | `ObjectProvider.getObject()` |
| 获取同类型全部 Bean | `ObjectProvider.stream()` |
| 希望依赖关系显式可读 | 优先 `ObjectProvider` |

---

### 十九、Bean 销毁流程

#### 19.1 哪些 Bean 会自动销毁

容器主要负责 singleton 和由 Scope 管理的 Bean 销毁。

prototype Bean 创建完成后交给调用方，Spring 不会自动执行其完整销毁回调。

#### 19.2 注册销毁适配器

创建阶段结束前：

```java
registerDisposableBeanIfNecessary(beanName, bean, mbd);
```

满足以下任一条件时通常需要注册：

- 实现 `DisposableBean`
- 存在 `@PreDestroy`
- 配置 destroy-method
- 推断出 `close` 或 `shutdown` 方法
- `DestructionAwareBeanPostProcessor` 要处理该 Bean

#### 19.3 容器关闭主线

```text
AbstractApplicationContext.close()
  └── doClose()
      └── destroyBeans()
          └── DefaultListableBeanFactory.destroySingletons()
              └── destroySingleton(beanName)
                  └── DisposableBeanAdapter.destroy()
```

#### 19.4 销毁顺序

```text
1. 先销毁依赖当前 Bean 的 dependent Beans
2. DestructionAwareBeanPostProcessor.postProcessBeforeDestruction()
   └── @PreDestroy
3. DisposableBean.destroy()
4. AutoCloseable.close() 或自定义 destroy-method
5. 销毁 contained Beans
6. 清理依赖关系和缓存
```

#### 19.5 为什么依赖方先销毁

如果 `OrderService` 依赖 `DataSource`：

```text
正确：先销毁 OrderService，再销毁 DataSource
错误：DataSource 已关闭，OrderService 的销毁逻辑仍尝试访问数据库
```

Spring 通过 `dependentBeanMap` 保证依赖方优先销毁。

#### 19.6 优雅关闭注意事项

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

业务 Bean 的销毁方法应：

- 可重复调用或具备幂等性
- 设置超时，避免无限等待
- 正确响应线程中断
- 不再创建新的 Bean
- 不依赖已经销毁的外部资源

---

### 二十、异常、回滚与并发创建

#### 20.1 常见异常与阶段

| 异常 | 常见阶段 | 含义 |
|------|----------|------|
| `NoSuchBeanDefinitionException` | 依赖解析 | 没有候选 Bean |
| `NoUniqueBeanDefinitionException` | 候选消歧 | 同类型 Bean 无法确定唯一候选 |
| `UnsatisfiedDependencyException` | 构造器或属性注入 | 某个依赖无法解析 |
| `BeanCurrentlyInCreationException` | 循环依赖 | Bean 重复进入创建或原始对象泄露 |
| `BeanCreationException` | 创建总流程 | 构造器、工厂方法、注入或初始化失败 |
| `BeanIsNotAFactoryException` | FactoryBean 解引用 | 对普通 Bean 使用了 `&` |
| `BeanNotOfRequiredTypeException` | 类型适配 | 最终对象类型不符合 requiredType |
| `ScopeNotActiveException` | 自定义 Scope | request/session 等作用域当前未激活 |

#### 20.2 异常为什么经常嵌套很深

```text
BeanCreationException: orderController
  └── UnsatisfiedDependencyException: orderService
      └── UnsatisfiedDependencyException: paymentService
          └── NoUniqueBeanDefinitionException
```

排查时从最内层 `Caused by` 开始，而不是只看最外层 Bean 名称。

#### 20.3 单例创建的同步

Spring Framework `6.1.17` 在单例创建路径中使用 `singletonObjects` 作为同步锁，保证同一个 BeanFactory 中的单例不会被多个线程重复创建。

锁内部可能递归创建其他依赖 Bean。Java 的 `synchronized` 是可重入的，因此同线程递归进入不会死锁。

#### 20.4 不要在初始化阶段启动失控的异步任务

如果构造器或 `@PostConstruct` 立即启动新线程，并在新线程中查询尚未完成创建的 Bean，可能造成：

- 读取到未完成初始化的状态
- 与容器单例锁产生等待
- 应用启动顺序不可控
- 关闭时线程无法正确回收

更合适的时机包括：

- `SmartInitializingSingleton.afterSingletonsInstantiated()`
- `ContextRefreshedEvent`
- Spring Boot `ApplicationReadyEvent`
- `SmartLifecycle.start()`

### 二十一、BeanPostProcessor 全阶段地图

#### 21.1 为什么 BeanPostProcessor 是理解创建流程的关键

Spring 的默认创建模板负责搭建骨架，真正的大量扩展能力来自后处理器：

```text
依赖注入
AOP
事务
异步
缓存
校验
生命周期注解
Aware 回调
```

#### 21.2 完整扩展点顺序

| 阶段 | 扩展点 | 典型作用 |
|------|--------|----------|
| 1 | `InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation()` | 实例化前直接返回代理或替代对象 |
| 2 | `SmartInstantiationAwareBeanPostProcessor.determineCandidateConstructors()` | 提供候选构造器 |
| 3 | `MergedBeanDefinitionPostProcessor.postProcessMergedBeanDefinition()` | 解析并缓存注入、生命周期元数据 |
| 4 | `InstantiationAwareBeanPostProcessor.postProcessAfterInstantiation()` | 决定是否继续属性填充 |
| 5 | `InstantiationAwareBeanPostProcessor.postProcessProperties()` | 执行 `@Autowired`、`@Value`、`@Resource` 注入 |
| 条件阶段 | `SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference()` | 依赖递归过程中按需创建早期代理，发生在当前 Bean 完成初始化之前 |
| 6 | `BeanPostProcessor.postProcessBeforeInitialization()` | ApplicationContextAware、`@PostConstruct` 等初始化前处理 |
| 7 | `BeanPostProcessor.postProcessAfterInitialization()` | AOP 等最终包装 |
| 销毁阶段 | `DestructionAwareBeanPostProcessor.postProcessBeforeDestruction()` | `@PreDestroy` 等销毁前回调 |

#### 21.3 三个最重要的内置后处理器

##### AutowiredAnnotationBeanPostProcessor

```text
determineCandidateConstructors()  → 选择 @Autowired 构造器
postProcessMergedBeanDefinition() → 缓存字段和方法注入元数据
postProcessProperties()           → 执行 @Autowired / @Value 注入
```

##### CommonAnnotationBeanPostProcessor

```text
postProcessMergedBeanDefinition() → 缓存 @Resource 元数据
postProcessProperties()           → 执行 @Resource 注入
postProcessBeforeInitialization() → 继承处理 @PostConstruct
postProcessBeforeDestruction()    → 继承处理 @PreDestroy
```

##### AbstractAutoProxyCreator

```text
postProcessBeforeInstantiation()  → 特殊 TargetSource 提前代理
getEarlyBeanReference()           → 循环依赖中的早期代理
postProcessAfterInitialization()  → 普通场景创建最终 AOP 代理
```

---

## 第四部分：调试、练习与复盘
### 二十二、源码阅读路线

#### 22.1 第一条：从容器启动进入 Bean 创建

```text
AbstractApplicationContext.refresh()
  ↓
finishBeanFactoryInitialization()
  ↓
DefaultListableBeanFactory.preInstantiateSingletons()
  ↓
AbstractBeanFactory.getBean()
  ↓
AbstractBeanFactory.doGetBean()
```

先理解“为什么创建这个 Bean”，不要一开始就陷入反射细节。

#### 22.2 第二条：单个 Bean 的完整创建

```text
AbstractAutowireCapableBeanFactory.createBean()
  ↓
resolveBeforeInstantiation()
  ↓
doCreateBean()
  ├── createBeanInstance()
  ├── populateBean()
  └── initializeBean()
```

#### 22.3 第三条：构造器参数

```text
createBeanInstance()
  ↓
autowireConstructor()
  ↓
ConstructorResolver.autowireConstructor()
  ↓
createArgumentArray()
  ↓
resolveAutowiredArgument()
  ↓
DefaultListableBeanFactory.resolveDependency()
```

#### 22.4 第四条：字段注入

```text
populateBean()
  ↓
AutowiredAnnotationBeanPostProcessor.postProcessProperties()
  ↓
InjectionMetadata.inject()
  ↓
AutowiredFieldElement.inject()
  ↓
resolveDependency()
```

#### 22.5 第五条：循环依赖与 AOP

```text
doCreateBean()
  ↓
addSingletonFactory()
  ↓
DefaultSingletonBeanRegistry.getSingleton(beanName, true)
  ↓
getEarlyBeanReference()
  ↓
AbstractAutoProxyCreator.getEarlyBeanReference()
```

#### 22.6 第六条：销毁

```text
AbstractApplicationContext.close()
  ↓
DefaultSingletonBeanRegistry.destroySingletons()
  ↓
destroySingleton()
  ↓
DisposableBeanAdapter.destroy()
```

---

### 二十三、IDEA 断点调试指南

#### 23.1 主链断点

| 观察目标 | 类 | 方法 |
|----------|----|------|
| 批量创建单例 | `DefaultListableBeanFactory` | `preInstantiateSingletons()` |
| 获取 Bean 主入口 | `AbstractBeanFactory` | `doGetBean()` |
| 单例创建锁 | `DefaultSingletonBeanRegistry` | `getSingleton(String, ObjectFactory)` |
| 创建前短路 | `AbstractAutowireCapableBeanFactory` | `resolveBeforeInstantiation()` |
| Bean 核心模板 | `AbstractAutowireCapableBeanFactory` | `doCreateBean()` |
| 选择实例化方式 | `AbstractAutowireCapableBeanFactory` | `createBeanInstance()` |
| 构造器解析 | `ConstructorResolver` | `autowireConstructor()` |
| 属性填充 | `AbstractAutowireCapableBeanFactory` | `populateBean()` |
| 依赖解析 | `DefaultListableBeanFactory` | `doResolveDependency()` |
| 初始化 | `AbstractAutowireCapableBeanFactory` | `initializeBean()` |
| 早期引用 | `DefaultSingletonBeanRegistry` | `getSingleton(String, boolean)` |
| AOP 代理 | `AbstractAutoProxyCreator` | `wrapIfNecessary()` |
| 销毁 | `DisposableBeanAdapter` | `destroy()` |

#### 23.2 重点观察变量

```text
beanName
mbd / mbdToUse
instanceWrapper
bean
exposedObject
earlySingletonExposure
earlySingletonReference
singletonsCurrentlyInCreation
singletonObjects
earlySingletonObjects
singletonFactories
pvs / pvsToUse
descriptor
matchingBeans
autowiredBeanName
```

#### 23.3 条件断点建议

如果容器 Bean 很多，在以下断点增加条件：

```text
beanName.equals("orderService")
```

缓存断点：

```text
beanName.equals("a") || beanName.equals("b")
```

依赖解析断点：

```text
descriptor.getDependencyType().getName().contains("PaymentService")
```

#### 23.4 调试时不要使用 toString()

IDEA 自动计算变量的 `toString()` 可能触发：

- 懒加载
- Hibernate 查询
- 代理方法调用
- 新的 Bean 获取
- 集合遍历

复杂源码调试时关闭自动 `toString()` 展示，优先观察对象类型、字段和 identity hash。

---

### 二十四、八个必做实验

#### 24.1 实验一：构造器与字段注入时机

目标：证明字段注入发生在构造器之后。

```java
@Service
class DemoService {

    @Autowired
    private UserService userService;

    public DemoService() {
        System.out.println(userService); // null
    }

    @PostConstruct
    public void init() {
        System.out.println(userService); // 已注入
    }
}
```

观察断点：

```text
createBeanInstance()
populateBean()
postProcessProperties()
```

#### 24.2 实验二：初始化顺序

让 Bean 同时实现：

- `BeanNameAware`
- `ApplicationContextAware`
- `InitializingBean`
- `@PostConstruct`
- 自定义 init-method
- 自定义 BeanPostProcessor

记录输出顺序，并与第十四章对照。

#### 24.3 实验三：多候选依赖

依次测试：

1. 两个实现，无任何标记。
2. 增加 `@Primary`。
3. 使用 `@Qualifier`。
4. 使用字段名匹配 BeanName。
5. 增加 `@Priority`。

在 `determineAutowireCandidate()` 观察最终选择。

#### 24.4 实验四：FactoryBean

```java
@Component("carFactory")
class CarFactoryBean implements FactoryBean<Car> {
    @Override
    public Car getObject() {
        return new Car();
    }

    @Override
    public Class<?> getObjectType() {
        return Car.class;
    }
}
```

分别观察：

```java
context.getBean("carFactory");
context.getBean("&carFactory");
```

并查看 `singletonObjects` 与 `factoryBeanObjectCache`。

#### 24.5 实验五：singleton 注入 prototype

连续调用 singleton Bean 的业务方法，观察 prototype 对象是否变化；然后改用 `ObjectProvider` 再比较。

#### 24.6 实验六：普通循环依赖

临时开启：

```yaml
spring.main.allow-circular-references: true
```

构造 A、B 字段循环，在三个缓存写入和迁移的位置打断点。

实验后必须恢复默认配置。

#### 24.7 实验七：循环依赖与 AOP

为 A 增加 `@Transactional`，观察：

- `getEarlyBeanReference()` 是否返回代理
- B 中持有的 A 类型
- 一级缓存中 A 的类型
- 两个对象是否为同一引用

#### 24.8 实验八：创建失败清理

```java
@PostConstruct
public void init() {
    throw new IllegalStateException("init failed");
}
```

观察：

- `BeanCreationException` 的嵌套原因
- `singletonsCurrentlyInCreation` 是否清理
- 三级缓存记录是否清理
- 依赖 Bean 是否受到影响

---

### 二十五、常见错误认知

#### 25.1 “new 出对象就完成 Bean 创建了”

错误。构造器只完成实例化，后面还有属性填充、初始化、代理和销毁注册。

#### 25.2 “@Autowired 在构造器之前执行”

字段和方法注入发生在实例化之后。构造器参数注入属于实例化过程的一部分。

#### 25.3 “@PostConstruct 属于 invokeInitMethods()”

错误。它由 `InitDestroyAnnotationBeanPostProcessor` 在初始化前阶段调用。

#### 25.4 “AOP 代理一定在初始化后创建”

普通情况如此，但循环依赖时可能通过 `getEarlyBeanReference()` 提前创建代理。

#### 25.5 “三级缓存解决所有循环依赖”

错误。构造器循环、prototype 循环以及某些晚期代理循环无法解决；Boot `3.3.9` 默认还禁止循环依赖。

#### 25.6 “FactoryBean 自己就是产品对象”

错误。`FactoryBean` 是工厂 Bean，默认 BeanName 返回产品，`&BeanName` 才返回工厂。

#### 25.7 “@Lazy Bean 一定到第一次业务调用才创建”

错误。被非懒 Bean 直接依赖时，依赖解析仍可能提前创建它。

#### 25.8 “prototype Bean 会自动执行 @PreDestroy”

错误。Spring 通常不负责 prototype 的完整销毁，需要调用方自行释放资源。

---

### 二十六、面试题与答题框架

#### 26.1 Spring Bean 的完整创建流程是什么

答题框架：

1. `getBean()` 进入 `doGetBean()`，先查缓存、处理父容器、合并 BeanDefinition 和 Scope。
2. singleton 通过 `getSingleton(beanName, ObjectFactory)` 保证只创建一次。
3. `createBean()` 允许 InstantiationAwareBeanPostProcessor 在实例化前短路。
4. `doCreateBean()` 依次执行实例化、MergedBeanDefinition 后处理、早期暴露、属性填充、初始化、早期引用检查和销毁注册。
5. 初始化后对象可能被 AOP 包装，最终对象进入一级缓存。

#### 26.2 实例化和初始化有什么区别

实例化是通过构造器、工厂方法或 Supplier 得到原始对象；初始化发生在属性注入之后，包括 Aware、`@PostConstruct`、`InitializingBean`、init-method 和 BeanPostProcessor 包装。

#### 26.3 为什么构造器循环依赖无法解决

三级缓存是在原始对象实例化之后放入的，而构造器循环在实例化完成前就需要对方对象，没有可提前暴露的引用。

#### 26.4 为什么需要三级缓存

三级缓存保存 ObjectFactory，在真正需要早期引用时调用 `getEarlyBeanReference()`，从而按需暴露原始对象或早期代理，保证循环依赖双方与容器最终对象一致。

#### 26.5 @Autowired 在哪个阶段执行

构造器候选由 `determineCandidateConstructors()` 参与实例化；字段和方法注入由 `postProcessProperties()` 在 `populateBean()` 阶段执行。

#### 26.6 @PostConstruct 在哪个阶段执行

在 BeanPostProcessor 初始化前阶段，由 `InitDestroyAnnotationBeanPostProcessor` 调用，早于 `InitializingBean.afterPropertiesSet()` 和自定义 init-method。

#### 26.7 FactoryBean 和 BeanFactory 有什么区别

`BeanFactory` 是 IOC 容器接口；`FactoryBean` 是容器中的特殊 Bean，用于生产另一个对象。

#### 26.8 BeanPostProcessor 为什么可能导致 Bean 提前创建

BeanPostProcessor 本身需要在普通 Bean 前实例化和注册。如果其构造器或创建方法依赖普通业务 Bean，就会导致业务 Bean 在后处理器链尚未完整时提前创建。

---

### 二十七、生产实践建议

#### 27.1 优先构造器注入

优点：

- 依赖显式
- 可以使用 `final`
- 创建完成即处于有效状态
- 易于单元测试
- 更容易暴露循环依赖设计问题

#### 27.2 不要在构造器中执行重型业务

避免：

- 数据库查询
- 网络请求
- 启动线程
- 发布依赖完整容器的事件
- 调用可能依赖 AOP 的方法

构造器只负责建立对象基本不变量。

#### 27.3 谨慎使用 @PostConstruct

适合：

- 校验配置
- 构造轻量内存结构
- 初始化无需代理的本地状态

不适合：

- 长时间阻塞操作
- 依赖事务或异步代理
- 调用不稳定外部服务
- 创建无法关闭的线程

#### 27.4 避免业务代码主动 getBean()

主动查询容器会：

- 隐藏依赖
- 增加测试难度
- 容易提前创建 Bean
- 让生命周期难以推断

真正需要动态获取时优先使用 `ObjectProvider`。

#### 27.5 自定义 BeanPostProcessor 必须轻量

- 不要在构造器中依赖业务 Bean
- 不要在每个 Bean 上重复做昂贵反射扫描
- 缓存解析后的元数据
- 跳过基础设施 Bean 和不相关类型
- 保持幂等，避免重复包装

#### 27.6 不依赖循环引用

Spring Boot 默认禁止循环依赖是在推动更清晰的架构。不要为了让旧设计启动而直接开启全局配置。

#### 27.7 控制启动阶段外部依赖

如果 Bean 初始化必须访问外部系统：

- 设置连接和读取超时
- 明确失败策略
- 考虑延迟初始化
- 提供健康检查
- 避免让非核心能力阻断整个应用启动

---

### 二十八、七天学习路线

#### 第一天：只看主线

```text
preInstantiateSingletons()
doGetBean()
createBean()
doCreateBean()
```

目标：能不看资料画出完整主链。

#### 第二天：实例化

学习：

- Supplier
- FactoryMethod
- 构造器选择
- ConstructorResolver
- BeanWrapper

#### 第三天：依赖注入

学习：

- populateBean()
- AutowiredAnnotationBeanPostProcessor
- DependencyDescriptor
- doResolveDependency()
- 多候选消歧

#### 第四天：生命周期与代理

学习：

- Aware
- `@PostConstruct`
- InitializingBean
- BeanPostProcessor
- AbstractAutoProxyCreator

#### 第五天：三级缓存

手工推演 A、B 循环依赖，观察三个缓存每一步的变化。

#### 第六天：FactoryBean、Scope 与销毁

完成 FactoryBean、prototype、ObjectProvider、scoped proxy 和销毁实验。

#### 第七天：源码复述与面试

要求自己在 15 分钟内讲清：

1. Bean 从 BeanDefinition 到最终对象。
2. `@Autowired` 的完整链路。
3. AOP 代理生成时机。
4. 三级缓存和循环依赖边界。
5. FactoryBean 与销毁流程。

---

## 最终总结

Bean 创建不是一个方法，而是一套可扩展模板：

```text
BeanDefinition
  ↓
doGetBean()                       决定缓存、父容器、依赖和 Scope
  ↓
getSingleton()                    保证单例唯一并管理创建状态
  ↓
createBean()                      提供实例化前短路机会
  ↓
doCreateBean()
  ├── createBeanInstance()        构造器 / FactoryMethod / Supplier
  ├── MergedBeanDefinition BPP    解析注入和生命周期元数据
  ├── addSingletonFactory()       为循环依赖准备早期引用
  ├── populateBean()              @Autowired / @Value / @Resource
  ├── initializeBean()            Aware / @PostConstruct / init / AOP
  ├── early reference check       保证原始对象和代理引用一致
  └── register destruction        注册销毁回调
  ↓
singletonObjects                  最终可使用 Bean
```

一句话记忆：

> Spring Bean 创建的本质，是以 BeanDefinition 为配方，以 BeanPostProcessor 为扩展链，在 Scope 和单例缓存约束下完成实例化、依赖注入、初始化、代理增强和销毁管理。

真正掌握本文的标准不是背方法名，而是能够回答：

- 当前 Bean 为什么现在创建
- 当前拿到的是原始对象、早期引用还是最终代理
- 当前依赖为何选择这个候选 Bean
- 当前回调发生在实例化、属性填充还是初始化阶段
- 创建失败后哪些缓存和依赖关系需要清理
- 容器关闭时为什么按这个顺序销毁

关联阅读：

- `01-01-Spring-IOC.md`
- `01-02-Spring-IOC-ConfigurationClassPostProcessor.md`
- `02-01-Spring-AOP.md`
