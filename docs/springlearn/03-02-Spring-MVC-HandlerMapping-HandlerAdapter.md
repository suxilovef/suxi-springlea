# Spring MVC 专题：HandlerMapping 与 HandlerAdapter 源码深度解析

> 本文是第 4 阶段 Spring MVC 的第二篇文档，承接 `03-01-Spring-MVC.md`。上一篇建立了 Spring MVC 请求处理主线；本文专门拆解两个核心组件：`HandlerMapping` 如何把请求匹配到 Controller 方法，`HandlerAdapter` 如何完成 Controller 方法调用。

---

## 一、先给结论

### 1.1 HandlerMapping 解决什么问题

`HandlerMapping` 解决的是：

```text
当前 HTTP 请求应该由谁处理？
```

对于注解 Controller，最常见的实现是：

```text
RequestMappingHandlerMapping
```

它会在容器启动阶段扫描所有 Controller，把 `@RequestMapping`、`@GetMapping`、`@PostMapping` 等注解方法注册成映射关系。

请求进来后，它根据 URL、HTTP 方法、请求参数、请求头、`Content-Type`、`Accept` 等条件，找到最匹配的 `HandlerMethod`。

### 1.2 HandlerAdapter 解决什么问题

`HandlerAdapter` 解决的是：

```text
找到 Handler 之后，应该怎么调用它？
```

对于注解 Controller，最常见的实现是：

```text
RequestMappingHandlerAdapter
```

它负责准备参数解析器、返回值处理器、数据绑定器、消息转换器，并最终调用 Controller 方法。

### 1.3 两者的关系

一句话总结：

```text
HandlerMapping 负责“找方法”
HandlerAdapter 负责“调方法”
```

它们在 `DispatcherServlet#doDispatch()` 中前后衔接：

```text
DispatcherServlet#doDispatch()
  -> getHandler(request)
      -> HandlerMapping 找到 HandlerExecutionChain
  -> getHandlerAdapter(handler)
      -> HandlerAdapter 找到能调用该 Handler 的适配器
  -> ha.handle(request, response, handler)
      -> HandlerAdapter 真正调用 Controller 方法
```

---

## 二、为什么要分成 Mapping 和 Adapter

### 2.1 如果没有 HandlerMapping

如果没有 `HandlerMapping`，`DispatcherServlet` 就必须自己知道：

- 哪些 Bean 是 Controller。
- 哪些方法能处理请求。
- URL 怎么匹配。
- HTTP 方法怎么匹配。
- `consumes` / `produces` 怎么判断。
- 多个候选方法谁优先。

这样 `DispatcherServlet` 会变得非常臃肿。

所以 Spring MVC 把“找处理器”的职责抽象成 `HandlerMapping`。

### 2.2 如果没有 HandlerAdapter

Spring MVC 支持的 Handler 不只有注解 Controller 方法，还包括：

- `HandlerMethod`
- `Controller` 接口
- `HttpRequestHandler`
- 静态资源处理器
- `RouterFunction` 等扩展形态

不同 Handler 的调用方式不同。

如果没有 `HandlerAdapter`，`DispatcherServlet` 就要写大量 `if else` 判断不同 Handler 类型。

所以 Spring MVC 使用适配器模式：

```text
DispatcherServlet 不直接关心 Handler 怎么调用
它只需要找到支持该 Handler 的 HandlerAdapter
```

### 2.3 设计价值

这种拆分带来三个好处：

- `DispatcherServlet` 保持稳定，只负责编排流程。
- 不同 Handler 类型可以通过不同 Adapter 扩展。
- 请求匹配和方法调用解耦，职责清晰。

---

## 三、最小案例：贯穿本文

### 3.1 示例 Controller

```java
@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/{id}")
    public UserDTO detail(@PathVariable Long id,
                          @RequestParam(defaultValue = "false") boolean verbose) {
        return new UserDTO(id, "Tom", verbose);
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public UserDTO create(@Valid @RequestBody CreateUserRequest request) {
        return new UserDTO(1L, request.getName(), false);
    }
}
```

### 3.2 容器启动时发生什么

启动阶段，Spring MVC 会把 Controller 方法注册成类似结构：

