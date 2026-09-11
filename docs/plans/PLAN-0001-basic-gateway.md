# Gateway PLAN-0001：基础 Gateway 自身开发与独立验收

- 日期：2026-09-09；范围更新：2026-09-10；所有者及维护人：Gateway / 本任务主代理。
- 状态：2026-09-10用户授权执行开发；G0—G5完成，Gateway自身开发与独立验收通过；未创建Goal、未提交、未部署。
- 用户要求：基础 Gateway 独立运行、静态路由、请求与 Cookie 透传、超时和错误处理、管理面隔离；
  2026-09-10明确先不联合其他项目验证。原真实前端登录、改密、刷新、退出和业务调用移至后续接入阶段。
- 约束：[开发流程](../development-workflow.md)、[独立边界 ADR-0001](../adr/ADR-0001-gateway-boundary.md)、
  [分阶段 ADR-0002](../adr/ADR-0002-staged-gateway-delivery.md)、
  [自身独立验收 ADR-0003](../adr/ADR-0003-gateway-independent-acceptance.md)。首批范围以ADR-0003为准。

## 1. 现场与证据

本任务前轮只读调查：Gateway HEAD 为 `9bca2ee58a6a7c4a2b27775c261bc8228ee0bc17`，master 只有治理文档；
README、docs/README 和未跟踪 ADR-0002 为已有修改。本轮继续前复核其状态，保留内容，不纳入任何隐含提交授权。
Mix 前轮读取 HEAD 为 `4cebf5117876d21fa65fbe12466e083122419520`，并存在其他任务修改；后续消费前再次冻结，
不把历史路线文档中的旧 HEAD 当作当前候选。本轮不修改 Mix。

参考来源均于本任务 2026-09-09 读取，路径相对各自仓库根；这些是调研定位，不是构建路径依赖：

| 所有者及来源 | 对本计划的作用 |
| --- | --- |
| Mix `docs/development-workflow.md`、`docs/agent-execution-standard.md` | 编号计划、官方依据、批次门禁、证据及代理模式 |
| Platform `docs/plans/PLAN-0003-iam-audit-master-integration.md` | 冻结提交和 JAR、独立构建、真实组合及验收边界；不继承历史 PASS |
| Platform `docs/contracts/identity-audit-v1.md` | Cookie、CSRF、首次改密、刷新重放和浏览器接口合同 |
| IAM `services/vireon-iam-service/src/main/java/com/simxe/vireon/iam/security/IamCookies.java` | 多条 Set-Cookie、路径与清除行为 |
| IAM 同目录 `IamRequestBoundaryFilter.java`、`IamHttpSettings.java` | 重复认证拒绝、精确 Origin、安全传输与回环限制 |
| IAM/Audit 各自 `src/main/resources/application.yml` | 独立管理端口，默认不解释代理头 |
| Framework `build/vireon-parent/pom.xml` | 参考 JDK 25、Boot 3.5.16、Cloud 2025.0.3；不是 Gateway 锁定值 |

