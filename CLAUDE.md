# NetSqueeze Plus

## 必须遵守

- **禁止本地构建、编译、执行测试或启动服务器；全部交给 GitHub Actions。** 允许源码审阅、差异检查、配置/脚本语法静态解析。
- 用户已明确长期授权：本项目开发完成后自动提交相关改动并正常推送功能分支，跟进 GitHub Actions 构建、修复失败项并交付预发布；不再重复询问。不 force-push、不自动合并主分支、不部署真实服务器或上传私密日志。不要自动接受 Minecraft EULA。
- 保留用户已经修改的代码；发布前必须报告实际执行的验证以及未验证项。

## 项目基线

- 公开独立版，不依赖 CoreLib。Kotlin 运行库重定位后完整打包，不启用 Shadow minimize。
- Java 25、Kotlin 2.3.20；默认发布编译基线仍是固定的 Folia `26.1.2.build.8-stable`；1.3.0 生产代码不再直接链接 NMS/CraftPlayer，易变签名集中在缓存适配器中，服务端接受 26+ 正式版并做 API 能力检查。
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

- 所有分支推送及 PR 自动构建；PR 只读权限、无持久化 Git 凭据。独立发布 job 只在可信分支 push 或明确要求发布的手动构建上有写权限，不执行 Gradle。master 正式版，其他分支按提交生成不可变预发布，不能覆盖正式 Latest 或强制移动标签。
- 根测试用真实 StreamCompressor 生成协议夹具；client:test 依赖根 test 并用真实 StreamDecoder / EnableDetector 解码。不把两端 Minecraft 放在同一测试类路径。
- Action 与 Wrapper 固定校验值；依赖锁和 verification metadata 只能在可信 GitHub 修订上生成、人工审核，不能自动信任 PR 的首次下载。
- `scripts/ci/verify_artifacts.py` 检查两端版本、描述符、Kotlin 重定位及 Mixin 类，产生两个 Jar 与 SHA256SUMS。
- 服务器 smoke 是显式 opt-in：可信 Jar + SHA-256 + EULA。只有启动/命令/停服，不是真实 Fabric 登录测试。
- 需要真实环境独立验收：Canvas/Folia/Paper 具体构建、正版加密、阈值 -1、Via/PacketEvents 等插件组合、多区域重连和卸载。

## GoldenCarrot服务器与代理模式（1.2.0）

- 服务器名称为 GoldenCarrot服务器。上游历史数据不得改称本服务器实测，作者与许可不改。
- 新增 `transport-mode: auto/direct/velocity`；VC 后端建议明确 velocity。auto 只探测后端已有代理转发开关，不是全能代理检测。探测失败时选择隧道；已知代理不得强制裸流 direct。
- **不在 VC 添加插件。** 后端 PLAY 邀请 + UUID ACK，普通 CustomPayload 透明承载。裸 v1 不能穿过 Minecraft 代理；只补 hello 不可行。
- `shared/.../TunnelProtocol.java` 两端共用白名单/分片限制。1.3.0 隧道 v2 按后端/客户端实际协议号匹配，不再限定 26.1.2，不允许不透明包体交给 VC/Via 做跨版本转换。
- `tunnel/TunnelGate` 在 encoder 尾侧、unbundler 头侧标记包；`TunnelCompressor` 编码后截获白名单 ByteBuf，再经正常 encoder 发送载荷。不能绕过原版 compress/prepender。
- 小于 tunnel-min-packet-bytes 的包原样发送且不推进字典。遇控制包先收帧。重生/配置切换/卸载使用 STOP 隔离字典；初始/新 PLAY 入站限次重试，不能固定延迟强制启用。
- 客户端 fallback Codec 只保留本协议载荷，不依赖 Fabric API；TunnelClientHandler 在 decoder 后、bundler 前。用 PacketDecoderAccessor 取当前 ProtocolInfo.codec() 还原对象，**不可递归进入 ByteToMessageDecoder**。
- 配置切换后原版会重新插入 bundler，必须重新安置客户端隧道 handler。共享 FrameInflater 使用有界内存，重组缓冲在回调前转移所有权，防同步关连接双重释放。
- metadata 在代理 ACK 后才通过实体调度器设置，不能保证 PlayerJoinEvent LOWEST 可见。
- `/nsq` 的隧道 raw/payload 不是相对原版收益，也不是 VC 出口 TCP 流量。比较实际节省需要在 VC 出口测量。
- 根测试生成 tunnel-*.bin；客户端消费服务端真实载荷。它不代替真实 VC + Fabric + 后端联调。参见 docs/VELOCITY.md。

