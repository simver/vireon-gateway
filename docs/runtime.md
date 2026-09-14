# Gateway 运行与独立验证

本期只运行本仓。真实IAM/Audit、浏览器会话、TLS/共享环境、生产镜像和部署均未验收。
版本、信任和错误契约见 [ADR-0004](adr/ADR-0004-static-webflux-runtime.md)。

## 本机TLS接入配置

PLAN-0003采用Boot标准`server.ssl.enabled`、`server.ssl.key-store`、`server.ssl.key-store-type=PKCS12`及环境内`SERVER_SSL_KEY_STORE_PASSWORD`。
业务监听HTTPS，独立回环管理端口显式`management.server.ssl.enabled=false`；管理面不经外部Gateway路径暴露。
下游精确信任键为`spring.cloud.gateway.server.webflux.httpclient.ssl.trusted-x509-certificates`（字符串列表），保持`use-insecure-trust-manager=false`。
不设置代理信任、不使用不安全信任或修改系统CA。设置`vireon.gateway.routes.iam/audit`为对应HTTPS绝对URI，证书SAN必须匹配。
`GatewayTlsJarIT`已加入本仓verify，真实JAR对受信下游保持完整path/query，对未知CA、错SAN、过期证书返回固定502；实际通过范围见PLAN-0003，不能代替真实IAM/Audit组合验收。

## 构建

需要JDK25，`JAVA_HOME`或PATH中的java指向该JDK。Maven由本仓Wrapper下载，分发SHA-256固定，首次构建需要访问Maven Central。
Windows从仓库根执行：

```powershell
./ops/verify.ps1
```

已有源码目录外的Maven缓存可通过`-MavenRepository '缓存绝对路径'`复用；缺省使用Maven标准本地仓库。
Wrapper缓存通过`MAVEN_USER_HOME`复用，脚本缺省设为用户目录下`.m2`，避免异常JVM user.home定位到磁盘根。
`-Phase test`只跑单元/协议测试；默认`verify`还打包并用最终JAR启动独立运行测试。`-Format`先按固定格式器整理Java。
`mvnw.cmd verify`或Unix `sh ./mvnw verify`可直接使用；脚本默认不执行clean/install/deploy，不创建每次专用依赖缓存。
`.mvn/settings.xml`只使用公开Central，无用户私有settings和仓库凭据。JDK/缓存沙箱权限问题按实际环境解决。

## 启停和管理面

构建后前台启动，Ctrl+C停止：

```powershell
java -jar ./target/vireon-gateway-0.1.0-SNAPSHOT.jar
```

业务默认`127.0.0.1:18080`；管理默认`127.0.0.1:19080`。启动前检查端口占用；日志输出到控制台。
管理只允许`/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`；无组件详情。
管理端口不转发业务请求，业务端口不能访问管理端点。管理必须绑定127.0.0.1且与业务端口不同；测试允许两者配置0。
健康仅代表Gateway自身，不承诺下游可达。下游不可达时保持进程可诊断，路由不自动消失。

## 静态配置

| 属性 | 缺省和限制 |
| --- | --- |
| `vireon.gateway.routes` | 空Map；仅允许iam/audit，值必须为显式http(s) origin，无userinfo/path/query/fragment |
| `vireon.gateway.connect-timeout-millis` | 2000，范围1—60000毫秒 |
| `vireon.gateway.response-timeout` | 10s，范围1ms—120s；首响应及读取等待有界，不承诺持续有数据的无限流总时长 |
| `server.address` / `server.port` | 127.0.0.1 / 18080 |
| `management.server.address` / `management.server.port` | 127.0.0.1 / 19080；隔离约束不可放宽 |

本期默认所有业务路径404。后续真实服务经过接入验证后，由运行所有者显式设置`vireon.gateway.routes.iam`或audit。
不提供虚假可运行下游示例。测试替身及其URI仅由测试运行时创建，不成为产品配置或可发布模块。
不允许用Spring Cloud原生routes/default-filters/discovery、Forwarded信任配置建立第二运行权威源或改变安全边界。
不消费Nacos，不提供Nacos profile；未来启用须另做静态等价、认证、断连、重连和回滚验收。
禁止启用`management.endpoint.health.probes.add-additional-paths`或设置任意健康组`additional-path`；
这些配置会在启动时被拒绝，健康检查仅使用独立管理端口的既定路径。
健康组的`show-details`与`show-components`只允许未设置（继承全局never）或显式`never`；
`always`、`when-authorized`均在启动时拒绝，不能通过组配置放宽无详情约束。

请求Cookie/Authorization/Origin/CSRF不作为Gateway身份依据；不读取或改写业务凭据。重复输入不择一、不去重。
清除客户端Forwarded、X-Forwarded-*、X-Real-IP及明确身份声明头；Host按下游URI设置。
不使用StripPrefix，保留完整路径/query；歧义路径规则见ADR。hop-by-hop头按官方机制处理。
不跟随下游重定向，不自动重试。多个Set-Cookie及清除/重设顺序保留；本期只证明协议透传，不证明真实浏览器会话。

