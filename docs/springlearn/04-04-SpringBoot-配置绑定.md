# Spring Boot 专题：外部化配置与类型安全绑定源码深度解析

> 本文是第 5 阶段 Spring Boot 核心机制的第四篇文档，承接 `04-03-SpringBoot-Starter.md`。
>
> 上一篇已经用 Starter 把「依赖聚合 + 自动配置 + 配置属性」串成了一个可发布物；但配置属性在那篇里只是配角。本文只回答两个问题：
>
> 1. `application.properties` / `application.yml` / 环境变量 / 启动参数，到底在什么时间、以什么顺序进入 `Environment`？
> 2. `@ConfigurationProperties` 在 Bean 生命周期的哪一步把配置绑到对象上，为什么它和 `@Value` 的行为不一样？
>
> 基准：Spring Boot `3.3.9` / Spring Framework `6.1.17` / Java `17`。
>
> 本文的关键顺序与类名用 `javap -c` 反编译本地 `spring-boot-3.3.9.jar` 核对过；文中的「实测输出」来自三组临时 JUnit 探针（已删除），你可以按第十六节的实验步骤复现。

---

## 一、先给结论

### 1.1 配置问题必须拆成两段

很多人把「配置」当成一件事，于是把 `application.yml` 的加载、`Environment` 的优先级、`@ConfigurationProperties` 的绑定混在一起讲。实际上它们是两个阶段，分界线是 `refresh()`：

```text
装载阶段：refresh() 之前
  SpringApplication.prepareEnvironment()
    → EnvironmentPostProcessor 链
    → ConfigData 解析 / 加载
    → 属性进入 Environment（PropertySource 列表）

绑定阶段：refresh() 之中的 Bean 创建期
  getBean() → doCreateBean()
    → populateBean()            依赖注入 / @Value
    → BeanPostProcessor.before   ConfigurationPropertiesBindingPostProcessor 绑定
    → @PostConstruct / afterPropertiesSet
```

记住这条分界线，很多现象就能直接解释：

- 为什么 `spring.config.*` 不能写在 `@PropertySource` 里？因为 `@PropertySource` 要等到 `refresh()` 的配置类解析阶段才生效，而 ConfigData 在那之前就已经开始读 `spring.config.*` 了。
- 为什么 `@ConfigurationProperties` 报错发生在启动期，而不是第一次 `getProperty` 时？因为绑定发生在 Bean 创建期，不是访问期。
- 为什么改了配置中心的配置，已经注入的 Bean 不跟着变？因为绑定只在 Bean 创建时做一次。

### 1.2 一张主线图

```text
SpringApplication.run()
├─ prepareEnvironment()
│   ├─ 创建 Environment（非 Web 时是 ApplicationEnvironment）
│   ├─ 加 defaultProperties、命令行参数
│   ├─ 发布 ApplicationEnvironmentPreparedEvent
│   └─ EnvironmentPostProcessorApplicationListener 依次执行 EPP：
│        RandomValuePropertySourceEnvironmentPostProcessor     (MIN+1)
│        SystemEnvironmentPropertySourceEnvironmentPostProcessor(MIN+4)
│        SpringApplicationJsonEnvironmentPostProcessor          (MIN+5)
│        ConfigDataEnvironmentPostProcessor                     (MIN+10)
│            └─ ConfigDataEnvironment.processAndApply()
│                 resolve → load → applyToEnvironment()
└─ refresh()
    └─ preInstantiateSingletons()
        └─ doCreateBean()
            ├─ populateBean()                       @Autowired / @Value
            ├─ postProcessBeforeInitialization()
            │    └─ ConfigurationPropertiesBindingPostProcessor
            │         └─ ConfigurationPropertiesBinder.bind()
            │              └─ Binder.bind(prefix, Bindable, handler)
            ├─ @PostConstruct / afterPropertiesSet
            └─ postProcessAfterInitialization()     AOP 代理在这里生成
```

### 1.3 六个常见误解

| 误解 | 事实 |
|---|---|
| 只写 `@ConfigurationProperties` 就会绑定 | 不会。类必须先被注册成 Bean；没有注册时注解完全静默，不报错 |
| `@Value` 也支持松散绑定 | 不支持。`@Value` 走精确 key 查找；`${probe.kebabName}` 不会命中 `probe.kebab-name`（本文实测） |
| 配置文件优先级由文件位置单独决定 | 不完整。优先级 = 来源类别（默认值/配置文件/环境变量/命令行）+ 位置 + profile + import + 测试源共同决定 |
| 构造器绑定必须写 `@ConstructorBinding` | Boot 3 中，只有一个有参构造器时可以省略；多构造器才需要标注 |
| `@ConfigurationProperties` 在 `populateBean()` 里执行 | 不是。它在 `postProcessBeforeInitialization()` 里执行；构造器绑定则更早，在 Bean 实例创建时 |
| 配置元数据是运行时绑定的依据 | 不是。`spring-configuration-metadata.json` 只服务 IDE 提示和文档，Binder 运行时不读它 |

---

## 二、Environment 与 PropertySource 的职责边界

### 2.1 三个接口，三件事

```text
Environment
├─ PropertyResolver        key → value 的解析与类型转换
├─ Profiles                当前激活 / 默认 profile
└─ PropertySources         一组有序的 PropertySource
```

- `PropertySource`：一个命名的键值源。`MapPropertySource`、`PropertiesPropertySource`、`SystemEnvironmentPropertySource`、YAML 加载后的 `OriginTrackedMapPropertySource` 都是它。
- `MutablePropertySources`：内部是一个有序列表，**越靠前优先级越高**，查找时第一个命中的值胜出。
- `PropertyResolver`：负责 `getProperty(key, type)`、`getRequiredProperty`、占位符解析等。

所以「配置优先级」在运行时最终落到一句话：**谁在 `MutablePropertySources` 里更靠前，谁赢。** 装载阶段做的一切工作，本质上都是在决定各来源插入列表的位置。

### 2.2 StandardEnvironment 与 ApplicationEnvironment

Spring Framework 自带 `StandardEnvironment`，它在构造时加入两个源：

```text
systemProperties   （JVM 系统属性，-Dkey=value）
systemEnvironment  （操作系统环境变量）
```

Spring Boot 在非 Web 场景下使用的却是另一个类：

```java
// org.springframework.boot.ApplicationEnvironment（包级私有）
class ApplicationEnvironment extends StandardEnvironment {

    @Override
    protected String doGetActiveProfilesProperty() {
        return null;
    }

    @Override
    protected String doGetDefaultProfilesProperty() {
        return null;
    }

    @Override
    protected ConfigurablePropertyResolver createPropertyResolver(MutablePropertySources propertySources) {
        return ConfigurationPropertySources.createPropertyResolver(propertySources);
    }
}
```

这三个覆盖点都有明确动机：

1. **`createPropertyResolver` 换成 Boot 的实现**：普通 `PropertySourcesPropertyResolver` 只做精确 key 查找；Boot 的 `ConfigurationPropertySourcesPropertyResolver` 会把 key 规范化为 `ConfigurationPropertyName`，再交给适配后的 `ConfigurationPropertySource` 查找，从而理解 Boot 的属性名模型。注意：这并不等于 `@Value` 获得了完整的松散绑定，原因见 8.6。
2. **`doGetActiveProfilesProperty()` 返回 `null`**：阻止 `AbstractEnvironment` 在配置数据装载完成之前，从系统属性/环境变量里惰性读取 `spring.profiles.active`。profile 必须由 `ConfigDataEnvironment` 在解析配置文档之后统一决定，否则会出现「profile 文件还没加载，profile 已经被定死」的循环。
3. **`doGetDefaultProfilesProperty()` 返回 `null`**：同理，默认 profile 也交给 ConfigData 设置（默认值仍是 `default`）。

### 2.3 Environment 能做什么、不能做什么

| 能力 | `Environment` | `@ConfigurationProperties` |
|---|---|---|
| 按 key 取值 | ✅ | ✅（整对象） |
| 类型转换 | ✅ 单值 | ✅ 单值 + 集合 + 嵌套对象 |
| 松散绑定 | ❌（精确 key） | ✅ |
| 校验 | ❌ | ✅（`@Validated`） |
| 配置元数据 | ❌ | ✅（编译期生成） |
| 构造器 / record 绑定 | ❌ | ✅ |
| 失败诊断 | 弱 | 强（FailureAnalyzer） |

这也是为什么 Boot 要在 `Environment` 之上再放一层 `Binder`：`Environment` 解决「值从哪来」，`Binder` 解决「值怎么变成一个对象」。

---

## 三、配置文件是在什么时候进入 Environment 的

