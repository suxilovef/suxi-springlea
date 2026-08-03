# Spring Boot 专题：自动配置原理源码深度解析

> 本文是第 5 阶段 Spring Boot 核心机制的第二篇文档，承接 `04-01-SpringBoot-启动流程.md`。上一篇讲到主配置类在 `refresh()` 中被 `ConfigurationClassPostProcessor` 解析；本文从 `@EnableAutoConfiguration` 这个分支继续，专门拆解候选类加载、条件筛选、排序、排除和退让机制。

---

## 一、先给结论

### 1.1 什么是自动配置

Spring Boot 自动配置解决的问题是：

```text
根据 classpath、现有 Bean、配置属性和应用类型，
自动向 Spring 容器注册一组合理的默认 Bean
```

例如引入 Web Starter 后，Spring Boot 可以自动准备：

- `DispatcherServlet`。
- `RequestMappingHandlerMapping`。
- `RequestMappingHandlerAdapter`。
- `HttpMessageConverter`。
- 内嵌 WebServerFactory。

但前提是对应条件成立，并且用户没有提供要覆盖默认行为的 Bean。

### 1.2 自动配置不是什么

自动配置不是：

- 扫描 classpath 中所有类并随机注册。
- 绕过 Spring IOC 直接创建对象。
- 永远强制覆盖用户配置。
- 在 `SpringApplication.run()` 外部运行的独立机制。

它本质上仍然是：

```text
一批有条件的 Spring @Configuration 类
```

### 1.3 完整主线

```text
@SpringBootApplication
  -> @EnableAutoConfiguration
    -> @Import(AutoConfigurationImportSelector.class)
      -> DeferredImportSelector
      -> 加载 AutoConfiguration.imports
      -> 去重、排除、过滤
      -> 根据条件决定是否生效
      -> 排序自动配置类
      -> 作为配置类继续解析
      -> 注册 BeanDefinition
```

---

## 二、自动配置与普通配置类的关系

### 2.1 共同点

自动配置类和普通 `@Configuration` 都会进入 Spring 配置类解析流程。

它们都可以：

- 声明 `@Bean`。
- 使用 `@Import`。
- 使用 `@Conditional`。
- 导入其他配置类。

### 2.2 不同点

| 维度 | 普通配置类 | 自动配置类 |
|---|---|---|
| 入口 | 组件扫描、`@Import`、显式注册 | `@EnableAutoConfiguration` 批量导入 |
| 候选清单 | 项目代码明确指定 | `AutoConfiguration.imports` |
| 条件 | 可选 | 通常大量使用 Boot 条件注解 |
| 退让 | 由开发者自行设计 | 常用 `@ConditionalOnMissingBean` |
| 排序 | 主要依赖导入关系 | 可使用 AutoConfigure Before / After / Order |

### 2.3 本文不重复 ConfigurationClassPostProcessor

前面 IOC 阶段已经讲过：

```text
ConfigurationClassPostProcessor
  -> ConfigurationClassParser
  -> processImports()
  -> 注册 BeanDefinition
```

本文只聚焦 `processImports()` 遇到 `AutoConfigurationImportSelector` 之后的 Boot 特有分支。

---

## 三、@EnableAutoConfiguration 入口

### 3.1 核心结构

简化结构：

```java
@AutoConfigurationPackage
@Import(AutoConfigurationImportSelector.class)
public @interface EnableAutoConfiguration {

    Class<?>[] exclude() default {};

    String[] excludeName() default {};
}
```

其中有两条重要分支：

```text
@AutoConfigurationPackage
  -> 注册自动配置基础包信息

AutoConfigurationImportSelector
  -> 选择需要导入的自动配置类
```

### 3.2 AutoConfigurationPackage 不等于 ComponentScan

`@AutoConfigurationPackage` 会记录主配置类所在包，供某些自动配置使用。

例如：

- JPA 实体扫描默认包。
- Repository 扫描基础包。

它不是 `@ComponentScan` 的替代品。

---

## 四、AutoConfigurationImportSelector

### 4.1 类型定位

`AutoConfigurationImportSelector` 是自动配置导入的核心选择器。

它实现：

```text
DeferredImportSelector
BeanClassLoaderAware
ResourceLoaderAware
BeanFactoryAware
EnvironmentAware
Ordered
```

所以它可以获取：

