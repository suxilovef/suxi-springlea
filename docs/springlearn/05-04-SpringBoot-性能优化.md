# Spring Boot 专题：性能优化与容量治理源码深度解析

> 本文是第 6 阶段 Spring Boot 工程化与生产治理的最后一篇，承接 `05-03-SpringBoot-测试与部署.md`。
>
> 05-03 解决了「怎么测、怎么打包、怎么发布、怎么优雅停机」。最后一篇回答：
>
> 1. 一个请求变慢，应该先看 CPU、内存、线程、连接池，还是数据库？
> 2. Boot 的线程池、Hikari 连接池、Tomcat 默认值分别是什么，哪些默认值在生产上是危险的？
> 3. JPA 的 `open-in-view`、N+1、批量写入、分页、索引该怎么处理？
> 4. 压测怎么做才不是「自欺欺人」？性能回归怎么防？
>
> 基准：Spring Boot `3.3.9` / HikariCP `5.1.0` / Java `17`（虚拟线程需要 Java 21 运行时）。
>
> 本文的默认值用 `javap` 反编译本地 Boot 3.3.9 / HikariCP 5.1.0 jar，并对照 `META-INF/spring-configuration-metadata.json` 核对过。

---

## 一、先给结论

### 1.1 性能问题是一条约束链

```text
客户端
  → 网络 / LB / TLS
    → Tomcat 连接数 / 线程池
      → 应用线程
        → 连接池
          → 数据库 / Redis / 下游服务
            → 序列化 / GC / CPU
```

任何一个环节成为瓶颈，整条链的吞吐都会被它限制。**优化不是把所有参数调大，而是找到当前最窄的那一段。**

### 1.2 优化顺序

```text
1. 建立基线
2. 用指标/Profiler 定位瓶颈
3. 一次只改一个变量
4. 在相同环境复测
5. 记录回归阈值
6. 准备回滚
```

同时改线程池、连接池、GC、缓存，最后变快了也不知道是哪一项起作用；变慢了也不知道该回滚谁。

### 1.3 四个核心资源

| 资源 | 典型瓶颈 | 观测 |
|---|---|---|
| CPU | JSON 序列化、GC、正则、加密、计算 | `process.cpu.usage`、JFR、火焰图 |
| 内存 | 堆、直接内存、线程栈、缓存 | 堆使用、GC、NMT、堆转储 |
| 线程 | Tomcat 线程、业务线程池、异步任务 | `tomcat.threads.busy`、executor 指标 |
| 连接 | Hikari、Redis、HTTP 客户端 | `hikaricp.connections.*`、pending、超时 |

### 1.4 性能指标

不要只看平均响应时间。平均值会掩盖长尾：

```text
P50 / P95 / P99 / P999
吞吐（QPS/TPS）
错误率
饱和度（CPU、堆、线程、连接池、队列）
```

用户感知的是 P99，不是平均值。

### 1.5 十个常见误解

| 误解 | 事实 |
|---|---|
| 加内存就能解决性能问题 | 可能是 GC、锁、DB、连接池、下游超时；加内存只掩盖症状 |
| Hikari 连接池越大越好 | 连接池过大反而增加 DB 上下文切换/锁竞争；要按 DB 容量和并发预算 |
| Tomcat 线程越多越好 | 线程多会增加上下文切换和内存；瓶颈可能在下游 |
| 缓存一定提升性能 | 缓存有命中率、TTL、失效、穿透、雪崩、一致性问题 |
| JPA 慢就换 JDBC | 很多慢是 N+1、缺索引、长事务、错误抓取策略，不一定是 JPA |
| 虚拟线程能解决所有并发问题 | 它只降低阻塞线程成本；DB 连接、下游容量、CPU 仍是瓶颈 |
| 压测不需要预热 | JIT、连接池、缓存、页面缓存都需要预热；冷启动数据没有代表性 |
| 平均响应时间够低就行 | 长尾可能已经让部分用户超时 |
| 启动快等于运行快 | 启动优化和运行时吞吐是两回事 |
| JMH 微基准等于系统性能 | 微基准测不到 DB、网络、GC、线程池和真实数据分布 |

---

## 二、性能模型

### 2.1 请求路径

```text
Client
  → DNS / LB / TLS
    → Tomcat Acceptor / Poller / Worker
      → Controller → Service
        → Cache / DB / Downstream
          → Serialization
            → Response
```

每一段都要问：

- 排队吗？（线程池、连接池、accept 队列、DB 锁）
- 超时吗？（连接、读取、下游、事务）
- 饱和吗？（CPU、内存、连接数）

### 2.2 Little's Law

```text
并发数 = 吞吐 × 延迟
```

例如：

```text
500 QPS × 100ms = 50 个并发请求
```

如果每个请求占用一个线程 100ms，理论上至少需要 50 个线程；还要加上突发、GC、下游抖动余量。

反过来：

```text
线程数固定 200，平均延迟 100ms → 理论吞吐约 2000 QPS
```

但这只是理论上限；一旦 DB 连接池只有 10，DB 就会成为新瓶颈。

### 2.3 CPU 密集型 vs IO 密集型

