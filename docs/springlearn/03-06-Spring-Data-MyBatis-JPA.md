# Spring 数据访问专题：JdbcTemplate、MyBatis、JPA 集成机制

> 本文是第 4 阶段的第六篇文档，目标是理解 Spring 数据访问抽象，以及 JdbcTemplate、MyBatis、JPA 如何与 Spring 容器和事务体系集成。重点不是背 API，而是建立“DAO/Mapper Bean 如何注册、SQL 如何执行、事务如何共享连接”的主线认知。

---

## 一、先给结论

### 1.1 Spring 数据访问解决什么问题

数据访问层通常要解决：如何获取数据库连接、如何执行 SQL、如何映射结果集、如何处理异常、如何参与事务、如何把 DAO / Mapper 注入到 Service。

Spring 提供了统一的基础设施：

```text
DataSource
JdbcTemplate
TransactionManager
DataAccessException
Repository / Mapper Bean 管理
```

### 1.2 三类常见技术

| 技术 | 特点 |
|---|---|
| `JdbcTemplate` | Spring 原生 JDBC 封装，轻量直接 |
| `MyBatis` | SQL 可控，Mapper 接口代理，国内项目常见 |
| `JPA` | ORM 思想，实体状态管理，Repository 抽象强 |

建议掌握顺序：

```text
先理解 DataSource 和事务连接绑定
再理解 JdbcTemplate
再理解 MyBatis Mapper 代理
最后理解 JPA 的 EntityManager / Repository
```

---

## 二、Spring 数据访问基础设施

### 2.1 DataSource

`DataSource` 是数据库连接来源。常见连接池包括 HikariCP、Druid、Tomcat JDBC。Spring Boot 默认常见是 HikariCP。

### 2.2 JdbcTemplate

`JdbcTemplate` 封装了 JDBC 样板代码：

```text
获取 Connection
创建 PreparedStatement
设置参数
执行 SQL
处理 ResultSet
释放资源
异常转换
```

开发者只关注 SQL 和映射逻辑。

### 2.3 PlatformTransactionManager

数据访问要和事务协作。

JDBC / MyBatis 常用：

```text
DataSourceTransactionManager
```

JPA 常用：

```text
JpaTransactionManager
```

### 2.4 DataAccessException

Spring 把不同数据库、不同驱动的异常转换成统一异常体系。

```text
SQLException
  -> DataAccessException
```

好处是异常体系统一，减少对 JDBC checked exception 的依赖，便于上层统一处理。

---

## 三、JdbcTemplate 核心机制

### 3.1 最小使用

```java
@Repository
public class UserDao {

    private final JdbcTemplate jdbcTemplate;

    public UserDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserDTO findById(Long id) {
        return jdbcTemplate.queryForObject(
                "select id, name from users where id = ?",
                new BeanPropertyRowMapper<>(UserDTO.class),
                id
        );
    }
}
```

### 3.2 执行主流程

```text
JdbcTemplate#query()
  -> DataSourceUtils#getConnection()
  -> 创建 PreparedStatement
  -> 设置参数
  -> 执行 SQL
  -> RowMapper 映射结果
  -> 处理异常转换
  -> DataSourceUtils#releaseConnection()
```

关键是：

```text
JdbcTemplate 不是直接 dataSource.getConnection()
而是通过 DataSourceUtils 获取连接
```

### 3.3 为什么用 DataSourceUtils

`DataSourceUtils` 会优先获取当前线程事务绑定的连接。

```text
如果当前线程有事务连接：复用事务连接
如果没有事务连接：从连接池获取新连接
```

这保证了 JdbcTemplate 能自动参与 Spring 事务。

---

## 四、MyBatis 与 Spring 集成主线

### 4.1 MyBatis 原生核心对象

MyBatis 原生核心概念包括 `SqlSessionFactory`、`SqlSession`、`Mapper` 接口、Mapper XML、`Executor`、`MappedStatement`。

### 4.2 Spring 集成后的变化

在 Spring 项目中，我们通常不会手动创建 `SqlSession`。