- ClassLoader。
- ResourceLoader。
- BeanFactory。
- Environment。
- 导入它的配置类元数据。

### 4.2 为什么是 DeferredImportSelector

`DeferredImportSelector` 不会在遇到 `@Import` 时立即导入。

它会延后到常规配置类解析基本完成后再批量处理。

这样做的价值：

- 用户配置类可以先被解析。
- 自动配置可以更容易检查用户已经提供的 BeanDefinition。
- 所有自动配置候选项可以统一分组、去重和排序。

### 4.3 AutoConfigurationGroup

`AutoConfigurationImportSelector` 使用专用 Group 统一处理延迟导入。

简化主线：

```text
DeferredImportSelectorHandler
  -> DeferredImportSelectorGroupingHandler
  -> AutoConfigurationGroup#process()
  -> AutoConfigurationGroup#selectImports()
```

Group 负责：

- 汇总多个导入源的自动配置。
- 统一处理排除项。
- 统一排序。
- 输出最终导入列表。

---

## 五、getAutoConfigurationEntry() 主流程

### 5.1 主流程

简化过程：

```text
AutoConfigurationImportSelector#getAutoConfigurationEntry()
  -> isEnabled()
  -> getAttributes()
  -> getCandidateConfigurations()
  -> removeDuplicates()
  -> getExclusions()
  -> checkExcludedClasses()
  -> removeAll(exclusions)
  -> getConfigurationClassFilter().filter()
  -> fireAutoConfigurationImportEvents()
  -> new AutoConfigurationEntry(configurations, exclusions)
```

### 5.2 isEnabled()

自动配置可以通过全局属性关闭。

常见关键字：

```text
spring.boot.enableautoconfiguration
```

正常项目不建议整体关闭自动配置，通常只排除特定配置类。

### 5.3 候选项并不等于最终生效

`getCandidateConfigurations()` 得到的只是候选类。

一个候选类最终是否生效，还要经过：

- 排除项检查。
- Class 条件过滤。
- Bean 条件判断。
- Property 条件判断。
- Web 应用类型判断。
- 配置类解析阶段的其他 Condition。

---

## 六、AutoConfiguration.imports 候选加载

### 6.1 Spring Boot 3 的候选清单

Spring Boot 3 主要从以下资源加载自动配置类：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

文件内容是自动配置类全限定名，每行一个：

```text
com.example.autoconfigure.ExampleAutoConfiguration
com.example.autoconfigure.ExampleWebAutoConfiguration
```

### 6.2 ImportCandidates

候选加载核心可以追到：

```text
ImportCandidates#load(AutoConfiguration.class, classLoader)
```

它会从 classpath 中汇总所有相同路径的 imports 文件。

所以多个 Starter 可以各自提供自动配置候选项。

### 6.3 与 spring.factories 的关系

早期 Spring Boot 常通过 `META-INF/spring.factories` 声明自动配置类。

Spring Boot 3 中，自动配置候选类应使用专用的 `AutoConfiguration.imports`。

`spring.factories` 仍可用于部分其他扩展点，不能简单理解为整个机制被删除。

---

## 七、条件装配体系

### 7.1 @Conditional 是基础

Spring Boot 的条件注解最终都建立在 Spring Framework `@Conditional` 之上。

```java
@Conditional(OnClassCondition.class)
public @interface ConditionalOnClass {
}
```

条件类核心要回答：

```text
当前配置类或 Bean 方法是否应该被跳过？
```

### 7.2 常见条件注解

| 条件 | 作用 |
|---|---|
| `@ConditionalOnClass` | classpath 存在指定类 |
| `@ConditionalOnMissingClass` | classpath 不存在指定类 |
| `@ConditionalOnBean` | 容器中存在指定 Bean |
| `@ConditionalOnMissingBean` | 容器中不存在指定 Bean |
| `@ConditionalOnSingleCandidate` | 指定类型存在唯一候选者或 Primary |
| `@ConditionalOnProperty` | 配置属性满足要求 |
| `@ConditionalOnResource` | classpath 存在指定资源 |
| `@ConditionalOnWebApplication` | 当前是指定 Web 应用类型 |
| `@ConditionalOnNotWebApplication` | 当前不是 Web 应用 |

### 7.3 条件评估阶段

部分条件可以在配置类解析阶段快速判断，例如 classpath 中是否存在某个类。

