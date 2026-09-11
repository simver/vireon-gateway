# Gateway PLAN-0002：独立交付与运行验收

- 日期：2026-09-11；所有者及维护人：Gateway / 本任务主代理。
- 状态：G0—G4本地独立交付与验收完成，普通模式；提交记录以Git历史为准，未创建Goal、推送、部署或删除。
- 基线：`85caba403222ededd33fd59d7bb3bee284969ed4`；沿用用户“先做好Gateway本身、不联合其他项目验证”的范围。
- 依据：[开发流程](../development-workflow.md)、[PLAN-0001](PLAN-0001-basic-gateway.md)、
  [ADR-0003](../adr/ADR-0003-gateway-independent-acceptance.md)、[运行契约ADR-0004](../adr/ADR-0004-static-webflux-runtime.md)。

## 1. 为什么做这一批

基础路由、透传、错误映射和管理隔离已完成。2026-09-11提交前完整验收为62项测试通过，
证据在本地`target/commit-verify.log`；本轮只核对历史记录，不重新声称运行测试。
目前`ops/verify.ps1`可完成本仓门禁，但回执仍由代理手工生成；最终62项候选没有新的仓库外源码快照验收。
已有JAR测试覆盖启动、配置拒绝和协议故障，未明确证明端口冲突失败后的资源释放及正常停机时在途请求的结果。
现场`git remote -v`为空，仓库无CI配置；不能假设GitHub、远程Runner或制品库已经可用。

建议下一批交付一个可以从源码验证、核对摘要并独立启动的本地候选，补齐交付证据和进程生命周期。
不重复实现代理功能，也不以增加配置、模块或测试数量为目标。

## 2. 目标及边界

Goal草案：仅使用Gateway源码、Wrapper和正式依赖，完成可自动生成证据的独立JAR交付，
证明启动失败、正常服务及受控停机的行为，保持既有外部HTTP和管理隔离契约。
只有用户明确要求创建Goal才创建；开发授权本身不包含Goal、Git提交、推送或部署授权。

本期包含：统一验证回执、冻结输入后的独立构建、同环境JAR可重复性验证、进程生命周期回归、交付说明。
本期不包含：IAM/Audit/QBC联验、登录状态机或前端、真实业务路由启用、Nacos、限流/熔断/灰度、
新的日志监控平台、镜像/部署编排、远程CI或制品发布、依赖版本升级。System/QBC及默认空路由保持现状。

## 3. 实施顺序与批次门禁

| 批次 | 工作与预计资产 | 完成证据 |
| --- | --- | --- |
| G0 契约核验 | 阅读现有验证入口、JAR测试和当前Boot/Netty停机机制；确定回执字段、冻结输入范围和停机断言 | 将官方机制、测试方法及约束补入本计划；不依赖猜测信号语义 |
| G1 验证回执 | 扩展本仓验证入口，自动读取本次Surefire/Failsafe结果，记录源码状态、运行版本、JAR摘要与各门禁结果 | 成功、失败、缺失报告均有明确结果；失败返回非零，不读取旧报告冒充本次PASS |
| G2 独立候选 | 从明确输入清单复制到全新任务目录，排除target、.git、环境秘密和相邻源码；复用常规外部Maven缓存 | 输入摘要匹配；新目录自身Wrapper完成完整verify；候选JAR绑定同一次验收回执 |
| G3 生命周期 | 扩展GatewayJarIT：分别占用业务/管理端口；验证失败退出及另一个端口释放；验证受控正常停机 | 测试持有精确子进程和socket，观察退出码、在途请求和退出后端口状态，不终止其他进程 |
| G4 交付复核 | 运行完整门禁，整理JAR、摘要、回执及运行说明；未参与实现者审查新补丁 | 当前候选全部适用门禁通过，问题修复复核闭环；更新本计划及README，不预填部署结论 |

G1、G2采用一份简明结构化回执，不创建独立证据服务或复杂框架。普通开发继续复用常规缓存；
源码冻结复制仅用于候选交付，不变成每次编译的强制步骤。

## 4. 具体验收要求

