# Spring MVC 专题：参数解析器与返回值处理器源码深度解析

> 本文是第 4 阶段 Spring MVC 的第三篇文档，承接 `03-02-Spring-MVC-HandlerMapping-HandlerAdapter.md`。`03-02` 只讲到 Adapter 调用入口；本文专门深入两条策略链：`HandlerMethodArgumentResolver` 参数解析器链和 `HandlerMethodReturnValueHandler` 返回值处理器链。

---

## 一、先给结论

### 1.1 参数解析器解决什么问题

Controller 方法可以写成这样：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id,
                      @RequestParam(defaultValue = "false") boolean verbose,
                      HttpServletRequest request) {
    return new UserDTO(id, "Tom", verbose);
}
```

这里的问题是：

```text
HTTP 请求里的路径、查询参数、请求头、请求体，如何变成 Java 方法参数？
```

Spring MVC 的答案是：

```text
HandlerMethodArgumentResolver
```

它负责把 HTTP 请求上下文中的数据解析成 Controller 方法入参。

### 1.2 返回值处理器解决什么问题

Controller 方法可以返回很多类型：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id) { ... }

@GetMapping("/users/entity/{id}")
public ResponseEntity<UserDTO> detailEntity(@PathVariable Long id) { ... }

@GetMapping("/users/page")
public String page(Model model) { ... }
```

这里的问题是：

```text
Controller 返回值如何变成 HTTP 响应？
```

Spring MVC 的答案是：

```text
HandlerMethodReturnValueHandler
```

它负责把 Controller 方法返回值转换成响应体、视图、状态码或异步结果。

### 1.3 两条链的位置

它们都在 `RequestMappingHandlerAdapter` 调用 Controller 方法时工作。

核心链路如下：

```text
RequestMappingHandlerAdapter#invokeHandlerMethod()
  -> ServletInvocableHandlerMethod#invokeAndHandle()
    -> InvocableHandlerMethod#invokeForRequest()
      -> getMethodArgumentValues()
        -> HandlerMethodArgumentResolverComposite#resolveArgument()
      -> doInvoke()
        -> Method.invoke()
    -> HandlerMethodReturnValueHandlerComposite#handleReturnValue()
```

一句话总结：

```text
参数解析器链在 Controller 方法调用前工作
返回值处理器链在 Controller 方法调用后工作
```

---

## 二、为什么需要策略链

### 2.1 Controller 参数形态太多

Controller 方法参数可能来自很多地方：

- 路径变量。
- 查询参数。
- 表单参数。
- 请求体 JSON。
- 请求头。
- Cookie。
- Session。
- 原生 Servlet 对象。
- Spring MVC 模型对象。
- 当前登录用户。
- 自定义注解参数。

如果只靠 `RequestMappingHandlerAdapter` 自己处理，会变成大量 `if else`。

所以 Spring MVC 设计了参数解析器接口：

```text
一个参数类型 / 注解，对应一个或一类 ArgumentResolver
```

### 2.2 Controller 返回值形态也很多

Controller 返回值可能是：

- 普通 Java 对象。
- `String` 视图名。
- `ModelAndView`。
- `ResponseEntity`。
- `void`。
- `Map`。
- `Callable`。
- `DeferredResult`。
- `StreamingResponseBody`。
- `SseEmitter`。

所以 Spring MVC 也设计了返回值处理器接口：

```text
一种返回值类型 / 注解，对应一个或一类 ReturnValueHandler
```

### 2.3 Composite 组合器

Spring MVC 不是直接让 `RequestMappingHandlerAdapter` 持有一个解析器，而是持有一组解析器。

核心对象是：

```text
HandlerMethodArgumentResolverComposite
HandlerMethodReturnValueHandlerComposite
```

它们负责：

- 保存解析器 / 处理器列表。
- 按顺序查找支持当前参数或返回值的策略对象。
- 缓存查找结果。
- 委托具体策略对象完成解析或处理。

这就是典型的责任链 + 策略模式。

---

## 三、最小案例：贯穿本文

### 3.1 示例 Controller