| 类型 | 线程数 | 优化方向 |
|---|---|---|
| CPU 密集 | 接近核心数 | 减少计算、序列化、GC、正则；用缓存/批处理 |
| IO 密集 | 核心数 ×（1 + 等待/计算） | 减少等待、连接池、异步、批量、下游优化 |
| 混合 | 按瓶颈分段 | 先测哪一段占主导 |

线程数不是越多越好：超过下游容量后，只会把压力转成排队和超时。

### 2.4 利用率与延迟

系统利用率接近 100% 时，排队延迟会急剧上升：

```text
利用率 50% → 延迟相对平稳
利用率 80% → 延迟开始上升
利用率 90%+ → 长尾迅速恶化
```

所以生产要留余量，不要按 100% 利用率设计。

### 2.5 观测

05-01 已经建立了指标、health、Prometheus、日志。性能排查优先看：

```text
http.server.requests（P95/P99、错误率）
jvm.memory.used / jvm.gc.pause
jvm.threads.live
tomcat.threads.busy / tomcat.connections.current
hikaricp.connections.active / pending / acquisition
executor.active / queued
process.cpu.usage / system.cpu.usage
```

---

## 三、JVM 与容器

### 3.1 内存构成

```text
容器内存 limit
├─ Java Heap
├─ Metaspace
├─ Thread Stacks（每线程约 1MB 虚拟地址，实际提交按需）
├─ Code Cache
├─ Direct Buffers（Netty、NIO）
├─ GC 自身结构
└─ 其他 native 内存
```

只给堆设上限不够：堆外内存、线程栈、Metaspace 也会占用容器内存。容器 limit 必须留出余量。

### 3.2 `MaxRAMPercentage`

JVM 在容器中会读取 cgroup 内存限制。默认堆比例通常远低于容器 limit；Boot 不替你设置，需要显式配置：

```bash
-XX:MaxRAMPercentage=75.0
```

为什么不是 100%？因为还要留给：

- Metaspace；
- 线程栈；
- 直接内存；
- Code Cache；
- JVM/GC 原生内存。

常见现象：

```text
容器 limit 1Gi，堆 900Mi，堆外 200Mi → OOMKilled
```

`OOMKilled` 是容器层面的，不一定是 Java `OutOfMemoryError`。看 `kubectl describe pod` 的退出码和事件。

### 3.3 GC

Java 17 默认 G1。关注：

```text
jvm.gc.pause（次数/总时间/最大停顿）
jvm.memory.used / max
分配速率
Humongous 对象
```

常见调优方向：

- 降低分配速率（少创建临时对象、少大对象）；
- 调整堆大小；
- 调整 `MaxGCPauseMillis`（G1 目标，不是保证）；
- 低延迟场景评估 ZGC/Shenandoah（JDK 版本/平台支持）；
- 不要盲目加 GC 线程，CPU 受限时反而更差。

GC 日志：

```bash
-Xlog:gc*:file=/var/log/app/gc.log:time,uptime,level,tags
```

或 JFR 采样。

### 3.4 CPU 与容器

容器 CPU limit 会影响：

- JVM 可见的 CPU 数（`ActiveProcessorCount`）；
- GC 线程数；
- ForkJoinPool common pool；
- JIT 编译线程；
- 应用线程调度。

如果 CPU limit 很低，JVM 可能仍然按宿主 CPU 数创建线程；可以用：

```bash
-XX:ActiveProcessorCount=2
```

但更好的做法是让 cgroup 和 JVM 自动感知，并给足 CPU requests。

CPU throttling 的表现：

- 延迟周期性抖动；
- GC 停顿变长；
- 吞吐上不去但 CPU 使用率看起来不高（被限流）。

Prometheus 可看：

```text
container_cpu_cfs_throttled_periods_total
container_cpu_cfs_throttled_seconds_total
```

### 3.5 堆外与 NMT

排查堆外内存：

```bash
-XX:NativeMemoryTracking=summary
jcmd <pid> VM.native_memory summary
```

关注：

- 直接内存（Netty、NIO、压缩）；
- 线程数（每线程栈）；
- Metaspace（动态代理/类加载）；
- Code Cache。

### 3.6 JIT 与预热

JVM 需要预热：

- 解释执行 → C1 → C2；
- 连接池、缓存、页面缓存需要预热；
- 压测/发布后不要立刻用前几秒数据下结论。

启动优化（05-03）和运行时吞吐是两件事：

- `-XX:TieredStopAtLevel=1` 可能加快启动，但牺牲峰值吞吐；
- CDS/AppCDS 改善启动；
- AOT/native 是另一条路线，有兼容性/生态成本。

### 3.7 常用 JVM 参数

```bash
-XX:MaxRAMPercentage=75.0
-XX:+UseG1GC
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/tmp
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*:file=/var/log/app/gc.log:time,uptime,level,tags
```

注意：

- `HeapDumpOnOutOfMemoryError` 需要磁盘空间；
- `ExitOnOutOfMemoryError` 让容器尽快重启，但要配合健康检查；
- `AlwaysPreTouch` 会延长启动、占用内存，按需使用；
- `UseStringDeduplication` 有 CPU 成本，收益取决于字符串重复度。