部分条件需要等 BeanDefinition 信息更完整后判断，例如 `@ConditionalOnMissingBean`。

可以用 `ConfigurationCondition.ConfigurationPhase` 理解两个阶段：

```text
PARSE_CONFIGURATION
REGISTER_BEAN
```

### 7.4 条件判断不是只做一次

自动配置候选项会先进行快速过滤，进入配置类解析后，类级别和 `@Bean` 方法级别条件还会继续评估。

所以不要把自动配置理解成“一次性过滤列表”。

---

## 八、常见条件深入理解

### 8.1 @ConditionalOnClass

```java
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
public class DataSourceAutoConfiguration {
}
```

含义：classpath 中存在 `DataSource` 时，才有必要继续解析。

这是自动配置能够随依赖变化的基础。

### 8.2 @ConditionalOnMissingBean

```java
@Bean
@ConditionalOnMissingBean
public ExampleClient exampleClient() {
    return new DefaultExampleClient();
}
```

含义：

```text
用户没有提供 ExampleClient
  -> 注册默认实现

用户已经提供 ExampleClient
  -> 自动配置退让
```

这是 Spring Boot “默认可用、用户可覆盖”的核心。

### 8.3 @ConditionalOnProperty

```java
@ConditionalOnProperty(
        prefix = "example.client",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
```

需要关注四个点：

- `prefix`。
- `name` / `value`。
- `havingValue`。
- `matchIfMissing`。

`matchIfMissing = true` 表示属性未配置时也可能生效，并不是“必须显式配置 true”。

### 8.4 @ConditionalOnBean 的时序风险

Bean 条件依赖当前已经注册的 BeanDefinition。

所以它的判断可能受自动配置顺序影响。

这也是为什么 Boot 专门提供自动配置排序机制。

---

## 九、自动配置排序

### 9.1 常见排序方式

```text
@AutoConfigureBefore
@AutoConfigureAfter
@AutoConfigureOrder
```

`@AutoConfiguration` 也支持通过 `before`、`beforeName`、`after`、`afterName` 声明相对顺序。

### 9.2 排序解决什么问题

排序主要影响：

- 自动配置类的处理顺序。
- BeanDefinition 的可见时机。
- `@ConditionalOnBean` / `@ConditionalOnMissingBean` 的判断。

### 9.3 排序不等于 Bean 创建顺序

这是常见误区。

```text
自动配置类排序
≠
单例 Bean 实例化顺序
```

Bean 实例化顺序还受依赖关系、`@DependsOn`、懒加载等因素影响。

---

## 十、退让机制

### 10.1 Back-off 是 Spring Boot 设计核心

自动配置应该提供默认能力，而不是阻止用户定制。

典型写法：

```java
@Bean
@ConditionalOnMissingBean
public ObjectMapper objectMapper() {
    return new ObjectMapper();
}
```

用户自己提供同类型 Bean 后，默认 Bean 应该退让。

### 10.2 退让失败的常见原因

- 用户 Bean 类型和条件检查类型不一致。
- Bean 注册时机晚于条件判断。
- 条件限定了 Bean 名称、注解或搜索范围。
- 用户只创建了普通 Java 对象，没有注册成 Spring Bean。

### 10.3 用户 Bean 优先不等于 @Primary

`@ConditionalOnMissingBean` 决定默认 Bean 是否注册。

`@Primary` 决定多个 Bean 已经存在时，依赖注入优先选谁。

两者不是同一个问题。

---

## 十一、排除自动配置

### 11.1 exclude

```java
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class Application {
}
```

### 11.2 excludeName

当项目编译时不能直接引用某个自动配置类，可使用类名：

```java
@EnableAutoConfiguration(
        excludeName = "com.example.autoconfigure.ExampleAutoConfiguration"
)
```

### 11.3 配置属性排除

```properties
spring.autoconfigure.exclude=\
com.example.autoconfigure.ExampleAutoConfiguration
```

### 11.4 排除之前要先理解条件

如果自动配置只是因为条件不满足而没生效，没有必要排除。

排除适合“候选配置会生效，但应用明确不需要”的场景。

---

## 十二、@AutoConfiguration 的设计

### 12.1 专用自动配置注解

Spring Boot 3 自定义自动配置推荐使用：

```java
@AutoConfiguration
public class ExampleAutoConfiguration {
}
```

