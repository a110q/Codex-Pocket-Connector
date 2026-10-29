# Codex Pocket 0.9.9 视觉验收

- Source visual truth: `design/review/0.9.9/reference.html`，以及同目录 `reference-chat.png`、`reference-settings.png`、`reference-hosts.png`、`reference-attachments.png`。
- 原始设计视口：390 × 844 CSS px，deviceScaleFactor 1；参考截图 390 × 844 px。
- Android 对比：390 × 844 dp 的应用内容区，保留真实系统状态栏及手势导航，不将它们复制进 UI。第一轮截图 1080 × 2400 px 仅用于探索，未作为等尺寸结论。
- 第二轮截图 1170 × 2676 px，密度 3，裁去顶部/底部各 72 px 系统区域，缩为 390 × 844 px。后续 780 × 1784 px、密度 2，裁去顶部/底部各 48 px 后缩为相同尺寸。
- 场景：浅色聊天、展开的运行配置、电脑与附件面板；另查深色、长模型名、键盘和大字号。

## Comparison history

### 初次实现

`.private/ui-0.9.9/capture-rounds/emulator-pass1` 的应用截图已打开检查。该轮已采用开放正文、一行摘要、一行运行配置及底部面板；发现引用区过大，失败仅藏在折叠记录中。随后补充独立失败卡片并收紧正文排版。

### 第二轮：等尺寸并排检查

Full-view evidence: `design/review/0.9.9/comparison-pass2.png`。
Focused settings evidence: `design/review/0.9.9/comparison-settings-pass2.png`。

- [P2] Android TextView 的默认字体行距放大了正文换行与引用区，导致失败和审批卡片整体下移。已改为明确的 25 sp 正文行高，引用字号 13.5 sp，并减少段落空白。
- [P2] 返回最新内容单独占用一行，压缩聊天区。已改为用户向上滚动后出现的浮动按钮。
- [P2] 运行配置面板的重复说明与多余间隔偏高。已精简为紧凑的标签、48 dp 选项和当前权限说明；真实模型数量仍由电脑决定。
- [P2] 小屏大字号时摘要行可能过密。已为运行配置与执行过程增加大字号换行布局。

### 最终面板与审批检查

`comparison-chat.png`、`comparison-settings.png`、`comparison-hosts.png` 已合并两侧同尺寸截图打开检查。

- 设置面板使用 36 × 4 dp 的源稿拖动条，去掉额外尾注，未修改时提供真实“完成设置”关闭动作；修改后仍提交实际设置。
- [P2] 用户滚到顶部时，返回最新的浮动按钮可能盖住待审批卡片的详情入口。已限制为没有待审批请求时才显示。修复后已重新捕获并打开 `comparison-chat.png`，审批区域没有浮动覆盖；独立回归 1/1 通过。
- 截图明确通过用户滚动停在第一条消息，避免把默认“打开最新内容”的位置与源稿顶部位置误比。

## Final comparison

- Full view: `design/review/0.9.9/comparison-chat.png`。参考与最新原生截图同时置于一张 800 × 874 px 比较图内，含标注与边距；左右应用内容各 390 × 844 px。最终 Android 原图为 780 × 1784 px、密度 2，裁掉真实系统区域后归一化。
- 配置与电脑面板：`comparison-settings.png`、`comparison-hosts.png`；附件：`comparison-attachments.png`。
- Focused chrome: `comparison-chrome.png`，将顶部和底部区域从同一对最终截图中裁出并列检查，明确核对字体、图标、48 dp 操作及输入区边框/间距。
- 其他状态：`native-dark.png`、`native-keyboard.png`、`native-large-text-settings.png`、`native-large-text-keyboard.png`。早期长输入截图使用密度 3，其余最终对比使用密度 2；均先裁去实际系统区域，再缩至 390 × 844。

## Required fidelity surfaces

- Fonts / typography：正文 15.5 sp、明确 25 sp 行高；引用约 13.5 sp / 20 sp、段落间距 14 sp。标题/摘要/辅助文字层级与参考对应，使用 Android 系统中文字体与系统等宽字体；大字号摘要改为两行，长名称截断不挤掉发送按钮。Android 与浏览器系统字体的字宽、基线及抗锯齿存在轻微差异，按平台预期差异接受。
- Spacing / layout：核对 16 dp 内容边距、60 dp 顶栏、14–16 dp 卡片、18 dp 面板顶部圆角及 48 dp 操作。助手正文保持全宽开放；审批进入内容流且可滚到完整按钮；固定配置与输入区始终可达。前述引用区、面板和浮动按钮问题已修复。没有遗留 P0/P1/P2 布局问题。
- Colors / tokens：背景、靛蓝、气泡、薰衣草、文字及描边直接映射源稿数值。语义失败卡片保持细红边浅底；深色模式使用相同层级与适配后的暗色表面。
- Assets / image quality：源稿聊天关键 SVG 转换为本地 VectorDrawable，保持矢量清晰；沿用既有产品标识。不存在缺失的插画、占位图或需生成的位图素材。
- Copy / content：产品使用真实电脑、可用模型、授权模式和消息。模型列表多一行来自实际返回的三个模型，面板因此更高；不为了模仿静态样例隐藏可用模型。未修改配置显示可用的“完成设置”，修改后显示“保存设置”并提交真实更新。元数据不虚构源稿时间；失败使用真实退出码和详情入口。审批“允许本次”保留单次授权含义。

## Interaction evidence

最终面板完成/设置保存、发送/停止、附件、电脑及更多操作回归通过。整个工作累计 20 个不同模拟器回归场景通过，覆盖队列、审批、网页/文件预览、独立草稿、长输入、断线及大字号。Node 180 项、JVM 21 项通过；最终 Lint 通过。发布 APK 签名校验、覆盖安装及冷启动通过。

## Findings and remaining polish

无未解决的 P0/P1/P2。

- [P3 / accepted] Android 中文字体与浏览器回退字体会产生细小基线、换行和垂直节奏差异；主要尺寸、颜色、结构和可操作区域已对应。
- 原稿的电脑/模型数、时间、网络恢复提示及通用“重试命令”为示例；产品的真实数据与可用操作差异属于有意保留的行为约束，详见 `docs/ui-0.9.9.md`。
- 模拟器验证不能代替所有 Android 厂商字体/输入法组合；Windows 实机连接验收仍未进行。

## Implementation checklist

- [x] 映射颜色、图标、字阶、卡片与面板规范。
- [x] 接回真实导航、配置、电脑、附件、消息及审批交互。
- [x] 同尺寸并排比较，修复引用间距、面板密度和审批覆盖。
- [x] 检查深色、大字号、长名称、键盘及队列状态。
- [x] 验证发布 APK 签名、覆盖安装和冷启动。

final result: passed