```java
@RestController
@RequestMapping("/users")
public class UserController {

    @GetMapping("/{id}")
    public UserDTO detail(@PathVariable Long id,
                          @RequestParam(defaultValue = "false") boolean verbose,
                          @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                          HttpServletRequest request) {
        return new UserDTO(id, "Tom", verbose, traceId);
    }

    @PostMapping
    public ResponseEntity<UserDTO> create(@Valid @RequestBody CreateUserRequest request) {
        UserDTO user = new UserDTO(1L, request.getName(), false, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }
}
```

### 3.2 GET 请求会触发哪些参数解析器

请求：

```http
GET /users/100?verbose=true
X-Trace-Id: abc-123
```

大致对应：

| 方法参数 | 数据来源 | 常见解析器 |
|---|---|---|
| `@PathVariable Long id` | URI 模板变量 | `PathVariableMethodArgumentResolver` |
| `@RequestParam boolean verbose` | Query 参数 | `RequestParamMethodArgumentResolver` |
| `@RequestHeader String traceId` | 请求头 | `RequestHeaderMethodArgumentResolver` |
| `HttpServletRequest request` | Servlet 原生对象 | `ServletRequestMethodArgumentResolver` |

### 3.3 POST 请求会触发哪些处理

请求：

```http
POST /users
Content-Type: application/json
Accept: application/json

{"name":"Tom"}
```

大致流程：

```text
@RequestBody CreateUserRequest
  -> RequestResponseBodyMethodProcessor
  -> MappingJackson2HttpMessageConverter 读取 JSON
  -> WebDataBinder / Validator 校验对象
  -> Controller 方法执行
  -> ResponseEntityMethodProcessor / HttpEntityMethodProcessor 处理 ResponseEntity
  -> MappingJackson2HttpMessageConverter 写 JSON
```

---

## 四、参数解析器核心接口

### 4.1 HandlerMethodArgumentResolver

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

两个方法职责非常清晰：

```text
supportsParameter()：我能不能处理这个参数？
resolveArgument()：如果能处理，具体怎么解析出参数值？
```

### 4.2 MethodParameter 是什么

`MethodParameter` 是 Spring 对 Java 方法参数的封装。

它包含：

- 所属方法。
- 参数下标。
- 参数类型。
- 泛型信息。
- 参数注解。
- 参数名。
- 嵌套类型信息。

例如：

```java
public UserDTO detail(@PathVariable Long id) { ... }
```

这个参数会被封装成类似：

```text
MethodParameter
  method: UserController#detail
  parameterIndex: 0
  parameterType: Long
  annotations: [@PathVariable]
```

### 4.3 NativeWebRequest 是什么

`NativeWebRequest` 是 Spring MVC 对 Web 请求的抽象。

它可以访问：

- `HttpServletRequest`
- `HttpServletResponse`
- 请求参数
- 请求头
- Session
- Locale
- request attributes

参数解析器通常通过它读取请求数据。

### 4.4 WebDataBinderFactory 是什么

`WebDataBinderFactory` 用于创建 `WebDataBinder`。

`WebDataBinder` 负责：

- 数据绑定。
- 类型转换。
- 格式化。
- 参数校验。
- 绑定错误收集。

所以一旦涉及对象绑定、类型转换、`@Valid` 校验，就离不开 `WebDataBinder`。

---

## 五、参数解析器组合器

### 5.1 HandlerMethodArgumentResolverComposite

`HandlerMethodArgumentResolverComposite` 内部持有一个解析器列表。

可以理解为：

```text
HandlerMethodArgumentResolverComposite
  resolvers:
    - RequestParamMethodArgumentResolver
    - PathVariableMethodArgumentResolver
    - RequestResponseBodyMethodProcessor
    - RequestHeaderMethodArgumentResolver
    - ServletRequestMethodArgumentResolver
    - ...
```

### 5.2 resolveArgument() 主流程

简化流程：

```java
public Object resolveArgument(MethodParameter parameter,
                              ModelAndViewContainer mavContainer,
                              NativeWebRequest webRequest,
                              WebDataBinderFactory binderFactory) throws Exception {

    HandlerMethodArgumentResolver resolver = getArgumentResolver(parameter);
    if (resolver == null) {
        throw new IllegalArgumentException("Unsupported parameter type");
    }
    return resolver.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
}
```

核心是：

```text
先找 Resolver
再委托 Resolver 解析参数
```

### 5.3 getArgumentResolver() 如何查找

查找逻辑可以简化为：

