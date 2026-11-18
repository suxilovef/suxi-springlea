# Spring Cloud 专题：注册发现与客户端负载均衡源码深度解析

> 本文是第 7 阶段 Spring Cloud 微服务基础的第一篇，承接 `05-04-SpringBoot-性能优化.md`。
>
> 05-04 讨论的是「一个进程内部，请求为什么慢、瓶颈在哪、容量怎么算」。进入第 7 阶段，问题从单进程变成多进程：
>
> 1. 服务实例的 IP、端口、数量都在变化，调用方凭什么知道该发给谁？
> 2. 注册中心到底存了什么？为什么注册中心挂了，已有调用不一定挂？
> 3. 实例宕机、正常下线、网络分区时，注册中心应该「立刻删」还是「先留着」？
> 4. Eureka、Nacos 的默认值分别是什么？这些默认值会制造哪些「明明注册了却调不到」？
> 5. Spring Cloud 的 `DiscoveryClient`、`ServiceRegistry`、`AutoServiceRegistration`、`LoadBalancer` 是怎么串成一条链的？
>
> 基准：Spring Boot `3.3.9` + Spring Cloud `2023.0.6` + Spring Cloud Alibaba `2023.0.3.4`（Nacos Client `2.4.3`）。Eureka 部分基于 Spring Cloud Netflix `4.1.6`（Netflix eureka `2.0.5`），Nacos 部分基于 Spring Cloud Alibaba `2023.0.3.x`。
>
> 本文的默认值不是抄文档，而是解压 Maven Central 上的 `spring-cloud-commons 4.1.6`、`spring-cloud-loadbalancer 4.1.6`、`spring-cloud-netflix-eureka-client/server 4.1.6`、`spring-cloud-starter-alibaba-nacos-discovery 2023.0.3.x` 的 sources jar 与 `spring-configuration-metadata.json` 对照得到。核对中发现两处元数据与源码行为不一致，正文会单独标注——这也是「配置元数据不能全信」的现场证据。

---

## 一、先给结论

### 1.1 注册发现解决的是「名字到实例列表」的动态映射

单体应用里，调用一个依赖通常是：

```text
orderService.createOrder(...)   // 进程内方法调用
```

拆成微服务后，变成：

```text
HTTP → http://order-service/orders/1
```

`order-service` 不是一个 IP，而是一组随时会变化的实例：

```text
order-service
  ├─ 10.0.1.11:8081
  ├─ 10.0.1.12:8081
  └─ 10.0.1.13:8081
```

注册中心的第一职责，就是维护这个映射：

```text
serviceId → [ServiceInstance...]
```

并且让这个映射在实例上下线时尽快收敛。

### 1.2 注册中心是控制面，调用路径是数据面

这是理解很多「注册中心挂了怎么办」问题的关键：

```text
控制面（control plane）
  注册、心跳、下线、查询、订阅
  → 注册中心 / 客户端本地缓存

数据面（data plane）
  选择实例、拼 URL、发 HTTP、超时、重试
  → 调用方进程内部完成
```

Spring Cloud 采用**客户端发现 + 客户端负载均衡**：

- 注册中心不转发请求；
- 调用方从注册中心（或本地缓存）拿到实例列表；
- 调用方自己选一个实例发请求；
- 因此注册中心短暂不可用，已有实例列表仍可支撑调用；但新的上下线信息无法及时传播。

### 1.3 注册发现的五个核心动作

| 动作 | 谁做 | 作用 | 失败后果 |
|---|---|---|---|
| `register` | 服务实例启动后 | 把 `ip:port`、元数据、权重写入注册中心 | 实例不可被发现 |
| `renew` / heartbeat | 服务实例周期性 | 证明「我还活着」 | 被标记不健康或摘除 |
| `deregister` | 服务实例正常关闭前 | 主动摘除自己 | 流量继续打到正在关闭的实例 |
| `fetch` / subscribe | 调用方 | 获取服务实例列表及变化 | 调用方看到旧列表或空列表 |
| `health / evict` | 注册中心服务端 | 根据心跳、健康状态维护列表 | 死实例残留，或误删活实例 |

不同注册中心的差异，本质上是在这五个动作上做不同的**一致性、可用性、时效性**取舍。

### 1.4 四个时间窗口

「注册成功」不等于「立刻可调用」。从实例启动、停止到调用方真正感知，中间至少经过四层：

```text
服务实例
  → 注册中心服务端内存/缓存
    → 调用方本地注册表缓存
      → Spring Cloud LoadBalancer 的实例列表缓存
        → HTTP 连接池/Keep-Alive
```

Eureka 默认值下，一个实例从启动到被调用方看见，常见要几十秒；硬宕机后从调用方列表消失，可能是几分钟量级：

| 环节 | Eureka 默认 | Nacos 默认 |
|---|---:|---:|
| 注册/心跳间隔 | 30s（`lease-renewal-interval-in-seconds`） | 5s（`DEFAULT_HEART_BEAT_INTERVAL`） |
| 判定失联 | 90s（`lease-expiration-duration-in-seconds`） | 15s（`DEFAULT_HEART_BEAT_TIMEOUT`） |
| 服务端清理/删除 | 60s（`eviction-interval-timer-in-ms`，源码默认） | 30s（`DEFAULT_IP_DELETE_TIMEOUT`） |
| 服务端响应缓存 | 30s（`response-cache-update-interval-ms`） | 推送，通常更快 |
| 客户端拉取 | 30s（`registry-fetch-interval-seconds`） | 订阅 + 本地缓存 |
| Spring Cloud LB 缓存 | 35s（`spring.cloud.loadbalancer.cache.ttl`） | 35s（默认 LB；Nacos 专用 LB 模式关闭该缓存） |

这些数字不是「调优建议」，而是解释现象的量级：

```text
刚启动的实例，调用方可能几十秒内看不到；
kill -9 的实例，调用方可能几分钟内还在打；
正常下线的实例，也可能因为客户端缓存继续被选中。
```

### 1.5 十个常见误解

| 误解 | 事实 |
|---|---|
| 注册成功 = 立刻可调用 | 调用方有本地缓存和负载均衡缓存，存在收敛窗口 |
| 注册中心挂了，微服务全挂 | 已有实例列表在客户端缓存里，调用可以继续；新上下线不可见 |
| Eureka 自保护是 bug | 它是网络分区下「宁留勿删」的 AP 取舍，代价是死实例残留 |
| 下线 = 立即从所有调用方消失 | 主动下线、服务端缓存、客户端拉取、LB 缓存是四件事 |
| Actuator `health=UP` = 注册中心健康 | 注册中心健康通常只看心跳/长连接，不看数据库、下游依赖 |
| IP 注册对了就一定能调通 | 注册的是「广告地址」，容器 NAT、跨网段、防火墙都可能让广告地址不可达 |
| 实例越多，负载越均衡 | 轮询只保证「次数」均衡，不保证「耗时」均衡；连接复用、权重、区域都会影响结果 |
| Nacos 和 Eureka 只是换皮 | 一致性模型、推送/拉取、命名空间、权重、配置中心能力都不同 |
| 配置中心和注册中心是一回事 | 一个管「配置数据」，一个管「实例目录」；Nacos 恰好都做 |
| 服务名可以随便起 | `serviceId` 是调用契约；大小写、group、namespace 任一不匹配都会变成空列表 |

### 1.6 版本矩阵（本文核对基线）

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | `3.3.9` | 本仓库基线 |
| Spring Cloud | `2023.0.6` | 2023.0.x 最新版；构建基线 Boot `3.3.13`；2023.0.x 支持 Boot 3.2.x/3.3.x |
| Spring Cloud Commons | `4.1.6` | `DiscoveryClient`、`ServiceRegistry`、自动注册抽象 |
| Spring Cloud LoadBalancer | `4.1.6` | 客户端负载均衡 |
| Spring Cloud Netflix | `4.1.6` | Eureka Client/Server；Netflix eureka `2.0.5` |
| Spring Cloud Alibaba | `2023.0.3.4` | 2023.0.x 最新版；Nacos Client `2.4.3` |
| Nacos Server | `2.4.x` | 与客户端 2.4.3 搭配 |
| JDK | `17` | 与仓库一致 |

需要说明两点：

- Spring Cloud `2023.0.x` 已进入 OSS EOL，本文锁定该版本是为了匹配仓库基线；新项目应评估升级到更新的 release train。
- Spring Cloud Alibaba `2023.0.3.4` 的 POM 编译基线是 Boot `3.2.9`、Spring Cloud Commons `4.1.4`，与 Spring Cloud `2023.0.x` 同一代，运行在 Boot 3.3.x 上常见且可用；但严格的生产项目应以官方兼容表为准，不要把「能启动」当成「官方认证」。

---

## 二、第一性原理：为什么需要注册中心

### 2.1 从静态配置到动态实例

最原始的做法是写死：

```yaml
order-service:
  url: http://10.0.1.11:8081
```

问题会随着规模和发布方式迅速出现：

```text
扩容 → 要改配置
缩容 → 要改配置
机器漂移/容器重建 → IP 变了，要改配置
滚动发布 → 新旧实例并存，调用方要知道两组实例
故障实例 → 调用方要能快速剔除
灰度/权重/机房路由 → 配置里表达不了
```

于是需要一层「实例目录」，把**服务名**和**实例集合**解耦。

### 2.2 为什么不用 DNS / Nginx 就够了

DNS 和 Nginx 都能解决一部分问题，但都不是注册中心的完整替代：

| 能力 | DNS | Nginx（服务端 LB） | 注册中心 |
|---|---|---|---|
| 名字到地址 | 有 | 有 | 有 |
| 健康检查 | 弱，依赖 TTL/额外探活 | 有，但需额外配置 | 内建心跳/长连接模型 |
| 实例变化传播 | TTL 决定，通常较慢 | 需动态配置/服务发现插件 | 推送或短周期拉取 |
| 元数据/权重/版本 | 弱（SRV 可表达一部分） | 可配置 | 原生支持 |
| 客户端侧选择 | 无 | 无（服务端选） | 有 |
| 与 Spring 生态集成 | 间接 | 外部组件 | 原生 |

更重要的是**数据面位置**：

```text
DNS/Nginx：请求必须经过一个中间层
注册中心：请求不经过注册中心，注册中心只提供目录
```

