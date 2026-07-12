# Spring 事务专题：声明式事务源码深度解析

> 本文是第 4 阶段的第五篇文档，目标是把前面 AOP 与 MVC 数据访问串起来：理解 `@Transactional` 为什么依赖 AOP，事务是如何开启、提交、回滚的，以及传播行为、隔离级别、回滚规则为什么会影响真实业务结果。

---

## 一、先给结论

### 1.1 声明式事务解决什么问题

业务代码希望这样写：

```java
@Transactional
public void createOrder(CreateOrderRequest request) {
    orderMapper.insert(order);
    orderItemMapper.batchInsert(items);
    stockService.deduct(request.getSkuId(), request.getCount());
}
```

开发者不希望在每个方法里手写获取连接、关闭自动提交、提交、异常回滚、释放连接等样板代码。

Spring 声明式事务就是把这些横切逻辑交给框架处理。

### 1.2 @Transactional 的本质

```text
@Transactional 本质是基于 AOP 的方法拦截
```

执行流程：

```text
调用代理对象方法
  -> TransactionInterceptor
  -> 创建或加入事务
  -> 执行业务方法
  -> 根据异常决定提交或回滚
  -> 清理事务上下文
```

### 1.3 三个核心组件

| 组件 | 职责 |
|---|---|
| `TransactionAttributeSource` | 解析事务属性，如传播行为、隔离级别、回滚规则 |
| `PlatformTransactionManager` | 事务管理抽象，负责开启、提交、回滚 |
| `TransactionInterceptor` | AOP 拦截器，包裹业务方法执行 |

---

## 二、事务基础概念

### 2.1 ACID

| 特性 | 含义 |
|---|---|
| Atomicity 原子性 | 要么全部成功，要么全部失败 |
| Consistency 一致性 | 事务前后数据满足业务约束 |
| Isolation 隔离性 | 并发事务之间互不干扰到指定程度 |
| Durability 持久性 | 提交后数据持久保存 |

### 2.2 本地事务与分布式事务

本文主要讲 Spring 本地事务。

```text
单数据源 / 单数据库连接
  -> DataSourceTransactionManager
```

分布式事务、消息最终一致性、TCC、Saga 等不在本文展开。

---

## 三、Spring 事务架构

### 3.1 PlatformTransactionManager

核心接口：

```java
public interface PlatformTransactionManager extends TransactionManager {

    TransactionStatus getTransaction(TransactionDefinition definition);

    void commit(TransactionStatus status);

    void rollback(TransactionStatus status);
}
```

它抽象了事务生命周期：

```text
getTransaction：获取或创建事务
commit：提交事务
rollback：回滚事务
```

### 3.2 常见实现

| 实现 | 场景 |
|---|---|
| `DataSourceTransactionManager` | JDBC、MyBatis、JdbcTemplate |
| `JpaTransactionManager` | JPA / Hibernate |
| `JtaTransactionManager` | JTA 分布式事务 |
| `R2dbcTransactionManager` | R2DBC 响应式事务 |

第 4 阶段重点掌握 `DataSourceTransactionManager`。

### 3.3 TransactionDefinition

事务定义包含：传播行为、隔离级别、超时时间、是否只读、事务名称。

`@Transactional` 上的属性最终会转换成事务定义。

### 3.4 TransactionStatus

`TransactionStatus` 表示当前事务状态，包含是否新事务、是否只读、是否已完成、是否回滚标记、Savepoint 信息。

---

## 四、声明式事务接入 AOP 的过程

### 4.1 开启事务能力

常见入口：

```java
@EnableTransactionManagement
```

Spring Boot 中通常由自动配置帮你启用事务基础设施。

### 4.2 注册了哪些核心 Bean

声明式事务会注册类似组件：

```text
BeanFactoryTransactionAttributeSourceAdvisor
TransactionAttributeSource
TransactionInterceptor
```

其中：

```text
Advisor = Pointcut + Advice
```

- Pointcut 判断哪些方法有事务属性。
- Advice 就是 `TransactionInterceptor`。

