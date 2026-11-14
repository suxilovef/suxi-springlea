# Spring Boot 专题：Actuator 与生产监控源码深度解析

> 本文是第 6 阶段 Spring Boot 工程化与生产治理的第一篇，承接 `04-04-SpringBoot-配置绑定.md`。
>
> 前五个阶段解决的是「应用能跑起来」：容器、AOP、MVC、事务、自动配置、Starter、配置绑定。从这一篇开始，问题变成「应用上线后，你怎么知道它还好不好」。
>
> 本文只回答四个问题：
>
> 1. Actuator 的端点是怎么被定义、筛选、暴露成 HTTP 接口的？
> 2. `/actuator/health` 应该怎么设计，liveness / readiness 到底该检查什么？
> 3. Micrometer 指标怎么采集，怎么接到 Prometheus，哪些指标真正值得看？
> 4. 生产环境哪些端点绝对不能公开，怎么把监控面收敛到最小？
>
> 基准：Spring Boot `3.3.9` / Micrometer `1.13.x` / Java `17`。
>
> 本文的类名、默认值用 `javap` 反编译本地 `spring-boot-actuator-3.3.9.jar`、`spring-boot-actuator-autoconfigure-3.3.9.jar`，并对照 jar 内 `META-INF/spring-configuration-metadata.json` 核对过。

---

## 一、先给结论

### 1.1 Actuator 不是「一堆 URL」，而是三层结构

```text
Endpoint 定义层
  @Endpoint / @ReadOperation / @WriteOperation / @Selector
        ↓
Endpoint 暴露层
  EndpointDiscoverer → EndpointFilter → IncludeExcludeEndpointFilter
        ↓
Adapter 层
  WebMvc / WebFlux / JMX / Cloud Foundry
```

理解这三层，才能解释两个最常见的问题：

- 为什么端点明明启用了，访问还是 404？——它可能没有 **exposed**。
- 为什么加了 `@Endpoint` 却没有路径？——它可能不是 Bean，或者 ID 不合法，或者被过滤器排除。

### 1.2 三个平面：health、metrics、diagnostics

| 平面 | 回答的问题 | 典型端点 | 使用方式 |
|---|---|---|---|
| health | 现在能不能接流量？ | `/actuator/health`、`/health/liveness`、`/health/readiness` | K8s 探针、负载均衡健康检查 |
| metrics | 运行得好不好？ | `/actuator/metrics`、`/actuator/prometheus` | Prometheus 抓取、Grafana、告警 |
| diagnostics | 为什么是这样？ | `env`、`configprops`、`beans`、`conditions`、`loggers`、`threaddump` | 人工排障，不应对公网开放 |

三者不能互相替代：

- health 是**二元/短状态**，不适合做趋势分析；
- metrics 是**数值时间序列**，不适合判断「这个实例该不该被摘流量」；
- diagnostics 是**快照**，适合定位问题，不适合持续采集。

### 1.3 默认只暴露 health

```yaml
management.endpoints.web.exposure.include: health
```

这是 Spring Boot 的默认值（源码 `WebEndpointProperties$Exposure` 初始化空集合，`EndpointExposure.WEB.getDefaultIncludes()` 返回 `["health"]`）。JMX 默认也只暴露 health；Cloud Foundry 环境默认暴露 `*`。

也就是说：**引入 `spring-boot-starter-actuator` 之后，你能直接访问的 Web 端点只有 `/actuator/health`。** 其他端点即使「启用」，也要显式加入 exposure 才能访问。

### 1.4 六个常见误解

| 误解 | 事实 |
|---|---|
| 引入 Actuator 就自动暴露所有端点 | 默认 Web 只暴露 health；其他端点需要 `management.endpoints.web.exposure.include` |
| `/actuator/health` 默认返回所有组件详情 | 默认 `show-details=never`，只返回聚合状态 |
| 健康检查越全越好 | liveness 检查下游会导致「数据库抖动 → 所有 Pod 重启」的级联故障 |
| `/actuator/metrics` 就是 Prometheus 格式 | 它是 JSON 调试端点；Prometheus 抓取要用 `/actuator/prometheus`，且需要 registry 依赖 |
| 指标 tag 越多越细越好 | 每个 tag 组合都是一条时间序列，高基数会拖垮 Prometheus |
| Actuator 端点不需要安全 | `env`、`configprops`、`heapdump`、`threaddump`、`loggers`、`shutdown` 都是高风险面 |

---

## 二、Actuator 的架构

### 2.1 starter 引入了什么

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

它主要带来：

```text
spring-boot-actuator                  端点定义（Endpoint、Health、MetricsEndpoint 等）
spring-boot-actuator-autoconfigure    端点的自动配置、Web/JMX 适配、暴露策略
micrometer-core                       MeterRegistry / Meter / Observation
micrometer-observation                Observation API
```

它**不会**自带 Prometheus registry。要 `/actuator/prometheus`，还要加：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### 2.2 Endpoint 定义层

一个端点就是一个 Bean：

```java
@Component
@Endpoint(id = "features")
public class FeaturesEndpoint {

    private final Map<String, Boolean> features = new ConcurrentHashMap<>();

    @ReadOperation
    public Map<String, Boolean> features() {
        return features;
    }

    @ReadOperation
    public Boolean feature(@Selector String name) {
        return features.get(name);
    }

    @WriteOperation
    public void configure(@Selector String name, boolean enabled) {
        features.put(name, enabled);
    }

    @DeleteOperation
    public void delete(@Selector String name) {
        features.remove(name);
    }
}
```

暴露后对应：

```text
GET    /actuator/features
GET    /actuator/features/{name}
POST   /actuator/features/{name}     body: {"enabled": true}
DELETE /actuator/features/{name}
```

关键规则：

- 类上必须有 `@Endpoint(id = "...")`，ID 使用小写；
- 读操作用 `@ReadOperation`，写操作 `@WriteOperation`，删除 `@DeleteOperation`；
- `@Selector` 表示从路径中取参数，其余参数从请求体/查询参数转换；
- 端点类必须是 Spring Bean（`@Component` 或 `@Bean`）；
- Web 专属扩展用 `@EndpointWebExtension(endpoint = FeaturesEndpoint.class)`；
- JMX 专属扩展用 `@EndpointJmxExtension`。

`@Endpoint` 有一个容易忽略的属性：

```java
public @interface Endpoint {
    String id();
    boolean enableByDefault() default true;
}
```

`enableByDefault = false` 的端点默认不启用。最典型的是 `ShutdownEndpoint`：

```java
@Endpoint(id = "shutdown", enableByDefault = false)
public class ShutdownEndpoint {
    @WriteOperation
    public ShutdownDescriptor performShutdown() { ... }
}
```

所以 `/actuator/shutdown` **默认不可用**，必须显式配置 `management.endpoint.shutdown.enabled=true` 才存在。

### 2.3 暴露层：发现、过滤、映射

`WebEndpointAutoConfiguration` 负责把端点 Bean 组装成 Web 端点：

```text
WebEndpointAutoConfiguration
├─ WebEndpointDiscoverer               扫描 @Endpoint / @EndpointWebExtension
├─ IncludeExcludeEndpointFilter        按 exposure.include/exclude 过滤
├─ PathMappedEndpoints                 计算 base-path + id + path-mapping
├─ WebMvcEndpointHandlerMapping        Spring MVC 适配（WebFlux 有对应实现）
└─ ControllerEndpointDiscoverer        @ControllerEndpoint / @RestControllerEndpoint
```

`IncludeExcludeEndpointFilter` 的构造参数里传了：

```java
new IncludeExcludeEndpointFilter<>(
        ExposableWebEndpoint.class,
        exposure.getInclude(),
        exposure.getExclude(),
        EndpointExposure.WEB.getDefaultIncludes());  // ["health"]
```

