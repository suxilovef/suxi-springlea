# Spring Boot 专题：Starter 设计原理与自定义 Starter 实战

> 本文是第 5 阶段 Spring Boot 核心机制的第三篇文档，承接 `04-02-SpringBoot-自动配置.md`。
>
> 上一篇已经讲清自动配置类如何被发现、筛选和导入；本文继续回答另一个工程化问题：为什么只引入一个 Starter，相关依赖和默认 Bean 就都准备好了？
>
> 本文以 Spring Boot 3.3.9、Java 17 和 Maven 为基准。配置绑定的完整机制留到下一篇，本文只使用它完成一个可运行的 Starter 示例。

---

## 一、先给结论

### 1.1 Starter 到底是什么

Starter 首先是一个依赖聚合模块。

它通过 Maven POM 或 Gradle Module Metadata，把完成某类功能所需的依赖组合起来，让使用者不必逐个声明。

例如：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

这一个依赖会继续带入：

- Spring Boot 基础 Starter。
- JSON 支持。
- 内嵌 Tomcat。
- Spring Web。
- Spring MVC。

一句话概括：

```text
Starter 负责把依赖带进来，AutoConfiguration 负责根据条件把 Bean 配出来
```

### 1.2 引入 Starter 后发生的两阶段过程

必须区分构建期和运行期：

```text
构建期
  -> Maven 读取 Starter POM
  -> 解析传递依赖
  -> 把相关 jar 放入 classpath

运行期
  -> @EnableAutoConfiguration 导入自动配置候选
  -> 条件注解检查 classpath、Bean、属性和应用类型
  -> 条件成立时注册默认 Bean
```

Starter 本身不会在应用启动后“执行一段初始化代码”。

真正执行 Spring 配置逻辑的是 Starter 带入的自动配置模块。

### 1.3 Starter 不是什么

Starter 不是：

- Spring IOC 之外的一套对象创建机制。
- 必须包含大量 Java 代码的业务模块。
- 自动扫描所有依赖并注册 Bean 的魔法入口。
- Spring Boot Maven 插件的替代品。
- Spring Boot Parent 或 BOM 的替代品。

一个纯粹的 Starter 甚至可以只有一个 `pom.xml`，最终生成的 jar 中没有任何业务类。

---

## 二、Starter、AutoConfiguration、类库的职责边界

### 2.1 三类模块分别解决什么问题

一个规范的第三方 Starter 通常涉及三类角色：

| 角色 | 主要职责 | 是否由业务应用直接使用 |
|---|---|---|
| 原始类库 | 提供真正的 SDK、客户端或业务能力 | 可以 |
| AutoConfigure 模块 | 根据 Spring Boot 环境创建和装配类库对象 | 通常不直接使用 |
| Starter 模块 | 聚合 AutoConfigure 模块和原始类库 | 推荐直接使用 |

示例：

```text
greeting-client
  -> 提供 GreetingClient

greeting-spring-boot-autoconfigure
  -> 提供 GreetingAutoConfiguration
  -> 提供 GreetingProperties

greeting-spring-boot-starter
  -> 依赖前两个模块
  -> 自身通常不写运行时代码
```

### 2.2 为什么推荐拆分

拆分后可以得到更清晰的依赖边界：

- 只需要原始 Java API 的项目可以单独使用类库。
- 想自行控制依赖组合的项目可以只引入 AutoConfigure 模块。
- 普通 Spring Boot 项目直接引入 Starter 即可。
- AutoConfigure 模块可以把原始类库声明为可选依赖。
- Starter 决定默认向使用者提供哪些真实依赖。

### 2.3 小型项目可以合并吗

可以。

如果功能很小，可以把自动配置类和依赖聚合放在同一个模块中：

```text
greeting-spring-boot-starter
  -> GreetingAutoConfiguration
  -> GreetingProperties
  -> AutoConfiguration.imports
  -> greeting-client 依赖
```

但模块一旦需要独立发布、控制可选依赖或支持多种组合，拆分为 `autoconfigure + starter` 通常更容易维护。

---

