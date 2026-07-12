# Spring MVC 知识点（请求处理主线源码解析）

> 本文是第 4 阶段的第一篇文档，目标是先建立 Spring MVC 的整体认知：一个 HTTP 请求进入应用后，如何经过 `DispatcherServlet`、`HandlerMapping`、`HandlerAdapter`，最终调用到 Controller 方法，并完成参数解析、参数校验、返回值处理、异常处理和视图/JSON 响应。本文讨论的是 Servlet Stack 下的 Spring MVC，不展开 WebFlux。

---

## 一、先建立主线：Spring MVC 解决什么问题

### 1.1 Web 开发的核心问题

一个 Web 框架本质上要解决几类问题：

- 请求从哪里进入应用。
- URL 如何匹配到具体处理方法。
- 请求参数如何转换成 Java 方法参数。
- Controller 返回值如何转换成 HTTP 响应。
- 参数校验失败如何转换成统一错误响应。
- 业务异常如何统一处理。
- 拦截器、过滤器、跨域、静态资源等横切能力如何接入。

Spring MVC 对这些问题的回答是：

```text
HTTP 请求
  -> Servlet 容器
  -> DispatcherServlet
  -> HandlerMapping 找 Handler
  -> HandlerAdapter 调 Handler
  -> ArgumentResolver 解析参数
  -> Controller 方法执行
  -> ReturnValueHandler 处理返回值
  -> HttpMessageConverter / ViewResolver 输出响应
  -> ExceptionResolver 处理异常
```

### 1.2 Spring MVC 的核心思想

Spring MVC 的核心不是 Controller 注解本身，而是围绕 `DispatcherServlet` 建立的一套请求分发体系。

它的关键设计思想是：

- **统一入口**：所有请求先进入 `DispatcherServlet`。
- **策略扩展**：请求匹配、方法调用、参数解析、返回值处理、异常处理都通过策略接口完成。
- **注解驱动**：`@Controller`、`@RequestMapping`、`@RequestBody`、`@ResponseBody` 等注解只是上层使用方式。
- **容器协作**：Controller、HandlerMapping、HandlerAdapter、ExceptionResolver 等都由 Spring 容器管理。

### 1.3 Spring MVC 和 Servlet 的关系

Spring MVC 建立在 Servlet API 之上。

```text
Tomcat / Jetty / Undertow
  -> Servlet 规范
  -> HttpServletRequest / HttpServletResponse
  -> DispatcherServlet
  -> Spring MVC 组件体系
```

`DispatcherServlet` 本身就是一个 Servlet，它继承自 `FrameworkServlet`，最终继承自 `HttpServlet`。

常见继承链如下：

```text
HttpServlet
  -> HttpServletBean
    -> FrameworkServlet
      -> DispatcherServlet
```

所以要理解 Spring MVC，必须抓住两条线：

- Servlet 容器如何把请求交给 `DispatcherServlet`。
- `DispatcherServlet` 如何把请求分发给 Controller。

---

## 二、最小案例：用一个请求贯穿全文

### 2.1 示例 Controller

```java
@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/{id}")
    public UserDTO detail(@PathVariable Long id,
                          @RequestParam(defaultValue = "false") boolean verbose) {
        return new UserDTO(id, "Tom", verbose);
    }

    @PostMapping
    public UserDTO create(@RequestBody CreateUserRequest request) {
        return new UserDTO(1L, request.getName(), false);
    }
}
```

### 2.2 关注的问题

以请求 `GET /users/1?verbose=true` 为例，需要弄清楚：

- 谁接收到这个请求？
- `/users/1` 如何匹配到 `detail()` 方法？
- `id` 和 `verbose` 如何被解析出来？
- `UserDTO` 如何变成 JSON？
- 如果 Controller 抛异常，谁负责兜底？

这些问题分别对应 Spring MVC 的核心组件。

---

## 三、核心组件总览

### 3.1 DispatcherServlet：请求总入口

`DispatcherServlet` 是 Spring MVC 的前端控制器。

它负责：

- 接收所有匹配到的 HTTP 请求。
- 查找能够处理请求的 Handler。
- 找到支持该 Handler 的 HandlerAdapter。
- 调用 HandlerAdapter 执行 Controller 方法。
- 处理返回值、视图渲染、异常处理。