### 3.8 容器资源

```yaml
resources:
  requests:
    cpu: 500m
    memory: 512Mi
  limits:
    cpu: "1"
    memory: 1Gi
```

原则：

- requests 决定调度和基本保障；
- limits 决定上限；
- JVM 堆 + 堆外要小于 limit；
- 留 20%~30% 余量；
- 不要给过低 CPU limit，否则 GC/JIT/业务一起被限流。

---

## 四、Web 层

### 4.1 Tomcat 默认值

源码/元数据核对（Boot 3.3.9）：

| 属性 | 默认 |
|---|---:|
| `server.tomcat.threads.max` | 200 |
| `server.tomcat.threads.min-spare` | 10 |
| `server.tomcat.max-connections` | 8192 |
| `server.tomcat.accept-count` | 100 |
| `server.tomcat.max-keep-alive-requests` | 100 |
| `server.tomcat.max-swallow-size` | 2MB |
| `server.compression.enabled` | false |
| `server.compression.min-response-size` | 2KB |
| `server.http2.enabled` | false |
| `spring.mvc.servlet.load-on-startup` | -1（懒加载） |

理解：

- `max-connections` 是连接数上限；
- `threads.max` 是处理请求的 worker 线程上限；
- `accept-count` 是等待队列；
- 连接多不等于线程多，但线程是实际处理并发的瓶颈之一。

### 4.2 线程、连接与队列

```text
连接进来 → Acceptor → Poller → Worker 线程 → 业务
                              ↑
                        线程池满 → 排队/拒绝
```

如果下游很慢：

- Worker 线程被占满；
- 新请求排队；
- `accept-count` 满后连接被拒绝；
- 延迟和错误率一起上升。

调大 `threads.max` 只有在**下游还能承受**时才有意义；否则只是把更多请求压到 DB/下游。

### 4.3 异步 MVC、WebFlux、虚拟线程

| 方案 | 适合 | 注意 |
|---|---|---|
| 同步 MVC | 常规业务、阻塞 IO | 线程被阻塞时占用 worker |
| 异步 MVC（Callable/DeferredResult） | 单个慢调用、释放 worker | 仍然占用另一线程池；错误处理更复杂 |
| WebFlux | 高并发、非阻塞、流式 | 不能阻塞 event loop；生态/调试成本 |
| 虚拟线程（Java 21） | 大量阻塞 IO、代码改动小 | DB/下游连接仍是瓶颈；注意 pinning |

虚拟线程：

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

默认 `false`。需要 Java 21 运行时；本项目 Java 17 目标下不要开启。

虚拟线程不是万能的：

- `synchronized` 块、native 调用可能导致 pinning；
- ThreadLocal/MDC 需要传播；
- DB 连接池数量仍是硬上限；
- CPU 密集任务不会变快；
- 下游容量有限时，虚拟线程只会更快地把请求压过去。

### 4.4 压缩、HTTP/2、Keep-Alive

```yaml
server:
  compression:
    enabled: true
    min-response-size: 2KB
    mime-types: application/json,text/html,text/plain
  http2:
    enabled: true
```

权衡：

- 压缩省带宽，但耗 CPU；
- 小响应压缩收益低；
- 已经是压缩格式（图片/zip）不要再压；
- HTTP/2 多路复用可能减少连接数，但不一定降低后端压力；
- Keep-Alive 减少连接建立成本，但占用连接资源。

### 4.5 序列化与响应体

- DTO 而不是直接序列化实体；
- 避免把 lazy 代理/大对象图序列化；
- 分页，不要一次返回全量；
- 大响应考虑流式/分块；
- 只返回必要字段（投影）；
- 避免在响应里放巨大 `Map`/嵌套集合。

Jackson 常见性能点：

- 大量小对象序列化；
- 反射/注解扫描；
- 日期格式化；
- 自定义 `Serializer` 做重计算；
- 无限递归/循环引用。

### 4.6 静态资源与缓存

```yaml
spring:
  web:
    resources:
      cache:
        cachecontrol:
          max-age: 365d
          cache-public: true
      chain:
        strategy:
          content:
            enabled: true
```

- 静态资源用内容哈希文件名，长缓存；
- 动态 API 用 ETag/Cache-Control，按业务决定；
- CDN/反向代理分担静态流量；
- 不要给用户敏感数据加公共缓存。

### 4.7 超时

必须设置：

- Tomcat 连接超时；
- HTTP 客户端连接/读取超时；
- DB 连接池获取超时；
- 下游 RPC 超时；
- 异步请求超时（`spring.mvc.async.request-timeout`）。

没有超时的等待，会把线程/连接一直占住，最终拖垮整个应用。

---

## 五、线程池

### 5.1 Boot 默认 TaskExecutor 的危险默认值

Boot 3.3.9 的 `TaskExecutionProperties.Pool` 默认：

| 属性 | 默认 |
|---|---:|
| `core-size` | 8 |
| `max-size` | `Integer.MAX_VALUE` |
| `queue-capacity` | `Integer.MAX_VALUE` |
| `keep-alive` | 60s |
| `allow-core-thread-timeout` | true |
| `shutdown.await-termination` | false |