匹配逻辑可以概括为：

```text
include 为空 → 使用 defaultIncludes（Web 是 health）
include 含 * → 全部候选
exclude 含 * 或命中 id → 排除（exclude 优先）
```

这解释了为什么：

- 只写 `management.endpoint.metrics.enabled=true`，`/actuator/metrics` 仍然 404；
- 必须写 `management.endpoints.web.exposure.include=health,metrics`；
- `exclude` 可以覆盖 `include`，用于「先全开再排除」的临时排查。

### 2.4 启用、暴露、访问是三件事

| 概念 | 控制属性 | 作用 |
|---|---|---|
| 是否创建端点 Bean | `management.endpoint.<id>.enabled`、`@Endpoint(enableByDefault)` | 没启用就没有端点 |
| 是否对外暴露 | `management.endpoints.web.exposure.include/exclude` | 启用了也可能不暴露 |
| 是否有权限访问 | Spring Security / `EndpointRequest` / `roles` | 暴露了也可能 401/403 |

一个端点要能访问，必须同时满足：**启用 + 暴露 + 通过安全校验**。排查 404 时按这个顺序查，不要一上来就怀疑路径。

`management.endpoints.enabled-by-default=false` 可以统一关闭所有端点，再逐个打开：

```yaml
management:
  endpoints:
    enabled-by-default: false
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      enabled: true
    info:
      enabled: true
    metrics:
      enabled: true
```

### 2.5 路径规则

默认：

```text
http://host:8080/actuator/{endpointId}
```

可配置：

```yaml
management:
  endpoints:
    web:
      base-path: /actuator          # 默认值
      path-mapping:
        health: healthcheck         # /actuator/healthcheck
```

如果使用独立管理端口：

```yaml
management:
  server:
    port: 8081
    address: 127.0.0.1
    base-path: /manage
  endpoints:
    web:
      base-path: /actuator
      exposure:
        include: health,info,prometheus
```

完整路径是：

```text
http://127.0.0.1:8081/manage/actuator/health
```

`management.server.base-path` 是管理服务器的基础路径，`management.endpoints.web.base-path` 再叠加；两个都配时不要漏算。

### 2.6 独立管理端口的意义

`management.server.port` 与业务端口分离后，管理端点跑在独立的子上下文和独立的 Web 服务器上。收益：

- 网络层隔离：管理端口只绑定内网/回环地址，公网 Ingress 不转发；
- 安全策略分离：业务 API 和管理端点可以有不同的认证/限流；
- 故障隔离：管理端点的异常不容易影响业务端口。

代价：

- 多一个端口要运维；
- K8s 探针、Prometheus 抓取、防火墙规则都要指向新端口；
- 自定义 SecurityFilterChain 时要确认它覆盖了管理上下文。

---

## 三、Health：能不能接流量

### 3.1 Health 模型

```java
public interface HealthIndicator {
    Health health();
}
```

`Health` 由状态 + 详情组成：

```java
Health.up()
      .withDetail("database", "mysql")
      .withDetail("latencyMs", 12)
      .build();

Health.down(ex).withDetail("error", ex.getMessage()).build();
Health.outOfService().build();
Health.unknown().build();
```

默认状态：

```text
UP
DOWN
OUT_OF_SERVICE
UNKNOWN
```

`HealthContributor` 是更宽的标记：既可以是单个 `HealthIndicator`，也可以是由多个指标组成的 `CompositeHealthContributor`。

### 3.2 一次 `/actuator/health` 的调用链

```text
HTTP GET /actuator/health
  → WebMvcEndpointHandlerMapping
    → HealthEndpointWebExtension#health(...)
      → HealthEndpointSupport#getHealth(...)
        → HealthContributorRegistry 取所有 contributor
          → 逐个调用 HealthIndicator#health()
            → StatusAggregator 聚合
              → HttpCodeStatusMapper 映射 HTTP 状态码
```

`HealthEndpoint` 本身有：

```java
public HealthComponent health();
public HealthComponent healthForPath(String... path);
```

`HealthEndpointWebExtension` 负责 Web 侧：

```java
public WebEndpointResponse<HealthComponent> health(
        ApiVersion apiVersion,
        WebServerNamespace serverNamespace,
        SecurityContext securityContext,
        String... path);
```

它决定：

- 是否展示组件（`show-components`）；
- 是否展示详情（`show-details`）；
- 是否有权限看详情（`when-authorized` + `roles`）；
- 返回 200 还是 503。

### 3.3 默认健康指标

Boot 默认会注册一些 contributor（具体取决于 classpath）：

| contributor | 来源 | 默认 |
|---|---|---|
| `ping` | `HealthContributorAutoConfiguration` | 启用 |
| `diskSpace` | `DiskSpaceHealthContributorAutoConfiguration` | 启用，阈值 `10MB` |
| `livenessState` | `AvailabilityProbesAutoConfiguration` | 开启 probes 时注册 |
| `readinessState` | `AvailabilityProbesAutoConfiguration` | 开启 probes 时注册 |
| `db` | DataSource 存在时 | 启用 |
| `redis`、`mongo`、`rabbit` 等 | 对应依赖存在时 | 启用 |

相关属性：

```yaml
management:
  health:
    defaults:
      enabled: true
    diskspace:
      enabled: true
      threshold: 10MB
      path: /data
    ping:
      enabled: true
```

注意：`management.health.livenessstate.enabled` / `readinessstate.enabled` 默认是 `false`，但 `AvailabilityProbesAutoConfiguration` 在 probes 条件满足时会单独注册这两个指标；不要把「health 指标属性」和「probes 配置」混为一谈。

### 3.4 详情、组件与权限

```yaml
management:
  endpoint:
    health:
      show-details: never          # never | when-authorized | always
      show-components: never       # 不配则继承 show-details
      roles: ACTUATOR              # when-authorized 时需要的角色
```

| 配置 | 输出 |
|---|---|
| `show-details: never` | `{"status":"UP"}` |
| `show-components: always`，`show-details: never` | `{"status":"UP","components":{"db":{"status":"UP"},...}}` |
| `show-details: always` | 组件 + 每个组件的 details |
| `show-details: when-authorized` | 认证且具备 `roles` 的用户才看详情 |

默认 `never` 不是「偷懒」，而是避免把数据库地址、异常堆栈、内部主机名暴露给未授权调用方。

### 3.5 状态聚合与 HTTP 状态码

默认状态严重度顺序：

```text
DOWN > OUT_OF_SERVICE > UP > UNKNOWN
```

默认 HTTP 映射：

```text
DOWN              → 503
OUT_OF_SERVICE    → 503
UP                → 200
UNKNOWN           → 200
```

可配置：

```yaml
management:
  endpoint:
    health:
      status:
        order: DOWN,OUT_OF_SERVICE,UP,UNKNOWN
        http-mapping:
          DOWN: 503
          OUT_OF_SERVICE: 503
          UP: 200
          UNKNOWN: 200
```

如果自定义状态（例如 `DEGRADED`），记得同时处理：

- `StatusAggregator` 的排序；
- `HttpCodeStatusMapper` 的映射；
- 监控/探针是否认识这个状态。

### 3.6 Health Group

Group 是「从全量健康指标里挑一个子集，单独暴露一个路径」。

```yaml
management:
  endpoint:
    health:
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
        custom:
          include: diskSpace,db
          show-details: always
          additional-path: custom-health
```

对应路径：

```text
/actuator/health/liveness
/actuator/health/readiness
/actuator/health/custom
/actuator/health/custom-health     （additional-path）
```

Group 的字段：

| 字段 | 作用 |
|---|---|
| `include` | 包含哪些 contributor（名称匹配） |
| `exclude` | 排除哪些 contributor |
| `show-details` | 该组是否展示详情 |
| `show-components` | 该组是否展示组件 |
| `roles` | 查看详情所需角色 |
| `additional-path` | 额外路径 |

