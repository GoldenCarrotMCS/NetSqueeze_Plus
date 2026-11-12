# 开发与验证

## 约束

- **本项目构建、编译、测试执行与服务器启动统一交给 GitHub Actions；禁止在本地运行 Gradle、Minecraft 或服务器。** 本地只做源码修改、差异审阅和不执行项目代码的静态检查。
- 基线为 Java 25、Kotlin 2.3.20、Minecraft 服务端 26.1.2。保持 dev bundle `26.1.2.build.8-stable` 固定，不能用 `+`。
- Fabric Loom 在根构建声明固定正式版本，子项目只应用插件；不使用 `mavenLocal()`。
- 根项目和客户端使用同一个版本号。改协议必须同步 `stream/Protocol.VERSION` 和 `NetSqueezeClient.VERSION`。
- 修改不自动提交或推送，不接受隐式发布或自动同意 Minecraft EULA。

## CI 流程

1. 任意分支推送和 PR 都自动运行 **Build**，完成服务端与客户端编译、JUnit 测试、Jar 结构检查。
2. 根项目 `StreamCompressorTest` 通过真实 Netty 处理器生成 `build/protocol-fixtures/`；客户端测试消费同次构建的字节，避免把两个不同 Minecraft 开发类路径混到一起。
3. 测试报告无论成功失败均上传到 `test-reports`。成功构建上传 `NetSqueeze`，内含两个版本一致的 Jar 及 `SHA256SUMS.txt`。
4. 独立发布 job 仅在分支 push 或勾选 `publish_jars` 的 workflow_dispatch 上申请 `contents: write`，永不从 PR 发布，Dependabot 不发布。它不运行 Gradle；认证、API 错误和指向其他提交的既有标签均导致失败，而不是当作“Release 不存在”。
5. `master` 按根 `version` 创建正式版，准备新正式版需改版本号；同版本不覆盖。其他分支每个提交自动创建 `preview-v<版本>-<12 位提交>` 预发布，不改正式 Latest，不移动已有标签。测试包从 Releases 的预发布直接下载，不必合并 PR。
6. 手动构建默认只产出 Artifacts；需要发布时勾选 `publish_jars`。真实服务器 smoke 仍是另一个显式 opt-in，不会因自动发布而接受 EULA。
7. 同仓库的分支推送及其 PR 可能各运行一次验证；这是不同事件，PR 发布步骤显示 skipped 是安全边界，不代表 build 被跳过。

## 依赖基线（首次必须经维护者审核）

本地禁止解析或构建，所以不能在本地产生真实依赖锁和 SHA-256 元数据。

1. 将经审核的修改送到 GitHub 后，在可信修订上手动运行 **Dependency baseline**。
2. 下载 `dependency-baseline` Artifact，检查 `gradle/verification-metadata.xml`、根目录各目标的 `gradle-<平台>-<开发包>.lockfile` 和客户端各版本的 `client/gradle-<版本>.lockfile`。
3. 核对仓库来源、依赖坐标和校验值；首次下载的校验值仅是待审核基线，不是独立的可信证明。
4. 经授权提交审核后的基线。后续构建使用 `--dependency-verification strict` 和 Gradle dependency locking。
5. **在基线提交前，CI 会发出警告；缺少元数据时不能声称已经验证所有传递依赖。** 更新依赖时重复生成并审核差异，不能在 PR 构建中自动接受新校验值。

Gradle 分发本身已经固定 SHA-256；Action 使用固定提交并由 Dependabot 提议更新。

## 测试与兼容矩阵

| 场景 | 自动化入口 | 证明范围 |
|---|---|---|
| 真实服务端压缩器 → 客户端解码器 | Build / JUnit | level 0/6/9、随机 TCP 分片、3 MiB 数据包、跨帧字典 |
| 握手切换、异常帧、回退 | Build / JUnit | 真实 EnableDetector；包对象解码由测试适配器替代，不证明完整游戏登录 |
| 缓冲上限与资源生命周期 | Build / JUnit | 解压上限、非法长度、ByteBuf 释放、Promise、Channel 清理 |
| 统计历史、配置解析 | Build / JUnit | TTL、离线容量、会话保护、配置类型和范围 |
| 服务器启动/命令/停服 | Build / 手动 server-smoke | 仅维护者提供的目标 26+ 服务端 Jar，不证明客户端握手 |
| 正版加密、真实 Fabric 玩家及其他网络插件共存 | 手工集成验收 | 必须分别实测，不能由单元测试或能编译推导兼容性 |

