# GoldenCarrot服务器：Velocity 适配

## 为什么旧版本没有效果

Velocity（VC）不是透明 TCP 转发：客户端到 VC、VC 到后端是两条独立的 Minecraft 连接。原来的 `netsqueeze:hello_v1` 在配置阶段可能遇到尚无后端连接的窗口；即使修复 hello 转发，后端发送裸 deflate 流后，VC 仍会按原版帧/包解析，不能靠补一条握手解决。

本项目不要求给 VC 安装任何插件，也不修改代理核心。改动只在 **后端插件 + 客户端 Mod**。

## 部署

1. GitHub 构建验证通过后，同时更新后端插件和客户端 Mod 至 **1.4.0**。
2. 以你的 **26.2** 后端为例，客户端也使用 **26.2**。v2 比较的是运行时协议号，不再把版本写死为 26.1.2；26.3 同理。未来版本仍需能力检查与用户实际验收。
3. 在后端 `plugins/NetSqueeze/config.yml` 设置：

   ```yaml
   # 通过 Velocity 时明确使用普通 CustomPayload 隧道。
   transport-mode: velocity
   # 默认压缩级别。
   compression-level: 6
   # 小包继续原版，避免隧道固定头使流量放大。
   tunnel-min-packet-bytes: 256
   ```

4. 重启后端。VC 不需要安装插件、注册新频道或修改核心；已有现代转发密钥等正常连接配置保持不变。
5. `auto` 会读取后端 Paper 的 Velocity / Spigot Bungee 转发开关；无法探测时保守选隧道。但它不是通用代理识别器，离线/无转发代理必须明确设 `velocity`。不能在代理路径强制 `direct`。

旧客户端不认识新邀请，会保持原版传输，不会因为收到邀请被切到裸流。

## 新链路

1. 后端进入 PLAY 后先发送 `netsqueeze:tunnel_offer_v3`（UUID + 协议号 + ZSTD 能力位，共 21 字节），再发原有 v2 邀请供旧客户端回退。新客户端首个有效邀请锁定格式，后来的旧格式邀请不能覆盖字典。
2. 客户端核对版本、解码器和游戏阶段，协议一致才用带协议号和 UUID 的 `netsqueeze:tunnel_ack_v3/<协议号>/<zstd或deflate>/<UUID>` 频道回复。ACK 不是密码或身份认证，只用于隔离过期会话。
3. **收到 ACK 前完全保持原版**。后端在真正的 PLAY 入站时限次重试邀请，不依赖初始配置 hello。
4. 白名单包经过后端原有 encoder 编码，再按 flush 收帧、跨包压缩。
5. 每帧拆成最多 256 KiB 的数据分片，加 24 字节 UUID/序号头，放入 `netsqueeze:tunnel_data_v3`（旧客户端仍是 v2 频道）。这些载荷重新走正常的 Minecraft 编码、压缩、帧长和加密。
6. VC 收到的仍是合法原版 CustomPayload。未注册频道通常走其内置透传分支，不需要代理扩展。
7. 客户端先于 bundler 重组/解压，再用当前 PLAY 协议 Codec 解码，并再次校验白名单后交给游戏。不会递归进入原版 `ByteToMessageDecoder`。
8. 重生、重配置、转后端或新邀请均隔离字典；序号缺失、重复、超限会断开受影响连接。旧会话迟到片段被丢弃。

## 什么压缩、什么不压缩

白名单包括实体位置/朝向/速度/数据/装备、区块/光照/方块更新、粒子和声音等。小于 `tunnel-min-packet-bytes` 的包仍走原版，不推进压缩字典。

**不封装** JoinGame、Respawn、KeepAlive、聊天、命令、资源包、Cookie、Transfer、配置切换等控制包。它们必须仍被 VC 看见。遇到原版包会先收完待发隧道帧，保持顺序。

代理上依赖观察白名单原始包的插件不会看到封装后的内部数据，这是无代理插件方案的限制；后端网络插件通常仍在编码前看到原包，但具体组合需要实测。现有代理插件若主动拦截这些频道，后端无法强迫其放行。

## 为什么必须匹配实际协议号