## 三、从 spring-boot-starter-web 看官方 Starter

### 3.1 Starter 的核心是依赖声明

Spring Boot 3.3.9 的 `spring-boot-starter-web` 主要聚合：

```text
spring-boot-starter
spring-boot-starter-json
spring-boot-starter-tomcat
spring-web
spring-webmvc
```

它解决的是：

```text
开发 Servlet Web 应用通常需要哪些依赖
```

而 `DispatcherServlet`、`RequestMappingHandlerMapping`、`HttpMessageConverter` 等 Bean 的默认创建，主要由 `spring-boot-autoconfigure` 中的 Web 自动配置完成。

### 3.2 Starter 与自动配置如何配合

如果只把 Spring MVC 和 Tomcat 放进 classpath，但没有启用 Spring Boot 自动配置：

- 依赖已经存在。
- Spring MVC 类可以被加载。
- Tomcat 类也可以被加载。
- 但 Spring Boot 不会凭空完成整套默认 Bean 注册。

反过来，如果自动配置类存在，但相关类库不在 classpath：

- `@ConditionalOnClass` 可能不成立。
- 对应自动配置会被跳过。
- 默认 Bean 仍然不会创建。

因此两者是配合关系：

```text
Starter 改变 classpath
  -> classpath 又参与自动配置条件判断
  -> 自动配置决定最终注册哪些 Bean
```

自动配置不是由 Starter 直接“调用”的。

Starter 只是通过依赖关系把自动配置 jar 放入 classpath。在典型 Spring Boot 应用中，`@EnableAutoConfiguration` 才负责统一导入自动配置候选；测试或框架内部也可以通过 `@ImportAutoConfiguration` 定向导入自动配置。

---

## 四、Starter 与 Parent、BOM、Plugin 的区别

### 4.1 spring-boot-starter-parent

`spring-boot-starter-parent` 是 Maven Parent POM，主要提供：

- Spring Boot 依赖版本管理。
- 常用 Maven 插件的默认配置。
- Java 编译、资源过滤等构建约定。

它不是普通运行时依赖，不能放进 `<dependencies>` 代替 Starter。

### 4.2 spring-boot-dependencies

`spring-boot-dependencies` 是 BOM，核心作用是集中管理兼容的依赖版本。

使用 BOM：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>3.3.9</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

必须注意：

```text
dependencyManagement 只管理版本，不会自动把依赖加入 classpath
```

### 4.3 spring-boot-maven-plugin

`spring-boot-maven-plugin` 是构建插件，常用于：

- 把应用重新打包为可执行 jar。
- 生成 Boot loader 结构。
- 提供 `spring-boot:run` 等 Maven Goal。

它负责构建和打包，不负责提供 Web、JDBC、Validation 等运行时依赖。

### 4.4 四者对比

| 名称 | Maven 位置 | 核心职责 |
|---|---|---|
| Parent | `<parent>` | 构建默认值和版本管理 |
| BOM | `<dependencyManagement>` | 统一管理依赖版本 |
| Starter | `<dependencies>` | 聚合一组功能依赖 |
| Plugin | `<build><plugins>` | 编译、运行、重新打包 |

---

## 五、自定义 Starter 的目标与工程结构

### 5.1 示例目标

本文实现一个 Greeting Starter。

业务应用只需引入：

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>greeting-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

再配置：

```yaml
acme:
  greeting:
    endpoint: https://greeting.example.com
```

就可以直接注入：

```java
private final GreetingClient greetingClient;
```

如果用户自己声明了 `GreetingClient`，默认配置必须退让。

### 5.2 推荐目录

```text
greeting-spring-boot/
├── pom.xml
├── greeting-client/
│   ├── pom.xml
│   └── src/main/java/com/example/greeting/client/GreetingClient.java
├── greeting-spring-boot-autoconfigure/
│   ├── pom.xml
│   ├── src/main/java/com/example/greeting/autoconfigure/
│   │   ├── GreetingAutoConfiguration.java
│   │   └── GreetingProperties.java
│   └── src/main/resources/META-INF/spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
├── greeting-spring-boot-starter/
│   └── pom.xml
└── greeting-spring-boot-starter-tests/
    ├── pom.xml
    └── src/test/java/com/example/greeting/tests/
        └── StarterIntegrationTests.java
```