这就是为什么注册中心可以短暂不可用，而调用仍然继续。

### 2.3 客户端发现 vs 服务端发现

| 模型 | 选择实例的人 | 典型实现 | 优点 | 代价 |
|---|---|---|---|---|
| 客户端发现 | 调用方进程 | Spring Cloud LoadBalancer + Eureka/Nacos | 少一跳、客户端可定制算法 | 每个语言/客户端都要实现发现与 LB |
| 服务端发现 | 中间代理 | Nginx、HAProxy、Kubernetes Service、云 LB | 调用方简单 | 多一跳；代理成为容量与故障点 |

Spring Cloud 选择客户端发现，所以 `spring-cloud-starter-loadbalancer` 和注册中心客户端通常一起出现。OpenFeign（06-03）也是在这个模型上做声明式封装。

### 2.4 注册中心的数据模型

以 Nacos 的模型最完整，Eureka 是它的子集：

```text
namespace（环境隔离）
  └─ group（业务/租户隔离）
       └─ service（服务名）
            └─ cluster（机房/可用区）
                 └─ instance（ip、port、weight、metadata、ephemeral、healthy）
```

Eureka 没有原生的 namespace/group/cluster 四层，通常用：

```text
appName        ≈ service
metadata       ≈ 扩展信息
availabilityZone / region ≈ 区域
instanceId     ≈ 实例唯一标识
```

这也是迁移时最容易踩坑的地方：Eureka 的「服务名」直接对应 Nacos 的 `service + group + namespace` 组合，少一层隔离就会出现「明明注册了，但查不到」。

### 2.5 五个动作的失败语义

#### register

- 启动时执行，应该幂等；
- 失败后是否让应用启动失败，是一个策略问题：
  - Eureka：客户端会重试，通常不会因为一次注册失败立刻杀死应用（具体取决于客户端初始化和重试状态）；
  - Nacos：`spring.cloud.nacos.discovery.fail-fast=true` 默认，注册异常会抛出，可能导致启动失败；
  - Spring Cloud Commons 还有一个 `spring.cloud.service-registry.auto-registration.fail-fast=false`，含义是「没有自动注册 Bean 时是否启动失败」，和 Nacos 的 `fail-fast` 不是同一个层次。

#### renew / heartbeat

- 心跳只证明「进程还在、连接还在」，不证明「业务健康」；
- 心跳间隔、超时、清理间隔共同决定故障发现时间；
- 网络分区时，注册中心无法区分「实例死了」和「实例暂时联系不上」，这就是自保护/健康阈值存在的原因。

#### deregister

- 正常关闭时主动摘除；
- 进程被 `kill -9`、容器被强制删除、机器断电时不会执行；
- 即使执行了，调用方仍可能因为缓存继续打一段时间。

#### fetch / subscribe

- Eureka：客户端每 30s 拉一次全量/增量，服务端还有 30s 响应缓存；
- Nacos 2.x：客户端订阅，服务端通过 gRPC 长连接推送变更，客户端维护本地缓存；
- 两者都不是「强一致实时视图」。

#### health / evict

- Eureka：租约到期 + 定时扫描 + 自保护；
- Nacos：临时实例心跳/长连接，持久实例服务端主动探测；
- 健康检查的粒度决定了「误杀」和「漏杀」的平衡。

### 2.6 CAP 与注册中心：为什么发现场景通常选 AP

注册中心不是银行账本。对它来说：

```text
返回一个稍旧的实例列表  → 调用方可能打到已死实例，但可以超时/重试/熔断
返回一个空列表          → 调用方直接失败，故障面更大
```

所以在网络分区时，服务发现通常优先**可用性和非空视图**，接受短时间的不一致。常见定位：

| 系统 | 一致性模型 | 说明 |
|---|---|---|
| Eureka | AP | 节点间异步复制；自保护模式在续约率骤降时停止剔除 |
| Nacos（临时实例） | AP | Distro 协议，最终一致；适合随进程生死的实例 |
| Nacos（持久实例） | CP | Raft，适合需要人工维护/固定实例的场景 |
| Consul | 偏 CP | Catalog 写入走 Raft；读取一致性可调 |
| ZooKeeper | CP | 选举期间可能不可写；常用于需要强一致的协调场景 |

注意：这不是「AP 一定优于 CP」。如果注册数据是人工维护的固定后端、要求写入强一致，CP 更合适；如果实例随容器频繁生灭，AP 更合适。注册中心选型要看**数据生命周期**，不是看名词。

### 2.7 Eureka 自保护：一个被误解的可用性机制

Eureka 服务端会统计每分钟收到的续约数：

```text
numberOfRenewsPerMinThreshold
  = expectedNumberOfClientsSendingRenews
    × (60 / expectedClientRenewalIntervalSeconds)
    × renewalPercentThreshold
```

默认值：

```text
expectedClientRenewalIntervalSeconds = 30
renewalPercentThreshold             = 0.85
```

也就是说，每个实例预期每分钟续约 2 次，阈值是预期续约数的 85%。

当 `renewsLastMin <= threshold` 时，`PeerAwareInstanceRegistryImpl.isLeaseExpirationEnabled()` 返回 `false`，**剔除任务被跳过**：

```text
正常情况：续约数 > 阈值 → 允许剔除过期实例
异常情况：续约数 <= 阈值 → 认为更可能是网络问题 → 保留实例
```

设计动机：如果机房与注册中心之间网络抖动，大量实例同时「失联」，立刻剔除会把整个服务目录清空。自保护选择「先保留，等网络恢复」，避免把局部网络故障放大成全局不可用。

代价也很直接：

- 真正死掉的实例会继续留在列表里；
- 调用方可能持续打到死实例；
- 如果客户端没有超时/重试/熔断，故障会向上游扩散。

所以：

- 开发/测试环境可以关闭自保护，快速看到真实状态；
- 生产环境要保留自保护，但必须配合客户端超时、重试、熔断和健康检查；
- 看到 `EMERGENCY! EUREKA MAY BE INCORRECTLY CLAIMING INSTANCES ARE UP...` 日志时，先查网络和续约率，不要第一反应就关掉。

### 2.8 健康检查的三个层次

很多「注册中心不健康」的问题，来自把三个层次混为一谈：

| 层次 | 谁判断 | 默认行为 | 影响 |
|---|---|---|---|
| 注册层心跳 | 注册中心 + 客户端 | Eureka 30s 心跳/90s 过期；Nacos 5s/15s/30s | 实例是否还在目录里 |
| 实例层健康 | 应用自身（Actuator） | Eureka 默认不启用健康检查处理器；Nacos 默认不启用 discovery health indicator | 实例是否愿意接流量 |
| 负载均衡层健康 | 调用方（Spring Cloud LB） | `health-check` 配置才会主动探测 | 调用方是否跳过不健康实例 |

几个需要核对源码的点：

- Eureka 的 `eureka.client.healthcheck.enabled` 在 `additional-spring-configuration-metadata.json` 里写着 `defaultValue: true`，但 `EurekaDiscoveryClientConfiguration` 的条件是：

  ```java
  @ConditionalOnProperty(value = "eureka.client.healthcheck.enabled", matchIfMissing = false)
  ```

  也就是**不显式配置就不会创建 `EurekaHealthCheckHandler`**。以运行行为为准：默认只看心跳，不看 Actuator 健康。

- Nacos 的 `spring.cloud.nacos.discovery.health-indicator.enabled` 在 SCA `2023.0.3.4` 中默认 `false`；`spring.cloud.nacos.discovery.heart-beat.enabled` 默认也是 `false`，而且它控制的是 Spring `HeartbeatEvent` 的发布（给 Gateway/Boot Admin 用），**不是注册心跳**。

- Spring Cloud LoadBalancer 的 `health-check` 模式默认关闭；默认 `RoundRobinLoadBalancer` 不做主动健康探测，只信任发现客户端返回的列表。

结论：**注册中心的「健康」通常只代表「我还联系得上」，不代表「我能正确处理业务请求」**。业务健康要靠 Actuator、就绪探针、LB 健康检查或熔断降级来补。

### 2.9 多层缓存与收敛时间

把缓存层画出来：

```text
注册中心服务端
  ├─ 注册表（内存）
  ├─ 响应缓存（Eureka：30s 更新）
  └─ 推送通道（Nacos：gRPC）

调用方
  ├─ 发现客户端本地缓存（Eureka：30s 拉取；Nacos：订阅缓存）
  ├─ Spring Cloud LoadBalancer 缓存（默认 35s TTL）
  └─ HTTP 连接池 / Keep-Alive
```

每一层都在用「旧数据」换「低延迟和高可用」。这解释了三个常见现象：

1. 刚注册的实例，调用方可能几十秒内看不到；
2. 刚下线的实例，调用方可能继续打几十秒到几分钟；
3. 注册中心控制台显示正常，调用方仍然报 `No instances available`。

排查时不要只盯着注册中心，要沿着这条链逐层看。

---

## 三、Spring Cloud 的抽象：一套接口，多个实现

### 3.1 依赖与 BOM

先统一版本，避免 `NoSuchMethodError`、`ClassNotFoundException` 这类典型的版本错配：

```xml
<properties>
    <java.version>17</java.version>
    <spring-cloud.version>2023.0.6</spring-cloud.version>
    <spring-cloud-alibaba.version>2023.0.3.4</spring-cloud-alibaba.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>${spring-cloud-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

选择实现：

```xml
<!-- Eureka 服务端 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>

<!-- Eureka 客户端；starter 已包含 spring-cloud-starter-loadbalancer -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>

<!-- Nacos 客户端 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>

<!-- Nacos starter 对 loadbalancer 是 optional，使用 @LoadBalanced 时要显式加 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

一个容易忽略的事实：`spring-cloud-starter-netflix-eureka-client` 的 POM 把 `spring-cloud-starter-loadbalancer` 作为 compile 依赖引入；而 `spring-cloud-starter-alibaba-nacos-discovery` 对 `spring-cloud-loadbalancer` 是 optional。用 Nacos 时如果只加 discovery starter，`@LoadBalanced` 可能没有可用的 LB 客户端。

### 3.2 核心接口

