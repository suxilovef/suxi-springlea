# Spring Boot 专题：测试体系、打包部署与发布治理源码深度解析

> 本文是第 6 阶段 Spring Boot 工程化与生产治理的第三篇，承接 `05-02-SpringBoot-日志与配置治理.md`。
>
> 05-01 解决「上线后看得见」，05-02 解决「日志和配置管得住」。本文继续回答从开发到上线的最后一段：
>
> 1. **测试**：`@SpringBootTest`、切片测试、MockMvc、Testcontainers 各自解决什么问题，怎么避免「测试很慢、还测不到重点」？
> 2. **打包**：可执行 jar 的内部结构是什么？分层 jar 为什么对 Docker 有意义？
> 3. **部署与发布**：镜像怎么构建，优雅停机怎么做到不丢请求，滚动/蓝绿/灰度发布和数据库迁移怎么配合？
> 4. **启动性能**：启动慢该从哪里量、从哪里改？
>
> 基准：Spring Boot `3.3.9` / JUnit Jupiter `5.10.5` / Mockito `5.11.0` / Testcontainers `1.19.8` / Java `17`。
>
> 本文的类名、默认值用 `javap` 反编译本地 Boot 3.3.9 相关 jar，并对照 jar 内配置元数据核对过。

---

## 一、先给结论

### 1.1 从测试到发布是一条链

```text
单元测试（无 Spring）
  → 切片测试（只加载一层）
    → 集成测试（@SpringBootTest / Testcontainers）
      → 打包（可执行 jar / 分层 jar）
        → 镜像（Dockerfile / Buildpacks）
          → 发布（滚动 / 蓝绿 / 灰度）
            → 停机（readiness + 优雅关闭）
              → 观测（health / metrics / logs）
```

每一层解决不同问题，不能互相替代：

- 单元测试快，但测不到 Spring 装配；
- 切片测试测得到一层，但测不到跨层；
- 集成测试最真实，但最慢、最贵；
- 打包/镜像/发布决定「测试通过的东西能不能安全上线」。

### 1.2 测试的金字塔与成本

| 层级 | 加载内容 | 速度 | 典型用途 |
|---|---|---|---|
| 单元测试 | 纯 Java 对象 | 毫秒 | 业务规则、边界、算法 |
| 切片测试 | 一层自动配置 | 百毫秒~秒 | Controller、Repository、JSON、HTTP Client |
| 集成测试 | 完整 `ApplicationContext` | 秒~十秒 | 跨层装配、真实依赖 |
| E2E | 真实进程/外部系统 | 十秒~分钟 | 发布前冒烟、关键链路 |

`@SpringBootTest` 不是「更高级的单元测试」。它启动完整上下文，代价高，应该用在**跨层装配或真实环境行为**上，而不是每个类都加。

### 1.3 部署的三件事

| 事情 | 关键问题 |
|---|---|
| 可执行 jar | 能不能独立运行、启动类是否正确、依赖是否打全 |
| 镜像 | 是否非 root、是否有 JVM 容器感知、是否可复现、是否最小化 |
| 优雅停机 | 发布/缩容时在途请求和异步任务能不能处理完 |

### 1.4 发布策略的底线

- 新实例必须通过 readiness 才接流量；
- 旧实例必须先停止接流量，再关闭；
- 数据库变更必须向后兼容，否则回滚会失败；
- 回滚不仅是回滚镜像，还要考虑数据库、配置、消息格式；
- 任何发布策略都要先在 staging 演练。

### 1.5 十个常见误解

| 误解 | 事实 |
|---|---|
| `@SpringBootTest` 越多越好 | 它启动完整上下文，成本高；大部分逻辑应该用单元/切片测试 |
| 切片测试等于单元测试 | 切片仍然启动 Spring 上下文，只是范围小 |
| `@MockBean` 不影响上下文缓存 | 它会改变缓存 key，可能产生大量上下文 |
| `@DataJpaTest` 一定用 H2 | 默认会替换数据源，但可以 `replace=NONE` + Testcontainers 用真实数据库 |
| Testcontainers 只能本地跑 | CI 只要能访问 Docker daemon 就能跑；否则要显式跳过或换方案 |
| 可执行 jar 就是普通 fat jar | Boot 使用嵌套 jar + `JarLauncher`，类加载方式不同 |
| 分层 jar 只是为了好看 | 它让 Docker 依赖层可缓存，应用层变化时不重传依赖 |
| `server.shutdown=graceful` 覆盖所有资源 | 它主要覆盖 Web 服务器；线程池、消费者、定时任务要自己处理 |
| 启动慢加内存就行 | 先测量：扫描、自动配置、Bean 初始化、外部连接都可能慢 |
| 回滚就是回滚镜像 | 数据库、配置、消息格式不兼容时，镜像回滚也会失败 |

---

## 二、测试体系

### 2.1 `@SpringBootTest` 的默认值

源码核对：

```java
public @interface SpringBootTest {
    String[] properties() default {};
    String[] args() default {};
    Class<?>[] classes() default {};
    WebEnvironment webEnvironment() default WebEnvironment.MOCK;
    UseMainMethod useMainMethod() default UseMainMethod.NEVER;
}
```

关键点：

| 属性 | 默认 | 说明 |
|---|---|---|
| `webEnvironment` | `MOCK` | 不启动真实服务器，配合 MockMvc |
| `useMainMethod` | `NEVER` | 不调用 `main`；可设 `ALWAYS`/`WHEN_AVAILABLE` |
| `classes` | 空 | 自动向上查找 `@SpringBootConfiguration` |
| `properties` | 空 | 测试属性，优先级高 |
| `args` | 空 | 传给 `SpringApplication` 的参数 |

`@SpringBootTest` 的启动链路：

```text
SpringBootTestContextBootstrapper
  → SpringBootContextLoader
    → 查找 @SpringBootConfiguration
      → 创建 SpringApplication
        → 应用 EnvironmentPostProcessor / ConfigData
        → 加载完整 ApplicationContext
```

