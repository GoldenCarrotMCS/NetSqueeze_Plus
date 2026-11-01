# NetSqueeze

Folia/Canvas 服务器插件(Kotlin 2.3 / JVM 25,paperweight `foliaDevBundle`,shadow 打包),依赖 CoreLib;
加一个配套的 Fabric 客户端 mod(`client/`,Java,MC 26.1.2)。
**装了 mod 的玩家走"按 flush 攒帧 + 跨包流式压缩"的下行格式,没装的走原版。** 思路来自 NeoForge mod
NotEnoughBandwidth(只有 NeoForge、两端必装、作者明确不做 Bukkit 版,所以自己做)。不需要改服务端核心。

## 常用命令

```
.\gradlew.bat compileKotlin -q --console=plain          # 插件编译校验(改完必跑)
.\gradlew.bat :client:compileJava -q --console=plain    # 客户端 mod 编译校验
.\gradlew.bat build                                     # 一次出两个包:build/libs/*-all.jar 和 client/build/libs/
```

client 是根构建的子项目(`settings.gradle.kts` 里 include),打开根目录 IDEA 就能看到。Fabric Loom 在根
`plugins` 里 `apply false` 声明、子项目只 apply,和 paperweight 放在同一个类加载器里,别挪到子项目里单独写版本。

约定:编译通过后直接 `git commit`(中文提交信息),不 push。dev bundle 钉死在本机已缓存的 `26.1.2.build.8-stable`,
别改回 `+`(解析到新 build 会触发整份反编译)。客户端版本组合抄 Wurst 当年的 26.1.2(loom 1.15、loader 0.19),
26.x 原版不混淆,用不带映射的 `net.fabricmc.fabric-loom`;mod **不依赖 Fabric API**,玩家只要有 Fabric Loader。

## 协议(两端改协议时一起改 VERSION,服务端 `stream/Protocol`,客户端 `NetSqueezeClient`)

1. 客户端登录完成、出站切到配置协议后发 `netsqueeze:hello_v<版本>`(不带数据:原版 DiscardedPayload 不带数据,
   版本只能编进频道名,这样不用 Fabric API 注册载荷)。
2. 服务端认得就在这条连接的 netty 线程上:先用原版格式发 `netsqueeze:enable`,紧接着挂真压缩,之后的出站字节全是新格式。
   只在配置阶段切;不认得的版本原样放过,客户端照走原版。
3. 帧格式:varint 帧长 + deflate 输出(整条连接一个流、nowrap、level 6,每次 flush 做 SYNC_FLUSH 成一帧);
   解压后是一串"varint 包长 + 包字节(包 id + 包体)"。上行不动。

## 服务端(`netty/`、`stream/`、`stats/`)

`netty/Injector` 管全部管线注入。出站方向(从尾到头):encoder(Via/PacketEvents 挂在它附近)→ **真压缩**
→ compress → prepender → **原版计数** → encrypt。
- `netsqueeze_hello`(`stream/HelloHandler`):用 Paper 的 `ChannelInitializeListenerHolder` 在连接一建好就挂在
  packet_handler 前,等 hello;没装 mod 的进服时摘掉。
- `netsqueeze_stream`(`stream/StreamCompressor`,装 mod 的):包不再往 compress 传,喂进 `FrameDeflater`,flush 时
  从 **prepender 的 context** 往下写,绕过原版逐包压缩和长度前缀;compress/prepender 留着不拆(Via/PacketEvents 按名字找)。
  原包 promise 等所在帧写完一起完成。不是 ByteBuf 的出站消息原样放过(原版切入站协议时从出站方向写配置任务,
  要走到头侧 decoder 位置的 inbound_config 才被吃掉;2026-11-01 首测就是把它当异常断开、一进服就掉线),放过前先收帧保顺序。出错退不回原版,只能断开。
- `netsqueeze_wire`(`stats/WireCounter`,没装 mod 的):原版出网计数,只数字节,几乎零开销。
- `/nsq` 两类玩家的出网、包头估算、人均分开显示;`/nsq players` 按人、`/nsq reset` 清零、`/nsq pipeline` 两类玩家各一份管线样本。
- 影子压缩(没装 mod 的也压一遍只量不发,估"全员换压缩能省多少")在真压缩跑通后删了(线上约 0.7 个核心,只换来估算数),
  要再估去 git 历史里找 `shadow/ShadowCompressor`。

热重载:`Injector.shutdown` 同步等各 netty 线程摘完处理器才返回(旧类加载器关闭后旧处理器再处理包会
NoClassDefFoundError 踢人)。已切到真压缩的连接交接不了,先走这条流发断线消息再关,让他们重连。

## 客户端(`client/`)

- `ConnectionMixin`:连接刚建好就在 splitter 前挂 `StreamDecoder`、packet_handler 前挂 `EnableDetector`;
  正版加密时把解码器挪到 decrypt 后面(本服离线模式用不到)。`ClientHandshakePacketListenerImplMixin` 发 hello。
- `StreamDecoder` 切换前**自己按原版帧长切帧、一次只给 splitter 一整帧**,所以 enable 那帧交出去、检测器同步置位后,
  剩下的字节都还在自己手里,直接按新格式解;不用从 splitter 里往回抢,也不在别的解码器中途改管线。切换后解出的包从
  decompress(没有就 splitter)的 context 往下发,直接进 decoder。服务端不认握手时,配置阶段结束撤掉自己。
- 改了编解码要跑对拍:服务端真 `StreamCompressor` 压、客户端真 `StreamDecoder` 解,字节流随机切碎喂(1~5000 字节、
  含 3 MB 大包、空 flush、撤掉路径)。2026-11-01 写在会话 scratchpad 里(`harness/RoundTrip.java`,EmbeddedChannel),
  没进仓库;要再跑就照这个思路重写。

## 背景事实(决定方案的,别重查)

- Canvas 上游的 "optimize flush calls with region threading" 只在 `tickChildren` 前后暂停 flush,前面的 drainTasks、
  处理玩家包、区域/实体调度器里发的包一包一 flush。2026-11-01 实测(233 人):每人每 tick 15 次,
  95.3% 的 flush 只带 1 个包,TCP/IP 头约占下行三分之一(原先以为每 tick 一次、包头只占 2%~3%,是错的)。
  已在 Canvas 本地补丁 LOCAL-PATCHES #25 把暂停扩到整个区域 tick;非 tick 线程(TAB 之类)发的包仍立即 flush。
  上线稳态(247 人):每人每 tick 约 1.2 次 flush,原版下行(含包头)75.0 → 52.0 Mbps;影子压缩在此基础上 22.5 Mbps,
  即装 mod 还能再省 57%,耗时约 0.7 个核心(9950X 的 4% 左右)。
- 攒帧边界直接用这个现成的 flush,不另加定时器、不加延迟。
- 选 JDK Deflater 而非 Zstd:两端零 native 依赖,每连接约 300 KB(Zstd 大窗口 NEB 默认每人 8 MB)。
- 玩家客户端版本以 26.1.2 为主(`viaversion list`),mod 先只做这一个版本。
