# 测试报告

## v0.2.0 构建与测试

| 检查 | 结果 |
| --- | --- |
| `gradlew.bat assembleDebug testDebugUnitTest lintDebug` | 构建通过，16 项单元测试通过，lint 无错误 |
| `gradlew.bat assembleRelease` | 构建通过 |
| `gradlew.bat :health-connect:connectedDebugAndroidTest` | Android 16 模拟器 2 项通过，覆盖心率去重和修订，以及锻炼、路线、距离、爬升、热量、速度和体重 |
| HTTPS 测试服务 | 验证分页游标、时间范围、断连、401、403、429、503 和重试时间响应头 |

模拟器测试使用合成记录，完成后清理。生产同步从用户授权的小米大陆云端读取。

## 真机验证

测试设备为 Redmi Note 12 Turbo，Android 15、HyperOS 3.0，使用系统内置 Health Connect。

- 小米扫码授权后成功读取真实云端记录。心率、静息心率、血氧、步数、睡眠和活动热量均验证写入 Health Connect。
- 重复同步同一范围时，相同内容被跳过。覆盖安装和重新打开 App 后仍可使用已保存的登录状态。
- 小米运动健康与 Health Connect 中的实际睡眠时长抽查一致。ChatGPT Health 已验证能够读取此来源并显示正确的心率。睡眠在 ChatGPT Health 的汇总显示尚未复验。
- 省电策略设为「无限制」后，单独把 App 从最近任务划掉，定时任务仍自动唤起进程并完成拉取。该次执行较最早安排的时间晚约 4 分钟。
- 锻炼、速度、路线、体重和其他锻炼附属指标通过测试数据验证了系统写入。真机测试期间没有对应的可用云端记录，真实数值尚未核对。
- 手机重启、会话自然过期、权限变更和断网后恢复的完整真机流程尚待验证。

## 公开第三方接口

匿名探测小米公开最新心率接口返回业务码 `-8`，提示 `invalid clientId`。应用使用本人扫码授权的客户端云端路径，所用接口与小米开发者平台的公开第三方接口分别说明。 [云端调查](CLOUD_API_RESEARCH.md)