`management.endpoint.health.validate-group-membership=true`（默认）会在启动时校验 group 里的名称是否存在；写错名字会直接启动失败，而不是静默少一个指标。这个默认值是「早失败」设计，不要轻易关掉。

### 3.7 liveness、readiness、startup 的分工

| 探针 | 回答的问题 | 失败后果 | 应该检查什么 |
|---|---|---|---|
| liveness | 进程还活着吗？ | 重启 Pod | JVM 是否卡死、死锁、不可恢复状态 |
| readiness | 现在能接流量吗？ | 从 Service 摘除 | 依赖是否就绪、启动是否完成、是否在优雅停机 |
| startup | 第一次启动完成了吗？ | 延迟 liveness 生效 | 启动耗时长的应用 |

最重要的设计原则：

> **liveness 不要检查下游依赖。**

如果 liveness 里检查数据库，数据库抖动 30 秒会导致所有 Pod 同时被重启；重启后数据库仍然不可用，形成级联故障。依赖检查应该放在 readiness，让流量暂时绕开，而不是重启进程。

Boot 的 probes 配置：

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
```

在 Kubernetes 上，`AvailabilityProbesAutoConfiguration$ProbesCondition` 会在检测到 `CloudPlatform.KUBERNETES` 时自动启用 probes；非 K8s 环境默认关闭，需要显式设 `management.endpoint.health.probes.enabled=true`。

K8s 探针示例：

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  periodSeconds: 5
  failureThreshold: 2

startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  periodSeconds: 5
  failureThreshold: 30
```

如果使用独立管理端口，探针端口要改成管理端口；如果希望 probes 同时出现在主端口，设：

```yaml
management.endpoint.health.probes.add-additional-paths: true
```

### 3.8 可用性状态与优雅停机

Boot 用 `ApplicationAvailability` 管理两个状态：

```text
LivenessState:  CORRECT / BROKEN
ReadinessState: ACCEPTING_TRAFFIC / REFUSING_TRAFFIC
```

关键时间点：

```text
启动成功、Runner 执行完
  → ReadinessState.ACCEPTING_TRAFFIC
  → readiness 变 UP

收到停机信号 / Context 关闭
  → ReadinessState.REFUSING_TRAFFIC
  → readiness 变 OUT_OF_SERVICE / DOWN
  → 负载均衡先把流量摘走
  → 再等待在途请求完成
```

与优雅停机配合：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

Boot 会先让 readiness 进入 `REFUSING_TRAFFIC`，K8s 把 Pod 从 Endpoints 摘除，再等待在途请求和线程池收尾。自定义线程池、消息消费者要自己实现 `SmartLifecycle`/`@PreDestroy`，否则「优雅停机」只覆盖了 Web 容器。这部分细节放到 `05-03-SpringBoot-测试与部署.md`。

### 3.9 自定义 HealthIndicator

```java
@Component
public class PaymentGatewayHealthIndicator implements HealthIndicator {

    private final PaymentClient client;

    public PaymentGatewayHealthIndicator(PaymentClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            long start = System.nanoTime();
            client.ping();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return Health.up()
                    .withDetail("gateway", "pay.example.com")
                    .withDetail("latencyMs", latencyMs)
                    .build();
        }
        catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("gateway", "pay.example.com")
                    .build();
        }
    }
}
```

命名规则：Bean 名去掉 `HealthIndicator` / `HealthContributor` 后缀，就是健康组件名。例如 `paymentGatewayHealthIndicator` → `paymentGateway`。Group 的 `include` 要写这个名字。

实现注意：

- 健康检查必须**快**：设超时、避免重查询、避免在 liveness 里调用外部服务；
- 不要吞异常后返回 UP；
- 不要在 health 里做有副作用的操作；
- 慢指标超过 `management.endpoint.health.logging.slow-indicator-threshold`（默认 `10s`）会打警告；
- 大量指标可以并行执行，但线程池要有界。

---

## 四、Metrics：运行得好不好

### 4.1 Micrometer 模型

```text
MeterRegistry
├─ Counter              只增计数
├─ Timer                耗时分布 + 次数
├─ DistributionSummary  数值分布
├─ Gauge                瞬时值（需要强引用）
├─ LongTaskTimer        长任务
├─ FunctionCounter      函数式计数
└─ FunctionTimer        函数式计时
```

每个 Meter 由「名字 + 标签（tags）+ 类型」唯一确定：

```text
http.server.requests{method=GET,uri=/api/orders,status=200,outcome=SUCCESS}
```

Micrometer 是门面，具体后端是 registry：

- `SimpleMeterRegistry`：内存注册表，用于没有 exporter 的场景；
- `CompositeMeterRegistry`：把指标广播到多个 registry；
- `PrometheusMeterRegistry`：Prometheus 后端；
- OTLP、StatsD、Datadog 等：其他后端。

Boot 的 `MetricsAutoConfiguration` 负责：

```text
MetricsAutoConfiguration
├─ Clock
├─ MeterRegistryPostProcessor      绑定 MeterBinder、应用 MeterFilter、customizer
├─ PropertiesMeterFilter           管理 management.metrics.enable / tags
└─ MeterRegistryCloser             关闭 registry
```

### 4.2 默认指标

只要引入了 registry，Boot 会自动装配一批 `MeterBinder`：

| 自动配置 | 指标示例 |
|---|---|
| `JvmMetricsAutoConfiguration` | `jvm.memory.used`、`jvm.gc.pause`、`jvm.threads.live`、`jvm.classes.loaded`、`jvm.buffer.memory.used` |
| `SystemMetricsAutoConfiguration` | `system.cpu.usage`、`system.load.average.1m`、`process.cpu.usage`、`process.uptime`、`disk.free` |
| `LogbackMetricsAutoConfiguration` | `logback.events`（按 level） |
| `TomcatMetricsAutoConfiguration` | `tomcat.sessions.active.current`、`tomcat.threads.busy`、`tomcat.connections.current` |
| `DataSourcePoolMetricsAutoConfiguration` | `hikaricp.connections.active` 等 |
| `CacheMetricsAutoConfiguration` | `cache.gets`、`cache.puts`、`cache.evictions` |
| `TaskExecutorMetricsAutoConfiguration` | `executor.active`、`executor.queued`、`executor.pool.size` |
| `WebMvcObservationAutoConfiguration` | `http.server.requests` |
| `StartupTimeMetricsListenerAutoConfiguration` | `application.started.time`、`application.ready.time` |

注意：指标是否出现取决于依赖和配置。例如没有 DataSource 就不会有 `hikaricp.*`；没有缓存管理器就不会有 `cache.*`。

### 4.3 HTTP 服务指标

Boot 3 通过 Observation 生成 HTTP 指标：

```text
请求进入
  → ServerHttpObservationFilter
    → ObservationRegistry
      → ObservationHandler
        → MeterObservationHandler
          → MeterRegistry
            → http.server.requests
```

默认指标名：

```text
http.server.requests
```

默认标签（来自 `DefaultServerRequestObservationConvention`）：

| tag | 示例 | 说明 |
|---|---|---|
| `method` | `GET` | HTTP 方法 |
| `uri` | `/api/orders/{id}` | **路径模板**，不是原始 URL |
| `status` | `200` | HTTP 状态码 |
| `outcome` | `SUCCESS` | `SUCCESS` / `REDIRECTION` / `CLIENT_ERROR` / `SERVER_ERROR` / `UNKNOWN` |
| `exception` | `none` / `TimeoutException` | 异常类名 |

