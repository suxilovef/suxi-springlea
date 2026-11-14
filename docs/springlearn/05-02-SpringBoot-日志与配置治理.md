# Spring Boot 专题：日志体系与配置治理源码深度解析

> 本文是第 6 阶段 Spring Boot 工程化与生产治理的第二篇，承接 `05-01-SpringBoot-Actuator-监控.md`。
>
> 05-01 解决的是「应用运行得好不好」：health、metrics、Prometheus、诊断端点。本文解决另外两个上线后必然会遇到的问题：
>
> 1. **日志**：日志系统在什么时候初始化？`logback-spring.xml` 和 `logback.xml` 到底差在哪？日志级别、格式、文件、异步、traceId、脱敏分别该管什么？
> 2. **配置治理**：04-04 已经讲清配置怎么装载、怎么绑定；本文继续讲「多环境怎么分层、密钥怎么放、配置中心刷新/回滚的边界在哪」。
>
> 基准：Spring Boot `3.3.9` / Logback `1.5.16` / SLF4J `2.0.16` / Log4j2 `2.23.1` / Java `17`。
>
> 本文的类名、默认值用 `javap` 反编译本地 `spring-boot-3.3.9.jar`，并对照 jar 内 `META-INF/spring-configuration-metadata.json` 核对过。

---

## 一、先给结论

### 1.1 日志和配置在同一条启动链上

```text
SpringApplication.run()
├─ ApplicationStartingEvent
│    └─ LoggingApplicationListener 准备日志系统
├─ prepareEnvironment()
│    ├─ EnvironmentPostProcessorApplicationListener   (order = MIN + 10)
│    │    └─ ConfigDataEnvironmentPostProcessor
│    │         └─ application.yml / profile / import 进入 Environment
│    └─ LoggingApplicationListener                    (order = MIN + 20)
│         └─ 用已经装载好的 Environment 初始化日志
│              logging.config / logging.level / logging.pattern / logging.file ...
├─ refresh()
│    └─ @ConfigurationProperties 绑定配置
└─ ApplicationPreparedEvent / ContextClosedEvent
     └─ 注册日志单例（供 Actuator 使用） / 清理日志系统
```

这个顺序很关键：

- **配置先装载，日志后初始化**。所以 `application.yml` 里的 `logging.level.*`、`logging.pattern.*` 能生效。
- 反过来，`logging.config` 如果写在「日志初始化之后才加载」的来源里，就来不及了。
- 日志不是普通 Bean：应用还没创建容器时，启动日志就已经要输出。

### 1.2 日志治理的四个层次

| 层次 | 解决的问题 | 主要手段 |
|---|---|---|
| 采集/输出 | 日志写到哪 | `logging.file.name/path`、ConsoleAppender、RollingFileAppender、stdout |
| 格式 | 人看还是机器看 | `logging.pattern.*`、JSON encoder、字段契约 |
| 关联 | 一次请求怎么串起来 | MDC、traceId/spanId、requestId |
| 安全/治理 | 能不能给别人看 | 脱敏、级别、保留期、访问控制、审计 |

### 1.3 配置治理的四个层次

| 层次 | 解决的问题 | 主要手段 |
|---|---|---|
| 来源与优先级 | 值从哪来、谁覆盖谁 | 04-04：ConfigData、环境变量、命令行、import |
| 分层 | 不同环境/不同模块怎么组织 | profile、外部配置目录、configtree |
| 密钥 | 敏感值怎么放 | 环境变量、K8s Secret、Vault、配置中心加密 |
| 变更 | 改了之后怎么生效、怎么回滚 | `spring.config.import`、配置中心、`@RefreshScope`、灰度/回滚 |

### 1.4 八个常见误解

| 误解 | 事实 |
|---|---|
| `logback.xml` 和 `logback-spring.xml` 等价 | 不等价。`logback.xml` 由 Logback 自己加载，Boot 的 `springProfile`/`springProperty` 扩展不生效 |
| 改了配置中心的日志级别会立即生效 | Boot 本身只在启动时应用 `logging.level`；动态刷新需要额外的 rebinder/刷新机制 |
| 日志里加 traceId 要自己写 MDC | 接入 Micrometer Tracing 后，traceId/spanId 自动进入 MDC；你只需要配置格式 |
| Boot 3.3 自带 JSON 日志 | 没有。`logging.structured.*` 是 Boot 3.4+ 的能力；3.3 需要 logstash encoder 或自定义 encoder |
| 正则脱敏就够了 | 正则是最后一道防线；更可靠的是「不记录敏感字段」+ 字段级 allowlist |
| 生产环境开 DEBUG 没关系 | 日志量、I/O、敏感信息、GC 压力都会上升；DEBUG 只应短时、定向开启 |
| 配置中心能刷新所有 Bean | 只有被刷新机制覆盖的 Bean 才会重建/重绑；很多 Bean 在创建时已经捕获了旧值 |
| 敏感配置不写进 application.yml 就安全 | 环境变量、命令行、日志、异常、Actuator 都可能泄露；要端到端治理 |

### 1.5 版本边界

- **Boot 3.3.9 没有原生结构化日志**：jar 里没有 `logging.structured.*` 属性，也没有对应的 encoder 自动配置。要用 JSON，需要 `logstash-logback-encoder` 或自定义 `Encoder`。
- **Boot 3.4+** 才提供 `logging.structured.format.console=ecs|gelf|logstash` 这类原生能力。本文按 3.3.9 写，升级时可以利用新特性。
- **配置刷新不是 Boot 核心能力**：`/actuator/refresh`、`@RefreshScope`、`ConfigurationPropertiesRebinder` 属于 Spring Cloud Context。Boot 只负责配置装载和绑定，刷新策略要单独设计。

---

## 二、启动链：配置先装载，日志后初始化

### 2.1 两个监听器的顺序

`ApplicationEnvironmentPreparedEvent` 发布后，至少有两个监听器会处理它：

| 监听器 | Order（源码常量） | 作用 |
|---|---:|---|
| `EnvironmentPostProcessorApplicationListener` | `-2147483638`（`MIN + 10`） | 执行 `EnvironmentPostProcessor`，包括 ConfigData |
| `LoggingApplicationListener` | `-2147483628`（`MIN + 20`） | 初始化 `LoggingSystem`，应用 `logging.*` |

数值越小越先执行，所以顺序是：

```text
ConfigData 先装载 → LoggingSystem 后初始化
```

这解释了一个常见问题：

> 为什么 `application.yml` 里的 `logging.level.com.example=DEBUG` 能生效？

因为日志初始化时，`ConfigDataEnvironmentPostProcessor` 已经把 `application.yml` 放进 `Environment` 了。

### 2.2 LoggingApplicationListener 的事件

`LoggingApplicationListener` 监听五类事件：

| 事件 | 动作 |
|---|---|
| `ApplicationStartingEvent` | 选择 `LoggingSystem`，调用 `beforeInitialize()` |
| `ApplicationEnvironmentPreparedEvent` | `LoggingSystem.initialize(...)`，加载日志配置、处理 `--debug`/`--trace`、应用级别 |
| `ApplicationPreparedEvent` | 把 `LoggingSystem`、`LogFile`、`LoggerGroups` 注册为单例，供 Actuator loggers 端点使用 |
| `ContextClosedEvent` | 清理日志系统 |
| `ApplicationFailedEvent` | 清理日志系统 |