```text
RequestMappingInfo
  path: /users/{id}
  method: GET
  params: []
  headers: []
  consumes: []
  produces: []

HandlerMethod
  bean: userController
  method: detail(Long, boolean)
```

以及：

```text
RequestMappingInfo
  path: /users
  method: POST
  consumes: application/json
  produces: application/json

HandlerMethod
  bean: userController
  method: create(CreateUserRequest)
```

### 3.3 请求进来时发生什么

以请求为例：

```http
GET /users/100?verbose=true
Accept: application/json
```

运行期流程是：

```text
DispatcherServlet#doDispatch()
  -> RequestMappingHandlerMapping 根据请求找到 UserController#detail
  -> 返回 HandlerExecutionChain
  -> DispatcherServlet 找到 RequestMappingHandlerAdapter
  -> RequestMappingHandlerAdapter 解析 id、verbose
  -> 调用 UserController#detail()
  -> 处理返回值并写 JSON
```

---

## 四、HandlerMapping 类型体系

### 4.1 核心接口

`HandlerMapping` 的核心方法可以简化理解为：

```java
public interface HandlerMapping {

    HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception;
}
```

它输入一个请求，输出一个 `HandlerExecutionChain`。

```text
HttpServletRequest
  -> HandlerMapping
  -> HandlerExecutionChain
```

### 4.2 HandlerExecutionChain 是什么

`HandlerExecutionChain` 不是单纯的 Handler，它包含：

- Handler 本身。
- 当前请求匹配到的拦截器链。

可以理解为：

```text
HandlerExecutionChain = Handler + HandlerInterceptor[]
```

这也是为什么拦截器是由 `HandlerMapping` 参与组织的：因为不同 URL 可能匹配不同拦截器。

### 4.3 常见 HandlerMapping 实现

| HandlerMapping | 作用 |
|---|---|
| `RequestMappingHandlerMapping` | 处理 `@RequestMapping` 注解方法 |
| `BeanNameUrlHandlerMapping` | 根据 Bean 名称中的 URL 映射 Handler |
| `RouterFunctionMapping` | 处理函数式路由 |
| `SimpleUrlHandlerMapping` | 处理简单 URL 映射，常见于静态资源 |
| `WelcomePageHandlerMapping` | Spring Boot 欢迎页映射 |

学习 Spring MVC 注解开发时，重点看：

```text
RequestMappingHandlerMapping
```

---

## 五、RequestMappingHandlerMapping 启动注册流程

### 5.1 它什么时候开始扫描 Controller

`RequestMappingHandlerMapping` 在 Bean 初始化阶段扫描 Controller。

典型调用链：

```text
AbstractApplicationContext.refresh()
  -> finishBeanFactoryInitialization()
    -> 创建 RequestMappingHandlerMapping
      -> afterPropertiesSet()
        -> initHandlerMethods()
          -> detectHandlerMethods(beanName)
            -> getMappingForMethod(method, userType)
            -> registerHandlerMethod(handler, method, mapping)
```

这里要注意：

```text
Controller 方法映射是在启动阶段提前注册的，不是请求来了才扫描的。
```

### 5.2 isHandler()：判断哪些 Bean 是 Handler

`RequestMappingHandlerMapping` 会判断某个 Bean 是否是 Handler。

常见规则是类上有：

- `@Controller`
- `@RequestMapping`

`@RestController` 本身组合了 `@Controller` 和 `@ResponseBody`，所以也会被识别。

示意：

```java
@Override
protected boolean isHandler(Class<?> beanType) {
    return AnnotatedElementUtils.hasAnnotation(beanType, Controller.class);
}
```

实际源码会考虑组合注解、桥接方法、代理类等情况，但学习阶段先抓住主线即可。

### 5.3 getMappingForMethod()：提取方法映射

对于每个候选 Controller 方法，Spring MVC 会尝试提取 `RequestMappingInfo`。

示例：

```java
@GetMapping("/{id}")
public UserDTO detail(@PathVariable Long id) { ... }
```

会被解析成：

```text
RequestMappingInfo
  path: /users/{id}
  method: GET
```

类上的：

```java
@RequestMapping("/users")
```