`uri` 使用路径模板是低基数设计的关键：`/api/orders/1`、`/api/orders/2` 都归一为 `/api/orders/{id}`。如果请求没有匹配到 Handler，`uri` 可能是 `UNKNOWN` 或 `root`；看到大量 `UNKNOWN`，先查路由和 404，而不是查监控。

指标名可改：

```yaml
management:
  observations:
    http:
      server:
        requests:
          name: http.server.requests
```

客户端指标默认是 `http.client.requests`。

### 4.4 自定义指标

**Counter**

```java
@Component
public class OrderMetrics {

    private final Counter created;

    public OrderMetrics(MeterRegistry registry) {
        this.created = Counter.builder("orders.created")
                .description("创建的订单数")
                .tag("channel", "web")
                .register(registry);
    }

    public void onCreated() {
        created.increment();
    }
}
```

**Timer**

```java
Timer timer = Timer.builder("order.lookup")
        .description("订单查询耗时")
        .publishPercentileHistogram()
        .register(registry);

Order order = timer.record(() -> orderService.find(id));
```

**Gauge**

```java
Gauge.builder("order.queue.size", queue, Queue::size)
        .description("订单队列长度")
        .register(registry);
```

Gauge 的坑：注册表只持有弱引用/函数引用，**被观察对象必须自己保持强引用**，否则对象被 GC 后指标变成 NaN。

**MeterBinder**

```java
@Bean
public MeterBinder orderQueueMetrics(OrderQueue queue) {
    return registry -> Gauge.builder("order.queue.size", queue, Queue::size)
            .register(registry);
}
```

**公共标签**

```java
@Bean
public MeterRegistryCustomizer<MeterRegistry> commonTags() {
    return registry -> registry.config().commonTags(
            "application", "order-service",
            "env", "prod");
}
```

或者：

```yaml
management:
  metrics:
    tags:
      application: order-service
      env: prod
```

### 4.5 分布统计：percentile、histogram、SLO

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      slo:
        http.server.requests: 10ms,50ms,100ms,300ms,1s
      percentiles:
        http.server.requests: 0.5,0.95,0.99
      expiry:
        http.server.requests: 2m
      buffer-length:
        http.server.requests: 3
```

区别：

| 配置 | 作用 | Prometheus 结果 |
|---|---|---|
| `percentiles` | 客户端计算分位数 | `http_server_requests_seconds{quantile="0.99"}` |
| `percentiles-histogram` | 生成直方图桶 | `http_server_requests_seconds_bucket{le="..."}` |
| `slo` | 生成 SLO 边界桶 | 同上，桶边界由 SLO 决定 |
| `expiry` / `buffer-length` | 滑动窗口 | 影响客户端分位数的时效性 |

Prometheus 做 `histogram_quantile` 必须有 `_bucket`；只配 `percentiles` 没有 `_bucket`，PromQL 的 `histogram_quantile` 会失效。生产上建议对 HTTP 延迟启用 `percentiles-histogram`，不要依赖客户端分位数（多实例无法正确聚合）。

### 4.6 `@Observed`、`@Timed`、`@Counted`

Boot 3 的推荐方式是 Observation API：

```java
@Observed(name = "order.lookup", contextualName = "order-lookup")
public Order find(Long id) {
    ...
}
```

`@Observed` 由 `ObservedAspect` 处理；`ObservationAutoConfiguration$ObservedAspectConfiguration` 在 AspectJ 存在时注册它。它有 AOP 的同样限制：**自调用不生效**，必须经过 Spring 代理。

`@Timed` / `@Counted` 是 Micrometer 旧注解：

```java
@Timed(value = "order.lookup", percentiles = {0.5, 0.95, 0.99})
public Order find(Long id) { ... }
```

Boot 3.3 的 `MetricsAspectsAutoConfiguration` 只有在下面任一条件成立时才注册 `TimedAspect` / `CountedAspect`：

```yaml
management.observations.annotations.enabled: true
# 或
micrometer.observations.annotations.enabled: true
```

默认两者都是 `false`。所以：

- 「我加了 `@Timed` 但没指标」的第一检查项是这两个属性；
- 更推荐 `@Observed` 或直接注入 `MeterRegistry`/`ObservationRegistry`；
- 不要同时用 `@Observed` 和 `@Timed` 记录同一个方法，容易重复计数。

直接使用 Observation API：

```java
Observation observation = Observation.createNotStarted("order.pay", observationRegistry)
        .lowCardinalityKeyValue("channel", channel)
        .highCardinalityKeyValue("orderId", orderId); // 谨慎：高基数

observation.observe(() -> paymentService.pay(order));
```

低基数 key 可以进指标标签；高基数 key 只适合 trace/日志，不要做成 metrics tag。

### 4.7 `/actuator/metrics` 与 Prometheus 的分工

| 端点 | 格式 | 用途 |
|---|---|---|
| `/actuator/metrics` | JSON | 人查：有哪些指标、有哪些 tag、当前值 |
| `/actuator/metrics/{name}` | JSON | 人查某个指标 |
| `/actuator/prometheus` | Prometheus 文本 | 机器抓取 |

```bash
curl http://localhost:8080/actuator/metrics
curl 'http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/orders/{id}'
```

`/actuator/metrics/{name}` 返回：

```json
{
  "name": "http.server.requests",
  "baseUnit": "seconds",
  "measurements": [
    {"statistic": "COUNT", "value": 120},
    {"statistic": "TOTAL_TIME", "value": 3.2}
  ],
  "availableTags": [
    {"tag": "method", "values": ["GET", "POST"]},
    {"tag": "status", "values": ["200", "500"]}
  ]
}
```

它是调试工具，不是监控采集接口。Prometheus 采集要额外引入 registry 并暴露 `/actuator/prometheus`。

### 4.8 指标基数与命名

高基数是生产监控的头号杀手。以下 tag 不要做：

```text
userId, orderId, requestId, traceId, rawUrl, timestamp
```

它们会让时间序列数量爆炸，最终导致 Prometheus 内存/磁盘打满。正确做法：

- 用路径模板（`/api/orders/{id}`）而不是原始 URL；
- 用有限枚举（status、method、outcome、region、channel）；
- 用户/订单维度放到日志或 trace，不放到 metrics；
- 用 `MeterFilter` 限制或拒绝高基数 tag；
- 用 `management.metrics.enable.*` 关闭不需要的指标。

`MeterFilter` 示例：

```java
@Bean
public MeterFilter denyRawUri() {
    return MeterFilter.deny(id ->
            id.getName().equals("http.server.requests")
                    && id.getTag("uri") != null
                    && id.getTag("uri").contains("?"));
}
```

---

## 五、Prometheus：从指标到告警

### 5.1 接入

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Spring Boot 3.3 的 Prometheus registry 使用：

```text
io.micrometer.prometheusmetrics.PrometheusMeterRegistry
io.prometheus.metrics.model.registry.PrometheusRegistry
org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint
```

配置：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    prometheus:
      enabled: true
  prometheus:
    metrics:
      export:
        enabled: true
```

访问：

```bash
curl http://localhost:8080/actuator/prometheus
```

支持的内容类型（`PrometheusOutputFormat`）：

```text
text/plain;version=0.0.4
application/openmetrics-text;version=1.0.0
application/x-protobuf
```

### 5.2 命名转换

Micrometer 使用点号命名，Prometheus 使用下划线：

```text
http.server.requests  →  http_server_requests_seconds
jvm.memory.used       →  jvm_memory_used_bytes
process.cpu.usage     →  process_cpu_usage
logback.events        →  logback_events_total
```

Timer 会生成：

```text
http_server_requests_seconds_count
http_server_requests_seconds_sum
http_server_requests_seconds_max
http_server_requests_seconds_bucket{le="..."}   # 开启 histogram 后
```

相关属性：

