# 0.9.8.2 验证记录

日期：2026-10-29。对外版本 0.9.8.2，内部 npm 版本 0.9.8-patch.2，pocketBuild / Android versionCode 31。

## 原问题与回归

隔离重放同一条桌面队列 v2 通知：任务 A 正在运行，任务 B 等待自动发送。变更前连接器的输出没有 B；变更后 B 出现在 A 后方，A 的活动回合身份不变。

- 180 项 Node 测试通过，其中 10 项专项覆盖队列初始读取、实时新增/编辑/重排/删除/清空、客户端与服务端队列、消息身份去重、暂停及不确定发送状态、超长文本详情、错误所有者/主机/协议、异步读取与连接清理。
- 21 项 Android JVM 测试通过。
- 3 项 Android 模拟器队列测试通过：已有队列可见并标注等待状态、发送后不重复与拒绝同聊天版本的旧队列结果；编辑/清空及新流版本；分页窗口外删除与旧历史页防止排队项复活。
- 16 项 Android 模拟器连接恢复回归通过，覆盖失效连接、就绪恢复、回执、禁止自动重发、旧流版本及后台切换。
- Android Debug Lint 通过。初轮队列分页夹具将 `thread.history` 错写为 `history.page`，修正夹具后 3 项队列用例全部复跑通过；未以首次失败结果计为通过。

手机测试使用隔离的 TLS/WebSocket 加密服务和虚构消息，不会提交真实客户任务。尚未在客户实体手机上验收。

## 桌面协议核对

只读检查当前已安装桌面包 `/Applications/ChatGPT.app/Contents/Resources/app.asar`，确认独立队列广播、持久化 `queued-follow-ups` 及 `thread/queue/list` 接口。对当前桌面上下文执行一次真实 `thread/queue/list`，返回 `data` / `nextCursor`，队列为空；该探测未发送消息、启动任务或接管 writer。因此，这次真实探测只确认接口可用，非真实非空队列的端到端验收。

REA 对当前包的精确摘录进行静态分析，Evidence `ev_aa992eb78df9c026fa7d143245dc6b4e883b9d789f14f8626103233f0777ed7c`。原文件哈希、字符位置、完整 Evidence 与测试日志留在项目 `.private/queued-followups-20261029/`。摘录未执行，外部依赖和未覆盖的桌面路径仍属未知；不把静态分析当作运行证明。

## 安装包

按项目约定使用 `npm run package:mac`、`npm run package:windows`、`./scripts/build-android.sh release` 生成 0.9.8.2；各平台成功发布到本地 releases 后保留最近两个版本 0.9.8.2 / 0.9.8.1。

- macOS：Universal `.app`，本机临时签名及严格验签通过，包含的同步源码与项目一致；未做 Apple 公证。
- Windows：直接运行 EXE 与免安装 ZIP。ZIP 中 926 个清单文件逐个核对 SHA-256，同步源码与项目一致；无 Windows 实机连接验收，不把交叉编译和静态检查当作连接通过。
- Android：Release APK 生成成功，versionName 0.9.8.2 / versionCode 31，验签通过，签名证书与 0.9.8.1 相同。模拟器安装成功，正式包冷启动返回 `Status: ok` / `LaunchState: COLD`。

手机与电脑连接器需要一起更新。本地安装包已生成，未执行 GitHub 发布、生产部署或发送客户消息。