### 5.3 父工程只负责聚合

父工程：

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.9</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>greeting-spring-boot</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>greeting-client</module>
        <module>greeting-spring-boot-autoconfigure</module>
        <module>greeting-spring-boot-starter</module>
        <module>greeting-spring-boot-starter-tests</module>
    </modules>

    <properties>
        <java.version>17</java.version>
    </properties>
</project>
```

父工程使用 `pom` 打包，只聚合子模块，不生成可运行应用。

---

## 六、原始类库模块

### 6.1 greeting-client 的 POM

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>greeting-spring-boot</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>greeting-client</artifactId>
</project>
```

### 6.2 提供普通 Java 能力

```java
package com.example.greeting.client;

public class GreetingClient {

    private final String endpoint;

    public GreetingClient(String endpoint) {
        this.endpoint = endpoint;
    }

    public String greet(String name) {
        return "Hello, " + name + " from " + endpoint;
    }

    public String getEndpoint() {
        return endpoint;
    }
}
```

这个模块：

- 不依赖 Spring。
- 不声明 `@Component`。
- 不关心自动配置。
- 可以在非 Spring 项目中单独使用。

这能避免把框架集成逻辑侵入原始类库。

---

## 七、AutoConfigure 模块的依赖设计

### 7.1 基础 POM

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>greeting-spring-boot</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>greeting-spring-boot-autoconfigure</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>

        <dependency>
            <groupId>com.example</groupId>
            <artifactId>greeting-client</artifactId>
            <version>${project.version}</version>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 7.2 为什么 greeting-client 在这里是 optional

AutoConfigure 模块编译自动配置代码时需要 `GreetingClient`，但不一定要强迫所有直接使用 AutoConfigure 模块的项目都获得该类库。

声明为可选依赖后：

```text
autoconfigure 可以编译
但 greeting-client 不会继续作为它的传递依赖提供给下游
```

真正的 Starter 会显式依赖 `greeting-client`，从而决定默认组合。Maven 的 `optional=true` 不是运行时开关，也不是 Spring 条件，它只控制该依赖是否继续传递给下游。

```text
A 依赖 autoconfigure
autoconfigure 可选依赖 greeting-client

结果：
A 默认不会通过传递依赖获得 greeting-client
```

如果 A 自己显式依赖 `greeting-client`，它仍然会正常进入 classpath。

---

## 八、配置属性类

### 8.1 最小属性对象

```java
package com.example.greeting.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "acme.greeting")
public class GreetingProperties {

    private String endpoint = "http://localhost:8080";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
}
```

它把 `acme.greeting.endpoint` 保存为一个类型安全的 Java 对象。

### 8.2 本文只关注它在 Starter 中的角色

在 Starter 设计中，配置属性类负责：

- 为集成能力提供统一配置入口。
- 提供合理默认值。
- 避免自动配置类到处直接读取 `Environment`。
- 为 IDE 配置提示提供元数据来源。

属性来源、松散绑定、构造器绑定和校验将在 `04-04-SpringBoot-配置绑定.md` 中详细拆解。

---

## 九、自动配置类

### 9.1 完整代码

```java
package com.example.greeting.autoconfigure;

import com.example.greeting.client.GreetingClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(GreetingClient.class)
@EnableConfigurationProperties(GreetingProperties.class)
public class GreetingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GreetingClient greetingClient(GreetingProperties properties) {
        return new GreetingClient(properties.getEndpoint());
    }
}
```

### 9.2 每个注解的职责

| 注解 | 职责 |
|---|---|
| `@AutoConfiguration` | 声明这是 Boot 自动配置类 |
| `@ConditionalOnClass` | 只有类库存在时才处理该配置 |
| `@EnableConfigurationProperties` | 注册并启用配置属性对象 |
| `@Bean` | 声明默认 Bean |
| `@ConditionalOnMissingBean` | 用户未提供同类 Bean 时才创建默认 Bean |