```yaml
management:
  prometheus:
    metrics:
      export:
        descriptions: true
        step: 1m
        histogram-flavor: prometheus   # prometheus | native
```

### 5.3 Prometheus 抓取配置

```yaml
scrape_configs:
  - job_name: order-service
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets:
          - order-service-1:8080
          - order-service-2:8080
```

如果使用独立管理端口：

```yaml
metrics_path: /manage/actuator/prometheus
targets:
  - order-service-1:8081
```

Prometheus 会自动加 `job`、`instance` 标签，不需要在应用里再重复加。

### 5.4 常用 PromQL

```promql
# QPS
sum(rate(http_server_requests_seconds_count[5m]))

# 5xx 错误率
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))

# 平均延迟
sum(rate(http_server_requests_seconds_sum[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))

# P99（需要 histogram）
histogram_quantile(
  0.99,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))
)

# JVM 堆使用率
jvm_memory_used_bytes{area="heap"}
/
jvm_memory_max_bytes{area="heap"}

# GC 平均停顿
rate(jvm_gc_pause_seconds_sum[5m])
/
rate(jvm_gc_pause_seconds_count[5m])

# 线程数
jvm_threads_live_threads
```

注意：`histogram_quantile` 要求 `_bucket` 时间序列存在；没有开启 `percentiles-histogram` 或 `slo` 时，Timer 通常只有 count/sum/max，没有 bucket。

### 5.5 告警设计

告警应该围绕**用户可感知的症状**，而不是内部原因：

```yaml
groups:
  - name: order-service
    rules:
      - alert: HighHttp5xxRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          /
          sum(rate(http_server_requests_seconds_count[5m])) > 0.05
        for: 5m
        labels:
          severity: page
        annotations:
          summary: "订单服务 5xx 比例超过 5%"
          runbook: "https://runbook.example.com/order-5xx"

      - alert: HighLatencyP99
        expr: |
          histogram_quantile(
            0.99,
            sum by (le) (rate(http_server_requests_seconds_bucket[5m]))
          ) > 1
        for: 10m
        labels:
          severity: page

      - alert: JvmHeapHigh
        expr: |
          jvm_memory_used_bytes{area="heap"}
          /
          jvm_memory_max_bytes{area="heap"} > 0.9
        for: 10m
        labels:
          severity: ticket
```

原则：

- 错误率、延迟、可用性告警 → page；
- 资源趋势（堆、线程、连接池）→ ticket 或 dashboard，不要全部 page；
- 告警必须带 runbook；
- 避免「CPU 高」这类原因告警，除非它已经导致用户可见症状；
- `for` 要足够长，避免抖动误报。

### 5.6 Pushgateway 的边界

Boot 支持：

```yaml
management:
  prometheus:
    metrics:
      export:
        pushgateway:
          enabled: true
          base-url: http://pushgateway:9091
          job: order-service
```

Pushgateway 只适合**短生命周期批处理任务**。长期运行的服务应该由 Prometheus 主动抓取；把常驻服务推到 Pushgateway 会带来「实例消失后指标仍然残留」的问题。

### 5.7 多实例与标签

- `job` 表示一组服务，`instance` 表示实例；
- 不要自己在应用里加 `instance` / `hostname` tag，Prometheus 已经提供；
- 应用公共标签建议只放 `application`、`env`、`region` 这类低基数维度；
- 多环境用不同 Prometheus job 或外部标签区分，不要把环境做成高基数的业务 tag。

---

## 六、Info：版本与构建信息

### 6.1 默认行为

`/actuator/info` 默认返回 `{}`，而且**默认不暴露**（Web exposure 只有 health）。需要：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 6.2 内置 InfoContributor

| 属性 | 默认 | 作用 |
|---|---|---|
| `management.info.build.enabled` | `true` | 读取 `META-INF/build-info.properties` |
| `management.info.git.mode` | `simple` | 读取 `git.properties`，`simple`/`full` |
| `management.info.env.enabled` | `false` | 暴露 `info.*` 属性 |
| `management.info.java.enabled` | `false` | JVM 信息 |
| `management.info.os.enabled` | `false` | 操作系统信息 |

生成 build info：

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals>
                <goal>build-info</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

生成 `git.properties` 可以用 `git-commit-id-maven-plugin`。`simple` 模式只暴露 `branch`、`commit.id`、`commit.time` 等少量字段；`full` 会暴露更多信息，按需选择。

### 6.3 自定义 InfoContributor

```java
@Component
public class AppInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("app", Map.of(
                "name", "order-service",
                "version", "1.4.2",
                "commit", "abc1234"));
    }
}
```

Info 的定位是「让运维知道现在跑的是哪个版本」，不要放：

- 数据库密码、Token、密钥；
- 内部拓扑、管理端口；
- 大段配置内容。

---

## 七、Diagnostics：排障端点

### 7.1 env 与 configprops

两者是排查「配置从哪来、绑成了什么」的核心端点：

| 端点 | 内容 | 对应知识 |
|---|---|---|
| `/actuator/env` | `Environment` 里的 PropertySource、key、值（默认脱敏） | 04-04 配置装载与优先级 |
| `/actuator/configprops` | 已绑定的 `@ConfigurationProperties` Bean | 04-04 配置绑定 |

```yaml
management:
  endpoint:
    env:
      show-values: never          # never | when-authorized | always
      roles: ACTUATOR
    configprops:
      show-values: never
      roles: ACTUATOR
```

`show-values=never` 时值显示为 `******`；`always` 会展示原始值，**生产环境不要开**。`when-authorized` 需要结合 Spring Security 和 `roles`。

### 7.2 beans 与 mappings

| 端点 | 作用 | 风险 |
|---|---|---|
| `/actuator/beans` | Bean 名称、类型、依赖、作用域、资源 | 暴露内部结构；响应可能很大 |
| `/actuator/mappings` | MVC 请求映射、过滤器映射 | 暴露路由；响应可能很大 |

排查「Bean 没注入」「请求 404」时很有用，但对公网开放等于把内部设计图交出去。

### 7.3 conditions

`/actuator/conditions` 展示自动配置的条件评估报告：

```json
{
  "positiveMatches": {
    "DataSourceAutoConfiguration": [...]
  },
  "negativeMatches": {
    "RedisAutoConfiguration": [...]
  }
}
```

它和启动时 `debug=true` 打印的是同一类数据，区别是：

- `debug=true`：启动日志，适合一次性排查；
- `/actuator/conditions`：运行时按需查看，适合已上线环境。

回顾 04-02：自动配置是否生效，看的就是这里。

### 7.4 loggers

```bash
# 查看所有 logger
curl http://localhost:8080/actuator/loggers

# 查看某个 logger
curl http://localhost:8080/actuator/loggers/com.example.order

# 动态调整级别
curl -X POST http://localhost:8080/actuator/loggers/com.example.order \
  -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"DEBUG"}'

# 恢复默认
curl -X POST http://localhost:8080/actuator/loggers/com.example.order \
  -H 'Content-Type: application/json' \
  -d '{"configuredLevel":null}'
```

注意：

- 修改只在当前进程生效，重启即失效；
- `configuredLevel` 与 `effectiveLevel` 是两回事；
- 支持 `logging.group.*` 定义的 logger 组；
- POST 操作需要安全配置和 CSRF 处理；
- 生产上建议禁用或仅对运维角色开放。

### 7.5 threaddump、heapdump、logfile

| 端点 | 输出 | 用途 | 生产建议 |
|---|---|---|---|
| `/actuator/threaddump` | JSON / 文本 | 死锁、线程阻塞、热点线程 | 仅内网/按需 |
| `/actuator/heapdump` | HPROF 二进制 | 堆分析（MAT/jhat） | **绝不公网** |
| `/actuator/logfile` | 日志文件 | 临时下载日志 | 需配置 `logging.file.name`；谨慎 |