这意味着：

```text
默认只有 8 个核心线程在工作；
队列无界，永远不会触发扩容到 max-size；
任务堆积时内存持续增长，最终 OOM。
```

这是很多 `@Async` 默认配置在生产出问题的根源。

### 5.2 正确配置

```yaml
spring:
  task:
    execution:
      pool:
        core-size: 8
        max-size: 32
        queue-capacity: 200
        keep-alive: 60s
      thread-name-prefix: order-task-
      shutdown:
        await-termination: true
        await-termination-period: 30s
```

原则：

- 队列必须有界；
- `max-size` 要覆盖突发；
- 队列满后要有拒绝策略/降级；
- 不要用无界队列 + 无限 max 的「假弹性」；
- 线程名要有业务前缀，方便排障。

### 5.3 手工线程池

```java
@Bean
public ThreadPoolTaskExecutor orderExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(8);
    executor.setMaxPoolSize(32);
    executor.setQueueCapacity(200);
    executor.setKeepAliveSeconds(60);
    executor.setThreadNamePrefix("order-task-");
    executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
}
```

使用前需要在配置类上加 `@EnableAsync`；`@Async("orderExecutor")` 指定线程池，不写名称时使用容器中唯一的 `TaskExecutor`。

拒绝策略：

| 策略 | 行为 |
|---|---|
| `AbortPolicy` | 抛异常 |
| `CallerRunsPolicy` | 调用线程执行，形成反压 |
| `DiscardPolicy` | 静默丢弃 |
| `DiscardOldestPolicy` | 丢最旧任务 |

选择取决于业务：不能丢的任务不能用 Discard；不能阻塞调用方的不能用 CallerRuns。

### 5.4 定时任务

Boot 默认调度线程池：

```text
spring.task.scheduling.pool.size = 1
```

如果多个 `@Scheduled` 任务互相阻塞，会互相延迟。按需调大，但注意任务本身是否幂等/并发安全。

### 5.5 线程池指标

```text
executor.active
executor.queued
executor.pool.size
executor.completed
```

看：

- active 是否长期接近 max；
- queued 是否持续增长；
- 拒绝/超时是否出现；
- 任务执行时间分布。

---

## 六、连接池与数据库

### 6.1 Hikari 默认值

Boot 3.3.9 使用 HikariCP 5.1.0。Hikari 默认：

| 参数 | 默认 |
|---|---:|
| `maximum-pool-size` | 10 |
| `minimum-idle` | 与 maximum 相同（Hikari 内部 -1 表示跟随） |
| `connection-timeout` | 30s |
| `validation-timeout` | 5s |
| `idle-timeout` | 10min |
| `max-lifetime` | 30min |
| `leak-detection-threshold` | 0（关闭） |
| `keepalive-time` | 0（关闭） |

Boot 属性：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 10
      connection-timeout: 30s
      idle-timeout: 10m
      max-lifetime: 30m
      leak-detection-threshold: 30s
```

### 6.2 连接池大小

常见错误：

```text
线程池 200 → Hikari 也配 200
```

结果：

- DB 连接数爆掉；
- 上下文切换/锁竞争；
- 其他服务连不上；
- 延迟反而上升。

预算公式（粗略起点）：

```text
每实例连接数 × 实例数 + 管理连接 < 数据库最大连接数
```

例如 DB `max_connections=200`，10 个实例：

```text
每实例最多 ~15 个连接，留管理/运维余量
```

常见经验公式（PostgreSQL 社区广为引用，Hikari 文档也提到）：

```text
connections = (CPU 核数 × 2) + 有效磁盘数
```

这是起点，不是定律。更重要的是：

- SQL 是否快；
- 事务是否短；
- 是否有锁等待；
- 是否有慢查询占用连接。

### 6.3 Hikari 监控

```text
hikaricp.connections.active
hikaricp.connections.idle
hikaricp.connections.pending
hikaricp.connections.acquire
hikaricp.connections.creation
hikaricp.connections.timeout
```

关键信号：

- `pending` 长期 > 0：池太小或连接被长时间占用；
- `active` 长期接近 max：需要看 SQL/事务；
- `timeout` 增长：应用在等连接；
- `leak-detection-threshold` 适合非生产或短时排查（有开销）。

### 6.4 事务边界

坏模式：

```java
@Transactional
public void process() {
    orderRepository.save(order);
    remotePaymentClient.pay(order);   // 远程调用在事务里
    sendMessage(order);               // 消息在事务里
}
```

问题：

- 事务时间被远程调用拉长；
- 连接被长时间占用；
- 远程成功、DB 回滚 → 不一致；
- 消息发送失败/重复 → 需要补偿。

原则：

- 事务尽量短；
- 远程调用/消息发送放在事务外，或使用 outbox/最终一致性；
- 读操作考虑 `readOnly`；
- 避免在事务里做大循环/批处理。

### 6.5 JPA 常见性能问题

#### `open-in-view`

Boot 默认：

```yaml
spring.jpa.open-in-view: true
```

它让 EntityManager 在 Web 请求期间保持打开，容易导致：

- 视图层触发懒加载；
- 懒加载会额外获取连接/SQL，连接持有时间可能被拉长；
- 难以发现 N+1；
- 事务边界模糊。

生产建议：

```yaml
spring.jpa.open-in-view: false
```

然后在 Service/事务边界内完成数据加载，返回 DTO。

#### N+1

```java
List<Order> orders = orderRepository.findAll();
for (Order order : orders) {
    order.getItems().size();   // 每个订单一次查询
}
```

解决：

- `@EntityGraph` / `join fetch`；
- `@BatchSize` / `hibernate.default_batch_fetch_size`；
- DTO 投影/专用查询；
- 只查需要的字段。

#### 抓取策略

- `FetchType.EAGER` 容易放大查询；
- 默认 `LAZY` 不代表不会 N+1；
- 分页 + join fetch 可能产生内存分页问题；
- 复杂查询用 DTO 投影，不要强行实体图。

#### 批量写入

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
        batch_versioned_data: true
```