### 9.3 为什么条件放在配置类上

`@ConditionalOnClass(GreetingClient.class)` 放在配置类上，可以在整个类库不存在时尽早跳过这组配置。

如果一个 AutoConfiguration 同时集成多个相互独立的可选能力，可以拆成多个内部配置类，分别设置条件，避免一个条件关闭全部能力。

### 9.4 为什么不要依赖组件扫描

自动配置类不应该要求使用者扫描 `com.example.greeting.autoconfigure`。

原因是：

- 使用者的启动类通常不在同一根包。
- 组件扫描范围属于业务应用，不应成为第三方 Starter 的隐式前提。
- 自动配置已有标准导入机制。
- 扫描和自动配置同时生效可能造成重复处理。

正确入口是 `AutoConfiguration.imports`。

---

## 十、注册 AutoConfiguration.imports

### 10.1 文件路径

创建：

```text
greeting-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 10.2 文件内容

```text
com.example.greeting.autoconfigure.GreetingAutoConfiguration
```

多个自动配置类时每行一个全限定类名：

```text
com.example.greeting.autoconfigure.GreetingAutoConfiguration
com.example.greeting.autoconfigure.GreetingMetricsAutoConfiguration
```

### 10.3 它与 Starter 的关系

这个文件属于 AutoConfigure 模块，而不是必须放在 Starter 模块。

依赖链如下：

```text
业务应用
  -> greeting-spring-boot-starter
    -> greeting-spring-boot-autoconfigure
      -> AutoConfiguration.imports
        -> GreetingAutoConfiguration
```

只要该资源最终进入运行时 classpath，Spring Boot 就可以发现候选自动配置。

---

## 十一、Starter 模块

### 11.1 Starter POM

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>greeting-spring-boot</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>greeting-spring-boot-starter</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>greeting-spring-boot-autoconfigure</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>com.example</groupId>
            <artifactId>greeting-client</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</project>
```

### 11.2 为什么 Starter 要再次依赖 greeting-client

AutoConfigure 模块中的可选依赖不会自动传递给使用者，因此 Starter 必须显式补回 `greeting-client`：

```text
引入 Starter
  -> 获得 autoconfigure
  -> 获得 greeting-client
  -> @ConditionalOnClass(GreetingClient.class) 成立
  -> 默认 GreetingClient 可以被创建
```

如果漏掉该依赖，AutoConfiguration 仍可能被发现，但 `@ConditionalOnClass` 不成立，默认 Bean 不会创建。

### 11.3 是否需要依赖 spring-boot-starter

不是所有自定义 Starter 都必须显式依赖 `spring-boot-starter`。

应该根据实际能力决定：

- AutoConfigure 模块已经需要 `spring-boot-autoconfigure`。
- 原始类库有自己的必要依赖。
- 如果确实需要 Boot 基础日志等约定，可以聚合 `spring-boot-starter`。
- 不要为了“看起来像 Starter”而引入无关依赖。

最重要的原则是：

```text
Starter 应提供完成该能力所需的最小合理依赖集合
```

---

## 十二、依赖管理与传递依赖

### 12.1 Starter 为什么通常不写依赖版本