### 3.1 时间点：refresh 之前

```java
// SpringApplication#run 的简化顺序
ConfigurableEnvironment environment = prepareEnvironment(listeners, bootstrapContext, applicationArguments);
// ...
context = createApplicationContext();
prepareContext(bootstrapContext, context, environment, listeners, applicationArguments, printedBanner);
refreshContext(context);
```

`prepareEnvironment()` 内部的关键一步是发布 `ApplicationEnvironmentPreparedEvent`。这个事件由 `EnvironmentPostProcessorApplicationListener` 监听，后者从 `META-INF/spring.factories` 里取出所有 `EnvironmentPostProcessor`，排序后逐个执行。

注意：Spring Boot 3 的**自动配置**候选已经迁移到 `META-INF/spring/...AutoConfiguration.imports`，但 `EnvironmentPostProcessor` 仍然走 `spring.factories`。两者不是同一套机制，不要混淆。

### 3.2 EnvironmentPostProcessor 链

`spring-boot-3.3.9.jar` 的 `META-INF/spring.factories` 中注册了这些 EPP：

```properties
org.springframework.boot.env.EnvironmentPostProcessor=\
org.springframework.boot.cloud.CloudFoundryVcapEnvironmentPostProcessor,\
org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor,\
org.springframework.boot.env.RandomValuePropertySourceEnvironmentPostProcessor,\
org.springframework.boot.env.SpringApplicationJsonEnvironmentPostProcessor,\
org.springframework.boot.env.SystemEnvironmentPropertySourceEnvironmentPostProcessor,\
org.springframework.boot.reactor.ReactorEnvironmentPostProcessor
```

关键 Order（`javap` 核对）：

| EPP | Order | 作用 |
|---|---:|---|
| `RandomValuePropertySourceEnvironmentPostProcessor` | `MIN_VALUE + 1` | 加入 `random.*` 源 |
| `SystemEnvironmentPropertySourceEnvironmentPostProcessor` | `MIN_VALUE + 4` | 把 `systemEnvironment` 换成 Boot 的适配版本 |
| `SpringApplicationJsonEnvironmentPostProcessor` | `MIN_VALUE + 5` | 解析 `SPRING_APPLICATION_JSON` |
| `CloudFoundryVcapEnvironmentPostProcessor` | `MIN_VALUE + 5` | 解析 Cloud Foundry 的 `VCAP_*` |
| `ConfigDataEnvironmentPostProcessor` | `MIN_VALUE + 10` | 加载 `application.*`、profile 文件、import |
| `ReactorEnvironmentPostProcessor` | `MAX_VALUE` | Reactor 上下文传播，与配置装载无关 |

排序值越小越先执行。配置装载相关的顺序是：

```text
Random → SystemEnvironment → JSON / CloudFoundry → ConfigData
```

为什么 ConfigData 排在配置装载链的最后？因为 ConfigData 需要先知道「配置名、位置、profile、import」这些元配置，而这些元配置本身可能来自环境变量、系统属性或 `SPRING_APPLICATION_JSON`。先准备好这些「装载参数」，再装载真正的配置文档，顺序不能反。

### 3.3 ConfigData 的主线

`ConfigDataEnvironmentPostProcessor` 的核心方法 `postProcessEnvironment()` 最终调用 `ConfigDataEnvironment.processAndApply()`：

```text
processAndApply()
├─ registerBootstrapBinder()
├─ processInitial()               先处理 spring.config.import 的初始导入
├─ createActivationContext()      决定 active/default profiles
├─ processWithoutProfiles()       装载非 profile 文档
├─ withProfiles()                 合并 profile 组 / include
├─ processWithProfiles()          装载 profile-specific 文档
└─ applyToEnvironment()           把最终 PropertySource 插入 Environment
```

再往下看两个关键类：

- `StandardConfigDataLocationResolver`：把 `classpath:/`、`file:./config/` 这类 location 解析成具体的 `application.properties` / `application.yml` 资源。
- `ConfigDataImporter`：调用 `ConfigDataLoader` 真正读取资源，生成 `ConfigData`（内部是一组 `PropertySource`）。

这里有一个容易被忽略的实现细节：`StandardConfigDataLocationResolver#getReferencesForConfigName()` 对同一个配置名，按 `PropertySourceLoader` 的注册顺序遍历扩展名，并用 `Deque.addFirst()` 插入；`ConfigDataImporter#load()` 又从解析结果列表的**末尾向前**装载。两次方向相反的遍历，最终决定了同目录下 `.properties` 和 `.yml` 的相对优先级。

本文实测（见 4.2）结论是：**同一位置同时存在 `application.properties` 和 `application.yml` 时，`application.properties` 胜出。** 但不要把这个细节当成设计契约；同一个配置项只保留一种格式，才是可维护的做法。

### 3.4 默认搜索位置

`ConfigDataEnvironment` 的静态常量直接给出了默认值：

```java
// javap 反编译
DEFAULT_SEARCH_LOCATIONS = {
    ConfigDataLocation.of("optional:classpath:/;optional:classpath:/config/"),
    ConfigDataLocation.of("optional:file:./;optional:file:./config/;optional:file:./config/*/")
};
```

展开后按优先级从低到高：

| 优先级 | 位置 | 说明 |
|---:|---|---|
| 1（最低） | `classpath:/` | jar 内根目录 |
| 2 | `classpath:/config/` | jar 内 config 目录 |
| 3 | `file:./` | 当前工作目录 |
| 4 | `file:./config/` | 当前目录的 config 子目录 |
| 5（最高） | `file:./config/*/` | config 下的直接子目录（如 `config/prod/`） |

外层优先于内层，文件系统优先于 classpath。`optional:` 前缀表示「文件不存在不算失败」；默认位置全部带 `optional:`，所以一个没有任何配置文件的 Boot 应用也能启动。

### 3.5 为什么必须在 refresh 之前

配置在 `refresh()` 之前进入 `Environment`，不是因为「顺手」，而是因为 refresh 中大量机制都要读配置：

- `@ConditionalOnProperty` / `@ConditionalOnClass` 的条件评估；
- 数据源、连接池、Redis、MQ 等自动配置的创建；
- 日志级别、Banner、应用名；
- `@Profile` 的 Bean 过滤；
- `ConfigurationClassPostProcessor` 解析配置类时用到的属性。

如果配置等到 refresh 中才可用，这些机制就只能读到默认值。所以 Boot 把「配置装载」提前到容器创建之前，并用 `EnvironmentPostProcessor` 这个扩展点把装载过程模块化。

---

## 四、配置优先级：从低到高

### 4.1 官方优先级表

Spring Boot 文档给出的顺序（后面的覆盖前面的）：

| 顺序 | 来源 | 典型用途 |
|---:|---|---|
| 1（最低） | `SpringApplication.setDefaultProperties` | 代码里的兜底默认值 |
| 2 | `@PropertySource` 注解 | 绑在配置类上的额外文件 |
| 3 | Config Data（`application.properties` / `.yml`） | 应用主配置 |
| 4 | `RandomValuePropertySource`（`random.*`） | 随机端口、测试值 |
| 5 | 操作系统环境变量 | 容器/CI 注入 |
| 6 | JVM 系统属性（`-Dkey=value`） | 启动脚本 |
| 7 | JNDI（`java:comp/env`） | 传统 Java EE |
| 8 | `ServletContext` 初始化参数 | Web 容器 |
| 9 | `ServletConfig` 初始化参数 | Web 容器 |
| 10 | `SPRING_APPLICATION_JSON` | 平台注入整段 JSON |
| 11 | 命令行参数（`--key=value`） | 临时覆盖 |
| 12 | 测试类上的 `properties` 属性（`@SpringBootTest(properties=...)`） | 测试 |
| 13 | `@TestPropertySource` | 测试 |
| 14 | `@DynamicPropertySource` | 测试容器动态端口 |
| 15 | Devtools 的 `~/.config/spring-boot` 属性源 | 本地开发 |
| 16（最高） | Devtools 全局设置 | 本地开发 |

记忆方式不是背这张表，而是记住三条设计原则：

1. **越靠近部署环境的来源优先级越高**：镜像里的默认配置 < 环境变量 < 启动参数。
2. **越靠近运行时的来源优先级越高**：文件 < 系统属性 < 命令行。
3. **测试和开发工具可以覆盖一切**：它们是「人为注入」的最高层。

### 4.2 同一位置：properties、yml、profile

实测探针在一个临时目录里同时放了：

```properties
# application.properties
probe.value=from-properties
```

```yaml
# application.yml
probe:
  value: from-yaml
```

```properties
# application-dev.properties
probe.value=from-dev
```

然后分别以「不激活 profile」和「激活 dev」启动：