| 编号 | 验收场景 | 通过标准 |
| --- | --- | --- |
| A01 基础契约 | 现有精确路由、Cookie/认证头、错误与管理隔离回归 | 全部继续通过，不删除或放宽原断言来满足新门禁 |
| A02 回执真实性 | 门禁成功、Maven失败、报告缺失/陈旧、文档失败 | 仅本轮全部完成才标PASS；失败阶段可定位；无跳过被计入完整验收 |
| A03 候选标识 | 干净提交与开发中工作区 | 记录HEAD及dirty状态；未提交输入另有文件摘要，不能仅用HEAD代表工作区 |
| A04 源码独立性 | 新目录无原target、无相邻源码或输出 | 用本仓Wrapper及正式坐标构建运行；需要重新联网解析时如实记录，不假称离线成功 |
| A05 可重复产物 | 相同冻结输入、依赖、JDK/Maven和归档时间在两个全新输出目录打包 | JAR SHA256一致；不承诺跨操作系统/JDK一致，不通过忽略差异或跳过测试掩盖失败 |
| A06 端口冲突 | 分别由测试socket占用业务端口和管理端口 | Gateway有限时间内非零退出、不干扰占用者、无监听或子进程残留 |
| A07 正常停机 | 正常在途请求、超过关闭期限的请求、关闭后探针与连接 | 正常请求在期限内完成；超期行为明确且进程有界退出；停止后不继续服务；不把强杀当正常停机 |
| A08 信息与资源 | 回执、日志、交付文件及测试清理 | 不记录认证/Cookie正文或秘密环境值；只操作测试持有资源，不递归删除或清理公共缓存 |

G1须解决历史报告污染后才将其回执用于G2/G4。G3须先验证操作系统下的正常停机方式；
若Windows进程终止机制不能触发正常关闭，应记录未验证项并选择可验证机制，不能只断言进程消失。
不因本机缺少Linux环境、CI或容器而伪造跨平台通过；对应环境验证另列后续准入。

## 5. 官方机制与设计约束

优先使用Maven的`project.build.outputTimestamp`控制归档时间；固定值应由冻结候选确定，
禁止使用每次构建当前时间。需要核验Boot重打包结果也满足一致性，不能只比较未重打包JAR。
来源：[Maven可重复构建指南](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)，2026-09-11查阅。
当前技术栈不升级；新增插件或参数须在G0核验与现有版本兼容，不预设某个插件已被验证。

远程CI另行实施时可复用统一verify及回执，参考
[GitHub Maven构建与测试指南](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven)。
本仓尚无远端，当前不生成无法实际验收的CI占位文件、不创建仓库或上传代码。

## 6. 执行方式、停止条件与回滚

- 建议主代理连续完成G0—G4，最终由一名未参与实现的审查者检查证据真实性、资源归属及生命周期断言；
  共享契约未固定前不并行开发。本轮不派发实现任务，不设置模型或token预算。
- 实施前需用户明确授权这一批范围。G0中的技术选择由实施者调查闭合；若需要改变HTTP/健康语义、
  扩展外部项目或选择正式部署平台，则先提出对应决策并等待实际答复。
- 若需改变健康语义、超时默认值或运行平台边界，先记录新ADR；本计划不隐含修改ADR-0004。
- 发现安全回归、非任务资源冲突、候选输入变化或证据无法绑定时停止该批验收并修正，不宣布PASS。
- 回滚为恢复此前脚本、POM和测试实现；尚无线上部署、数据库或迁移。临时证据目录保留并登记用途，
  不自动删除目录、分支、日志或公共缓存。

## 7. 本轮规划记录

已完成源码/流程/提交状态及官方资料核对、计划登记和旧索引状态校正。
本轮只修改Markdown；只运行文档链接和差异检查，不执行Maven、JAR、CI、容器或业务服务。
后续顺序建议：本计划完成后，再根据用户范围决定真实服务接入验收或镜像/部署平台规划；
二者均不自动进入本期。Nacos等上线增强继续后置。

## 8. G0技术闭合与实施记录（2026-09-11）

- 用户已同意继续推进；普通模式执行G0—G4，最后进行独立审查。
- 回执采用JSON，记录runId、阶段、HEAD/dirty、输入清单SHA256、版本、报告摘要、计数及JAR摘要。
  每次Maven使用全新UUID报告目录，成功后检查报告存在、时间和零失败/错误/跳过；compile/test不标完整验收。
