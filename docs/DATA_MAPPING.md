# 小米云端与 Health Connect 指标映射

所有实际写入均由小米大陆云端响应生成，Health Connect 的来源由系统标记为「小米健康同步」。采样时刻保留 Unix 秒含义，时区偏移来自记录，日期范围按北京时间解释。App 不推算医疗指标。

| 指标 | 小米字段和时间语义 | Health Connect 写入 |
| --- | --- | --- |
| 心率 | 原始 `heart_rate`，内外层 `time` 为同一采样时刻，`bpm` 为次/分 | `HeartRateRecord`，按 UTC 分钟分组，保留真实采样点时间 |
| 静息心率 | 独立的 `resting_heart_rate`，`date_time` 对应日键、`bpm` 为当日静息值 | `RestingHeartRateRecord`，本地日零点作为日期标记。云端未提供精确测量时刻 |
| 血氧 | `spo2` 和手动测量 `single_spo2`，`spo2` 为百分数 | `OxygenSaturationRecord`，保留采样时刻，同一秒仅保留一条 |
| 步数 | `daily_report` 下的 `steps` 是当天已上传的累计步数。零值不写入 | `StepsRecord`，每个北京时间日期一条。当天写入已过区间，累计值变化或日期结束时更新原记录 |
| 睡眠 | `daily_report/sleep` 的 `segment_details` 提供会话起止和实际睡眠分钟。仅为起止完全匹配的会话补充原始 `sleep.items` 中的分段 | `SleepSessionRecord`，真实起止、跨日会话和可验证分段均保留，标题显示小米报告的实际睡眠时长。重叠会话优先保留云端较新的汇总 |
| 锻炼 | 已结束运动摘要的 `start_time`、`end_time`、`sport_type` | `ExerciseSessionRecord`。无法识别具体运动时按「其他锻炼」写入，保留实际起止 |
| 锻炼距离 | 同一运动摘要的 `distance`，米 | `DistanceRecord`，按锻炼区间写入。全天步行距离没有叠加进去 |
| 爬升高度 | 运动摘要的 `rise_height`，米 | `ElevationGainedRecord`。需要云端有明确的正值 |
| 活动热量 | `daily_report/calories` 的 `calories`，千卡 | `ActiveCaloriesBurnedRecord`，每日一条，含当天已经上传的累计值。锻炼热量已计入每日活动热量，因此同类运动热量不重复写 |
| 锻炼总热量 | 运动摘要的 `total_cal`，千卡，包含该次运动期间的静息消耗 | `TotalCaloriesBurnedRecord`，范围是单次锻炼，全天总能量没有写入 |
| 锻炼速度 | 小米运动文件内的跑步逐秒配速或骑行逐秒速度，保留该秒采样时刻 | `SpeedRecord`，转换为米/秒，按十分钟分批。当前读取小米已核实的跑步 22/v8 与骑行 23/v5 文件 |
| 锻炼路线 | 小米运动文件内实际经纬度和采样时刻 | 单独授权后，随 `ExerciseSessionRecord` 写入 GPS 文件 v2 的有效点。超长路线最多等距保留 4,000 个原始采样点 |
| 体重 | 原始 `weight` 记录，千克及真实测量时刻 | `WeightRecord`，小米个人资料中的当前体重不转为历史测量 |

记录身份使用账号作用域与稳定的日期或采样时间键。存储写入索引、内容摘要和版本用于断点重放，日志里没有心率值或账号凭据。相同内容跳过，修订使用相同记录 ID 和更高版本。每批最多 100 条，某指标的全部批次成功后才提交游标。默认回溯 48 小时。

原有五种指标已用本人账号实测写入。用户在 ChatGPT Health 核对了真实心率。当天步数及活动热量已与小米运动健康逐日抽查一致。血氧和静息心率已验证存在及写入结果，逐条数值尚未核对。血氧连续与单次采样同秒出现时保留更新时间较新一条。睡眠分段代码 2 为深睡，3 为浅睡，4 为 REM，5 为清醒。缺少可信分段时保留会话起止时间。小米提供的日静息消耗是能量，缺乏明确的代谢率采样，暂不写入基础代谢率。

锻炼明细按每次同步每类最多 10 个支持的锻炼读取，可用较短的自选范围补录。记录无位置、缺少速度、明细格式未验证或文件不可用时保留可用的锻炼摘要。已写入路线的会话在无法再次取得路线时保留现有记录。可核实依据见 [活动热量解析](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/domains/activity_daily.py)、[运动摘要](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/notes/10-workout-records.md)、[运动明细](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/notes/12-fds-workout-detail.md) 和 [小米爬升数据字段](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2330)。

Health Connect 的睡眠会话保留记录到的整个区间。区间可能包含清醒，也可能与小米按分钟报告的睡着时长存在取整差异。Health Connect 没有单独存放小米时长的数字字段，读取方可根据分段或区间自行计算，显示的口径取决于读取方。会话起点修订后会取得新记录标识，旧标识下的睡眠记录可在系统设置按来源和日期管理。

云端 P0 字段依据固定版本的 [心率语义](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/notes/08-heart-rate-keys-and-completeness.md)、[血氧语义](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/notes/11-spo2-weight-profile.md)、[日汇总语义](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/notes/06-daily-report-day-semantics.md) 和 [睡眠解析源码](https://github.com/Jenwein/mifit/blob/ed8fd7f36ca9729f94471cbb85428a1435ea7b72/src/mifit/domains/sleep.py)。平台去重行为见 [Health Connect 写入文档](https://developer.android.com/health-and-fitness/health-connect/write-data)。
