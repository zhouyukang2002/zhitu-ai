---
kp: Spring MVC
category: SKILL
source: 《Spring Web 开发指南》
---
## HTTP 与 Servlet
HTTP 请求-响应模型：方法（GET 读/POST 建/PUT 更/DELETE 删）、状态码（200 成功、301 重定向、400 参数错、401 未认证、403 禁止、404 不存在、500 服务错）。GET 参数在 URL 幂等可缓存，POST 参数在请求体。Servlet 单例多线程：容器只创建一个实例，并发调用 service 方法，可变实例成员变量有线程安全问题，应使用局部变量。一次请求完整链路：DNS 解析 → TCP 三次握手（HTTPS 加 TLS）→ 请求报文 → 服务器处理 → 响应 → 渲染。

## Spring MVC
DispatcherServlet 前端控制器统一入口：HandlerMapping 找处理器 → HandlerAdapter 调用（参数绑定）→ Controller 执行 → ViewResolver 渲染或消息转换器序列化。参数绑定：@PathVariable URL 变量、@RequestParam 查询参数（required=false 缺省为 null）、@RequestBody 请求体 JSON 反序列化。@RestController = @Controller + @ResponseBody，返回值经 Jackson 序列化为 JSON。全局异常处理：@RestControllerAdvice + @ExceptionHandler 统一捕获转换，业务代码零 try-catch 样板。

## RESTful API
RESTful 用 HTTP 方法表达语义、路径用复数名词无动词：GET /articles 列表、GET /articles/{id} 详情、POST 创建、PUT /articles/{id} 全量更新、DELETE 删除。前后端分离约定统一响应结构 {code, message, data}，配合日期格式与 null 字段策略稳定契约。分页参数用 page/size，排序用 sort 字段。

## 会话与 Cookie
Cookie 存客户端容量小可被篡改；Session 存服务端通过 Cookie 中 JSESSIONID 关联，更安全但占内存且分布式需共享。分布式方案：Redis 集中存储（Spring Session）或 JWT 无状态令牌。JWT 优点是无状态易水平扩展，缺点是签发后无法主动作废（需黑名单）、载荷增加传输量。登录校验推荐 Spring MVC 拦截器：可注入 Bean、可取注解元数据；容器级处理（跨域、编码）用 Filter。

## MVC 分层
三层架构：Controller 表现层（协议适配、参数校验）→ Service 业务层（业务规则、事务边界）→ DAO/Mapper 持久层（数据访问）。分层价值：职责单一、逻辑复用（Service 可被定时任务/消息消费者调用）、脱离 HTTP 环境可单测、更换协议或存储时上层解耦。Controller 直接写业务与 SQL 是新手最常见反模式，会导致事务失效与逻辑重复。