```text
1. 先查缓存 argumentResolverCache
2. 如果缓存命中，直接返回
3. 如果缓存没有，遍历全部 resolvers
4. 调用 supportsParameter(parameter)
5. 找到第一个支持的 resolver
6. 放入缓存
7. 返回 resolver
```

这说明解析器顺序很重要：

```text
多个 Resolver 都可能支持某个参数时，排在前面的优先生效
```

---

## 六、常见参数解析器详解

### 6.1 @PathVariable

示例：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id) { ... }
```

常见解析器：

```text
PathVariableMethodArgumentResolver
```

解析流程：

```text
1. 从 request attribute 中获取 URI_TEMPLATE_VARIABLES
2. 根据 @PathVariable 的 name 找到字符串值
3. 使用类型转换器转换成目标类型
4. 返回参数值
```

路径变量通常由 `HandlerMapping` 在匹配阶段解析并放入请求属性。

常见请求属性：

```text
HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
```

### 6.2 @RequestParam

示例：

```java
@GetMapping("/users")
public List<UserDTO> list(@RequestParam(defaultValue = "1") Integer pageNo,
                          @RequestParam(defaultValue = "20") Integer pageSize) { ... }
```

常见解析器：

```text
RequestParamMethodArgumentResolver
```

解析来源包括：

- Query String。
- 表单参数。
- Multipart 文件参数。

解析流程：

```text
1. 根据参数名从 request parameter 中取值
2. 如果没有值，检查 required / defaultValue
3. 执行类型转换
4. 返回目标类型参数
```

注意：

```text
@RequestParam 不是只处理 GET query，也能处理 application/x-www-form-urlencoded 表单参数
```

### 6.3 @RequestHeader

示例：

```java
@GetMapping("/users")
public List<UserDTO> list(@RequestHeader("X-Tenant-Id") String tenantId) { ... }
```

常见解析器：

```text
RequestHeaderMethodArgumentResolver
```

解析流程：

```text
从 HttpServletRequest header 中读取指定 header
  -> required / defaultValue 判断
  -> 类型转换
```

常见用途：

- 租户 ID。
- Trace ID。
- Token。
- 客户端版本。

### 6.4 @CookieValue

示例：

```java
@GetMapping("/profile")
public UserDTO profile(@CookieValue("SESSION") String sessionId) { ... }
```

常见解析器：

```text
ServletCookieValueMethodArgumentResolver
```

解析来源：

```text
HttpServletRequest#getCookies()
```

### 6.5 原生 Servlet 对象

示例：

```java
@GetMapping("/raw")
public void raw(HttpServletRequest request,
                HttpServletResponse response,
                HttpSession session) { ... }
```

常见解析器：

```text
ServletRequestMethodArgumentResolver
ServletResponseMethodArgumentResolver
```

这类参数不需要从请求参数里取值，而是直接从当前请求上下文中拿原生对象。

### 6.6 Model / Map

示例：

```java
@GetMapping("/page")
public String page(Model model) {
    model.addAttribute("name", "Tom");
    return "index";
}
```

常见处理器：

```text
ModelMethodProcessor
MapMethodProcessor
```

它们主要用于传统服务端页面渲染，把数据放入 `ModelAndViewContainer`。

---

## 七、@RequestBody 深度解析

### 7.1 常见解析器

`@RequestBody` 使用的常见解析器是：

```text
RequestResponseBodyMethodProcessor
```

它比较特殊，因为它既是参数解析器，也是返回值处理器。

它处理：

```text
@RequestBody 参数读取
@ResponseBody 返回值写出
```

### 7.2 @RequestBody 读取流程

示例：

```java
@PostMapping("/users")
public UserDTO create(@RequestBody CreateUserRequest request) { ... }
```

核心流程：

```text
RequestResponseBodyMethodProcessor#resolveArgument()
  -> readWithMessageConverters()
    -> 根据 Content-Type 选择 HttpMessageConverter
    -> MappingJackson2HttpMessageConverter#read()
    -> Jackson 反序列化 JSON
  -> 创建 WebDataBinder
  -> 如果有 @Valid，执行参数校验
  -> 返回 Java 对象
```

### 7.3 HttpMessageConverter 如何选择

请求头：

```http
Content-Type: application/json
```

会影响请求体读取。

Spring MVC 会遍历 `HttpMessageConverter`：

```text
for converter in messageConverters:
    if converter.canRead(targetType, contentType):
        return converter.read(...)
