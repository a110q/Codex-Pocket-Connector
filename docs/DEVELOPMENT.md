# 源码构建

公开源码不含维护者实际公网配置、签名私钥和管理员账号。`config/` 模板使用 `XX`，需要先替换为自己的配置。

## 环境

- Node.js >= 22.13，运行 `npm ci` 安装锁定依赖。
- Android：Android Studio、JDK 17+、SDK Platform 37 / Build Tools 37；建议使用 Android Studio 自带 JBR。
- macOS：macOS 主机与 Xcode Command Line Tools，生成 Apple Silicon / Intel 通用应用。
- Windows：Zig x64 交叉编译工具链，通过 `POCKET_ZIG` 指定；打包需要 `zip` 命令，可使用 macOS / Linux 或配置好工具的 Windows 环境。

## 客户端配置

先 [部署中继](SELF_HOSTING.md)，再生成客户端公开配置：

```sh
node scripts/configure-server.mjs \
  --relay https://XX:24443 \
  --cert /absolute/path/to/cert.pem
```

`XX` 是自己的域名或 IP。`--cert` 只能读取服务器**公开证书**，不能使用私钥。生成文件已被 `.gitignore` 排除：

| 位置 | 用途 |
| --- | --- |
| `.private/client-defaults.json` | 桌面打包使用的中继地址、公开证书及指纹 |
| `connector-defaults.json` | 从源码运行连接器时的同类配置 |
| `android/app/src/main/assets/server.json` | APK 内置地址、证书固定指纹、可选 APK 签名指纹 |

真实配置请勿强行 `git add -f`。本机已有的 `.private/companion.json` 可能含设备身份和配对密钥，不能用它作为公开模板。

## Android

```sh
./scripts/build-android.sh debug
```

Release 使用自己的签名。参考 `config/android-signing.properties.example` 创建 `.private/android-signing.properties`；将 keystore 放到 `.private/android-release.jks`。Gradle 当前使用别名 `codex-pocket`，密码从私密 properties 读取。

```sh
./scripts/build-android.sh release
```

输出位于 `releases/android/`。自己的签名无法覆盖维护者 APK。需要签名异常检测时，取得自己 APK 的 SHA-256 签名证书指纹后，用配置脚本的 `--signing-sha256 <64位十六进制指纹>` 参数更新，再构建一次，并在中继环境变量配置相同指纹。

## macOS

```sh
npm run package:mac -- --config .private/client-defaults.json
```

输出 `releases/mac/Codex-Pocket-Connector-<版本>.app`。默认临时签名，公开发行可自行添加 Developer ID 签名和公证。

## Windows

```sh
export POCKET_ZIG=/absolute/path/to/zig
npm run package:windows -- --config .private/client-defaults.json
```

PowerShell 使用 `$env:POCKET_ZIG = 'C:\Tools\zig\zig.exe'`。输出带版本号的单文件 EXE 和便携 ZIP，运行依赖官方 Codex 和 WebView2 Runtime。

## 测试

```sh
npm test
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease
```

`androidTest` 内部分仪器测试需要模拟器和隔离中继夹具，不应连接正式服务。编译、单元测试和静态检查不等于实机交互验收。

Node 测试包含真实 TLS / WebSocket 与加密传输、图片/文件分片和断点恢复、权限范围验证与 MCP 表单、后台提醒状态转换、自建服务验证与身份隔离、消息去重、回执核对和配对取消。

安卓不依赖外部夹具的仪器测试可以单独运行：

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.codexpocket.app.PairingImagesTest,dev.codexpocket.app.ConnectionRecoveryTest
```

账号与服务切换测试需先在项目根目录运行 `node test/support/account-ui-server.mjs`。它会创建临时中继及虚构桌面，并把连接资料保存在 `.private/account-ui-fixture.json`；不要发布该文件。模拟器使用 `adb reverse` 转发夹具端口。`AccountFlowTest` 需要 `accountRelay`、`accountPin`、`accountDevice`、`lanPairing`、`lanDesktop`、`cloudPairing`、`cloudDesktop` 仪器参数；`ServiceRoutingTest` 需要 `servicePairing`。均从夹具文件读取，勿放入公开日志。

`SelfHostingFlowTest` 会真正发送图片与文字，只能在明确授权的专用测试会话中运行。先按自建文档启动独立 Docker 中继、初始化测试管理员并连接测试电脑，再提供 `selfRelay`、`selfPin`、`selfUsername`、`selfPassword`、`selfPairing`、`selfDesktop`、`selfThread` 参数。不得使用其他人的账号或工作会话。已执行的流程和环境边界见 [0.7.7 验收记录](verification-0.7.7.md)。

## 目录

```text
android/                 安卓 Compose 客户端
desktop/                 macOS / Windows 原生启动器
scripts/                 配置、打包、管理员工具
test/                    Node 测试和隔离夹具
deploy/                  中继容器配置
config/                  使用 XX 的脱敏模板
docs/                    使用、自建、开发文档
accounts.mjs             账号、权限和设备配额
relay.mjs                HTTPS / WSS 中继
companion.mjs            电脑连接服务与加密消息处理
desktop-adapter.mjs       桌面 IPC 适配
standalone-adapter.mjs    Codex App Server 适配
relay-profiles.mjs        自建中继校验与服务身份隔离
image-uploads.mjs         加密通道中的图片分片接收与校验
file-uploads.mjs          普通文件上传、校验与本地附件引用
approvals.mjs             目录/网络权限范围及 MCP 响应校验
.private/                本机私密数据，禁止提交
releases/                本地构建产物，只发布到 Release
```