`initialize()` 的核心步骤：

```text
LoggingApplicationListener.initialize(environment, classLoader)
├─ LoggingSystem.get(classLoader)             选择 Logback / Log4j2 / JUL
├─ LoggingSystemProperties.apply(...)         把 logging.* 映射为系统属性
├─ LoggingSystem.initialize(...)              加载 logback-spring.xml / log4j2-spring.xml
├─ setLogLevels(...)                          应用 logging.level 和 logging.group
├─ registerShutdownHookIfNecessary(...)       注册日志关闭钩子
└─ initializeSpringBootLogging(...)           处理 Spring Boot 自身的日志级别
```

### 2.3 为什么日志不能是普通 Bean

如果日志系统等到 `refresh()` 才初始化：

- Spring 容器启动过程中的日志无法输出；
- 日志实现（Logback/Log4j2）还没选出来；
- `logging.level.*` 没法在 Bean 创建前生效；
- 早期启动失败（配置解析、Bean 定义错误）没有日志可看。

所以 Boot 用一个**容器外的抽象** `LoggingSystem`，在 `Environment` 准备好后、容器 refresh 之前完成初始化。

`LoggingSystem` 的实现：

| 实现 | 触发条件 |
|---|---|
| `LogbackLoggingSystem` | classpath 有 Logback（默认） |
| `Log4J2LoggingSystem` | classpath 有 Log4j2 且没有 Logback |
| `JavaLoggingSystem` | 只有 JUL |
| `NoOpLoggingSystem` | `org.springframework.boot.logging.LoggingSystem=none` |

可以用系统属性强制指定：

```bash
-Dorg.springframework.boot.logging.LoggingSystem=org.springframework.boot.logging.logback.LogbackLoggingSystem
```

### 2.4 `logging.config` 必须在日志初始化前可见

`logging.config` 指定自定义日志配置文件。它必须来自「日志初始化之前就能读到」的来源：

- 环境变量；
- JVM 系统属性；
- 命令行参数；
- `application.yml`（ConfigData 先于日志初始化）。

不能写在：

- `@PropertySource`：refresh 时才加载；
- 一个依赖日志初始化的 Bean 里；
- 日志初始化之后才 import 的配置中心文档。

### 2.5 多日志实现共存

常见依赖组合：

```text
spring-boot-starter-logging
├─ logback-classic
├─ log4j-to-slf4j       （把使用 Log4j2 API 的库桥接到 SLF4J）
└─ jul-to-slf4j         （把 JUL 桥接到 SLF4J）

spring-boot-starter-log4j2
├─ log4j-slf4j2-impl
├─ log4j-core
└─ 排除 logback-classic
```

注意：`log4j-to-slf4j` 不是 Log4j2 后端，它只是 API 桥接。要真正用 Log4j2，必须引入 `spring-boot-starter-log4j2` 并排除 Logback。两者同时存在会出现「日志重复 / 绑定冲突 / 配置不生效」。

---

## 三、日志配置模型

### 3.1 默认依赖与桥接

默认 `spring-boot-starter-logging` 使用 SLF4J + Logback：

```text
应用代码 → SLF4J API → Logback
              ↑
        JUL / Log4j2 API 通过桥接
```

业务代码只应依赖 SLF4J：

```java
private static final Logger log = LoggerFactory.getLogger(OrderService.class);
```

不要直接依赖 `ch.qos.logback.classic.Logger` 或 `org.apache.logging.log4j.Logger`，否则切换日志实现时会被绑死。

### 3.2 日志配置的加载顺序

`AbstractLoggingSystem.initialize()` 的逻辑可以概括为：

```text
logging.config 有值？
  → 加载指定配置
否则：
  → 找标准配置（logback.xml / logback-test.xml ...）
      找到且没有 logging.file.* → reinitialize
      否则找 -spring 配置（logback-spring.xml ...）
           找到 → Boot 加载
           找不到 → loadDefaults()
```

Logback 的标准位置（源码）：

```text
logback-test.groovy
logback-test.xml
logback.groovy
logback.xml
```

`logback-spring.xml` 由 `getSpringConfigLocations()` 派生出来（在标准名上插入 `-spring`），由 Boot 的 `SpringBootJoranConfigurator` 加载。

### 3.3 `logback.xml` vs `logback-spring.xml`

| 文件 | 加载者 | Boot 扩展 |
|---|---|---|
| `logback.xml` | Logback 自己 | `springProfile`、`springProperty` 不生效 |
| `logback-spring.xml` | Boot 的 `SpringBootJoranConfigurator` | 支持 `springProfile`、`springProperty` |

还有一个容易踩的坑：**如果 classpath 里同时有 `logback.xml` 和 `logback-spring.xml`，`logback.xml` 会先被识别为标准配置**，Boot 扩展可能不生效。建议只保留 `logback-spring.xml`，删除 `logback.xml`。

### 3.4 推荐的 `logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <!-- Boot 默认的 conversion rules / 默认 pattern -->
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <springProperty scope="context" name="APP_NAME"
                    source="spring.application.name" defaultValue="app"/>

    <property name="LOG_PATTERN"
              value="%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] [%correlationId] %logger{40} - %msg%n"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <springProfile name="dev">
        <root level="DEBUG">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="!dev">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

</configuration>
```

关键点：

- `defaults.xml` 注册了 `%clr`、`%wEx`、`%applicationName`、`%correlationId` 等 conversion word；不 include 它，这些词可能不可用。
- `springProperty` 从 `Environment` 读值，适合把 `spring.application.name` 注入日志上下文。
- `springProfile` 根据激活的 profile 选择配置块，避免用代码 if-else 判断环境。
- `%correlationId` 只有在 MDC 中有 `traceId`/`spanId` 时才有内容；没有时输出空白。

### 3.5 Log4j2 替代方案

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-log4j2</artifactId>
</dependency>
```

同时在引入 `spring-boot-starter-logging` 的依赖上排除 `spring-boot-starter-logging`，否则两者冲突。

Log4j2 的 Spring 配置文件：

```text
log4j2-spring.xml
```

它支持 Spring Boot 的 profile 扩展。`logging.log4j2.config.override` 可以指定额外的覆盖配置文件列表，适合在不改主配置的情况下叠加环境配置。

### 3.6 常用日志属性