```

对于 JSON，常见转换器是：

```text
MappingJackson2HttpMessageConverter
```

如果没有合适转换器，或 `Content-Type` 不支持，常见异常是：

```text
HttpMediaTypeNotSupportedException
```

### 7.4 JSON 格式错误

如果请求体 JSON 格式错误，例如：

```json
{"name": "Tom"
```

反序列化失败，常见异常是：

```text
HttpMessageNotReadableException
```

这和字段校验失败不同。

```text
JSON 格式错误：HttpMessageNotReadableException
字段规则不满足：MethodArgumentNotValidException
```

### 7.5 @RequestBody 只能读一次的问题

HTTP request body 底层是输入流，默认只能读取一次。

所以如果在 Filter / Interceptor 中提前读取了 body，后续 `@RequestBody` 可能读不到内容。

常见解决思路：

- 使用可重复读取的 request wrapper。
- 在日志组件里谨慎读取 body。
- 避免在多个地方直接消费 input stream。

---

## 八、类型转换与数据绑定

### 8.1 为什么需要类型转换

HTTP 请求里的数据本质上通常是字符串。

例如：

```http
GET /users/100?enabled=true&age=18
```

Controller 方法参数却是 Java 类型：

```java
public UserDTO detail(@PathVariable Long id,
                      @RequestParam Boolean enabled,
                      @RequestParam Integer age) { ... }
```

所以必须有类型转换。

```text
"100" -> Long
"true" -> Boolean
"18" -> Integer
```

### 8.2 ConversionService

Spring MVC 通常通过 `ConversionService` 完成类型转换。

它能处理：

- String 到基础类型。
- String 到枚举。
- String 到日期时间。
- 集合元素转换。
- 自定义 Converter。

### 8.3 WebDataBinder

`WebDataBinder` 更偏向一次请求内的数据绑定上下文。

它会协调：

- 目标对象。
- 类型转换。
- 字段绑定。
- 校验器。
- BindingResult。

对于复杂对象参数，Spring MVC 会创建 Binder，把请求参数绑定到对象属性上。

### 8.4 类型转换失败

示例：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id) { ... }
```

请求：

```text
GET /users/abc
```

`abc` 无法转成 `Long`，常见异常是：

```text
MethodArgumentTypeMismatchException
```

---

## 九、参数校验与 BindingResult

### 9.1 @Valid 的位置

常见写法：

```java
@PostMapping("/users")
public UserDTO create(@Valid @RequestBody CreateUserRequest request) { ... }
```

处理流程：

```text
先解析参数对象
再执行 Validator 校验
校验通过才调用 Controller 方法
```

### 9.2 @RequestBody 校验失败

对于 `@Valid @RequestBody`，如果没有紧跟 `BindingResult`，常见异常是：

```text
MethodArgumentNotValidException
```

通常由全局异常处理器统一处理。

### 9.3 @ModelAttribute 校验失败

对于表单对象或普通对象绑定：

```java
@PostMapping("/users/form")
public String submit(@Valid CreateUserRequest request, BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
        return "form";
    }
    return "redirect:/users";
}
```

如果 `BindingResult` 紧跟被校验参数，校验错误会进入 `bindingResult`，Controller 方法仍然可以执行。

### 9.4 BindingResult 的顺序要求

错误示例：

```java
public String submit(@Valid CreateUserRequest request,
                     Model model,
                     BindingResult bindingResult) { ... }
```

`BindingResult` 没有紧跟被校验参数，可能无法正确接收校验结果。

正确示例：

```java
public String submit(@Valid CreateUserRequest request,
                     BindingResult bindingResult,
                     Model model) { ... }
```

---

## 十、返回值处理器核心接口

### 10.1 HandlerMethodReturnValueHandler

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

两个方法职责：

```text
supportsReturnType()：我能不能处理这个返回类型？
handleReturnValue()：如果能处理，具体怎么写响应或设置模型？
```

### 10.2 ModelAndViewContainer

返回值处理器通常会修改 `ModelAndViewContainer`。

它可以保存：

- Model 数据。
- View 名称。
- View 对象。
- 请求是否已经处理完成。
- RedirectModel。
- BindingResult。

对于 `@ResponseBody`，返回值处理器通常会把请求标记为：

```text
requestHandled = true
```

表示响应已经写出，不需要再走视图解析。

---

## 十一、返回值处理器组合器

### 11.1 HandlerMethodReturnValueHandlerComposite

它和参数解析器组合器类似，内部维护返回值处理器列表。

简化流程：

```text
1. 遍历所有 ReturnValueHandler
2. 调用 supportsReturnType(returnType)
3. 找到第一个支持的 handler
4. 调用 handler.handleReturnValue()
```

### 11.2 顺序同样重要

多个返回值处理器可能都能处理某些类型。

例如：

- `String` 可能是普通响应体，也可能是视图名。
- `Map` 可能是模型数据，也可能是 JSON 响应。

具体走哪个处理器，取决于：

- Controller 是否有 `@ResponseBody`。
- 类上是否是 `@RestController`。
- 返回值处理器顺序。
- 返回类型本身。

---

## 十二、常见返回值处理器详解

### 12.1 @ResponseBody / @RestController

示例：

```java
@RestController
public class UserController {

    @GetMapping("/users/{id}")
    public UserDTO detail(@PathVariable Long id) {
        return new UserDTO(id, "Tom");
    }
}
```

常见处理器：

```text
RequestResponseBodyMethodProcessor
```

处理流程：

```text
Controller 返回 UserDTO
  -> RequestResponseBodyMethodProcessor#handleReturnValue()
  -> 根据 Accept / produces 选择 MediaType
  -> 选择 HttpMessageConverter
  -> MappingJackson2HttpMessageConverter 写 JSON
  -> 标记 requestHandled = true
```

### 12.2 ResponseEntity

示例：

```java
@PostMapping("/users")
public ResponseEntity<UserDTO> create(@RequestBody CreateUserRequest request) {
    UserDTO user = new UserDTO(1L, request.getName());
    return ResponseEntity.status(HttpStatus.CREATED).body(user);
}
```

常见处理器：

```text
HttpEntityMethodProcessor
```

它会处理：

- HTTP 状态码。
- 响应头。
- 响应体。

例如：

```text
201 Created
Content-Type: application/json

{"id":1,"name":"Tom"}
```

### 12.3 String 返回值

在 `@Controller` 中：

```java
@GetMapping("/index")
public String index() {
    return "index";
}
```

`String` 通常表示视图名。

常见处理器：

```text
ViewNameMethodReturnValueHandler
```

在 `@RestController` 中：

```java
@GetMapping("/hello")
public String hello() {
    return "hello";
}
```

`String` 通常表示响应体。

常见处理器：

```text
RequestResponseBodyMethodProcessor
StringHttpMessageConverter
```

### 12.4 ModelAndView

示例：

```java
@GetMapping("/users/page")
public ModelAndView page() {
    ModelAndView mv = new ModelAndView("users");
    mv.addObject("title", "用户列表");
    return mv;
}
```

常见处理器：

```text
ModelAndViewMethodReturnValueHandler
```

它会设置视图和模型数据，后续进入视图解析和渲染。

### 12.5 void 返回值

`void` 返回值比较特殊。

可能表示：

- 方法已经通过 `HttpServletResponse` 自己写出响应。
- 根据请求路径推断默认视图名。
- 异步请求已经开始。

所以不建议在普通 REST API 中滥用 `void`，除非你明确知道响应在哪里写出。

---

## 十三、HttpMessageConverter 再深入

### 13.1 它同时参与读和写

`HttpMessageConverter` 有两类核心能力：

```text
canRead() / read()
canWrite() / write()
```

对应场景：

```text
@RequestBody：read()
@ResponseBody：write()
```

### 13.2 读请求体

读取请求体时，关键看：

```text
目标 Java 类型
请求 Content-Type
converter.canRead(targetType, contentType)
```

例如：

```http
Content-Type: application/json
```

目标类型：

```text
CreateUserRequest
```

通常选择：

```text
MappingJackson2HttpMessageConverter
```

### 13.3 写响应体

写响应体时，关键看：

```text
返回值 Java 类型
客户端 Accept
Controller produces
converter.canWrite(returnType, mediaType)
```

例如：

```http
Accept: application/json
```

返回类型：

```text
UserDTO
```

通常选择：

```text
MappingJackson2HttpMessageConverter
```

### 13.4 常见 Converter

| Converter | 读 | 写 | 典型用途 |
|---|---|---|---|
| `MappingJackson2HttpMessageConverter` | 是 | 是 | JSON |
| `StringHttpMessageConverter` | 是 | 是 | 文本 |
| `ByteArrayHttpMessageConverter` | 是 | 是 | 字节数组 |
| `FormHttpMessageConverter` | 是 | 部分 | 表单 |
| `ResourceHttpMessageConverter` | 否/部分 | 是 | 文件下载 |

---

## 十四、自定义参数解析器

### 14.1 典型场景

自定义参数解析器适合处理统一上下文对象。

例如：

- 当前登录用户。
- 当前租户。
- 当前门店。
- Trace 上下文。
- 权限上下文。

### 14.2 自定义注解

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
```

### 14.3 自定义 Resolver

```java
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType().equals(LoginUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return request.getAttribute("loginUser");
    }
}
```

### 14.4 注册 Resolver

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
```

