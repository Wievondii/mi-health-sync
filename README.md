# 小米健康同步

将本人小米运动健康的中国大陆云端记录同步到 Android Health Connect。小米运动健康继续负责连接手环或手表。ChatGPT Health 等应用可在你授权后从 Health Connect 读取记录。

这是独立的开源项目，适用于小米运动健康中国大陆账号。小米的云端接口可能变化，当前版本不保证其他地区可用。

## 安装和第一次同步

1. 在 Android 手机上安装 [发布页的 APK](https://github.com/Wievondii/mi-health-sync/releases/latest)。手机须支持 Health Connect，Android 14 或更新版本通常内置。
2. 打开「小米健康同步」，点「获取小米登录二维码」。可以打开「在此手机打开小米确认页」，也可以用小米官方应用扫码。按小米页面的提示确认登录。
3. 在 App 中点「授权写入」，按需授权数据类型。锻炼路线在「设置 → 同步数据」单独授权。已有其他应用写入历史时，可以先用「选择日期」避开重复日期。
4. 点「立即同步」。在 App 查看本批次的数量，再打开 Android Health Connect 检查「小米健康同步」来源。随后在 ChatGPT Health 授权访问 Health Connect，并核对读取到的数据。刷新取决于 Health Connect 和 ChatGPT 的连接状态。

## 支持的数据

| 何时可以同步 | 类型 |
| --- | --- |
| 小米云端有对应记录 | 心率、静息心率、血氧、步数、睡眠、活动热量、体重 |
| 小米云端有同一次锻炼的摘要 | 锻炼时间和类型、锻炼距离、爬升高度、锻炼总热量 |
| 小米云端有本版本支持的运动明细 | 锻炼速度、锻炼路线 |

锻炼路线需要单独授权。手环完成采样后，需等小米运动健康将数据上传。小米云端没有值时，App 会显示暂无可同步数据。 [字段与格式限制](docs/DATA_MAPPING.md)

## 同步设置和诊断

- 默认每 30 分钟尝试，可选 15、30、60 分钟或仅手动，支持仅 Wi-Fi。系统会决定实际运行时间。HyperOS 若限制后台运行，可在系统应用设置按需开启自启动、调整电池限制。点击系统里的「强行停止」后，需要重新打开 App，才会恢复定时同步。
- 当前个人扫码接入通过定期检查获取云端更新。小米官方的变更订阅需要经过开发者审核，并由公网 HTTPS 回调接收通知。
- 可在「设置 → 账号与隐私」开启登录自动续期，然后重新登录一次。App 只在本机加密保存小米签发的账号级续期凭据，同步收到登录过期响应时自动尝试一次续期。关闭自动续期或退出账号时清除续期凭据。
- 在「设置 → 账号与隐私」开启登录过期通知。续期凭据缺失、已失效或小米要求额外确认时，同步会暂停并发出一次系统通知。点通知可获取新的二维码，经本人确认后恢复同步。首页会显示过期状态，直到重新登录。
- 首页按健康、日常活动和锻炼显示各项同步结果。点开项目可查看写入计数或失败原因。
- 可选最近 1、7、30 天或指定日期。按北京时间解释日期。云端只能返回已上传的数据，首选核对一条心率的采样时间与数值。
- 日志记录状态、时间和计数。「导出脱敏诊断」会打开 Android 的分享界面。小米的登录会话加密保存在手机上，退出账号时清除。若要删除已写入的数据，可在系统 Health Connect 中按「小米健康同步」来源处理。

## 构建

使用 JDK 17 和 Android SDK 36。通过项目自带的 Gradle Wrapper 构建。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

macOS 或 Linux 运行 `./gradlew assembleDebug testDebugUnitTest lintDebug`。调试安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。可使用 Android Studio 的 `Generate Signed Bundle / APK` 生成自签安装包。覆盖安装要求新旧安装包使用同一个签名。

设备实验使用 `:health-connect:connectedDebugAndroidTest`，该测试仅允许在 Android 模拟器写入合成数据，并在测试结束后删除测试记录。阶段 0 验证包独立于正式 App，可运行 `tools/phase0-probe/build.ps1` 重新构建。

## 资料

- [小米云端协议调查](docs/CLOUD_API_RESEARCH.md)
- [指标映射与已知限制](docs/DATA_MAPPING.md)
- [测试报告](docs/TEST_REPORT.md)
- [MIT 许可证](LICENSE)
- [第三方许可](cloud/src/main/resources/META-INF/mihealth-sync-licenses.txt)