```text
no-profile probe.value = from-properties
dev-profile probe.value = from-dev
```

两个结论：

- 同一位置，`.properties` 覆盖 `.yml`；
- profile-specific 文件（`application-dev.*`）覆盖非 profile-specific 文件。

profile 文件的规则还有一条：多个 profile 同时激活时，**后声明的 profile 赢**（last-wins）。例如 `spring.profiles.active=prod,debug`，`debug` 的 profile-specific 文件优先于 `prod`。

### 4.3 控制装载行为的四个属性

| 属性 | 作用 | 注意 |
|---|---|---|
| `spring.config.name` | 配置文件名，默认 `application` | 改成 `app` 后读 `app.properties` / `app.yml` |
| `spring.config.location` | **替换**默认搜索位置 | 目录必须以 `/` 结尾；文件则写全名 |
| `spring.config.additional-location` | **追加**到默认位置之前 | 常用于「外部配置目录」 |
| `spring.config.import` | 从其他位置导入配置 | 导入的文档优先级高于导入它的文档 |

几个容易踩的细节：

- `spring.config.location` 是替换，不是追加。只写一个目录，默认的 `classpath:/`、`file:./config/` 就都不再搜索了。
- 目录和文件要分清：`spring.config.location=file:./config/` 是目录；`spring.config.location=file:./config/app.yml` 是文件。
- `spring.config.import` 默认是**强制**的：导入目标不存在会导致启动失败；加 `optional:` 才是可选。这一点和默认搜索位置的 `optional:` 行为相反，容易记混。
- `spring.config.import` 适合接配置中心、Kubernetes ConfigMap、Vault 等外部来源。`configtree:` 前缀可以把「目录下每个文件就是一个 key」的配置树读进来，典型场景是 Kubernetes Secret 挂载。
- 导入文档与导入者同名属性冲突时，**被导入的文档优先**。本文实测：`application.yml` 里 `spring.config.import` 指向 `external.yml`，两边都写 `probe.value`，最终取 `external.yml` 的值。

### 4.4 为什么环境变量高于配置文件，而命令行最高

这不是随意排序，而是部署分层的直接映射：

```text
镜像/包内 application.yml        开发者的默认值
        ↓ 被覆盖
部署平台注入的环境变量            运维/平台的值
        ↓ 被覆盖
启动脚本追加的 -D / --参数        本次启动的临时值
```

如果反过来（文件高于环境变量），容器平台就无法在不改镜像的前提下调整配置；如果命令行不高于环境变量，`--server.port=0` 这类临时覆盖就会失效。优先级顺序本质上是「谁更接近本次运行，谁说话更算数」。

### 4.5 一组可复现的覆盖矩阵

| 来源 | 写法 | 相对优先级 |
|---|---|---:|
| 默认值 | `SpringApplication.setDefaultProperties` | 最低 |
| `application.properties` | `app.name=file` | 3 |
| `application-dev.properties` | 激活 dev 时 | 高于普通文件 |
| 环境变量 | `APP_NAME=env` | 高于配置文件 |
| 系统属性 | `-Dapp.name=sys` | 高于环境变量 |
| 命令行 | `--app.name=cli` | 高于系统属性 |
| 测试属性 | `@SpringBootTest(properties=...)` | 高于命令行 |

环境变量的 key 转换规则见 8.4：`app.name` 要写成 `APP_NAME`，而不是原样照抄。

---

## 五、`@ConfigurationProperties` 的注册

### 5.1 注解本身不会让类变成 Bean

这是最常见的静默失败：

```java
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {
    private String endpoint = "http://localhost:8080";
    // getter / setter
}
```

上面这个类如果没有被任何机制注册成 Bean，Spring 不会绑定它，**也不会报错**。因为 `ConfigurationPropertiesBindingPostProcessor` 只处理「正在被创建的 Bean」；类不在容器里，BPP 根本看不到它。

### 5.2 四种注册方式

| 方式 | 写法 | 适用场景 | 备注 |
|---|---|---|---|
| `@EnableConfigurationProperties` | `@EnableConfigurationProperties(PaymentProperties.class)` | 自动配置类、明确少量属性类 | 同时注册绑定基础设施 |
| `@ConfigurationPropertiesScan` | 启动类上加该注解 | 业务应用批量扫描 | 扫描启动类所在包及子包 |
| `@Component` | `@Component` + `@ConfigurationProperties` | 简单业务项目 | 由组件扫描注册 |
| `@Bean` 方法 | `@Bean @ConfigurationProperties(prefix = "payment")` | 属性类无法改注解、或需要工厂方法 | 不支持构造器绑定（见 6.7） |

推荐顺序：**自动配置/Starter 用 `@EnableConfigurationProperties`，业务应用用 `@ConfigurationPropertiesScan`，`@Component` 只作为简单场景的补充。** 不建议把 `@ConfigurationProperties` 类当成普通 `@Component` 到处扫描，原因是属性类通常属于「配置边界」，显式注册更利于控制装配范围。

### 5.3 `@ConfigurationPropertiesScan` 的扫描逻辑

`@ConfigurationPropertiesScan` 的元注解是：

```java
@Import(ConfigurationPropertiesScanRegistrar.class)
@EnableConfigurationProperties
```

`@EnableConfigurationProperties` 再通过 `@Import(EnableConfigurationPropertiesRegistrar.class)` 注册绑定基础设施；`ConfigurationPropertiesScanRegistrar` 负责扫描属性类。

`ConfigurationPropertiesScanRegistrar` 用 `ClassPathScanningCandidateComponentProvider` 扫描 `@ConfigurationProperties` 类，然后交给 `ConfigurationPropertiesBeanRegistrar` 注册。它的注册方法里有一个关键判断：

```java
private void register(ConfigurationPropertiesBeanRegistrar registrar, Class<?> type) {
    if (!isComponent(type)) {
        registrar.register(type);
    }
}
```

也就是说：**带 `@Component` 的属性类会被扫描器跳过**，因为它已经由组件扫描注册了；跳过是为了避免重复定义。没有 `@Component` 的属性类，才由 `@ConfigurationPropertiesScan` 注册。

### 5.4 为什么必须显式注册：设计动机

这不是「多此一举」，而是三个约束共同作用的结果：

1. **组件扫描只认 stereotype**：`@ConfigurationProperties` 本身不是 `@Component` 的派生注解，`@ComponentScan` 不会把它当成候选组件。要让它参与扫描，只能显式引入扫描器。
2. **构造器绑定需要在 BeanDefinition 阶段做决定**：构造器绑定不能用「先 new 再 set」的方式，必须在 BeanDefinition 上设置 `instanceSupplier`（见 6.6）。这要求注册发生在配置类解析阶段，而不是运行到某个 BPP 时再补。
3. **避免重复定义**：同一个属性类可能同时被 `@ComponentScan`、`@EnableConfigurationProperties`、`@ConfigurationPropertiesScan` 命中。注册器在注册前检查 `containsBeanDefinition`，扫描器跳过 `@Component`，都是为了「只注册一次」。

### 5.5 Bean 名称规则

`ConfigurationPropertiesBeanRegistrar` 的命名规则：

```text
prefix 非空：  <prefix>-<类的全限定名>
prefix 为空：  <类的全限定名>
```

实测：

```text
PROPS bean name = probe-com.sw.yang.springlea.probe.BindingProbeTest$ProbeProps
```

两个后果：

- 同一个属性类如果写了两个不同的 `prefix`，会被注册成两个 Bean（名字不同），这是合法的，但也容易造成困惑。
- 想按名称注入时，不要硬编码这个长名字；优先按类型注入，或用 `@Qualifier` 配合显式 bean 名称。

### 5.6 一个最小可运行示例

```java
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    /** 支付网关地址。 */
    private String endpoint = "http://localhost:8080";

    /** 超时时间，默认 3 秒。 */
    private Duration timeout = Duration.ofSeconds(3);

    private final Retry retry = new Retry();

    public static class Retry {
        private int maxAttempts = 3;
        // getter / setter
    }

    // getter / setter
}
```

```java
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    @Bean
    public PaymentClient paymentClient(PaymentProperties properties) {
        return new PaymentClient(properties.getEndpoint(), properties.getTimeout());
    }
}
```

```yaml
payment:
  endpoint: https://pay.example.com
  timeout: 5s
  retry:
    max-attempts: 5
```

这里体现的是「配置类只负责数据，业务 Bean 通过构造器接收数据」的分层方式；不要把业务逻辑塞进属性类的 setter。

### 5.7 `@Bean` 方法上的 `@ConfigurationProperties`

注解的目标包含 `METHOD`，所以也可以写在 `@Bean` 方法上：

