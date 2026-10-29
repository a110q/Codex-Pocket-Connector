# 安装包与版本规则

代码、开发临时文件、日志和交付文件均保存在项目根目录内。

```text
releases/
  mac/       Codex-Pocket-Connector-0.9.8.1.app
  windows/   Codex-Pocket-Connector-0.9.8.1-Windows.exe
             Codex-Pocket-Connector-0.9.8.1-Windows.zip
  android/   Codex-Pocket-0.9.8.1.apk
  README.md
  SHA256.txt
```

每次软件变更必须增加对外版本号，小修复使用第四段，例如 `0.9.8.1` → `0.9.8.2`。功能更新调整第三或第二段，大更新调整第一段。以 `package.json` 的 `pocketVersion` 为准；npm `version` 保持合法 SemVer，`pocketBuild` 与 Android versionCode 同步递增。三平台的界面和产物名称使用相同的对外版本。

每个平台最多保留最近两个版本，逐段按数字排序：`0.9.9` > `0.9.8.10` > `0.9.8.2` > `0.9.8`。Windows 同版本 EXE 和 ZIP 算一版。新包复制、签名和完整性检查成功后，才清理该平台多余旧版本；不跟随符号链接，不按宽泛后缀删除其他文件。

Mac 双击 `.app`；Windows 双击单文件 EXE，或完整解压 ZIP 后双击 `Codex-Pocket.exe`；Android 覆盖安装 APK。三端完整发行版本相同。Mac 标准版本使用前三段、构建号使用 `pocketBuild`，完整版本保存在 `PocketReleaseVersion`，应用界面显示完整版本。Windows 的资源版本和清单均保留第四段。

```sh
npm run package:mac
npm run package:windows
./scripts/build-android.sh release
```

Windows 使用项目内 `.private/toolchains/zig-npm/package/zig` 交叉编译 x64 GUI EXE，也可通过 `POCKET_ZIG` 指定编译器。`npm run package:android` 只收集已编译的 release APK，`build-android.sh release` 编译后自动收集。调试 APK 不进入发布目录。

凭据、管理员首次登录资料、服务器密码、签名私钥、日志与历史开发归档保存在 `.private`，不得放进 `releases` 或普通安装包。公开源码脱敏，正式安装包按用户要求保留默认公网服务配置。

本地打包不等于 GitHub 发布。发布需遵循用户当前授权；未获批准的本地版本不得上传。Windows 静态检查和交叉编译不等于实机连接验收。