使用：

```java
@GetMapping("/me")
public UserDTO me(@CurrentUser LoginUser loginUser) {
    return userService.detail(loginUser.getUserId());
}
```

### 14.5 设计建议

自定义参数解析器要注意：

- `supportsParameter()` 条件要尽量收敛。
- 不要在解析器中写复杂业务逻辑。
- 不要直接访问数据库做重查询。
- 上下文对象最好由 Filter / Interceptor 提前准备。
- 异常语义要明确，避免吞掉认证失败或参数错误。

---

## 十五、自定义返回值处理器

### 15.1 什么时候需要

多数项目不需要自定义返回值处理器。

更常见的统一响应做法是：

- `ResponseBodyAdvice`
- 全局异常处理
- 统一响应对象

只有当你需要支持一种特殊返回值模型时，才考虑自定义 `HandlerMethodReturnValueHandler`。

### 15.2 示例：特殊返回包装

假设有一个返回类型：

```java
public class ApiResult<T> {
    private String code;
    private String message;
    private T data;
}
```

通常不需要自定义返回值处理器，直接返回 `ApiResult<T>` 即可由 Jackson 序列化。

真正需要自定义的场景可能是：

- 特殊流式协议。
- 特殊文件响应。
- 自定义异步模型。
- 框架级统一包装。

