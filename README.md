# Vireon Gateway

`vireon-gateway` 是 Vireon 的独立边缘网关仓库。它负责路由、边缘安全与流量治理，不聚合业务
DTO，也不代替 IAM、Audit、System 或 QBC 服务执行权限和业务校验。

## 当前状态

- 启动第 1 步已完成：独立 Git 仓库与根级治理入口已经建立。
- 当前没有 Maven 工程、可运行应用、镜像、Nacos 配置或活跃路由。
- Gateway 实现位于总体启动顺序第 9 步；Framework 与 Platform 首批服务形成独立闭环后再开始。

## 外部路径契约

Gateway 必须按精确路径段匹配并将完整路径原样转发，禁止使用 `StripPrefix`：

| Gateway Path Predicate | 下游服务 | 首期状态 |
| --- | --- | --- |
| `/api/iam`、`/api/iam/**` | `vireon-iam-service` | 服务就绪后启用 |
| `/api/audit`、`/api/audit/**` | `vireon-audit-service` | 服务就绪后启用 |
| `/api/system`、`/api/system/**` | `vireon-system-service` | 服务真实创建前保持 404 |
| `/api/qbc`、`/api/qbc/**` | QBC 业务服务 | 业务服务真实创建前保持 404 |

`/api/iamx`、`/api/auditing` 等相邻前缀不得误匹配。静态路由与显式启用后的 Nacos 动态路由
必须保持相同的外部路径和转发语义。

## 项目边界

- Gateway 拥有独立 Maven 根、Wrapper、版本、制品、进程、镜像、配置、健康检查和发布入口。
- Gateway 不进入 Framework，也不被业务项目作为编译依赖。
- Gateway 不依赖 Platform 或 QBC 的内部 Java 类型、数据库表、源码或构建输出。
- 路由只有一个权威来源：本地静态描述或显式 Nacos 模式，不在代码中维护第三套副本。
- Actuator 使用独立 management 端口或受控内网路径，不默认暴露到 Gateway 外部路由。

## 工作入口

1. 修改前阅读 [AGENTS.md](AGENTS.md)、本 README 和相关 ADR。
2. 新增运行能力前确认下游服务与真实验收用例已经存在，不创建不可执行的占位路由。
3. 开发与交付使用仓库自己的 Wrapper、测试和发布入口，不依赖相邻仓库工作树。
4. 高影响的路由、安全、配置源、版本或部署边界变更先记录 ADR。

## 文档

- [治理文档索引](docs/README.md)
- [Gateway 独立边界与路径契约](docs/adr/ADR-0001-gateway-boundary.md)
- [部署资产边界](ops/README.md)

本仓当前按私有项目管理，未声明开源许可证。