和方法上的：

```java
@GetMapping("/{id}")
```

会合并成：

```text
/users/{id}
```

### 5.4 registerHandlerMethod()：注册映射关系

注册的核心关系可以理解为：

```text
RequestMappingInfo -> HandlerMethod
```

内部会放入映射注册表中。

简化结构：

```text
MappingRegistry
  registry: Map<RequestMappingInfo, MappingRegistration>
  pathLookup: Map<String, List<RequestMappingInfo>>
  nameLookup: Map<String, List<HandlerMethod>>
  corsLookup: Map<HandlerMethod, CorsConfiguration>
```

重点记住：

```text
RequestMappingInfo 是匹配条件
HandlerMethod 是最终要调用的 Controller 方法
```

---

## 六、RequestMappingInfo：请求匹配条件模型

### 6.1 它是什么

`RequestMappingInfo` 是 Spring MVC 对请求映射条件的封装。

它不是单纯保存 URL，而是保存一组匹配条件。

常见条件包括：

- 路径条件。
- HTTP 方法条件。
- 请求参数条件。
- 请求头条件。
- `consumes` 条件。
- `produces` 条件。
- 自定义条件。

### 6.2 常见条件示例

```java
@PostMapping(
    value = "/users",
    params = "source=admin",
    headers = "X-Client=web",
    consumes = "application/json",
    produces = "application/json"
)
public UserDTO create(@RequestBody CreateUserRequest request) { ... }
```

对应条件可以理解为：

```text
path: /users
method: POST
params: source=admin
headers: X-Client=web
consumes: application/json
produces: application/json
```

只有这些条件都满足，才算匹配。

### 6.3 条件匹配不是只看 URL

很多问题表面是 404，实际是映射条件没匹配上。

例如：

```java
@PostMapping(value = "/users", consumes = "application/json")
```

如果客户端请求：

```http
POST /users
Content-Type: text/plain
```

即使 URL 和 HTTP 方法都对，也可能匹配失败或抛出媒体类型不支持异常。

---

## 七、请求匹配运行流程

### 7.1 getHandlerInternal()

请求进入后，`DispatcherServlet` 会调用：

```text
HandlerMapping#getHandler(request)
```

对于注解 Controller，最终会进入：

```text
AbstractHandlerMethodMapping#getHandlerInternal()
```

简化流程：

```text
1. 从 request 中解析 lookupPath
2. 根据路径快速找到候选 RequestMappingInfo
3. 对候选 mapping 执行 getMatchingCondition()
4. 得到所有匹配项
5. 按优先级排序
6. 选择最佳匹配 HandlerMethod
7. 暴露 URI_TEMPLATE_VARIABLES 等请求属性
```

### 7.2 getMatchingCondition()

每个 `RequestMappingInfo` 会判断自己是否匹配当前请求。

可以理解为：

```text
RequestMappingInfo.getMatchingCondition(request)
  -> path 是否匹配
  -> method 是否匹配
  -> params 是否匹配
  -> headers 是否匹配
  -> consumes 是否匹配
  -> produces 是否匹配
```

如果不匹配，返回 `null`。

如果匹配，返回一个针对当前请求裁剪后的 `RequestMappingInfo`。

### 7.3 最佳匹配规则

如果多个 Controller 方法都能匹配同一个请求，Spring MVC 会选择最具体的那个。

常见优先级理解：

- 路径越具体优先级越高。
- HTTP 方法明确的优先级更高。
- `params` / `headers` 条件更具体的优先级更高。
- `consumes` / `produces` 更匹配请求头的优先级更高。

示例：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id) { ... }

@GetMapping("/users/me")
public UserDTO current() { ... }
```

请求：

```text
GET /users/me
```

通常会优先匹配：

```text
/users/me
```

而不是：

```text
/users/{id}
```

因为固定路径比路径变量更具体。

### 7.4 匹配冲突

如果两个方法映射条件完全一样，启动时就可能报错。

典型错误：

```text
Ambiguous mapping. Cannot map ... There is already ... mapped.
```

示例：

```java
@GetMapping("/users/{id}")
public UserDTO detail1(@PathVariable Long id) { ... }