官方调研入口：[支持矩阵](https://spring.io/projects/spring-cloud/)、
[WebFlux 运行栈](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/starter.html)、
[头处理](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/httpheadersfilters.html)、
[超时](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/http-timeouts-configuration.html)。
前轮查询支持页面时 2025.0.x 被标为停止支持；G0 须复核支持范围、精确版本和解析，不只照抄参考基线。
4.3 文档用于机制调研，不证明最终候选版本；最终选型须记录对应版本资料和差异，不自动升级兄弟项目。

## 2. Goal 草案和范围

拟定目标：在独立 Gateway 仓库完成可独立构建和运行的基础服务，提供静态路由机制、请求与 Cookie 透传、
有界超时、错误处理、代理头防护和管理面隔离；使用本仓测试下游完成独立协议、安全及故障验收，
交付自身JAR、运行说明、测试和独立审查证据。不依赖其他业务项目运行或参与验证。

本期不做真实业务调用；不运行IAM/Audit及其数据库，不开发最小前端页面。测试替身仅存在于本仓测试范围，
不发布为业务服务，不实现登录/改密等状态机。未经真实接入验证的路由默认关闭；System/QBC保持404。
不含 Nacos、集群、灰度、分布式限流、网关登录、DTO 聚合、审计扩展、完整管理后台、生产镜像发布和部署。
独立镜像与部署所有权保持；本期 JAR 验收不宣称镜像或发布验收完成。上传、SSE/WebSocket 随真实业务另定。

## 3. 待决事项与技术准入

| ID | 建议及待完成工作 | 责任与状态 |
| --- | --- | --- |
| D1 联验范围 | 本期只做Gateway自身独立验证，其他项目联合验收后置 | 用户2026-09-10明确，已闭合 |
| D2 联调环境 | 真实前端和共享HTTPS环境不在本期；测试使用隔离本机临时端口 | 原问题本期不适用，不推断未来环境选择 |
| T1 版本和栈 | JDK25、Boot4.0.8、Cloud2025.1.3/Gateway5.0.3、Maven3.9.16/Wrapper3.3.4；Central正式POM和分发摘要已核验 | 已闭合，ADR-0004 |
| T2 Framework | 不引入Framework；独立Boot parent/Cloud BOM，无Servlet业务运行栈 | 已闭合，依赖门禁；无需Framework空缓存消费者验证 |
| T3 拓扑 | 本仓Gateway+临时HTTP下游；默认18080/19080绑定回环，测试动态端口、finally停止子进程 | 已闭合，运行说明 |
| T4 信任 | 不信任客户端代理头，Host按下游URI，实际管理监听端口只允许health三个路径；TLS后置 | 已闭合，协议反例与JAR验收 |
| T5 配置和错误 | 类型化校验；连接2秒/响应10秒；固定JSON码、502传输失败、504超时，下游状态透传 | 已闭合，ADR-0004；不是生产SLO |
| T6 测试设施 | 原始socket下游支持重复头/正文、Set-Cookie、延迟/断连、分块/心跳、调用次数和取消观测 | 已闭合，无业务状态/数据库，默认空路由 |

D1已明确，原D2随联合验收后置。用户随后明确“执行开发”，本期实施已授权；未明确请求创建Goal，因此普通任务持续执行。
T1—T6方案见 [ADR-0004](../adr/ADR-0004-static-webflux-runtime.md)，编码前已记录。
官方配置优先；确需重复头或故障映射适配时限定在受支持扩展点，并提供反例。不得自建通用 HTTP 代理。

## 4. 实施批次

| 批次 | 工作和交付 | 退出门禁 |
| --- | --- | --- |
| G0 计划定稿 | 完善本计划、流程与必要 ADR，闭合 T1—T6，确定验证入口和所有者 | 范围已明确，关键技术有证据；实施和 Goal 授权分别明确 |
| G1 独立运行 | 自有 POM/Wrapper/JAR、配置校验、独立管理端口、健康、启动/停止和日志入口、质量门禁 | 脱离相邻源码/输出构建运行；Gateway 不依赖数据库/Nacos启动；健康不伪称下游业务就绪 |
| G2 静态透传 | 唯一静态事实源、精确路径段、完整路径和 query、方法/正文/多值头/Cookie | 测试配置正反例通过；默认无虚假活跃路由；已启用下游故障不变成404 |
| G3 安全和故障 | 超时、不可达、代理头、管理/内部隔离、脱敏、取消和资源释放 | 故障可观察且有界；下游错误不重写；写请求不自动重试 |
| G4 独立运行验收 | 冻结Gateway JAR，真实启动自身进程及本仓测试下游，通过HTTP客户端验证 | 本期适用A01—A12有可复核证据；不启动其他项目、数据库或浏览器前端 |
| G5 交付 | 独立审查、返修、完整门禁、独立构建、文档/敏感信息检查和回执 | 必验项通过且无阻断问题；证据绑定最终候选；剩余范围明确 |

G2/G4测试下游用于观察HTTP与故障，是本期独立验收设施，不代表真实业务接入通过。各批次先受影响测试，再运行本仓适用门禁；
无变化且无未解风险不重复测试。若修改下游，回到其所有者计划与组合候选管理，不扩大本 Goal。

## 5. HTTP 与验收矩阵

以下均为待实施要求，不预填通过；最终记录命令、测试标识和回执位置。

| ID | 场景 | 预期和证据 |
| --- | --- | --- |
| A01 路由 | 前缀自身/子路径、相邻前缀、System/QBC、编码/重复斜线/点段等边界 | 合法路径完整透传；未启用路径404；歧义路径不能绕过边界，拒绝规则在G0锁定 |
| A02 协议 | 方法、正文、query 编码/多值、响应状态与必要头 | 保持语义，不二次包装JSON；hop-by-hop 按协议处理，不承诺所有头字节不变 |
| A03 Cookie | 多个Set-Cookie、同名清除后重设、轮换/删除形式 | 传输层保持条目、顺序及Path/Domain/Secure/HttpOnly/SameSite/Max-Age等属性；不宣称浏览器生命周期验收 |
| A04 冲突输入 | 重复Authorization/Origin/CSRF、重复同名Cookie、多认证来源 | 测试下游仍可观察冲突，不去重或择一；若网关协议层提前拒绝须有明确契约，不能静默选出有效凭据 |
| A05 首次改密 | 后置 | 不在本期执行，不用测试替身模拟IAM状态机或宣称通过 |
| A06 会话 | 后置 | 本期只验证Cookie传输，不验证真实刷新重放或会话撤销 |
| A07 CSRF传输 | Origin和CSRF头缺失/单值/重复；不同路径Cookie形式 | 不合成可信Origin，不擅自添加/改写CSRF或业务校验；真实CSRF验证后置 |
| A08 状态透传 | 测试下游返回成功、401、403及业务错误 | 状态/必要头/正文保持；网关不自动登录、改写为成功或重试；真实权限和数据效果后置 |
| A09 故障 | 拒绝连接、重置、响应超时、下游业务4xx/5xx、恢复 | 建议传输失败502、超时504；下游401/403/503保留；恢复后路由可用；不得泄露内网/堆栈 |
| A10 边缘 | 伪造Forwarded/X-Forwarded/身份头、Host边界、内部及Actuator路径 | 不扩大信任；客户端身份头不得成为授权依据；业务入口无法访问内部和管理面 |
| A11 资源和秘密 | 写请求超时、客户端取消、日志与测试证据扫描 | 写请求不自动重试；上游取消/资源释放有测试；动态Token/Cookie等不泄漏，扫描覆盖边界明示 |
| A12 运行文档 | 自身启动、配置、测试入口、下游显式启用条件和错误说明 | 命令可执行，说明测试路由与正式接入区别；真实OpenAPI/Scalar和前端验收后置 |

超时不证明下游写入已回滚；前端不得无条件重发写请求。响应已提交后不能承诺换成完整JSON错误，
流中断行为单独定义与验证。合法重复query由协议测试覆盖，具体服务仍可按自己的合同拒绝非法参数。
Gateway只提供自身最小健康语义，下游状态可诊断但不因单个下游故障触发全网关重启或假报全部就绪。

## 6. 本期文档与后续前端接入

G4/G5交付Gateway自身运行与配置说明、测试命令、错误和接入限制；不要求创建前端工程或浏览器联验载体。
以下保留后续前端接入清单，均不属于本期完成门禁；后续启动接入时再形成 `docs/frontend-integration.md`：

1. 唯一外部origin、/api代理、实际端口、下游Origin配置与环境限制；不改写Origin以绕过校验。
2. 先取IAM CSRF，再登录；登录/改密/退出后按合同重新获取CSRF；masked token仅放内存，通过指定头发送。
3. 初始密码登录识别CHANGE_PASSWORD，改密后识别LOGIN并重新登录；受限退出使用cancel。
4. 正常登录后me及业务读写；Audit单独获取CSRF；会话Cookie由浏览器管理，不读HttpOnly原文。
5. 合并前端并发刷新，不自动重试旧Refresh；刷新失败回到登录；一般写请求超时结果不确定，先查询确认。
6. 401/403/网关传输错误分别处理，退出只有真实服务结果才能说明持久撤销；文档访问保留服务授权。

## 7. Goal 执行、完成和停止

推荐后续采用一个根Goal持续推进G1—G5，主代理实现并负责最终门禁；安全设计及最终候选安排一个独立审查者。
推荐主代理与安全审查Astra/medium，理由是协议透传和边缘安全边界；这是执行建议，未核验或切换实际配置，
本轮实际派发security_review进行只读设计及实现复核，未核验继承模型/思考等级，不将推荐值当作实际配置。
未采集可靠token用量，不承诺成本最优。

准入：G0闭合且用户明确启动实施；创建Goal另需明确请求，不是代码开发前置。本轮按“执行开发”在普通任务中持续推进，
不创建Goal、不指定预算，不视为Git操作授权。已授权范围内连续执行和返修，不逐阶段索要确认。

完成：独立构建、本期协议/故障/安全必验项、自身进程验收、安全独立审查、文档和敏感信息检查全部满足；
回执绑定最终Gateway提交/JAR摘要和测试配置，保留失败与修复。标记“Gateway自身开发与独立验收通过”，
真实IAM/Audit接入、浏览器会话和生产部署明确未验证。A05/A06等后置项不伪填通过，也不阻塞本期交付。

停止依赖工作并说明原因：需要改变公共IAM合同或扩展到新业务；缺少Gateway必要正式依赖或自身测试环境；
所需动作超出授权；发现设计不成立。先完成不受影响且已授权的工作，不弱化本期断言；测试替身是本期明确设施。
Goal blocked/complete等工具状态遵循运行时规则，不能以普通等待或额度耗尽伪称完成。

提交、合并、推送、发布、部署和删除不属于本Goal默认授权。无新增资源时记录不适用；后续资源创建前登记
用途、精确路径/ID、日志、停止和退役条件，目录清理遵循用户精确授权与工作树受控退役规则。

## 8. 本轮记录与下一步

本轮新增本计划、计划索引和开发流程，更新AGENTS/README/文档索引；已有ADR-0002和路线内容保留。
未创建POM、源码、测试、镜像、脚本、进程、容器或工作树；未启动Goal、构建、提交、推送或部署；资源退役不适用。
验证：本轮6份修改/新增文档的20个本地链接目标均存在，git diff --check通过；未执行Java或运行验收。
Git报告用户级ignore文件读取权限警告，状态检查据实际输出记录，不扩大为全局忽略规则核验通过。
2026-09-10：按用户新决定调整首批范围，新增ADR-0003并同步计划、流程和索引；D1闭合、D2本期不适用。
本轮仍仅修改文档，未创建Goal或代码，未构建、联合验收、提交或部署。下一步由主代理补充G0自身技术核验。

### 2026-09-10 执行开发（更新前述历史状态）

- 用户授权Gateway自身开发，未授权Git提交/推送、部署或删除。工作区原文档修改保留，Mix未修改。
- 主代理实现；独立审查由`security_review`只读承担，未参与源码修改。设计初审无阻断，最终首审发现CSRF保护名遗漏
  和取消测试可能被400ms读超时误满足，已增加X-CSRF-TOKEN及心跳存活对照；待最终新候选复核。
- `compile-01.log`为Wrapper异常user.home路径失败；显式MAVEN_USER_HOME后`compile-02.log`暴露JDK安全文件沙箱读取失败。
  经自动审批允许的提升权限执行本仓Maven后正常进入构建，不执行其他项目。`compile-03.log`依赖规则误拦校验器EL库，
  调整为禁止Tomcat运行容器而非EL；`test-01.log`修复Spring7 HttpHeaders API，未降低架构/协议要求。
- `test-02.log`健康断言按官方无详情的groups/status响应修正；`test-03.log`及`verify-01.log`发现管理端口可匹配业务路由，
  增加实际端口隔离并保留反例。`verify-02.log`完整构建通过：41项单元/协议测试、3项JAR测试，无失败/错误/跳过。
  该回执早于审查返修及补充分块/日志测试，不作最终候选通过结论。
- 产物和失败日志在本仓target；Wrapper源码分发和官方Gateway源码也仅暂存target用于核验，未删除。
  共享Maven缓存复用`C:/Workbench/Data/.m2/repository`，非本任务专属资源。无数据库、容器或业务项目进程。
- 当前新增本仓JAR/测试/验证入口/运行文档；镜像、Nacos、联合会话和生产环境明确未验证。

### 最终验收与资源记录

| 项目 | 实际结果与证据 |
| --- | --- |
| 本仓完整门禁 | `ops/verify.ps1 -Phase verify -Format -MavenRepository C:/Workbench/Data/.m2/repository`；`target/verify-03.log` PASS，38项协议+13项配置+3项JAR测试，共54，失败/错误/跳过均0 |
| 格式与依赖边界 | Spotless 10个Java文件通过；Enforcer JDK/Maven/禁止业务与Servlet容器依赖检查通过；只消费公开Central坐标 |
| 独立构建 | 36个输入文件逐一校验摘要后复制至 `C:/Users/simver/AppData/Local/Temp/vireon-gateway-plan0001-20260910-151529`；在该目录用本仓Wrapper `-o verify`，无原target或相邻源码，54项同样全通过；日志为该目录`independent-verify.log` |
| 候选标识 | HEAD仍为9bca2ee58a6a7c4a2b27775c261bc8228ee0bc17，源码为未提交工作区；冻结输入清单在`target/independent-inputs.sha256`，机器回执在`target/receipt.json` |
| 本仓JAR | SHA256 `BB8E35D93BA0E0FA9D3153A406581225342D0B5C1F140EA60922897F9141A701`；绑定verify-03及独立审查 |
| 独立快照JAR | SHA256 `00418E3AD053EBC3327D7899C2F1F16B4C4630F7A3B2EC34DF030F37E5692E0A`；输入摘要一致，归档时间等构建元数据不同，未声称字节可复现构建 |
| 审查 | security_review只读复核源码、测试及verify-03回执，确认两项问题关闭、无新增阻断；未独立重跑测试，也未审查外部快照运行，后者由主代理核验 |
| 文档 | 运行说明及索引已同步；本地链接33个和git diff --check通过，外链/锚点不纳入本地链接结论；收尾只改状态文档不重跑Java |
| 敏感信息 | JAR测试用随机协议标记覆盖路径、认证/Cookie/Origin/CSRF头、正文不进入运行日志；非法URI启动值不泄漏；源码模式检查无私钥/常见真实Token命中，不代表生产全面泄漏审计 |

资源归属：本仓target为任务构建/日志/源码核验缓存；外部快照为本任务专属证据，保留供复核，交付日2026-09-10，
后续提交或用户要求清理时复查。未进行目录删除；公共Maven/Wrapper缓存保留，不属于任务退役范围。
测试用临时socket/线程和JAR子进程由测试持有并在finally关闭，未创建常驻服务、数据库、容器或工作树。
首次进程清单读取因CIM权限失败，其空输出不作为退出证据；提升权限只读复查确认无引用本仓或独立快照的Java进程残留。
Spotless/JDK25有上游Unsafe弃用警告，不影响本轮门禁；后续格式器升级时复评，未屏蔽警告或削弱门禁。

完成范围对应A01—A04、A07—A12的本期协议/安全要求；A05/A06、真实业务授权、浏览器、TLS、Nacos和生产发布仍后置。
接入真实服务前必须另做其公开合同和运行验收，默认空路由不改变。无代码提交、推送、合并或部署。

### 2026-09-10 Code Review返修

- 用户授权修复独立审查发现的两项P2：健康探针/自定义健康组别名可绕过业务端口隔离；
  下游非法状态行被识别为Gateway内部500。仅修复本仓，不扩展联合验证或提交范围。
- 管理隔离启动检查读取Boot绑定的健康组属性，同时拒绝探针附加路径开关；补充真实JAR拒绝启动反例。
- 下游解码器将非法状态码暴露为裸NumberFormatException，`target/review-fix-red.log`已记录502断言实际500的失败。
  通过HttpClientCustomizer在下游连接中标记HttpObject的解码失败为IOException，复用502处理；
  不全局映射NumberFormatException。维护成本为一处Netty客户端入站处理器，协议回归覆盖实际字节及正常请求恢复；
  若上游框架提供稳定协议异常分类，可移除此适配并保留回归。
- 修复后的定向测试`target/review-fix-focused.log`通过；完整门禁及最终复核结果待追加。
- 上述54项及独立快照摘要属于返修前候选，不能用于本次修改后的通过结论。
- 最终完整门禁：`ops/verify.ps1 -Phase verify -Format -MavenRepository C:/Workbench/Data/.m2/repository`；
  `target/review-fix-verify.log`通过，40项协议+13项配置+4项JAR测试共57，失败/错误/跳过均0；
  11个Java文件格式、依赖边界和33个本地文档链接通过。真实JAR覆盖固定及自定义健康别名拒绝启动，
  默认业务端口别名404、管理探针200，以及非法下游状态行502。
- `fresh_review`只读复核当前源码、反例和定向测试，确认原两项P2关闭、无新增P1/P2；
  审查者未重复运行构建，其复核时JAR测试尚在执行，最终4项JAR通过由主代理核验。
- 新JAR SHA256为`FAAFF938B9F4A596BA3B2FA91E2325A83290BF1B957E9E0D28A8E5D78FE941DB`；
  当前机器回执为`target/review-fix-receipt.json`，原`receipt.json`保留历史候选记录。
  未变更依赖或构建入口，本轮不重复仓库外快照构建，不将旧快照称为新候选通过。
- 本次无提交、推送、部署、目录清理及联合项目验证；测试日志和原失败证据继续保留在target。

### 2026-09-10 健康组详情审查返修

- 用户授权修复健康组覆盖全局无详情约束的P2。原JAR复现证据为`target/review-health-details-results.json`：
  管理readiness返回组件/磁盘详情，业务口仍404；不属于业务入口暴露。
- 校验所有绑定健康组：show-details/show-components仅允许null（继承never）或NEVER；
  拒绝ALWAYS和WHEN_AUTHORIZED。未修改健康路由、依赖、业务协议或管理端口边界。
- 增加真实JAR四项拒绝启动反例和显式never仍可用的正例；原默认探针测试覆盖未设置时继承。
  本轮门禁及独立复核结果待记录，之前57项回执为修改前候选证据。
- 最终门禁`target/health-group-fix-verify.log`通过：40项协议+13项配置+9项真实JAR测试共62，
  失败/错误/跳过均0；11个Java文件格式、依赖边界、33个本地文档链接及git diff --check通过。
- `fresh_review`只读复核确认该P2关闭、无新增P1/P2；其审查时JAR测试仍在执行，最终9项通过由主代理核验。
- 当前JAR SHA256为`BC5AEAD9A2F13869FDA6F9CD8F235C8862AFCCEED107567D7DB61D2DB01D719D`，
  机器回执为`target/health-group-fix-receipt.json`；旧回执保留。本轮未重复外部快照构建，未联合验证、提交或部署。

### 2026-09-11 本地提交验收

- 用户明确授权提交本次Gateway代码及配套文档；按33个明确文件路径纳入提交，不含target日志、探针和构建产物。
- 提交前重新运行本仓完整verify，`target/commit-verify.log`通过：40项协议+13项配置+9项JAR共62，
  失败/错误/跳过均0；格式、依赖边界、33个本地文档链接及暂存差异空白检查通过。
- 本批次提交标识以Git历史为准；未推送远端、部署、清理目录或联合其他项目验证。