`heapdump` 会触发堆转储，可能造成秒级停顿并占用大量磁盘；不要在自动化健康检查或高频脚本里调用。`threaddump` 相对轻量，但也会暴露线程栈和内部类名。

### 7.6 startup

`/actuator/startup` 展示应用启动步骤的时间线，需要：

```java
public static void main(String[] args) {
    SpringApplication app = new SpringApplication(Application.class);
    app.setApplicationStartup(new BufferingApplicationStartup(2048));
    app.run(args);
}
```

然后：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,startup
```

`StartupEndpointAutoConfiguration` 只有在 `ApplicationStartup` 是 `BufferingApplicationStartup` 时才创建端点。启动耗时也会以 gauge 形式进入 metrics：

```text
application.started.time
application.ready.time
```

两者都带 `main.application.class` 标签，便于区分同一进程里的多个应用上下文。

### 7.7 caches、scheduledtasks 等

| 端点 | 条件 |
|---|---|
| `/actuator/caches` | 存在 CacheManager |
| `/actuator/scheduledtasks` | 存在 `@Scheduled` 任务 |
| `/actuator/flyway`、`/actuator/liquibase` | 对应迁移工具在 classpath |
| `/actuator/sessions` | Spring Session |
| `/actuator/quartz` | Quartz |
| `/actuator/integrationgraph` | Spring Integration |

这些端点默认「存在依赖才出现」，不是所有应用都有。

---

## 八、安全：哪些端点不能公开

### 8.1 默认安全行为

如果没有 Spring Security：

- 暴露的端点**没有任何认证**；
- 默认只暴露 health，所以风险面较小；
- 一旦把 exposure 改成 `*`，所有端点都可匿名访问。

如果引入了 Spring Security：

- `ManagementWebSecurityAutoConfiguration` 会提供默认管理安全链；
- 它只对 `HealthEndpoint` 放行（`EndpointRequest.to(HealthEndpoint.class).permitAll()`），其余请求要求认证；
- 它只在「用户没有自定义默认 Web 安全」时生效（`@ConditionalOnDefaultWebSecurity`）；
- 它默认开启 formLogin / httpBasic。

这只是「默认安全」，不是「生产安全」。健康检查可以匿名，其他端点通常需要角色控制或网络隔离。

### 8.2 三层收敛

```text
第一层：exposure
  只暴露必要端点
  include: health,info,prometheus

第二层：网络
  独立管理端口 + 绑定内网/回环 + 防火墙 / NetworkPolicy
  不让公网 Ingress 转发 /actuator

第三层：认证授权
  Spring Security + EndpointRequest + roles
  show-details / show-values 使用 when-authorized
```

最小暴露示例：

```yaml
management:
  server:
    port: 8081
    address: 127.0.0.1
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
        exclude: env,configprops,beans,mappings,loggers,threaddump,heapdump,shutdown
  endpoint:
    health:
      show-details: when-authorized
      roles: ACTUATOR
    env:
      show-values: never
    configprops:
      show-values: never
```

### 8.3 EndpointRequest 用法

```java
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Bean
public SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
    http.securityMatcher(EndpointRequest.toAnyEndpoint())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
            .requestMatchers(EndpointRequest.to("info", "prometheus")).permitAll()
            .anyRequest().hasRole("ACTUATOR"))
        .httpBasic(Customizer.withDefaults());
    return http.build();
}
```

`EndpointRequest` 常用方法：

```java
EndpointRequest.toAnyEndpoint();
EndpointRequest.to(HealthEndpoint.class);
EndpointRequest.to("info", "prometheus");
EndpointRequest.toLinks();          // /actuator 链接页
```

注意：

- 如果自定义了 `SecurityFilterChain`，Boot 的 `ManagementWebSecurityAutoConfiguration` 会退让；
- 独立管理端口时，确认安全链匹配的是管理端口请求；
- POST `/loggers`、`/shutdown` 需要 CSRF 处理，按实际安全模型决定是带 token 还是对管理端点关闭 CSRF。

### 8.4 高风险端点清单

| 端点 | 风险 | 建议 |
|---|---|---|
| `heapdump` | 堆转储包含内存中的敏感数据 | 禁用；仅本地排障时临时开启 |
| `threaddump` | 线程栈、内部类名 | 仅内网 |
| `env` | 配置、主机、路径、密钥（即使脱敏） | 禁用或严格角色 |
| `configprops` | 绑定后的配置对象 | 禁用或严格角色 |
| `loggers` | 动态调高日志级别可能泄露敏感信息 | 仅运维角色 |
| `beans` / `mappings` | 内部结构 | 仅排障时开启 |
| `shutdown` | 可远程停机 | 默认禁用，永不公网 |
| `prometheus` | 指标通常可接受，但可能暴露内部命名 | 内网抓取，限流 |
| `info` | 版本/commit，低风险但与攻击面相关 | 可公开但不要放敏感信息 |

### 8.5 脱敏

`show-values=never` 是默认值，会隐藏所有值；`when-authorized` 结合角色展示；`always` 不建议生产使用。

细粒度脱敏可以实现 `SanitizingFunction`：

```java
@Bean
public SanitizingFunction maskPaymentSecret() {
    return data -> data.getKey().contains("payment.secret")
            ? data.withSanitizedValue()
            : data;
}
```

原则：

- 不要依赖「key 名字看起来不像密码」；
- 敏感配置不要放进 `info.*`；
- `env` 和 `configprops` 默认就不该对公网开放；
- 日志、异常消息、Health 详情同样可能泄露敏感信息（05-02 会展开日志脱敏）。

---

## 九、监控体系怎么设计

### 9.1 四个黄金信号

| 信号 | 指标 | 说明 |
|---|---|---|
| 延迟 Latency | `http.server.requests` 的 P50/P95/P99；下游 timer | 区分成功请求和失败请求的延迟 |
| 流量 Traffic | `http.server.requests` count；`executor.active` | QPS、并发、队列 |
| 错误 Errors | 5xx 比例、`exception` tag、日志 error rate | 用户看到的失败 |
| 饱和度 Saturation | CPU、堆、GC、线程池、连接池、磁盘 | 资源接近上限的程度 |

RED 方法（面向请求）：

```text
Rate      请求速率
Errors    错误数/错误率
Duration  延迟分布
```

USE 方法（面向资源）：

```text
Utilization  使用率
Saturation   排队/等待
Errors       错误
```

### 9.2 health、metrics、logs、traces 的分工

| 信号 | 回答的问题 | 保留时间 | 成本 |
|---|---|---|---|
| health | 现在能不能接流量？ | 秒级 | 低 |
| metrics | 趋势、告警、容量 | 天/月 | 中 |
| logs | 单次事件的细节 | 天/周 | 高 |
| traces | 一次请求跨服务路径 | 小时/天 | 高 |

不要把日志量做成 metrics tag，也不要用 health 做长期趋势；它们解决的问题不同。

### 9.3 SLO 与告警

一个可用的 SLO 示例：

```text
99% 的请求在 300ms 内完成
5xx 错误率 < 0.5%
按月统计错误预算
```

告警应该基于 SLO 的 burn rate（错误预算消耗速度），而不是单点瞬时值。至少配置：

- 错误率告警；
- P99 延迟告警；
- 实例存活/探针失败告警；
- 资源饱和趋势告警（堆、连接池、线程池）。

告警必须回答三个问题：**谁受影响、影响多大、该看哪个 runbook**。

### 9.4 JVM 与容器

容器里的 JVM 常见问题：

- 堆上限按宿主内存算，而不是容器 limit → OOMKilled；
- GC 停顿导致 readiness/liveness 超时；
- 线程数暴涨；
- CPU throttling 导致延迟抖动。

建议：

```bash
-XX:MaxRAMPercentage=75
-XX:+UseG1GC
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp
```

监控重点：

```text
jvm.memory.used / jvm.memory.max
jvm.gc.pause
jvm.threads.live
process.cpu.usage
system.cpu.usage
```

注意 `system.cpu.usage` 在不同环境下可能是宿主视角，容器场景要结合 cgroup 限制和 `container_cpu_cfs_throttled_periods_total`（如果采集）一起看。

### 9.5 成本与基数

- scrape interval：15s 是常见起点；过于频繁会增加存储和抓取压力；
- retention：按 SLO/故障复盘需要设置，不要无限保留；
- histogram 桶数量：越多越精确，也越占空间；
- 高基数 tag：最贵的成本来源；
- 指标命名：稳定、可读、可聚合；
- 只采集有用的指标，默认指标里也可以关掉不需要的部分。

### 9.6 仪表盘分层

```text
第一层：服务总览
  QPS、错误率、P99、实例数、健康状态

