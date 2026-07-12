# Spring MVC 专题：异常处理、拦截器与参数校验源码深度解析

> 本文是第 4 阶段 Spring MVC 的第四篇文档，承接 `03-03-Spring-MVC-ArgumentResolver-ReturnValueHandler.md`。前三篇已经打通请求映射、方法调用、参数解析和返回值处理；本文专门补齐 MVC 请求链路中最容易影响工程质量的三块：异常处理、拦截器、参数校验。

---

## 一、先给结论

### 1.1 异常处理解决什么问题

Controller 或参数解析过程中可能出现很多异常：参数缺失、类型转换失败、JSON 反序列化失败、参数校验失败、请求方法不支持、媒体类型不支持、业务异常、系统异常。

Spring MVC 通过 `HandlerExceptionResolver` 体系把异常转换成响应。

```text
异常不是直接抛给浏览器，而是先交给 HandlerExceptionResolver 尝试处理
```

### 1.2 拦截器解决什么问题

`HandlerInterceptor` 解决的是 Controller 调用前后的 MVC 层横切逻辑，典型用途包括登录校验、权限检查、TraceId 透传、多租户上下文、请求日志、接口耗时统计。

```text
Interceptor 是 Spring MVC Handler 级拦截，不是 Servlet Filter，也不是 Spring AOP
```

### 1.3 参数校验解决什么问题

参数校验用于在 Controller 执行前阻止非法输入进入业务层。

```java
@PostMapping("/users")
public UserDTO create(@Valid @RequestBody CreateUserRequest request) {
    return userService.create(request);
}
```

```text
@RequestBody 先反序列化，再校验；校验失败通常进入异常处理链，不会执行 Controller 方法
```

---

## 二、三者在请求链路中的位置

```text
Filter before
  -> DispatcherServlet#doDispatch()
    -> HandlerMapping 找 HandlerExecutionChain
    -> HandlerInterceptor#preHandle()
    -> HandlerAdapter 调用 Controller
      -> 参数解析
      -> 参数校验
      -> Controller 方法执行
      -> 返回值处理
    -> HandlerInterceptor#postHandle()
    -> processDispatchResult()
      -> HandlerExceptionResolver 处理异常
    -> HandlerInterceptor#afterCompletion()
Filter after
```

关键点：

- Filter 在 Servlet 层，早于 Spring MVC。
- Interceptor 在 Spring MVC 层，围绕 Handler 执行。
- 参数校验发生在参数解析阶段。
- 异常处理通常发生在 `processDispatchResult()` 相关流程中。
- `afterCompletion()` 常用于清理上下文。

---

## 三、HandlerExceptionResolver 异常处理体系

### 3.1 核心接口

```java
public interface HandlerExceptionResolver {

    ModelAndView resolveException(HttpServletRequest request,
                                  HttpServletResponse response,
                                  Object handler,
                                  Exception ex);
}
```

它的职责是：

```text
输入异常，输出 ModelAndView 或直接写响应
```

如果返回 `null`，表示当前 resolver 不处理这个异常，交给下一个 resolver。

### 3.2 常见实现

| Resolver | 作用 |
|---|---|
| `ExceptionHandlerExceptionResolver` | 处理 `@ExceptionHandler` |
| `ResponseStatusExceptionResolver` | 处理 `@ResponseStatus` 和 `ResponseStatusException` |
| `DefaultHandlerExceptionResolver` | 处理 Spring MVC 内置异常 |
| `HandlerExceptionResolverComposite` | 组合多个 resolver |

最常用的是 `ExceptionHandlerExceptionResolver`，因为它负责支持 `@ControllerAdvice`、`@RestControllerAdvice`、`@ExceptionHandler`。

### 3.3 DispatcherServlet 中的异常处理入口

```text
DispatcherServlet#doDispatch()
  -> 捕获异常 dispatchException
  -> processDispatchResult()
    -> processHandlerException()
      -> 遍历 HandlerExceptionResolver
      -> resolveException()
```

---

## 四、@ExceptionHandler 与 @RestControllerAdvice

### 4.1 局部异常处理