MySQL 还需要：

```text
rewriteBatchedStatements=true
```

注意：

- `saveAll` 不一定自动 batch，取决于 ID 生成策略/驱动；
- 大批量写入要分批 `flush`/`clear`，避免一级缓存膨胀；
- 批量更新/删除可以用 JPQL `@Modifying` 或 JDBC batch；
- 监控 SQL 数量，不要只看总耗时。

#### 分页

- `Page` 会执行 count 查询，确认是否必要；
- 深分页 `LIMIT 100000, 20` 会扫描大量行；
- 用 keyset/seek 分页（`where id > ? order by id limit ?`）；
- 索引要覆盖过滤/排序字段。

### 6.6 索引与 SQL

排查顺序：

```text
1. 慢查询日志
2. EXPLAIN / EXPLAIN ANALYZE
3. 是否走索引
4. 是否回表过多
5. 是否排序/临时表
6. 是否锁等待
```

常见问题：

- 索引列上做函数/类型转换；
- `LIKE '%x%'` 前导通配；
- `OR`/`!=`/`NOT IN` 导致索引失效；
- 复合索引顺序不对；
- `SELECT *`；
- 大表无分区/归档；
- 统计信息过期。

### 6.7 读写分离/缓存

- 读多写少可考虑只读副本，但注意复制延迟；
- 不要把强一致读路由到副本；
- 缓存与 DB 的一致性策略要明确；
- 连接池按数据源分别预算。

---

## 七、缓存

### 7.1 缓存层级

```text
浏览器/HTTP 缓存
  → CDN
    → 本地缓存（Caffeine）
      → 分布式缓存（Redis）
        → 数据库
```

越靠近用户，收益越大、失效越难。

### 7.2 Spring Cache + Caffeine

```yaml
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=5m
```

```java
@EnableCaching
@Configuration
class CacheConfig {
}
```

```java
@Cacheable(cacheNames = "orders", key = "#id")
public Order find(Long id) {
    return orderRepository.findById(id).orElseThrow();
}

@CacheEvict(cacheNames = "orders", key = "#order.id")
public void update(Order order) {
    ...
}
```

注意：

- 自调用不经过代理，缓存不生效；
- `@Cacheable(sync = true)` 可以防同一 key 的并发回源；
- 缓存 key 要稳定、可读；
- 空值/异常是否缓存要明确；
- 缓存与事务的顺序要清楚（事务提交后失效，还是提交前）。

### 7.3 缓存问题

| 问题 | 说明 | 对策 |
|---|---|---|
| 穿透 | 查询不存在的数据，每次都打 DB | 缓存空值/布隆过滤器 |
| 击穿 | 热点 key 过期瞬间大量回源 | 互斥锁/`sync=true`/逻辑过期 |
| 雪崩 | 大量 key 同时过期 | TTL 加随机抖动/多级缓存 |
| 热 key | 单 key 流量过高 | 本地缓存/分片 |
| 一致性 | DB 更新后缓存旧值 | 失效策略/延迟双删/版本号 |
| 大 key | 缓存巨大对象 | 拆分/只缓存必要字段 |

### 7.4 命中率与容量

- 监控命中率、回源 QPS、缓存大小；
- 不要缓存无限增长的数据；
- 给本地缓存设 `maximumSize`；
- Redis 注意内存/淘汰策略/网络延迟；
- 缓存不是数据库的替代品。

---

## 八、压测与性能回归

### 8.1 压测类型

| 类型 | 目的 |
|---|---|
| Smoke | 确认基本可用 |
| Baseline | 建立当前基线 |
| Load | 目标负载下是否满足 SLO |
| Stress | 超过预期负载时的退化行为 |
| Spike | 突发流量 |
| Soak | 长时间运行（内存泄漏/连接泄漏） |
| Breakpoint | 找到系统极限 |

### 8.2 工具

- JMeter：生态成熟，闭模型（线程数）；
- Gatling：DSL，支持开模型；
- k6：脚本化、开模型、CI 友好；
- wrk/ab：简单基准，不适合复杂场景。