使用 Spring Boot Parent 或 BOM 后，受管理的依赖通常不需要显式版本：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-autoconfigure</artifactId>
</dependency>
```

但自研模块的版本仍需管理。

同一个多模块工程可以使用：

```xml
<version>${project.version}</version>
```

独立发布时也可以在自定义 BOM 中统一管理。

### 12.2 不要随意覆盖 Boot 管理版本

Spring Boot BOM 提供的是经过兼容性验证的一组版本。

随意覆盖 Jackson、Tomcat、Hibernate、Spring Framework 等核心依赖版本，可能造成：

- 方法不存在。
- 类加载失败。
- 自动配置条件判断异常。
- 运行时行为与文档不一致。

如果必须升级，应先确认 Spring Boot 兼容范围并执行完整测试。

### 12.3 exclusions 应谨慎使用

业务应用可以排除 Starter 的传递依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-tomcat</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

但排除后必须补上替代实现或确认相关自动配置会安全退让。

例如排除 Tomcat 后，通常需要引入其他受支持的嵌入式服务器，而不是只删除依赖。

### 12.4 用 dependency:tree 查看真实结果

```bash
mvn dependency:tree
```

重点检查：

- Starter 实际带入了哪些依赖。
- 是否出现重复版本。
- 某个 jar 是从哪条路径传递进来的。
- 可选依赖是否符合预期。
- 测试依赖是否错误进入运行时。

---

## 十三、Starter 命名规范

### 13.1 官方 Starter

Spring Boot 官方模块通常使用：

```text
spring-boot-starter-{name}
```

例如：

```text
spring-boot-starter-web
spring-boot-starter-jdbc
spring-boot-starter-validation
```

### 13.2 第三方 Starter

第三方项目推荐使用：

```text
{name}-spring-boot-starter
```

例如：

```text
greeting-spring-boot-starter
payment-spring-boot-starter
audit-spring-boot-starter
```

不要把第三方模块命名为 `spring-boot-starter-greeting`，以免与 Spring Boot 官方命名空间混淆。

### 13.3 配置前缀也要避免冲突

属性前缀应体现组织或产品边界：

```yaml
acme:
  greeting:
    endpoint: https://example.com
```

大型公共 Starter 不建议使用过于宽泛的前缀，例如：

```text
client
service
config
common
```

---

## 十四、配置元数据

### 14.1 configuration processor 的作用

`spring-boot-configuration-processor` 会根据 `@ConfigurationProperties` 生成配置元数据：

```text
META-INF/spring-configuration-metadata.json
```

IDE 可以基于该文件提供：

- 属性名补全。
- 类型提示。
- 默认值提示。
- 属性说明。
- 废弃属性提示。

### 14.2 它不是运行时绑定器

配置处理器主要在编译期生成元数据。

即使没有生成元数据，运行时配置绑定仍可能正常工作；但使用者的 IDE 体验会明显下降。

因此要区分：

```text
配置绑定
  -> 运行时把外部属性绑定到对象

配置元数据
  -> 编译期生成给工具和 IDE 使用的说明文件
```

### 14.3 autoconfigure processor 的作用

`spring-boot-autoconfigure-processor` 会在编译期分析自动配置类，并生成：

```text
META-INF/spring-autoconfigure-metadata.properties
```

Spring Boot 可以利用这份元数据更早过滤明显不匹配的自动配置，减少不必要的类加载和条件解析。

它不能替代 `AutoConfiguration.imports`：

```text
AutoConfiguration.imports
  -> 声明有哪些自动配置候选

spring-autoconfigure-metadata.properties
  -> 提供部分条件和排序元数据，辅助提前过滤
```

### 14.4 显式 annotationProcessorPaths 的特殊情况

在 Java 17 和 Maven 默认配置下，把处理器声明为依赖即可参与注解处理。

但如果项目显式配置了 `maven-compiler-plugin` 的 `annotationProcessorPaths`，编译器只会从列出的路径中查找处理器。此时仅添加依赖还不够，必须把处理器加入路径。

当前仓库的 `pom.xml` 已经将该路径限定为 Lombok，因此需要扩展为：

```xml
<annotationProcessorPaths>
    <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
    </path>
    <path>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-configuration-processor</artifactId>
    </path>
    <path>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure-processor</artifactId>
    </path>
</annotationProcessorPaths>
```

生成主代码元数据时至少要修正 `default-compile`；如果测试源码也使用 Lombok 或其他注解处理器，再同步检查 `default-testCompile`。修改时必须保留项目原有的处理器。

### 14.5 验证元数据是否打包

构建后执行：

```bash
jar tf greeting-spring-boot-autoconfigure/target/greeting-spring-boot-autoconfigure-1.0.0.jar
```

检查是否存在：

```text
META-INF/spring-configuration-metadata.json
META-INF/spring-autoconfigure-metadata.properties
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