### 15.3 ResponseBodyAdvice 更常用

统一包装响应时，优先考虑：

```java
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        return ApiResult.success(body);
    }
}
```

注意：统一包装 `String` 返回值时要特别小心，因为 `StringHttpMessageConverter` 和 JSON 转换器行为不同，可能导致类型不匹配。

---

## 十六、常见问题排查

### 16.1 @RequestParam 收不到参数

排查：

- 参数名是否一致。
- 是否缺少 `-parameters` 编译参数导致参数名不可见。
- 是否设置了 `required = false` 或 `defaultValue`。
- 请求是 query/form 还是 JSON body。

常见误区：

```text
@RequestParam 不能直接读取 JSON body 里的字段
JSON body 应该用 @RequestBody
```

### 16.2 @RequestBody 为空

排查：

- 请求是否真的有 body。
- `Content-Type` 是否是 `application/json`。
- body 是否被 Filter / Interceptor 提前读取。
- DTO 是否能被 Jackson 反序列化。
- 是否同时用了多个 `@RequestBody`。

注意：一个请求方法里通常不应该有多个 `@RequestBody` 参数。

### 16.3 参数类型转换失败

常见异常：

```text
MethodArgumentTypeMismatchException
ConversionFailedException
```

排查：

- 请求参数格式是否正确。
- 日期格式是否符合配置。
- 枚举值是否匹配。
- 是否需要自定义 Converter。

### 16.4 @Valid 不生效

排查：

- 是否引入校验依赖。
- 是否在参数上加了 `@Valid` 或 `@Validated`。
- DTO 字段上是否有约束注解。
- 是否被 `BindingResult` 接住。
- 是否走到了对应参数解析器。

### 16.5 返回值不是 JSON

排查：

- 类上是否是 `@RestController`。
- 方法上是否有 `@ResponseBody`。
- 是否返回了视图名 `String`。
- `Accept` 请求头是否影响内容协商。
- 是否缺少 Jackson converter。

### 16.6 统一响应包装导致 String 报错