## 手动服务器烟测

在 GitHub 的 **Build → Run workflow** 中勾选 `run_server_smoke`，提供可信 HTTPS Jar 地址和已独立核验的 SHA-256，并在阅读 EULA 后明确勾选 `accept_minecraft_eula`。

服务器 Jar 下载到新目录；运行时另建空目录，不复用世界，不从下载目录加载 Python 模块。测试只绑定 `127.0.0.1`，验证插件启用、四个控制台命令和干净停服，日志保存为 `server-smoke-logs`。

不要使用未知来源的服务器 Jar。烟测以维护者身份授权执行服务器代码，但不会把 GitHub 写令牌提供给该 job。

## 发布前人工验收

- 分别验证目标 Canvas/Folia/Paper 构建；未验证的派生核心不写作“完全兼容”。
- 装 Mod / 不装 Mod 都能登录；enable 和第一帧粘包仍正确。
- `network-compression-threshold=-1`、启用原版压缩、正版加密分别测试。
- 多区域玩家、重连、退出与插件停用；观察无非法 Folia 线程访问或旧类加载器残留。
- ViaVersion、PacketEvents 等兼容性按实际版本逐一测试。
- 安装客户端 26.1～26.3 的声明继承自上游；本次静态修改没有重新完成这些版本的真实客户端验证。

## 新增 Velocity 传输（1.2.0）

GoldenCarrot服务器的代理方案和验收清单见 [docs/VELOCITY.md](docs/VELOCITY.md)。不添加 VC 插件。1.3.0 改为 v2 协议协商：运行时协议号一致才启用，26.2 和 26.3 不再被固定字符串拒绝；协议不同维持原版。

测试夹具现同时包含直连帧和带 UUID/序号的真实隧道载荷。`client-compat` 在 GitHub 分别针对 26.1、26.1.1、26.2、26.3 编译并检查新增原版接口；26.1.2 在主 Build 验证。Release 依赖这些 job 全部成功，但纯编译/API 检查不能证明真实 Fabric Mixin 启动与代理互通。

依赖基线生成会遍历这些版本，并分别生成 `client/gradle-<版本>.lockfile`，审核时一并提交。手动服务器 smoke 仍不包含真实 VC 或 Fabric 客户端，不能拿它证明代理压缩收益。

## 1.3.0 多版本服务端

- 默认发布基线仍固定 Folia 26.1.2 开发包，生产代码不直接引用 CraftPlayer/NMS 类；易变签名集中在缓存 PacketAccess/ChannelAccess。
- server-compat 在 Paper 26.1.1/26.1.2/26.2/26.3 和 Folia 26.2/26.3 上执行适配器测试，还通过隔离类加载器加载主 Build 的同一发布 Jar，不用“重新编译能过”冒充发布 Jar 可链接。
- 运行时只接受 26+ 正式版字符串，实际 API 形状必须通过检查才注册网络处理器。26.1 无独立 Paper 开发包未单独证明；未来版本属于能力探测/待验证，不承诺永远兼容。
- 客户端 CI 仍覆盖已发布 26.1～26.3，Fabric 声明放宽到后续 26.x；未知 Mixin 签名仍可能需要适配，不能保证未来客户端启动。
- 不主动运行服务器/游戏验收；用户负责实际 Canvas/Paper/Folia、VC、ViaVersion 和 Fabric 安装验证。没有自动接受 EULA。

## 1.4.0 大包 ZSTD 验证

- `HybridFramesTest` 用真实直连/隧道处理器产生 Deflate 小包 → ZSTD 大包 → Deflate 小包夹具，包括阈值边界、高熵回退、3 MiB 原包、多分片、禁用开关和旧客户端协商。
- 客户端 `FrameInflater`、`StreamDecoder`、`EnableDetector`、`TunnelReassembler` 消费本次构建的真实字节，核对原始包序列。CI 必须真正加载 ZSTD native，不允许 assume 跳过冒充成功。
- 产物验证核对服务端完整 native、客户端嵌套 ZSTD Jar/native；两端共享协议代码不能 import ZSTD 或 Fabric。
- 默认 64 KiB 大包阈值、level 1，关闭 `zstd-enabled` 或 native 探测失败时仍保留 Deflate。原版和旧客户端不收到未协商的 ZSTD 格式。
- 没有本地构建或测试。真实服务器、游戏、VC 和其他操作系统/架构 native 加载验收交给用户。
