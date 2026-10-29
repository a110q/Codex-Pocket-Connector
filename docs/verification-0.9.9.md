# 0.9.9 本地验证记录

日期：2026-10-29。对外版本与 npm 版本 0.9.9；Android versionCode / pocketBuild 32。

## 范围

原生 Android 界面按用户提供的 HTML 设计落实，保留桌面队列同步、真实电脑/模型选择、权限审批、附件、网页/文件预览及连接恢复。参考文件留档在 `design/review/0.9.9/reference.html`，视觉比较和状态差异见项目根目录 `design-qa.md`。

## 功能与静态检查

- Node：180 项通过；包含电脑排队任务回读、事件更新、清除、去重及旧历史处理。
- Android JVM：21 项通过。
- Android Lint：通过。
- Android 模拟器：累计 20 个不同回归场景通过，包括 14 项界面场景、3 项队列场景和 3 项电脑管理场景。
- 新增验证覆盖：顶部切换面板、配置面板完成/保存、附件与更多操作、停止/发送切换、大字号与键盘布局。
- 既有验证覆盖：执行过程折叠与流式更新、长工具列表、失败可见、审批、Markdown 链接与真实本地网页隧道、文件预览、长模型名、离线待确认消息、草稿隔离和设备重命名。
- 本次修改文件的 diff 空白检查通过。整个工作区另有此前的修改，未重置这些内容。

第一轮“新建对话”曾在高分辨率模拟器等待超时；单独重跑通过，未为此更改产品发送逻辑。新增大字号测试曾错误地二次设置测试 Activity 的内容，已改为在单次内容内更新字体缩放并重跑通过。保留原始失败与成功日志，未将失败批次整体标成通过。

## 构建与完整性

- 按项目约定执行 `npm run package:mac`、`npm run package:windows`、`./scripts/build-android.sh release`。
- Android APK：发布签名有效，证书 SHA-256 与 0.9.8.2 一致，版本名 0.9.9 / versionCode 32。
- Mac：`codesign --verify --deep --strict` 通过；包内版本信息和排队修复源文件与当前项目一致。
- Windows ZIP：清单内 926 个文件逐个校验 SHA-256 通过；可运行 EXE 和免安装 ZIP 已生成。尚未做 Windows 实机连接验收，不以静态检查或打包成功代替。
- 每个平台保留 0.9.8.2 与 0.9.9 两个版本；同版本 Windows EXE/ZIP 为一个版本。
- 统一校验清单：`releases/SHA256SUMS-0.9.9.txt` 与 `releases/SHA256.txt`。
- APK 覆盖安装成功；`am start -W -S` 返回 `Status: ok`、`LaunchState: COLD`，总启动时间 8256 ms。

## 证据

- 日志与测试结果：`.private/ui-0.9.9/`。
- 首次批次：`suite-first-results`，15 通过、1 超时；新建对话后续通过。
- 补充批次：`final-batch-results`，6 通过、1 测试搭建错误；修复后的大字号结果在 `large-text-results`（2/2 通过）。
- 面板完成/保存与发送回归：`handoff-results`（2/2 通过）。
- 最终审批布局回归：`final-approval-layout.log`（1/1 通过），结果保存于 `final-approval-results`。
- 视觉原始截图：`.private/ui-0.9.9/final-screenshots/`；归一化截图与并排对比：`design/review/0.9.9/`。

本次没有推送提交、创建 GitHub Release、部署生产服务或给客户发送消息。