```java
@Configuration
public class PaymentConfig {

    @Bean
    @ConfigurationProperties(prefix = "payment")
    public PaymentProperties paymentProperties() {
        return new PaymentProperties();
    }
}
```

这种写法由 `ConfigurationPropertiesBean.get()` 通过 `RootBeanDefinition.getResolvedFactoryMethod()` 找到工厂方法，再把 Bean 当作 JavaBean 绑定。它适合「属性类不方便改注解」的场景，但要注意：**`@Bean` 方法已经负责实例化，构造器绑定不再适用。**

---

## 六、绑定时机：为什么是 BeanPostProcessor

### 6.1 绑定基础设施

`@EnableConfigurationProperties` 的注册器 `EnableConfigurationPropertiesRegistrar` 会注册三类基础设施：

```java
static void registerInfrastructureBeans(BeanDefinitionRegistry registry) {
    ConfigurationPropertiesBindingPostProcessor.register(registry);
    BoundConfigurationProperties.register(registry);
}

static void registerMethodValidationExcludeFilter(BeanDefinitionRegistry registry) {
    // 注册 MethodValidationExcludeFilter，把 @ConfigurationProperties 排除在方法校验之外
}
```

`ConfigurationPropertiesBindingPostProcessor` 本身是一个 `BeanPostProcessor`：

```java
public class ConfigurationPropertiesBindingPostProcessor
        implements BeanPostProcessor, PriorityOrdered,
                   ApplicationContextAware, InitializingBean {

    public static final String BEAN_NAME =
            "org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor";

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 1;   // PriorityOrdered，几乎最靠前
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!hasBoundValueObject(beanName)) {
            bind(ConfigurationPropertiesBean.get(applicationContext, bean, beanName));
        }
        return bean;
    }
}
```

### 6.2 一次 Bean 创建的时间线

```text
createBeanInstance()                 反射 / 工厂方法创建实例
        ↓
populateBean()                       @Autowired / @Value 注入
        ↓
initializeBean()
  ├─ applyBeanPostProcessorsBeforeInitialization()
  │    ├─ ConfigurationPropertiesBindingPostProcessor   ← 绑定在这里
  │    └─ CommonAnnotationBeanPostProcessor             ← @PostConstruct 在这里
  ├─ invokeInitMethods()                               afterPropertiesSet
  └─ applyBeanPostProcessorsAfterInitialization()      AOP 代理在这里
```

### 6.3 为什么在 `populateBean()` 之后

- 依赖注入必须先完成：属性类可能依赖其他 Bean（虽然不推荐，但语法上允许），Binder 绑定时对象已经存在。
- 绑定要能覆盖 `@Value`：同一个 Bean 上如果既有 `@Value` 注入，又有 `@ConfigurationProperties` 绑定，绑定发生在注入之后，配置源有值时以绑定结果为准。但**不建议混用**，原因见 13.4。
- 对象已经实例化：JavaBean 绑定需要 setter；构造器绑定则在实例创建时就完成，不会走到这里。

### 6.4 为什么在 `@PostConstruct` 之前

`@PostConstruct` 是 Bean 初始化回调，业务代码通常在这里做「依赖就绪后的动作」。如果绑定晚于它，`@PostConstruct` 里读到的就是默认值或 null，这是很难排查的时序 bug。所以 BPP 的 `getOrder()` 设为 `Integer.MIN_VALUE + 1`，确保它排在 `CommonAnnotationBeanPostProcessor` 之前。

### 6.5 为什么用 BPP，而不是新的注入机制

- **统一覆盖所有 Bean 来源**：`@Component`、`@Bean` 方法、`@EnableConfigurationProperties` 注册的 Bean，最终都会经过 BPP。
- **复用 Spring 的生命周期扩展点**：不需要在 `populateBean` 里加特判，也不会与 `@Autowired`、`@Value` 的解析顺序耦合。
- **绑定失败能包装成启动异常**：BPP 中抛出 `ConfigurationPropertiesBindException`，由 FailureAnalyzer 给出可读报告。

### 6.6 构造器绑定为什么绕过 BPP

构造器绑定要求「对象创建时就带着配置值」，不能先 `new` 一个空对象再 set。Boot 的处理方式是：

```text
ConfigurationPropertiesBeanRegistrar.createBeanDefinition()
├─ deduceBindMethod(type)
│    └─ BindConstructorProvider.DEFAULT.getBindConstructor(type, false)
│         ├─ 有可绑定构造器 → BindMethod.VALUE_OBJECT
│         └─ 否则           → BindMethod.JAVA_BEAN
├─ RootBeanDefinition(type)
├─ BindMethodAttribute.set(beanDefinition, bindMethod)
└─ if (VALUE_OBJECT)
      beanDefinition.setInstanceSupplier(
          (beanFactory) -> ConstructorBound.from(beanFactory, beanName, type)
      )
```

`ConstructorBound.from()` 会用 Binder 绑定构造器参数并创建实例。等这个 Bean 进入 `postProcessBeforeInitialization` 时，`hasBoundValueObject(beanName)` 发现 BeanDefinition 上的 `BindMethod` 是 `VALUE_OBJECT`，直接跳过 BPP，避免二次绑定。

一句话总结：**JavaBean 绑定是「先建对象，再填属性」；构造器绑定是「用配置值建对象」。两条路径在 BeanDefinition 阶段就已经分叉。**

### 6.7 `@Bean` 方法 + 构造器绑定的坑

因为 `@Bean` 方法已经 `new` 了对象，`ConfigurationPropertiesBean.get()` 会走 JavaBean 分支：

```java
// ConfigurationPropertiesBean.get 的简化逻辑
if (bindable.getBindMethod() == null && factoryMethod != null) {
    bindable = bindable.withBindMethod(BindMethod.JAVA_BEAN);
}
if (bindable.getBindMethod() != BindMethod.VALUE_OBJECT) {
    bindable = bindable.withExistingValue(bean);
}
```

对 record 或没有 setter 的不可变类，JavaBean 绑定没有可写属性，结果是**静默不绑定**。本文实测：

```java
@Bean
@ConfigurationProperties(prefix = "probe")
RecordProps recordProps() {
    return new RecordProps("created-by-bean-method");
}
```

配置里写 `probe.value=from-config`，最终输出：

```text
BEAN-RECORD value = created-by-bean-method
```

没有异常，也没有生效。结论：**需要构造器绑定的属性类，用 `@EnableConfigurationProperties` 或 `@ConfigurationPropertiesScan` 注册，不要用 `@Bean` 方法。**

---

## 七、Binder 的绑定主线

### 7.1 从 Environment 到 ConfigurationPropertySource

`ConfigurationPropertiesBinder` 创建 Binder 时，先把 `Environment` 里的 `PropertySource` 适配成 `ConfigurationPropertySource`：

```java
private Binder getBinder() {
    return new Binder(
        ConfigurationPropertySources.get(environment),          // 适配后的属性源
        new PropertySourcesPlaceholdersResolver(environment),   // ${...} 解析
        getConversionServices(),                                // 类型转换
        getPropertyEditorInitializer(),
        null, null);
}
```

`ConfigurationPropertySources.get(environment)` 的顺序与 `Environment.getPropertySources()` 一致，因此「谁在 Environment 里靠前，谁先被 Binder 看到」——装载阶段的优先级在这里原样继承。

### 7.2 Binder 的组成

| 组件 | 作用 |
|---|---|
| `Iterable<ConfigurationPropertySource>` | 属性源列表，按优先级排列 |
| `PlaceholdersResolver` | 解析 `${key:default}` |
| `ConversionService` | 字符串 → 目标类型 |
| `BindHandler` | 绑定过程中的拦截器链（校验、未知字段检查等） |
| `BindConstructorProvider` | 决定构造器绑定使用哪个构造器 |

### 7.3 `bind()` 主流程

```java
Binder.bind(prefix, Bindable<T>, BindHandler)
├─ 把 prefix 转成 ConfigurationPropertyName
├─ findProperty()                         先看有没有「标量值」
├─ bindProperty()                         有标量 → 直接转换
├─ bindAggregate()                        集合 / Map / 数组
└─ bindDataObject()                       普通对象
     ├─ JAVA_BEAN     → JavaBeanBinder
     └─ VALUE_OBJECT  → ValueObjectBinder（构造器绑定）
```

`Bindable` 是绑定的目标描述：

- `Bindable.of(MyProperties.class)`：从类型创建新对象；
- `Bindable.ofInstance(existingBean)`：绑定到已有实例（JavaBean 路径）；
- `Bindable.of(type).withBindMethod(VALUE_OBJECT)`：强制构造器绑定。

### 7.4 BindHandler 链

