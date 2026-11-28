# 小米运动健康中国大陆云端数据调查

调查日期为 2026-11-24，北京时间。本人扫码读取的首项真实指标验收通过。用户在真机上完成小米扫码，并确认读到的心率与小米运动健康显示一致。个人路线只在本机使用小米签发的健康会话。官方第三方接口另有应用审批要求。自用 Android 安装包可通过 USB 安装和调试。 [Android USB 安装文档](https://developer.android.com/tools/adb#move) · [小米云端服务端接入指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2333)

## 官方云 API

| 检查项 | 已核实的证据 |
| --- | --- |
| 服务和地区 | 小米澎湃 OS 开发者平台的运动健康服务提供云端 HTTPS API。服务端指南列出的生产域名为 `hlthopen.io.mi.com`。如果选择官方第三方云接口，需要依大陆开放平台流程接入并以大陆账号实测。文档与域名只能确认服务存在，无法确认该账号或手环的数据覆盖。 [服务接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2326) · [服务端接入指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2333) |
| 应用资质 | 在开发者管理中心为应用开通小米账号服务和运动健康服务，登记回调地址，提交用途、申请材料、隐私政策、用户协议和官网链接，选择读取范围，等待审核。接入流程还写明首次接入时配置签名证书指纹。公开资料未能确认个人自用应用可以取得审批。 [服务申请及签名流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2326) · [账号服务开通流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1512) |
| 用户认证 | 用户在小米授权页登录并同意范围。`scope_provider=healthkit`，授权码单次有效，时限 10 分钟。文档要求用匹配的回调与 `client_secret` 换取令牌，刷新也需同一应用密钥。云端请求使用 OAuth Bearer 令牌、`X-Client-ID` 和不重复的 `X-Client-Nonce`。用户可在小米运动健康「我的 → 三方数据管理」撤销绑定。 [健康服务鉴权](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2333) · [授权码模式](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1708) |
| 纯手机端限制 | 运动健康服务端文档未列出 PKCE 公共客户端或设备授权流程。旧版通用文档中的隐式授权已自 2023-09-30 起下线。开放平台的共享 AppSecret 无法作为随 APK 分发的保密凭据，项目同时要求认证在手机端完成并禁止自建中转。小米若提供项目可用的独立移动端授权或书面认可的无服务器方案，需按其正式规范重新验证。 [隐式授权下线说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1717) · [RFC 8252 §8.5](https://www.rfc-editor.org/rfc/rfc8252.html#section-8.5) |
| 首次读取与分页 | 初次可用最新采样接口 `/healthkit/dataSources/latestDataPoint` 只查一个心率类型。历史采样 `/healthkit/dataSources/dataPoints` 与睡眠等健康记录 `/healthkit/healthRecords` 的单请求窗口最多 30 天，响应带 `cursor` 和 `hasMore`。采样点文档列出起止时间、`value`、`modifyTime` 和时区。部分示例的组级时间为秒、点级为毫秒，实际响应需核对后定型。 [云服务接口 §1.2、§1.4、§2.2](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2328) |
| 历史范围与时效 | 官方列出 1 个月、3 个月、1 年数据范围权限。权限范围与单次请求窗口是两个限制，实际保留期和可读历史未测。数据字典将血氧明细的查询及时性描述为小时级，当前设备各指标的上传时延未测。 [权限表](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2333) · [数据类型](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2329) |
| 限流与失败 | 服务端指南要求控制调用频率，优先按时间窗口和游标读取。采样接口的具体限额未在所读文档中确认。刷新令牌章节另列单 `client_id` 每秒 5000 次，超量返回 HTTP 503，该值不适用于推断采样配额。未带应用身份的真实请求返回 HTTP 200 和业务码 `-8`，消息为 `auth fail, invalid clientId`，响应仅有 `code`、`message`。认证后其他错误码、实际限额和 `Retry-After` 均未测。 [服务端指南 §1、§2.2.3](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2333) · [探测记录](TEST_REPORT.md) |

### 官方 P0 指标核对

| 指标 | 官方文档所示语义 | 本项目状态 |
| --- | --- | --- |
| 心率 | `com.xiaomi.heart_rate.delta`，`bpm` 单位为次/分，使用采样点时间。 | 等待授权验证 |
| 静息心率 | `com.xiaomi.resting_heart_rate.instantaneous`，接入流程将其归入心率权限。云字典的 `bpm` 单位栏写成 `mmol/L`，端侧字典写为次/分。需核对真机数据与官方显示。 | 单位文档存在冲突，等待实测 |
| 血氧 | `com.xiaomi.spo2.instantaneous`，`spo2` 为百分数，文档范围 `(0,100]`。 | 等待授权验证 |
| 步数 | `com.xiaomi.steps.delta` 的 `steps_delta` 为区间步数，日总量 `com.xiaomi.steps.total` 是独立类型。 | 等待验证采样区间及多源融合规则 |
| 睡眠 | `com.xiaomi.sleep.health` 提供健康记录，云接口示例在 `subData` 中包含 `com.xiaomi.sleep_state.delta` 分段。 | 等待验证跨日时间、分期编码及覆盖 |

来源见 [云端数据字典](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2329)、[端侧数据字典](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2331)、[服务接入权限表](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2326)、[云端睡眠响应结构](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2328)。

## 参考项目源码

下列证据来自 Git 实际检出的文件和作者的公开对照记录。源码许可证针对代码再使用。小米端点的第三方接入权限由小米的服务规则与授权决定。

### Jenwein/mifit

- 固定提交 `ed8fd7f36ca9729f94471cbb85428a1435ea7b72`，2026-11-14，GitHub 未归档。MIT，另有依赖来源与归属说明。作者报告对照过一个大陆账号（UTC+8），其他地区未覆盖。 [固定提交](https://github.com/Jenwein/mifit/tree/ed8fd7f36ca9729f94471cbb85428a1435ea7b72) · [许可证](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/LICENSE#L1) · [第三方归属](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/THIRD_PARTY_NOTICES#L1-L25)
- `auth/qr.py` 请求小米账号二维码、等待手机扫码确认，再经 STS 获得小米运动健康客户端会话。`region.py` 和 `transport.py` 分别配置客户端服务与签名传输，使用 `hlth.io.mi.com`。作者说明默认保存的会话含账号级续期凭据，并明确提醒自动调用可能触发账号限制。二维码完成的是客户端会话登录，本项目缺少小米为第三方应用签发限定健康读取范围的许可依据。 [二维码与 STS 源码](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/auth/qr.py#L397-L558) · [服务与地区](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/region.py#L12-L22) · [会话范围](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/README.md#L63-L77) · [使用限制](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/README.md#L521-L528)
- 心率解码 `time`（Unix 秒）和 `bpm`。日静息心率来自 `avg_rhr`，血氧 `spo2` 为百分数，步数实现读取日汇总。睡眠实现关联跨日会话、日汇总和阶段。作者报告心率、步数、日静息心率、血氧与 APP 核对通过，另记录睡眠合计和分段时长核对结果。 [心率源码](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/domains/heart_rate.py#L111-L138) · [静息心率源码](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/domains/heart_rate_daily.py#L59-L69) · [血氧源码](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/domains/spo2.py#L56-L71) · [步数源码](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/domains/steps.py#L41-L100) · [心率、步数对照](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/docs/superpowers/verification/2026-10-14-core-read-live.md#L64-L84) · [静息心率与血氧对照](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/docs/superpowers/verification/2026-10-15-end-of-run-checklist.md#L70-L139) · [睡眠对照](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/docs/superpowers/verification/2026-10-14-sleep-live.md#L1-L34)
- 分页读取 `has_more` 和 `next_key`，检测重复或缺失游标。作者观察到原始密集采样查询可能漏首桶、当天云端数据可能尚未齐全。库默认请求间隔约 1 秒另加抖动，CLI 默认限制 92 天，完整心率模式最多 10 页。这些是客户端设置，服务的强制配额、实际延迟和最长历史仍未知。遇 HTTP 401 可尝试一次续期，失败则要求重新认证。 [分页及传输](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/transport.py#L76-L110) · [游标处理](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/transport.py#L254-L303) · [限制与延迟](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/README.md#L445-L502)

### ridd1ot/xiaomi-health-sync

- 固定提交 `96550584d53c126ae612ddbe61717b5848f8f3c3`，2026-08-26，GitHub 未归档。README 声明项目代码为 MIT，固定提交无独立许可证文件，GitHub `licenseInfo` 为空。README 将传输依赖标为 GPL-3.0。依赖源码发布包的元数据未确认许可证声明，分发前须另行核实。 [固定提交](https://github.com/ridd1ot/xiaomi-health-sync/tree/96550584d53c126ae612ddbe61717b5848f8f3c3) · [作者许可声明](https://github.com/ridd1ot/xiaomi-health-sync/blob/96550584d53c126ae612ddbe61717b5848f8f3c3/README.md#L158-L183)
- 实际代码调用 `mi-fitness>=0.2.0` 的扫码辅助和 STS 客户端。GitHub 公开访问上游 `MistEO/MiSDK` 返回 404。通过 PyPI 下载并校验 `mi_fitness-0.2.0.tar.gz` 的 SHA-256 `0fb1bb16cbec948531e3bf7de8ac6456c2665775b059e64f441c42eb625bf369`。只读检查包内 `src/mi_fitness/auth/qr.py:20–53`、`auth/sts.py:14`、`client/base.py:67–156`、`client/data.py:122–172` 和 `client/data.py:176–185`，确认它走客户端二维码、STS、加密读取及 UTC+8 日窗口。项目源码还对本人和亲情共享两种请求路径分流，亲情数据超出本项目范围。 [依赖声明](https://github.com/ridd1ot/xiaomi-health-sync/blob/96550584d53c126ae612ddbe61717b5848f8f3c3/requirements.txt#L1) · [扫码辅助](https://github.com/ridd1ot/xiaomi-health-sync/blob/96550584d53c126ae612ddbe61717b5848f8f3c3/scripts/login_helper.py#L21-L43) · [本人和亲情请求](https://github.com/ridd1ot/xiaomi-health-sync/blob/96550584d53c126ae612ddbe61717b5848f8f3c3/health_vault/sync.py#L17-L32) · [PyPI 固定版本](https://pypi.org/project/mi-fitness/0.2.0/#files)
- 同步代码读取日步数、睡眠时长（分钟）及分段、心率（bpm）和血氧历史，再写入 SQLite。项目 README 记载历史血压可能为空、令牌过期时需重新扫码。其主要读取层通过 `days` 构造日窗口，未凭该实现确认逐条历史的完整性、跨区可用性、服务端限流或云端延迟。此项目未提供独立的小米第三方授权流程。 [同步代码](https://github.com/ridd1ot/xiaomi-health-sync/blob/96550584d53c126ae612ddbe61717b5848f8f3c3/health_vault/sync.py#L103-L218) · [已知行为](https://github.com/ridd1ot/xiaomi-health-sync/blob/96550584d53c126ae612ddbe61717b5848f8f3c3/README.md#L149-L164)

### Aaron-gx/mihealth-api

- 固定提交 `05edd1308025795e124f8958b20f8408c41b1c82`，2026-09-30，GitHub 未归档，MIT。源码默认 `cn`，也有其他地区配置，当前国行手机未验证该实现。客户端向 `hlth.io.mi.com` 发签名和加密请求。许多 README 中的 `/api/...` 是项目的 Flask 本地网关路径，小米官方生产域名以官方服务端指南为准。 [固定提交](https://github.com/Aaron-gx/mihealth-api/tree/05edd1308025795e124f8958b20f8408c41b1c82) · [许可证](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/LICENSE#L1) · [地区与传输](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/mihealth_client.py#L217-L315) · [本地网关](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/README.md#L143-L169)
- `login.py` 含密码和二次验证的客户端会话复现，登录与验证中模拟米家客户端身份，也支持使用已持有的账号级会话续期。仓库还提供 Root 或 Hook 取会话的备用流程。登录方式与本项目的合规授权条件不符。 [登录源码](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/login.py#L91-L190) · [二次验证源码](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/login.py#L213-L259) · [作者的备用流程说明](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/README.md#L86-L140)
- 健康读取代码处理 `data_list`、`has_more`、`next_key`，显式按请求区间过滤超界记录。作者说明请求区间用毫秒，心率、血氧和睡眠等记录时间常用秒，部分睡眠汇总字段用分钟。可见 `steps`、`bpm`、`spo2` 和睡眠。静息心率的独立返回语义、可取得的最长历史和最新记录延迟未据此确认。同步默认请求间隔 0.4 秒，代码对 HTTP 429 等状态有限重试，支持 401 时更新会话。本地间隔与页数上限不代表平台限额。 [读取与翻页源码](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/mihealth_client.py#L338-L394) · [重试与会话源码](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/mihealth_client.py#L217-L328) · [单位及分页说明](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/API.md#L87-L116) · [指标和日志语义](https://github.com/Aaron-gx/mihealth-api/blob/05edd1308025795e124f8958b20f8408c41b1c82/README.md#L171-L196)

## 个人导出、端侧服务及使用条款

- 小米账号隐私中心提供个人数据下载。小米的 Mi Fitness 支持说明写明「隐私 → 管理您的数据 → Mi Fitness」，并按提示绑定邮箱。大陆账户本次未登录，产品条目、导出字段、历史范围、生成时延和格式未实测。公开说明是用户手动导出，不能作为本项目自动轮询的云 API。 [大陆隐私支持](https://privacy.mi.com/support/faq?locale=zh-cn) · [Mi Fitness 官方导出说明](https://www.mi.com/my/support/article/KA-11566/)
- 小米官方端侧 SDK 走系统 HealthKit 本地服务，要求 HyperOS 4.0 及以上并完成包名、签名和权限配置。设备检测到 HyperOS 3.0，包列表未显示 `com.xiaomi.healthkit`。该 SDK 走本地服务数据读取，超出本项目仅云端的边界。 [官方端侧接入指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2332)
- 旧版小米健康云文档明确面向生态链企业和合作伙伴。本次未找到证据证明它能读取当前国行小米运动健康账户记录。参考仓库的数据调用与米家 IoT 设备列表属于不同服务。 [旧版开放平台文档](https://dev.mi.com/docs/micloud/health/)
- 小米运动健康隐私政策描述了产品数据和授权管理。小米账号协议适用于账号使用，另有小米云服务协议列出反向工程限制。公开材料未给出这三个客户端复现项目的第三方接入许可，也未据通用小米云条款判定某一具体端点的法律适用范围。 [小米运动健康隐私政策](https://watch.xiaomiwear.com/html/privacy/index.html?locale=zh_cn&model=app&type=sport_healthy_privacy) · [小米账号使用协议](https://static.account.xiaomi.com/html/agreement/user/zh_CN.html) · [小米云服务用户协议](https://i.mi.com/useragreement?lang=zh&region=CN)
- 已登录的电脑端管理中心直接跳转到开发者注册页，新注册表述为企业开发者，另一入口绑定已有账号。服务流程允许配置测试签名，未找到以个人账号直接开通该健康云服务进行自用测试的说明。小米账号使用协议 §2.2.4 涉及第三方取得账号信息的限制。个人自己制作、只在本机运行的工具是否可以走官方 App 会话，公开资料没有针对该场景的明确说明。 [开发者管理中心](https://dev.mi.com/xiaomihyperos/console) · [服务接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2326) · [小米账号使用协议](https://static.account.xiaomi.com/html/agreement/user/zh_CN.html)

## 阶段 0 通过条件

官方服务或自用路线都要求平台接受该账号的正常登录和数据请求。申请二维码返回 HTTP 200 和业务码 0，二维码资源返回 HTTP 200 和 `image/png`。根据 `mifit` 实现，已构建阶段 0 Android 验证包。自用路线实现等待本人扫码、使用小米签发的短期会话，只在手机内存中处理数据。账号安全挑战或平台拒绝会停止读取，账号级续期凭据不保留。另一个开源项目报告部分账号可能收到 `70036`，须以本人实测为准。 [源码](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/auth/qr.py#L397-L558) · [扫码失败记录](https://github.com/Zhou-Ruichen/mi-health-mcp)

真实心率的最低验收已达成。用户扫码后在 Redmi Note 12 Turbo 上核对数值、时间和单位一致，ADB 复核验证包的确认状态。随后开始正式 App 接入，静息心率、血氧、步数、跨日睡眠、历史窗口、续页及最新数据延迟仍需逐项在该账号上验证。 [测试报告](TEST_REPORT.md)

## 2026-11-28 · 授权续期和云端变更通知

- 个人扫码流程可由小米签发账号级续期凭据。v0.3.2 默认关闭续期。主动开启并完成一次小米授权后，凭据随会话由 Android Keystore 加密保存在手机上，在读取收到 HTTP 401 时尝试续期一次，成功后重试原查询。关闭续期或退出账号时清除该凭据。旧版会话不含该凭据。 [开源客户端的续期实现](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/auth/renew.py)
- 小米开放平台提供步数、心率、睡眠等数据变更通知。接入需通过数据权限审核、由用户完成相应的应用授权，并使用公网 HTTPS 回调接收和验证通知。个人扫码链路未找到可验证的推送入口。客户端的分页标记仅用于续页，参考项目的增量水位仍需主动查询。现有无服务器设计保留 WorkManager 定期检查。 [服务接入流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2326) · [云服务接口](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2328)