## 1.3.0 多版本适配与验证边界

- 不保证未知未来版本永远兼容。MinecraftVersions.eligible 只决定版本是否进入 API 检查，不是完整兼容认证；API 失败时在注入前禁用。
- PacketAccess 缓存 Identifier、载荷、断线包和 SharedConstants.getProtocolVersion 反射签名；ChannelAccess 缓存 CraftPlayer 到 Channel 的字段链。生产代码不导入 net.minecraft 类，管线锚点逐连接核对。
- v2 邀请为 UUID 16 字节 + 后端实际协议号 4 字节；ACK 频道含协议号和会话。v2 offer/data/stop/ack 与旧隧道隔离，旧客户端保持原版，需两端升级 1.3.0。直连 v1 线格式保持不变。
- server-compat：Paper 26.1.1/26.1.2/26.2/26.3、Folia 26.2/26.3，默认 Build 覆盖 Folia 26.1.2；发布依赖全部成功。PublishedAdapterTest 用同一个主构建 Jar 在目标开发运行库中链接，不能替换为只重新编译源码。
- 客户端已有 26.1～26.3 CI 矩阵，Loader 声明允许后续 26.x，但未知 Mixin 仍可能需更新；不保证未来版本启动。服务端 26.1 无独立开发包，未单独证明。
- 本地不构建/不执行测试；用户自行负责游戏、服务器和 VC 验收。不主动启动任何接受测试，也不代用户接受 EULA。

## 1.4.0 大包 ZSTD

- 默认配置 zstd-enabled=true、zstd-min-packet-bytes=65536、zstd-level=1。小包保持现有算法，VC 极小包仍原版。
- 直连 v3 和隧道 v3 明确协商标记帧：0=连续 Deflate，1=独立 ZSTD；旧直连 v1/隧道 v2 始终没有算法标记。
- 新客户端发送现代 hello 再发送旧 hello，旧服务端仅响应旧格式。后端先发 v3 再发 v2 offer，客户端首个有效会话固定格式，同格式重试可重发 ACK，回退邀请不能改变活动字典。
- 大包前先 emit 而不 flush。ZSTD 压独立副本，失败或不比 raw 包帧小时尚未推进字典，可回退 Deflate。不能把已喂入 Deflate 的数据丢掉改算法。
- ZstdFrame / ZstdFrameDecoder 校验内容大小、完整单帧和窗口23；所有 ctx 必须 close。shared 不引入算法库。JNI 包不能 relocate，服务端 shade 保留完整原生资源；客户端 implementation + Loom include 嵌套完整库。
- ZSTD-JNI 固定 1.5.7-26，不允许 + 或 latest。CI 必须实际验证 native，不能 assume 跳过；仍不本地构建/运行游戏或服务器。跨平台真实验收由用户负责。


## 1.6.0 ZSTD 会话字典

- 独立能力 hello/enable 和隧道 v5（42 字节邀请，尾部显式 cache flag），旧 v3/v4 不解释新标记。小包仍 Deflate，64 KiB 大包阈值不变。
- marker 2 = 字典 SHA-256 32 字节 + 标准原始字典 ZSTD 帧；上一个成功 ZSTD 的原始包长前缀和包体尾部派生 64 KiB 字典。仅选中 ZSTD 才推进，Deflate/失败尝试不推进。引用前必须校验摘要，不可不安全地回退。
- 字典是每连接/后端会话独立内存，不上传、不持久化、不跨玩家共享。暂停/卸载/断连关闭；区块缓存有无不影响字典协商，但必须保持信封模式一致。
- JNI 旧库兼容仍使用 loadDict(byte[]) 和纯 Java 帧头预检，不恢复 setLongMax 硬依赖。新字典实际探测失败只退回独立 ZSTD，不误报支持。
- 原生库、字典、双端真实夹具和旧库独立 JVM 测试由 GitHub 执行。本地不构建/运行测试；按用户长期授权自动提交功能分支并跟进云端构建与预发布。