### 4.3 代理创建

如果某个 Bean 方法匹配事务切点，Spring AOP 会为该 Bean 创建代理。

```text
调用 Service 代理对象
  -> TransactionInterceptor
  -> 目标 Service 方法
```

这解释了为什么事务会有自调用失效问题。

---

## 五、TransactionInterceptor 主流程

### 5.1 invoke()

`TransactionInterceptor` 本质是一个 `MethodInterceptor`。

```text
TransactionInterceptor#invoke()
  -> TransactionAspectSupport#invokeWithinTransaction()
```

### 5.2 invokeWithinTransaction()

简化流程：

```text
1. 获取事务属性 TransactionAttribute
2. 获取事务管理器 PlatformTransactionManager
3. createTransactionIfNecessary()
4. 执行业务方法 invocation.proceed()
5. 如果抛异常，completeTransactionAfterThrowing()
6. 如果正常返回，commitTransactionAfterReturning()
7. cleanupTransactionInfo()
```

一句话：

```text
事务拦截器在业务方法前开启事务，在业务方法后提交或回滚事务
```

### 5.3 TransactionAttributeSource：事务属性从哪里来

`TransactionAttributeSource` 负责读取方法或类上的 `@Transactional` 元数据。

它会解析出：

- 传播行为。
- 隔离级别。
- 超时时间。
- 只读标记。
- rollbackFor / noRollbackFor。
- 事务管理器限定符。

简化理解：

```text
@Transactional 注解
  -> TransactionAttributeSource
  -> TransactionAttribute
  -> TransactionInterceptor 根据属性控制事务
```

### 5.4 TransactionInfo：事务上下文如何保存

`TransactionAspectSupport` 会把当前事务信息包装成 `TransactionInfo`。

它包含：

- 当前事务属性。
- 当前事务管理器。
- 当前 `TransactionStatus`。
- 上一个事务上下文。

这样做是为了在嵌套调用、异常处理、清理现场时能恢复上下文。

### 5.5 rollbackOn()：异常如何决定回滚

业务方法抛异常后，事务是否回滚不是简单看“有没有异常”，而是看事务属性的回滚规则。

```text
TransactionAttribute#rollbackOn(Throwable ex)
  -> 命中 rollbackFor：回滚
  -> 命中 noRollbackFor：不回滚
  -> 默认 RuntimeException / Error 回滚
  -> 默认 checked exception 不回滚
```

### 5.6 DataSourceTransactionManager 开启事务

典型动作：

```text
从 DataSource 获取 Connection
设置 autoCommit=false
根据隔离级别调整 Connection
绑定 Connection 到 TransactionSynchronizationManager
```

绑定到 `TransactionSynchronizationManager` 的目的，是让同一线程中的 MyBatis / JdbcTemplate 获取到同一个 Connection。

---

## 六、事务传播行为

### 6.1 REQUIRED

默认传播行为。

```text
当前有事务：加入当前事务
当前无事务：新建事务
```

### 6.2 REQUIRES_NEW

```text
挂起当前事务
新建一个独立事务
新事务完成后恢复外层事务
```

常见用途：操作日志独立提交、审计记录独立提交、失败也要保存的流水。

注意：`REQUIRES_NEW` 需要额外数据库连接，高并发下要关注连接池压力。

### 6.3 NESTED

```text
当前有事务：创建 savepoint
内部失败：回滚到 savepoint
当前无事务：类似 REQUIRED 新建事务
```

是否可用取决于事务管理器和数据库 savepoint 支持。

### 6.4 其它传播行为

| 传播行为 | 含义 |
|---|---|
| `SUPPORTS` | 有事务就加入，没有就非事务执行 |
| `NOT_SUPPORTED` | 挂起当前事务，以非事务执行 |
| `MANDATORY` | 必须存在事务，否则报错 |
| `NEVER` | 必须不存在事务，否则报错 |

面试和实际项目最常用：`REQUIRED`、`REQUIRES_NEW`、`NESTED`。

---

## 七、隔离级别