```java
@Mapper
public interface UserMapper {

    UserDO selectById(Long id);
}
```

Service 中直接注入：

```java
@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }
}
```

问题是：

```text
UserMapper 是接口，为什么能注入？
```

答案是：

```text
MyBatis-Spring 会为 Mapper 接口注册代理 Bean
```

### 4.3 Mapper 扫描注册

常见入口：

```java
@MapperScan("com.example.mapper")
```

或 Spring Boot 自动扫描 `@Mapper`。

核心过程：

```text
扫描 Mapper 接口
  -> 为接口创建 BeanDefinition
  -> BeanClass 设置为 MapperFactoryBean
  -> Spring 创建 MapperFactoryBean
  -> getObject() 返回 Mapper 代理对象
```

### 4.4 MapperFactoryBean

`MapperFactoryBean` 是 MyBatis Mapper 能注入的关键，它本身是一个 FactoryBean。

```text
Spring 容器中的 Bean 定义：MapperFactoryBean
实际注入给 Service 的对象：Mapper 动态代理
```

代理对象执行方法时：

```text
UserMapper#selectById()
  -> MapperProxy
  -> SqlSessionTemplate
  -> Executor
  -> JDBC
```

---

## 五、SqlSessionTemplate

### 5.1 它解决什么问题

`SqlSession` 原生不是线程安全的，不能直接作为单例 Bean 注入。

Spring 使用：

```text
SqlSessionTemplate
```

它是线程安全的 `SqlSession` 代理。

### 5.2 执行流程

```text
MapperProxy.invoke()
  -> MapperMethod.execute()
  -> SqlSessionTemplate.selectOne/selectList/update
  -> SqlSessionInterceptor
  -> SqlSessionUtils#getSqlSession()
  -> Executor 执行 SQL
```

### 5.3 如何参与事务

`SqlSessionUtils#getSqlSession()` 会检查当前线程是否有事务同步资源。

```text
当前有 Spring 事务：复用绑定到事务的 SqlSession / Connection
当前无 Spring 事务：创建临时 SqlSession，用完关闭
```

底层仍然依赖：

```text
TransactionSynchronizationManager
```

---

## 六、MyBatis 一级缓存与事务边界

### 6.1 一级缓存是什么

MyBatis 一级缓存默认是 SqlSession 级别。

```text
同一个 SqlSession 内，相同查询可能命中一级缓存
```

### 6.2 Spring 事务中的 SqlSession

在 Spring 事务中，同一事务内通常复用同一个 SqlSession。

```text
@Transactional 方法
  -> Mapper 查询 A
  -> Mapper 查询 A
  -> 可能命中同一 SqlSession 的一级缓存
```

### 6.3 注意事项

- 更新操作通常会清理缓存。
- 不要跨业务滥用一级缓存假设。
- 分页、动态 SQL、插件可能影响执行结果。
- 理解缓存边界有助于排查“为什么 SQL 没发出去”。

---

## 七、JPA 与 Spring 集成主线

### 7.1 JPA 核心概念

| 概念 | 说明 |
|---|---|
| Entity | 持久化实体 |
| EntityManager | 实体管理器 |
| Persistence Context | 持久化上下文 |
| Repository | Spring Data 抽象仓储 |
| JpaTransactionManager | JPA 事务管理器 |

### 7.2 Repository 为什么能注入

```java
public interface UserRepository extends JpaRepository<UserEntity, Long> {
}
```

它也是接口，但可以注入。原因是 Spring Data JPA 会为 Repository 接口创建代理对象。

```text
扫描 Repository 接口
  -> 创建代理工厂
  -> 生成 Repository 代理 Bean
  -> 方法调用转成 EntityManager 操作
```

### 7.3 EntityManager 与事务

JPA 的核心是 `EntityManager`。

```text
@Transactional
  -> EntityManager 绑定到当前事务
  -> 查询实体进入 Persistence Context
  -> 修改实体字段
  -> flush 时同步到数据库
```

JPA 和 MyBatis 最大区别之一：

