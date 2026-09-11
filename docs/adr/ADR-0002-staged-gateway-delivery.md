# ADR-0002：开发联调基础版与上线增强分阶段交付

- 日期：2026-09-09；所有者：Gateway；状态：按用户明确路线要求记录，文档未提交，功能未实施。
- 共同路线：Mix PLAN-0005、Mix ADR-0008（由相邻独立仓库维护；不形成源码或构建依赖）。
- 本决策细化 [ADR-0001](ADR-0001-gateway-boundary.md)的交付阶段，保持其路径、安全与独立边界。

## 基础版

2026-09-10 阶段调整：首批先做 Gateway 自身开发及独立验收，真实浏览器与下游联合验收后置，
详见 [ADR-0003](ADR-0003-gateway-independent-acceptance.md)。以下保留原阶段决策，不再作为首批全部准入条件。

先开发可独立构建、运行和测试的 Gateway，满足接口访问与后续前端接入：

1. 使用显式静态 URI，只激活真实可用的 IAM/Audit；System/QBC 创建并通过接入验收后再启用。
2. 精确路径段匹配，保留完整 /api/<service> 路径和 query 语义，不使用 StripPrefix。
3. 正确转发方法、正文、认证 Cookie、Authorization、CSRF/Origin、多个 Set-Cookie、状态及必要协议头；
   遵守 hop-by-hop 头规则，不二次包装业务 JSON，不伪造身份、不丢弃重复认证输入证据。
4. 前端同源访问；保留服务端 Origin、Cookie 和每服务 CSRF。共享/非回环环境使用 HTTPS；HTTP 仅限
   明确隔离的回环测试配置。前端开发服务可代理到 Gateway，不引入跨 origin 凭据放开作为默认方案。
5. 有界连接/响应超时、下游不可达和超时错误、伪造代理头防护；内部接口和管理面不映射公网入口。
   首期不自动重试写请求，不聚合业务 DTO，不替代服务授权。
6. 验证经 Gateway 的真实登录、首次改密、刷新、退出、当前用户、成功管理写及权限/CSRF 失败，交付前端
   地址配置、会话流程、错误和文档说明。Audit 查询只是既有回归，不增加审计业务范围。

基础版不以前端完整页面开发或生产部署作为验收前置，但必须有真实浏览器会话证据和独立路由/安全测试。
具体版本、WebFlux/MVC、端口及同源方式在后续 Gateway 实施计划中核验；本 ADR 不预填依赖或测试通过。

## 上线增强

Nacos 配置/发现/注册、集群、灰度和分布式流量治理按实际部署需求在后期设计；启用 Nacos 前仍须验证
与静态模式路径等价及认证、断连、重连、回滚，不能因基础版通过而视为已完成。正式证书/域名、网络边界、
可信代理、负载、监控、镜像与发布回退在预发布完成。上传、SSE/WebSocket 按真实业务出现时补验。

官方路由与超时可满足首期机制需求，优先受支持配置及必要扩展点，不自建通用 HTTP 代理。参考
[Gateway 4.3 Path Predicate](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/request-predicates-factories.html)、
[超时配置](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html)
与[转发头](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/httpheadersfilters.html)，
核验于 2026-09-09。文档版本仅为路线依据，实施前锁定真实依赖并验证；不引入仅支持 Servlet 的 Framework Starter 到 Reactive 应用。

## 本轮交付

仅新增阶段决策并同步 README/文档索引；原 ADR 的历史状态保留。未新增应用、路由、配置或部署资产，
未提交/推送；本仓 git diff --check 及本轮文档本地链接目标检查通过。无新增进程/工作树/临时资源，
退役不适用。独立构建与运行验收在基础版实施时执行。