第二层：接口/依赖
  按 uri/status 拆分；下游调用延迟和错误

第三层：JVM/资源
  堆、GC、线程、CPU、连接池、队列

第四层：排障
  日志、trace、threaddump、heapdump
```

每一层都应该有明确的「看到异常后下一步做什么」，否则仪表盘只是装饰。

### 9.7 生产清单

- [ ] 引入 `spring-boot-starter-actuator`；
- [ ] 需要 Prometheus 时引入 `micrometer-registry-prometheus`；
- [ ] Web 只暴露 `health,info,prometheus`；
- [ ] 管理端口独立并绑定内网/回环；
- [ ] `health.show-details=never` 或 `when-authorized`；
- [ ] liveness 不检查下游，readiness 检查关键依赖；
- [ ] 配置 K8s liveness/readiness/startupProbe；
- [ ] 配置公共 tags（application/env）；
- [ ] HTTP 延迟开启 `percentiles-histogram`；
- [ ] 配置错误率/P99/资源告警和 runbook；
- [ ] 禁用 `heapdump`、`threaddump`、`env`、`configprops`、`loggers`、`shutdown` 的公网访问；
- [ ] 开启启动耗时指标或 startup 端点；
- [ ] 验证安全链：健康检查匿名、其他端点鉴权；
- [ ] 验证 Prometheus 抓取和管理端口防火墙。

---

## 十、常见问题排查

### 10.1 `/actuator/xxx` 404

按顺序查：

1. 端点是否启用？`management.endpoint.<id>.enabled`；
2. 是否暴露？`management.endpoints.web.exposure.include`；
3. base-path 是否被改？`management.endpoints.web.base-path`；
4. 是否用了独立管理端口？端口/`management.server.base-path` 是否对；
5. 是否有 `path-mapping` 改了路径；
6. 是否是默认禁用的端点（如 `shutdown`）。

### 10.2 health DOWN

1. 临时打开 `show-details=always`（仅本地）看组件；
2. 看 `diskSpace` 是否低于阈值；
3. 看 DB/Redis 等依赖指标；
4. 看自定义 `HealthIndicator` 是否吞异常/超时；
5. 检查 `StatusAggregator` 和 HTTP 映射是否符合预期；
6. 检查 readiness group 是否包含了你以为不需要的依赖。

### 10.3 metrics 端点没有指标

1. 是否有 `MeterRegistry`？没有 exporter 时 Boot 会提供 `SimpleMeterRegistry`；
2. 指标是否真的被记录？Counter 需要 `increment()`，Timer 需要至少一次请求；
3. `management.metrics.enable.*` 是否关掉了；
4. `management.observations.enable.*` 是否关掉了；
5. `@Timed` 是否因为 `management.observations.annotations.enabled=false` 没有 aspect；
6. 方法是否自调用，绕过了 AOP 代理；
7. 指标名是否拼错，先用 `/actuator/metrics` 列表确认。

### 10.4 `/actuator/prometheus` 404 或 406

- 404：没有 `micrometer-registry-prometheus`；没有暴露 prometheus；端点被禁用；
- 406：`Accept` 头不支持，尝试 `text/plain` 或 `application/openmetrics-text`；
- 空内容：registry 没有指标，或 scrape 路径/端口不对。

### 10.5 指标基数爆炸

- 检查自定义 tag 是否包含用户 ID、订单 ID、原始 URL；
- `http.server.requests` 的 `uri` 是否出现大量具体 ID（说明路由模板没生效）；
- 用 Prometheus 的 `count by (__name__)` / `topk` 找高基数指标；
- 用 `MeterFilter` 拒绝/裁剪，或在源头改 tag 设计。

### 10.6 401/403

- 自定义 `SecurityFilterChain` 是否拦住了 actuator；
- 是否允许 health 匿名；
- `show-details=when-authorized` 时当前用户是否有 `roles`；
- POST `/loggers` 是否被 CSRF 拦截；
- 独立管理端口的 security matcher 是否覆盖了管理请求。

### 10.7 探针一直失败

- 路径是否包含 `/actuator`；
- 端口是业务端口还是管理端口；
- `management.endpoint.health.probes.enabled` 是否开启（非 K8s）；
- group 名称是否写对；
- liveness 是否错误地包含了 DB/Redis；
- 启动慢是否没有配 startupProbe；
- `add-additional-paths` 是否需要在主端口访问。

### 10.8 heapdump 卡住/文件过大

- heapdump 会暂停 JVM，生产上不要频繁调用；
- 确认磁盘空间；
- 使用超时和本地临时目录；
- 优先用 `jcmd`/`jmap` 在受控环境采集；
- 采集后及时删除，避免泄露。

### 10.9 动态日志级别不生效

- logger 名称是否匹配（包名 vs 类名）；
- 是否被 `logging.group` 覆盖；
- 是否重启后恢复（这是预期行为，不持久化）；
- 是否有安全/CSRF 拦截。

### 10.10 管理端口与业务端口混淆

- Prometheus 抓取路径是否用了管理端口；
- K8s 探针端口是否写错；
- 防火墙是否只放行了业务端口；
- Ingress 是否错误地把 `/actuator` 暴露到公网。

---

## 十一、源码断点建议

| 想观察什么 | 断点位置 |
|---|---|
| 端点发现 | `WebEndpointAutoConfiguration#webEndpointDiscoverer` |
| 端点扫描 | `EndpointDiscoverer#discoverEndpoints` |
| 暴露过滤 | `IncludeExcludeEndpointFilter#match` |
| 路径映射 | `PathMappedEndpoints` |
| Web 适配 | `WebMvcEndpointHandlerMapping`、`AbstractWebMvcEndpointHandlerMapping` |
| health 主流程 | `HealthEndpoint#health`、`HealthEndpointSupport#getHealth` |
| health Web 详情 | `HealthEndpointWebExtension#health` |
| health 分组 | `AutoConfiguredHealthEndpointGroups`、`HealthEndpointGroups` |
| probes 条件 | `AvailabilityProbesAutoConfiguration$ProbesCondition` |
| readiness 状态变化 | `ApplicationAvailabilityBean#onApplicationEvent` |
| 指标注册 | `MetricsAutoConfiguration#meterRegistryPostProcessor` |
| 指标绑定 | `MeterRegistryPostProcessor#postProcessAfterInitialization` |
| HTTP 观测 | `WebMvcObservationAutoConfiguration#webMvcObservationFilter` |
| HTTP 标签 | `DefaultServerRequestObservationConvention` |
| Prometheus 输出 | `PrometheusScrapeEndpoint#scrape` |
| 动态日志 | `LoggersEndpoint#configureLogLevel` |
| env 端点 | `EnvironmentEndpoint#environment` |
| configprops 端点 | `ConfigurationPropertiesReportEndpoint#configurationProperties` |
| startup 端点 | `StartupEndpoint#startup` |
| 管理安全 | `ManagementWebSecurityAutoConfiguration#managementSecurityFilterChain` |