它不直接执行业务逻辑，而是调度其他组件完成工作。

一句话：

```text
DispatcherServlet 不负责具体业务，只负责请求分发和流程编排。
```

### 3.2 HandlerMapping：找谁处理请求

`HandlerMapping` 负责根据请求找到处理器。

最常见实现是：

```text
RequestMappingHandlerMapping
```

它负责解析：

- `@Controller`
- `@RestController`
- `@RequestMapping`
- `@GetMapping`
- `@PostMapping`
- `@PutMapping`
- `@DeleteMapping`

并把 URL、HTTP 方法、请求参数、请求头等条件映射到具体 Controller 方法。

例如：

```text
GET /users/1
  -> UserController#detail(Long id, boolean verbose)
```

### 3.3 HandlerAdapter：怎么调用 Handler

`HandlerMapping` 只负责找 Handler，但不负责调用。

`HandlerAdapter` 负责真正调用 Handler。

最常见实现是：

```text
RequestMappingHandlerAdapter
```

它负责：

- 解析 Controller 方法参数。
- 调用 Controller 方法。
- 处理 Controller 返回值。

为什么需要 HandlerAdapter？

因为 Spring MVC 支持多种 Handler 形态，不只有 `@RequestMapping` 方法。

例如：

- 注解 Controller 方法。
- 实现 `Controller` 接口的老式 Controller。
- `HttpRequestHandler`。
- 静态资源处理器。

不同 Handler 的调用方式不同，所以需要适配器模式。

### 3.4 HandlerMethod：Controller 方法的运行时模型

在注解驱动 Spring MVC 中，一个 Controller 方法会被封装为 `HandlerMethod`。

它包含：

- Controller Bean。
- Java Method。
- 方法参数信息。
- 注解元数据。
- BeanFactory 上下文。

可以理解为：

```text
HandlerMethod = Controller Bean + Method + 注解元数据
```

后续参数解析、返回值处理、异常处理，都围绕 `HandlerMethod` 展开。

### 3.5 HandlerExecutionChain：Handler + Interceptor

`HandlerMapping` 返回的通常不是单独的 Handler，而是：

```text
HandlerExecutionChain
```

它包含：

- Handler 本身。
- 匹配到的多个 `HandlerInterceptor`。

请求执行时顺序大致如下：

```text
interceptor.preHandle()
  -> Controller 方法
  -> interceptor.postHandle()
  -> 渲染视图或写 JSON
  -> interceptor.afterCompletion()
```

需要注意：如果某个 `preHandle()` 返回 `false`，后续 Controller 不会执行；如果 Controller 抛出异常，`postHandle()` 通常不会执行，但已经成功执行过 `preHandle()` 的拦截器仍会进入 `afterCompletion()`。

---

## 四、DispatcherServlet 请求处理主流程

### 4.1 核心入口：doDispatch()

Spring MVC 请求处理主流程集中在：

```text
DispatcherServlet#doDispatch(HttpServletRequest request, HttpServletResponse response)
```

简化后的主流程如下：

```java
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
    HandlerExecutionChain mappedHandler = getHandler(request);

    HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());

    if (!mappedHandler.applyPreHandle(request, response)) {
        return;
    }

    ModelAndView mv = ha.handle(request, response, mappedHandler.getHandler());

    mappedHandler.applyPostHandle(request, response, mv);

    processDispatchResult(request, response, mappedHandler, mv, dispatchException);
}
```

重点记住五步：

```text
1. getHandler() 找处理器
2. getHandlerAdapter() 找适配器
3. preHandle() 执行拦截器前置逻辑
4. ha.handle() 调用 Controller
5. processDispatchResult() 处理结果或异常
```

### 4.2 完整调用链

一次典型 JSON 请求调用链如下：