| 接口/类 | 所在模块 | 职责 |
|---|---|---|
| `DiscoveryClient` | Commons | 查询：`getInstances(serviceId)`、`getServices()`、`probe()` |
| `ReactiveDiscoveryClient` | Commons | 响应式版本的查询 |
| `ServiceInstance` | Commons | 一个实例：`serviceId`、`host`、`port`、`secure`、`metadata`、`uri` |
| `ServiceRegistry` | Commons | 写：`register`、`deregister`、`setStatus` |
| `Registration` | Commons | 待注册实例的描述 |
| `AutoServiceRegistration` | Commons | 把「Web 容器启动完成」和「注册」连接起来 |
| `LoadBalancerClient` | Commons | 阻塞式选择实例并执行请求 |
| `ReactiveLoadBalancer` | Commons | 响应式选择实例 |
| `ServiceInstanceListSupplier` | LoadBalancer | 提供实例列表及过滤/缓存链 |
| `LoadBalancerClientFactory` | LoadBalancer | 为每个 `serviceId` 创建独立的子上下文 |

`DiscoveryClient` 的契约很小：

```java
public interface DiscoveryClient extends Ordered {
    String description();
    List<ServiceInstance> getInstances(String serviceId);
    List<String> getServices();
    default void probe() { getServices(); }
    default int getOrder() { return DEFAULT_ORDER; }
}
```

`ServiceInstance` 也不复杂：

```java
public interface ServiceInstance {
    default String getInstanceId() { return null; }
    String getServiceId();
    String getHost();
    int getPort();
    boolean isSecure();
    URI getUri();
    Map<String, String> getMetadata();
    default String getScheme() { return null; }
}
```

复杂的是这些接口背后的**生命周期、条件和缓存**。

### 3.3 DiscoveryClient 的组合：Composite 与 Simple

Spring Cloud Commons 会自动装配：

```text
SimpleDiscoveryClientAutoConfiguration
  → 基于 spring.cloud.discovery.client.simple.instances 的静态客户端（兜底）
CompositeDiscoveryClientAutoConfiguration
  → @Primary 的组合客户端，按 Order 依次查询所有 DiscoveryClient
```

`CompositeDiscoveryClient` 的查询逻辑是「第一个返回非空实例的客户端获胜」：

```java
for (DiscoveryClient client : discoveryClients) {
    List<ServiceInstance> instances = client.getInstances(serviceId);
    if (instances != null && !instances.isEmpty()) {
        return instances;
    }
}
return Collections.emptyList();
```

这意味着：

- 同时引入 Eureka 和 Nacos 时，实际使用的是**排在前面的非空结果**，而不是合并列表；
- 多注册中心不是天然的「双活」，需要明确主备/优先级；
- 用 `spring.cloud.discovery.client.simple.instances` 可以在没有注册中心时做本地静态发现，适合测试。

### 3.4 自动注册的生命周期

Spring Cloud Commons 的 `AbstractAutoServiceRegistration` 实现了：

```java
ApplicationListener<WebServerInitializedEvent>
```

流程：

```text
WebServerInitializedEvent
  → 忽略 management 子上下文
  → 记录端口
  → start()
       → publish InstancePreRegisteredEvent
       → serviceRegistry.register(registration)
       → 如启用 management 注册，注册 management 实例
       → publish InstanceRegisteredEvent
  → running = true
```

源码关键点：

```java
@Override
public void onApplicationEvent(WebServerInitializedEvent event) {
    ApplicationContext context = event.getApplicationContext();
    if (context instanceof ConfigurableWebServerApplicationContext) {
        if ("management".equals(context.getServerNamespace())) {
            return;   // management 子上下文不重复注册
        }
    }
    this.port.compareAndSet(0, event.getWebServer().getPort());
    this.start();
}
```

为什么是 `WebServerInitializedEvent` 而不是 `ApplicationReadyEvent`？

- `WebServerInitializedEvent` 在 Web 服务器端口真正绑定后发出，此时注册的 `ip:port` 才是可用端口；
- `ApplicationReadyEvent` 更晚，但此时已经可以接流量，注册太晚；
- 注册发生在 Web 服务器启动后、应用完全就绪前，中间有一个窗口。Eureka 的 `instance-enabled-onit=false` 会让实例先以 `STARTING` 出现，但 Spring Cloud 的 `EurekaServiceRegistry` 随后会把状态置为 `initial-status`（默认 `UP`），所以**不能只靠注册状态表达业务就绪**；需要 Actuator/就绪探针来控制流量。

关闭时：

```text
ContextClosedEvent / @PreDestroy
  → stop()
       → serviceRegistry.deregister(registration)
       → serviceRegistry.close()
```

不同实现的细节：

- **Eureka**：`EurekaAutoServiceRegistration` 自己实现了 `SmartLifecycle` 和 `SmartApplicationListener`，在 `ContextClosedEvent` 和 `stop()` 时调用 `EurekaServiceRegistry.deregister()`，把实例状态置为 `DOWN`；随后 `CloudEurekaClient` 的 `shutdown()` 负责客户端资源释放（`should-unregister-on-shutdown=true` 时会执行取消注册）。
- **Nacos**：`NacosAutoServiceRegistration` 继承 `AbstractAutoServiceRegistration`，并额外监听 `ContextClosedEvent`：

  ```java
  @EventListener(ContextClosedEvent.class)
  public void onContextClosedEvent(ContextClosedEvent event) {
      stop();
      ThreadUtils.sleep(gracefulShutdownWaitTime);   // 默认 10s
  }
  ```

  先摘除，再睡 10 秒，让调用方/注册中心有时间感知。

这段代码也暴露了一个重要问题：**先摘除再等待**是正确顺序，但等待时间是否覆盖调用方缓存，是另一回事。后面会算这笔账。

### 3.5 自动配置条件

| 属性 | 默认 | 作用 |
|---|---:|---|
| `spring.cloud.discovery.enabled` | `true` | 是否启用发现客户端相关自动配置 |
| `spring.cloud.service-registry.auto-registration.enabled` | `true` | 是否自动注册 |
| `spring.cloud.service-registry.auto-registration.fail-fast` | `false` | 没有 `AutoServiceRegistration` Bean 时是否启动失败 |
| `spring.cloud.service-registry.auto-registration.register-management` | `true` | 是否把 management 端口作为独立实例注册 |
| `spring.cloud.loadbalancer.enabled` | `true` | 是否启用 Spring Cloud LoadBalancer |
| `eureka.client.enabled` | `true` | 是否启用 Eureka 客户端 |
| `spring.cloud.nacos.discovery.enabled` | `true` | 是否启用 Nacos 发现 |
| `spring.cloud.nacos.discovery.register-enabled` | `true` | 只订阅不注册时设为 `false` |
| `spring.cloud.nacos.discovery.fail-fast` | `true` | Nacos 注册失败时是否抛异常 |

`@EnableDiscoveryClient` 在现代 Spring Cloud 中通常不需要加：

- 各实现通过 `AutoConfiguration.imports` 自动装配；
- `@EnableDiscoveryClient` 主要是历史上用来导入实现；
- 它有一个 `autoRegister` 属性，设为 `false` 会关闭自动注册（本质上添加 `spring.cloud.service-registry.auto-registration.enabled=false` 属性源）。

### 3.6 `@LoadBalanced` 的原理

调用方通常这样写：

```java
@Bean
@LoadBalanced
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
            .setConnectTimeout(Duration.ofSeconds(2))
            .setReadTimeout(Duration.ofSeconds(3))
            .build();
}
```

然后：

```java
restTemplate.getForObject("http://order-service/orders/{id}", Order.class, id);
```

`http://order-service/...` 里的 `order-service` 不是可解析的主机名，而是一个**服务 ID**。真正把它变成 `http://10.0.1.11:8081/...` 的是一条拦截器链：

```text
RestTemplate
  → LoadBalancerInterceptor / RetryLoadBalancerInterceptor
       → 从 URI 中提取 serviceId
       → LoadBalancerClient.execute(serviceId, request)
            → BlockingLoadBalancerClient.choose()
                 → LoadBalancerClientFactory.getInstance(serviceId)
                      → RoundRobinLoadBalancer.choose()
                           → ServiceInstanceListSupplier.get()
                                → DiscoveryClient.getInstances()
            → 选中 ServiceInstance
       → 用 instance.host:port 重建 URI
  → 真正发 HTTP
```

自动配置在 `org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration`：

- 收集所有带 `@LoadBalanced` 的 `RestTemplate`；
- 通过 `RestTemplateCustomizer` 添加 `LoadBalancerInterceptor`；
- 如果 classpath 有 Spring Retry 且 `spring.cloud.loadbalancer.retry.enabled` 未关闭，则使用 `RetryLoadBalancerInterceptor`；
- RestClient 在 4.1.x 也有对应的 BeanPostProcessor 支持。

`BlockingLoadBalancerClient.execute()` 有一个关键失败路径：

```java
ServiceInstance serviceInstance = choose(serviceId, lbRequest);
if (serviceInstance == null) {
    throw new IllegalStateException("No instances available for " + serviceId);
}
```

所以 `No instances available for order-service` 的含义不是「网络不通」，而是**负载均衡器从发现客户端拿到了空列表**。排查要回到发现链路，而不是先查防火墙。

### 3.7 ServiceInstanceListSupplier 链

默认 `LoadBalancerClientConfiguration` 会为每个服务构建：

```text
DiscoveryClientServiceInstanceListSupplier   // 调用 DiscoveryClient.getInstances()
  + CachingServiceInstanceListSupplier       // 默认 35s TTL 缓存
```

对应源码：

```java
@ServiceInstanceListSupplier.builder()
    .withBlockingDiscoveryClient()   // 或 withDiscoveryClient()
    .withCaching()
    .build(context);
```

负载均衡缓存默认值：

| 属性 | 默认 |
|---|---:|
| `spring.cloud.loadbalancer.cache.enabled` | `true` |
| `spring.cloud.loadbalancer.cache.ttl` | `35s` |
| `spring.cloud.loadbalancer.cache.capacity` | `256` |
| `spring.cloud.loadbalancer.service-discovery.timeout` | 未设置（`DiscoveryClientServiceInstanceListSupplier` 内部默认 30s） |

这个 35s 缓存是很多「下线后还在打」问题的直接原因：即使注册中心已经摘除，LB 仍可能拿缓存里的旧列表选实例。