`@AutoConfiguration` 本质上是针对自动配置场景的 `@Configuration(proxyBeanMethods = false)`。

### 12.2 为什么 proxyBeanMethods = false

自动配置类通常不依赖 `@Bean` 方法之间的直接调用来保证单例语义。

关闭 Bean 方法代理可以减少配置类代理开销。

建议通过方法参数声明 Bean 依赖：

```java
@Bean
public ExampleService exampleService(ExampleClient exampleClient) {
    return new ExampleService(exampleClient);
}
```

---

## 十三、最小自定义自动配置

### 13.1 业务对象

```java
public class GreetingService {

    public String greet(String name) {
        return "Hello, " + name;
    }
}
```

### 13.2 自动配置类

```java
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "demo.greeting",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class GreetingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GreetingService greetingService() {
        return new GreetingService();
    }
}
```

这里不使用 `@ConditionalOnClass(GreetingService.class)`，因为 `GreetingService` 就定义在当前自动配置模块中，该条件会始终成立。

如果自动配置封装的是一个可选 SDK，应该检查 SDK 提供的关键类：

```java
@ConditionalOnClass(name = "com.example.greeting.client.GreetingClient")
```

### 13.3 注册 imports 文件

创建：

```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

写入：

```text
com.example.autoconfigure.GreetingAutoConfiguration
```

### 13.4 验证退让

用户项目中显式声明：

```java
@Bean
public GreetingService customGreetingService() {
    return new CustomGreetingService();
}
```

此时自动配置中的默认 `GreetingService` 应该退让。

---

## 十四、ConditionEvaluationReport

### 14.1 它记录什么

`ConditionEvaluationReport` 会记录：

- Positive matches。
- Negative matches。
- Exclusions。
- Unconditional classes。

它是排查自动配置为什么生效或不生效的重要依据。

### 14.2 debug=true

```properties
debug=true
```

启动后可查看：

```text
CONDITIONS EVALUATION REPORT
Positive matches
Negative matches
Exclusions
```

### 14.3 Actuator conditions

引入 Actuator 并显式开放 `conditions` 端点后，可通过：

```text
/actuator/conditions
```

查看条件评估情况。

生产环境开放端点时需要做好访问控制。

---

## 十五、常见问题排查

### 15.1 自动配置类完全没有进入候选

检查：

- `AutoConfiguration.imports` 路径是否正确。
- 文件是否被打包到 jar。
- 类全限定名是否正确。
- 应用是否禁用了自动配置。

### 15.2 @ConditionalOnClass 不生效

检查可选依赖是否真的在运行时 classpath 中。

不要只看 IDE 是否能打开类，还要看 Maven / Gradle 依赖作用域和最终打包结果。

### 15.3 @ConditionalOnMissingBean 没有退让

检查：

- 条件检查的类型。
- 用户 Bean 实际暴露的返回类型。
- 用户 BeanDefinition 的注册时机。
- 自动配置类顺序。

### 15.4 @ConditionalOnProperty 行为不符合预期

检查：

- 属性完整名称。
- `havingValue`。
- `matchIfMissing`。
- Profile 是否激活正确。
- 配置源优先级是否覆盖了当前值。

### 15.5 不要把自动配置放进 ComponentScan

自动配置类应通过 imports 文件导入，不应依赖用户项目组件扫描。

否则可能绕过排除、排序和预期的自动配置边界。

---

## 十六、源码断点建议

### 16.1 导入入口

```text
ConfigurationClassParser#processImports
DeferredImportSelectorHandler#handle
DeferredImportSelectorGroupingHandler#processGroupImports
```

### 16.2 候选类选择

```text
AutoConfigurationImportSelector$AutoConfigurationGroup#process
AutoConfigurationImportSelector#getAutoConfigurationEntry
AutoConfigurationImportSelector#getCandidateConfigurations
ImportCandidates#load
AutoConfigurationImportSelector$AutoConfigurationGroup#selectImports
```

Boot 3.3.9 的延迟导入分组主链路会由 `AutoConfigurationGroup#process()` 直接调用 `getAutoConfigurationEntry()`；所以跟踪自动配置时，不应把 `AutoConfigurationImportSelector#selectImports()` 作为必然命中的主断点。

### 16.3 条件判断