---

## 十五、使用 Starter

### 15.1 安装或发布

在多模块工程根目录执行：

```bash
mvn clean install
```

这会按模块依赖顺序构建并安装到本地 Maven 仓库。

团队共享时应发布到公司 Maven 私服，而不是要求每个使用者复制 jar。

### 15.2 业务应用引入

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>greeting-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

配置：

```yaml
acme:
  greeting:
    endpoint: https://greeting.example.com
```

使用：

```java
@RestController
public class GreetingController {

    private final GreetingClient greetingClient;

    public GreetingController(GreetingClient greetingClient) {
        this.greetingClient = greetingClient;
    }

    @GetMapping("/greet")
    public String greet(@RequestParam String name) {
        return greetingClient.greet(name);
    }
}
```

### 15.3 验证用户配置退让

业务应用显式声明：

```java
@Bean
GreetingClient customGreetingClient() {
    return new GreetingClient("https://custom.example.com");
}
```

由于默认 Bean 使用 `@ConditionalOnMissingBean`，容器中最终应只有用户提供的 `GreetingClient`。

---

## 十六、AutoConfiguration 测试

### 16.1 为什么推荐 ApplicationContextRunner

`ApplicationContextRunner` 可以创建一个轻量 ApplicationContext，并精确控制：

- 加载哪些自动配置。
- 提供哪些属性。
- 预先注册哪些用户 Bean。
- 使用什么 ClassLoader。

它比启动完整 Web 应用更适合验证自动配置条件。

### 16.2 完整 AutoConfigure 模块测试

下面的代码是一个完整测试类，同时验证默认 Bean、用户退让、缺类条件和 imports 发现机制：

```java
package com.example.greeting.autoconfigure;

import com.example.greeting.client.GreetingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class GreetingAutoConfigurationTests {

    private final ApplicationContextRunner directRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(GreetingAutoConfiguration.class)
                    );

    private final ApplicationContextRunner importsRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            EnableAutoConfigurationTestConfiguration.class
                    );

    @Test
    void createsDefaultGreetingClient() {
        directRunner
                .withPropertyValues(
                        "acme.greeting.endpoint=https://test.example.com"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(GreetingClient.class);
                    assertThat(context.getBean(GreetingClient.class).getEndpoint())
                            .isEqualTo("https://test.example.com");
                });
    }

    @Test
    void backsOffWhenUserProvidesGreetingClient() {
        GreetingClient customClient =
                new GreetingClient("https://custom.example.com");

        directRunner
                .withBean(GreetingClient.class, () -> customClient)
                .run(context -> {
                    assertThat(context).hasSingleBean(GreetingClient.class);
                    assertThat(context.getBean(GreetingClient.class))
                            .isSameAs(customClient);
                });
    }

    @Test
    void doesNotConfigureWhenGreetingClientIsMissing() {
        directRunner
                .withClassLoader(new FilteredClassLoader(GreetingClient.class))
                .run(context ->
                        assertThat(context)
                                .doesNotHaveBean(GreetingClient.class)
                );
    }

    @Test
    void discoversAutoConfigurationFromImports() {
        importsRunner
                .withPropertyValues(
                        "acme.greeting.endpoint=https://imports.example.com"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(GreetingClient.class);
                    assertThat(context.getBean(GreetingClient.class).getEndpoint())
                            .isEqualTo("https://imports.example.com");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class EnableAutoConfigurationTestConfiguration {
    }
}
```

两个 Runner 的测试目标不同：

| Runner | 验证内容 |
|---|---|
| `directRunner` | 自动配置类自身的条件和 Bean 注册逻辑 |
| `importsRunner` | `@EnableAutoConfiguration` 能否从 `AutoConfiguration.imports` 发现配置类 |

### 16.3 增加独立 consumer 集成测试

AutoConfigure 模块测试仍然不能证明 Starter POM 正确，因为测试模块直接依赖了 AutoConfigure 和 client。