---

## 十二、必做实验

### 实验一：默认暴露面

1. 引入 `spring-boot-starter-actuator`；
2. 启动应用；
3. 访问 `/actuator`、`/actuator/health`、`/actuator/metrics`。

预期：只有 health 可访问；其他端点 404，直到你显式 exposure。

### 实验二：health 详情与组件

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: always
```

观察 `/actuator/health` 从 `{"status":"UP"}` 变成带 `components` 和 details 的结构；再改回 `never`。

### 实验三：自定义 HealthIndicator 与 group

写一个 `PaymentGatewayHealthIndicator`，配置：

```yaml
management:
  endpoint:
    health:
      group:
        payment:
          include: paymentGateway
          show-details: always
```

访问 `/actuator/health/payment`，验证组件名和 group 名称一致。

### 实验四：probes 与优雅停机

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 10s
```

观察 `/actuator/health/liveness`、`/actuator/health/readiness`；在停机时观察 readiness 先变为 `OUT_OF_SERVICE`。

### 实验五：Prometheus 接入

1. 加 `micrometer-registry-prometheus`；
2. 暴露 `prometheus`；
3. 访问 `/actuator/prometheus`；
4. 查找 `http_server_requests_seconds_count`、`jvm_memory_used_bytes`。

### 实验六：自定义指标

写一个 Counter、Timer、Gauge，分别访问 `/actuator/metrics/{name}`，观察 measurements 和 availableTags。

### 实验七：@Observed vs @Timed

1. 给一个 Service 方法加 `@Observed`，触发调用，查指标；
2. 给另一个方法加 `@Timed`，不加 `management.observations.annotations.enabled`，观察没有指标；
3. 打开该属性，再观察指标出现；
4. 在类内部自调用，观察 AOP 不生效。

### 实验八：直方图与 PromQL

开启：

```yaml
management.metrics.distribution.percentiles-histogram.http.server.requests: true
```

抓取 `/actuator/prometheus`，确认出现 `_bucket`；在 Prometheus 里执行 `histogram_quantile`，观察 P99。

### 实验九：独立管理端口与安全

```yaml
management:
  server:
    port: 8081
    address: 127.0.0.1
```

验证业务端口访问 `/actuator/health` 404、管理端口可访问；再加 Spring Security，用 `EndpointRequest` 控制 health 匿名、其他端点需要角色，验证 401/403。

### 实验十：诊断端点

访问 `/actuator/conditions`、`/actuator/beans`、`/actuator/mappings`、`/actuator/env`、`/actuator/configprops`；对照 04-02/04-04 的知识，找到你关心的自动配置和配置绑定结果。

### 实验十一：动态日志

用 `loggers` 端点把某个包调到 DEBUG，观察日志输出；重启应用，确认恢复为 `logging.level.*` 的配置。

### 实验十二：startup 端点

```java
SpringApplication app = new SpringApplication(Application.class);
app.setApplicationStartup(new BufferingApplicationStartup(2048));
app.run(args);
```

暴露 `startup`，访问 `/actuator/startup`，观察启动步骤时间线；再查看 `application.started.time`、`application.ready.time` 指标。

---

## 十三、面试常问

### 13.1 Actuator 端点是怎么暴露的？

端点由 `@Endpoint` 定义，`EndpointDiscoverer` 扫描 Bean；`IncludeExcludeEndpointFilter` 按 `management.endpoints.web.exposure.include/exclude` 过滤；最后由 WebMvc/WebFlux/JMX adapter 映射成 HTTP/JMX 接口。启用、暴露、认证是三件事。

### 13.2 默认暴露哪些端点？

Web 默认只暴露 `health`；JMX 默认也是 `health`；Cloud Foundry 默认 `*`。其他端点需要显式 exposure。

### 13.3 为什么 health 默认不显示详情？

防止泄露数据库地址、异常堆栈、内部主机名。通过 `show-details`、`show-components`、`roles` 控制；生产建议 `never` 或 `when-authorized`。

### 13.4 liveness、readiness、startup 有什么区别？

liveness 判断进程是否该重启；readiness 判断是否该接流量；startup 给慢启动应用留出启动时间。liveness 不应检查下游依赖，否则会导致级联重启。

### 13.5 HealthIndicator 和 HealthContributor 的区别？

`HealthIndicator` 返回单个 `Health`；`HealthContributor` 可以是单个指标，也可以是组合（`CompositeHealthContributor`）。Group 从 contributor 集合里选子集。

### 13.6 Micrometer 是什么？

指标门面（facade）：应用只依赖 `MeterRegistry`/`Meter`，由具体 registry 负责导出到 Prometheus、OTLP、StatsD 等。Boot 自动装配默认 JVM/系统/HTTP/Tomcat/DataSource 等 `MeterBinder`。

### 13.7 `/actuator/metrics` 和 `/actuator/prometheus` 的区别？

前者是 JSON 调试端点，用于人查看指标和 tag；后者是 Prometheus 文本格式，用于机器抓取。后者需要 `micrometer-registry-prometheus`。

### 13.8 `http.server.requests` 有哪些 tag？

`method`、`uri`（路径模板）、`status`、`outcome`、`exception`。`uri` 用模板而不是原始 URL 是为了控制基数。

### 13.9 `@Observed` 和 `@Timed` 有什么区别？

`@Observed` 基于 Observation API，适合统一观测；`@Timed`/`@Counted` 是 Micrometer 旧注解。Boot 3.3 的 `TimedAspect`/`CountedAspect` 需要 `management.observations.annotations.enabled=true` 才注册。两者都受 AOP 自调用限制。

### 13.10 生产环境应该暴露哪些 Actuator 端点？

通常只暴露 `health`、`info`、`prometheus`；`env`、`configprops`、`beans`、`mappings`、`loggers`、`threaddump`、`heapdump`、`shutdown` 仅内网/角色访问，绝不公网。

### 13.11 怎么排查 Actuator 404？

启用 → 暴露 → base-path/端口/path-mapping → 安全。四步按顺序查。

### 13.12 怎么做 HTTP 延迟告警？

开启 `percentiles-histogram` 生成 `_bucket`，用 `histogram_quantile` 查 P99；结合 SLO 和 burn rate 告警，而不是瞬时值。

---

## 十四、本篇过关标准

学完本文后，需要能做到：

- 画出 Endpoint → 暴露过滤 → Web/JMX adapter 的链路。
- 解释启用、暴露、认证三者的区别，并排查 404。
- 设计 liveness/readiness/startup，说明为什么 liveness 不查下游。
- 配置 health group、probes、show-details、HTTP 状态映射。
- 说出 Micrometer 的 registry/meter/tag 模型，列出常用默认指标。
- 解释 `http.server.requests` 的标签和低基数设计。
- 接入 Prometheus，写出 QPS、错误率、P99 的 PromQL。
- 解释 percentiles、histogram、SLO 的区别。
- 用 `@Observed` 或 `MeterRegistry` 写自定义指标，并知道 `@Timed` 的启用条件。
- 配置独立管理端口、exposure 白名单、Spring Security 角色。
- 说清生产环境哪些端点不能公开，以及如何脱敏。
- 用 env/configprops/conditions/loggers/threaddump/startup 排查问题。

---

## 十五、下一篇建议

下一篇继续第 6 阶段的工程化主题：

```text
05-02-SpringBoot-日志与配置治理.md
```

重点包括：

- Logback / Log4j2 的 Boot 配置模型；
- 日志级别、logger group、动态调整的边界；
- 结构化日志与 traceId / spanId；
- 日志脱敏与敏感信息治理；
- 多环境配置、外部化配置和配置中心的边界。