@GetMapping("/users/{userId}")
public UserDTO detail2(@PathVariable Long userId) { ... }
```

这两个路径模板对 Spring MVC 来说可能是同一类映射，容易产生歧义。

---

## 八、路径匹配策略：AntPathMatcher 与 PathPatternParser

### 8.1 为什么要关注路径匹配策略

不同 Spring / Spring Boot 版本中，路径匹配策略可能不同。

常见两类：

```text
AntPathMatcher
PathPatternParser
```

如果你调试源码时发现教程里的断点或类名和项目不一致，路径匹配策略就是一个常见原因。

### 8.2 AntPathMatcher

`AntPathMatcher` 是老项目中很常见的路径匹配实现。

常见模式：

```text
/users/*
/users/**
/users/{id}
```

特点：

- 使用字符串模式匹配。
- 历史悠久，兼容性强。
- 老项目中很常见。

### 8.3 PathPatternParser

`PathPatternParser` 是新版本中更推荐的路径匹配方式。

特点：

- 启动时解析路径模式。
- 运行期匹配效率更高。
- 对一些复杂路径模式的语义更严格。

### 8.4 学习建议

学习源码时重点不要背配置，而是记住：

```text
URL 匹配不是简单字符串 equals
Spring MVC 会把路径模式解析成匹配条件，再参与最佳匹配排序
```

如果项目中存在路径匹配差异，优先检查：

```properties
spring.mvc.pathmatch.matching-strategy
```

---

## 九、HandlerAdapter 类型体系

### 9.1 核心接口

`HandlerAdapter` 的核心方法可以简化理解为：

```java
public interface HandlerAdapter {

    boolean supports(Object handler);

    ModelAndView handle(HttpServletRequest request,
                        HttpServletResponse response,
                        Object handler) throws Exception;
}
```

其中：

- `supports()` 判断是否支持当前 Handler。
- `handle()` 真正调用 Handler。

### 9.2 常见 HandlerAdapter 实现

| HandlerAdapter | 作用 |
|---|---|
| `RequestMappingHandlerAdapter` | 调用注解 Controller 方法 |
| `HttpRequestHandlerAdapter` | 调用 `HttpRequestHandler` |
| `SimpleControllerHandlerAdapter` | 调用老式 `Controller` 接口 |
| `HandlerFunctionAdapter` | 调用函数式 Handler |

学习注解 Controller 时，重点看：

```text
RequestMappingHandlerAdapter
```

### 9.3 DispatcherServlet 如何选择 Adapter

`DispatcherServlet` 会遍历所有 `HandlerAdapter`：

```text
for HandlerAdapter adapter in handlerAdapters:
    if adapter.supports(handler):
        return adapter
```

对于 `HandlerMethod`，通常会选择：

```text
RequestMappingHandlerAdapter
```

---

## 十、RequestMappingHandlerAdapter 初始化流程

### 10.1 它准备了哪些组件

`RequestMappingHandlerAdapter` 初始化时，会准备很多策略组件。

核心包括：

- `HandlerMethodArgumentResolver` 列表。
- `HandlerMethodReturnValueHandler` 列表。
- `HttpMessageConverter` 列表。
- `WebDataBinderFactory`。
- `ModelFactory`。
- `SessionAttributesHandler`。
- `ControllerAdviceBean` 相关缓存。

### 10.2 afterPropertiesSet()

典型初始化入口：

```text
RequestMappingHandlerAdapter#afterPropertiesSet()
```

简化流程：

```text
1. 初始化 @ControllerAdvice 相关缓存
2. 初始化参数解析器 argumentResolvers
3. 初始化 @InitBinder 参数解析器
4. 初始化返回值处理器 returnValueHandlers
5. 初始化消息转换器 messageConverters
```

这里要重点关注两条链：

```text
参数解析器链：把 HTTP 请求变成 Java 方法参数
返回值处理器链：把 Java 返回值变成 HTTP 响应
```

---

## 十一、Controller 方法调用主流程

### 11.1 handleInternal()

注解 Controller 的调用主流程会进入：

```text
RequestMappingHandlerAdapter#handleInternal()
```

再进入：

```text
RequestMappingHandlerAdapter#invokeHandlerMethod()
```

### 11.2 invokeHandlerMethod()

核心流程可以简化为：

```text
1. 创建 ServletInvocableHandlerMethod
2. 设置 argumentResolvers
3. 设置 returnValueHandlers
4. 创建 WebDataBinderFactory
5. 创建 ModelFactory
6. 调用 invokeAndHandle()
```

### 11.3 ServletInvocableHandlerMethod

`ServletInvocableHandlerMethod` 是真正负责调用 Controller 方法并处理返回值的对象。

主流程：

```text
ServletInvocableHandlerMethod#invokeAndHandle()
  -> InvocableHandlerMethod#invokeForRequest()
      -> getMethodArgumentValues()
      -> doInvoke()
        -> Method.invoke()
  -> HandlerMethodReturnValueHandlerComposite#handleReturnValue()
```

可以理解为：

```text
先解析参数
再反射调用方法
最后处理返回值
```

---

## 十二、Adapter 调用边界：参数与返回值只看入口

`RequestMappingHandlerAdapter` 不只是反射调用 Controller，它会先准备参数解析器、返回值处理器、数据绑定器和消息转换器。

本文只需要记住调用入口：

```text
RequestMappingHandlerAdapter#invokeHandlerMethod()
  -> ServletInvocableHandlerMethod#invokeAndHandle()
    -> InvocableHandlerMethod#invokeForRequest()
      -> HandlerMethodArgumentResolverComposite 解析参数
      -> InvocableHandlerMethod#doInvoke() 调用 Controller
    -> HandlerMethodReturnValueHandlerComposite 处理返回值
```

详细的 `@PathVariable`、`@RequestParam`、`@RequestBody`、`@ResponseBody`、`ResponseEntity`、`HttpMessageConverter` 机制，统一放到下一篇 `03-03-Spring-MVC-ArgumentResolver-ReturnValueHandler.md`，避免本篇和后文重复。

## 十三、HandlerMapping 和 Interceptor 的关系

### 14.1 拦截器为什么挂在 HandlerExecutionChain 上

拦截器不是全局无差别执行的，它通常可以配置路径匹配规则。

例如：

```java
registry.addInterceptor(new LoginInterceptor())
        .addPathPatterns("/admin/**")
        .excludePathPatterns("/admin/login");
```

所以 Spring MVC 在找到 Handler 时，也会顺便找出当前请求匹配的拦截器。

结果就是：

```text
HandlerExecutionChain
  handler: HandlerMethod
  interceptors: [LoginInterceptor, TraceInterceptor]
```

### 14.2 执行顺序

拦截器执行顺序大致是：

```text
preHandle 正序执行
Controller 方法执行
postHandle 倒序执行
afterCompletion 倒序执行
```

如果某个 `preHandle()` 返回 `false`：

```text
后续拦截器和 Controller 不再执行
已经执行过 preHandle 且返回 true 的拦截器会触发 afterCompletion
```

如果 Controller 抛异常：

```text
postHandle 通常不执行
afterCompletion 仍会执行
异常会进入 HandlerExceptionResolver 流程
```

---

## 十四、常见问题排查

### 15.1 Controller 启动时报 Ambiguous mapping

典型原因：

- 两个方法 URL 和 HTTP 方法完全一样。
- 路径变量名称不同但路径结构相同。
- 父类和子类重复声明映射。
- 多个 Controller 扫描到了同一路径。

排查重点：

```text
搜索报错中的 URL
检查所有 @RequestMapping / @GetMapping / @PostMapping
确认是否存在重复映射
```

### 15.2 请求 404

重点排查：

- Controller 是否被扫描。
- 类路径和方法路径拼接是否正确。
- 请求路径是否受 `context-path` 或 `servlet-path` 影响。
- HTTP 方法是否正确。
- 是否被静态资源处理或网关路径改写影响。

### 15.3 请求 405

说明路径可能匹配到了，但 HTTP 方法不支持。

例如：

```java
@PostMapping("/users")
```

却请求：

```text
GET /users
```

### 15.4 请求 415

通常和 `Content-Type` / `consumes` 有关。

例如：

```java
@PostMapping(value = "/users", consumes = "application/json")
```

但请求头是：

```http
Content-Type: text/plain
```

### 15.5 请求 406

通常和 `Accept` / `produces` 有关。

例如：

```java
@GetMapping(value = "/users/{id}", produces = "application/json")
```

但客户端只接受：

```http
Accept: application/xml
```

---

## 十五、源码断点建议

### 16.1 HandlerMapping 注册断点

建议断点：

```text
RequestMappingHandlerMapping#afterPropertiesSet
AbstractHandlerMethodMapping#initHandlerMethods
AbstractHandlerMethodMapping#detectHandlerMethods
RequestMappingHandlerMapping#getMappingForMethod
AbstractHandlerMethodMapping#registerHandlerMethod
```

观察：

- 哪些 Bean 被认为是 Handler。
- 哪些方法被解析成 `RequestMappingInfo`。
- 类级别和方法级别路径如何合并。
- 映射关系何时注册进 `MappingRegistry`。

### 16.2 HandlerMapping 请求匹配断点

建议断点：

```text
DispatcherServlet#getHandler
AbstractHandlerMapping#getHandler
AbstractHandlerMethodMapping#getHandlerInternal
AbstractHandlerMethodMapping#lookupHandlerMethod
RequestMappingInfo#getMatchingCondition
```

观察：

- 请求路径如何解析。
- 候选 Mapping 有哪些。
- 哪些 Mapping 被过滤掉。
- 最终选择哪个 `HandlerMethod`。

### 16.3 HandlerAdapter 选择断点

建议断点：

```text
DispatcherServlet#getHandlerAdapter
RequestMappingHandlerAdapter#supportsInternal
```

观察：

- 当前 Handler 类型是什么。
- 为什么选择 `RequestMappingHandlerAdapter`。

### 16.4 HandlerAdapter 调用断点

建议断点：

```text
RequestMappingHandlerAdapter#handleInternal
RequestMappingHandlerAdapter#invokeHandlerMethod
ServletInvocableHandlerMethod#invokeAndHandle
InvocableHandlerMethod#invokeForRequest
InvocableHandlerMethod#doInvoke
```

观察：

- 参数什么时候解析。
- Controller 方法什么时候真正被反射调用。
- 返回值什么时候被处理。

---

## 十六、必做实验

### 17.1 实验一：观察启动时映射注册

目标：确认 Controller 方法映射是在启动阶段注册的。

步骤：

1. 写一个 `UserController`。
2. 在 `AbstractHandlerMethodMapping#registerHandlerMethod` 打断点。
3. 启动应用。
4. 观察 `RequestMappingInfo` 和 `HandlerMethod`。

过关标准：

```text
能说明 URL 到 Controller 方法的映射不是请求来了才创建的。
```

### 17.2 实验二：观察请求匹配过程

目标：理解一次请求如何匹配到具体方法。

步骤：

1. 请求 `GET /users/100`。
2. 在 `lookupHandlerMethod()` 打断点。
3. 观察候选 Mapping。
4. 观察最佳匹配结果。

过关标准：

```text
能说明路径、HTTP 方法、params、headers、consumes、produces 都可能参与匹配。
```

### 17.3 实验三：制造 Ambiguous mapping

目标：理解重复映射为什么启动失败。

示例：

```java
@GetMapping("/users/{id}")
public UserDTO detail1(@PathVariable Long id) { ... }

@GetMapping("/users/{userId}")
public UserDTO detail2(@PathVariable Long userId) { ... }
```

观察：

- 应用是否启动失败。
- 报错中显示哪两个方法冲突。

### 17.4 实验四：观察 HandlerAdapter 调用过程

目标：理解 HandlerAdapter 如何调用 Controller。

步骤：

1. 请求一个 Controller 接口。
2. 在 `RequestMappingHandlerAdapter#invokeHandlerMethod` 打断点。
3. 观察 `ServletInvocableHandlerMethod`。
4. 继续进入 `InvocableHandlerMethod#doInvoke`。

过关标准：

```text
能说明 HandlerAdapter 不是只做反射调用，还负责参数解析、绑定、校验、返回值处理。
```

### 17.5 实验五：制造 415 / 406

目标：理解 `consumes` / `produces` 的影响。

步骤：

1. Controller 声明 `consumes = application/json`。
2. 使用 `Content-Type: text/plain` 请求。
3. 观察是否出现 415。
4. Controller 声明 `produces = application/json`。
5. 使用 `Accept: application/xml` 请求。
6. 观察是否出现 406。

---

## 十七、面试常问问题

### 18.1 HandlerMapping 和 HandlerAdapter 的区别？

推荐回答：

```text
HandlerMapping 负责根据请求找到处理器，解决“谁来处理”的问题；
HandlerAdapter 负责调用处理器，解决“怎么调用”的问题。对于注解 Controller，
常见组合是 RequestMappingHandlerMapping 找到 HandlerMethod，
RequestMappingHandlerAdapter 负责解析参数、调用方法并处理返回值。
```

### 18.2 Controller 方法映射什么时候注册？

推荐回答：

```text
Controller 方法映射在容器启动阶段注册。RequestMappingHandlerMapping 初始化时会扫描
Controller Bean，解析 @RequestMapping 系列注解，生成 RequestMappingInfo 和 HandlerMethod
的映射关系，存入 MappingRegistry。请求来了之后直接基于这些映射做匹配，不会重新扫描。
```

### 18.3 RequestMappingInfo 是什么？

推荐回答：

```text
RequestMappingInfo 是 Spring MVC 对请求匹配条件的封装，不只是 URL，
还包括 HTTP 方法、params、headers、consumes、produces、自定义条件等。
请求匹配时这些条件都会参与判断。
```

### 18.4 为什么会出现 Ambiguous mapping？

推荐回答：

```text
因为两个或多个 Controller 方法注册出了相同或无法区分的 RequestMappingInfo，
Spring MVC 无法确定某个请求应该由谁处理，所以通常在启动阶段直接报错。
常见原因是路径和 HTTP 方法重复，或者路径变量名不同但路径结构相同。
```

### 18.5 HandlerAdapter 如何调用 Controller 方法？

推荐回答：

```text
RequestMappingHandlerAdapter 会把 HandlerMethod 包装成 ServletInvocableHandlerMethod，
先通过 HandlerMethodArgumentResolverComposite 解析所有方法参数，
再通过 InvocableHandlerMethod#doInvoke 反射调用 Controller 方法，
最后通过 HandlerMethodReturnValueHandlerComposite 处理返回值。
```

### 18.6 404、405、415、406 分别常见原因是什么？

推荐回答：

```text
404 通常是没有匹配到 Handler；405 通常是路径匹配到了但 HTTP 方法不支持；
415 通常是请求 Content-Type 与 consumes 不匹配；406 通常是客户端 Accept 与
Controller produces 不匹配。
```

---

## 十八、本篇过关标准

学完本文后，需要能做到：

- 能说明 `HandlerMapping` 和 `HandlerAdapter` 的职责区别。
- 能说出 `RequestMappingHandlerMapping` 启动时如何注册 Controller 方法。
- 能解释 `RequestMappingInfo` 包含哪些匹配条件。
- 能说明请求来了之后如何选择最佳匹配 `HandlerMethod`。
- 能解释 `HandlerExecutionChain` 为什么包含拦截器。
- 能说明 `RequestMappingHandlerAdapter` 如何调用 Controller 方法。
- 能根据 404、405、415、406 快速定位是映射问题、方法问题还是媒体类型问题。
- 能在源码里跟踪从 `getHandler()` 到 `invokeHandlerMethod()` 的完整流程。

---

## 十九、下一篇建议

下一篇建议继续深入参数解析和返回值处理：

```text
03-03-Spring-MVC-ArgumentResolver-ReturnValueHandler.md
```

重点拆解：

- `HandlerMethodArgumentResolverComposite` 如何选择参数解析器。
- `@PathVariable`、`@RequestParam`、`@RequestBody` 的解析差异。
- `WebDataBinder`、类型转换、参数校验如何协作。
- `HandlerMethodReturnValueHandlerComposite` 如何处理不同返回值。
- `HttpMessageConverter` 如何参与请求体读取和响应体写出。