```text
HttpServlet.service()
  -> FrameworkServlet.service()
    -> FrameworkServlet.processRequest()
      -> DispatcherServlet.doService()
        -> DispatcherServlet.doDispatch()
          -> DispatcherServlet.getHandler()
            -> RequestMappingHandlerMapping.getHandlerInternal()
          -> DispatcherServlet.getHandlerAdapter()
            -> RequestMappingHandlerAdapter.supports()
          -> HandlerExecutionChain.applyPreHandle()
          -> RequestMappingHandlerAdapter.handle()
            -> RequestMappingHandlerAdapter.handleInternal()
              -> RequestMappingHandlerAdapter.invokeHandlerMethod()
                -> ServletInvocableHandlerMethod.invokeAndHandle()
                  -> InvocableHandlerMethod.invokeForRequest()
                    -> HandlerMethodArgumentResolverComposite.resolveArgument()
                    -> InvocableHandlerMethod.doInvoke()
                      -> Method.invoke()
                  -> HandlerMethodReturnValueHandlerComposite.handleReturnValue()
                    -> RequestResponseBodyMethodProcessor.handleReturnValue()
                      -> HttpMessageConverter.write()
          -> HandlerExecutionChain.applyPostHandle()
          -> DispatcherServlet.processDispatchResult()
          -> HandlerExecutionChain.triggerAfterCompletion()
```

这条链路是学习 Spring MVC 源码的主线。

---

## 五、HandlerMapping：请求如何找到 Controller 方法

### 5.1 RequestMappingHandlerMapping 的作用

`RequestMappingHandlerMapping` 会在容器启动阶段扫描 Controller Bean。

它做两件事：

- 找出带有 `@RequestMapping` 系列注解的方法。
- 把请求条件和 `HandlerMethod` 注册到映射表中。

例如：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id) { ... }
```

会注册为类似映射：

```text
RequestMappingInfo
  path: /users/{id}
  method: GET
  produces: */*
  consumes: */*
  params: []
  headers: []

HandlerMethod
  bean: userController
  method: detail(Long)
```

### 5.2 映射注册时机

核心注册时机在容器启动期间。

典型调用链：

```text
AbstractApplicationContext.refresh()
  -> finishBeanFactoryInitialization()
    -> 创建 RequestMappingHandlerMapping
    -> afterPropertiesSet()
      -> initHandlerMethods()
        -> detectHandlerMethods()
          -> getMappingForMethod()
          -> registerHandlerMethod()
```

这说明 Controller 方法映射不是请求来了才扫描，而是在启动时提前建立好。

### 5.3 请求匹配规则

一次请求是否能匹配某个 Controller 方法，通常看这些条件：

- URL 路径是否匹配。
- HTTP 方法是否匹配。
- `params` 条件是否匹配。
- `headers` 条件是否匹配。
- `consumes` 是否匹配请求 `Content-Type`。
- `produces` 是否匹配客户端 `Accept`。

示例：

```java
@PostMapping(value = "/users", consumes = "application/json", produces = "application/json")
public UserDTO create(@RequestBody CreateUserRequest request) { ... }
```

意味着：

```text
必须是 POST /users
请求体必须是 application/json
响应最好能产出 application/json
```

### 5.4 常见匹配失败原因

常见 404 或 405 问题通常来自：

- URL 路径写错。
- HTTP 方法不匹配。
- Controller 没有被 Spring 扫描到。
- 类上和方法上的 `@RequestMapping` 拼接后路径不符合预期。
- `consumes` / `produces` 条件不匹配。
- 应用配置了 `context-path` 或 Servlet path。

---

## 六、HandlerAdapter：Controller 方法如何被调用

### 6.1 RequestMappingHandlerAdapter 的职责

`RequestMappingHandlerAdapter` 是注解 Controller 的核心执行器。

它主要负责：

- 准备参数解析器列表。
- 准备返回值处理器列表。
- 准备数据绑定器。
- 准备消息转换器。
- 调用 `HandlerMethod`。

一句话：

```text
HandlerMapping 负责“找到方法”，HandlerAdapter 负责“调用方法”。
```

### 6.2 invokeHandlerMethod() 主流程

核心方法：

```text
RequestMappingHandlerAdapter#invokeHandlerMethod()
```

简化流程：

```text
1. 创建 ServletInvocableHandlerMethod
2. 设置 argumentResolvers
3. 设置 returnValueHandlers
4. 创建 WebDataBinderFactory
5. 创建 ModelFactory
6. 执行 invokeAndHandle()
```

这一步之后，请求开始真正进入 Controller 方法调用阶段。

---

## 七、参数解析：请求参数如何变成 Java 参数

### 7.1 HandlerMethodArgumentResolver

Controller 方法参数由 `HandlerMethodArgumentResolver` 解析。

核心接口：

```java
public interface HandlerMethodArgumentResolver {

    boolean supportsParameter(MethodParameter parameter);