### 3.8 服务实例的身份与元数据

`serviceId` 是逻辑服务名，`instanceId` 是实例唯一标识。默认值：

- Eureka：`IdUtils.getDefaultInstanceId(env)` → `hostname:applicationName:port`（还支持 `spring.application.instance_id` 覆盖）；
- Nacos：实例 ID 由 Nacos 侧管理，客户端在元数据里暴露 `nacos.instanceId`。

元数据（metadata）是注册中心最有价值的扩展点：

```yaml
eureka:
  instance:
    metadata-map:
      zone: az-1
      version: v2
      weight: "10"

spring:
  cloud:
    nacos:
      discovery:
        metadata:
          version: v2
          zone: az-1
```

但要注意：

- Spring Cloud LoadBalancer 默认的 `RoundRobinLoadBalancer` **不读权重**；
- Eureka 的 `eureka.instance.metadata-map.weight` 只有配合 weighted 配置或自定义算法才有意义；
- Nacos 的权重只有在 `spring.cloud.loadbalancer.nacos.enabled=true` 时才会被 Nacos 专用 LB 使用；
- 元数据适合放路由、版本、区域、监控标识，不适合放大对象。

---

## 四、Eureka：AP 优先的服务目录

### 4.1 角色与启动入口

Eureka 有两端：

```text
Eureka Server
  ├─ 注册表（内存）
  ├─ 响应缓存
  ├─ 续约/剔除任务
  ├─ Peer 复制
  └─ Jersey REST 接口 + Dashboard

Eureka Client
  ├─ 注册 / 续约 / 取消注册
  ├─ 定时拉取注册表
  ├─ 本地缓存
  └─ 健康检查处理器（可选）
```

服务端只需要一个注解：

```java
@EnableEurekaServer
@SpringBootApplication
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

`@EnableEurekaServer` 导入 `EurekaServerMarkerConfiguration`，`EurekaServerAutoConfiguration` 通过：

```java
@ConditionalOnBean(EurekaServerMarkerConfiguration.Marker.class)
```

判断是否启用。也就是说，不是加了 starter 就会启动注册中心，必须有这个标记。

Eureka Server 的 REST 端点默认在 `/eureka`，常用的有：

```text
GET /eureka/apps                 查看所有应用
GET /eureka/apps/{appName}       查看某个应用
GET /eureka/apps/{app}/{id}      查看某个实例
```

默认返回 XML，可以用：

```bash
curl -H "Accept: application/json" http://localhost:8761/eureka/apps
```

### 4.2 服务端默认值（源码核对）

| 属性 | 默认 | 说明 |
|---|---:|---|
| `eureka.server.enable-self-preservation` | `true` | 自保护 |
| `eureka.server.renewal-percent-threshold` | `0.85` | 续约阈值百分比 |
| `eureka.server.renewal-threshold-update-interval-ms` | `900000` | 15 分钟重算阈值 |
| `eureka.server.eviction-interval-timer-in-ms` | `60000` | 60s 扫描过期租约 |
| `eureka.server.response-cache-update-interval-ms` | `30000` | 响应缓存 30s 更新 |
| `eureka.server.use-read-only-response-cache` | `true` | 客户端读只读缓存 |
| `eureka.server.wait-time-in-ms-when-sync-empty` | `300000` | 同步为空时等待 5 分钟 |
| `eureka.server.peer-eureka-nodes-update-interval-ms` | `600000` | Peer 节点 10 分钟更新 |
| `eureka.server.expected-client-renewal-interval-seconds` | `30` | 预期客户端续约间隔 |
| `eureka.server.rate-limiter-enabled` | `false` | 默认不限流 |
| `eureka.dashboard.enabled` | `true` | 控制台开关 |
| `eureka.dashboard.path` | `/` | 控制台路径 |
| `eureka.instance.registry.expected-number-of-clients-sending-renews` | `1` | 单机默认期望客户端数 |
| `eureka.instance.registry.default-open-for-traffic-count` | `1` | 单机默认放行数 |

这里有一个必须指出的**元数据与源码不一致**：

- `spring-configuration-metadata.json` 里 `eureka.server.eviction-interval-timer-in-ms` 的 `defaultValue` 是 `0`；
- 但 `EurekaServerConfigBean` 构造函数里是：

  ```java
  private long evictionIntervalTimerInMs = 60 * 1000;
  ```

  所以运行时的默认值是 **60s**，不是 0。以源码/运行行为为准。

同理，`eureka.client.healthcheck.enabled` 的元数据写 `true`，但实际条件 `matchIfMissing=false`，默认不启用健康检查处理器。

### 4.3 客户端默认值（源码核对）

| 属性 | 默认 | 说明 |
|---|---:|---|
| `eureka.client.register-with-eureka` | `true` | 是否注册自己 |
| `eureka.client.fetch-registry` | `true` | 是否拉取注册表 |
| `eureka.client.registry-fetch-interval-seconds` | `30` | 拉取间隔 |
| `eureka.client.instance-info-replication-interval-seconds` | `30` | 实例变更复制间隔 |
| `eureka.client.initial-instance-info-replication-interval-seconds` | `40` | 初始复制间隔 |
| `eureka.client.eureka-service-url-poll-interval-seconds` | `300` | 服务端 URL 轮询 |
| `eureka.client.eureka-server-connect-timeout-seconds` | `5` | 连接超时 |
| `eureka.client.eureka-server-read-timeout-seconds` | `8` | 读超时 |
| `eureka.client.should-unregister-on-shutdown` | `true` | 关闭时取消注册 |
| `eureka.client.filter-only-up-instances` | `true` | 只保留 UP 实例 |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka/` | 默认注册中心地址 |
| `eureka.instance.lease-renewal-interval-in-seconds` | `30` | 心跳间隔 |
| `eureka.instance.lease-expiration-duration-in-seconds` | `90` | 租约过期 |
| `eureka.instance.prefer-ip-address` | `false` | 默认注册 hostname，不是 IP |
| `eureka.instance.instance-enabled-onit` | `false` | 启动初期先 `STARTING`，避免过早接流量 |
| `eureka.instance.initial-status` | `UP` | 注册时设置的状态 |
| `eureka.client.healthcheck.enabled` | 实际未启用 | 只有显式配置才创建 `EurekaHealthCheckHandler` |

重点解释两个默认值：

#### `prefer-ip-address=false`

`EurekaInstanceConfigBean.getHostName()` 的逻辑是：

```java
return this.preferIpAddress ? this.ipAddress : this.hostname;
```

默认 `false`，所以 Eureka **注册的是主机名**，不是 IP。这在物理机/同网段环境通常没问题；在容器、跨网段、Kubernetes、NAT 环境里，主机名可能不可解析，表现为：

```text
注册成功 → 调用方拿到 hostname → DNS 解析失败 / 解析到错误地址 → 调用失败
```

生产上常见做法：

```yaml
eureka:
  instance:
    prefer-ip-address: true
    instance-id: ${spring.cloud.client.ip-address}:${server.port}
```

`spring.cloud.client.ip-address` 由 `HostInfoEnvironmentPostProcessor` 写入，取第一个非回环地址。

#### `instance-enabled-onit=false` 与 `initial-status=UP`

`InstanceInfoFactory` 在非 `instance-enabled-onit` 时先把状态设为 `STARTING`，理由是「避免过早接流量」；随后 `EurekaServiceRegistry.register()` 会把状态设置为 `EurekaInstanceConfigBean.getInitialStatus()`，默认 `UP`。所以默认最终是 UP，但中间状态和健康检查的组合会影响流量窗口。

### 4.4 注册与心跳链路

```text
应用启动
  → Web 服务器绑定端口
  → WebServerInitializedEvent
  → EurekaAutoServiceRegistration.onApplicationEvent()
  → EurekaServiceRegistry.register()
       → ApplicationInfoManager.setInstanceStatus(initialStatus)
       → 注册健康检查处理器（如果存在）
  → Eureka Client 后台线程：
       ├─ 每 30s 续约（heartbeat）
       ├─ 每 30s 拉取注册表
       └─ 实例变更时复制到服务端
```

服务端：

```text
收到续约
  → 找到租约
  → 重置 lastUpdateTimestamp
  → 更新续约统计

定时 eviction（默认 60s）
  → 检查租约是否超过 90s 未续约
  → 检查自保护阈值
  → 允许剔除时，按过期列表清理
```

### 4.5 拉取、响应缓存与「延迟可见」

Eureka 的读取路径不是一个实时查询：

```text
客户端每 30s 拉取
  → 服务端响应缓存（默认 30s 更新一次，且默认只读）
    → 返回实例列表
      → 客户端本地缓存
```

所以：

- 新实例注册后，调用方最多可能等「服务端缓存刷新 + 客户端拉取」约 60s 才看到；
- 实例被剔除后，调用方也可能继续看到旧列表一段时间；
- `eureka.client.filter-only-up-instances=true` 会在客户端过滤非 UP 实例，但过滤发生在**拿到列表之后**，不能消除拉取延迟。

这也是为什么 Eureka 更适合「实例变化不频繁、能接受最终一致」的场景。

### 4.6 自保护的实战判断

自保护触发时，服务端日志通常会出现：

```text
EMERGENCY! EUREKA MAY BE INCORRECTLY CLAIMING INSTANCES ARE UP WHEN THEY'RE NOT.
RENEWALS ARE LESSER THAN THRESHOLD AND HENCE THE INSTANCES ARE NOT BEING EXPIRED JUST TO BE SAFE.
```

判断步骤：

1. 看是不是网络问题/注册中心与实例之间的连通性问题；
2. 看续约率是否整体下降，而不是单个实例；
3. 看实例是否真的还在运行；
4. 如果是测试环境，可以临时关闭自保护观察真实剔除：

```yaml
eureka:
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
```

生产环境不要只关自保护，要同时补上：

```text
客户端连接/读取超时
有限重试 + 退避
熔断/降级（第 8 阶段）
实例级健康检查/就绪探针
```

### 4.7 Eureka 集群

Eureka 的 HA 靠 Peer 复制：

```yaml
# eureka-server-1
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server-2:8761/eureka/,http://eureka-server-3:8761/eureka/

# eureka-server-2
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server-1:8761/eureka/,http://eureka-server-3:8761/eureka/
```

原则：