```java
@RestController
@RequestMapping("/users")
public class UserController {

    @ExceptionHandler(IllegalArgumentException.class)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException ex) {
        return ErrorResponse.fail("PARAM_ERROR", ex.getMessage());
    }
}
```

局部异常处理只对当前 Controller 生效。

### 4.2 全局异常处理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ErrorResponse handleBusinessException(BusinessException ex) {
        return ErrorResponse.fail(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ErrorResponse handleException(Exception ex) {
        return ErrorResponse.fail("SYSTEM_ERROR", "系统异常");
    }
}
```

`@RestControllerAdvice` 可以理解为：

```text
@ControllerAdvice + @ResponseBody
```

所以返回对象会直接写入响应体。

### 4.3 匹配规则

`ExceptionHandlerExceptionResolver` 会根据异常类型选择最合适的 `@ExceptionHandler` 方法。

常见原则：

- 子类异常优先于父类异常。
- 局部 Controller 内的处理器优先于全局处理器。
- 更精确的异常类型优先。
- 没有匹配时继续交给后续 resolver。

---

## 五、常见 MVC 异常与处理建议

| 异常 | 常见原因 | 建议响应 |
|---|---|---|
| `MissingServletRequestParameterException` | 缺少必填 `@RequestParam` | 400 参数错误 |
| `MethodArgumentTypeMismatchException` | 参数类型转换失败 | 400 参数错误 |
| `HttpMessageNotReadableException` | JSON 格式错误或反序列化失败 | 400 请求体错误 |
| `MethodArgumentNotValidException` | `@RequestBody` 参数校验失败 | 400 参数校验失败 |
| `BindException` | 表单/对象绑定校验失败 | 400 参数校验失败 |
| `HttpRequestMethodNotSupportedException` | HTTP 方法不支持 | 405 |
| `HttpMediaTypeNotSupportedException` | `Content-Type` 不支持 | 415 |
| `HttpMediaTypeNotAcceptableException` | `Accept` 不支持 | 406 |
| `NoHandlerFoundException` | 配置为抛异常时未匹配 Handler | 404 |
| `BusinessException` | 业务规则失败 | 业务错误码 |

更清晰的处理链路可以这样看：

| 异常来源 | 典型异常 | 主要处理者 | 输出结果 |
|---|---|---|---|
| 参数解析 | `MethodArgumentTypeMismatchException` | `DefaultHandlerExceptionResolver` 或全局异常处理 | 400 |
| JSON 读取 | `HttpMessageNotReadableException` | 全局异常处理更常见 | 400 |
| 参数校验 | `MethodArgumentNotValidException` / `BindException` | `@RestControllerAdvice` | 字段级错误响应 |
| 业务规则 | `BusinessException` | `@ExceptionHandler(BusinessException.class)` | 业务错误码 |
| 未兜底异常 | `Exception` | 兜底异常处理器 | 通用系统错误 |

建议统一错误结构：

```java
public class ErrorResponse {
    private String code;
    private String message;
    private String traceId;
    private Object detail;
}
```

不要把 Java 异常栈直接返回给前端。

---

## 六、参数校验完整流程

### 6.1 @Valid @RequestBody

```java
public class CreateUserRequest {

    @NotBlank(message = "用户名不能为空")
    private String name;

    @Min(value = 1, message = "年龄必须大于 0")
    private Integer age;
}
```

```java
@PostMapping("/users")
public UserDTO create(@Valid @RequestBody CreateUserRequest request) {
    return userService.create(request);
}
```

处理流程：

```text
RequestResponseBodyMethodProcessor#resolveArgument()
  -> readWithMessageConverters()
  -> JSON 反序列化成 CreateUserRequest
  -> 创建 WebDataBinder
  -> validateIfApplicable()
  -> WebDataBinder#validate()
  -> 校验失败抛 MethodArgumentNotValidException
