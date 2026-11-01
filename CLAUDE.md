# NetSqueeze

Folia/Canvas 服务器插件(Kotlin 2.3 / JVM 25,paperweight `foliaDevBundle`,shadow 打包),依赖 CoreLib。
**目标:装了配套客户端 mod 的玩家走"按 flush 攒帧 + 跨包流式压缩"的下行格式,没装的走原版。** 思路来自 NeoForge mod
NotEnoughBandwidth(只有 NeoForge、两端必装、作者明确不做 Bukkit 版,所以自己做)。

## 常用命令

```
.\gradlew.bat compileKotlin -q --console=plain   # 编译校验(改完必跑)
.\gradlew.bat shadowJar                          # 打包
```

约定:编译通过后直接 `git commit`(中文提交信息),不 push。dev bundle 钉死在本机已缓存的 `26.1.2.build.8-stable`,
别改回 `+`(解析到新 build 会触发整份反编译)。

## 现状:影子模式(只统计,不改任何发出去的字节)

每个玩家的 netty 管线挂两个出站处理器(`shadow/Injector`):

- `netsqueeze_shadow`(compress 尾侧,出站先经过它):把 Via/PacketEvents 处理完的单个包喂进本连接独享的
  `Deflater`(level 6、nowrap),每次 flush 做 SYNC_FLUSH,得出"这一 tick 的包打成一帧"的压缩后大小。
- `netsqueeze_wire`(prepender 头侧、encrypt 之前):数原版实际写进 socket 的字节。

`/nsq` 汇总、`/nsq players` 按人、`/nsq reset` 清零(不重置压缩上下文)、`/nsq pipeline` 看处理器位置。

背景事实(决定方案的,别重查):
- Canvas 已经在每个区域 tick 暂停本区域玩家的 flush、tick 末统一 flush 一次
  (`MinecraftServer` 的 "optimize flush calls with region threading"),所以服务端单方面攒包只能省 TCP/IP 头,
  估算 2%~3%,不值得;要省就得换压缩格式,必须客户端配合。
- 攒帧边界直接用这个现成的 flush,不另加定时器、不加延迟。
- 选 JDK Deflater 而非 Zstd:两端零 native 依赖,每连接约 300 KB(Zstd 大窗口 NEB 默认每人 8 MB)。

支持 PlugManX 热重载:`Injector.shutdown` 同步等各 netty 线程把处理器摘完才返回(旧类加载器关闭后旧处理器
再处理包会 NoClassDefFoundError 踢人),重载后统计归零。以后加了真实切换协议,热重载就得把已切换的连接
换回原版格式(或直接踢下线重连),到时再定。