- 每个 Peer 的 `defaultZone` 指向其他 Peer；
- 客户端配置多个注册中心地址，避免单点；
- 集群模式下要评估 `eureka.instance.registry.*` 的默认值（单机默认值不一定适合集群）；
- Eureka 是异步复制，不是强一致；网络分区时各节点可能有不同视图。

### 4.8 Eureka 配置示例

服务端：

```yaml
server:
  port: 8761

spring:
  application:
    name: eureka-server

eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
  server:
    enable-self-preservation: true
```

客户端：

```yaml
server:
  port: 8081

spring:
  application:
    name: order-service

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    instance-id: ${spring.cloud.client.ip-address}:${server.port}
    lease-renewal-interval-in-seconds: 30
    lease-expiration-duration-in-seconds: 90
```

### 4.9 Eureka 的边界

- 没有原生的 namespace/group/cluster 四层模型；
- 没有内置鉴权，暴露在公网需要额外安全层；
- 服务端和客户端都进入维护模式，新功能有限；
- 没有配置中心能力；
- 优势是模型简单、AP 语义清晰、Spring Cloud 集成成熟。

---

## 五、Nacos：注册、配置与 AP/CP 双模型

### 5.1 模型与角色

Nacos 的命名模型比 Eureka 多几层：

```text
namespace
  └─ group
       └─ service
            └─ cluster
                 └─ instance
```

- `namespace`：环境隔离（dev/test/prod）；
- `group`：业务/租户隔离；
- `service`：服务名；
- `cluster`：机房/可用区；
- `instance`：`ip`、`port`、`weight`、`ephemeral`、`metadata`、`healthy`、`enabled`。

Nacos 的临时实例（`ephemeral=true`）适合随进程生灭的微服务实例；持久实例（`ephemeral=false`）适合需要人工维护、不依赖客户端心跳的实例。两者的一致性模型不同：

```text
临时实例：Distro，AP，最终一致
持久实例：Raft，CP
```

### 5.2 客户端默认值（源码核对）

| 属性 | 默认 | 说明 |
|---|---:|---|
| `spring.cloud.nacos.discovery.enabled` | `true` | 启用发现 |
| `spring.cloud.nacos.discovery.server-addr` | `${spring.cloud.nacos.server-addr:127.0.0.1:8848}` | 注册中心地址 |
| `spring.cloud.nacos.discovery.namespace` | 空 | 默认 public |
| `spring.cloud.nacos.discovery.group` | `DEFAULT_GROUP` | 分组 |
| `spring.cloud.nacos.discovery.register-enabled` | `true` | 是否注册 |
| `spring.cloud.nacos.discovery.ephemeral` | `true` | 临时实例 |
| `spring.cloud.nacos.discovery.weight` | `1` | 权重 |
| `spring.cloud.nacos.discovery.instance-enabled` | `true` | 是否接流量 |
| `spring.cloud.nacos.discovery.fail-fast` | `true` | 注册失败是否抛异常 |
| `spring.cloud.nacos.discovery.graceful-shutdown-wait-time` | `10000ms` | 摘除后等待 |
| `spring.cloud.nacos.discovery.watch-delay` | `30000ms` | HeartbeatEvent 发布间隔 |
| `spring.cloud.nacos.discovery.naming-load-cache-at-start` | `false` | 启动时是否加载本地缓存 |
| `spring.cloud.nacos.discovery.heart-beat-interval` | 未设置（客户端默认 5s） | 心跳间隔元数据 |
| `spring.cloud.nacos.discovery.heart-beat-timeout` | 未设置（客户端默认 15s） | 心跳超时元数据 |
| `spring.cloud.nacos.discovery.ip-delete-timeout` | 未设置（客户端默认 30s） | 删除超时元数据 |
| `spring.cloud.nacos.discovery.heart-beat.enabled` | `false` | 是否发布 Spring HeartbeatEvent，不是注册心跳 |
| `spring.cloud.nacos.discovery.watch.enabled` | `false` | 是否启用 `NacosWatch` |
| `spring.cloud.nacos.discovery.failure-tolerance-enabled` | `false` | 发现异常时是否返回本地缓存 |
| `spring.cloud.nacos.discovery.health-indicator.enabled` | `false`（2023.0.3.4） | 是否启用 discovery health indicator |

两个容易误解的点：

1. `heart-beat.enabled` 控制的是 `NacosDiscoveryHeartBeatPublisher` 发布的 Spring `HeartbeatEvent`，用于 Gateway 动态路由、Spring Boot Admin 等场景；它不是 Nacos 注册心跳开关。
2. `watch-delay` 在 2023.0.x 中主要被 HeartBeatPublisher 用作事件发布间隔；`NacosWatch` 默认关闭，且它做的是订阅实例变化、同步元数据，不是注册心跳。

### 5.3 注册链路

```text
NacosServiceRegistryAutoConfiguration
  → NacosRegistration（收集 metadata、management 端口、心跳元数据）
  → NacosAutoServiceRegistration（继承 AbstractAutoServiceRegistration）
  → NacosServiceRegistry.register()
       → NamingService.registerInstance(serviceId, group, instance)
```

`NacosServiceRegistry.register()` 的关键逻辑：

```java
Instance instance = getNacosInstanceFromRegistration(registration);
try {
    namingService.registerInstance(serviceId, group, instance);
} catch (Exception e) {
    if (nacosDiscoveryProperties.isFailFast()) {
        rethrowRuntimeException(e);
    } else {
        log.warn(...);
    }
}
```

默认 `fail-fast=true`，注册失败会让启动失败；如果希望注册中心故障时应用仍能启动（例如本地开发），可以改为 `false`，但生产要谨慎，因为这会制造「应用在跑，但没人能发现它」的隐性故障。

服务名的默认值：

```text
spring.cloud.nacos.discovery.service
  = ${spring.application.name}
```

如果 `spring.application.name` 为空，`NacosServiceRegistry.register()` 会打日志并直接返回：

```text
No service to register for nacos client...
```

这是「应用启动了但控制台没有实例」的常见原因。

### 5.4 发现链路与本地缓存

```text
NacosDiscoveryClient.getInstances(serviceId)
  → NacosServiceDiscovery.getInstances(serviceId)
       → NamingService.selectInstances(serviceId, group, true)
            → 订阅 + 本地缓存（Nacos 2.x 通过 gRPC 接收变更）
  → hostToServiceInstanceList()
       → 过滤 !enabled / !healthy
       → NacosServiceInstance
```

`NacosServiceDiscovery.getInstances()` 调用的是：

```java
namingService().selectInstances(serviceId, group, true);
```

这个 `true` 表示只返回健康实例；底层默认 `subscribe=true`，客户端会订阅该服务并维护本地缓存。Nacos 2.x 通过 gRPC 长连接接收服务端推送，所以正常情况下比 Eureka 的 30s 拉取更快。

转换后的元数据包含：

```text
nacos.instanceId
nacos.weight
nacos.healthy
nacos.cluster
nacos.ephemeral
```

这些元数据后续可以被 LB、灰度路由、监控使用。

`NacosWatch` 默认关闭：

```java
@ConditionalOnProperty(value = "spring.cloud.nacos.discovery.watch.enabled", matchIfMissing = false)
```

它用于订阅当前服务的实例变化并同步元数据，不是发现其他服务所必需。

### 5.5 心跳、长连接与健康

Nacos Client 的默认常量（`com.alibaba.nacos.api.common.Constants`）：

```text
DEFAULT_HEART_BEAT_INTERVAL = 5s
DEFAULT_HEART_BEAT_TIMEOUT  = 15s
DEFAULT_IP_DELETE_TIMEOUT   = 30s
```

Nacos 2.x 的临时实例使用 gRPC 长连接：

- 客户端注册后保持连接；
- 服务端通过连接健康/心跳判断实例状态；
- 连接断开后客户端有 redo 机制，重连后会重新注册；
- 服务端在心跳超时后标记不健康，超过删除超时后摘除。

持久实例则不同：服务端主动探测，适合不希望依赖客户端心跳的场景。

需要区分：

```text
Nacos 注册心跳：客户端/长连接 → 服务端
Spring HeartbeatEvent：应用内部事件 → Gateway/Boot Admin 监听
Actuator health：应用健康 → 监控/探针
```

三者不是一回事。

### 5.6 优雅下线与缓存对齐

Nacos 的 `NacosAutoServiceRegistration` 在 `ContextClosedEvent` 时：

```text
stop()（deregister）
  → sleep(gracefulShutdownWaitTime)  // 默认 10s
```

设计意图：先让注册中心摘除实例，再等待一段时间，让调用方感知，最后 Web 服务器停止接收请求。

问题在于默认的 10s 小于 Spring Cloud LoadBalancer 默认的 35s 缓存 TTL：

```text
Nacos 摘除 + 等待 10s
  → 注册中心已没有该实例
  → 但调用方 LB 缓存可能还有 35s
  → 新请求仍可能选中正在关闭的实例
```

三种处理方式：

1. 把 `graceful-shutdown-wait-time` 调整到覆盖调用方缓存（例如大于 35s），但会延长发布窗口；
2. 使用 Nacos 专用 LB 模式（关闭 Spring Cloud LB 缓存，见 6.7），让调用方更快感知；
3. 调用方配合有限重试/熔断，把「选中正在关闭的实例」当成可恢复错误。

同时建议开启 Boot 优雅停机：

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

注意 `server.shutdown` 默认是 `immediate`；不配置的话，Web 服务器可能直接停止，注册中心的摘除和调用方的感知都来不及完成。

### 5.7 namespace、group、cluster 的区别与坑

| 维度 | 作用 | 不匹配的后果 |
|---|---|---|
| namespace | 环境隔离（dev/test/prod） | 消费者看不到生产者 |
| group | 业务/租户隔离 | 消费者看不到生产者 |
| service | 服务名 | 调用方请求的服务名找不到 |
| cluster | 机房/可用区 | Nacos LB 优先同 cluster；找不到时可能跨集群 |

最常见的生产事故：

```text
生产者：namespace=prod, group=ORDER_GROUP, service=order-service
消费者：namespace=prod, group=DEFAULT_GROUP, service=order-service
→ 消费者拿到空列表 → No instances available
```

排查时先对齐这四个维度，再查网络和 IP。

### 5.8 Nacos 配置示例