开模型（固定到达率）更能暴露排队延迟；闭模型（固定并发）容易低估长尾。

### 8.3 k6 示例

```js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
};

export default function () {
  const res = http.get('http://localhost:8080/api/orders/1');
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(0.1);
}
```

### 8.4 压测纪律

- 预热：JVM、连接池、缓存、页面缓存；
- 阶梯加压，观察拐点；
- 稳定运行足够长时间（10~30 分钟）；
- 生产近似数据量/索引/网络；
- 监控服务端和压测客户端；
- 不要用笔记本 + 空库 + 单机压测下结论；
- 记录 P50/P95/P99/P999、吞吐、错误率、饱和度；
- 区分客户端瓶颈和服务端瓶颈。

### 8.5 性能分析工具

| 工具 | 用途 |
|---|---|
| JFR | CPU、GC、锁、分配、I/O，低开销 |
| async-profiler | CPU/分配/锁火焰图 |
| JProfiler/YourKit | 商业 profiler |
| `jstack` | 线程栈/死锁 |
| `jcmd` | GC、NMT、VM 信息 |
| 堆转储 + MAT | 内存泄漏/对象占用 |
| `EXPLAIN ANALYZE` | SQL 执行计划 |
| 慢查询日志 | DB 慢 SQL |

JFR：

```bash
java -XX:StartFlightRecording=filename=app.jfr,duration=60s,settings=profile \
     -jar app.jar
jfr print --events jdk.GarbageCollection,jdk.ExecutionSample app.jfr
```

async-profiler：

```bash
java -agentpath:/path/libasyncProfiler.so=start,event=cpu,file=profile.html \
     -jar app.jar
```

### 8.6 JMH

微基准必须用 JMH，不要手写 `System.nanoTime()` 循环：

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)
public String render(Blackhole bh) {
    String value = renderOrder();
    bh.consume(value);
    return value;
}
```

JMH 解决：

- 预热/JIT；
- 死代码消除；
- 循环优化；
- 统计噪声。

但 JMH 不能替代系统压测：DB、网络、GC、线程池、真实数据分布都测不到。

### 8.7 性能回归

- 建立基线（版本、环境、数据量、压测脚本）；
- 在 CI/专用环境跑关键场景；
- 设阈值（P99、吞吐、错误率、CPU）；
- 关注趋势，不只看单次；
- 变更一次只改一个变量；
- 失败可回滚；
- 保留压测报告和火焰图。

### 8.8 容量规划

```text
实例数 × 每实例线程/连接 <= 下游容量
```

例如：

```text
10 实例 × Hikari 10 = 100 DB 连接
DB max_connections = 200 → 留 100 给管理/其他服务
```

还要考虑：

- 故障时剩余实例能否承接流量；
- 自动扩缩容时 DB 连接预算；
- 下游限流/熔断；
- 峰值余量（30%~50%）。

---

## 九、常见性能反模式

| 反模式 | 后果 | 修正 |
|---|---|---|
| 无界队列 + 无限线程池 | 内存增长、OOM、延迟恶化 | 有界队列 + 明确拒绝策略 |
| Hikari 池按线程数配置 | DB 连接耗尽、锁竞争 | 按 DB 容量/事务时长预算 |
| `@Async` 用默认 executor | 8 线程 + 无界队列 | 自定义有界线程池 |
| N+1 查询 | SQL 数量爆炸 | EntityGraph/批量抓取/DTO |
| `open-in-view=true` | 懒加载在视图层、连接占用 | 关闭，事务内加载 DTO |
| 深分页 offset | 扫描大量行 | keyset 分页 |
| 缺索引/函数索引失效 | 全表扫描 | EXPLAIN + 索引 |
| 长事务里做远程调用 | 连接/锁长期占用 | 缩短事务/最终一致性 |
| 缓存无 TTL/无上限 | 内存膨胀/脏数据 | TTL + 容量 + 失效策略 |
| 缓存击穿/雪崩 | DB 瞬间被打爆 | 互斥/逻辑过期/TTL 抖动 |
| 热路径 DEBUG/字符串拼接 | CPU/IO/GC 压力 | 参数化日志/级别控制 |
| 序列化实体图 | 懒加载/大响应 | DTO/投影/分页 |
| 同步文件日志 | 业务线程被磁盘阻塞 | 异步/有界队列/stdout |
| 没有超时 | 线程/连接被永久占用 | 所有外部调用设超时 |
| 重试无上限/无退避 | 重试风暴 | 有界重试 + 退避 + 熔断 |
| 虚拟机线程 + synchronized | pinning | 避免长 synchronized/native |
| 微基准当系统性能 | 结论错误 | JMH + 系统压测 |

---

## 十、排查路径

### 10.1 高延迟

```text
P99 高、平均正常？
  → GC 停顿
  → 线程池排队
  → 连接池 pending
  → DB 锁/慢 SQL
  → 下游超时/重试
  → 缓存击穿/冷启动
  → 序列化/大响应