    Object resolveArgument(MethodParameter parameter,
                           ModelAndViewContainer mavContainer,
                           NativeWebRequest webRequest,
                           WebDataBinderFactory binderFactory) throws Exception;
}
```

它采用责任链模式：

```text
遍历所有 ArgumentResolver
  -> 找到 supportsParameter() 返回 true 的解析器
  -> 调用 resolveArgument() 得到参数值
```

### 7.2 常见参数解析器

| 注解 / 参数类型 | 典型解析器 | 作用 |
|---|---|---|
| `@PathVariable` | `PathVariableMethodArgumentResolver` | 解析路径变量 |
| `@RequestParam` | `RequestParamMethodArgumentResolver` | 解析查询参数或表单参数 |
| `@RequestBody` | `RequestResponseBodyMethodProcessor` | 读取请求体并反序列化 |
| `@RequestHeader` | `RequestHeaderMethodArgumentResolver` | 解析请求头 |
| `@CookieValue` | `ServletCookieValueMethodArgumentResolver` | 解析 Cookie |
| `Model` | `ModelMethodProcessor` | 处理模型数据 |
| `HttpServletRequest` | `ServletRequestMethodArgumentResolver` | 注入原生请求对象 |
| `HttpServletResponse` | `ServletResponseMethodArgumentResolver` | 注入原生响应对象 |

### 7.3 @PathVariable 解析

示例：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id) { ... }
```

解析过程：

```text
/users/100
  -> URI 模板匹配 {id}
  -> 提取字符串 "100"
  -> 类型转换成 Long
  -> 赋值给方法参数 id
```

### 7.4 @RequestParam 解析

示例：

```java
@GetMapping("/users")
public List<UserDTO> list(@RequestParam(defaultValue = "1") Integer pageNo) { ... }
```

请求：

```text
GET /users?pageNo=2
```

解析结果：

```text
pageNo = 2
```

如果请求没有传 `pageNo`，则使用默认值 `1`。

### 7.5 @RequestBody 解析

示例：

```java
@PostMapping("/users")
public UserDTO create(@RequestBody CreateUserRequest request) { ... }
```

解析过程：

```text
读取 HTTP request body
  -> 根据 Content-Type 选择 HttpMessageConverter
  -> Jackson 反序列化 JSON
  -> 创建 CreateUserRequest 对象
```

常见 JSON 请求体：

```json
{
  "name": "Tom",
  "age": 18
}
```

常见处理器是：

```text
RequestResponseBodyMethodProcessor
```

底层常见转换器是：

```text
MappingJackson2HttpMessageConverter
```

---

## 八、返回值处理：Java 对象如何变成 HTTP 响应

### 8.1 HandlerMethodReturnValueHandler

Controller 返回值由 `HandlerMethodReturnValueHandler` 处理。

核心接口：

```java
public interface HandlerMethodReturnValueHandler {

    boolean supportsReturnType(MethodParameter returnType);

    void handleReturnValue(Object returnValue,
                           MethodParameter returnType,
                           ModelAndViewContainer mavContainer,
                           NativeWebRequest webRequest) throws Exception;
}
```

它和参数解析器类似，也是一组策略链。

### 8.2 @ResponseBody 与 JSON 响应

如果方法或类上有：

```java
@ResponseBody
```

或者使用：

```java
@RestController
```

返回对象不会进入视图解析，而是直接写入 HTTP 响应体。

流程如下：

```text
Controller 返回 UserDTO
  -> RequestResponseBodyMethodProcessor
  -> 根据 Accept / produces 选择 MediaType
  -> 选择 HttpMessageConverter
  -> MappingJackson2HttpMessageConverter
  -> JSON 写入 response body
```

### 8.3 ModelAndView 与视图渲染

如果是传统 MVC 页面渲染：

```java
@Controller
public class PageController {

    @GetMapping("/index")
    public String index(Model model) {
        model.addAttribute("name", "Tom");
        return "index";
    }
}
```

返回值 `index` 通常会被当作视图名。

流程如下：

```text
Controller 返回 "index"
  -> ViewResolver 解析视图
  -> 得到 View
  -> View.render()
  -> 输出 HTML
```

前后端分离项目中，最常见的是 `@RestController` + JSON 响应；传统服务端页面项目中，才会大量使用 `ViewResolver`。

