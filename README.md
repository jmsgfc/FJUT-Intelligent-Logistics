# 福建理工大学智慧后勤

这是一款能够直接查询 30 天内水电费消耗情况的软件，面向福建理工大学宿舍水电查询场景开发。应用可以展示宿舍剩余电量、剩余水量、近 30 天累计用量、逐日用量以及按当前单价估算的费用，减少在网页中反复查找宿舍和表计的操作。

> 本项目是个人开发的第三方查询工具，并非福建理工大学官方应用。查询结果与费用估算仅供参考，实际数据和收费金额以学校智慧后勤系统为准。

## 主要功能

- 通过手机号、短信验证码和学号完成智慧后勤账户绑定。
- 启动时检查本地登录 Cookie，有效时自动进入查询页面。
- 按宿舍区、楼号和宿舍号选择查询对象。
- 查询宿舍剩余电量、剩余水量及对应金额估算。
- 汇总最近 30 天用电、用水及费用。
- 展示最近 30 天每一天的水电用量与金额。
- 分别展示：
  - 电表一：阳台、卫生间照明和普通插座。
  - 电表二：房间照明和空调。
- 内置诊断日志：连续点击顶部“智慧后勤”6次可查看、清除或导出日志。
- 查询请求带有防重复提交、短时结果缓存和业务码 400 有界重试。

## 计价标准

| 项目 | 单价 |
| --- | ---: |
| 电费 | 0.5333 元/度 |
| 水费 | 2.6 元/吨 |

费用由客户端按照查询到的用量估算，不代表学校最终账单。

## 登录与数据安全

- 密码默认使用学号后 6 位，仅用于智慧后勤绑定请求，不保存密码和短信验证码。
- 登录 Cookie 和设备标识使用 Android Keystore 生成的 AES-GCM 密钥加密，保存在应用私有目录。
- 手机号和学号为了下次填写方便保存在应用私有偏好设置中，可通过“清除登录状态”删除。
- 应用备份和设备迁移显式排除本地登录数据。
- 诊断日志会隐藏手机号、学号、Cookie、密码、验证码、查询参数和设备标识。

## 网络限制

智慧后勤服务器可能限制校外网络访问。如果服务器将请求重定向到学校 VPN 导航页，应用会停止请求并给出提示，不会把 Cookie 或认证数据发送到其他域名。

当前版本不内置 WebVPN。校外使用需要连接学校允许使用的系统级 VPN，或等待学校将智慧后勤加入 WebVPN 资源列表。

## 环境要求

- Android 6.0（API 23）及以上。
- JDK 17。
- Android SDK，编译 SDK 36。
- Gradle Wrapper 已包含在仓库中。

## 编译

Windows：

```powershell
gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

生成文件：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安装

连接已启用 USB 调试的 Android 手机后：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接从 GitHub Releases 下载已经编译的 APK。

## 当前版本

- 版本：`1.8.8`
- versionCode：`27`
- 包名：`com.local.smartlogistics`
- 最低 Android 版本：Android 6.0

## 项目结构

```text
app/src/main/java/com/local/smartlogistics/
├── MainActivity.java            # 主界面、登录和交互逻辑
├── SmartLogisticsClient.java    # 智慧后勤网络请求与数据解析
├── SecureStore.java             # Android Keystore 加密存储
├── DiagnosticLog.java           # 脱敏诊断日志
└── UsageSummary.java            # 水电统计数据模型
```

## 使用提醒

- 请勿将短信验证码、密码、Cookie 或导出的诊断日志公开上传。
- 请合理控制查询频率，避免连续频繁请求学校服务器。
- 学校接口、页面结构或计费规则发生变化时，应用可能需要同步更新。
