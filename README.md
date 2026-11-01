# NetSqueeze

给 Minecraft 服务器省下行带宽：装了配套客户端模组的玩家，服务器发给他的数据改用**按帧攒包 + 跨包流式压缩**；
没装的照走原版，什么都不变。

- **服务端**：Paper / Folia 系插件（Kotlin），只装插件，不用改服务端核心。
- **客户端**：Fabric 模组（Java），只依赖 Fabric Loader，不需要 Fabric API。
- 两边任意一边没装都会自动退回原版，不影响玩家进别的服务器。

思路来自 NeoForge 模组 [NotEnoughBandwidth](https://github.com/USS-Shenzhou/NotEnoughBandwidth)
（它只有 NeoForge 版、两端必装）。NetSqueeze 是独立实现，没有用它的代码。

## 为什么能省

原版的网络压缩是**一个包一个包单独压**的，小于压缩阈值（默认 256 字节）的包干脆原样发送。可游戏里占大头的
恰恰是实体移动、声音、方块变化这类几十字节的小包，每个包还各自带一份帧头，经常各占一个 TCP 段。

NetSqueeze 换成：

1. **按 flush 攒帧**：服务器一次 flush 发出的所有包拼成一帧，不额外加定时器，也不引入延迟。
2. **跨包流式压缩**：整条连接共用一个 deflate 流，每帧 `SYNC_FLUSH` 收尾，前面发过的内容后面直接引用，
   重复越多省得越多。

## 实测效果

某 Folia 系服务器（Canvas 26.1.2），约 250 人在线、玩家多为 26.1.2 客户端，稳态数据：

| | 下行（含 TCP/IP 包头估算） |
|---|---|
| 原版 | 52.0 Mbps |
| 全员换 NetSqueeze 格式（同期影子估算） | 22.5 Mbps，**少 57%** |

- 影子估算的做法：没装模组的玩家也按新格式在服务端压一遍，只统计大小、不发出去。现在这套代码已经删掉，git 历史里还能找到。
- 单个装了模组的玩家刚进服大量加载区块时，实际出网只有原始包大小的 12% 左右。
- 服务端开销：deflate level 6，约 250 人时总共占 0.7 个 CPU 核心左右，分摊在 netty 线程上，不占 tick 线程。
  每条连接常驻约 300 KB 压缩状态。

> **flush 次数决定能攒多少。** 这台服务器的 Folia 系核心原本只在区域 tick 的后半段暂停 flush，前半段（处理玩家发来的包、
> 区域任务、实体调度器）里发的包一个包 flush 一次，实测 95% 的 flush 只带 1 个包。服务端在本地补丁里把暂停范围扩到了
> 整个区域 tick 之后，每人每 tick 只剩 1 次左右 flush，连原版的包头开销都从 24 Mbps 降到了 3 Mbps。
> NetSqueeze 在没改过核心的服务器上照样能用，只是每帧攒到的包少，省的比例会低一些。

## 要求

**服务端**

- Minecraft **26.1.x** 的 Paper / Folia 系服务端（插件直接用到了服务端内部类，绑定这个大版本）。
  开发和线上都跑在 Canvas 26.1.2 上；纯 Paper、Purpur 没有专门测过。
- Java 25。
- 前置插件 [CoreLib](https://github.com/eternity4719/CoreLib)：同一个仓库构建出 `CoreLib` 和 `CoreLibRuntime`
  两个插件，后者提供 Kotlin 运行库，两个都要装。
- 和 ViaVersion、PacketEvents、Grim、ProtocolLib、TAB 同时装过：压缩器挂在它们的编码器之后，它们照常看到每一个包。

**客户端**

- Minecraft **26.1 ~ 26.3**（一个 jar 通用；26.1、26.1.1、26.1.2、26.2、26.3 逐个核对过用到的类和方法）。
- Fabric Loader 0.19 以上，**不需要 Fabric API**。
- 正版服务器的加密已经处理，但作者的服务器是离线模式，正版场景没有实测过。

## 安装

1. 服务端：`plugins/` 里放 CoreLib、CoreLibRuntime 和 `NetSqueeze-x.x.x-all.jar`，重启。
2. 客户端：`NetSqueeze-Client-x.x.x.jar` 放进 `mods/`。
3. 玩家照常进服就行。客户端日志 `logs/latest.log` 里出现 `服务器已启用流式压缩` 就说明已经生效。

## 命令

权限 `netsqueeze.admin`（默认 OP）。

| 命令 | 作用 |
|---|---|
| `/nsq` | 汇总：装和没装模组两类玩家各自的出网、TCP/IP 包头估算、人均带宽，以及压缩耗时 |
| `/nsq players` | 按实际出网给玩家排行，装了模组的标 `[mod]` |
| `/nsq reset` | 统计清零（不影响压缩状态） |
| `/nsq pipeline` | 两类玩家最近一次进服时的 netty 管线，用来排查和其他网络插件的顺序 |

## 给其他插件：判断玩家装没装模组

装了模组的在线玩家身上有 metadata `netsqueeze_mod`（值为 `true`），进服事件的 LOWEST 阶段就已经打上，
其他插件在自己的进服监听里就能读到：

```java
boolean modded = player.hasMetadata("netsqueeze_mod");
```

伪造握手、却不会解压的客户端，会在服务器切到新格式后立刻掉线，所以带着这个标记还能正常在线的，就是真装了模组，
可以放心拿来发奖励。

## 注意

- **热重载**（PlugManX 等）：已经切到新格式的连接没法交接给新的插件实例，卸载时会给这些玩家发一条
  「服务器网络组件更新，请重新连接」后断开，没装模组的玩家无感。建议放在定时重启里更新插件。
- 只压缩服务器发往客户端的方向，客户端发给服务器的照走原版。
- 压缩出错没法退回原版（客户端已经切换），出错的连接会被断开，第一条堆栈打在后台，`/nsq` 里能看到出错次数。

## 协议

想在别的平台（Velocity、NeoForge 等）实现兼容端，按下面的约定做就行。两端协议有变化时，版本号一起改。

1. 客户端登录完成、出站协议切到配置阶段后，发送自定义包 `netsqueeze:hello_v1`，**不带数据**
   （版本号编在频道名里，原版客户端的 `DiscardedPayload` 不带数据）。
2. 服务端认得这个版本，就在这条连接的 netty 线程上：先按原版格式发 `netsqueeze:enable`（不带数据），
   紧接着换掉出站格式。`enable` 是这条连接最后一个原版格式的包，之后服务端发出的字节全是新格式。
   服务端不认得就什么都不回，客户端在配置阶段结束时撤掉自己，照走原版。
3. 新格式的帧：`varint 帧长` + `deflate 输出`。整条连接共用一个 raw deflate 流（nowrap，不带 zlib 头），
   每帧以 `SYNC_FLUSH` 结尾，保证每帧解压出来都是完整的包。解压后是一串 `varint 包长` + `包字节`
   （包 id + 包体，不再有原版的压缩长度前缀）。
4. 客户端必须在 netty 线程上、解到 `enable` 的那一刻就切换：同一批收到的字节里，紧跟在后面的已经是新格式了。

## 构建

```
# 先把 CoreLib 发布到本地 maven(顺带构建出 CoreLib 和 CoreLibRuntime 两个插件 jar)
git clone https://github.com/eternity4719/CoreLib
cd CoreLib && ./gradlew build publishToMavenLocal

# 再构建本仓库：插件在 build/libs/*-all.jar，客户端模组在 client/build/libs/
./gradlew build
```

需要 JDK 25。`client/` 是根构建的子项目，Fabric Loom 在根项目上统一声明。

## 许可

[MIT](LICENSE)