---

## 九、专题边界：后续文档如何承接

本篇只需要建立 Spring MVC 请求处理主线，不在这里展开所有细节。后续专题分工如下：

| 后续文档 | 承接内容 | 本篇只需掌握 |
|---|---|---|
| `03-02-Spring-MVC-HandlerMapping-HandlerAdapter.md` | URL 匹配、最佳匹配、Controller 调用入口 | `HandlerMapping` 找方法，`HandlerAdapter` 调方法 |
| `03-03-Spring-MVC-ArgumentResolver-ReturnValueHandler.md` | 参数解析、返回值处理、`HttpMessageConverter` | 参数在调用前解析，返回值在调用后处理 |
| `03-04-Spring-MVC-Exception-Interceptor-Validation.md` | 异常处理、拦截器、参数校验 | 异常走 Resolver，拦截器围绕 Handler，校验发生在参数解析阶段 |

建议学习时不要在总览篇停留太久：先能画出 `DispatcherServlet#doDispatch()` 主流程，再进入后续专题逐个拆源码。

## 十、Spring Boot 中 MVC 自动配置

### 13.1 DispatcherServletAutoConfiguration

在 Spring Boot 中，请求入口 `DispatcherServlet` 主要由自动配置注册。

关键组件包括：

- `DispatcherServlet`
- `DispatcherServletRegistrationBean`
- Servlet 映射路径
- 启动加载顺序

可以简单理解为：

```text
DispatcherServletAutoConfiguration
  -> 创建 DispatcherServlet
  -> 创建 DispatcherServletRegistrationBean
  -> 把 DispatcherServlet 注册到 Servlet 容器
```

这一步解决的是：HTTP 请求如何进入 Spring MVC。

### 13.2 WebMvcAutoConfiguration

`WebMvcAutoConfiguration` 负责 MVC 内部能力的默认配置。

它会配置或定制：

- `RequestMappingHandlerMapping`
- `RequestMappingHandlerAdapter`
- `ExceptionHandlerExceptionResolver`
- `HttpMessageConverter`
- 静态资源处理
- 格式化转换器
- 内容协商
- 默认欢迎页

可以简单理解为：

```text
DispatcherServletAutoConfiguration 负责注册入口 Servlet
WebMvcAutoConfiguration 负责配置 MVC 处理链路
```

### 13.3 @EnableWebMvc 要谨慎使用

在 Spring Boot 项目中，一般不建议随便加：

```java
@EnableWebMvc
```

原因是它会切换到 Spring MVC 的显式配置模式，可能导致 Spring Boot 默认 MVC 自动配置失效或被覆盖。