```

### 6.2 @ModelAttribute / 表单对象校验

```java
@PostMapping("/users/form")
public String submit(@Valid CreateUserRequest request, BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
        return "user-form";
    }
    return "redirect:/users";
}
```

重点：

```text
BindingResult 紧跟被校验参数时，Controller 方法仍然执行
没有 BindingResult 时，通常抛 BindException
```

### 6.3 BindingResult 顺序要求

正确：

```java
public String submit(@Valid CreateUserRequest request,
                     BindingResult bindingResult,
                     Model model) { ... }
```

错误：

```java
public String submit(@Valid CreateUserRequest request,
                     Model model,
                     BindingResult bindingResult) { ... }
```

`BindingResult` 必须紧跟目标参数。

### 6.4 分组校验

```java
public interface CreateGroup {}
public interface UpdateGroup {}
```

```java
public class UserRequest {

    @Null(groups = CreateGroup.class)
    @NotNull(groups = UpdateGroup.class)
    private Long id;

    @NotBlank(groups = {CreateGroup.class, UpdateGroup.class})
    private String name;
}
```

```java
@PostMapping("/users")
public UserDTO create(@Validated(CreateGroup.class) @RequestBody UserRequest request) { ... }
```

---

## 七、HandlerInterceptor 拦截器体系

### 7.1 核心接口

```java
public interface HandlerInterceptor {

    default boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {
        return true;
    }

    default void postHandle(HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler,
                            ModelAndView modelAndView) throws Exception {
    }

    default void afterCompletion(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Object handler,
                                 Exception ex) throws Exception {
    }
}
```

三个方法职责：

```text
preHandle：Controller 前执行，可决定是否放行
postHandle：Controller 后、视图渲染前执行
afterCompletion：请求完成后执行，适合清理资源
```

### 7.2 注册拦截器

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TraceInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/login", "/error");
    }
}
```

### 7.3 执行顺序

```text
A.preHandle
B.preHandle
Controller
B.postHandle
A.postHandle
B.afterCompletion
A.afterCompletion
```

多个拦截器：`preHandle` 正序，`postHandle` 和 `afterCompletion` 反序。

### 7.4 preHandle 返回 false

```text
当前请求不再进入 Controller
后续拦截器不执行
已经成功 preHandle 的拦截器会触发 afterCompletion
```

### 7.5 Controller 抛异常

```text
postHandle 通常不执行
afterCompletion 会执行
异常进入 HandlerExceptionResolver
```

所以清理 ThreadLocal、MDC、租户上下文等逻辑应该放在 `afterCompletion()`。

---

## 八、Filter、Interceptor、AOP 的边界

| 机制 | 所属层级 | 典型粒度 | 执行位置 |
|---|---|---|---|
| Filter | Servlet | HTTP 请求 | DispatcherServlet 前后 |
| Interceptor | Spring MVC | Handler 请求 | Controller 前后 |
| AOP | Spring Bean | 方法调用 | Bean 方法调用前后 |

常见选择建议：

- 跨所有 Web 请求、包装 request/response：用 Filter。
- 依赖 HandlerMapping、路径匹配、Controller 上下文：用 Interceptor。
- 业务方法级增强、事务、方法注解：用 AOP。

---

## 九、统一错误响应设计建议

### 9.1 推荐结构

```json
{
  "code": "PARAM_ERROR",
  "message": "用户名不能为空",
  "traceId": "abc-123",
  "detail": null
}
```

字段建议：`code` 稳定错误码，`message` 用户可读提示，`traceId` 用于日志追踪，`detail` 保存可控的字段级错误。

### 9.2 参数校验错误聚合

```json
{
  "code": "PARAM_ERROR",
  "message": "参数校验失败",
  "detail": [
    {"field":"name","message":"用户名不能为空"},
    {"field":"age","message":"年龄必须大于 0"}
  ]
}
```

生产环境要避免返回敏感内部信息。

### 9.3 业务异常与系统异常区分

- 业务异常返回明确错误码和提示。
- 系统异常记录完整日志，对外返回通用提示。
- 参数异常统一返回 400。
- 认证授权异常返回 401 / 403。

---

## 十、源码断点建议

### 10.1 异常处理断点