```text
ConditionEvaluator#shouldSkip
SpringBootCondition#matches
OnClassCondition#getMatchOutcome
OnBeanCondition#getMatchOutcome
OnPropertyCondition#getMatchOutcome
```

### 16.4 条件报告

```text
ConditionEvaluationReport#get
ConditionEvaluationReport#recordConditionEvaluation
ConditionEvaluationReportLoggingListener
```

---

## 十七、必做实验

### 17.1 实验一：跟踪候选加载

1. 在 `AutoConfigurationImportSelector#getAutoConfigurationEntry` 打断点。
2. 观察候选类数量。
3. 观察 exclusions。
4. 观察过滤前后的类型列表。

### 17.2 实验二：查看条件报告

1. 设置 `debug=true`。
2. 启动应用。
3. 选择一个 Positive match。
4. 选择一个 Negative match。
5. 说明它们的条件差异。

### 17.3 实验三：验证退让

1. 找一个使用 `@ConditionalOnMissingBean` 的自动配置。
2. 观察默认 Bean。
3. 在用户配置中声明同类型 Bean。
4. 再次启动，观察默认 Bean 是否消失。

### 17.4 实验四：自定义 AutoConfiguration

1. 创建 `GreetingAutoConfiguration`。
2. 创建 `AutoConfiguration.imports`。
3. 用 `@ConditionalOnProperty` 控制开关。
4. 用 `@ConditionalOnMissingBean` 实现退让。
5. 在另一个应用中验证生效。

### 17.5 实验五：排除自动配置

1. 用 `exclude` 排除一个自动配置。
2. 用 `spring.autoconfigure.exclude` 完成同样操作。
3. 观察 ConditionEvaluationReport 中的 Exclusions。

---

## 十八、面试常问问题

### 18.1 Spring Boot 自动配置原理是什么？

```text
@SpringBootApplication 包含 @EnableAutoConfiguration，它通过 @Import 导入
AutoConfigurationImportSelector。该 Selector 作为 DeferredImportSelector，
会从 AutoConfiguration.imports 加载自动配置候选类，再执行去重、排除、
条件过滤和排序。最终入选的类作为普通配置类继续解析，
其 @Bean 方法根据条件注解决定是否注册 BeanDefinition。
```

### 18.2 DeferredImportSelector 有什么作用？

```text
它会把导入处理延后到常规配置类基本解析完成之后，便于先处理用户配置，
再对自动配置进行分组、去重、排除和排序。
```

### 18.3 @ConditionalOnMissingBean 是什么作用？

```text
当容器中没有指定类型、名称或注解的 Bean 时条件成立。它常用于提供默认 Bean，
并在用户自定义 Bean 存在时退让。
```

### 18.4 AutoConfiguration.imports 有什么作用？

```text
它是 Spring Boot 3 声明自动配置候选类的专用资源文件。
AutoConfigurationImportSelector 会通过 ImportCandidates 从 classpath 中汇总这些文件，
获得候选自动配置类列表。
```

### 18.5 自动配置顺序是 Bean 创建顺序吗？

```text
不是。自动配置顺序主要影响配置类处理和 BeanDefinition 注册时机，
不等于单例 Bean 的实例化顺序。Bean 创建还受依赖关系等因素影响。
```

---

## 十九、本篇过关标准

学完本文后，需要能做到：

- 能说明 `@EnableAutoConfiguration` 的两条核心分支。
- 能画出 `AutoConfigurationImportSelector` 选择候选类的主流程。
- 能说明 `DeferredImportSelector` 为什么要延后处理。
- 能说明 `AutoConfiguration.imports` 的加载方式。
- 能区分 Class、Bean、Property、Web 等常见条件。
- 能解释 `@ConditionalOnMissingBean` 的退让机制。
- 能区分自动配置排序和 Bean 创建顺序。
- 能使用 `debug=true` 和 ConditionEvaluationReport 排查自动配置。
- 能编写一个最小 `@AutoConfiguration` 并注册到 imports 文件。

---

## 二十、下一篇建议

下一篇继续拆解 Starter 的工程组织方式：

```text
04-03-SpringBoot-Starter.md
```

重点包括：

- Starter 与 AutoConfiguration 模块的职责边界。
- 依赖聚合与可选依赖。
- 自动配置、属性元数据和配置处理器的打包。
- 自定义 Starter 的模块划分和验证方式。