建议增加一个只依赖 Starter 的测试模块：

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example</groupId>
        <artifactId>greeting-spring-boot</artifactId>
        <version>1.0.0</version>
    </parent>

    <artifactId>greeting-spring-boot-starter-tests</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>greeting-spring-boot-starter</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

该模块不直接依赖 `greeting-client` 或 AutoConfigure 模块。测试代码：

```java
package com.example.greeting.tests;

import com.example.greeting.client.GreetingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = StarterIntegrationTests.TestApplication.class,
        properties = "acme.greeting.endpoint=https://consumer.example.com"
)
class StarterIntegrationTests {

    @Autowired
    private GreetingClient greetingClient;

    @Test
    void starterProvidesClientAndAutoConfiguration() {
        assertThat(greetingClient.getEndpoint())
                .isEqualTo("https://consumer.example.com");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
```

这个测试同时验证：

- Starter POM 确实传递了 `greeting-client`。
- Starter POM 确实传递了 AutoConfigure 模块。
- `AutoConfiguration.imports` 已正确打包。
- 配置属性可以绑定。
- 默认 `GreetingClient` 可以创建。

---

## 十七、常见设计错误与排查

### 17.1 Starter 引入后完全不生效

依次检查：

1. Starter 是否依赖 AutoConfigure 模块。
2. `AutoConfiguration.imports` 路径是否正确。
3. 自动配置类全限定名是否正确。
4. 资源文件是否打进 jar。
5. 原始类库是否真的进入运行时 classpath。
6. `@ConditionalOnClass` 等条件是否成立。
7. 应用是否排除了该自动配置。

### 17.2 把 Starter 当成组件扫描包

错误做法：

```java
@ComponentScan("com.example.greeting")
```

这会把第三方包结构暴露给使用者，并可能扫描到不应注册的内部类。

应通过标准自动配置导入机制注册 Bean。

### 17.3 Starter 聚合过多无关依赖

例如一个日志审计 Starter 无条件带入：

- Spring MVC。
- JDBC。
- Redis。
- Kafka。

会造成：

- classpath 膨胀。
- 更多自动配置候选被激活。
- 启动时间和内存占用增加。
- 依赖冲突概率上升。
- 使用者难以判断真正需要什么。

应按能力拆分 Starter，或者把扩展集成做成可选模块。

### 17.4 只测试自动配置类，不测试发布物

测试源码中的自动配置类可以通过，不代表最终 jar 正确。

发布物还可能存在：

- imports 文件漏打包。
- 配置元数据未生成。
- Maven Scope 写错。
- Starter POM 漏依赖。
- Shade 或资源合并过程覆盖 `META-INF` 文件。

因此应检查 jar 内容并建立使用方集成测试。

### 17.5 自动配置覆盖用户 Bean

检查：

- 是否缺少 `@ConditionalOnMissingBean`。
- 条件搜索范围是否符合预期。
- 用户 Bean 是否在条件判断前已经注册为 BeanDefinition。
- 自动配置之间的顺序是否正确。
- 是否错误使用 `@Primary` 代替退让。

### 17.6 把 Starter 打成可执行 fat jar

Starter 是给其他项目依赖的类库，不是独立启动应用。

通常不应该对 Starter 和 AutoConfigure 模块执行 Boot `repackage`，否则产物结构不适合作为普通依赖使用。

可执行 jar 应属于最终业务应用。

---

## 十八、源码与构建排查建议

### 18.1 先看 POM，不要只看 Starter 名称

检查官方或第三方 Starter 时，优先阅读它发布的 POM：

```text
Starter POM
  -> dependencies
  -> scope
  -> optional
  -> exclusions
  -> dependencyManagement
```

Starter 名称只是约定，真正决定 classpath 的是依赖元数据。

### 18.2 查看有效 POM

```bash
mvn help:effective-pom
```

它可以帮助确认：

- Parent 和 BOM 最终提供了哪些版本。
- 插件配置如何继承。
- Profile 是否修改了依赖。
- 当前模块最终生效的构建配置。

### 18.3 查看自动配置条件报告

启动应用时开启：

```properties
debug=true
```

重点查看：

