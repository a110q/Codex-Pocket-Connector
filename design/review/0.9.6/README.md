# 0.9.6 实际界面截图

- `mobile-overview.png`：连接入口、项目列表、聊天与工具记录，三张原生截图等比拼接。
- `desktop-overview.png`：Mac App 实际窗口，缩放便于预览。
- 其余 PNG 保留对应原图；`workspace-dark.png` 为深色主题。

Android 连接入口来自已签名发布 APK 的冷启动；项目和会话内容来自隔离原生 UI 测试，用于验证布局和操作，不是生产用户的数据。Mac 通过独立测试状态目录启动，没有绑定真实电脑或修改用户配对。

设计参考 Uiverse Galaxy 的卡片层级与交互反馈，界面代码由项目现有 HTML/CSS 与 Jetpack Compose 实现，无直接复制的第三方组件源码。