```

工具：

- `/actuator/metrics`、Prometheus；
- JFR/async-profiler；
- DB 慢查询/EXPLAIN；
- Hikari/线程池指标；
- 日志中的超时/重试。

### 10.2 吞吐上不去

- CPU 饱和？
- GC 占用高？
- 线程池/连接池饱和？
- DB 锁/慢 SQL？
- 下游容量/限流？
- 网络带宽/TLS？
- 客户端瓶颈？

### 10.3 错误率上升

- 连接池获取超时；
- 线程池拒绝；
- 下游超时；
- GC 停顿导致请求超时；
- OOM/容器 OOMKilled；
- 数据库连接数满。

### 10.4 CPU 高

- Profiler 看热点方法；
- JSON 序列化/正则/加密/日志；
- GC 线程；
- 线程上下文切换；
- 容器 CPU throttling。

### 10.5 内存高/OOM

- 堆转储 + MAT 找支配对象；
- 分配 profiling 找短命大对象；
- 缓存/集合无界增长；
- ThreadLocal 泄漏；
- 类加载器泄漏；
- 直接内存/线程栈/Metaspace。

### 10.6 数据库慢

- 慢查询日志；
- EXPLAIN ANALYZE；
- 索引/统计信息；
- N+1；
- 锁等待/长事务；
- 连接池；
- 大表归档/分区。

### 10.7 容器问题

- OOMKilled：limit 太小/堆外；
- CPU throttling：limit 太低；
- DNS/网络；
- 磁盘 I/O；
- 时间同步。

### 10.8 症状速查

| 症状 | 优先检查 |
|---|---|
| P99 高 | GC、线程池队列、DB 锁、下游 |
| QPS 上不去 | CPU、GC、线程/连接池、DB |
| 5xx 增多 | 超时、池耗尽、下游、OOM |
| CPU 高 | Profiler 热点、GC、序列化 |
| 内存高 | 堆转储、缓存、集合、ThreadLocal |
| DB 慢 | 慢 SQL、索引、锁、N+1 |
| 容器重启 | OOMKilled、liveness、CPU throttling |
| 启动慢 | 05-03（扫描/自动配置/外部连接） |

---

## 十一、源码与配置断点

| 想观察什么 | 位置 |
|---|---|
| Hikari 默认/连接获取 | `com.zaxxer.hikari.HikariConfig`、`HikariPool#getConnection` |
| Boot DataSource 配置 | `DataSourceConfiguration$Hikari`、`DataSourceProperties` |
| TaskExecutor 默认 | `TaskExecutionProperties$Pool`、`TaskExecutionAutoConfiguration` |
| 线程池构建 | `ThreadPoolTaskExecutorBuilder` |
| Tomcat 定制 | `TomcatWebServerFactoryCustomizer`、`TomcatServletWebServerFactory` |
| JPA open-in-view | `OpenEntityManagerInViewInterceptor`、`HibernateJpaAutoConfiguration` |
| 虚拟线程判断 | `Threading`（`Threading.VIRTUAL`） |
| 缓存自动配置 | `CacheAutoConfiguration`、`CaffeineCacheConfiguration` |
| HTTP 指标 | `WebMvcObservationAutoConfiguration`、`DefaultServerRequestObservationConvention` |
| Hikari 指标 | `DataSourcePoolMetricsAutoConfiguration` |

---

## 十二、必做实验

### 实验一：Hikari 池耗尽

1. 把 Hikari `maximum-pool-size` 设为 1；
2. 写一个慢 SQL（`select pg_sleep(2)`）；
3. 并发 10 个请求；
4. 观察 `hikaricp.connections.pending`、获取超时、P99。

### 实验二：线程池默认值

1. 用默认 `@Async` 线程池提交 100 个 1 秒任务；
2. 观察只有 8 个线程在工作、队列增长；
3. 改成有界队列 + maxSize，观察差异。

### 实验三：Tomcat 线程与连接

1. 写一个 `Thread.sleep(2s)` 的接口；
2. 用 k6/JMeter 逐步加压；
3. 观察 `tomcat.threads.busy`、`accept-count`、延迟/错误。

### 实验四：N+1

1. 准备 1 个订单 + N 个明细；
2. 用 `findAll()` 后循环访问明细；
3. 打开 SQL 日志数 SQL 条数；
4. 用 `@EntityGraph`/`@BatchSize`/DTO 投影修复，比较 SQL 数量和耗时。

### 实验五：批量写入

1. 逐条 `save` 10000 条；
2. 配置 `hibernate.jdbc.batch_size` + `order_inserts`；
3. 分批 `flush/clear`；
4. 比较耗时和 SQL 数量。

### 实验六：`open-in-view`

1. 保持默认 `true`，在 Controller 里访问 lazy 属性；
2. 改为 `false`，观察 `LazyInitializationException`；
3. 在 Service 事务内加载 DTO，修复。

### 实验七：缓存

1. 用 Caffeine 缓存一个查询；
2. 观察命中率/回源次数；
3. 模拟热点 key 过期，观察击穿；
4. 用 `sync=true` 或互斥锁修复。

### 实验八：JMH

写一个字符串/对象转换的 JMH 基准，比较两种实现；注意 warmup、fork、Blackhole。

### 实验九：系统压测

用 k6 固定到达率压一个真实接口，记录：