### 7.1 常见并发问题

| 问题 | 含义 |
|---|---|
| 脏读 | 读到别人未提交数据 |
| 不可重复读 | 同一事务内两次读同一行结果不同 |
| 幻读 | 同一事务内两次范围查询行数不同 |

### 7.2 Spring 隔离级别

| 隔离级别 | 说明 |
|---|---|
| `DEFAULT` | 使用数据库默认隔离级别 |
| `READ_UNCOMMITTED` | 可能脏读 |
| `READ_COMMITTED` | 避免脏读 |
| `REPEATABLE_READ` | 避免脏读、不可重复读，MySQL 默认 |
| `SERIALIZABLE` | 最高隔离，性能成本高 |

建议大多数业务使用数据库默认隔离级别，不要随意提高到 `SERIALIZABLE`。

---

## 八、回滚规则

### 8.1 默认规则

Spring 默认只对以下异常回滚：

```text
RuntimeException
Error
```

受检异常默认不回滚。

```java
@Transactional
public void importData() throws IOException {
    mapper.insert(data);
    throw new IOException("导入失败");
}
```

默认情况下，`IOException` 不一定触发回滚。

### 8.2 rollbackFor

```java
@Transactional(rollbackFor = Exception.class)
public void importData() throws Exception { ... }
```

建议在业务中根据异常体系明确回滚规则。

### 8.3 noRollbackFor

```java
@Transactional(noRollbackFor = BusinessWarningException.class)
public void process() { ... }
```

表示某些异常不触发回滚。

### 8.4 异常被吞导致不回滚

```java
@Transactional
public void createOrder() {
    try {
        mapper.insert(order);
        remoteCall();
    } catch (Exception e) {
        log.error("failed", e);
    }
}
```

异常被 catch 且没有继续抛出，事务拦截器认为方法正常返回，就会提交事务。

---

## 九、事务失效场景

### 9.1 自调用失效

```java
public void outer() {
    this.inner();
}

@Transactional
public void inner() { ... }
```

`this.inner()` 没有经过代理对象，所以事务不生效。

### 9.2 常见失效清单

- 方法不是 public。
- 类不是 Spring Bean。
- final / private 方法无法被正常增强。
- 异常类型不符合回滚规则。
- 异常被 catch 后没有继续抛出。
- 多线程导致事务上下文丢失。

事务上下文绑定在线程上：

```text
TransactionSynchronizationManager 使用 ThreadLocal 保存事务资源
```

新线程中默认拿不到外层事务。

---

## 十、MyBatis / JDBC 如何参与同一事务

### 10.1 Connection 绑定线程

事务开启后，`DataSourceTransactionManager` 会把数据库连接绑定到当前线程。

```text
TransactionSynchronizationManager
  -> ThreadLocal resources
  -> DataSource -> ConnectionHolder
```

### 10.2 MyBatis 获取连接

MyBatis-Spring 会通过 Spring 的事务同步机制获取连接。

```text
Service @Transactional
  -> MapperA 使用线程绑定 Connection
  -> MapperB 使用同一个 Connection
  -> 提交或回滚由事务管理器统一处理
```

### 10.3 不要手动管理连接

在 Spring 事务里，不建议自己手动：

```java
connection.commit();
connection.rollback();
connection.close();
```

否则可能破坏 Spring 的事务管理。

---

## 十一、只读事务和超时

### 11.1 readOnly

```java
@Transactional(readOnly = true)
public UserDTO detail(Long id) { ... }
```

含义：

- 给事务管理器和数据库一个只读提示。
- 某些框架可据此优化 flush 行为。
- 不等于绝对禁止写入，具体取决于数据库和驱动。

### 11.2 timeout

```java
@Transactional(timeout = 3)
public void process() { ... }
```

表示事务超时时间。超时不是万能的，它和事务管理器、数据库驱动、SQL 执行状态有关。

---

## 十二、源码断点建议