`ConfigurationPropertiesBinder#getBindHandler()` 按顺序包了一层又一层的 handler（源码核对）：

```text
ValidationBindHandler                        有校验器时（最外层）
  └─ NoUnboundElementsBindHandler            ignoreUnknownFields=false 时
       └─ IgnoreErrorsBindHandler            ignoreInvalidFields=true 时
            └─ ConfigurationPropertiesBindHandler
                 └─ IgnoreTopLevelConverterNotFoundBindHandler   （最内层）
                      └─ [Built-in tracking / user advisors]
```

从外到内看：最外层负责校验，往里依次检查「有没有未绑定的配置项」、决定是否忽略字段转换错误，最内层才是 Binder 自己的转换与赋值。排查时以实际异常类型为准（`BindValidationException` / `UnboundConfigurationPropertiesException` / `BindException`），不要只背包装顺序。

对应到注解属性：

| 注解属性 | 默认值 | 效果 |
|---|---:|---|
| `ignoreInvalidFields` | `false` | `true` 时忽略单字段转换失败 |
| `ignoreUnknownFields` | `true` | `false` 时遇到未绑定的配置项直接报错 |

`ignoreUnknownFields = true` 的默认值是「双刃剑」：它让配置可以逐步演进，但也会把拼写错误吞掉。团队里如果要防止 `payment.endpont` 这类 typo，建议在关键属性类上显式设为 `false`。

### 7.5 类型转换

默认转换服务是 `ApplicationConversionService`，它包含 Spring 的通用转换器，以及 Boot 增加的：

- `Duration` / `Period` / `DataSize`；
- 集合、数组、Map 的转换；
- 字符串 → 枚举（大小写不敏感）。

自定义转换有两种方式：

**方式一：独立的 Converter / Formatter Bean，加 `@ConfigurationPropertiesBinding`**

```java
@Component
@ConfigurationPropertiesBinding
public class MoneyConverter implements Converter<String, Money> {

    @Override
    public Money convert(String source) {
        return Money.parse(source);
    }
}
```

`@ConfigurationPropertiesBinding` 本身是一个带 `@Qualifier` 的注解；`ConversionServiceDeducer` 只会收集带这个限定符的 `Converter`、`GenericConverter`、`Formatter`。**不加这个注解的普通 Converter Bean 不会被配置绑定使用。**

**方式二：直接提供一个名为 `conversionService` 的 Bean**

```java
@Bean
public ConversionService conversionService() {
    // 这个 Bean 一旦存在，会整体替换 Binder 的默认转换服务
    return new MyConversionService();
}
```

注意：方式二是「替换」而不是「追加」，会失去 Boot 默认的 `Duration`、`DataSize` 等转换器。除非你有完整实现，否则优先用方式一。

### 7.6 占位符

Binder 解析的是 `${...}` 占位符，不是 SpEL：

```yaml
payment:
  endpoint: ${PAYMENT_ENDPOINT:https://pay.example.com}
  timeout: ${PAYMENT_TIMEOUT:5s}
```

- `${key:default}`：key 不存在时使用默认值；
- 占位符可以嵌套；
- 无法解析的占位符在 Boot 的 Binder 中默认**保留原样**，不会抛异常（`PropertyPlaceholderHelper` 的 `ignoreUnresolvablePlaceholders = true`）；
- `@ConfigurationProperties` 不支持 `#{...}` SpEL；需要 SpEL 时用 `@Value`。

### 7.7 常用类型

```yaml
demo:
  timeout: 5s                 # Duration
  idle-timeout: 30            # 默认毫秒
  size: 10MB                  # DataSize
  ratio: 0.75                 # double
  hosts:
    - a.example.com
    - b.example.com
  flags: a,b,c                # List<String>，逗号分隔
  headers:
    X-Trace-Id: abc
```

- `Duration` 纯数字默认按毫秒解析；想按秒，用 `@DurationUnit(ChronoUnit.SECONDS)` 或直接写 `5s`。
- `DataSize` 支持 `10MB`、`1GB`，纯数字默认字节。
- 简单集合可以用逗号分隔的字符串绑定；YAML 列表更直观。
- `Map` 的 key 如果包含特殊字符（点、斜杠、大写），用中括号语法保留原值，见 8.5。

---

## 八、松散绑定：同一语义的多张写法

### 8.1 为什么需要

配置来源是异构的：

```text
YAML：        my.main-project.person.first-name
properties： my.main-project.person.firstName
环境变量：    MY_MAINPROJECT_PERSON_FIRSTNAME
命令行：      --my.main-project.person.first-name=...
Map：         my.main-project.person.first_name
```

如果 Binder 只认一种写法，同一份配置换个来源就失效。松散绑定（relaxed binding）的作用是：把这些写法归一到同一个 `ConfigurationPropertyName`，再与 Java 属性名匹配。

### 8.2 规范名 `ConfigurationPropertyName`

规则（安全子集）：

- 元素之间用 `.` 分隔；
- 元素由小写字母、数字、`-` 组成，通常以字母开头；
- 索引用 `[0]` 表示，如 `my.list[0].name`；
- 比较时忽略 `-`，索引按数字比较；
- 大写、下划线不是规范形式，但在从属性源「适配」时会被归一化。

### 8.3 等价写法

以下写法都能绑定到 `PaymentProperties.endpointUrl`：

```yaml
payment:
  endpoint-url: https://pay.example.com
```

```properties
payment.endpointUrl=https://pay.example.com
payment.endpoint_url=https://pay.example.com
```

```text
PAYMENT_ENDPOINTURL=https://pay.example.com
PAYMENT_ENDPOINT_URL=https://pay.example.com
```

### 8.4 环境变量的映射与不可逆性

环境变量名只能包含大写字母、数字、下划线（不同操作系统有差异），所以 Boot 做了映射：

```text
payment.endpoint-url  →  PAYMENT_ENDPOINT_URL
payment.endpointUrl   →  PAYMENT_ENDPOINTURL
```

注意这是**有损**的：`PAYMENT_ENDPOINT_URL` 无法区分它原本是 `payment.endpoint-url` 还是 `payment.endpoint.url`。所以：

- 配置项尽量用 kebab-case；
- 不要设计 `payment.endpoint-url` 和 `payment.endpoint.url` 两个语义不同、却映射到同一个环境变量的 key；
- 属性类的前缀必须是规范形式，不能写 `@ConfigurationProperties(prefix = "Payment")` 或 `payment.endpointUrl`。

### 8.5 Map 的 key 与中括号

YAML 的 Map key 可以包含任意字符：

```yaml
demo:
  headers:
    X-Trace-Id: abc
    /api/v1: enabled
```

绑定到 `Map<String, String> headers` 时，key 会保留原值。用 properties 或环境变量表达特殊 key 时，用中括号：

```properties
demo.headers[X-Trace-Id]=abc
demo.headers[/api/v1]=enabled
```

不加中括号，`.` 会被当成层级分隔符，key 就被拆碎了。

### 8.6 `@Value` 与松散绑定的实测差异

我们用一个探针同时验证 `Environment`、`@Value`、`@ConfigurationProperties`：

```java
app.setDefaultProperties(Map.of(
        "probe.kebab-name", "from-kebab",
        "probe.camelName", "from-camel",
        "probe.list", "a,b,c"));
```

结果：

```text
ENV kebabName  = null
ENV kebab-name = from-kebab
@Value          = missing          // @Value("${probe.kebabName:missing}")
PROPS kebabName = from-kebab       // 字段 kebabName
PROPS camelName = from-camel       // 字段 camelName
PROPS list      = [a, b, c]
```

结论：

- `Environment.getProperty("probe.kebabName")` 没有命中 `probe.kebab-name`；带大写的 key 不是规范名，Boot 的规范名解析失败后会退回精确查找；
- `@Value("${probe.kebabName:missing}")` 同样没有命中；
- `@ConfigurationProperties` 的字段 `kebabName` 能命中 `probe.kebab-name`，字段 `camelName` 能命中 `probe.camelName`。

需要补充一个边界：系统环境变量源（`SystemEnvironmentPropertySource`）本身支持把 `.` / `-` 转成 `_` 并大写，所以 `${payment.endpoint-url}` 有可能命中 `PAYMENT_ENDPOINT_URL`。但这是**环境变量源的局部适配**，不是 Binder 的通用松散绑定；换成 properties/yml 文件就不成立。结论仍然成立：**需要类型安全、整对象、松散绑定时用 `@ConfigurationProperties`，不要用 `@Value`。**

---

## 九、结构与类型绑定

### 9.1 集合、数组、Map