```text
QPS、P50/P95/P99、错误率、CPU、GC、线程池、Hikari
```

找出第一个饱和的资源。

### 实验十：Profiler

用 JFR 或 async-profiler 采一次高 CPU 场景，生成火焰图，定位热点方法。

### 实验十一：虚拟线程

在 Java 21 环境：

1. 开启 `spring.threads.virtual.enabled=true`；
2. 用阻塞 IO 接口压测；
3. 对比平台线程；
4. 观察 DB 连接池是否成为新瓶颈；
5. 用 synchronized 制造 pinning，观察差异。

### 实验十二：容器资源

1. 给容器设置较低的 CPU limit；
2. 压测观察 CPU throttling/延迟抖动；
3. 调整 `MaxRAMPercentage` 和内存 limit，观察 OOMKilled/GC。

---

## 十三、面试常问

### 13.1 性能优化的一般步骤？

先测量建立基线，用指标/Profiler 找瓶颈，一次只改一个变量，在相同环境复测，设回归阈值，准备回滚。不要凭感觉同时调所有参数。

### 13.2 线程池怎么设置大小？

CPU 密集型接近核心数；IO 密集型按等待/计算比例增加；用 Little's Law 估算并发，再用压测验证。队列必须有界，拒绝策略要符合业务。

### 13.3 Hikari 连接池怎么设置？

按 DB 最大连接数和实例数预算，不是按线程数。先保证 SQL 快、事务短，再调池大小；监控 `pending/active/timeout`。

### 13.4 `open-in-view` 有什么问题？

它让 EntityManager 在 Web 请求期间保持打开，容易在视图层触发懒加载、占用连接、掩盖 N+1、模糊事务边界。生产建议关闭，在事务内加载 DTO。

### 13.5 N+1 怎么发现和解决？

打开 SQL 日志/监控 SQL 数量；用 `@EntityGraph`、`join fetch`、`@BatchSize`、`default_batch_fetch_size` 或 DTO 投影解决。

### 13.6 批量写入怎么优化？

配置 Hibernate `jdbc.batch_size`、`order_inserts/updates`、`batch_versioned_data`；MySQL 加 `rewriteBatchedStatements=true`；分批 `flush/clear`，避免一级缓存过大。

### 13.7 缓存击穿/穿透/雪崩是什么？

- 击穿：热点 key 过期瞬间大量回源；
- 穿透：查询不存在的数据，每次都打 DB；
- 雪崩：大量 key 同时过期。

对策：互斥/逻辑过期、空值缓存/布隆过滤器、TTL 抖动/多级缓存。

### 13.8 虚拟线程能替代线程池吗？

它降低阻塞线程的成本，但不能替代下游容量/连接池/限流；CPU 密集任务不会变快；注意 pinning、ThreadLocal/MDC 传播和下游并发限制。

### 13.9 压测为什么要预热？

JIT、连接池、缓存、页面缓存、类加载都需要预热；冷启动数据不代表稳定态。压测要有阶梯加压和稳定运行阶段。

### 13.10 为什么平均值不够？

平均值掩盖长尾；用户感知的是 P95/P99；GC、锁、排队、下游抖动都会体现在长尾。

### 13.11 高 CPU 怎么排查？

Profiler（JFR/async-profiler）看热点；区分业务代码、序列化、正则、加密、日志、GC；看容器 CPU throttling。

### 13.12 内存泄漏怎么排查？

堆转储 + MAT 找支配对象；分配 profiling 找短命大对象；检查缓存/集合/ThreadLocal/类加载器；区分堆、直接内存、Metaspace、线程栈。

---

## 十四、本篇过关标准

学完本文后，需要能做到：

- 画出请求路径上的资源约束链，并用 USE/RED 定位瓶颈。
- 解释 Little's Law，估算线程/连接需求。
- 配置容器内存/CPU，理解 `MaxRAMPercentage`、OOMKilled、CPU throttling。
- 解释 GC/堆/堆外/线程栈的关系，使用 JFR/GC 日志/NMT。
- 说出 Tomcat 线程/连接/队列的默认值和调优边界。
- 说出 Boot TaskExecutor 的危险默认值，并能配置有界线程池。
- 说出 Hikari 默认值和连接池预算方法，监控 pending/active/timeout。
- 处理 `open-in-view`、N+1、批量写入、深分页、索引问题。
- 设计 Caffeine/Redis 缓存及 TTL/失效/击穿/雪崩策略。
- 设计压测场景，使用开模型、预热、稳定态和 P99。
- 用 JFR/async-profiler/EXPLAIN/堆转储排查性能问题。
- 设计容量规划、性能回归和回滚方案。

---

## 十五、下一篇建议

第 6 阶段到此完成。下一篇进入第 7 阶段 Spring Cloud 微服务基础：

```text
06-01-SpringCloud-注册发现.md
```

重点包括：

- 服务注册与发现的基本模型；
- Nacos/Eureka/Consul 的定位差异；
- 注册中心的高可用与一致性取舍；
- 服务实例健康检查与摘除；
- 从单体 Boot 应用拆出第一个微服务。
