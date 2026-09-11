# ADR-0004：独立 WebFlux 静态网关运行契约

- 日期：2026-09-10；状态：实施采用；依据：用户授权执行 PLAN-0001，真实服务联合验收后置。
- T1—T6技术方案在此收敛；实现及测试结果归PLAN-0001，不预填通过。

## 方案与版本

采用JDK25、Spring Boot4.0.8、Spring Cloud2025.1.3 BOM管理的Gateway5.0.3、Maven3.9.16、Wrapper3.3.4。
以Maven Central正式POM解析为准，官方依据：[Cloud支持组合](https://spring.io/projects/spring-cloud/)、
[Gateway WebFlux](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux.html)。
核验日2026-09-10；旧3.5/2025.0参考基线不强制继承。新仓无既有运行兼容负担，不升级任何相邻项目。
官方WebFlux/Netty负责代理、连接池和背压；MVC可代理但无现有Servlet适配需求；不自建HTTP代理。
不消费Framework，独立Boot parent和Cloud BOM，无数据库、Redis、Nacos、业务Java依赖。

## 配置与边界

- `vireon.gateway.routes`为唯一静态事实源，条目只允许`iam`/`audit`及显式HTTP(S) origin URI，禁用户信息、路径、query、fragment。
  默认空；System/QBC真实接入前不可配置。测试条目只在自动化测试启动参数中注入，不提供活跃假路由profile。
- 精确匹配`/api/<id>`或其`/`子路径，完整原始路径/query透传。不使用StripPrefix、RewritePath、兜底路由。
  拒绝路径中的反斜线、分号、重复斜线、点段及编码斜线/反斜线/点/分号/百分号，避免多次解码绕过。
- 连接2秒、响应10秒，允许类型化正数有界配置；关闭Netty自动重试及重定向跟随。下游故障不撤销路由。
- 独立管理端口，默认业务`127.0.0.1:18080`、管理`127.0.0.1:19080`；两端口必须不同，测试可各用0。
  管理只暴露health、无详情；业务入口没有管理或internal路由。liveness/readiness仅代表Gateway自身。
- 直接接入模式不信任任何上游代理；清除Forwarded、X-Forwarded-*、X-Real-IP及明确身份声明头，
  不生成客户端身份、不解释代理头。Host由下游URI决定；不改Origin、CSRF、Authorization、Cookie。
- 保持端到端头和多Set-Cookie。重复认证输入保留供下游校验；Connection动态指名的头按hop-by-hop去除，
  若指名Authorization/Cookie/Origin/CSRF等安全头则拒绝，不能删除证据使请求降级通过。
- 业务响应完全透传；网关自身错误固定JSON`{"code":"..."}`，无请求路径、地址、堆栈及异常message。
  404 NOT_FOUND、400 INVALID_REQUEST、502 BAD_GATEWAY、504 GATEWAY_TIMEOUT、500 INTERNAL_ERROR。
  未发出响应前映射错误；已提交响应遇故障中断连接，不伪造完整JSON。超时不代表下游事务回滚。

## 验证与演进

测试使用本仓临时HTTP下游、动态回环端口、合成协议值，不模拟IAM状态机。覆盖原始协议、重复头、Cookie、
静态路径、管理隔离、下游拒绝/超时/断连、恢复、无自动重试、取消及日志输出。另以最终JAR启动进行验收。
依赖门禁禁止业务/Servlet/Nacos/数据库依赖；纯依赖消费不要求Framework空缓存，外部Maven缓存与源码隔离。
全套验证不调用clean或删除目录。可信反向代理、真实服务、TLS和浏览器接入另立验收；不能从此推断生产可用。
回滚为停止本Gateway并恢复之前制品/配置；当前无旧运行版本、数据库或迁移。无发布/部署/删除授权。