```text
DispatcherServlet#processDispatchResult
DispatcherServlet#processHandlerException
HandlerExceptionResolverComposite#resolveException
ExceptionHandlerExceptionResolver#doResolveHandlerMethodException
ExceptionHandlerExceptionResolver#getExceptionHandlerMethod
ServletInvocableHandlerMethod#invokeAndHandle
```

### 10.2 参数校验断点

```text
RequestResponseBodyMethodProcessor#resolveArgument
AbstractMessageConverterMethodArgumentResolver#readWithMessageConverters
AbstractMessageConverterMethodArgumentResolver#validateIfApplicable
WebDataBinder#validate
```

### 10.3 拦截器断点

```text
AbstractHandlerMapping#getHandler
HandlerExecutionChain#applyPreHandle
HandlerExecutionChain#applyPostHandle
HandlerExecutionChain#triggerAfterCompletion
```

---

## 十一、必做实验

### 11.1 实验一：全局处理参数校验异常

1. DTO 字段添加 `@NotBlank`。
2. Controller 参数使用 `@Valid @RequestBody`。
3. 定义 `@RestControllerAdvice`。
4. 添加 `@ExceptionHandler(MethodArgumentNotValidException.class)`。
5. 发送非法请求。

过关标准：能说明校验失败为什么没有进入 Controller 方法，而是进入全局异常处理器。

### 11.2 实验二：观察 Interceptor 执行顺序

1. 注册两个 Interceptor：A、B。
2. 在三个方法中打印日志。
3. 请求正常接口。
4. 请求异常接口。
5. 让 B 的 `preHandle()` 返回 false。

### 11.3 实验三：区分 JSON 错误和字段校验错误

1. 发送非法 JSON，观察 `HttpMessageNotReadableException`。
2. 发送合法 JSON 但字段为空，观察 `MethodArgumentNotValidException`。
3. 分别在全局异常处理器中处理。

---

## 十二、面试常问问题

### 12.1 Spring MVC 全局异常处理原理是什么？

```text
DispatcherServlet 在请求处理过程中捕获异常后，会通过 HandlerExceptionResolverComposite
遍历多个 HandlerExceptionResolver。最常用的是 ExceptionHandlerExceptionResolver，
它会查找 Controller 或 @ControllerAdvice 中匹配的 @ExceptionHandler 方法，调用该方法，
再通过返回值处理器把错误对象写成响应。
```

### 12.2 @RestControllerAdvice 和 @ControllerAdvice 区别？

```text
@RestControllerAdvice 等价于 @ControllerAdvice + @ResponseBody，适合前后端分离接口，
异常处理方法返回对象会直接写入响应体。@ControllerAdvice 更通用，也可以用于返回视图。
```

### 12.3 @Valid 校验失败为什么不进入 Controller？

```text
@RequestBody 参数由 RequestResponseBodyMethodProcessor 解析。它先通过 HttpMessageConverter
把 JSON 反序列化成对象，再通过 WebDataBinder 触发校验。如果校验失败且没有 BindingResult
接收错误，通常抛出 MethodArgumentNotValidException，请求流程进入异常处理链，不再调用 Controller。
```

### 12.4 Interceptor、Filter、AOP 有什么区别？

```text
Filter 属于 Servlet 规范，在 DispatcherServlet 前后执行；Interceptor 属于 Spring MVC，
围绕 HandlerMapping 找到的 Handler 执行；AOP 属于 Spring Bean 方法级增强，围绕 Bean 方法调用执行。
```

---

## 十三、本篇过关标准

- 能说明 `HandlerExceptionResolver` 的作用。
- 能说明 `@ExceptionHandler` 和 `@RestControllerAdvice` 的执行原理。
- 能区分常见 MVC 异常及响应状态码。
- 能说明 `@Valid @RequestBody` 的校验流程。
- 能说明 `BindingResult` 的顺序要求。
- 能说明 `HandlerInterceptor` 三个方法的执行时机。
- 能区分 Filter、Interceptor、AOP 的边界。
- 能设计一个合理的统一错误响应结构。
- 能通过源码断点跟踪异常处理、参数校验和拦截器执行流程。