| 属性 | 默认值 | 作用 |
|---|---|---|
| `logging.level.<logger>` | 继承 root | 设置某个 logger 的级别 |
| `logging.group.<name>` | 无 | 定义 logger 组 |
| `logging.config` | 无 | 指定日志配置文件 |
| `logging.file.name` | 无 | 日志文件名/路径；优先级高于 `path` |
| `logging.file.path` | 无 | 日志目录；只配它时文件名是 `spring.log` |
| `logging.pattern.console` | Boot 默认 | 控制台格式 |
| `logging.pattern.file` | Boot 默认 | 文件格式 |
| `logging.pattern.level` | `%5p` | 级别格式 |
| `logging.pattern.dateformat` | `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` | 日期格式 |
| `logging.pattern.correlation` | 无 | 关联 ID 格式 |
| `logging.charset.console` / `file` | 平台默认 | 字符集 |
| `logging.threshold.console` / `file` | `TRACE` | appender 级最低输出级别 |
| `logging.exception-conversion-word` | `%wEx` | 异常格式 |
| `logging.include-application-name` | `true` | 默认 pattern 中是否包含应用名 |
| `logging.register-shutdown-hook` | `true` | 注册日志关闭钩子 |
| `logging.logback.rollingpolicy.max-file-size` | `10MB` | 单文件上限 |
| `logging.logback.rollingpolicy.max-history` | `7` | 保留天数 |
| `logging.logback.rollingpolicy.total-size-cap` | `0B` | 总容量上限，0 表示不限制 |
| `logging.logback.rollingpolicy.file-name-pattern` | `${LOG_FILE}.%d{yyyy-MM-dd}.%i.gz` | 归档文件名 |
| `spring.output.ansi.enabled` | `detect` | 控制台颜色 |
| `spring.main.log-startup-info` | `true` | 是否打印启动信息 |

已废弃但不建议继续使用：

```text
logging.file                  → logging.file.name
logging.path                  → logging.file.path
logging.file.max-size         → logging.logback.rollingpolicy.max-file-size
logging.file.max-history      → logging.logback.rollingpolicy.max-history
logging.file.total-size-cap   → logging.logback.rollingpolicy.total-size-cap
logging.pattern.rolling-file-name → logging.logback.rollingpolicy.file-name-pattern
```

### 3.7 文件与滚动

```yaml
logging:
  file:
    name: /var/log/order-service/order-service.log
  logback:
    rollingpolicy:
      max-file-size: 50MB
      max-history: 14
      total-size-cap: 2GB
      clean-history-on-start: false
```

规则：

- `logging.file.name` 优先；只配 `logging.file.path` 时，文件名为 `spring.log`；
- `total-size-cap: 0B` 表示不限制，生产上建议设置上限，避免磁盘被日志打满；
- `max-history` 是保留天数，不是文件个数；
- 容器环境优先**输出到 stdout**，由平台/采集器处理轮转和保留；文件日志适合传统部署或需要本地排查的场景。

### 3.8 异步与背压

同步写文件会阻塞业务线程。高吞吐场景可以用 `AsyncAppender`：

```xml
<appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
    <queueSize>1024</queueSize>
    <discardingThreshold>0</discardingThreshold>
    <neverBlock>true</neverBlock>
    <appender-ref ref="FILE"/>
</appender>
```

参数的含义：

| 参数 | 作用 |
|---|---|
| `queueSize` | 队列长度；越大越抗突发，内存占用越高 |
| `discardingThreshold` | 队列剩余多少时开始丢弃低级别日志；`0` 表示不按级别丢弃 |
| `neverBlock` | 队列满时是否阻塞业务线程；`true` 会丢日志，`false` 会阻塞 |

选择：

- **不能丢日志**：`neverBlock=false`，接受业务线程被日志拖慢；
- **不能拖慢业务**：`neverBlock=true`，接受队列满时丢日志；
- 二者不能同时满足，必须按业务重要性选择。

异步日志的另一个坑：MDC 是线程绑定的，异步 appender 通常由工作线程写日志，必须确认 appender 能正确传递 MDC（Logback 的 `AsyncAppender` 会传递 MDC；自定义线程池则要手动处理）。

### 3.9 默认日志格式

Boot 默认 Logback 配置（`DefaultLogbackConfiguration`）大致包含：

```text
日期 级别 PID --- 应用名[线程] 关联ID logger : 消息 异常
```

具体点：

- `%applicationName`：由 `logging.include-application-name=true` 控制，取 `spring.application.name`；
- `${LOG_CORRELATION_PATTERN:-}`：关联 ID 占位符，默认空；设置 `logging.pattern.correlation` 后填充；
- `%clr(...)`：控制台颜色；
- `%wEx` / `%wex`：异常堆栈输出；
- `%PID`：进程号。

不要在生产环境把 pattern 写得太花：字段越少、越稳定，越容易被采集和索引。

### 3.10 测试日志

```java
@ExtendWith(OutputCaptureExtension.class)
class OrderServiceTest {

    @Test
    void logsOrderCreated(CapturedOutput output) {
        orderService.create(...);
        assertThat(output).contains("order created");
    }
}
```

`OutputCaptureExtension` 来自 `spring-boot-test`。测试日志时：

- 断言关键字段/消息，不要断言完整格式；
- 避免把日志当业务契约；
- 敏感日志的测试要验证「不包含密码/token」。

---

## 四、日志级别与动态调整

### 4.1 级别模型

```text
TRACE < DEBUG < INFO < WARN < ERROR < OFF
```

- 每个 logger 有自己的 `configuredLevel`；
- 没配置时继承父 logger，最终到 `ROOT`；
- `effectiveLevel` 是实际生效级别；
- `additivity=true` 时，日志还会传给父 logger 的 appender，可能导致重复输出。

### 4.2 `logging.level` 与 `logging.group`

```yaml
logging:
  level:
    root: INFO
    com.example.order: DEBUG
    org.springframework.web: WARN
  group:
    web: org.springframework.web,com.example.web
    sql: org.springframework.jdbc.core,org.hibernate.SQL
```

设置组级别：

```yaml
logging:
  level:
    web: DEBUG
```

Boot 内置两个组（源码 `DEFAULT_GROUP_LOGGERS`）：

```text
web: org.springframework.http, org.springframework.web,
     org.springframework.boot.actuate.endpoint.web,
     org.springframework.boot.web.servlet.ServletContextInitializerBeans
sql: org.springframework.jdbc.core, org.hibernate.SQL, org.jooq.tools.LoggerListener
```

组是「批量设置级别」的语法糖，不改变 logger 的继承关系。

### 4.3 生产级别矩阵

| 环境 | root | 业务包 | 框架/第三方 |
|---|---|---|---|
| dev | DEBUG | DEBUG | INFO/WARN |
| test | INFO/WARN | INFO | WARN |
| staging | INFO | INFO | WARN |
| prod | INFO | INFO（必要时定向 DEBUG） | WARN |

原则：

- 生产不要 root DEBUG；
- 定向打开某个包，而不是全局；
- 框架日志多数是噪音，SQL 参数日志只在受控环境开；
- 日志级别是配置，不是代码里的 `if (log.isDebugEnabled())` 到处判断。

### 4.4 动态调整：Actuator loggers

05-01 已经讲过端点，这里只强调治理边界：

```bash
# 查看
curl http://localhost:8080/actuator/loggers/com.example.order

# 修改
curl -X POST http://localhost:8080/actuator/loggers/com.example.order \
  -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"DEBUG"}'
```

