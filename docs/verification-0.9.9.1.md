# 0.9.9.1 验证记录

版本：0.9.9.1，pocketBuild / Android versionCode 33。验证日期：2026-10-29。修复范围见 [修复说明](patch-0.9.9.1.md)。

## 自动化验证

- Node：184 项全部通过，包含桌面摘要运行状态合并、Pocket 所属进程状态、状态缺失回退，以及明确校时响应和脱敏诊断。
- Android JVM：21 项全部通过；Android lint 通过。
- Android 模拟器连接与状态回归：26 项全部通过，覆盖首次确认丢失、缺少中继在线通知、持续丢失时的固定总期限、首次心跳回复丢失、运行和完成状态、无通知时的列表轮询及后台停止刷新。
- Android 模拟器界面回归：2 项全部通过，验证恢复后仍能打开并复制连接诊断、诊断不含配对凭据，以及参考风格底部面板中的真实聊天操作。
- 最终 release APK 覆盖安装成功；强制停止后冷启动成功，`LaunchState: COLD`，`Status: ok`。
- 本次修改涉及的受版本管理文件 `git diff --check` 通过。

原始验证日志保存在本项目私有目录 `.private/timeout-0.9.9/`，包括 `node-tests-final.log`、`android-checks.log`、`android-lint-final.log`、`device-regression.log`、`diagnostic-ui-tests.log`、`release-install-final.log` 和 `release-launch-final.log`。日志不随安装包分发。

## 实际 Mac 复核

修复前，只读查询中桌面报告的 3 条运行会话，在独立 App Server 列表中均为 `notLoaded`。修复后，3 条会话均正确合并为 `active`，没有创建实时历史订阅。

本机连接器已切换到 0.9.9.1，状态接口确认公网模式连接成功且错误为空。用户反馈在等待后，其他运行中的会话也自动显示出来。列表约每 5 秒刷新，首次还包含连接和查询时间；此反馈证明自动刷新已发生，不代表所有网络条件下都有固定的显示时限。

首次超时截图发生在分阶段日志启用前，原始中断位置无法追溯。后续记录的两次确认成功，电脑立即回复，时钟差约 1 秒。可复现的通知或确认丢失恢复缺口已通过模拟器测试；用户实体手机首次连接的修复仍需安装新版 APK 后验证。

## 安装包与完整性

- 使用项目规定命令构建 Mac、Windows 和 Android 安装包，保留各平台最近两个版本 0.9.9 和 0.9.9.1。
- Android APK 签名验证成功，证书与 0.9.9 相同；包内版本为 0.9.9.1 / 33。
- Mac 应用 `codesign --verify --deep --strict` 通过。
- Windows 包中 926 个清单文件逐项 SHA-256 验证通过；Windows 实机连接尚待验收。
- `releases/SHA256SUMS-0.9.9.1.txt` 的 5 项校验全部通过，覆盖 APK、EXE、ZIP、Mac 可执行文件和签名资源清单。

本次只生成本地交付包，未发布 GitHub Release。