```text
MyBatis 更接近 SQL 显式执行
JPA 更强调实体状态和持久化上下文
```

---

## 八、MyBatis vs JPA vs JdbcTemplate

| 维度 | JdbcTemplate | MyBatis | JPA |
|---|---|---|---|
| SQL 控制 | 最高 | 高 | 较低/可 JPQL/原生 SQL |
| 学习成本 | 低 | 中 | 较高 |
| 对象映射 | 手动/RowMapper | XML/注解映射 | ORM 自动映射 |
| 动态 SQL | 手写 | 强 | 一般 |
| 事务集成 | Spring 原生 | MyBatis-Spring | Spring Data JPA |
| 适合场景 | 简单 SQL、批处理 | 复杂业务 SQL | 领域模型、CRUD 快速开发 |

建议：复杂业务系统常用 MyBatis，强领域模型系统可考虑 JPA，简单脚本或基础设施可用 JdbcTemplate。

---

## 九、事务协作边界：这里只讲接入点

事务传播、回滚规则、事务失效已经在 `03-05-Spring-Transaction.md` 展开，本篇只关注数据访问组件如何接入同一个 Spring 事务。

核心答案是：

```text
事务资源绑定到当前线程
```

| 技术 | 绑定资源 | 关键入口 |
|---|---|---|
| JdbcTemplate | `ConnectionHolder` | `DataSourceUtils#getConnection()` |
| MyBatis | `SqlSessionHolder` / `ConnectionHolder` | `SqlSessionUtils#getSqlSession()` |
| JPA | `EntityManagerHolder` | `JpaTransactionManager` / `SharedEntityManagerCreator` |

只要这些组件通过 Spring 提供的工具类获取资源，就能复用当前事务；如果绕开 Spring 自己手动创建连接或会话，就可能脱离事务管理。

## 十、常见问题排查

### 10.1 Mapper 注入失败

排查：是否加了 `@Mapper`，是否配置了 `@MapperScan`，Mapper 包路径是否正确，Mapper XML namespace 是否和接口全限定名一致，方法名和 SQL statement id 是否一致。

### 10.2 Invalid bound statement

常见原因：

```text
Mapper 接口方法找不到对应 SQL
```

排查 XML 是否被打包进 classpath、namespace 是否正确、SQL id 是否和方法名一致、mapper-locations 配置是否正确。

### 10.3 事务中 SQL 没回滚

排查：方法是否经过 Spring 代理，是否加了 `@Transactional`，异常是否被吞，异常类型是否符合回滚规则，是否使用了正确的事务管理器，数据源是否是同一个。

### 10.4 JPA 修改实体没更新

排查：实体是否处于持久化上下文，方法是否在事务中，是否调用了 flush，是否修改的是 detached 对象，是否事务只读。

### 10.5 N+1 查询

典型表现：

```text
先查列表 1 次
再为每条记录查关联数据 N 次
```

解决思路：明确 SQL join、批量查询关联数据、JPA 使用 fetch join / EntityGraph、MyBatis 避免循环中调用 Mapper。

---

## 十一、源码断点建议

### 11.1 JdbcTemplate

```text
JdbcTemplate#query
JdbcTemplate#execute
DataSourceUtils#getConnection
DataSourceUtils#doGetConnection
DataSourceUtils#releaseConnection
```

### 11.2 MyBatis

```text
MapperFactoryBean#getObject
MapperProxy#invoke
MapperMethod#execute
SqlSessionTemplate#selectOne
SqlSessionInterceptor#invoke
SqlSessionUtils#getSqlSession
SpringManagedTransaction#getConnection
```

### 11.3 JPA

```text
JpaRepositoryFactory#getTargetRepository
SimpleJpaRepository#save
SimpleJpaRepository#findById
SharedEntityManagerCreator
JpaTransactionManager#doBegin
EntityManager#flush
```

---

## 十二、必做实验

### 12.1 实验一：JdbcTemplate 参与事务