- 修改**只在当前进程生效**，重启即恢复；
- 不知道是谁改的、什么时候改的，就没有治理；
- 生产上应限制角色、记录审计、设过期时间；
- 不要用动态日志级别代替结构化日志或指标。

### 4.5 动态调整的边界

`LoggingSystem.setLogLevel()` 只改级别，不改：

- appender；
- layout/pattern；
- 输出目标；
- 文件滚动策略；
- 是否异步。

这些属于日志配置文件，改它们通常需要重启。配置中心刷新日志级别也需要额外的 rebinder；Boot 本身只在启动时应用 `logging.level`。

### 4.6 性能注意

- SLF4J 的参数化日志：

  ```java
  log.debug("order {} status {}", orderId, status);
  ```

  比字符串拼接好，但参数对象仍会被求值；昂贵对象用 `isDebugEnabled()` 守卫。

- 不要在热路径打大对象 `toString()`、完整请求体、完整 SQL 结果集；
- 异常只记录一次，避免每层都 `log.error(e.getMessage(), e)`；
- 预期内的业务异常不要打完整堆栈；
- 日志级别的判断在 logger 内部完成，关闭级别时格式化和输出会被跳过。

---

## 五、格式化与结构化日志

### 5.1 文本 vs 结构化

```text
2026-11-11T10:00:00.123+08:00  INFO [http-nio-8080-exec-1] [a1b2c3d4-...] c.e.o.OrderService : order created id=42
```

这是人可读的文本，但机器解析要靠正则，字段一改就崩。结构化日志：

```json
{
  "@timestamp": "2026-11-11T02:00:00.123Z",
  "level": "INFO",
  "service": "order-service",
  "env": "prod",
  "thread": "http-nio-8080-exec-1",
  "logger": "com.example.order.OrderService",
  "traceId": "a1b2c3d4...",
  "spanId": "e5f6...",
  "message": "order created",
  "orderId": 42
}
```

适合：

- 集中式日志平台（ELK/Loki）按字段查询；
- 按 traceId 关联链路；
- 告警规则匹配 level/logger/message；
- 避免正则解析。

### 5.2 Boot 3.3.9 的结构化日志

源码/元数据核对：

```text
spring-boot-3.3.9.jar 中没有 logging.structured.* 属性
也没有结构化日志 encoder 自动配置
```

所以 3.3.9 要用 JSON，需要：

1. `logstash-logback-encoder`（选择与 Logback 1.5.x 兼容的 8.x 版本，以依赖树为准）；
2. 或者自己实现 `Encoder<ILoggingEvent>`；
3. 或者输出文本，由采集器（Vector/Fluent Bit/Filebeat）做结构化解析。

Boot 3.4+ 才有：

```yaml
logging:
  structured:
    format:
      console: ecs   # ecs | gelf | logstash
```

升级到 3.4+ 时可以评估是否移除第三方 encoder。

### 5.3 Logstash encoder 示例

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>spanId</includeMdcKeyName>
        <customFields>{"service":"order-service","env":"prod"}</customFields>
    </encoder>
</appender>
```

输出是单行 JSON，适合容器 stdout 和采集器。注意：

- 不要把整个 MDC 全量输出，避免把敏感上下文带出去；
- 异常堆栈也会被编码，确认采集端能正确展开；
- 单行 JSON 对 grep 不友好，但对机器友好；
- 日志量会变大，注意采集和存储成本。

### 5.4 字段契约

建议固定以下字段：

| 字段 | 说明 |
|---|---|
| `@timestamp` | ISO-8601，建议 UTC |
| `level` | TRACE/DEBUG/INFO/WARN/ERROR |
| `logger` | logger 名称 |
| `thread` | 线程名 |
| `service` | 服务名 |
| `env` | 环境 |
| `version` | 应用版本/commit |
| `traceId` / `spanId` | 链路关联 |
| `message` | 日志消息 |
| `exception` | 异常类/堆栈 |

字段名一旦定下，不要随意改；否则仪表盘、告警、查询都会失效。

### 5.5 日志注入

用户输入直接拼进日志会有两个问题：

- CRLF 注入：伪造日志行；
- JSON 注入：破坏结构化格式。

做法：

- 使用参数化日志，避免拼接；
- 结构化日志由 encoder 转义；
- 文本日志对换行做转义；
- 不要记录未经处理的请求头/请求体。

Logback `replace()` 示例：

```xml
<pattern>%d %-5level %logger{36} - %replace(%msg){'\r?\n', ' '}%n%wEx</pattern>
```

### 5.6 采集链路

推荐：

```text
应用 → stdout（JSON/文本）
  → 容器运行时日志
  → Fluent Bit / Vector / Filebeat
  → Elasticsearch / Loki / ClickHouse
  → Kibana / Grafana
```

文件日志只在：

- 传统虚拟机/物理机部署；
- 需要本地保留原始日志；
- 采集器无法读取 stdout

时使用。容器里同时写 stdout 和文件通常是重复采集。

### 5.7 保留与成本

- 热数据（7~14 天）用于排障；
- 冷数据（30~90 天）用于审计/复盘；
- 错误日志可以保留更久；
- 日志量按服务/环境做预算；
- 高基数内容（请求体、SQL 结果）不要进日志。

---

## 六、链路关联：traceId、spanId、MDC

### 6.1 MDC 是什么

MDC（Mapped Diagnostic Context）是日志框架提供的线程绑定上下文：

```java
MDC.put("traceId", traceId);
try {
    log.info("order created");
}
finally {
    MDC.clear();
}
```

日志 pattern 用 `%X{traceId}` 或 Boot 的 `%correlationId` 输出。MDC 是**线程绑定**的：

- 同一线程内自动可见；
- 线程池/异步任务默认不传递；
- 必须在 finally 清理，避免线程复用时串号。

### 6.2 Micrometer Tracing 自动填充

引入 Micrometer Tracing（Brave 或 OpenTelemetry）后：

```text
HTTP 请求进入
  → Observation/Tracing
    → MDC.put("traceId", ...)
    → MDC.put("spanId", ...)
      → 业务日志自动带上
```

你不需要在每个方法里手动 `MDC.put`。需要做的是：

- 引入 tracing 依赖；
- 配置采样；
- 配置日志 pattern；
- 处理异步线程传播。

完整链路追踪（Span、Exporter、Zipkin/OTel）在第 8 阶段 `07-02-SpringCloud-链路追踪与观测.md` 展开。

### 6.3 Boot 的 correlation 支持

Boot 的 `LogbackLoggingSystem` 提供：

```text
getDefaultLogCorrelationPattern() → "%correlationId"
```

`%correlationId` 由 `CorrelationIdConverter` 实现，它从 MDC 读取：

```text
traceId(32), spanId(16)
```

默认格式（`CorrelationIdFormatter.DEFAULT`）：

```text
[traceId-spanId]
```

没有 MDC 时输出空白。要让默认 pattern 使用它：

```yaml
logging:
  pattern:
    correlation: "%correlationId "