客户端：

```yaml
server:
  port: 8081

spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: prod
        group: ORDER_GROUP
        weight: 1
        ephemeral: true
        metadata:
          version: v1
```

如果需要显式设置心跳/删除参数：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        heart-beat-interval: 5000
        heart-beat-timeout: 15000
        ip-delete-timeout: 30000
```

Nacos 2.x 部署注意：

- 客户端除了 `8848`，还会使用 gRPC 端口（默认在服务端端口基础上偏移，常见为 `9848`/`9849`）；
- 集群还会使用 `7848` 等端口；
- 只开放 `8848`，可能出现「能打开控制台，但客户端注册/订阅异常」。

### 5.9 Eureka vs Nacos

| 维度 | Eureka | Nacos |
|---|---|---|
| 一致性 | AP，Peer 异步复制 | 临时实例 AP（Distro），持久实例 CP（Raft） |
| 健康模型 | 客户端心跳 30s/90s | 临时实例心跳/长连接；持久实例服务端探测 |
| 发现方式 | 客户端 30s 拉取 + 服务端 30s 响应缓存 | 订阅 + gRPC 推送 + 本地缓存 |
| 命名空间 | 无原生 namespace/group | namespace/group/cluster/service |
| 权重 | metadata 权重，默认 LB 不读 | Nacos LB 模式支持权重 |
| 配置中心 | 无 | 有（06-02） |
| 鉴权 | 无内置 | 支持（可选开启） |
| 控制台 | 有 | 有 |
| 维护状态 | Spring Cloud Netflix 维护模式 | Spring Cloud Alibaba 活跃维护 |

选型不是「谁更强」，而是：

- 只要注册发现、模型简单、已有 Eureka 生态 → Eureka；
- 需要命名空间、分组、权重、配置中心一体化 → Nacos；
- 需要强一致/固定实例/多语言 → 评估 Consul、ZooKeeper 或 Kubernetes Service。

---

## 六、Spring Cloud LoadBalancer：客户端怎么选实例

### 6.1 客户端负载均衡的职责边界

Spring Cloud LoadBalancer 只做三件事：

```text
1. 拿到实例列表（通过 ServiceInstanceListSupplier）
2. 按算法选一个实例
3. 把 serviceId 还原成 host:port
```

它**不负责**：

- 设置 HTTP 连接/读取超时；
- 重试的业务语义；
- 熔断、限流、隔离；
- 服务端负载均衡。

这些是调用方代码、Feign、Resilience4j/Sentinel（第 8 阶段）的职责。很多「LB 不生效」其实是「没有配置超时/重试」，而不是算法问题。

### 6.2 默认算法：RoundRobin

`LoadBalancerClientConfiguration` 默认创建：

```java
return new RoundRobinLoadBalancer(
        loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class),
        name);
```

核心逻辑：

```java
if (instances.isEmpty()) {
    return new EmptyResponse();
}
if (instances.size() == 1) {
    return new DefaultResponse(instances.get(0));
}
int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;
ServiceInstance instance = instances.get(pos % instances.size());
```

细节：

- 初始位置是随机种子，避免所有客户端从同一个实例开始；
- 单实例时不做位置递增；
- 空列表返回 `EmptyResponse`，最终在 `BlockingLoadBalancerClient` 变成 `IllegalStateException("No instances available for ...")`；
- 实例列表来自 supplier，可能已经被缓存、区域过滤、健康过滤。

### 6.3 缓存：35 秒的旧列表

默认 supplier 链包含 `CachingServiceInstanceListSupplier`：

```text
缓存命中 → 直接返回
缓存未命中 → 调用 DiscoveryClient.getInstances()
             → 写入缓存（TTL 35s，容量 256）
```

这带来一个反直觉的结果：

```text
注册中心已经摘除实例
  ≠ 调用方立即停止调用
  ≠ LB 立即停止选择
```

如果你的场景要求「下线后尽快停止流量」，要么缩短 TTL（会增加发现客户端压力），要么使用推送型注册中心 + 无 LB 缓存模式（Nacos LB 模式），要么在调用方加重试/熔断。

### 6.4 可选的实例列表策略

通过 `spring.cloud.loadbalancer.configurations` 选择：

| 值 | 作用 |
|---|---|
| `default` | DiscoveryClient + 缓存 |
| `zone-preference` | 优先同 zone，找不到回退全量 |
| `health-check` | 主动探活，过滤不健康实例 |
| `weighted` | 按权重选择 |
| `same-instance-preference` | 尽量复用同一实例（粘性） |
| `request-based-sticky-session` | 基于请求的粘性会话 |
| `subset` | 按条件筛选子集 |

示例：

```yaml
spring:
  cloud:
    loadbalancer:
      configurations: zone-preference
      cache:
        ttl: 35s
```

注意：

- `health-check` 需要 Actuator/健康端点，会增加额外请求；
- `weighted` 读取实例权重，但不同注册中心的权重来源不同；
- `zone-preference` 依赖实例元数据/区域信息；
- 这些策略不是「越多越好」，每加一层都会增加延迟和排障复杂度。

### 6.5 RestTemplate / WebClient / RestClient

`@LoadBalanced` 对三种客户端都有支持：

```java
@Bean
@LoadBalanced
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();
}

@Bean
@LoadBalanced
public WebClient.Builder webClientBuilder() {
    return WebClient.builder();
}
```

WebClient 通过 `ReactorLoadBalancerClientAutoConfiguration` 和过滤器实现服务名解析；RestClient 在 4.1.x 通过 `LoadBalancerRestClientBuilderBeanPostProcessor` 支持。

Feign 不需要 `@LoadBalanced`，它有自己的 LB 集成（06-03 会展开）。

### 6.6 超时与重试：LB 不会替你兜底

最常见的错误配置：

```java
@Bean
@LoadBalanced
public RestTemplate restTemplate() {
    return new RestTemplate();   // 没有连接/读取超时
}
```

结果是：一旦选中的实例卡住，调用方线程会一直占用，最终把线程池/连接池拖垮。

正确做法：

```java
@Bean
@LoadBalanced
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
            .setConnectTimeout(Duration.ofSeconds(2))
            .setReadTimeout(Duration.ofSeconds(3))
            .build();
}
```

重试要满足：

- 只对幂等请求重试；
- 有最大次数和退避；
- 不重试已经明确失败的业务异常；
- 结合熔断/限流（第 8 阶段）。

### 6.7 Nacos 专用 LB：什么时候权重才生效

Spring Cloud Alibaba 提供了一个可选的 Nacos LB：

```yaml
spring:
  cloud:
    loadbalancer:
      nacos:
        enabled: true
```

只有开启后，`LoadBalancerNacosAutoConfiguration` 才会生效，用 `NacosLoadBalancer` 替换默认的 `RoundRobinLoadBalancer`：

```java
@ConditionalOnProperty(value = "spring.cloud.loadbalancer.nacos.enabled", havingValue = "true")
```

它的默认算法是 `DefaultLoadBalancerAlgorithm` → `NacosBalancer.getHostByRandomWeight3()`，即**按权重随机**；并且会：

- 优先同 cluster 的实例；
- 按 IPv4/IPv6 过滤；
- 跳过 Spring Cloud LoadBalancer 的缓存（因为 Nacos 客户端本身已有缓存）。

如果不开这个开关：

```text
默认 RoundRobinLoadBalancer
  → 权重不生效
  → 仍然使用 35s LB 缓存
```

这是「Nacos 权重配置了但没效果」的最常见原因。

### 6.8 元数据路由的边界

用元数据可以做轻量的灰度/版本路由：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        metadata:
          version: v2
```

但默认 LB 不会自动按 `version` 过滤。需要：

- 自定义 `ServiceInstanceListSupplier`；
- 或使用 `subset` 配置；
- 或使用 Nacos LB 的 `ServiceInstanceFilter`；
- 或在 Feign 拦截器/网关层做路由（06-03、06-04、第 8 阶段）。

不要误以为「写了 metadata，LB 就会自动按它路由」。

---

## 七、失败模型：注册中心、网络和实例分别坏了会怎样

### 7.1 时间线

#### Eureka 硬宕机

```text
t=0     实例最后一次心跳
t=90s   租约过期（lease-expiration-duration-in-seconds）
t≤150s  eviction 扫描到并剔除（eviction-interval-timer-in-ms=60s）
t≤180s  调用方下一次拉取（registry-fetch-interval-seconds=30s）
t≤215s  LB 缓存过期（cache.ttl=35s）
```

这是最坏量级，实际取决于缓存失效路径和拉取时机。结论：**不要假设 kill -9 后实例会立刻消失**。

#### Eureka 正常下线

```text
ContextClosedEvent
  → EurekaAutoServiceRegistration.stop()
  → EurekaServiceRegistry.deregister()（状态 DOWN）
  → CloudEurekaClient.shutdown()（取消注册）
  → 调用方下一次拉取前可能仍使用旧列表
  → LB 缓存可能再兜 35s
```

#### Nacos 正常下线

```text
ContextClosedEvent
  → NacosAutoServiceRegistration.stop()
  → NacosServiceRegistry.deregister()
  → sleep(gracefulShutdownWaitTime=10s)
  → Web 服务器停止
  → 调用方通过 gRPC 推送感知（通常较快）
  → 但 Spring Cloud LB 缓存仍可能有 35s
```

#### Nacos 硬宕机

```text
t=0     最后一次心跳/连接活跃
t=15s   心跳超时，实例不健康
t=30s   删除超时，临时实例被摘除
         → 订阅者收到推送
         → LB 缓存仍可能保留旧列表
```

### 7.2 故障矩阵

| 故障 | 注册中心表现 | 调用方表现 | 关键检查 |
|---|---|---|---|
| 注册中心服务端宕机 | 无法注册/查询 | 已有缓存可继续；新实例不可见 | 客户端本地缓存、注册中心 HA |
| 实例与注册中心网络分区 | Eureka 可能自保护；Nacos 心跳超时 | 列表可能保留死实例或提前摘除 | 网络、心跳、续约率 |
| 实例进程崩溃 | 租约过期/心跳超时后摘除 | 缓存窗口内仍可能被调用 | 超时、重试、熔断 |
| 实例正常下线 | 主动摘除 | 缓存窗口内仍可能被调用 | 优雅停机、缓存 TTL |
| 注册 IP 不可达 | 实例显示 UP | 连接超时/拒绝 | 广告 IP、NAT、防火墙 |
| namespace/group 不匹配 | 生产者存在，消费者查不到 | `No instances available` | 命名空间/分组 |
| LB 缓存旧列表 | 注册中心正确 | 选中已下线实例 | LB TTL、Nacos LB 模式 |
| 自保护触发 | 死实例不剔除 | 持续打不通 | 续约率、网络、日志 |