更推荐的方式是实现：

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/login");
    }
}
```

也就是：

```text
保留 Boot 自动配置，只扩展自己需要的部分。
```

### 13.4 路径匹配策略版本差异

调试 Spring MVC 时，要注意路径匹配策略可能因版本不同而不同。

| 版本 / 配置 | 常见策略 | 说明 |
|---|---|---|
| Spring Framework 5.x 常见项目 | `AntPathMatcher` | 老项目中更常见 |
| Spring Framework 6.x / Boot 3.x 常见项目 | `PathPatternParser` | 新版本默认更偏向解析后的路径模式 |
| Boot 2.6+ | 可配置匹配策略 | 老项目升级时容易遇到行为差异 |

如果你发现源码断点、路径匹配行为和教程不一致，优先检查：

```properties
spring.mvc.pathmatch.matching-strategy
```

学习阶段不需要死记版本差异，但要知道 URL 匹配不是只有一种实现。

---

## 十一、常见问题与排查路径

### 14.1 Controller 没生效

排查顺序：

```text
1. 类上是否有 @Controller 或 @RestController
2. 是否在组件扫描路径下
3. 是否被条件注解排除
4. URL 路径是否正确
5. HTTP 方法是否正确
6. context-path / servlet-path 是否影响路径
```

### 14.2 404 但 Controller 明明存在

重点检查：

- 类级别 `@RequestMapping` 和方法级别路径拼接结果。
- `spring.mvc.servlet.path`。
- `server.servlet.context-path`。
- 请求方法是否写错。
- 是否访问了错误端口。

还要注意：默认情况下，很多 404 会由容器或默认错误处理链路直接返回响应，不一定抛出 `NoHandlerFoundException`。如果项目希望 404 进入统一异常处理，通常需要额外配置并确认静态资源处理不会提前兜底。

### 14.3 405 Method Not Allowed

说明 URL 匹配到了，但 HTTP 方法不匹配。

例如 Controller 是：

```java
@PostMapping("/users")
```

但请求用了：

```text
GET /users
```

### 14.4 @RequestBody 为空或解析失败

重点检查：

- 请求是否真的有 body。
- `Content-Type` 是否是 `application/json`。
- JSON 字段名是否和 Java 属性匹配。
- DTO 是否有无参构造、getter/setter，或 Jackson 可识别的构造方式。
- 是否重复读取了 request input stream。

### 14.5 返回 JSON 失败

重点检查：

- 是否使用了 `@ResponseBody` 或 `@RestController`。
- 是否存在 Jackson 依赖。
- 返回对象是否存在循环引用。
- 字段是否能被 Jackson 序列化。
- `produces` 是否和客户端 `Accept` 冲突。

---

## 十二、源码断点建议

### 15.1 请求入口断点

建议先打：

```text
DispatcherServlet#doDispatch
```

观察：

- 请求是否进入 Spring MVC。
- `mappedHandler` 是什么。
- `HandlerAdapter` 是哪个。
- 是否进入异常分支。

### 15.2 HandlerMapping 断点

```text
AbstractHandlerMethodMapping#getHandlerInternal
RequestMappingInfoHandlerMapping#getMatchingMapping
AbstractHandlerMethodMapping#lookupHandlerMethod
```

观察：

- URL 如何匹配。
- 匹配到了哪个 `HandlerMethod`。
- 多个匹配结果如何选最优。

### 15.3 HandlerAdapter 断点

```text
RequestMappingHandlerAdapter#handleInternal
RequestMappingHandlerAdapter#invokeHandlerMethod
ServletInvocableHandlerMethod#invokeAndHandle
InvocableHandlerMethod#invokeForRequest
```

观察：

- 参数解析器列表。
- 返回值处理器列表。
- Controller 方法何时真正被反射调用。

### 15.4 参数解析断点

```text
HandlerMethodArgumentResolverComposite#resolveArgument
RequestParamMethodArgumentResolver#resolveName
PathVariableMethodArgumentResolver#resolveName
RequestResponseBodyMethodProcessor#readWithMessageConverters
```

观察：

- 每个参数由哪个 Resolver 处理。
- `@RequestBody` 何时读取 body。
- JSON 何时反序列化。

### 15.5 返回值处理断点

```text
HandlerMethodReturnValueHandlerComposite#handleReturnValue
RequestResponseBodyMethodProcessor#handleReturnValue
AbstractMessageConverterMethodProcessor#writeWithMessageConverters
MappingJackson2HttpMessageConverter#writeInternal
```

观察：

- 返回值由哪个 Handler 处理。
- 如何选择 `MediaType`。
- 如何选择 `HttpMessageConverter`。

### 15.6 异常处理断点

```text
DispatcherServlet#processHandlerException
HandlerExceptionResolverComposite#resolveException
ExceptionHandlerExceptionResolver#doResolveHandlerMethodException
```

观察：

- 异常是否被 `@ExceptionHandler` 捕获。
- 全局异常处理器是否生效。
- 最终响应状态码和响应体如何生成。

---

## 十三、必做实验

### 16.1 实验一：跟踪一次 GET 请求

目标：理解请求如何找到 Controller 方法。

步骤：

1. 新建 `UserController`。
2. 添加 `GET /users/{id}` 接口。
3. 在 `DispatcherServlet#doDispatch` 打断点。
4. 观察 `mappedHandler`。
5. 进入 `RequestMappingHandlerAdapter#invokeHandlerMethod`。
6. 观察 Controller 方法何时被调用。

过关标准：

```text
能说清楚 getHandler() 和 getHandlerAdapter() 分别做什么。
```

### 16.2 实验二：观察参数解析器

目标：理解不同参数由不同 Resolver 处理。