```

或者在自定义 pattern 中直接写 `%correlationId`。

`logging.pattern.correlation` 的元数据说明是：

> Appender pattern for log correlation. Its default value varies according to the logging system.

源码里还有一个 `logging.expect-correlation-id` 常量（默认 false）；它用于让 Boot 在没有显式 pattern 时启用默认关联格式。日常配置建议直接用 `logging.pattern.correlation`，更直观。

### 6.4 日志 pattern 示例

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] [%correlationId] %logger{40} - %msg%n"
    correlation: "%correlationId "
```

或使用 `logback-spring.xml` + `defaults.xml`：

```xml
<include resource="org/springframework/boot/logging/logback/defaults.xml"/>
<property name="LOG_PATTERN"
          value="%d{...} %-5level [%thread] [%correlationId] %logger - %msg%n"/>
```

### 6.5 异步线程传播

以下场景默认拿不到 MDC：

- `@Async` 线程池；
- `CompletableFuture`；
- 自建 `ThreadPoolExecutor`；
- 消息消费者线程；
- 定时任务。

方案：

- Spring 的 `TaskDecorator` 复制 MDC；
- Micrometer Context Propagation 自动传播；
- 在任务入口显式设置 MDC，出口清理；
- 消息头/请求头传递 trace 上下文。

不要在每个业务方法里手工复制 MDC；把它收敛到线程池/框架适配层。

### 6.6 requestId vs traceId

| ID | 用途 |
|---|---|
| traceId | 一次分布式调用链的全局 ID |
| spanId | 链路中单个操作的 ID |
| requestId | 单次请求/任务 ID（可以是 traceId，也可以独立） |
| businessId | 订单号/用户 ID 等业务标识 |

不要把用户 ID/订单号直接做成 metrics tag，但可以放进日志/trace 的 high-cardinality 字段。

### 6.7 常见问题

- 日志里没有 traceId：没有 tracing 依赖、pattern 没配、MDC 键名不对、异步线程没传播；
- traceId 串号：线程池复用时没有清理 MDC；
- 同一请求日志对不上：网关/服务之间没有传递 trace 上下文；
- 采样导致部分请求没有 trace：按业务重要性配置采样，不要全量关闭。

---

## 七、日志脱敏与安全

### 7.1 威胁模型

日志通常会被：

- 多个人查看；
- 集中采集到第三个系统；
- 保留数周/数月；
- 被备份、导出、下载。

所以日志里出现敏感信息，等于把敏感信息复制到了更多地方。**日志删除很难，脱敏必须前置。**

### 7.2 不要记录的内容

| 类别 | 示例 |
|---|---|
| 凭证 | 密码、Token、API Key、Cookie、Authorization 头 |
| 支付信息 | 卡号、CVV、有效期 |
| 个人身份信息 | 身份证号、手机号、邮箱、地址 |
| 请求/响应体 | 完整表单、文件内容、消息正文 |
| SQL 参数 | 带敏感值的 SQL |
| 内部信息 | 数据库连接串、内部 IP、管理端口、密钥路径 |

注意：即使打了码，`Bearer abc...` 的部分片段也可能有价值；最好整段替换。

### 7.3 三层防御

```text
第一层：源头不记录
  日志语句只记录必要字段；DTO 的 toString 不包含敏感字段

第二层：格式层脱敏
  Encoder / Converter / 采集 pipeline 对已知字段打码

第三层：平台层
  访问控制、保留期、审计、导出审批
```

正则是第二层的最后手段，不是第一层。

### 7.4 Logback 自定义 Converter

```java
public class MaskingConverter extends ClassicConverter {

    private static final Pattern CARD =
            Pattern.compile("\\b(\\d{4})\\d{8,12}(\\d{4})\\b");

    @Override
    public String convert(ILoggingEvent event) {
        return CARD.matcher(event.getFormattedMessage())
                .replaceAll("$1********$2");
    }
}
```

```xml
<conversionRule conversionWord="maskedMsg"
                converterClass="com.example.log.MaskingConverter"/>

<pattern>%d %-5level %logger - %maskedMsg%n%wEx</pattern>
```

注意：

- 正则匹配会带来 CPU 开销，热路径慎用；
- 只覆盖已知模式，无法保证覆盖所有敏感信息；
- 结构化日志优先做字段级 allowlist，而不是整条消息正则。

### 7.5 结构化日志的字段级治理

- 只输出白名单字段；
- 明确 `message` 不允许包含请求体；
- 异常消息可能包含 SQL/URL/Token，需要单独处理；
- 采集端可以配置字段删除（如 `Authorization`、`Cookie`）；
- 对 `traceId`/`spanId` 以外的 MDC 键保持谨慎。

### 7.6 日志注入

- 用户输入中的 `\r\n` 可以伪造日志行；
- 结构化日志由 encoder 转义，但仍要防止字段名注入；
- 文本日志使用 `replace()` 或自定义 converter 去除换行；
- 不要直接把请求头/请求体拼进 message。

### 7.7 审计日志

不要把审计日志和业务日志混在一起：

| 类型 | 特点 |
|---|---|
| 应用日志 | 排障为主，可采样、可轮转 |
| 审计日志 | 谁在何时做了什么，要求完整、不可篡改、长期保留 |
| 安全日志 | 登录、权限变更、敏感操作，单独管道 |

审计日志通常需要：

- 独立存储/索引；
- 写入失败告警；
- 防篡改；
- 访问审批；
- 合规保留期。

### 7.8 泄露后的处理

1. 确认泄露范围（日志文件、采集平台、备份）；
2. 立即轮换泄露的凭证；
3. 修正日志语句/脱敏规则；
4. 清理历史日志（如果平台支持）；
5. 复盘：为什么敏感值会进入日志。

---

## 八、日志容量与治理

### 8.1 日志级别矩阵

见 4.3。补充：

- 生产 root `INFO`；
- 业务包 `INFO`；
- 框架 `WARN`；
- `org.springframework.web` 的请求日志按需开 DEBUG；
- SQL 参数日志只在 staging/临时排障开；
- 安全相关 logger 单独评估。

### 8.2 日志预算

按服务估算：

```text
QPS × 每请求日志条数 × 单条平均大小 × 86400
```

例如 100 QPS、每请求 2 条、每条 500B：

```text
100 × 2 × 500 × 86400 ≈ 8.6 GB/天
```

这只是原始日志，还没算索引和副本。控制手段：

- 减少 INFO 级别的高频日志；
- 大对象只在 DEBUG；
- 采样（如每 100 个请求记录一次摘要）；
- 异步 + 丢弃策略；
- 集中采集端做过滤/降采样。

### 8.3 异步、磁盘与背压

见 3.8。生产原则：

- 业务线程不能无限等待日志；
- 队列必须有界；
- 队列满时按业务重要性选择丢弃或阻塞；
- 文件日志必须设置 `total-size-cap`；
- 容器优先 stdout，避免本地磁盘打满。

### 8.4 集中式日志

推荐字段：

```text
service, env, version, level, logger, traceId, spanId, message
```

查询场景：

- 按 traceId 查一次请求的全部日志；
- 按 level=ERROR + service 查异常；
- 按 logger/关键词查特定模块；
- 按时间范围做故障复盘。

### 8.5 告警