1. 写一个 `@Transactional` 方法。
2. 方法内调用两次 JdbcTemplate。
3. 抛出运行时异常。
4. 观察数据是否回滚。
5. 断点看 `DataSourceUtils#getConnection`。

### 12.2 实验二：MyBatis Mapper 代理

1. 定义一个 Mapper 接口。
2. 配置 XML 或注解 SQL。
3. 在 Service 中注入 Mapper。
4. 断点 `MapperFactoryBean#getObject` 和 `MapperProxy#invoke`。
5. 观察接口如何变成代理。

### 12.3 实验三：MyBatis 参与事务

1. `@Transactional` 方法内调用两个 Mapper。
2. 第二个 Mapper 后抛异常。
3. 观察两个操作是否一起回滚。
4. 断点 `SqlSessionUtils#getSqlSession`。

### 12.4 实验四：JPA Repository 代理

1. 定义 `JpaRepository` 接口。
2. 注入 Repository。
3. 调用 `save()` 和 `findById()`。
4. 观察 Repository 代理和 EntityManager 调用。

### 12.5 实验五：N+1 查询

1. 查询列表。
2. 循环中查询关联数据。
3. 打印 SQL 数量。
4. 改成批量查询或 join。
5. 对比 SQL 次数。

---

## 十三、面试常问问题

### 13.1 JdbcTemplate 为什么能参与 Spring 事务？

```text
JdbcTemplate 通过 DataSourceUtils 获取 Connection。DataSourceUtils 会优先从
TransactionSynchronizationManager 中获取当前线程绑定的事务连接，所以在 @Transactional 方法中，
JdbcTemplate 会复用事务连接，最终由事务管理器统一提交或回滚。
```

### 13.2 MyBatis Mapper 接口为什么能注入？

```text
MyBatis-Spring 会扫描 Mapper 接口，并为每个接口注册 MapperFactoryBean。Spring 创建 Bean 时，
MapperFactoryBean#getObject() 返回 Mapper 动态代理对象，所以 Service 注入到的不是接口本身，
而是 MyBatis 创建的 Mapper 代理。
```

### 13.3 SqlSessionTemplate 有什么作用？

```text
SqlSessionTemplate 是 MyBatis-Spring 提供的线程安全 SqlSession 代理。它负责在方法调用时获取
合适的 SqlSession，并通过 SqlSessionUtils 与 Spring 事务同步。如果当前有事务，就复用事务绑定的
SqlSession / Connection；没有事务则创建临时会话并及时关闭。
```

### 13.4 MyBatis 如何参与 Spring 事务？

```text
Spring 事务开启后，DataSourceTransactionManager 会把 Connection 绑定到当前线程。
MyBatis-Spring 获取 SqlSession 和 Connection 时，会通过 TransactionSynchronizationManager
复用当前线程绑定的事务资源，因此同一个 @Transactional 方法内多个 Mapper 操作可以共用同一事务。
```

### 13.5 JPA 和 MyBatis 最大区别是什么？

```text
MyBatis 更强调 SQL 可控，Mapper 方法和 SQL 映射清晰，适合复杂 SQL；JPA 更强调 ORM 和实体状态管理，
通过 EntityManager 和持久化上下文跟踪实体变化，适合领域模型和标准 CRUD，但需要理解懒加载、flush、N+1 等 ORM 问题。
```

---

## 十四、本篇过关标准

- 能说明 Spring 数据访问基础设施包括哪些核心组件。
- 能说明 JdbcTemplate 如何获取连接并参与事务。
- 能说明 MyBatis Mapper 接口为什么能注入。
- 能说明 MapperFactoryBean、MapperProxy、SqlSessionTemplate 的作用。
- 能说明 MyBatis 如何复用 Spring 事务连接。
- 能说明 JPA Repository 代理和 EntityManager 的关系。
- 能区分 JdbcTemplate、MyBatis、JPA 的适用场景。
- 能排查 Mapper 注入失败、SQL statement 找不到、事务不回滚、N+1 查询等问题。
- 能通过源码断点跟踪 JDBC、MyBatis、JPA 的执行主线。