接口示例：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id,
                      @RequestParam String source,
                      HttpServletRequest request) {
    return new UserDTO(id, source, request.getRequestURI());
}
```

断点：

```text
HandlerMethodArgumentResolverComposite#resolveArgument
```

观察：

- `id` 由哪个 Resolver 解析。
- `source` 由哪个 Resolver 解析。
- `HttpServletRequest` 由哪个 Resolver 注入。

### 16.3 实验三：观察 @RequestBody

目标：理解 JSON 如何反序列化成对象。

接口示例：

```java
@PostMapping("/users")
public UserDTO create(@RequestBody CreateUserRequest request) {
    return new UserDTO(1L, request.getName(), false);
}
```

断点：

```text
RequestResponseBodyMethodProcessor#readWithMessageConverters
MappingJackson2HttpMessageConverter#readInternal
```

观察：

- 何时读取请求体。
- 选择了哪个 `HttpMessageConverter`。
- JSON 如何变成 Java 对象。

### 16.4 实验四：观察 JSON 返回

目标：理解返回对象如何写成 JSON。

断点：

```text
RequestResponseBodyMethodProcessor#handleReturnValue
AbstractMessageConverterMethodProcessor#writeWithMessageConverters
MappingJackson2HttpMessageConverter#writeInternal
```

观察：

- 返回值是否进入视图解析。
- 如何选择响应 `Content-Type`。
- Java 对象何时序列化成 JSON。

### 16.5 实验五：观察参数校验失败

目标：理解 `@Valid` 失败为什么不会进入 Controller 方法。

接口示例：

```java
@PostMapping("/users")
public UserDTO create(@Valid @RequestBody CreateUserRequest request) {
    return new UserDTO(1L, request.getName(), false);
}
```

DTO 示例：

```java
public class CreateUserRequest {

    @NotBlank(message = "用户名不能为空")
    private String name;
}
```

断点：

```text
RequestResponseBodyMethodProcessor#resolveArgument
DataBinder#validate()
DispatcherServlet#processHandlerException
```

观察：

- JSON 是否先成功反序列化成对象。
- 校验失败后是否进入 Controller 方法。
- 最终是否由全局异常处理器处理 `MethodArgumentNotValidException`。

### 16.6 实验六：观察全局异常处理

目标：理解 `@RestControllerAdvice` 的执行时机。

示例：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> handle(IllegalArgumentException ex) {
        return Map.of("code", "PARAM_ERROR", "message", ex.getMessage());
    }
}
```

断点：

```text
DispatcherServlet#processHandlerException
ExceptionHandlerExceptionResolver#doResolveHandlerMethodException
```

过关标准：

```text
能说明 Controller 抛异常后为什么还能返回统一 JSON。
```

### 16.7 实验七：自定义 Interceptor

示例：

```java
public class TraceInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        request.setAttribute("traceId", UUID.randomUUID().toString());
        return true;
    }
}
```