- GreetingAutoConfiguration 是否进入候选。
- `@ConditionalOnClass` 是否匹配。
- `@ConditionalOnMissingBean` 是否退让。
- 自动配置是否被排除。

也可以在安全开放 Actuator 后访问：

```text
/actuator/conditions
```

### 18.4 关键检查命令

```bash
mvn dependency:tree
mvn help:effective-pom
mvn clean package
jar tf target/your-autoconfigure.jar
```

四个命令分别回答：

```text
依赖从哪里来
最终 POM 是什么
项目能否完整构建
关键资源是否进入 jar
```

---

## 十九、必做实验与面试题

### 19.1 必做实验

1. 创建 `client + autoconfigure + starter` 三个模块。
2. 只引入 Starter，确认默认 Bean 自动创建。
3. 删除 Starter 中的 client 依赖，观察 `@ConditionalOnClass` 不匹配。
4. 用户显式声明同类型 Bean，确认默认配置退让。
5. 故意写错 imports 文件路径，观察自动配置无法发现。
6. 使用 `ApplicationContextRunner` 验证默认配置、退让和 imports 发现。
7. 创建只依赖 Starter 的 consumer 测试模块。
8. 使用 `mvn dependency:tree` 画出 Starter 依赖树。
9. 解压 AutoConfigure jar，检查 imports 和配置元数据。

### 19.2 Starter 的原理是什么？

```text
Starter 本质上通过构建工具聚合一组依赖，使相关类库和自动配置模块进入 classpath。
应用启动后，@EnableAutoConfiguration 再从 AutoConfiguration.imports 加载自动配置候选，
并根据 classpath、Bean、属性等条件注册默认 Bean。
```

### 19.3 Starter 和 AutoConfiguration 有什么区别？

```text
Starter 主要解决依赖组合问题，属于构建期能力；
AutoConfiguration 主要解决默认 Bean 装配问题，属于应用启动期能力。
两者通常配合使用，但不是同一个概念。
```

### 19.4 dependencyManagement 和 Starter 有什么区别？

```text
dependencyManagement 主要管理版本，不会自动引入依赖；
Starter 通过 dependencies 真正把一组依赖加入项目。
```

### 19.5 为什么 AutoConfigure 模块中的类库依赖常设为 optional？

```text
让 AutoConfigure 模块可以编译，但不强迫所有直接使用该模块的项目获得原始类库。
Starter 再显式决定默认要聚合哪些真实依赖。
```

### 19.6 为什么不能只测试显式加载的 AutoConfiguration？

```text
通过 AutoConfigurations.of(...) 显式传入配置类，只能验证配置逻辑；
还需要通过 @EnableAutoConfiguration 验证 imports 发现，并用独立 consumer 模块
验证 Starter POM 的传递依赖和最终发布物。
```

---

## 二十、本篇过关标准

学完本文后，需要能做到：

- 能解释 Starter 是依赖聚合模块，而不是运行时扫描器。
- 能区分 Starter、AutoConfiguration、Parent、BOM 和 Plugin。
- 能画出构建期依赖解析与运行期自动配置两条链路。
- 能解释官方 `spring-boot-starter-web` 为什么能简化依赖声明。
- 能设计 `client + autoconfigure + starter` 三模块结构。
- 能正确使用 `optional` 控制依赖传递。
- 能注册 `AutoConfiguration.imports`。
- 能使用 `@ConditionalOnClass` 和 `@ConditionalOnMissingBean` 设计默认配置。
- 能使用 `ApplicationContextRunner` 测试自动配置。
- 能通过依赖树、条件报告和 jar 内容排查 Starter 不生效问题。

---

## 二十一、下一篇建议

下一篇继续学习 Spring Boot 外部化配置与类型安全绑定：

```text
04-04-SpringBoot-配置绑定.md
```

重点包括：

- `Environment` 与 `PropertySource`。
- 配置文件加载和属性优先级。
- `@ConfigurationProperties` 绑定流程。
- 松散绑定、集合绑定和构造器绑定。
- Profile 与多环境配置。
- 配置校验和配置元数据。