测试类通常应放在：

```text
src/test/java/com/example/order/OrderServiceTest.java
```

与主应用类同包或子包，才能自动找到 `@SpringBootConfiguration`。不同包时用：

```java
@SpringBootTest(classes = Application.class)
```

### 2.2 四种 `webEnvironment`

| 模式 | 是否启动服务器 | 典型搭配 |
|---|---|---|
| `MOCK` | 否 | `@AutoConfigureMockMvc` + `MockMvc` |
| `RANDOM_PORT` | 是，随机端口 | `TestRestTemplate` / `WebTestClient.bindToServer` |
| `DEFINED_PORT` | 是，使用 `server.port` | 本地调试、固定端口场景 |
| `NONE` | 否 | 非 Web 应用、纯服务层集成 |

示例：

```java
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void getOrder() throws Exception {
        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }
}
```

随机端口：

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void getOrder() {
        ResponseEntity<Order> response =
                restTemplate.getForEntity("/api/orders/1", Order.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
```

### 2.3 测试上下文缓存

Spring Test 会缓存 `ApplicationContext`，避免每个测试类都重新启动。缓存 key 包括：

- 配置类/`classes`；
- active profiles；
- property sources；
- context initializers；
- `@MockBean`/`@SpyBean` 定义；
- 其他影响上下文的定制器。

因此：

- 不同 `@SpringBootTest(properties=...)` 会生成不同上下文；
- `@MockBean` 不同组合会生成不同上下文；
- `@DirtiesContext` 会显式丢弃上下文；
- `@ActiveProfiles` 不同会生成不同上下文。

上下文越多，测试越慢。优化原则：

1. 尽量复用同一套测试配置；
2. 把 `@MockBean` 收敛到切片测试；
3. 不要在测试类上随意加不同 properties；
4. `@DirtiesContext` 只在必须清理不可恢复状态时使用；
5. 用测试报告观察上下文数量。

### 2.4 测试配置

```java
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.feature.enabled=true")
class OrderServiceTest {
}
```

测试资源：

```text
src/test/resources/
├─ application.yml
├─ application-test.yml
└─ sql/
   └─ orders.sql
```

优先级（简化）：

```text
@SpringBootTest(properties)
@DynamicPropertySource
@TestPropertySource
测试 profile 文件
测试 application.yml
主 profile / 主 application.yml
```

具体装载顺序见 04-04；测试属性的关键点是「测试 classpath 资源会覆盖主资源」。

`@TestConfiguration` 用于测试专用 Bean：

```java
@TestConfiguration(proxyBeanMethods = false)
class TestBeans {

    @Bean
    Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
```

```java
@SpringBootTest
@Import(TestBeans.class)
class OrderServiceTest {
}
```

`@TestConfiguration` 不会被主组件扫描误加载，适合替换时钟、外部客户端、测试数据构造器。

### 2.5 测试事务与数据

- `@DataJpaTest` 默认带 `@Transactional`，每个测试方法回滚；
- `@JdbcTest` 同理；
- `@SpringBootTest` 默认不带事务；需要时自己加 `@Transactional`，但要理解边界。

一个关键坑：

> `@SpringBootTest(webEnvironment = RANDOM_PORT)` 上的 `@Transactional` 不会传播到服务器线程。

测试方法的事务在测试线程，HTTP 请求在服务器线程，数据未提交时服务器看不到。需要：

- 用 `RANDOM_PORT` 时不依赖测试事务，显式准备/清理数据；
- 或用 `@Commit` 提交；
- 或用 Testcontainers + 测试数据脚本；
- 或退回 `MOCK` + MockMvc（同线程）。

`@Sql` 适合准备/清理：

```java
@Sql(scripts = "/sql/orders.sql")
@Test
void findOrders() {
}
```

### 2.6 Testcontainers

依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

`@ServiceConnection` 方式：

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    OrderRepository repository;

    @Test
    void saveAndFind() {
        repository.save(new Order(...));
        assertThat(repository.findById(...)).isPresent();
    }
}
```

`@DynamicPropertySource` 方式（适用于不支持 `@ServiceConnection` 的容器）：

```java
@Testcontainers
@SpringBootTest
class OrderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

要点：

- `@Container` 通常是 `static`，一个测试类共用一个容器；
- `@Testcontainers` 负责启动/停止；
- `@ServiceConnection` 自动生成连接信息，优先使用；
- `@Testcontainers(disabledWithoutDocker = true)` 可在没有 Docker 的环境跳过；
- 容器启动慢，尽量复用上下文/容器；
- `withReuse(true)` 能加速本地，但会影响测试隔离，CI 慎用；
- CI 需要 Docker daemon 或 Testcontainers Cloud。

### 2.7 测试策略

| 目标 | 推荐测试 |
|---|---|
| 业务规则、算法、边界 | 纯单元测试 |
| Controller 参数/返回/异常 | `@WebMvcTest` + MockMvc |
| Repository/JPQL/映射 | `@DataJpaTest` + 真实数据库（Testcontainers） |
| JSON 序列化契约 | `@JsonTest` |
| 外部 HTTP 客户端 | `@RestClientTest` |
| 跨层装配 | `@SpringBootTest` |
| 真实依赖（DB/MQ/Redis） | Testcontainers + `@SpringBootTest` |
| 发布前关键链路 | E2E/冒烟 |

不要用 `@SpringBootTest` 覆盖所有 Service 单元测试；也不要用 mock 掉所有依赖的「集成测试」冒充真实集成。

---

## 三、切片测试

### 3.1 `@WebMvcTest`

只加载 Web MVC 相关：

- `@Controller` / `@RestController`；
- `@ControllerAdvice`；
- `@JsonComponent`；
- `Converter` / `GenericConverter`；
- `Filter` / `HandlerInterceptor`；
- `WebMvcConfigurer`；
- `HandlerMethodArgumentResolver`。

不加载：

- `@Component` / `@Service` / `@Repository`；
- 大多数自动配置。

示例：

```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    OrderService orderService;

    @Test
    void getOrder() throws Exception {
        given(orderService.find(1L)).willReturn(new Order(1L, "PAID"));

        mockMvc.perform(get("/api/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }
}
```

安全相关：

- 引入 Spring Security 后，`@WebMvcTest` 默认会应用安全过滤器；
- 需要认证时用 `@WithMockUser`（`spring-security-test`）；
- 只测 MVC、不测安全时可用 `@AutoConfigureMockMvc(addFilters = false)`，但不要因此漏测安全。

### 3.2 `@DataJpaTest`

默认行为：

- 只加载 JPA/Repository 相关；
- 默认替换 DataSource 为嵌入式数据库（如果 classpath 有 H2 等）；
- 默认 `@Transactional`，测试方法回滚；
- 提供 `TestEntityManager`。

使用真实数据库：

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");
}
```

注意：

- H2 与 PostgreSQL/MySQL 的 SQL 方言、函数、锁行为不同，关键查询要在真实数据库上测；
- `@DataJpaTest` 不加载 `@Service`，业务逻辑要另测；
- 默认回滚适合大多数测试，但验证事务提交/隔离级别时要特殊处理。

### 3.3 `@JdbcTest`

适合只测 `JdbcTemplate` / SQL：

```java
@JdbcTest
class OrderJdbcTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void countOrders() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from orders", Integer.class);
        assertThat(count).isNotNull();
    }
}
```

同样默认使用嵌入式数据库，可用 `@AutoConfigureTestDatabase(replace = NONE)` 换真实库。

### 3.4 `@JsonTest`

只测 JSON 序列化/反序列化：

```java
@JsonTest
class OrderJsonTest {

    @Autowired
    JacksonTester<Order> json;

    @Test
    void serialize() throws Exception {
        Order order = new Order(1L, "PAID");

        assertThat(json.write(order))
                .hasJsonPathNumberValue("$.id", 1)
                .hasJsonPathStringValue("$.status", "PAID");
    }
}
```

适合验证：

- 字段名/日期格式；
- 枚举序列化；
- 空值策略；
- 自定义 `Serializer`/`Deserializer`。

### 3.5 `@RestClientTest`

测外部 HTTP 客户端，不启动真实服务：

```java
@RestClientTest(InventoryClient.class)
class InventoryClientTest {

    @Autowired
    InventoryClient client;

    @Autowired
    MockRestServiceServer server;

    @Test
    void getStock() {
        server.expect(requestTo("/stock/1"))
                .andRespond(withSuccess("{\"stock\":10}", MediaType.APPLICATION_JSON));

        Stock stock = client.getStock(1L);

        assertThat(stock.getStock()).isEqualTo(10);
    }
}
```

适合验证：

- URL/方法/请求头；
- 超时/重试配置；
- 错误响应处理；
- JSON 映射。

### 3.6 `@WebFluxTest`

WebFlux 控制器切片：

```java
@WebFluxTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    OrderService orderService;

    @Test
    void getOrder() {
        given(orderService.find(1L)).willReturn(Mono.just(new Order(1L, "PAID")));

        webTestClient.get().uri("/api/orders/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PAID");
    }
}
```

### 3.7 其他切片

| 注解 | 范围 |
|---|---|
| `@JdbcTest` | JdbcTemplate / DataSource |
| `@DataJpaTest` | JPA / Repository |
| `@DataJdbcTest` | Spring Data JDBC |
| `@DataR2dbcTest` | R2DBC |
| `@DataRedisTest` | Spring Data Redis |
| `@DataMongoTest` | Spring Data MongoDB |
| `@DataNeo4jTest` | Spring Data Neo4j |
| `@DataCassandraTest` | Cassandra |
| `@DataElasticsearchTest` | Elasticsearch |
| `@JooqTest` | jOOQ |
| `@GraphQlTest` | GraphQL |
| `@WebServiceServerTest` | SOAP 服务端 |
| `@WebServiceClientTest` | SOAP 客户端 |

切片不是越多越好；选与本次改动最相关的一层。

### 3.8 `@MockBean`、`@SpyBean` 与版本边界

Boot 3.3 使用：

```java
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
```

版本边界：

- Boot 3.3 仍可用 `@MockBean`/`@SpyBean`；
- Boot 3.4 起它们被标记为 deprecated，推荐 Spring Framework 6.2 的 `@MockitoBean`/`@MockitoSpyBean`；
- 升级时不要直接全局替换，先确认 Spring Framework 版本和测试依赖。

使用原则：

- 切片测试里 mock 依赖是合理的；
- 集成测试里不要把所有真实依赖都 mock 掉，否则测的是 mock 的行为；
- `@MockBean` 会改变上下文缓存，滥用会拖慢测试；
- 默认每个测试方法后重置 mock（`MockReset.AFTER`），需要保留时显式配置。

### 3.9 切片常见坑

| 现象 | 原因 |
|---|---|
| 404 | 控制器没被切片包含；`@WebMvcTest(OrderController.class)` 写错 |
| 401/403 | Spring Security 过滤器生效；需要 `@WithMockUser` 或测试安全配置 |
| `NoSuchBeanDefinitionException` | 切片不加载 `@Service`；需要 `@MockBean` 或 `@Import` |
| JSON 字段不符 | 切片加载的 `ObjectMapper` 与主应用不同；检查 `@JsonComponent`/配置 |
| 数据库方言问题 | `@DataJpaTest` 默认嵌入式库；换 Testcontainers |
| 测试很慢 | 上下文 key 变化过多；复用配置/减少 `@MockBean` 组合 |

---

## 四、测试质量与 CI

### 4.1 测试矩阵

| 改动类型 | 必测 |
|---|---|
| 纯业务逻辑 | 单元测试 |
| Controller 接口 | `@WebMvcTest` + MockMvc |
| Repository/查询 | `@DataJpaTest` + 真实库 |
| JSON 契约 | `@JsonTest` |
| 外部客户端 | `@RestClientTest` |
| 跨层装配 | 少量 `@SpringBootTest` |
| 发布前 | 关键链路 E2E/冒烟 |

### 4.2 测试结构

推荐命名：

```text
OrderServiceTest             纯单元
OrderControllerTest          @WebMvcTest
OrderRepositoryTest          @DataJpaTest
OrderApiIntegrationTest      @SpringBootTest + RANDOM_PORT
```

结构建议：

- given / when / then；
- 一个测试只验证一个行为；
- 测试数据用 builder/factory，不要复制大段构造；
- 不要依赖测试执行顺序；
- 不要共享可变静态状态；
- 断言具体值，不要只断言 `isNotNull()`。

### 4.3 不稳定测试

常见原因：

- 依赖真实时间 → 注入 `Clock`；
- 依赖异步完成 → Awaitility 等待；
- 依赖固定端口 → `RANDOM_PORT`；
- 依赖外部服务 → Testcontainers/MockServer；
- 测试顺序/共享状态 → 独立数据；
- 容器启动慢 → 复用/健康等待；
- 时区/locale → 固定 UTC/`Locale.ROOT`。

### 4.4 覆盖率

覆盖率是参考，不是目标。更重要的是：

- 关键业务路径有测试；
- 边界/异常路径有测试；
- 配置/安全/事务有集成测试；
- 发布前关键链路有冒烟测试。

### 4.5 CI 建议

```bash
./mvnw -q verify
```

- 单元/切片测试先跑，失败快速反馈；
- 集成/Testcontainers 测试后跑；
- 测试报告和失败日志归档；
- 固定 Java/JVM/依赖版本，保证可复现；
- Docker 环境准备（Testcontainers）；
- 不在 CI 里用 `-DskipTests` 发布。

### 4.6 测试反模式

- 所有测试都 `@SpringBootTest`；
- 所有测试都 `@DirtiesContext`；
- 把 mock 当集成测试；
- 测试私有方法/实现细节；
- 断言日志文本作为业务契约；
- 依赖测试执行顺序；
- 用 `Thread.sleep` 等待异步；
- 共享数据库状态不清理。

---

## 五、可执行 jar

### 5.1 `spring-boot-maven-plugin`

项目里通常有：

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
```

`mvn package` 会执行 `repackage`：

```text
target/
├─ app-0.0.1-SNAPSHOT.jar          可执行 jar
└─ app-0.0.1-SNAPSHOT.jar.original 原始 jar
```

运行：

```bash
java -jar target/app-0.0.1-SNAPSHOT.jar
```

如果存在多个 main 类，显式配置：

```xml
<configuration>
    <mainClass>com.example.Application</mainClass>
</configuration>
```

### 5.2 jar 内部结构

```text
app.jar
├─ META-INF/
│  └─ MANIFEST.MF
├─ BOOT-INF/
│  ├─ classes/          应用类
│  └─ lib/              依赖 jar
└─ org/springframework/boot/loader/...
```

Manifest 关键项：

```text
Main-Class: org.springframework.boot.loader.launch.JarLauncher
Start-Class: com.example.Application
Spring-Boot-Version: 3.3.9
Spring-Boot-Classes: BOOT-INF/classes/
Spring-Boot-Lib: BOOT-INF/lib/
Spring-Boot-Classpath-Index: BOOT-INF/classpath.idx
```

源码核对：Boot 3.3 的 `Layouts$Jar#getLauncherClassName()` 返回：

```text
org.springframework.boot.loader.launch.JarLauncher
```

所以：

- `Main-Class` 不是你的应用类，而是 `JarLauncher`；
- 你的应用类在 `Start-Class`；
- 依赖以嵌套 jar 形式存在，由 Boot 的类加载器读取；
- 不要用 `java -cp` 去跑这个 jar。

### 5.3 分层 jar

Boot 3.3 的 `Layers` 默认：

```java
private boolean enabled = true;
private boolean includeLayerTools = true;
```

标准分层（源码 `StandardLayers`）：

```text
dependencies            普通依赖
spring-boot-loader      Boot loader
snapshot-dependencies   SNAPSHOT 依赖
application             应用类和资源
```

jar 内会有 `layers.idx`，描述每个层包含哪些内容。分层本身不改变运行结果，目的是 Docker 构建时**依赖层可复用**：

```text
依赖层变化少 → Docker 缓存命中 → 只重建 application 层
```

配置：

```xml
<configuration>
    <layers>
        <enabled>true</enabled>
        <includeLayerTools>true</includeLayerTools>
    </layers>
</configuration>
```

关闭分层：

```xml
<layers>
    <enabled>false</enabled>
</layers>
```

`includeLayerTools=true`（3.3 默认）会把 jar tools 一并打入，便于 `tools` jarmode 使用；设为 `false` 时相应能力不可用。

### 5.4 提取分层

经典写法：

```bash
java -Djarmode=layertools -jar app.jar extract
```

Boot 3.3 也提供 `tools` jarmode：

```bash
java -Djarmode=tools -jar app.jar extract
```

提取后会得到按层组织的目录，Dockerfile 可以逐层 COPY，从而利用镜像层缓存。具体输出目录以你本地 Boot 版本的 `jarmode` 输出为准，不要盲目复制别处教程的路径。

### 5.5 构建可复现

- 固定 Java/依赖版本；
- 配置 `project.build.outputTimestamp`；
- 发布构建不要 `-DskipTests`；
- 检查 jar 内容（`jar tf`）；
- 校验 `Start-Class`、依赖完整性；
- 保留 SBOM/构建信息（build-info）。

---

## 六、Docker 镜像

### 6.1 Buildpacks

Boot Maven 插件支持直接构建 OCI 镜像：

```bash
./mvnw spring-boot:build-image \
  -Dspring-boot.build-image.imageName=order-service:1.0.0
```

特点：

- 不需要写 Dockerfile；
- 使用 Paketo Buildpacks；
- 自动分层、JVM 容器感知；
- 需要本机 Docker daemon；
- 可通过 `image.builder`、`image.env` 定制。

运行：

```bash
docker run --rm -p 8080:8080 order-service:1.0.0
```

### 6.2 多阶段 Dockerfile

```dockerfile
# 构建阶段
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -DskipTests package

# 运行阶段
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 appuser
COPY --from=build /workspace/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

要点：

- 运行阶段用 JRE，不用 JDK；
- 非 root 用户；
- `ENTRYPOINT` 用 exec 形式，确保 `SIGTERM` 能传给 JVM；
- `MaxRAMPercentage` 让 JVM 感知容器内存限制；
- `.dockerignore` 排除 `target/`、`.git/`、IDE 文件；
- 不把密钥/配置文件打进镜像。

### 6.3 分层 Dockerfile

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -DskipTests package \
 && java -Djarmode=layertools -jar target/*.jar extract

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 appuser

COPY --from=build /workspace/dependencies/ ./
COPY --from=build /workspace/spring-boot-loader/ ./
COPY --from=build /workspace/snapshot-dependencies/ ./
COPY --from=build /workspace/application/ ./

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", \
            "org.springframework.boot.loader.launch.JarLauncher"]
```

收益：

- 依赖层变化少，构建缓存命中；
- 只改业务代码时，不重复拉取依赖层；
- 镜像层更清晰，便于排查。

注意：

- 提取目录和入口类必须与 Boot 版本匹配；
- `JarLauncher` 包名在 Boot 3.2+ 是 `org.springframework.boot.loader.launch`；
- 使用 `tools` jarmode 时输出目录可能不同，按本地版本确认。

### 6.4 容器最佳实践

| 项 | 建议 |
|---|---|
| 用户 | 非 root |
| 文件系统 | 只读根文件系统；需要写的地方用 tmpfs/volume |
| JVM | `-XX:MaxRAMPercentage=75`、`-XX:+UseG1GC` |
| 信号 | `STOPSIGNAL SIGTERM`；exec 形式 ENTRYPOINT |
| 时区 | 固定 UTC；`-Duser.timezone=UTC` |
| 编码 | UTF-8 |
| 密钥 | 环境变量/Secret，不进镜像 |
| 镜像 | 最小基础镜像、固定 tag/digest、多架构按需 |
| 健康 | 用 readiness/liveness，不依赖容器内 `curl` 脚本 |
| 日志 | stdout，交给平台采集 |

### 6.5 容器环境变量

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_APPLICATION_JSON='{"server":{"port":8080}}' \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=75 -Duser.timezone=UTC' \
  order-service:1.0.0
```

注意：

- 环境变量有长度/字符限制，复杂 JSON 小心转义；
- 不要把密钥写进 `docker run` 历史/Compose 文件；
- `JAVA_TOOL_OPTIONS` 会被 JVM 自动读取，适合容器统一参数；
- 环境变量 key 的松散绑定规则见 04-04。

### 6.6 Kubernetes 配置

```yaml
spec:
  terminationGracePeriodSeconds: 40
  containers:
    - name: app
      image: order-service:1.0.0
      ports:
        - containerPort: 8080
      lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "sleep 5"]
      readinessProbe:
        httpGet:
          path: /actuator/health/readiness
          port: 8080
        periodSeconds: 5
      livenessProbe:
        httpGet:
          path: /actuator/health/liveness
          port: 8080
        periodSeconds: 10
      startupProbe:
        httpGet:
          path: /actuator/health/liveness
          port: 8080
        failureThreshold: 30
        periodSeconds: 5
      resources:
        requests:
          cpu: 500m
          memory: 512Mi
        limits:
          cpu: "1"
          memory: 1Gi
```

关键：

- `terminationGracePeriodSeconds` 要大于 `preStop` 时间 + 应用优雅停机超时；
- `preStop` 等待可以给 Endpoints 更新留时间；
- readiness 失败会摘流量，liveness 失败会重启；
- startupProbe 保护慢启动；
- 资源 limit 要让 JVM 正确计算堆（`MaxRAMPercentage`）。

---

## 七、优雅停机

### 7.1 停机的真实顺序

Kubernetes 删除 Pod 时大致是：

```text
1. Pod 进入 Terminating
2. 从 Service Endpoints 移除（异步）
3. 执行 preStop
4. 发送 SIGTERM
5. 等待进程退出
6. 超过 terminationGracePeriodSeconds → SIGKILL
```

问题在于：

- Endpoints 更新和 SIGTERM 可能几乎同时发生；
- 负载均衡器可能还有短暂流量；
- 应用内还有在途请求、异步任务、消息消费。

所以需要：

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

Boot 的 Web 容器会：

- 停止接收新请求；
- 等待在途请求完成；
- 超时后强制关闭。

05-01 已经讲过 readiness 在 `ServletWebServerApplicationContext.doClose()` 时会发布 `REFUSING_TRAFFIC`；配合 K8s readiness，可以把「摘流量」放在「关服务器」之前。

### 7.2 `server.shutdown=graceful` 的边界

它主要覆盖嵌入式 Web 服务器：

- Tomcat/Jetty/Undertow/Netty 的请求处理；
- Web 容器的线程池关闭。

它**不自动覆盖**：

- `@Async` 线程池；
- 自定义 `ThreadPoolExecutor`；
- `TaskScheduler`；
- Kafka/RabbitMQ 消费者；
- 定时任务；
- 缓存刷新线程；
- 外部客户端连接池。

这些要自己实现 `SmartLifecycle`、`DisposableBean`、`@PreDestroy`，或在配置中设置等待策略。

### 7.3 线程池优雅关闭

```java
@Bean
public ThreadPoolTaskExecutor orderExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(200);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);
    executor.initialize();
    return executor;
}
```

对于消息消费者：

- 先停止拉取/消费；
- 等待当前消息处理完成；
- 提交 offset；
- 再关闭连接。

顺序错误会导致「消息已消费但未提交」或「提交了但业务未完成」。

### 7.4 测试优雅停机

```java
ConfigurableApplicationContext context = SpringApplication.run(Application.class);
String result = callSlowEndpoint();

SpringApplication.exit(context, () -> 0);
assertThat(result).isEqualTo("done");
```

或用 Awaitility 等异步任务完成。测试重点：

- 在途请求完成；
- readiness 先行变为 REFUSING；
- 超时后强制退出；
- 不产生重复消费。

### 7.5 常见坑

| 现象 | 原因 |
|---|---|
| 发布时 502 | readiness 未先失败；preStop 太短；旧实例来不及处理 |
| 请求被截断 | 没有 graceful；超时太短；客户端超时更短 |
| 消息重复 | 消费后未提交 offset 就退出；或提交后业务失败 |
| 线程池任务丢失 | 没有 `waitForTasksToCompleteOnShutdown` |
| 进程立即退出 | SIGKILL；`System.exit`；关闭钩子未注册 |
| 关闭很慢 | 长请求/阻塞任务；timeout 过长；外部连接不释放 |

---

## 八、发布策略

### 8.1 滚动发布

Kubernetes 默认策略：

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

配合：

- readiness/startupProbe；
- `terminationGracePeriodSeconds`；
- PodDisruptionBudget；
- 资源 requests/limits。

顺序：

```text
新 Pod 启动 → startupProbe 通过 → readiness 通过 → 接流量
旧 Pod 从 Endpoints 移除 → graceful shutdown → 退出
```

### 8.2 蓝绿发布

```text
Blue 环境（当前版本） ← 全部流量
Green 环境（新版本）  ← 预验证
切换流量 → Blue 保留一段回滚窗口
```

要求：

- 两套环境共享/兼容数据库；
- 配置和密钥版本化；
- 预热/健康检查；
- 回滚一键切回。

### 8.3 灰度发布

按比例/请求头/用户分流：

```text
5% → 25% → 50% → 100%
```

观察：

- 错误率；
- P99 延迟；
- 关键业务指标；
- 日志异常；
- 资源使用。

灰度与配置中心/网关结合，细节见 07-05。

### 8.4 数据库迁移

发布与数据库变更必须解耦：

```text
扩展（Expand）
  → 加新列/新表，旧代码兼容
迁移（Migrate）
  → 双写/回填/校验
收缩（Contract）
  → 删除旧列/旧逻辑
```

原则：

- 不在同一发布里做破坏性变更；
- 旧版本代码必须能兼容新 schema（为了回滚）；
- 迁移脚本要可重复、可回滚（或 forward-fix）；
- 大表迁移分批、限流、观察锁等待；
- Flyway/Liquibase 迁移在启动时执行时注意锁和超时。

### 8.5 配置与密钥发布

- 配置版本化，变更可审计；
- 密钥轮换与发布解耦；
- 不要一次重启全部实例；
- 动态配置刷新前评估影响（05-02）；
- 特性开关用于快速关闭出问题的功能；
- 配置回滚与应用回滚分开设计。

### 8.6 回滚

回滚不是单一动作：

```text
镜像/版本回滚
+ 配置回滚
+ 数据库兼容/forward-fix
+ 消息格式兼容
+ 缓存/状态清理
```

发布前必须验证：

- 旧版本能否读新 schema；
- 新版本能否读旧 schema；
- 回滚后队列/消息是否可消费；
- 配置回滚后应用能否启动。

### 8.7 发布检查清单

- [ ] 单元/切片/集成测试通过；
- [ ] 关键链路 E2E/冒烟通过；
- [ ] 配置校验通过，密钥已注入；
- [ ] 数据库迁移向后兼容；
- [ ] readiness/liveness/startupProbe 正确；
- [ ] 优雅停机超时与 K8s grace period 匹配；
- [ ] 资源 requests/limits 合理；
- [ ] 日志/指标/告警已就绪；
- [ ] 灰度/回滚步骤明确；
- [ ] 发布后观察窗口和负责人明确。

---

## 九、启动性能

### 9.1 先测量

05-01 已经讲过：

```java
SpringApplication app = new SpringApplication(Application.class);
app.setApplicationStartup(new BufferingApplicationStartup(2048));
app.run(args);
```

配合：

- `/actuator/startup` 查看启动步骤；
- `application.started.time`、`application.ready.time` 指标；
- JFR：

  ```bash
  java -XX:StartFlightRecording=filename=startup.jfr,duration=30s -jar app.jar
  ```

- 启动日志时间戳；
- `-Xlog:class+load=info` 看类加载。

不要凭感觉优化。

### 9.2 常见启动慢的原因

| 原因 | 表现 |
|---|---|
| 组件扫描范围过大 | 大量类被扫描/加载 |
| 自动配置过多 | 条件评估、Bean 创建多 |
| Bean 初始化慢 | `@PostConstruct`、构造器里做 I/O |
| 外部连接慢 | DB/Redis/MQ 连接超时/重试 |
| JPA 初始化 | `ddl-auto`、实体扫描、Repository 初始化 |
| 定时任务/监听器启动 | 启动时立即执行 |
| 类路径过大 | 依赖多、jar 多 |
| JVM/容器冷启动 | JIT、CDS 未优化、CPU 限额低 |

### 9.3 优化手段

- 缩小 `@ComponentScan` 范围；
- 排除不需要的自动配置（`spring.autoconfigure.exclude`）；
- 用 `/actuator/conditions` 确认自动配置是否生效；
- 延迟初始化：`spring.main.lazy-initialization=true`（谨慎，可能延迟失败）；
- `@Lazy` 定向延迟重 Bean；
- 连接池设置合理超时，避免启动阻塞；
- JPA `ddl-auto=validate`（生产不要 `update`）；
- 避免在 `@PostConstruct` 做远程调用；
- 使用 AOT/CDS/GraalVM native（按团队能力评估）；
- 容器资源 requests/limits 合理，避免 CPU throttling。

### 9.4 启动与 readiness

- 启动慢不等于不健康：用 startupProbe 保护；
- readiness 应在依赖就绪后通过；
- liveness 不要在启动阶段过早检查；
- 冷启动/扩容时观察 `application.started.time`。

### 9.5 启动性能纪律

- 每次优化前后用同一环境、同一数据量测量；
- 记录基线；
- 不要用「加内存」掩盖扫描/外部调用问题；
- 不要为了启动快把关键校验/迁移全部异步化；
- native/AOT 是大工程，先评估收益和限制。

---

## 十、常见问题排查

### 10.1 测试启动失败

- 测试类不在 `@SpringBootConfiguration` 包下 → `classes`；
- 缺少测试配置/环境变量；
- 端口冲突 → `RANDOM_PORT`；
- 数据库不可用 → Testcontainers/嵌入式库；
- profile 未激活；
- 测试上下文缓存了错误配置 → 检查属性/profile。

### 10.2 `@WebMvcTest` 404/401/403

- 控制器没包含：`@WebMvcTest(OrderController.class)`；
- 安全过滤器：`@WithMockUser` 或 `addFilters=false`；
- 请求路径/方法不匹配；
- 参数绑定/序列化问题；
- `@ControllerAdvice` 未包含。

### 10.3 `@DataJpaTest` 行为与生产不一致

- 默认嵌入式数据库与生产方言不同；
- `@AutoConfigureTestDatabase(replace=NONE)` + Testcontainers；
- 事务回滚导致看不到提交行为；
- 数据库函数/索引/锁差异。

### 10.4 Testcontainers 失败

- Docker daemon 不可用；
- CI 无 Docker socket；
- 镜像拉取慢/被限流；
- 容器端口冲突；
- 未等待容器健康；
- Ryuk 清理问题；
- 用 `disabledWithoutDocker=true` 跳过时，注意 CI 覆盖率下降。

### 10.5 测试不稳定

- 真实时间/时区/locale；
- 异步未等待；
- 共享数据库状态；
- 测试顺序依赖；
- 固定端口；
- 线程池未关闭；
- 容器/上下文缓存。

### 10.6 jar 启动失败

- `no main manifest attribute` → 没有执行 `repackage`；
- `Start-Class` 找不到 → 主类配置/包路径；
- Java 版本不匹配 → `java -version`；
- 依赖缺失 → 检查 `BOOT-INF/lib`；
- 分层 jar 提取路径不对；
- `includeTools=false` 导致 tools jarmode 不可用。

### 10.7 容器问题

- OOMKilled → `MaxRAMPercentage`、limit、堆外内存；
- 非 root 用户写文件失败 → 目录权限/只读文件系统；
- 时区/locale 不一致 → 固定 UTC/UTF-8；
- 镜像过大 → 分层/基础镜像/`target` 未忽略；
- 健康检查失败 → 端口/路径/管理端口；
- 密钥泄露 → 检查镜像层/环境变量/日志。

### 10.8 发布时 502/请求丢失

- readiness 未先失败；
- preStop 太短；
- `terminationGracePeriodSeconds` 小于停机超时；
- 没有 graceful shutdown；
- 长请求超过超时；
- 负载均衡器未及时摘流量。

### 10.9 数据库迁移导致发布失败

- 迁移时间过长/锁表；
- 新旧代码不兼容；
- 迁移在多个实例并发执行；
- 回滚脚本缺失；
- 大表回填未分批。

### 10.10 启动慢

- 先测量（startup endpoint/JFR）；
- 检查扫描范围、自动配置、Bean 初始化、外部连接；
- 检查 JPA/连接池/定时任务；
- 检查容器 CPU limit；
- 优化后复测基线。

---

## 十一、源码断点建议

| 想观察什么 | 断点位置 |
|---|---|
| 测试上下文加载 | `SpringBootContextLoader#loadContext` |
| 测试引导 | `SpringBootTestContextBootstrapper#getContextLoader` |
| MVC 切片 | `WebMvcTestContextBootstrapper` |
| JPA 切片 | `DataJpaTestContextBootstrapper` |
| MockMvc 配置 | `AutoConfigureMockMvc` |
| 测试上下文缓存 | Spring Test `ContextCache` |
| jar 重打包 | `Repackager#repackage` |
| 启动类布局 | `Layouts$Jar#getLauncherClassName` |
| 分层定义 | `StandardLayers` |
| 可执行 jar 启动 | `org.springframework.boot.loader.launch.JarLauncher#main` |
| Web 优雅停机 | `WebServerGracefulShutdownLifecycle#stop` |
| readiness 切换 | `ServletWebServerApplicationContext#doClose` |
| shutdown hook | `SpringApplication#shutdownHook` |
| 启动端点 | `StartupEndpoint#startup` |
| 启动耗时指标 | `StartupTimeMetricsListener` |

---

## 十二、必做实验

### 实验一：测试层级对比

1. 写一个纯单元测试；
2. 写一个 `@WebMvcTest`；
3. 写一个 `@SpringBootTest`；
4. 对比启动耗时和上下文数量。

### 实验二：MockMvc 切片

用 `@WebMvcTest(OrderController.class)` + `@MockBean` 测 GET/POST/异常；打开安全后观察 401，再用 `@WithMockUser` 修复。

### 实验三：Repository 真实数据库

1. 先用默认 `@DataJpaTest` 跑 H2；
2. 换 PostgreSQL Testcontainers + `@AutoConfigureTestDatabase(replace=NONE)`；
3. 对比 SQL 方言/函数差异。

### 实验四：Testcontainers `@ServiceConnection`

启动 PostgreSQL 容器，使用 `@ServiceConnection` 自动注入连接信息；再用 `@DynamicPropertySource` 写一版，对比两种方式。

### 实验五：JSON 契约

用 `@JsonTest` + `JacksonTester` 验证字段名、日期格式、枚举、空值策略。

### 实验六：外部客户端

用 `@RestClientTest` + `MockRestServiceServer` 验证 URL、请求头、超时、错误响应。

### 实验七：随机端口

用 `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` 调用真实 HTTP；再用 `TestRestTemplate` 验证响应状态和 JSON。

### 实验八：可执行 jar

```bash
./mvnw -DskipTests package
java -jar target/*.jar
jar tf target/*.jar | head
```

检查 `BOOT-INF/classes`、`BOOT-INF/lib`、`Start-Class`、`JarLauncher`。

### 实验九：分层 jar 与 Docker

1. 执行 `java -Djarmode=layertools -jar app.jar extract`；
2. 写分层 Dockerfile；
3. 改业务代码重新构建，观察依赖层缓存命中。

### 实验十：Buildpacks

```bash
./mvnw spring-boot:build-image -Dspring-boot.build-image.imageName=order-service:test
docker run --rm -p 8080:8080 order-service:test
```

对比 Dockerfile 与 Buildpacks 的镜像大小、构建速度、可定制性。

### 实验十一：优雅停机

1. 配置 `server.shutdown=graceful`、`timeout-per-shutdown-phase`；
2. 调用一个耗时 5 秒的接口；
3. 发送 SIGTERM；
4. 观察 readiness 先失败、请求完成、进程退出；
5. 把 timeout 改成 1 秒，观察请求被截断。

### 实验十二：启动分析

1. 用 `BufferingApplicationStartup` 开启 `/actuator/startup`；
2. 记录 `application.started.time`；
3. 用 JFR 采一次启动；
4. 排除一个自动配置/缩小扫描范围，复测。

---

## 十三、面试常问

### 13.1 `@SpringBootTest` 和 `@WebMvcTest` 有什么区别？

`@SpringBootTest` 加载完整上下文，默认 `MOCK` 环境；`@WebMvcTest` 只加载 MVC 切片，Controller 以外的依赖需要 mock。前者适合跨层集成，后者适合 Controller 层快速测试。

### 13.2 `webEnvironment` 四种模式？

`MOCK`（不启动服务器，MockMvc）、`RANDOM_PORT`（启动服务器，随机端口）、`DEFINED_PORT`（固定端口）、`NONE`（非 Web）。默认 `MOCK`。

### 13.3 为什么测试上下文会变多？

配置类、profiles、properties、`@MockBean`、initializers 等不同都会产生不同缓存 key。上下文越多，测试越慢。要复用配置、减少组合、慎用 `@DirtiesContext`。

### 13.4 `@DataJpaTest` 默认用什么数据库？

默认会尝试替换成嵌入式数据库（如 H2）；可以用 `@AutoConfigureTestDatabase(replace=NONE)` + Testcontainers 使用真实数据库。生产方言相关的 SQL 应在真实库上测。

### 13.5 Testcontainers 怎么接入 Spring Boot？

`@Testcontainers` + `@Container` 启动容器；支持时用 `@ServiceConnection` 自动注入连接信息；不支持时用 `@DynamicPropertySource`。需要 Docker daemon。

### 13.6 可执行 jar 的内部结构？

`BOOT-INF/classes` 放应用类，`BOOT-INF/lib` 放依赖，`Main-Class` 是 `JarLauncher`，`Start-Class` 是应用主类；依赖是嵌套 jar，由 Boot loader 加载。

### 13.7 分层 jar 有什么用？

把依赖、loader、SNAPSHOT 依赖、应用类分层；Docker 构建时可以只重建应用层，依赖层缓存命中，提高构建/发布效率。

### 13.8 怎么做到优雅停机？

`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`；readiness 先失败摘流量；K8s `preStop` + `terminationGracePeriodSeconds`；自定义线程池/消费者用 `SmartLifecycle`/`waitForTasksToCompleteOnShutdown`。

### 13.9 滚动发布怎么避免 502？

readiness/startupProbe 正确；preStop 给 Endpoints 更新时间；grace period 大于停机超时；旧实例先摘流量再关闭；数据库迁移向后兼容。

### 13.10 数据库迁移怎么配合发布？

Expand-Contract：先加兼容结构，再迁移/回填，最后删除旧结构；旧代码要能兼容新 schema，回滚才可行；大表迁移分批限流。

### 13.11 启动慢怎么排查？

用 `BufferingApplicationStartup`/`/actuator/startup`、`application.started.time`、JFR 测量；检查扫描、自动配置、Bean 初始化、外部连接、JPA、容器 CPU limit；优化前后复测。

### 13.12 为什么不能所有测试都用 `@SpringBootTest`？

启动完整上下文成本高，测试慢、反馈慢；大部分逻辑用单元/切片测试更快更聚焦，只有跨层装配/真实依赖才需要完整上下文。

---

## 十四、本篇过关标准

学完本文后，需要能做到：

- 区分单元、切片、集成、E2E 测试的边界和成本。
- 使用 `@SpringBootTest` 的四种 `webEnvironment`，理解 `useMainMethod` 和上下文缓存。
- 用 `@WebMvcTest` + MockMvc 测 Controller，并处理安全/依赖 mock。
- 用 `@DataJpaTest` + Testcontainers 测 Repository，理解嵌入式库与真实库差异。
- 用 `@JsonTest`、`@RestClientTest` 测序列化和外部客户端。
- 用 `@ServiceConnection`/`@DynamicPropertySource` 接入 Testcontainers。
- 解释可执行 jar 的结构、`JarLauncher`、`Start-Class`、嵌套依赖。
- 解释分层 jar 和 Docker 层缓存的关系。
- 用 Dockerfile/Buildpacks 构建非 root、容器感知的镜像。
- 配置优雅停机，并说明 Web 容器之外还有哪些资源要关闭。
- 设计滚动/蓝绿/灰度发布和数据库 Expand-Contract 迁移。
- 用 startup endpoint/JFR 测量并优化启动时间。
- 排查测试、jar、镜像、停机、发布、启动的常见问题。

---

## 十五、下一篇建议

下一篇收尾第 6 阶段：

```text
05-04-SpringBoot-性能优化.md
```

重点包括：

- JVM/容器内存与 GC；
- 连接池、线程池、缓存；
- 数据库访问与批处理；
- Web 层吞吐与延迟；
- 压测方法与性能回归；
- 常见性能反模式与排查路径。
