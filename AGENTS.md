# Codex Pocket 项目约定

- 当前源码根目录固定为本目录。代码、开发临时文件、日志和交付文件均保存在本项目内，不再写入原 Documents 会话目录。
- 用户要求的安装包布局为 `releases/mac`（带版本号的 `.app`）、`releases/windows`（可直接运行的 EXE 和免安装 ZIP）、`releases/android`（APK）。每个平台最多保留最近两个版本，同版本 EXE 和 ZIP 算一个版本，按语义版本号排序。
- 使用 `npm run package:mac`、`npm run package:windows` 和 `./scripts/build-android.sh release` 生成产物。签名、复制和完整性检查成功后，才清理该平台多余的旧版本。
- 凭据、管理员首次登录资料、签名密钥和早期开发归档保存在 `.private`，不得放进 `releases` 或发给普通用户。
- Windows 包尚待实机验证；静态检查和打包成功不等于 Windows 连接验收。

- 每次软件变更必须递增对外版本号，不再用同版本覆盖发布。小修复递增第四段（如 `0.9.8.1` → `0.9.8.2`），功能更新递增第三或第二段，大更新递增第一段。安装包与界面使用 `package.json` 的 `pocketVersion`；内部 npm `version` 保持合法 SemVer，`pocketBuild` 与 Android versionCode 同步递增。
- 版本整理支持三段和四段，逐段按数字排序；例如 `0.9.9` > `0.9.8.10` > `0.9.8.2` > `0.9.8`。