```java
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private List<String> hosts = new ArrayList<>();
    private Set<Integer> ports = new LinkedHashSet<>();
    private Map<String, String> headers = new LinkedHashMap<>();
    // getter / setter
}
```

对应配置：

```yaml
demo:
  hosts:
    - a.example.com
    - b.example.com
  ports: 8080,8081,8082
  headers:
    X-Trace-Id: abc
```

- `List` / `Set` / 数组都支持 YAML 列表和逗号分隔字符串；
- 集合字段建议初始化为空集合，避免配置缺失时出现 null；
- `Map` 的值可以是嵌套对象，Binder 会递归绑定。

### 9.2 嵌套对象

```java
public class Retry {
    private int maxAttempts = 3;
    private Duration backoff = Duration.ofMillis(500);
    // getter / setter
}
```

JavaBean 绑定时，嵌套对象有 setter 就能递归绑定。`@NestedConfigurationProperty` 的作用需要澄清：

> 这个注解**不影响运行时绑定**，它只用于让 `spring-boot-configuration-processor` 生成嵌套属性的元数据。没有它，绑定照样工作，只是 IDE 提示可能不完整。

构造器绑定则完全不需要它：嵌套类型由构造器参数的类型推断。

### 9.3 构造器绑定与 record

Boot 3 的规则：

- 类只有一个有参构造器：自动使用构造器绑定，**不需要** `@ConstructorBinding`；
- 有多个构造器：在要使用的构造器上标 `@ConstructorBinding`；
- record：天然使用构造器绑定；
- `@Bean` 方法返回的对象：不走构造器绑定（见 6.7）。

```java
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        String endpoint,
        @DefaultValue("3s") Duration timeout,
        @DefaultValue("3") int maxAttempts) {
}
```

```java
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {
}
```

实测 record 绑定：

```text
RECORD value = from-default-props
RECORD bean name = probe-com.sw.yang.springlea.probe.ConfigDataProbeTest$RecordProps
```

如果参数可能缺失：

```java
public record PaymentProperties(
        String endpoint,
        Optional<Duration> timeout,
        @DefaultValue("3") int maxAttempts) {
}
```

- `Optional<T>` 表示「可以没有」；
- `@DefaultValue` 提供默认值；
- 没有默认值、没有 `Optional`、且类型不允许 `null`（如 `int` 等基本类型）的缺失参数，绑定会失败；`String`、包装类型等可以为 `null` 的参数通常能通过，但业务上是否允许 `null` 应由校验负责。

### 9.4 不可变对象与默认值

构造器绑定的最大价值是「对象创建即完整」：

- 字段可以 `final`；
- 不需要 setter；
- 校验可以在构造完成后立刻执行；
- 不会出现「先 new 空对象，再用 setter 填」的中间状态。

代价是默认值的表达方式不同：JavaBean 用字段初始化，构造器绑定用 `@DefaultValue`、`Optional` 或可空类型。

### 9.5 绑定失败的诊断

绑定失败通常包装成：

```text
ConfigurationPropertiesBindException
  └─ BindException
       ├─ 类型转换失败：字段名、原值、目标类型
       └─ 校验失败：字段名、约束、错误消息
```

Boot 注册了对应的 FailureAnalyzer：

- `BindFailureAnalyzer`：绑定失败；
- `BindValidationFailureAnalyzer`：校验失败；
- `InvalidConfigurationPropertyNameFailureAnalyzer`：属性名/前缀不合法；
- `ConfigDataNotFoundFailureAnalyzer`：`spring.config.import` 的强制导入目标不存在；
- `NotConstructorBoundInjectionFailureAnalyzer`：构造器绑定使用方式错误。

看到这些报告时，先看「属性名 + 原值 + 目标类型」，再回去查配置源，不要一上来就怀疑 Binder。

---

## 十、配置校验

### 10.1 依赖

JSR-303 校验需要 Hibernate Validator。Boot 的 `spring-boot-starter-web` **不再**自动包含它（从 Boot 2.3 起），需要显式引入：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

### 10.2 `@Validated` + 约束注解

```java
@Validated
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    @NotBlank
    private String endpoint;

    @NotNull
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration timeout;

    @Min(1)
    @Max(10)
    private int maxAttempts;

    // getter / setter
}
```

要点：

- 用 `@Validated`，不是 `@Valid`；
- `@DurationUnit` 只决定纯数字的默认单位，不是校验；`@NotNull` 才是非空校验；
- 校验发生在绑定末尾（`ValidationBindHandler`），失败会阻止 Bean 创建；
- 校验的是**绑定后的对象**，不是原始字符串。

### 10.3 嵌套校验

嵌套对象需要 `@Valid` 才会级联：

```java
public class PaymentProperties {

    @Valid
    private Retry retry;
}
```

没有 `@Valid`，`Retry` 内部的约束不会被执行。

### 10.4 自定义 Validator

除了 JSR-303，还可以注册一个名为 `configurationPropertiesValidator` 的 `Validator` Bean：

```java
@Bean
public static Validator configurationPropertiesValidator() {
    return new PaymentPropertiesValidator();
}
```

注意两点：

- Bean 名必须是 `configurationPropertiesValidator`（`ConfigurationPropertiesBinder` 按这个名字查找）；
- 通常声明为 `static`，避免配置类提前实例化导致初始化顺序问题。

`ConfigurationPropertiesBinder#getValidators()` 会组合三类校验器：

```text
configurationPropertiesValidator Bean
  + [存在 @Validated 时] JSR-303 Validator
  + 属性对象自身实现 Validator 时的 self-validator
```

### 10.5 与方法校验的区别

`@Validated` 在普通 Bean 上通常触发 Spring 的方法参数校验（Method Validation）；Boot 特意注册了 `MethodValidationExcludeFilter`，把 `@ConfigurationProperties` 排除在方法校验之外，避免同一个类被两套校验机制重复处理。配置类的校验走的是绑定期的 `ValidationBindHandler`，不是方法拦截。

---

## 十一、Profile 与多环境配置

### 11.1 四个 Profile 属性

| 属性 | 作用 | 默认值 |
|---|---|---|
| `spring.profiles.active` | 当前激活的 profile | 无 |
| `spring.profiles.default` | 未激活任何 profile 时使用的默认 profile | `default` |
| `spring.profiles.include` | 无条件追加 profile | 无 |
| `spring.profiles.group.<name>` | 给 profile 起组名，一次激活多个 | 无 |

```yaml
spring:
  profiles:
    active: prod
    group:
      prod: proddb,prodmq
```

激活 `prod` 时，`proddb`、`prodmq` 也会被激活。

### 11.2 profile-specific 文件

命名规则：

```text
application-{profile}.properties
application-{profile}.yml
```

规则：

- profile-specific 文件覆盖非 profile-specific 文件；
- 多个 profile 同时激活时，后声明的 profile 赢；
- profile-specific 文件不需要写在 `spring.config.activate.on-profile` 里，文件名已经表达了 profile。

### 11.3 多文档 YAML

一个 YAML 文件可以包含多个文档：

```yaml
server:
  port: 8080

---
spring:
  config:
    activate:
      on-profile: dev
server:
  port: 9090
```

要点：

- `spring.config.activate.on-profile` 是 Boot 2.4+ 的写法，替代旧的 `spring.profiles`；
- 只有 profile 激活时，对应文档才会加入；
- 同一属性出现在多个文档时，后加入的文档优先（profile 文档优先于普通文档）。本文实测：同一 `application.yml` 里 `doc1` 之后写 `doc2`，最终取 `doc2`。

### 11.4 profile 文档里的限制

`spring.profiles.active` 和 `spring.profiles.include` **不能**写在 profile-specific 文档里。原因是这会形成循环依赖：要决定加载哪个 profile 文档，必须先知道 active profiles；而 active profiles 又要从 profile 文档里读。Boot 检测到这种写法会抛 `InvalidConfigDataPropertyException`。

正确做法：把 `spring.profiles.active` 放在非 profile-specific 文档、环境变量或命令行里。

### 11.5 为什么要重写 `ApplicationEnvironment` 的 profile 读取

`ApplicationEnvironment#doGetActiveProfilesProperty()` 返回 `null`，是为了避免 `AbstractEnvironment` 在 ConfigData 处理之前，从 `systemProperties` 里惰性读取 `spring.profiles.active`。如果允许提前读取，会出现：

```text
系统属性 spring.profiles.active=prod
  → Environment 提前锁定 prod
  → ConfigData 还没加载 application-prod.yml
  → 结果看到一个「半激活」的 profile
```

Boot 把 profile 的决定权集中到 `ConfigDataEnvironment`，在解析完配置文档、处理完 `spring.profiles.group` / `include` 之后，再统一调用 `setActiveProfiles()` / `setDefaultProfiles()`。