### 7.3 容器与 IP 广告

微服务最常见的「注册成功但调不通」：

```text
容器内进程监听 0.0.0.0:8080
  → 注册中心看到的是容器 IP（如 172.17.0.2）
  → 调用方在另一台机器上，无法路由到该容器 IP
```

解决方向：

- 让注册中心注册**调用方可达的地址**（宿主机 IP、Service IP、Ingress 地址）；
- Eureka：`eureka.instance.prefer-ip-address=true` + `eureka.instance.ip-address` 或 `spring.cloud.client.ip-address`；
- Nacos：`spring.cloud.nacos.discovery.ip` / `network-interface`；
- Kubernetes：通常不用 Eureka/Nacos 做 Service 发现，直接用 Service DNS；如果必须用，注册 Pod IP 并保证网络可达；
- 云环境 NAT：注册公网/负载均衡地址，而不是容器内网地址。

### 7.4 注册中心的安全边界

注册中心是控制面，不应该暴露在公网：

- Eureka 默认没有鉴权，建议放在内网/Service Mesh/网关后；
- Nacos 支持鉴权，生产应开启并修改默认凭据；
- 注册中心的控制台、Actuator、REST API 都要限制访问；
- 不要把敏感配置放进实例 metadata（metadata 对所有发现方可见）。

---

## 八、最小可运行示例：从单体到两个实例

### 8.1 拓扑

```text
Eureka Server :8761
  ↑
order-service :8081  ─┐
order-service :8082  ─┴─ 注册/心跳
  ↑
caller-service :8080 ── 通过 http://order-service/... 调用
```

### 8.2 服务端

```java
@EnableEurekaServer
@SpringBootApplication
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

```yaml
server:
  port: 8761
spring:
  application:
    name: eureka-server
eureka:
  client:
    register-with-eureka: false
    fetch-registry: false
```

### 8.3 生产者（两个实例）

```yaml
server:
  port: ${PORT:8081}
spring:
  application:
    name: order-service
eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    instance-id: ${spring.cloud.client.ip-address}:${server.port}
```

用两个端口启动：

```bash
java -jar order-service.jar --server.port=8081
java -jar order-service.jar --server.port=8082
```

### 8.4 消费者

```java
@Configuration
class RestConfig {

    @Bean
    @LoadBalanced
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }
}

@RestController
class CallerController {

    private final RestTemplate restTemplate;
    private final DiscoveryClient discoveryClient;

    CallerController(RestTemplate restTemplate, DiscoveryClient discoveryClient) {
        this.restTemplate = restTemplate;
        this.discoveryClient = discoveryClient;
    }

    @GetMapping("/call/{id}")
    public String call(@PathVariable Long id) {
        return restTemplate.getForObject(
                "http://order-service/orders/{id}", String.class, id);
    }

    @GetMapping("/instances")
    public Object instances() {
        return discoveryClient.getInstances("order-service")
                .stream()
                .map(i -> i.getHost() + ":" + i.getPort())
                .toList();
    }
}
```

观察：

```bash
curl http://localhost:8080/instances
curl http://localhost:8080/call/1
```

### 8.5 Nacos 版本

依赖：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<!-- Nacos starter 对 LB 是 optional，显式加 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

配置：

```yaml
spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: prod
        group: ORDER_GROUP
```

启动 Nacos 单机：

```bash
sh startup.sh -m standalone
```

### 8.6 观察点

```text
Eureka：
  http://localhost:8761/           Dashboard
  /eureka/apps                     REST
  /actuator/features               发现/LB 特性
  /actuator/serviceregistry        注册状态

Nacos：
  控制台 → 服务列表
  /actuator/features
  /actuator/nacosdiscovery         Nacos 发现端点
```

`/actuator/features` 可以确认当前生效的 `DiscoveryClient`、`LoadBalancerClient`；`/actuator/serviceregistry` 可以确认自动注册是否装配。

---

## 九、常见故障排查

### 9.1 注册不上

| 检查 | 命令/位置 | 常见原因 |
|---|---|---|
| 服务名 | `spring.application.name` | 未配置或为空 |
| 注册中心地址 | `eureka.client.service-url.defaultZone` / `spring.cloud.nacos.discovery.server-addr` | 地址错、端口错 |
| 网络 | `curl`/`telnet` | Nacos 只开了 8848 控制台端口，未开 gRPC 端口（9848/9849 等） |
| 鉴权 | Nacos 用户名/密码 | 凭据错误 |
| namespace/group | 配置与控制台 | 生产者注册到了别的隔离域 |
| fail-fast | Nacos `fail-fast` | 注册异常被吞或启动失败 |
| 端口 | `server.port`、`management.server.port` | 注册了错误端口 |

### 9.2 注册上了但发现不到

| 检查 | 说明 |
|---|---|
| 消费者与生产者的 namespace/group/service 是否一致 | 最常见的空列表原因 |
| Eureka 拉取间隔/响应缓存 | 刚注册，等 30–60s |
| LB 缓存 | 35s TTL |
| `filter-only-up-instances` | 实例状态不是 UP |
| Nacos 订阅/本地缓存 | 查看 Nacos 控制台和 `nacosdiscovery` 端点 |
| 多注册中心优先级 | `CompositeDiscoveryClient` 取第一个非空结果 |

### 9.3 发现到了但调用失败

| 现象 | 优先检查 |
|---|---|
| Connection refused | 注册端口与实际监听端口不一致 |
| UnknownHostException | Eureka 默认注册 hostname，DNS 不可解析 |
| Connection timeout | 注册的是容器内 IP/NAT 地址 |
| 401/403 | 注册中心或下游鉴权 |
| 404 | context-path、路由前缀不匹配 |
| TLS 错误 | secure 配置与实际协议不一致 |

### 9.4 `No instances available`

```text
BlockingLoadBalancerClient.execute()
  → choose() 返回 null
  → IllegalStateException("No instances available for " + serviceId)
```

排查顺序：

```text
1. 注册中心里有没有这个 serviceId？
2. 实例是否 UP/健康？
3. 消费者与生产者的 namespace/group 是否一致？
4. 服务名大小写/拼写是否一致？
5. LB 缓存是否还是旧列表？
6. 是否同时引入了多个 DiscoveryClient，Composite 取到了错误的那个？
```

### 9.5 下线后仍被调用

按时间顺序排查：

```text
1. 是否执行了 deregister？（强杀不会）
2. 注册中心是否已摘除？
3. 客户端拉取/推送是否已更新？
4. LB 缓存（35s）是否已过期？
5. HTTP 连接池是否复用了旧连接？
6. 调用方是否配置了重试，重试到了旧实例？
```

### 9.6 流量不均

可能原因：

- 轮询只保证次数，不保证耗时不均；
- HTTP Keep-Alive 连接复用导致连接级不均；
- 权重未生效（默认 LB 不读权重 / Nacos LB 未开启）；
- zone-preference 导致跨区流量；
- 实例列表在客户端之间不同步（各自缓存不同）；
- 某个实例更慢，但仍在列表里（心跳健康 ≠ 业务健康）。

### 9.7 启动慢/启动失败

| 原因 | 表现 | 处理 |
|---|---|---|
| 注册中心不可达 | 启动日志重试/超时 | 检查网络、地址、HA |
| Nacos gRPC 端口未开 | 注册/订阅超时 | 开放 9848/9849 等端口 |
| `fail-fast=true` | 注册异常直接失败 | 本地开发可临时关闭，生产保留 |
| 自保护/大量实例 | 启动时拉取大注册表 | 评估拉取/缓存策略 |
| 版本不匹配 | `NoSuchMethodError`/类找不到 | 对齐 BOM |

---

## 十、源码与配置断点

| 想观察什么 | 位置 |
|---|---|
| 自动注册监听 Web 容器启动 | `AbstractAutoServiceRegistration#onApplicationEvent(WebServerInitializedEvent)` |
| Eureka 注册/下线 | `EurekaAutoServiceRegistration#start/stop`、`EurekaServiceRegistry#register/deregister` |
| Eureka 客户端默认值 | `EurekaClientConfigBean` 构造函数 |
| Eureka 实例默认值 | `EurekaInstanceConfigBean`（`preferIpAddress=false`、`leaseRenewal=30`、`leaseExpiration=90`） |
| Eureka 服务端默认值 | `EurekaServerConfigBean` 构造函数（`evictionIntervalTimerInMs=60000`） |
| 自保护判断 | `PeerAwareInstanceRegistryImpl#isLeaseExpirationEnabled/isSelfPreservationModeEnabled` |
| 租约剔除 | `AbstractInstanceRegistry#evict` |
| 续约阈值计算 | `AbstractInstanceRegistry#updateRenewsPerMinThreshold` |
| Eureka 发现 | `EurekaDiscoveryClient#getInstances` |
| Nacos 注册 | `NacosServiceRegistry#register/deregister` |
| Nacos 自动注册 | `NacosAutoServiceRegistration#onContextClosedEvent` |
| Nacos 发现 | `NacosServiceDiscovery#getInstances` |
| Nacos 客户端创建 | `NacosServiceManager#getNamingService` |
| Nacos 专用 LB | `NacosLoadBalancer#choose`、`DefaultLoadBalancerAlgorithm` |
| LB 选择入口 | `BlockingLoadBalancerClient#execute/choose` |
| 默认轮询 | `RoundRobinLoadBalancer#getInstanceResponse` |
| 实例列表缓存 | `CachingServiceInstanceListSupplier`、`LoadBalancerCacheProperties` |
| 发现客户端组合 | `CompositeDiscoveryClient#getInstances` |
| `@LoadBalanced` 拦截 | `LoadBalancerAutoConfiguration`、`LoadBalancerInterceptor` |
| LB 子上下文 | `LoadBalancerClientFactory` |

配置文件：

