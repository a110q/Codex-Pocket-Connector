# 0.9.18.2 验证记录

验证日期：2026-11-26。外显版本 `0.9.18.2`，构建号 `64`。本轮已完成本地修复及测试；三端正式包已生成，公开发布状态以文末后续记录为准。

## 修复证据

修改前，新增的 Node 回归用例因本地项目的 `hostId:null` 未转为 `local` 而失败。Android 14 中文模拟器上，使用真实 PocketModel、Compose、独立 TLS/WebSocket 和加密协议的永久回归用例，也复现了输入四个项目但手机仅显示两个本地项目的情况；本例给 YunqiPay 和另一个项目都设置了空主机。前轮单个项目为空的隔离诊断则复现了四个只显示三个。

修改后，JSON 空主机、空字符串、空白字符串、缺省主机及显式本地主机均保留有效本地项目，并验证新旧项目 ID 归属、打开会话的实际请求主机、项目内新建的实际请求项目 ID，以及存储重新读取。

回退测试使用项目内隔离 JSON 和 SQLite：桌面读取失败期间，清单从三项更新到四项，修前返回旧三项，修后返回当前四项；期间删为空清单也返回空清单，不带回 SQLite 残留项目。另有用例验证失败期间完成迁移后，按当前 canonical 项目清单筛选，不返回已移除项目。

## 测试状态

- `npm test`：258 项通过，0 失败、0 跳过。
- `:app:testDebugUnitTest`：33 项通过，0 失败、0 跳过。
- Android 主程序及仪器测试程序 Debug 编译通过，APK 元数据为 `0.9.18.2 / 64`。
- `ThreadStatusTest` 和 `ComputerPresenceTest`：17 项通过，0 失败。另行选入的 `SharedDesktopManagementTest` 需要 `isolatedSharedDesktop` 桌面联调环境，未配置而跳过 1 项，不计入通过数。
- `ProjectSyncTest` 与 `UiRedesignTest`：32 项通过，0 失败、0 跳过。覆盖空主机项目、空项目、会话归属与操作、已删除项目、旧缓存加载、切换电脑、重载持久化以及现有界面回归。

扩展回归首次有三项测试失败，均已定位并修正测试前提：项目运行计数仅在项目折叠时展示；任务动态副标题已有文案变化；模拟新任务运行后，模拟读取接口不能继续固定返回旧的完成状态。保留所有原有状态、筛选与隔离断言，调整后的 17 项已重新运行通过。未通过忽略错误或增加等待时间来绕过失败。

工具链：实际运行 `java -version` 和 `mvn -version`，均为 JDK 17.0.19，Maven 3.9.12。SDK 通过 `ANDROID_HOME` 指定；模拟器为 re34、Android 14 ARM64、中文环境。未使用 Parallels。

可复验命令：

```sh
npm test
./android/gradlew -p android :app:testDebugUnitTest --console=plain
./android/gradlew -p android :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.codexpocket.app.ProjectSyncTest,dev.codexpocket.app.UiRedesignTest' --console=plain
./android/gradlew -p android :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.codexpocket.app.ThreadStatusTest,dev.codexpocket.app.ComputerPresenceTest' --console=plain
```

修改前快照、增量补丁、测试日志、结果摘要及项目归属截图位于 `.private/project-sync-fix-20261126/`。测试使用合成项目、隔离存储和独立认证材料，没有读取或修改客户项目、聊天及生产服务。

## 尚未完成或未验收

客户 YunqiPay 的实际主机标识、实时工具错误及清单来源尚未取得。如果实时桌面接口不可用，且磁盘上的旧侧栏清单本身长期少一项，现有元数据无法区分新增项目与已删除残留；重新读取也不能凭空恢复缺失数据。该路径仍待现场证据和可靠的桌面可见性来源，不能声称已彻底解决。

三端正式包已生成；Android 正式 APK 在 Android 14 中文模拟器从 0.9.18 覆盖安装成功、启动正常，新旧签名一致。Mac 正式应用已启动并通过内置更新入口读取原线上 0.9.18 清单。Windows ZIP 内完整文件清单逐项哈希通过；Windows EXE、ZIP 均已整理。Windows 实机、客户自建中继与客户 Android 手机组合未验收。模拟器测试不代替客户现场验收。