---

## 十二、配置元数据

### 12.1 `spring-boot-configuration-processor`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

它是一个**编译期注解处理器**，不是运行时依赖。它扫描 `@ConfigurationProperties` 类，生成：

```text
target/classes/META-INF/spring-configuration-metadata.json
```

典型内容：

```json
{
  "groups": [
    {
      "name": "payment",
      "type": "com.example.PaymentProperties",
      "sourceType": "com.example.PaymentProperties"
    }
  ],
  "properties": [
    {
      "name": "payment.endpoint",
      "type": "java.lang.String",
      "description": "支付网关地址。",
      "sourceType": "com.example.PaymentProperties"
    }
  ]
}
```

### 12.2 元数据的作用

- IDE 自动补全配置 key；
- 显示类型、默认值、描述；
- 生成配置文档；
- 让 `spring-boot-configuration-processor` 在编译期发现字段/构造器参数的变化。

### 12.3 增量元数据

手写的元数据放在：

```text
src/main/resources/META-INF/additional-spring-configuration-metadata.json
```

编译期会与生成的元数据合并。适合：

- 第三方库没有提供元数据的配置；
- 需要补充 `deprecation`、`defaultValue` 等生成器推断不出的信息；
- 需要声明 `hints`（候选值）。

### 12.4 废弃与默认值

```java
@DeprecatedConfigurationProperty(reason = "改用 payment.endpoint")
public String getHost() {
    return host;
}
```

`@DefaultValue` 用于构造器参数，既参与运行时绑定，也会写入元数据。

### 12.5 为什么元数据缺失不影响运行

Binder 运行时靠**反射 + 注解**绑定，不读 `spring-configuration-metadata.json`。元数据缺失只影响 IDE 提示和文档，不影响绑定结果。反过来说，元数据写错也不会导致绑定错误，只会误导使用者。

---

## 十三、`@Value` 与 `@ConfigurationProperties` 选型

### 13.1 对比表

| 维度 | `@Value` | `@ConfigurationProperties` |
|---|---|---|
| 绑定粒度 | 单个字段 | 整个对象 / 一组字段 |
| 名字匹配 | 精确 key（环境变量源有有限适配） | 松散绑定 |
| 类型转换 | 依赖 `ConversionService` | 依赖 `ConversionService` + 集合/嵌套/构造器 |
| 集合 / Map | 能力有限，通常用 SpEL 拆分或手动处理 | 原生支持 |
| 嵌套对象 | 不支持 | 支持 |
| 校验 | 不支持 | `@Validated` |
| 元数据 | 不支持 | 支持 |
| SpEL | 支持 `#{...}` | 不支持 |
| 构造器 / record | 构造器参数可用，但仍是逐字段 | 原生支持 |
| 缺失值 | 无默认值时启动失败 | 默认 `null` / 默认值，通常不失败 |
| 失败诊断 | 占位符解析异常 | `ConfigurationPropertiesBindException` + FailureAnalyzer |
| 动态刷新 | 需要 `@RefreshScope` | 需要 `@RefreshScope`（绑定是一次性的） |

### 13.2 实测证据

见 8.6 的输出：

```text
@Value          = missing
PROPS kebabName = from-kebab
PROPS camelName = from-camel
```

同一份配置，`@Value` 用 camelCase key 没命中，`@ConfigurationProperties` 的字段正常绑定。这不是偶然，而是两者查找机制不同：前者是「按 key 取字符串」，后者是「按规范名匹配属性」。

### 13.3 选型建议

- 单个、稳定的标量配置（如 `app.version`）：`@Value` 足够；
- 一组相关配置、需要类型安全、需要校验、需要 IDE 提示：`@ConfigurationProperties`；
- 需要 SpEL（如 `#{systemProperties['os.name']}`）：`@Value`；
- 构造器注入不可变对象 / record：`@ConfigurationProperties`；
- 配置中心动态刷新：两者都需要额外的刷新机制，`@ConfigurationProperties` 通常配合 `@RefreshScope` 使用。

### 13.4 混用时的覆盖顺序

同一个 Bean 上同时使用 `@Value` 和 `@ConfigurationProperties` 时：

1. `populateBean()` 先执行 `@Value` 注入；
2. `postProcessBeforeInitialization()` 再执行 `@ConfigurationProperties` 绑定；
3. 如果配置源里有对应属性，绑定结果会覆盖 `@Value` 注入的值。

但不要依赖这个顺序。混用会让「这个值到底从哪来」变得不可读；一个配置类只保留一种读取方式。

---

## 十四、常见问题排查

### 14.1 配置没生效：先确认它是否在 Environment 里

```java
ConfigurableEnvironment env = context.getEnvironment();
System.out.println(env.getProperty("payment.endpoint"));
env.getPropertySources().forEach(ps -> System.out.println(ps.getName()));
```

如果 `Environment` 里没有，问题在装载阶段；如果有但对象没绑定，问题在绑定阶段。先分层，再看细节。

### 14.2 `@ConfigurationProperties` 没绑定

排查顺序：

1. 类是否被注册？（`@EnableConfigurationProperties` / `@ConfigurationPropertiesScan` / `@Component` / `@Bean`）
2. 前缀是否写对？必须是规范形式（小写、kebab-case）；前缀非法会抛 `InvalidConfigurationPropertyNameException`，并交给 `InvalidConfigurationPropertyNameFailureAnalyzer` 生成报告。
3. 字段是否有 setter（JavaBean）或构造器参数（构造器绑定）？
4. 是否用了 `@Bean` 方法 + record/不可变类？（不会绑定）
5. 是否被 `@Lazy` 延迟创建，导致启动时看不到绑定？

### 14.3 未知字段被忽略

默认 `ignoreUnknownFields = true`，`payment.endpont` 不会报错。关键属性类上设：

```java
@ConfigurationProperties(prefix = "payment", ignoreUnknownFields = false)
```

这样拼写错误会在启动期暴露。

### 14.4 环境变量没生效

- 名字映射：`payment.endpoint-url` → `PAYMENT_ENDPOINT_URL`；
- 不要指望环境变量区分 `payment.endpoint-url` 和 `payment.endpoint.url`；
- 容器里检查变量是否真的注入：`env | grep PAYMENT`；
- IDE 运行配置里的环境变量和系统属性是两回事。

### 14.5 profile 没激活

- 检查 `spring.profiles.active` 写在哪个来源；
- 检查 profile-specific 文件名是否拼对；
- 检查 `spring.config.activate.on-profile` 是否写在正确文档；
- 不要在 profile-specific 文档里写 `spring.profiles.active`。

### 14.6 绑定失败 / 校验失败

看启动日志里的 `ConfigurationPropertiesBindException` / `BindException`。关键信息：

```text
Property: payment.timeout
Value: "abc"
Origin: class path resource [application.yml] - 3:12
Reason: Failed to convert java.lang.String to java.time.Duration
```

`Origin` 会指出「哪个文件、哪一行」，这是排查配置问题最值钱的信息。

### 14.7 打开日志

```properties
# 查看 ConfigData 装载过程
logging.level.org.springframework.boot.context.config=TRACE

# 查看自动配置条件报告
debug=true
```

Actuator 可用时：

```properties
management.endpoints.web.exposure.include=env,configprops
```

- `/actuator/env`：按 PropertySource 展示属性（敏感值脱敏）；
- `/actuator/configprops`：展示已绑定的 `@ConfigurationProperties` Bean。

---

## 十五、源码断点建议

| 想观察什么 | 断点位置 |
|---|---|
| 配置装载入口 | `SpringApplication#prepareEnvironment` |
| EPP 调度 | `EnvironmentPostProcessorApplicationListener#onApplicationEnvironmentPreparedEvent` |
| ConfigData 主线 | `ConfigDataEnvironment#processAndApply` |
| 位置解析 | `StandardConfigDataLocationResolver#resolve` |
| 资源装载 | `ConfigDataImporter#load` |
| 属性源插入 | `ConfigDataEnvironment#applyToEnvironment` |
| Environment 适配 | `ConfigurationPropertySources#get` |
| 绑定入口 | `ConfigurationPropertiesBindingPostProcessor#postProcessBeforeInitialization` |
| 绑定执行 | `ConfigurationPropertiesBinder#bind` |
| Binder 主流程 | `Binder#bind`、`Binder#bindDataObject` |
| JavaBean 绑定 | `JavaBeanBinder#bind` |
| 构造器绑定 | `ValueObjectBinder#bind`、`ConstructorBound#from` |
| 校验 | `ValidationBindHandler#onSuccess` |
| 未知字段检查 | `NoUnboundElementsBindHandler#onSuccess` |
| 绑定失败报告 | `BindFailureAnalyzer`、`BindValidationFailureAnalyzer` |

