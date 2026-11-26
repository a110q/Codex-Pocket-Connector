# 0.9.18.1 本地验证记录

验证日期：2026-11-26。外显版本 `0.9.18.1`，构建号 `63`。

## 原始问题与证据

用户截图显示电脑项目中的“回应问候”“创建鹈鹕骑车 SVG 动画”在手机中落入“独立对话”，且电脑有四个项目，手机只显示三个。

通过隔离的桌面 SQLite、项目元数据和桌面工具返回值，在修改前复现了项目列表返回新 ID、会话归属保留旧 ID，导致直接比较失败的场景。项目 ID 映射修复后，同样场景通过。

Android 14 实际运行还发现 `JSONObject.NULL` 经 `optString` 处理后可能成为非空字符串。已显式排除 JSON 空值，并验证残留的旧 ID 映射不会把真正独立的会话重新归入项目。

未读取客户电脑的真实项目元数据或会话 ID，因此以上是已复现并修复的代码缺陷，不能据此断言客户现场只有这一项原因。YunqiPay 缺失的现场原因尚未单独确认；本轮已验证桌面返回的空项目在手机上正常显示。

## 测试结果

| 层级 | 结果 | 覆盖范围 |
| --- | --- | --- |
| `npm test` | 255 通过，0 失败 | 桌面项目 ID 映射、新旧 ID 筛选、刚创建且尚未进入本地目录的会话、显式移出项目、同名独立会话、项目删除、归档与恢复回归、项目内新建的真实目标 ID |
| `:app:testDebugUnitTest` | 33 通过，0 失败 | 现有 Android 单元测试回归 |
| `:app:assembleDebug :app:assembleDebugAndroidTest` | 编译通过 | 版本读取、Android 主程序和测试程序 |
| `:app:connectedDebugAndroidTest`，`UiRedesignTest` | 29 通过，0 失败，0 跳过 | Android 14 ARM64 中文模拟器；项目和空项目、折叠后收到新会话、新旧 ID 切换、JSON 空值、跨主机隔离、项目新建与搜索、项目自动刷新及界面回归 |

使用 JDK 17.0.19；实际核对 `java -version` 与 `mvn -version`，两者均使用该 JDK。Android SDK 通过 `ANDROID_HOME` 指定。

可复验命令（在已配置 JDK 17 和 Android SDK 的项目根目录运行）：

```sh
npm test
./android/gradlew -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
./android/gradlew -p android :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.codexpocket.app.UiRedesignTest' --console=plain
```

本轮日志、修改前快照、本轮单独补丁、结果摘要及模拟器截图保存在 `.private/project-sync-20261126/`。测试使用独立数据和认证材料，不修改客户项目或真实聊天。既有工作区改动保持原状。

## 发布与验收边界

本次为本地源码修复。正式包尚未生成，GitHub 和更新服务尚未发布本版本。Windows 实机、客户自建中继与客户 Android 手机的现场组合尚未验证，不能将模拟器结果当作客户实机验收。