```text
TransactionInterceptor#invoke
TransactionAspectSupport#invokeWithinTransaction
TransactionAspectSupport#createTransactionIfNecessary
AbstractPlatformTransactionManager#getTransaction
DataSourceTransactionManager#doBegin
TransactionAspectSupport#completeTransactionAfterThrowing
TransactionAspectSupport#commitTransactionAfterReturning
AbstractPlatformTransactionManager#commit
AbstractPlatformTransactionManager#rollback
DataSourceTransactionManager#doCommit
DataSourceTransactionManager#doRollback
TransactionSynchronizationManager#bindResource
```

观察：事务属性如何解析、事务管理器如何选择、数据库连接何时获取、连接何时绑定到线程、正常返回如何提交、抛异常如何判断是否回滚。

---

## 十三、必做实验

### 13.1 实验一：观察事务开启提交

1. 写一个 `@Transactional` Service 方法。
2. 方法内调用两个 Mapper。
3. 在 `TransactionInterceptor#invoke` 打断点。
4. 在 `DataSourceTransactionManager#doBegin` 打断点。
5. 观察 `autoCommit=false`。

### 13.2 实验二：运行时异常回滚

1. 插入一条数据。
2. 抛出 `RuntimeException`。
3. 观察事务是否回滚。

### 13.3 实验三：受检异常默认不回滚

1. 插入一条数据。
2. 抛出 `IOException`。
3. 观察默认是否提交。
4. 添加 `rollbackFor = Exception.class` 再观察。

### 13.4 实验四：自调用失效

1. `outer()` 调用 `this.inner()`。
2. `inner()` 加 `@Transactional`。
3. 观察事务是否开启。
4. 改成通过代理对象调用再观察。

### 13.5 实验五：REQUIRES_NEW

1. 外层事务插入主表。
2. 内层 `REQUIRES_NEW` 插入日志表。
3. 外层抛异常。
4. 观察日志是否独立提交。

---

## 十四、面试常问问题

### 14.1 @Transactional 原理是什么？

```text
@Transactional 基于 Spring AOP。Spring 会为匹配事务切点的 Bean 创建代理，方法调用进入代理后，
TransactionInterceptor 会解析事务属性，通过 PlatformTransactionManager 开启或加入事务，
执行业务方法后根据异常和回滚规则提交或回滚事务。
```

### 14.2 Spring 事务为什么会自调用失效？

```text
因为声明式事务依赖代理对象拦截方法调用。this.inner() 是目标对象内部调用，没有经过代理对象，
TransactionInterceptor 不会执行，所以 @Transactional 不生效。
```

### 14.3 REQUIRED 和 REQUIRES_NEW 区别？

```text
REQUIRED 是默认传播行为，有事务就加入，没有就新建；REQUIRES_NEW 会挂起当前事务，
新建一个独立事务，内外事务提交回滚互相独立，但会占用额外数据库连接。
```

### 14.4 默认哪些异常会回滚？

```text
Spring 默认对 RuntimeException 和 Error 回滚，受检异常默认不回滚。可以通过 rollbackFor、
noRollbackFor 自定义回滚规则。
```

### 14.5 MyBatis 如何参与 Spring 事务？

```text
Spring 事务开启时，DataSourceTransactionManager 会获取 Connection 并绑定到当前线程。
MyBatis-Spring 获取连接时会优先使用 TransactionSynchronizationManager 中绑定的连接，
因此同一事务内多个 Mapper 操作使用同一个连接，最终由事务管理器统一提交或回滚。
```

---

## 十五、本篇过关标准

- 能说明 `@Transactional` 为什么依赖 AOP。
- 能说出 `TransactionInterceptor` 的主流程。
- 能解释 `PlatformTransactionManager` 的三个核心方法。
- 能区分 REQUIRED、REQUIRES_NEW、NESTED。
- 能说明默认回滚规则和 `rollbackFor` 的作用。
- 能排查自调用、异常被吞、非 Spring Bean、受检异常不回滚等事务失效问题。
- 能说明 MyBatis / JDBC 为什么能参与同一个 Spring 事务。
- 能通过源码断点跟踪事务开启、提交、回滚流程。