建议的断点顺序：先在 `prepareEnvironment` 看 Environment 里有什么，再在 `ConfigurationPropertiesBindingPostProcessor` 看绑定了哪个 Bean，最后进 `Binder` 看具体属性匹配。

---

## 十六、必做实验

### 实验一：观察 PropertySource 顺序

```java
try (ConfigurableApplicationContext ctx =
             SpringApplication.run(Application.class, "--demo.value=cli")) {

    ConfigurableEnvironment env = ctx.getEnvironment();
    env.getPropertySources().forEach(ps ->
            System.out.println(ps.getName() + " -> " + ps.getClass().getSimpleName()));

    System.out.println("value = " + env.getProperty("demo.value"));
}
```

预期：`commandLineArgs` 位于列表靠前位置，`application.yml` 靠后；`value` 取命令行值。

### 实验二：properties / yml / profile 覆盖矩阵

在临时目录创建：

```text
application.properties       probe.value=from-properties
application.yml              probe.value: from-yaml
application-dev.properties   probe.value=from-dev
```

分别以「无 profile」和「dev」启动，预期：

```text
no-profile → from-properties
dev        → from-dev
```

本文的实测输出与此一致。

### 实验三：环境变量与命令行覆盖

```bash
PROBE_VALUE=from-env ./mvnw spring-boot:run -Dspring-boot.run.arguments=--probe.value=from-cli
```

预期：命令行覆盖环境变量。再分别去掉命令行、去掉环境变量，观察优先级逐层下降。

### 实验四：注册 vs 不注册

```java
@ConfigurationProperties(prefix = "probe")
static class UnregisteredProps {
    private String value;
    // getter / setter
}
```

在配置类中**不注册**它，直接 `new UnregisteredProps()` 或手动放入容器；观察配置值是否为空。再加 `@EnableConfigurationProperties(UnregisteredProps.class)`，观察绑定生效。

### 实验五：record 构造器绑定 vs `@Bean` 方法

```java
@ConfigurationProperties(prefix = "probe")
record ProbeProps(String value) {
}
```

- 用 `@EnableConfigurationProperties(ProbeProps.class)` 注册：`value` 来自配置；
- 用 `@Bean @ConfigurationProperties(prefix = "probe") ProbeProps probeProps()` 注册：`value` 停留在 `@Bean` 方法里 `new` 的值，配置不生效（本文实测）。

### 实验六：松散绑定与 `@Value` 对比

```java
@Value("${probe.kebabName:missing}")
String value;
```

配置写 `probe.kebab-name=from-kebab`，观察 `@Value` 得到 `missing`；换成 `@ConfigurationProperties` 的字段 `kebabName`，观察绑定成功。

### 实验七：校验失败

引入 `spring-boot-starter-validation`，给 `endpoint` 加 `@NotBlank`，配置空值，观察启动失败日志中的属性名、原值、来源行号。

### 实验八：自定义转换

写一个 `Converter<String, Money>`，加 `@ConfigurationPropertiesBinding`，配置 `payment.amount=¥10.00`，验证绑定成功；去掉注解，验证转换器不再被使用。

### 实验九：配置元数据

引入 `spring-boot-configuration-processor`，编译后查看：

```text
target/classes/META-INF/spring-configuration-metadata.json
```

在 IDEA 中编辑 `application.yml`，观察配置 key 的自动补全和描述。

### 实验十：`spring.config.import`

```yaml
spring:
  config:
    import: optional:file:./external/payment.yml
```

验证：

- 去掉 `optional:`，文件不存在时启动失败；
- 文件存在时，导入的属性覆盖当前文档的同名属性。

---

## 十七、面试常问

### 17.1 Spring Boot 配置加载流程是什么？

`SpringApplication.prepareEnvironment()` 创建 Environment → 发布 `ApplicationEnvironmentPreparedEvent` → `EnvironmentPostProcessor` 链执行 → `ConfigDataEnvironmentPostProcessor` 解析 location/name/profile/import → `ConfigDataImporter` 加载文件 → `applyToEnvironment()` 把 PropertySource 插入 Environment。整个过程发生在 `refresh()` 之前。

### 17.2 配置优先级怎么记？

三条原则：部署环境越近越高（镜像 < 环境变量 < 启动参数）；运行时越近越高（文件 < 系统属性 < 命令行）；测试/Devtools 可以覆盖一切。具体顺序见 4.1。

### 17.3 `@ConfigurationProperties` 的绑定原理？

`ConfigurationPropertiesBindingPostProcessor`（`BeanPostProcessor`，order = `Integer.MIN_VALUE + 1`）在 `postProcessBeforeInitialization` 中调用 `ConfigurationPropertiesBinder`，后者用 `Binder` 按 `prefix` 绑定。JavaBean 绑定在 `populateBean` 之后；构造器绑定在 BeanDefinition 的 `instanceSupplier` 中完成，BPP 会跳过。

### 17.4 `@Value` 和 `@ConfigurationProperties` 的区别？

`@Value` 是逐字段、精确 key、支持 SpEL、不支持松散绑定/校验/元数据；`@ConfigurationProperties` 是整对象、松散绑定、支持集合/嵌套/构造器/校验/元数据。需要一组配置时用后者。

### 17.5 什么是松散绑定？

用 `ConfigurationPropertyName` 把 `kebab-case`、`camelCase`、下划线、环境变量大写等写法归一化到同一语义。比较时忽略 `-`，环境变量用 `_` 映射，映射有损，所以 key 设计要避免歧义。

### 17.6 构造器绑定为什么特殊？

它要求对象创建时就拿到配置值，不能「先 new 再 set」。Boot 在 BeanDefinition 阶段设置 `instanceSupplier`，用 `ConstructorBound.from()` 通过 Binder 创建实例；因此不能和 `@Bean` 方法混用。

### 17.7 配置校验怎么做？

引入 `spring-boot-starter-validation`，在 `@ConfigurationProperties` 类上加 `@Validated` 和 JSR-303 约束；嵌套对象加 `@Valid`。校验发生在绑定末尾，失败抛 `ConfigurationPropertiesBindException`。也可以用名为 `configurationPropertiesValidator` 的 Bean 做自定义校验。

### 17.8 Profile 的加载规则？

`spring.profiles.active` 决定激活的 profile；profile-specific 文件覆盖普通文件；多 profile last-wins；`spring.profiles.group` 可以一次激活多个；`spring.profiles.active` 不能写在 profile-specific 文档里。

### 17.9 配置元数据是干什么的？

`spring-boot-configuration-processor` 在编译期生成 `spring-configuration-metadata.json`，供 IDE 补全、描述和文档使用；运行时 Binder 不读它，所以元数据缺失不影响绑定。

### 17.10 配置不生效怎么排查？

先分层：`Environment` 里有没有这个 key？有 → 查绑定（注册、前缀、setter/构造器、`@Bean` 方法限制）；没有 → 查装载（位置、profile、import、优先级）。打开 `logging.level.org.springframework.boot.context.config=TRACE` 和 `debug=true` 观察装载与条件报告。

---

## 十八、本篇过关标准

学完本文后，需要能做到：

- 画出「装载阶段 → refresh → 绑定阶段」的主线，并说清分界线。
- 说出 `Environment`、`PropertySource`、`Binder`、`@ConfigurationProperties` 各自解决什么问题。
- 解释 `application.properties` / `application.yml` / profile 文件 / 环境变量 / 命令行的优先级。
- 说出 `ConfigDataEnvironmentPostProcessor` 在什么时候执行，为什么必须在 `refresh()` 之前。
- 解释 `@ConfigurationProperties` 的四种注册方式，以及「只写注解不注册」为什么不报错。
- 解释绑定为什么发生在 `postProcessBeforeInitialization`，构造器绑定为什么绕过它。
- 能写一个 JavaBean 属性类、一个 record 属性类，并分别验证绑定。
- 能解释松散绑定、环境变量映射的有损性、Map 的中括号语法。
- 能配置 `@Validated` 校验，并读懂绑定失败报告。
- 能说清 `@Value` 与 `@ConfigurationProperties` 的差异和选型。
- 能生成并检查配置元数据。

---

## 十九、下一篇建议

第 5 阶段的核心机制到这里告一段落。下一篇进入第 6 阶段 Spring Boot 工程化与生产治理：

```text
05-01-SpringBoot-Actuator-监控.md
```

重点包括：

- Actuator 端点与暴露策略；
- `health` / `metrics` / `env` / `configprops` 的用途；
- Micrometer 指标与自定义指标；
- 生产环境的可观测性边界。