隧道里面是后端编码后的包 ID 和内容。VC / ViaVersion 无法对不透明载荷里的内部包做协议转换，因此不能把 26.2 的编码字节直接交给协议不同的客户端 Codec。协议相同可协商；协议不同则维持原版，不伪装成已加速。即使 VC 装了 ViaVersion，内部载荷仍不能自动做版本转换。旧 1.2.0 客户端不认识 v2，会保持原版；必须两端升级。

## 检测和流量口径

- `/nsq` 区分直连流和代理隧道；`/nsq players` 用 `[mod/tunnel]` 标识已经确认的代理会话。
- 客户端先出现“已协商 Velocity 压缩隧道”，真正恢复数据后再出现“已恢复首个数据包”。只有协商日志不能证明已有数据经过压缩。
- metadata `netsqueeze_mod` 在 ACK 后通过玩家实体调度器设置。**代理模式不能保证在 PlayerJoinEvent LOWEST 就可读**，依赖该标记的业务应延后检查。
- “后端出网”是后端到 VC；“隧道载荷”是内部压缩数据加分片头。两者都不能冒充 VC 到玩家的实际 TCP 流量。
- 原版大包本来就会 zlib 压缩。内部 `raw → payload` 的比率不是“相对原版节省比率”，也不是延迟/网速提升证明。用 VC 出口网卡或计费侧数据比较，不能保证复现上游直连数据。
- 首轮握手之前的包是原版；不会扣住玩家登录或等待 ACK 攒包。停用隧道可发送 STOP 回到普通传输，直连裸流仍需要断开重连。

## 验证状态与验收

代码附带服务端真实压缩器到客户端重组器的跨项目夹具对拍，覆盖分片、3 MiB 输入、字典连续性、错误序号、过期会话、长度上限与原版控制保序。它模拟透转载荷，不是一次真实 VC 登录测试。

GitHub 还配置了原有 26.1～26.3 客户端声明的编译/API 检查矩阵；这些检查不能证明 Mixin 在真实 Fabric 环境启动成功。

发布前必须通过 GitHub 构建，并用实际 Velocity + 后端 + Fabric 客户端验证：装/不装 Mod、登录、区块加载、原版保活、跨后端转服、维度重生、重配置、关闭插件与网络插件组合。**所有编译和自动化测试只在 GitHub 运行；不会在本地构建或自动进行服务器/游戏验收。真实联调由用户完成。**

## 实现依据

核对了 Velocity 上游 `dev/3.0.0` 的：

- `ClientConfigSessionHandler.handle(PluginMessagePacket)`：早到消息依赖后端连接是否存在。
- `BackendPlaySessionHandler.handle(PluginMessagePacket)`：未知频道可交给通用转发，不必由代理插件注册。
- `ClientPlaySessionHandler.handle(PluginMessagePacket)`：游戏阶段客户端消息转发/排队。
- `PluginMessagePacket`：默认 clientbound 载荷上限 1 MiB，本协议每片远低于此上限。

源码：[PaperMC/Velocity](https://github.com/PaperMC/Velocity/tree/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy)。部署的具体 VC 构建及其其他插件策略仍须验证。

## 1.4.0 大包 ZSTD

后端设置 `zstd-enabled: true`、`zstd-min-packet-bytes: 65536`、`zstd-level: 1`。只有白名单且达到原有隧道准入阈值、双方协商成功、编码后单包达到 64 KiB 时，才尝试独立 ZSTD 帧；小包算法与原版直通规则不变。ZSTD 不推进连续 Deflate 字典，不新增攒包定时器或强制 flush。

ZSTD 失败或结果不比 raw 包帧更小，可在发送前回退 Deflate；输出后不允许悄悄丢掉压缩结果改变字典。两端都显式关闭原生上下文，解码校验内容大小、8 MiB 窗口及完整单帧边界。

旧版 1.3.0 Mod 会响应 v2 邀请，只收到原格式 Deflate。VC 不需要改配置或安装插件。客户端 Jar 因嵌套 ZSTD-JNI/native 库增大；不同操作系统/架构的原生加载、真实 Fabric 启动及实际出口收益由用户验收，不由 Ubuntu CI 自动推定。