不要只按「日志条数」告警，容易误报。更可靠的是：

- ERROR 日志速率突增；
- 特定异常类出现；
- 关键业务事件缺失；
- 日志采集管道中断；
- 脱敏失败/敏感字段命中。

日志告警要和 metrics 告警结合，不能替代。

### 8.6 生产清单

- [ ] 日志输出到 stdout 或受控文件；
- [ ] 生产 root level = INFO，框架 = WARN；
- [ ] 关闭 SQL 参数日志、请求体日志；
- [ ] 配置滚动/保留/总容量；
- [ ] 异步队列有界，明确丢弃策略；
- [ ] 结构化日志字段契约稳定；
- [ ] traceId/spanId 进入日志；
- [ ] 敏感字段脱敏并测试；
- [ ] 日志采集管道有监控；
- [ ] 动态日志级别限制权限并审计；
- [ ] 审计日志独立存储；
- [ ] 泄露应急预案。

---

## 九、配置装载回顾：治理的起点

### 9.1 装载主线

04-04 已经详细拆过，这里只保留主线：

```text
SpringApplication.prepareEnvironment()
  → EnvironmentPostProcessor
    → ConfigDataEnvironmentPostProcessor
      → resolve → load → applyToEnvironment
        → Environment（PropertySource 列表）
          → refresh()
            → @ConfigurationProperties 绑定
```

配置治理的起点是：**你必须知道每个值最终来自哪个 PropertySource**。否则「配置没生效」永远只能靠猜。

### 9.2 优先级速查

从低到高（简化）：

```text
默认值
< @PropertySource
< application.properties / application.yml
< profile-specific 文件
< 环境变量
< 系统属性
< 命令行参数
< 测试属性
```

完整顺序见 04-04。治理时要明确：

- 哪些值允许被环境变量覆盖；
- 哪些值只能由配置中心下发；
- 哪些值是本地默认值；
- 哪些值禁止出现在命令行（会被 `ps` 看到）。

### 9.3 为什么装载之后还要治理

装载解决的是「能读到值」，治理解决的是：

- 值是否放对了地方（密钥、环境、模块）；
- 值是否被正确校验；
- 变更是否可审计、可回滚；
- 刷新是否安全；
- 配置漂移是否可发现。

---

## 十、多环境与 Profile 治理

### 10.1 Profile 的定位

Profile 适合：

- 不同环境的基础设施差异（数据库地址、日志级别、开关）；
- 不同环境的行为差异（mock、调试、特性开关）。

Profile 不适合：

- 多租户隔离（用租户配置/数据隔离）；
- 大量业务开关（用配置项/特性开关系统）；
- 细粒度的用户/请求级差异（用运行时参数）。

### 10.2 文件组织

推荐：

```text
config/
├─ application.yml                公共默认值
├─ application-dev.yml            开发
├─ application-staging.yml        预发
├─ application-prod.yml           生产
└─ secrets/                       K8s Secret / configtree
```

规则：

- 公共默认值放 `application.yml`；
- 环境差异放 profile 文件；
- 密钥不进入 Git；
- 外部配置目录通过 `spring.config.additional-location` 或 `spring.config.import` 挂载；
- 容器环境优先用环境变量/Secret 注入，而不是把文件打进镜像。

### 10.3 命名与归属

建议命名：

```text
<app>.<module>.<key>
```

例如：

```yaml
order:
  payment:
    timeout: 3s
    retry:
      max-attempts: 3
```

治理要求：

- 每个配置项有 owner；
- 有默认值、类型、单位、取值范围；
- 有配置元数据（04-04）；
- 变更走评审；
- 废弃项有迁移说明。

### 10.4 密钥治理

不要：

- 把密码/Token 写进 `application.yml`；
- 把密钥打进镜像；
- 在日志/异常/Actuator 中暴露；
- 用同一个密钥跨环境复用。

推荐：

- 环境变量 + 平台 Secret；
- Kubernetes `configtree:` 挂载；
- Vault/AWS Secrets Manager 等通过 `spring.config.import` 接入；
- 最小权限、定期轮换；
- 启动时校验必需密钥存在，失败快速退出。

```yaml
spring:
  config:
    import:
      - optional:configtree:/etc/app/secrets/
      - optional:file:./config/extra.yml
```

`configtree:` 的目录结构：

```text
/etc/app/secrets/
├─ db/
│  └─ password
└─ payment/
   └─ token
```

对应属性：

```text
db.password
payment.token
```

目录层级变成属性名的层级，叶子文件名就是最后一个元素。不要把层级和分隔符混在一起猜，按目录结构组织最清晰。

### 10.5 配置校验

```java
@Validated
@ConfigurationProperties(prefix = "payment", ignoreUnknownFields = false)
public class PaymentProperties {

    @NotBlank
    private String endpoint;

    @NotNull
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration timeout;

    // getter / setter
}
```

治理要求：

- 关键配置必须 fail fast；
- `ignoreUnknownFields=false` 防止拼写错误；
- 集合/Map 有边界；
- 密钥类配置只校验存在性，不在错误信息里回显值；
- 启动时打印「配置来源/版本」，不打印敏感值。

### 10.6 配置测试

- 用 `ApplicationContextRunner` 验证绑定和校验；
- 用 Testcontainers/本地 profile 验证环境差异；
- 对关键配置做契约测试；
- 启动 smoke test 验证配置中心/密钥挂载。

---

## 十一、配置中心边界与刷新/回滚

### 11.1 Boot 与配置中心的职责

| 能力 | Boot | 配置中心 |
|---|---|---|
| 配置装载 | `ConfigData` / `spring.config.import` | 提供远程数据源 |
| 配置绑定 | `@ConfigurationProperties` | 不负责 |
| 版本/审计 | 无 | 负责 |
| 推送/刷新 | 无（Boot 核心） | 负责触发 |
| 回滚 | 无 | 负责 |

Boot 通过 `ConfigDataLocationResolver` / `ConfigDataLoader` SPI 支持自定义配置源；很多配置中心 starter 就是实现这两个扩展点，再通过 `spring.config.import` 接入。

### 11.2 `spring.config.import` 的治理意义

- 明确声明依赖的外部配置；
- 默认强制存在（失败快速），`optional:` 表示可选；
- 导入的文档优先级高于导入它的文档；
- 适合配置中心、Vault、K8s configtree；
- 比隐式的 bootstrap 上下文更可控（Boot 3 推荐）。

示例（具体前缀以配置中心 starter 文档为准）：

```yaml
spring:
  config:
    import:
      - optional:configtree:/etc/app/secrets/
      - optional:configserver:http://config-server:8888
```

### 11.3 刷新语义

Spring Cloud 环境下，配置变更通常走：

```text
配置中心推送
  → EnvironmentChangeEvent
    → @RefreshScope Bean 被清除/重建
    → ConfigurationPropertiesRebinder 重新绑定
    → 日志级别 rebinder 等
```

关键限制：