常见原因：

```text
Controller 返回 String
选中了 StringHttpMessageConverter
ResponseBodyAdvice 又把 String 包成对象
最终 converter 无法按预期写出
```

解决思路：

- 对 `String` 单独处理成 JSON 字符串。
- 排除特定返回类型。
- 避免过度统一包装。

---

## 十七、源码断点建议

### 17.1 参数解析断点

建议断点：

```text
InvocableHandlerMethod#getMethodArgumentValues
HandlerMethodArgumentResolverComposite#resolveArgument
HandlerMethodArgumentResolverComposite#getArgumentResolver
RequestParamMethodArgumentResolver#resolveName
PathVariableMethodArgumentResolver#resolveName
RequestResponseBodyMethodProcessor#resolveArgument
```

观察：

- 每个参数对应哪个 `MethodParameter`。
- 哪个 Resolver 支持当前参数。
- 参数值从哪里读取。
- 类型转换何时发生。

### 17.2 @RequestBody 断点

建议断点：

```text
RequestResponseBodyMethodProcessor#readWithMessageConverters
AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters
MappingJackson2HttpMessageConverter#readInternal
WebDataBinder#validate
```

观察：

- 如何选择 `HttpMessageConverter`。
- JSON 何时被 Jackson 反序列化。
- `@Valid` 何时执行。
- 校验失败如何抛出异常。

### 17.3 返回值处理断点

建议断点：

```text
ServletInvocableHandlerMethod#invokeAndHandle
HandlerMethodReturnValueHandlerComposite#handleReturnValue
HandlerMethodReturnValueHandlerComposite#selectHandler
RequestResponseBodyMethodProcessor#handleReturnValue
HttpEntityMethodProcessor#handleReturnValue
```

观察：

- 返回值类型是什么。
- 哪个 Handler 处理返回值。
- 是否设置 `requestHandled = true`。

### 17.4 JSON 写出断点

建议断点：

```text
AbstractMessageConverterMethodProcessor#writeWithMessageConverters
MappingJackson2HttpMessageConverter#writeInternal
```

观察：

- 如何选择响应 `MediaType`。
- 如何选择 `HttpMessageConverter`。
- Java 对象何时序列化成 JSON。

---

## 十八、必做实验

### 18.1 实验一：观察不同参数的 Resolver

目标：理解每种参数由谁解析。

接口：

```java
@GetMapping("/users/{id}")
public UserDTO detail(@PathVariable Long id,
                      @RequestParam String source,
                      @RequestHeader("X-Trace-Id") String traceId,
                      HttpServletRequest request) {
    return new UserDTO(id, source, traceId, request.getRequestURI());
}
```

断点：

```text
HandlerMethodArgumentResolverComposite#getArgumentResolver
```

过关标准：

```text
能说出每个参数分别由哪个 Resolver 解析。
```

### 18.2 实验二：观察 @RequestBody 读取 JSON

目标：理解 JSON 到 Java 对象的转换。

步骤：

1. 写一个 `POST /users` 接口。
2. 参数使用 `@RequestBody CreateUserRequest`。
3. 请求头设置 `Content-Type: application/json`。
4. 在 `readWithMessageConverters()` 打断点。
5. 观察选中的 converter。

过关标准：

```text
能说明 @RequestBody 和 HttpMessageConverter 的关系。
```

### 18.3 实验三：观察 @Valid 校验失败

目标：理解校验失败为什么不会进入 Controller 方法。

步骤：

1. DTO 字段添加 `@NotBlank`。
2. Controller 参数添加 `@Valid @RequestBody`。
3. 发送非法 JSON 字段值。
4. 在 `WebDataBinder#validate` 打断点。
5. 观察 `MethodArgumentNotValidException`。

### 18.4 实验四：观察不同返回值处理器

目标：理解返回值处理器选择。

分别写接口返回：

```java
UserDTO
ResponseEntity<UserDTO>
String
ModelAndView
```

断点：

```text
HandlerMethodReturnValueHandlerComposite#selectHandler
```

观察每种返回值对应的 Handler。

### 18.5 实验五：自定义 CurrentUser 参数解析器

目标：掌握自定义 `HandlerMethodArgumentResolver`。

步骤：

