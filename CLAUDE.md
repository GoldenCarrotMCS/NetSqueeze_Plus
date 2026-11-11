# NetSqueeze Plus

## 必须遵守

- **禁止本地构建、编译、执行测试或启动服务器；全部交给 GitHub Actions。** 允许源码审阅、差异检查、配置/脚本语法静态解析。
- 不自动提交或推送；只有用户明确要求时才进行。不要自动接受 Minecraft EULA。
- 保留用户已经修改的代码；发布前必须报告实际执行的验证以及未验证项。

## 项目基线

- 公开独立版，不依赖 CoreLib。Kotlin 运行库重定位后完整打包，不启用 Shadow minimize。
- Java 25、Kotlin 2.3.20；服务端内部实现绑定 Minecraft 26.1.2，dev bundle 固定 `26.1.2.build.8-stable`。
- Fabric 客户端使用 Java、不依赖 Fabric API，编译基线 26.1.2；跨 26.1～26.3 声明继承自上游，改动后不能仅凭编译声称兼容。
- Loom 固定正式版本，在根项目 apply false 声明、client 子项目应用，避免与 paperweight 类加载器冲突。
- 两端使用根目录同一版本号；公开仓库远程为 `origin`，不依赖历史私有分支/远程结构。

## 模块与协议

- `platform/ServerNetwork`：版本边界与 CraftBukkit Channel 获取。扩版本前先独立测试，不直接放宽版本字符串。
- `netty/Injector`：监听连接、握手、管线注入和卸载；`ChannelRegistry`：连接关闭立即淘汰，卸载解绑 listener。
- `stream/HelloHandler` / `Protocol`：配置阶段收到 `netsqueeze:hello_v1`，在同一 Netty 任务发送最后一个原版 `netsqueeze:enable` 并切换出站。
- `StreamCompressor` / `FrameDeflater`：raw deflate、连接共享字典、按 flush 收帧；大帧提前切成子帧但不主动 flush。出站从 prepender context 写出，保留原版 compress/prepender 的名称供其他插件定位。
- 非 ByteBuf 配置任务必须先收帧再原样转发。Promise 必须随对应帧的写结果完成。异常不能退回原版，只能关闭受影响连接。
- 客户端 `StreamDecoder` 在 splitter 前按原版帧逐帧移交；真实 `EnableDetector` 同步切换后，余下字节按流式解码，从 decompress（或 splitter）context 绕过原版解压。未协商成功时配置阶段结束无损撤除。
- 加密时 decoder 必须位于 decrypt 后。只调整解码器顺序的测试不等于正版环境已实测。
- v1：`VarInt 压缩帧长 + raw deflate SYNC_FLUSH`；解压后重复 `VarInt 包长 + 包内容`。协议变更时服务端和客户端 VERSION 一起改。
- metadata `netsqueeze_mod` 表示已切换协议，不是签名认证或反作弊证明。
- `stats/PlayerHistory` 只在进退服/reset 锁定生命周期，不锁逐包计数；按连接标识防旧 quit 影响新会话。24 小时 TTL、最多 10,000 条离线记录，在线不淘汰。
- 不引入定时器攒包；保持现有 flush 边界。历史上高比例小 flush 会降低压缩收益，不能把改过核心的实测值保证给所有服务器。

## CI 与验收

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

- PR 构建只读权限、无持久化 Git 凭据；独立 Release job 才有写权限，不执行 Gradle。
- 根测试用真实 StreamCompressor 生成协议夹具；client:test 依赖根 test 并用真实 StreamDecoder / EnableDetector 解码。不把两端 Minecraft 放在同一测试类路径。
- Action 与 Wrapper 固定校验值；依赖锁和 verification metadata 只能在可信 GitHub 修订上生成、人工审核，不能自动信任 PR 的首次下载。
- `scripts/ci/verify_artifacts.py` 检查两端版本、描述符、Kotlin 重定位及 Mixin 类，产生两个 Jar 与 SHA256SUMS。
- 服务器 smoke 是显式 opt-in：可信 Jar + SHA-256 + EULA。只有启动/命令/停服，不是真实 Fabric 登录测试。
- 需要真实环境独立验收：Canvas/Folia/Paper 具体构建、正版加密、阈值 -1、Via/PacketEvents 等插件组合、多区域重连和卸载。

## GoldenCarrot服务器与代理模式（1.2.0）

- 服务器名称为 GoldenCarrot服务器。上游历史数据不得改称本服务器实测，作者与许可不改。
- 新增 `transport-mode: auto/direct/velocity`；VC 后端建议明确 velocity。auto 只探测后端已有代理转发开关，不是全能代理检测。探测失败时选择隧道；已知代理不得强制裸流 direct。
- **不在 VC 添加插件。** 后端 PLAY 邀请 + UUID ACK，普通 CustomPayload 透明承载。裸 v1 不能穿过 Minecraft 代理；只补 hello 不可行。
- `shared/.../TunnelProtocol.java` 两端共用白名单/分片限制。首版隧道仅同版本 26.1.2，不允许不透明包体交给 VC/Via 做跨版本转换。
- `tunnel/TunnelGate` 在 encoder 尾侧、unbundler 头侧标记包；`TunnelCompressor` 编码后截获白名单 ByteBuf，再经正常 encoder 发送载荷。不能绕过原版 compress/prepender。
- 小于 tunnel-min-packet-bytes 的包原样发送且不推进字典。遇控制包先收帧。重生/配置切换/卸载使用 STOP 隔离字典；初始/新 PLAY 入站限次重试，不能固定延迟强制启用。
- 客户端 fallback Codec 只保留本协议载荷，不依赖 Fabric API；TunnelClientHandler 在 decoder 后、bundler 前。用 PacketDecoderAccessor 取当前 ProtocolInfo.codec() 还原对象，**不可递归进入 ByteToMessageDecoder**。
- 配置切换后原版会重新插入 bundler，必须重新安置客户端隧道 handler。共享 FrameInflater 使用有界内存，重组缓冲在回调前转移所有权，防同步关连接双重释放。
- metadata 在代理 ACK 后才通过实体调度器设置，不能保证 PlayerJoinEvent LOWEST 可见。
- `/nsq` 的隧道 raw/payload 不是相对原版收益，也不是 VC 出口 TCP 流量。比较实际节省需要在 VC 出口测量。
- 根测试生成 tunnel-*.bin；客户端消费服务端真实载荷。它不代替真实 VC + Fabric + 后端联调。参见 docs/VELOCITY.md。