注册：

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TraceInterceptor()).addPathPatterns("/**");
    }
}
```

观察：

- `preHandle()` 是否在 Controller 前执行。
- `postHandle()` 和 `afterCompletion()` 的执行时机。
- Controller 抛异常时 `afterCompletion()` 是否执行。

---

## 十四、面试常问问题

### 17.1 Spring MVC 请求处理流程是什么？

推荐回答：

```text
请求先进入 DispatcherServlet，DispatcherServlet 通过 HandlerMapping 找到 HandlerExecutionChain，
再通过 HandlerAdapter 调用具体 Handler。对于注解 Controller，通常由
RequestMappingHandlerAdapter 执行方法调用，调用前使用 HandlerMethodArgumentResolver
解析参数，调用后使用 HandlerMethodReturnValueHandler 处理返回值。如果涉及 JSON，
会通过 HttpMessageConverter 完成序列化或反序列化。执行过程中如果出现异常，
由 HandlerExceptionResolver 处理。
```

### 17.2 HandlerMapping 和 HandlerAdapter 的区别？

推荐回答：

```text
HandlerMapping 负责根据请求找到处理器，解决“谁来处理”的问题；
HandlerAdapter 负责调用处理器，解决“怎么调用”的问题。
```

### 17.3 DispatcherServlet 为什么不是直接调用 Controller？

推荐回答：

```text
因为 Spring MVC 支持多种 Handler 类型，不同 Handler 的调用方式不同。
DispatcherServlet 只做流程编排，通过 HandlerMapping 和 HandlerAdapter 解耦查找与调用，
这样可以保持框架扩展性。
```

### 17.4 @RequestBody 和 @ResponseBody 的底层原理？

推荐回答：

```text
@RequestBody 由 RequestResponseBodyMethodProcessor 处理，它会读取请求体，
再根据 Content-Type 选择合适的 HttpMessageConverter，比如 Jackson Converter，
把 JSON 反序列化成 Java 对象。
@ResponseBody 也是由 RequestResponseBodyMethodProcessor 处理，它会把 Controller
返回值交给 HttpMessageConverter，再根据 Accept 或 produces 写成 JSON 响应体。
```

### 17.5 Filter、Interceptor、AOP 有什么区别？

推荐回答：

```text
Filter 属于 Servlet 规范，在请求进入 DispatcherServlet 前后执行，粒度是 HTTP 请求；
Interceptor 属于 Spring MVC，在 HandlerMapping 找到 Handler 后、Controller 调用前后执行；
AOP 属于 Spring Bean 方法增强，粒度是方法调用，常用于事务、日志、权限等场景。
```

### 17.6 @Valid 参数校验失败后为什么没有进入 Controller？

推荐回答：

```text
因为 Spring MVC 会在参数解析阶段完成数据绑定和校验。对于 @Valid @RequestBody，
请求体会先通过 HttpMessageConverter 反序列化成对象，然后由 WebDataBinder / Validator
执行校验。如果校验失败，通常抛出 MethodArgumentNotValidException，流程会进入
HandlerExceptionResolver，而不是继续调用 Controller 方法。
```

### 17.7 Spring Boot 中为什么不建议随便加 @EnableWebMvc？

推荐回答：

```text
因为 Spring Boot 已经通过 WebMvcAutoConfiguration 提供 MVC 默认配置。
如果随便使用 @EnableWebMvc，可能切换到显式 MVC 配置模式，导致 Boot 的部分默认配置失效。
通常推荐实现 WebMvcConfigurer 扩展需要的配置。
```

---

## 十五、学习顺序建议

建议按下面顺序学习和调试：

```text
1. 先看 DispatcherServlet#doDispatch() 总流程
2. 再看 HandlerMapping 如何匹配 Controller 方法
3. 再看 HandlerAdapter 如何调用 HandlerMethod
4. 再看 ArgumentResolver 如何解析参数
5. 再看 ReturnValueHandler 如何处理返回值
6. 再看 HttpMessageConverter 如何处理 JSON
7. 再看 Validator / DataBinder 如何处理参数校验
8. 最后看 ExceptionResolver 和 Interceptor
```

不要一开始就陷入所有 MVC 配置细节，先抓住请求处理主线。

补充边界：本文讲的是基于 Servlet 的 Spring MVC。如果项目使用 WebFlux，请求入口、处理链路、异步模型和背压机制都不同，不要把 `DispatcherServlet` 这套链路直接套到 WebFlux。

另外，Spring MVC 也支持异步请求，例如 `Callable`、`DeferredResult`、`WebAsyncTask`，背后会涉及 `WebAsyncManager`。这些属于进阶内容，本篇只建立主线认知，后续可以单独展开。

---

## 十六、本篇过关标准

学完本文后，需要能做到：

- 能画出 Spring MVC 一次请求处理流程。
- 能说明 `DispatcherServlet` 的核心职责。
- 能区分 `HandlerMapping` 和 `HandlerAdapter`。
- 能说明 Controller 方法参数是如何被解析的。
- 能说明 Controller 返回值是如何变成 JSON 的。
- 能说明 `@RequestBody` 和 `@ResponseBody` 与 `HttpMessageConverter` 的关系。
- 能说明 `@Valid` 校验失败为什么通常不会进入 Controller 方法。
- 能说明 `Filter`、`Interceptor`、`AOP` 的区别。
- 能在源码中跟踪一次请求从 `doDispatch()` 到 Controller 方法执行。

---

## 十七、下一篇建议

下一篇建议继续拆 Spring MVC 的核心机制：

```text
03-02-Spring-MVC-HandlerMapping-HandlerAdapter.md
```

重点深入：

- `RequestMappingHandlerMapping` 如何扫描 Controller。
- `RequestMappingInfo` 如何表示映射条件。
- URL 匹配和最佳匹配规则。
- `RequestMappingHandlerAdapter` 如何准备参数解析器和返回值处理器。
- `InvocableHandlerMethod` 如何完成反射调用。