- 冻结清单为根级AGENTS/README/.gitignore/POM/Wrapper，以及src、ops、docs、.mvn内文件；
  拒绝reparse point和秘密/构建输出文件，未提交源码按实际字节纳入摘要。两份副本先冻结后构建；
  使用仓库外新目录和普通Maven缓存，每份独立完整verify，比较Boot重打包JAR。目录和原始失败证据保留。
- 固定`project.build.outputTimestamp=2026-09-11T00:00:00Z`进入POM冻结输入；不新增依赖或升级插件。
  官方机制：[Maven可重复构建](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)、
  [Boot重打包参数](https://docs.spring.io/spring-boot/4.0/maven-plugin/packaging.html)，同日查阅；最终以双构建摘要验证。
- 停机使用仅测试期javaagent通过其子进程stdin触发`System.exit(0)`，进入真实JVM shutdown hook；
  应用仍以最终JAR启动，不增加产品关闭端点或测试classpath应用。测试参数控制关闭期限，不改产品默认。
  依据：[Boot正常关闭](https://docs.spring.io/spring-boot/4.0/reference/web/graceful-shutdown.html)、
  [JDK25 Runtime退出](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Runtime.html#exit(int))。
  此证据不代表Windows控制台Ctrl+C、服务管理器或Linux SIGTERM已验收。A07未验证不得整体验收PASS。
- 基线完整verify通过；首次沙箱运行因JDK java.security读取被拒，原始失败保留于
  `target/plan-0002-baseline.log`；正常权限成功记录为`target/plan-0002-baseline-authorized.log`。

## 9. G1—G4实现与审查记录

- G1/G2：已实现`ops/delivery-common.ps1`、`ops/verify.ps1`、`ops/deliver.ps1`，并增加POM独占报告目录配置。
  回执脚本反例由`ops/test-delivery.ps1`验证；合成结果不替代Gateway测试。
- G3：新增两个端口冲突和两个正常关闭参数场景，仍由最终应用JAR运行；仅测试agent位于src/test。
  收紧后的正常停机定向2项通过，记录`target/plan-0002-g3-shutdown-final.log`。
  前次G3运行因测试期间修改导致最后格式检查失败，记录`target/plan-0002-g3-verify-retry.log`，已格式化修复。
- 独立审查未发现G3阻断问题；G1发现两个P2：外部Maven参数可缩减门禁、JDK自动回显可能记录秘密参数。
  修复为首次Java启动前拒绝外部选项、固定本仓maven.config、禁用用户mavenrc并恢复调用方环境。
  Wrapper额外的DEBUG_OPTS/BASEDIR/JVM_CONFIG及批处理echo/pause通道同样拒绝，反例覆盖实际入口。
- 本仓完整Maven verify已运行66项（53单元/协议+13 JAR）且零失败/错误/跳过；首次新回执将真实XML的
  name属性误作节点名，故该轮回执正确标FAIL而非PASS。已修正为显式get_Name并加入含name属性反例。
  原始失败回执保留在`target/verification/ae0637fc1d6f4c71b50bcf67d5a3a06c/receipt.json`。
- 最终准入为修复后两份冻结源码各完整verify、回执PASS且JAR摘要一致；候选目录、摘要及实际结果在验收后补记。
  验收后补记若只改文档，需明示与冻结清单差异，不能把后来文档冒充构建输入。
- 首次仓库外候选`%TEMP%/vireon-gateway-plan0002-20260911-final`运行66项通过，但Spotless在无.git环境下
  采用不同换行判断，完整门禁失败，交付回执为FAIL。已在POM显式固定Java为UNIX/LF，现有Java字节无需改变。
  依据：[Spotless换行配置](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md#line-endings-and-encodings-invisible-stuff)。
  首次目录保留；修复后重新冻结到新的候选目录，不修改旧回执。
- 第二次候选`%TEMP%/vireon-gateway-plan0002-20260911-final2`完整Maven门禁通过，回执在产物阶段失败：
  实测Boot将JAR文件LastWriteTime也设为固定归档时间，不能用该时间判定本轮产物。
  已改为GatewayJarIT在全部JAR测试前后核对SHA256，并在本轮独占报告目录写入tested-artifact.sha256；
  回执只接受与实际测试摘要一致的交付JAR。新增缺失摘要/摘要不匹配反例，历史文件时间本身不再构成失败。
  第二次FAIL保留；后续重新冻结完整验证，不重写旧回执。
- 最终摘要绑定修复的针对性验证成功：53项单元/协议加1项JAR用例，完整命令输出
  `target/plan-0002-artifact-targeted.log`；这不是全量JAR验收。
  脚本25项合成回归通过：`target/delivery-tests/7d052565da724cf18d1ca431b064fd9b/results.json`。
  格式规则与摘要证明修复均经未参与实现的审查者只读复核，无未闭合P1/P2。

## 10. 最终验收与交付（2026-09-11）

已实现、已测试：G0—G4完成；无待用户决策项，无未闭合审查问题。验收时未提交、未部署；
HTTP/健康、默认路由、产品超时和日志默认值均未改变。真实业务联验和操作系统停止信号仍在范围之外。

| 证据 | 实际结果 |
| --- | --- |
| 本轮脚本回归 | 25项通过；包括成功、Maven失败、缺失/陈旧报告、失败/错误/跳过、文档失败、外部参数拒绝、测试产物摘要缺失/不匹配 |
| 冻结副本build-a | 自身Wrapper完整verify，53单元/协议+13 JAR，共66项；失败/错误/跳过均0；回执PASS |
| 冻结副本build-b | 同上，独立新输出完整66项；失败/错误/跳过均0；回执PASS |
| 格式/架构依赖/文档门禁 | 两份副本完整verify及文档检查通过，未跳过Spotless或Enforcer |
| 输入与产物 | 两份40文件冻结输入一致；测试证明、回执摘要与交付JAR一致；双JAR SHA256相同 |
| 生命周期 | 业务/管理端口冲突、正常在途请求完成、超期请求失败、有界退出与端口释放通过 |
| 独立审查 | 两项P2已修复并复核；后续格式规则及摘要绑定修复复核通过；无未闭合P1/P2 |
| 信息边界 | 合成秘密参数拒绝且不回显；协议标记日志断言通过；定向私钥/常见密钥格式扫描未命中；非生产秘密全面审计 |

最终命令为本仓`ops/deliver.ps1`，指定普通外部缓存`C:/Workbench/Data/.m2/repository`和全新目录：
`C:/Users/simver/AppData/Local/Temp/vireon-gateway-plan0002-20260911-final3`。
目录内`delivery.json`为PASS，`build-a`、`build-b`各自保留日志及`target/verification/<runId>/receipt.json`。
允许联网解析，未声称离线成功；没有远程上传、发布或部署。前两份失败目录及本仓原始失败记录全部保留。

- 冻结输入摘要：`92F98440C786BFB9EB7FAA488D4D5B850088EB95692E25A9D3D77F92A091A409`。
- 最终Boot可执行JAR：`vireon-gateway-0.1.0-SNAPSHOT.jar`，目录内`SHA256SUMS`可复核。
- JAR SHA256：`64D129B454316CD0E1BF9C8320F7E10552622295C0DDBC6528A610E8C4516D0B`。
- 实测JAR含87个嵌套运行库，未打入ShutdownTestAgent/GatewayJarIT或测试agent JAR。
- 验收后的变更仅为本节、计划索引、文档索引与根README状态补记。冻结副本保持原样；
  当前工作区文档字节因此不同于冻结清单，代码、测试、POM及验证脚本不变。后补文档只重新检查链接和差异。

Windows控制台Ctrl+C、服务管理器停止、Linux SIGTERM、真实IAM/Audit/QBC、浏览器、TLS及部署均未验证。
本期正常停机证据严格限于测试触发的JVM shutdown hook与应用关闭流程；同环境JAR一致不代表跨平台或第三方复现。

## 11. 本地提交登记

2026-09-11用户明确授权提交本批代码。提交范围包括PLAN-0002、验证/交付脚本、POM、JAR生命周期测试及相关文档，
不包含target或仓库外候选目录。提交前核对冻结输入，代码、测试、POM和脚本保持验收字节，只有上述文档状态补记不同；
复用本节之前的实际测试证据，不将提交前文档检查称为重新运行Java测试。提交标识以Git历史为准，未推送或部署。