- **不是所有 Bean 都会刷新**；
- `@RefreshScope` 是代理 + 延迟重建，调用时才创建新实例；
- 已经注入到其他 Bean 的旧引用可能仍指向旧对象；
- 单例 Bean 内部的字段不会自动更新；
- 线程池、连接池、缓存、事务等资源的重建有副作用；
- 刷新是「部分更新」，不是原子事务。

### 11.4 刷新风险

| 风险 | 说明 |
|---|---|
| 身份变化 | 刷新后的 Bean 与旧引用不是同一个对象 |
| 生命周期 | 旧 Bean 的销毁、连接释放、线程池关闭可能不完整 |
| 部分生效 | 一些 Bean 刷新了，一些没有 |
| 一致性问题 | 刷新过程中请求可能看到新旧配置混合 |
| 回滚困难 | 配置中心回滚了，应用内状态未必回滚 |
| 观测缺失 | 不知道刷新是否成功、影响了哪些 Bean |

因此：

- **静态配置**（数据源、端口、线程池大小）优先重启/滚动发布；
- **动态配置**（开关、阈值、日志级别）才考虑刷新；
- 刷新前做影响评估，刷新后做健康检查和指标观察；
- 关键配置刷新要能回滚，且回滚路径经过演练。

### 11.5 动态与静态分类

| 类型 | 示例 | 建议 |
|---|---|---|
| 静态 | server.port、datasource.url、线程池大小 | 重启/滚动发布 |
| 半动态 | 超时、重试次数、限流阈值 | 刷新 + 观测 + 回滚 |
| 动态 | 特性开关、日志级别、灰度比例 | 配置中心/开关系统 |

### 11.6 变更治理

- 配置变更走 PR/审批；
- 有版本、有 diff、有审计；
- 先在 staging 验证；
- 生产灰度：按实例/流量比例发布；
- 有明确回滚步骤；
- 变更后观察错误率、延迟、日志；
- 记录变更与故障的关联。

配置中心的刷新/灰度/回滚细节在第 8 阶段 `07-05-SpringCloud-灰度发布与配置治理.md` 展开。

---

## 十二、常见问题排查

### 12.1 日志不输出

1. logger 名称是否匹配？
2. `logging.level` 是否覆盖了目标包？
3. appender 是否挂到 root/目标 logger？
4. `additivity=false` 是否切断了父 appender？
5. 是否被 `logback.xml` 覆盖了 `logback-spring.xml`？
6. 异步队列是否满并丢弃？
7. profile 是否激活了错误的配置块？

### 12.2 日志重复

- 同一 appender 被挂到多个 logger；
- `additivity=true` 导致父子都输出；
- 同时存在多个 SLF4J 绑定（Logback + Log4j2）；
- 同时写 stdout 和文件，采集器又采集了两份。

用：

```bash
mvn dependency:tree | grep -E "logback|log4j|slf4j"
```

确认只有一个后端。

### 12.3 日志文件没生成

- `logging.file.name` / `logging.file.path` 是否配置；
- 目录权限；
- 相对路径的当前工作目录；
- 容器里是否应该用 stdout；
- 是否被自定义 logback 配置覆盖。

### 12.4 JSON 日志格式损坏

- encoder 版本与 Logback 不兼容；
- 异常堆栈/多行消息未由 encoder 处理；
- 自定义 `toString` 含未转义字符；
- 采集器按行读取，但日志里有换行。

### 12.5 traceId 缺失

- 没有引入 Micrometer Tracing/Brave/OTel；
- `logging.pattern.correlation` 未配置；
- MDC 键名不是默认的 `traceId`/`spanId`；
- 异步线程没有传播 MDC；
- 采样导致该请求没有 trace。

### 12.6 动态日志级别不生效

- logger 名称不匹配；
- group 覆盖；
- 端点未暴露/未授权；
- 修改的是 `configuredLevel`，但实际生效级别受父 logger 影响；
- 集群只改了一个实例；
- 重启后恢复是预期行为。

### 12.7 日志打满磁盘

- `max-history`/`total-size-cap` 未设置；
- stdout 被容器运行时无限保留；
- DEBUG 级别在热路径；
- 日志里打印大对象/请求体；
- 采集管道故障导致本地堆积。

### 12.8 配置不生效

按 04-04 的顺序：

1. 来源是否有这个 key（`/actuator/env`）；
2. 优先级是否被别人覆盖；
3. profile 是否激活；
4. `spring.config.import` 是否加载；
5. `@ConfigurationProperties` 是否注册；
6. 是否需要刷新而未刷新。

### 12.9 配置刷新后异常

- Bean 是否在刷新范围内；
- 旧引用是否仍指向旧对象；
- 连接池/线程池是否被重建；
- 刷新过程中是否有请求；
- 是否需要改为重启发布。

### 12.10 敏感信息泄露

- 日志语句是否打印了请求体/Token；
- 异常消息是否包含 SQL/URL/密钥；
- Actuator `env`/`configprops` 是否 `show-values=always`；
- 配置中心/CI 日志是否打印了变量；
- 立即轮换泄露凭证并清理历史日志。

---

## 十三、源码断点建议

| 想观察什么 | 断点位置 |
|---|---|
| 日志初始化 | `LoggingApplicationListener#onApplicationEnvironmentPreparedEvent` |
| 日志级别应用 | `LoggingApplicationListener#setLogLevels` |
| 日志系统选择 | `LoggingSystem#get` |
| 自定义配置加载 | `LogbackLoggingSystem#loadConfiguration` |
| Spring 扩展解析 | `SpringBootJoranConfigurator#processModel` |
| logging.* 映射 | `LoggingSystemProperties#apply` |
| 文件路径决策 | `LogFile#get` |
| 默认 pattern/转换器 | `DefaultLogbackConfiguration#defaults` |
| 关联 ID | `CorrelationIdConverter#convert` |
| 动态级别 | `LoggersEndpoint#configureLogLevel` |
| 配置装载 | `ConfigDataEnvironmentPostProcessor#postProcessEnvironment` |
| 配置绑定 | `ConfigurationPropertiesBindingPostProcessor#postProcessBeforeInitialization` |
| 日志测试捕获 | `OutputCaptureExtension` |

---

## 十四、必做实验

### 实验一：默认日志与级别

1. 启动应用，观察默认日志格式；
2. 设置 `logging.level.root=WARN`，观察启动日志变化；
3. 设置 `logging.level.com.example=DEBUG`，只打开业务包；
4. 对比 `logging.threshold.console=INFO` 与 logger 级别的区别。

### 实验二：logger group

```yaml
logging:
  group:
    app: com.example.order,com.example.payment
  level:
    app: DEBUG
```

观察两个包的日志级别同时变化；再设置单个包覆盖组。

### 实验三：logback-spring.xml

1. 创建 `logback-spring.xml`，使用 `<springProfile>` 区分 dev/prod；
2. 使用 `<springProperty>` 读取 `spring.application.name`；
3. 对比删除 `logback-spring.xml`、只保留 `logback.xml` 时 `springProfile` 是否生效；
4. 同时放两个文件，观察哪个生效。

### 实验四：文件与滚动

```yaml
logging:
  file:
    name: ./logs/app.log
  logback:
    rollingpolicy:
      max-file-size: 1MB
      max-history: 3
      total-size-cap: 5MB
```