## 错误与日志

下游返回的状态、正文和必要协议头直接透传。网关自身错误为单字段JSON：

| HTTP | code |
| --- | --- |
| 400 | INVALID_REQUEST |
| 404 | NOT_FOUND |
| 502 | BAD_GATEWAY |
| 504 | GATEWAY_TIMEOUT |
| 500 | INTERNAL_ERROR |

响应已开始后发生超时/断连，中断响应，不追加JSON。写超时不证明事务回滚，调用者不可盲目重发。
下游HTTP解码失败（例如非数字状态码）在响应提交前返回502；Gateway自身的程序异常仍返回500。
默认关闭可能携带原始URL/头的Netty/Gateway诊断和wiretap，不记录请求正文/凭据；启动失败只报告固定安全提示。
配置错误定位先核对ADR/属性/依赖与非秘密输入，不在共享环境启用原始网络debug或打印配置值。
自动测试使用合成标记检查运行和启动日志；不将此视为生产动态秘密全面审计。

## 验收资源

协议测试监听本机临时端口，执行结束关闭socket和线程。JAR测试通过ProcessBuilder启动本仓子进程，
日志写入`target/jar-*.log`，finally先终止精确子进程，超时才强制停止该测试持有的进程；不结束其他Java进程。
测试报告在`target/surefire-reports`、`target/failsafe-reports`；不自动删除资源目录。
实现者与审查者记录候选文件摘要/JAR摘要及真实结果；详见 [PLAN-0001](plans/PLAN-0001-basic-gateway.md)。

## 自动回执与独立候选（PLAN-0002）

PowerShell入口需要7.4或更新版本。`ops/verify.ps1`每次创建独占的`target/verification/<runId>/`，
保存Maven/文档日志、Surefire/Failsafe报告与`receipt.json`。直接调用Wrapper仍使用上节的默认报告目录。
只有完整verify、当轮有效报告、零失败/错误/跳过、文档检查及输入一致性全部通过，才记录
`completeAcceptance=true`；compile/test的PASS仅表示所选阶段成功。失败回执保留失败阶段，进程返回非零。
回执包含HEAD/dirty及实际输入逐文件SHA256；未提交候选不能只按HEAD识别。
JAR测试前后核对制品SHA256并写入当轮`tested-artifact.sha256`；回执要求交付JAR与此一致。
Boot可重复归档会固定JAR自身文件时间，因此不用该时间判断测试新鲜度。
完整回执入口拒绝外部Maven/JVM选项环境变量、非空`.mvn/jvm.config`及偏离本仓固定内容的
`.mvn/maven.config`，防止测试筛选、跳门禁或JDK自动打印秘密参数。自定义Maven调用不等同于本入口验收。

候选交付先在当前仓库完成格式整理和验证，再运行：

```powershell
./ops/deliver.ps1 -MavenRepository 'C:/Workbench/Data/.m2/repository'
```

缺省在系统临时目录创建全新`vireon-gateway-<UUID>`；也可用`-Destination`指定一个不存在、
位于源码目录之外且父目录已存在的绝对路径。脚本会保留路径并打印，不执行目录删除。
输入仅为根级AGENTS/README/.gitignore/POM/Wrapper及src、ops、docs、.mvn；目标不含原target、.git或相邻源码。
两份源码先按摘要冻结，然后各自用自身Wrapper完整verify，比较最终Boot可执行JAR的SHA256和运行版本。
普通依赖缓存可以复用；默认允许联网解析，只有显式`-Offline`才声明离线运行。

交付目录包括`build-a`、`build-b`的源码及各自完整验收证据、`frozen-source.json`、`delivery.json`、
候选JAR及`SHA256SUMS`。交付回执绑定两份验证回执摘要和JAR摘要；失败时保留目录与失败阶段，不交付PASS。
POM中固定归档时间属于冻结输入；同一候选不随构建当前时间变化。不承诺跨系统、JDK或第三方环境可重复。
运行候选前用`Get-FileHash -Algorithm SHA256`对照摘要，再按上节前台启动，仍默认空路由。

`ops/test-delivery.ps1`使用明确标注的合成报告和Wrapper替身检查回执失败路径；其结果仅为脚本回归，
不能作为Java构建通过证据。测试文件保留在独立`target/delivery-tests/<UUID>`。

正常停机回归以测试专用javaagent从测试持有的子进程stdin触发`System.exit(0)`，执行真实JVM shutdown hook。
应用仍运行交付JAR；agent不进入产品JAR，不提供产品关闭端点，也不修改产品默认超时。
用例检查期限内在途请求完成、超期请求失败、停止接收新连接、进程有界退出及双端口释放。
该机制验证应用关闭流程；Windows控制台Ctrl+C、Windows服务管理器及Linux SIGTERM仍需在对应运行环境验收。