```text
spring-cloud-commons-4.1.6.jar
  META-INF/spring-configuration-metadata.json
  META-INF/additional-spring-configuration-metadata.json

spring-cloud-netflix-eureka-client-4.1.6.jar
  META-INF/spring-configuration-metadata.json
  META-INF/additional-spring-configuration-metadata.json

spring-cloud-netflix-eureka-server-4.1.6.jar
  META-INF/spring-configuration-metadata.json

spring-cloud-starter-alibaba-nacos-discovery-2023.0.3.x.jar
  META-INF/spring-configuration-metadata.json
  META-INF/additional-spring-configuration-metadata.json
```

自动配置入口：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

可以对照：

```text
org.springframework.cloud.client.serviceregistry.ServiceRegistryAutoConfiguration
org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationAutoConfiguration
org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration
org.springframework.cloud.netflix.eureka.EurekaDiscoveryClientConfiguration
org.springframework.cloud.netflix.eureka.server.EurekaServerAutoConfiguration
com.alibaba.cloud.nacos.registry.NacosServiceRegistryAutoConfiguration
com.alibaba.cloud.nacos.discovery.NacosDiscoveryClientConfiguration
com.alibaba.cloud.nacos.loadbalancer.LoadBalancerNacosAutoConfiguration
```

---

## 十一、必做实验

### 实验一：最小注册发现

1. 启动 Eureka Server；
2. 启动两个 `order-service` 实例（8081/8082）；
3. 启动 `caller-service`，通过 `http://order-service/...` 调用；
4. 连续调用 10 次，观察是否轮询到两个实例；
5. 调用 `/actuator/features`、`/actuator/serviceregistry`、`/eureka/apps` 核对状态。

### 实验二：观察收敛窗口

1. 记录新实例注册的时间；
2. 每秒调用一次消费者，记录第一次成功路由到新实例的时间；
3. 把 `eureka.client.registry-fetch-interval-seconds` 调到 5s，重复实验；
4. 把 LB 缓存 TTL 调到 5s，重复实验；
5. 画出每一层延迟，验证「注册成功 ≠ 立刻可调用」。

### 实验三：硬杀 vs 优雅下线

1. `kill -9` 一个生产者，记录调用方报错开始/结束时间；
2. 正常 `SIGTERM` 关闭另一个生产者，记录调用方停止路由的时间；
3. 分别关闭/开启 Eureka 自保护，观察差异；
4. 计算 90s + 60s + 30s + 35s 的最坏窗口。

### 实验四：自保护触发

1. 让多个实例同时停止心跳（例如防火墙阻断/暂停进程）；
2. 观察续约数低于阈值；
3. 查看 `EMERGENCY!` 日志；
4. 验证死实例是否仍在列表中；
5. 恢复网络，观察实例状态恢复。

### 实验五：Nacos namespace/group 不匹配

1. 生产者注册到 `namespace=prod, group=ORDER_GROUP`；
2. 消费者先使用 `namespace=prod, group=DEFAULT_GROUP`；
3. 观察 `No instances available`；
4. 对齐 group 后恢复；
5. 记录控制台和 `/actuator/nacosdiscovery` 的差异。

### 实验六：Nacos 优雅下线

1. 设置 `graceful-shutdown-wait-time=10000`；
2. 正常关闭生产者；
3. 观察消费者多久停止选中它；
4. 把 LB 缓存 TTL 调到 5s，重复实验；
5. 对齐等待时间和缓存 TTL，观察发布窗口变化。

### 实验七：权重是否生效

1. 两个 Nacos 实例，权重分别设为 1 和 10；
2. 默认配置下调用 100 次，观察分布（应接近 50/50，权重不生效）；
3. 开启 `spring.cloud.loadbalancer.nacos.enabled=true`；
4. 再调用 100 次，观察分布（应接近 1:10）；
5. 观察 Nacos LB 模式是否绕过了 35s 缓存。

### 实验八：注册中心宕机

1. 关闭 Eureka/Nacos；
2. 继续调用已有服务，观察是否还能成功（客户端缓存）；
3. 启动新实例，观察是否能注册；
4. 恢复注册中心，观察状态恢复；
5. 记录「控制面故障」和「数据面故障」的区别。

### 实验九：IP 广告错误

1. 在容器/虚拟机中启动 Eureka 客户端，使用默认 `prefer-ip-address=false`；
2. 从另一台机器调用，观察 `UnknownHostException`；
3. 改为 `prefer-ip-address=true`，或显式设置 `eureka.instance.ip-address`；
4. 观察注册列表和调用结果变化；
5. 用 Nacos 的 `spring.cloud.nacos.discovery.ip` 做同样的实验。

### 实验十：LB 兜底配置

1. 去掉 RestTemplate 的超时；
2. 让一个实例阻塞（例如 `sleep 60s`）；
3. 观察调用方线程被占满；
4. 加上连接/读取超时、有限重试；
5. 观察错误率和线程占用变化。

---

## 十二、面试常问

### 12.1 服务注册和服务发现有什么区别？

注册是服务实例把自己的地址/元数据写入注册中心；发现是调用方从注册中心读取实例列表。注册是写，发现是读；两者共享同一份目录，但生命周期和失败模式不同。

### 12.2 Eureka 和 Nacos 怎么选？

看需求：只要 AP 注册发现、生态简单，Eureka 够用；需要 namespace/group/cluster、权重、配置中心一体化，选 Nacos。Eureka 已进入维护模式，Nacos 功能更全但模型更复杂。

### 12.3 Eureka 自保护是什么？为什么需要？

当每分钟续约数低于阈值（默认 85%）时，Eureka 认为更可能是网络问题，停止剔除过期实例，避免把网络分区放大成目录清空。代价是死实例残留，需要客户端超时/重试/熔断配合。

### 12.4 注册中心挂了，服务还能调用吗？

已有调用可以：客户端本地缓存 + LB 缓存里还有实例列表。新实例注册/下线不可见，直到注册中心恢复。注册中心不在数据面，所以控制面故障不等于数据面故障。

### 12.5 服务下线了，为什么还在被调用？

deregister、服务端缓存、客户端拉取/推送、LB 缓存、HTTP Keep-Alive 是不同层次。正常下线也要等这些缓存过期；强杀还要等租约过期和剔除扫描。

### 12.6 Nacos 的临时实例和持久实例有什么区别？

临时实例随客户端心跳/长连接存活，客户端断连/超时后被摘除，适合微服务；持久实例由服务端主动探测，不依赖客户端心跳，适合固定后端/需要人工维护的实例。

### 12.7 namespace、group、cluster、service 有什么区别？

namespace 隔离环境，group 隔离业务/租户，cluster 隔离机房/可用区，service 是服务名。消费者和生产者在这些维度上必须匹配，否则会出现空实例列表。

### 12.8 Spring Cloud LoadBalancer 默认算法是什么？缓存多久？

默认 `RoundRobinLoadBalancer`；默认 supplier 链带 35s TTL、容量 256 的缓存。Nacos 专用 LB 模式（`spring.cloud.loadbalancer.nacos.enabled=true`）使用权重随机并跳过该缓存。

### 12.9 `@LoadBalanced` 的原理？

`LoadBalancerAutoConfiguration` 给带 `@LoadBalanced` 的 `RestTemplate` 添加拦截器；拦截器从 URI 提取 serviceId，调用 `LoadBalancerClient.execute()`，选中实例后把 serviceId 替换成 host:port，再发请求。

### 12.10 注册中心该怎么保证高可用？

Eureka：多 Peer、客户端配置多个 defaultZone、异步复制；Nacos：集群 + 外部存储 + 客户端配置多个 server-addr + 开放 gRPC/Raft 端口。高可用不是「多起一个进程」，而是客户端、注册中心、网络、存储都要没有单点。

### 12.11 如何排查「注册成功但调不到」？

先看注册中心有没有实例，再看消费者与生产者的 namespace/group/service 是否一致，再看实例状态和广告地址，再看客户端拉取/推送与 LB 缓存，最后看网络/防火墙/端口。

### 12.12 为什么不用 DNS 或 Nginx 就够了？

DNS 解析结果受 TTL 和缓存影响，缺少应用级健康、元数据、权重、快速变更传播；Nginx 是服务端 LB，多一跳且需要动态配置。注册中心 + 客户端 LB 把选择权放到调用方，适合 Spring Cloud 的生态。

---

## 十三、本篇过关标准

学完本文后，需要能做到：

- 画出「服务实例 → 注册中心 → 客户端缓存 → LB 缓存 → HTTP」的完整链路。
- 解释注册、心跳、下线、拉取/订阅、剔除五个动作及各自的失败语义。
- 说出 Eureka 的 30/90/60/30/30/35 秒这些默认值分别属于哪一层。
- 解释 Eureka 自保护的公式、触发条件、利弊和适用场景。
- 说出 `eureka.instance.prefer-ip-address=false` 的后果，以及容器环境怎么修。
- 说出 Eureka 两处元数据与源码不一致的地方，并解释为什么以源码为准。
- 说出 Nacos 的 namespace/group/cluster/service 四层模型和常见不匹配故障。
- 解释 Nacos 临时实例/持久实例、心跳/长连接、gRPC 端口、优雅下线。
- 解释 `@LoadBalanced` 的拦截器链路和 `No instances available` 的真实含义。
- 说出 Spring Cloud LB 默认算法、35s 缓存、Nacos 专用 LB 的开关与差异。
- 能设计一个最小 Eureka/Nacos 注册发现实验，并观察收敛窗口。
- 能根据现象区分「注册失败」「发现不到」「发现到但调不通」「调通了但流量不均」。

---

## 十四、下一篇建议

第 7 阶段刚刚开始。下一篇：

```text
06-02-SpringCloud-配置中心.md
```

重点包括：

- 配置中心与注册中心的职责边界；
- Spring Cloud Config、Nacos Config、Apollo 的定位差异；
- 配置加载顺序、Profile、namespace/group/dataId；
- `@RefreshScope`、配置刷新、动态生效的边界；
- 配置中心的灰度、回滚、安全和审计；
- 与 05-02 的日志/配置治理如何衔接。

注册发现解决「服务在哪」，配置中心解决「服务怎么配」。两者经常一起出现，但一致性和失败模型完全不同，下一篇会把这条边界讲清楚。