快速写日志触发滚动，观察归档文件和总容量。

### 实验五：异步 appender

配置 `AsyncAppender`，把 `queueSize` 设为 16，快速写日志，观察队列满时 `neverBlock` 的行为（丢日志 vs 阻塞）。

### 实验六：JSON 日志

1. 引入兼容 Logback 1.5 的 `logstash-logback-encoder`；
2. 配置 JSON encoder；
3. 用 `jq` 解析输出，确认 traceId/level/message 字段；
4. 对比 Boot 3.3.9 与 Boot 3.4+ 的 `logging.structured.*`。

### 实验七：traceId

1. 引入 Micrometer Tracing + Brave 或 OTel；
2. 设置 `logging.pattern.correlation: "%correlationId "`；
3. 发起请求，观察日志中的 `[traceId-spanId]`；
4. 在 `@Async` 方法里观察 MDC 是否丢失，再用 `TaskDecorator` 修复。

### 实验八：脱敏

1. 写一个记录手机号/卡号的日志；
2. 用自定义 `ClassicConverter` 做正则脱敏；
3. 测试日志中不再出现完整敏感值；
4. 评估正则成本和漏网风险，改为源头不记录敏感字段。

### 实验九：动态日志

1. 用 `/actuator/loggers` 修改某个包为 DEBUG；
2. 观察日志变化；
3. 重启应用，确认恢复；
4. 检查安全配置和审计。

### 实验十：多环境配置

1. 准备 `application.yml`、`application-dev.yml`、`application-prod.yml`；
2. 用 `spring.profiles.active` 切换；
3. 用环境变量覆盖一个值；
4. 用 `spring.config.additional-location` 挂载外部配置；
5. 用 `configtree:` 模拟 K8s Secret。

### 实验十一：配置校验

1. 给关键属性加 `@Validated` + `@NotBlank`；
2. 配置空值，观察启动失败；
3. 设置 `ignoreUnknownFields=false`，制造拼写错误；
4. 观察 `ConfigurationPropertiesBindException` 和 FailureAnalyzer。

### 实验十二：配置刷新边界（可选）

在 Spring Cloud 环境下：

1. 改一个 `@RefreshScope` Bean 的属性，触发刷新；
2. 观察新实例；
3. 改一个普通单例 Bean 的属性，观察不生效；
4. 改数据源/线程池配置，评估是否需要重启。

没有 Spring Cloud 环境时，用 `Environment` 动态修改 + 观察已绑定 Bean 不变化，理解「绑定是一次性的」。

---

## 十五、面试常问

### 15.1 日志系统什么时候初始化？

在 `ApplicationEnvironmentPreparedEvent` 之后、`refresh()` 之前。`EnvironmentPostProcessorApplicationListener`（`MIN+10`）先装载 ConfigData，`LoggingApplicationListener`（`MIN+20`）再用 Environment 初始化 `LoggingSystem`。

### 15.2 `logback.xml` 和 `logback-spring.xml` 的区别？

`logback.xml` 由 Logback 自己加载，Boot 的 `springProfile`/`springProperty` 扩展不生效；`logback-spring.xml` 由 Boot 的 `SpringBootJoranConfigurator` 加载，支持 Spring 扩展。两者同时存在时，标准 `logback.xml` 可能优先。

### 15.3 Boot 的日志级别怎么生效？

`LoggingApplicationListener#setLogLevels` 读取 `logging.level` 和 `logging.group`，通过 `LoggingSystem#setLogLevel` 应用到具体日志实现；支持运行时通过 Actuator 修改，但不持久化。

### 15.4 日志级别和 threshold 的区别？

logger 级别决定「这个 logger 是否处理日志」；appender 的 `logging.threshold.console/file` 决定「即使 logger 放行，appender 是否输出」。两者可以组合，例如 logger DEBUG + console threshold INFO。

### 15.5 怎么把 traceId 放进日志？

接入 Micrometer Tracing，traceId/spanId 自动进入 MDC；配置 `logging.pattern.correlation: "%correlationId "` 或使用 `%X{traceId}`。异步线程要传播 MDC。

### 15.6 日志脱敏怎么做？

首选源头不记录敏感字段；其次结构化日志字段级 allowlist；最后才用正则/Converter/pipeline 脱敏。正则不是万能的，且要注意性能。

### 15.7 生产日志级别怎么定？

root INFO，业务 INFO，框架 WARN；DEBUG 只对特定包短时开启；SQL 参数、请求体日志在生产关闭；日志量按预算控制。

### 15.8 日志写文件还是 stdout？

容器优先 stdout，由平台采集和轮转；文件适合传统部署或本地排障。容器同时写 stdout 和文件通常会造成重复采集。

### 15.9 异步日志会不会丢日志？

会。`neverBlock=true` 队列满时丢；`neverBlock=false` 会阻塞业务线程。必须按业务重要性选择，并设置合理队列和磁盘上限。

### 15.10 配置中心能刷新所有配置吗？

不能。只有被刷新机制覆盖的 Bean 才会重建/重绑；单例内部字段、已注入的旧引用、线程池/连接池等都有边界。静态配置优先重启发布。

### 15.11 多环境配置怎么治理？

公共默认值放 `application.yml`，环境差异放 profile，密钥放 Secret/configtree，外部配置用 `additional-location`/`import`，关键配置校验 fail fast，变更走评审和回滚。

### 15.12 配置刷新有什么风险？

部分生效、旧引用、Bean 身份变化、资源重建、刷新过程请求看到混合配置、回滚不彻底。刷新前评估影响，刷新后观测，关键配置准备回滚。

---

## 十六、本篇过关标准

学完本文后，需要能做到：

- 画出启动链中「配置装载 → 日志初始化 → 配置绑定」的顺序，并解释监听器 order。
- 说清 `LoggingSystem` 的抽象和 Logback/Log4j2/JUL 的选择。
- 区分 `logback.xml` 与 `logback-spring.xml`，能写出带 `springProfile`/`springProperty` 的配置。
- 配置日志级别、logger group、文件滚动、异步 appender，并说明取舍。
- 解释 Boot 3.3.9 为什么没有原生结构化日志，怎么接 JSON encoder。
- 解释 MDC、traceId/spanId、`%correlationId` 的来源和异步传播问题。
- 设计日志脱敏方案，知道正则的边界。
- 设计日志级别矩阵、容量预算、集中采集和告警。
- 解释配置装载与治理的区别，设计多环境/profile/密钥方案。
- 解释配置中心刷新语义、风险和静态/动态配置的分类。
- 能排查日志不输出、重复、traceId 缺失、配置不生效、刷新异常等问题。

---

## 十七、下一篇建议

下一篇继续第 6 阶段：

```text
05-03-SpringBoot-测试与部署.md
```

重点包括：

- `@SpringBootTest` 与切片测试；
- MockMvc / WebTestClient / Testcontainers；
- 测试配置与 profile；
- 可执行 jar、分层 jar、Docker 镜像；
- 优雅停机与发布策略；
- 启动耗时与发布前检查。
