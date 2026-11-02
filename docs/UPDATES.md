# 应用更新维护说明

手动检测从 0.9.10 开始具备，自动检测与自选更新提醒从 0.9.14 / build 41 开始具备。0.9.12.1 用户需先在“关于与更新”手动检测并安装 0.9.14，之后会自动提示后续新版。0.9.9.3 及更早版本需手动下载安装包。

自动检查只在前台进行：启动、回到前台和前台定时触发，成功后至少间隔 6 小时，失败至少间隔 15 分钟。自动失败静默；下载中及已下载时不刷新清单。已验签的新版本显示“稍后／立即更新”，同版本稍后 24 小时内不再提醒，记录跨重启保留；新版本不受旧版本延后限制。只有用户明确选择才下载，手机系统安装器和电脑替换步骤仍由用户确认。

安卓从 0.9.14.1 / build 42 起将完整安装包保存到应用持久目录，并同步保存原始签名清单。安装来源授权期间页面或进程重建时，启动先离线验签和校验 APK，再恢复“安装更新”；手动更新页面支持系统保存状态恢复。手动重新检查相同更新复用已校验安装包；检查失败保留现有安装操作。已安装版本的记录和对应文件在恢复时清理。旧版更新到本版仍运行旧版更新逻辑，本修复在安装本版后生效。

更新分发服务沿用 1Panel 编排 codex-pocket-updates（24444 → 8443），使用持久目录；当前客户端版本为 0.9.15.1 / build 44，包含手机图片、交付折叠、筛选滑动和项目移除同步修复。中继 0.9.12.1（24443）与本次服务端源码逐文件一致，继续兼容本次客户端更新。

## 客户端配置

根目录 `update-defaults.json` 和 Android `assets/updates.json` 只含公开 HTTPS 地址、公开证书指纹及 Ed25519 验签公钥，不含私钥。这两个环境配置文件不加入 Git；公开字段示例见 `config/update-defaults.example.json`。

在项目根目录执行：

```sh
node scripts/configure-updates.mjs \
  --manifest https://YOUR_SERVER:PORT/updates/stable.json \
  --cert PATH_TO_PUBLIC_TLS_CERT.pem \
  --public-key PATH_TO_ED25519_PUBLIC_KEY.pem
```

该命令只更新本地配置。Mac、Windows 打包脚本验证并打包配置；Android 构建将公开配置放入 assets。切换自建中继不会改变更新来源。

客户端支持固定的自签名证书，不要求先购买域名。证书仍须有效且包含服务器 IP 或域名的 SAN。浏览器直接访问自签名服务可能提示证书不受信任；客户端按内置公开证书和主机名验证，不跳过验证。

## 只准备本地候选版

1. 在 `package.json` 设置新的 `pocketVersion` 和递增的 `pocketBuild`；内部 npm `version` 保持合法 SemVer。Android、连接器界面与三端包均从该文件取版本。
2. 完成测试，然后执行项目规定的三端构建命令。不要删除原签名密钥；Android 更新须使用与已安装应用相同的签名。
3. 写好纯文本更新说明，执行下面的本地准备命令。私钥只在本机读取，不会复制到候选目录。

```sh
npm run package:mac
npm run package:windows
./scripts/build-android.sh release
node scripts/prepare-update.mjs \
  --key .private/update-signing-private.pem \
  --notes PATH_TO_RELEASE_NOTES.txt
```

候选文件位于 `.private/update-candidates/VERSION/`：

- `packages/VERSION/`：Android APK、Mac ZIP、Windows EXE，Mac ZIP 由已签名 App 生成。
- `updates/stable.json`：签名清单候选，尚未上线。
- `release.json`：可读的清单原文，供审核版本、说明、地址、大小和哈希。
- `HOLD.txt`：标明尚未发布。

工具校验签名私钥与客户端公钥匹配、Mac App 签名及复制完整性，并自行验签输出清单。同版本已有候选时会拒绝覆盖，须先人工复查。该工具没有上传、部署或 GitHub 发布功能。

## 确认发布后

由维护者明确选定上线版本，再进行服务器部署和公开发布。分发服务使用持久目录及 Compose，在 1Panel 编排页中可恢复和启停；软件包目录必须与清单中的 `/packages/VERSION/NAME` 一致。

先上传并验证所有软件包，再原子切换 `updates/stable.json`。上线后从实际客户端验签、下载与覆盖安装，完成实际公网网络及 Windows 实机验收。清单签名私钥、Android 签名密钥、管理员资料和服务器凭据始终留在 `.private`，不进入容器公开目录或普通用户安装包。

停止容器后可在同一编排中启动；仅删除容器、保留编排与挂载目录时可重新创建。编排或持久目录被删除时需要用保存的 Compose 和备份恢复，不能保证恢复已删除且无备份的数据。

客户端固定服务器证书及验签公钥。更换 TLS 证书或签名密钥前，先安排携带新信任配置的客户端迁移版本；直接替换会使现有客户端拒绝更新。保留证书有效期、私钥离线备份及版本回退记录。回退线上清单不会自动降级客户端，页面会说明本机版本高于已发布版本。