1. 定义 `@CurrentUser` 注解。
2. 实现 `CurrentUserArgumentResolver`。
3. 通过 `WebMvcConfigurer#addArgumentResolvers` 注册。
4. 在 Controller 方法参数中使用 `@CurrentUser LoginUser user`。
5. 观察自定义 Resolver 是否被调用。

---

## 十九、面试常问问题

### 19.1 Spring MVC 如何解析 Controller 方法参数？

推荐回答：

```text
RequestMappingHandlerAdapter 调用 Controller 方法前，会通过 InvocableHandlerMethod
获取所有方法参数值。它会遍历 MethodParameter，并委托 HandlerMethodArgumentResolverComposite
选择支持当前参数的 HandlerMethodArgumentResolver，然后调用 resolveArgument() 解析参数。
例如 @PathVariable 由 PathVariableMethodArgumentResolver 处理，@RequestBody 通常由
RequestResponseBodyMethodProcessor 处理。
```

### 19.2 @RequestParam 和 @RequestBody 的区别？

推荐回答：

```text
@RequestParam 主要从 query string、表单参数或 multipart 参数中取值，适合简单参数；
@RequestBody 读取 HTTP request body，并通过 HttpMessageConverter 反序列化成 Java 对象，
常用于 JSON 请求体。@RequestParam 不能直接读取 JSON body 里的字段。
```

### 19.3 @RequestBody 的底层原理是什么？

推荐回答：

```text
@RequestBody 通常由 RequestResponseBodyMethodProcessor 解析。它会根据请求 Content-Type
选择合适的 HttpMessageConverter，例如 MappingJackson2HttpMessageConverter，然后读取
request body 并反序列化成目标 Java 类型。如果参数带有 @Valid，还会通过 WebDataBinder
触发校验。
```

### 19.4 Controller 返回值如何变成 JSON？

推荐回答：

```text
如果方法或类上有 @ResponseBody，或者使用 @RestController，返回值通常由
RequestResponseBodyMethodProcessor 处理。它会根据 Accept / produces 选择响应 MediaType，
再选择合适的 HttpMessageConverter，例如 MappingJackson2HttpMessageConverter，
把 Java 对象序列化成 JSON 写入 response body。
```

### 19.5 ResponseEntity 和普通对象返回有什么区别？

推荐回答：

```text
普通对象返回主要表示响应体，状态码通常是默认 200；ResponseEntity 可以同时控制状态码、
响应头和响应体，通常由 HttpEntityMethodProcessor 处理，适合创建资源、文件下载、缓存控制等场景。
```

### 19.6 如何自定义 Controller 参数解析？

推荐回答：

```text
可以实现 HandlerMethodArgumentResolver，重写 supportsParameter() 判断支持哪些参数，
重写 resolveArgument() 从请求上下文中解析参数值，然后通过 WebMvcConfigurer#addArgumentResolvers
注册到 Spring MVC。常见场景是 @CurrentUser、@CurrentTenant 等上下文参数。
```

---

## 二十、本篇过关标准

学完本文后，需要能做到：

- 能说明参数解析器链在 Controller 调用前工作。
- 能说明返回值处理器链在 Controller 调用后工作。
- 能说出 `HandlerMethodArgumentResolver` 两个核心方法的作用。
- 能说出 `HandlerMethodReturnValueHandler` 两个核心方法的作用。
- 能解释 `@PathVariable`、`@RequestParam`、`@RequestBody` 的解析差异。
- 能解释 `@RequestBody` 与 `HttpMessageConverter` 的关系。
- 能解释 `@Valid`、`WebDataBinder`、`BindingResult` 的协作关系。
- 能解释 `@ResponseBody`、`ResponseEntity`、视图名返回的处理差异。
- 能写一个简单的自定义 `HandlerMethodArgumentResolver`。
- 能在源码中跟踪参数解析、Controller 调用、返回值处理的完整链路。

---

## 二十一、下一篇建议

下一篇建议继续深入异常、拦截器与校验的协作：

```text
03-04-Spring-MVC-Exception-Interceptor-Validation.md
```

重点拆解：

- `HandlerExceptionResolver` 如何处理异常。
- `@ExceptionHandler` 与 `@RestControllerAdvice` 的执行流程。
- `HandlerInterceptor` 的匹配和执行时机。
- 参数校验异常如何进入全局异常处理。
- 统一错误响应如何设计。