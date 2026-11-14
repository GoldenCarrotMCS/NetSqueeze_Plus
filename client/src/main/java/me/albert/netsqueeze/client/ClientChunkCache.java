package me.albert.netsqueeze.client;

import me.albert.netsqueeze.client.cache.CachePreferences;
import me.albert.netsqueeze.client.cache.SnapshotDiskStore;
import me.albert.netsqueeze.protocol.ChunkCacheProtocol;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Disk and consent operations are serialized off the game/network threads, with a bounded queue. */
public final class ClientChunkCache {
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), runnable -> { Thread t = new Thread(runnable, "NetSqueeze-cache-io"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());
    private static final Path ROOT = FabricLoader.getInstance().getGameDir().resolve("netsqueeze-cache");
    private static final Path PREFS = FabricLoader.getInstance().getConfigDir().resolve("netsqueeze-cache.properties");
    private static CachePreferences preferences;
    private static final Set<String> REVOKED = ConcurrentHashMap.newKeySet();
    private static final Set<Lease> LEASES = ConcurrentHashMap.newKeySet();
    private static volatile String currentIdentity;
    private static volatile String currentScope;
    private static volatile long policyGeneration;
    private ClientChunkCache() { }
    public record Prepared(String identity, String scope, long limit, List<byte[]> seeds, long policyGeneration) { }
    public static final class Lease implements AutoCloseable {
        final String identity, scope;
        final long limit;
        volatile boolean persist = true;
        Lease(Prepared prepared) { identity = prepared.identity; scope = prepared.scope; limit = prepared.limit; persist = prepared.policyGeneration == policyGeneration && !REVOKED.contains(identity); LEASES.add(this); }
        public void save(byte[] snapshot) {
            if (!persist || REVOKED.contains(identity) || snapshot.length > ChunkCacheProtocol.MAX_PACKET) return;
            submit(() -> {
                if (!persist || REVOKED.contains(identity) || !prefs().allowed(identity)) return;
                new SnapshotDiskStore(ROOT, scope, limit, true).put(snapshot);
            });
        }
        @Override public void close() { persist = false; LEASES.remove(this); }
    }
    public static boolean valid(Prepared prepared) { return prepared.policyGeneration == policyGeneration && !REVOKED.contains(prepared.identity); }
    public static Lease activate(Prepared prepared) { return new Lease(prepared); }
    private static CachePreferences prefs() throws java.io.IOException {
        if (preferences == null) preferences = new CachePreferences(PREFS);
        return preferences;
    }
    public static String identity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() == null) return null;
        return mc.getCurrentServer().ip.toLowerCase(Locale.ROOT) + "/" + mc.getUser().getProfileId();
    }
    /** Called on the Minecraft thread; callback completes off-thread and must re-enter the event loop. */
    public static void prepare(ChunkCacheProtocol.Offer offer, java.util.function.Consumer<Prepared> callback) {
        String identity = identity();
        currentIdentity = identity;
        if (identity == null) { callback.accept(null); return; }
        String mods = FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId() + "=" + m.getMetadata().getVersion().getFriendlyString())
            .sorted().reduce("", (a, b) -> a + ";" + b);
        String dimension = Minecraft.getInstance().level == null ? "unbound" : Minecraft.getInstance().level.dimension().toString();
        String scope = identity + "/" + dimension + "/" + offer.namespace() + "/" + offer.epoch() + "/" + offer.protocol() + "/" + mods;
        currentScope = scope;
        long preparedGeneration = policyGeneration;
        if (!submit(() -> {
            Prepared result = null;
            try {
                if (!REVOKED.contains(identity) && prefs().allowed(identity)) {
                    SnapshotDiskStore disk = new SnapshotDiskStore(ROOT, scope, prefs().limit(), true);
                    SnapshotDiskStore.trim(ROOT, prefs().limit());
                    List<byte[]> seeds = new ArrayList<>(); int total = 0;
                    for (String key : disk.keys(ChunkCacheProtocol.MAX_SEEDS)) {
                        byte[] bytes = disk.get(key);
                        if (bytes != null && total + bytes.length <= ChunkCacheProtocol.SEED_BYTES) { seeds.add(bytes); total += bytes.length; }
                    }
                    if (preparedGeneration == policyGeneration && !REVOKED.contains(identity))
                        result = new Prepared(identity, scope, prefs().limit(), seeds, preparedGeneration);
                } else message("服务器支持可选区块缓存；默认不保存。输入 /nsqcache allow 授权，/nsqcache 查看说明。授权后重新连接生效。");
            } finally { callback.accept(result); }
        })) callback.accept(null);
    }
    /** Intercepted locally: this text is NEVER sent as a server command. */
    public static boolean command(String command) {
        if (!(command.equals("nsqcache") || command.startsWith("nsqcache "))) return false;
        String[] args = command.trim().split("\\s+");
        String action = args.length > 1 ? args[1] : "help";
        String identity = identity(), scope = currentScope;
        if (action.equals("deny") || action.equals("limit")) policyGeneration++;
        if (action.equals("deny") && identity != null) {
            REVOKED.add(identity);
            for (Lease lease : LEASES) if (lease.identity.equals(identity)) lease.persist = false;
        }
        if (action.equals("limit")) for (Lease lease : LEASES) lease.persist = false;
        if (!submit(() -> {
            switch (action) {
                case "allow" -> {
                    if (identity == null) { message("请先连接一个服务器。"); break; }
                    prefs().allow(identity, true); REVOKED.remove(identity);
                    message("已同意为当前服务器地址及玩家保存区块数据。缓存包含访问过的地形；重新连接启用。不会预先下载未知区块。");
                }
                case "deny" -> {
                    if (identity == null) { message("请先连接一个服务器。"); break; }
                    prefs().allow(identity, false);
                    message("已撤回授权并停止后续磁盘写入。当前会话已固定的内存数据保留到断线以保证顺序；可 /nsqcache clear-all 删除所有磁盘快照。");
                }
                case "clear" -> {
                    if (scope == null || !Objects.equals(identity, currentIdentity)) { message("当前连接尚未收到缓存邀请；可用 clear-all。"); break; }
                    message("已清理当前服务器缓存范围：" + new SnapshotDiskStore(ROOT, scope, prefs().limit(), false).clear() + " 个文件。当前会话可能再次写入；先 deny 可停止写入。");
                }
                case "clear-all" -> message("已清理全部区块快照：" + SnapshotDiskStore.clearAll(ROOT) + " 个文件；授权设置保持不变。");
                case "limit" -> {
                    if (args.length != 3) { message("用法：/nsqcache limit <16..1024>，单位 MiB，全局容量。"); break; }
                    long limit = Math.multiplyExact(Long.parseLong(args[2]), 1024L * 1024);
                    prefs().limit(limit); SnapshotDiskStore.trim(ROOT, limit);
                    for (Lease lease : LEASES) lease.persist = false;
                    message("全局磁盘上限已修改；为避免旧会话按旧容量写入，请重新连接。");
                }
                case "status" -> message("当前授权：" + (identity != null && !REVOKED.contains(identity) && prefs().allowed(identity)) +
                    "；磁盘 " + SnapshotDiskStore.size(ROOT) / 1048576 + "/" + prefs().limit() / 1048576 + " MiB；启用状态请看协商日志。统计不等于实际出口节省。");
                default -> message("本地命令：/nsqcache allow | deny | status | clear | clear-all | limit <MiB>。默认关闭；授权允许保存地形数据，不是身份认证；deny 不自动删除，clear-all 可删除快照。");
            }
        })) message("缓存后台队列已满，请稍后重试；未执行本次命令。");
        return true;
    }
    private interface Task { void run() throws Exception; }
    private static boolean submit(Task task) {
        try {
            IO.execute(() -> { try { task.run(); } catch (Exception e) { message("缓存操作失败，保留正常传输：" + e.getClass().getSimpleName()); NetSqueezeClient.LOGGER.debug("Cache I/O", e); } });
            return true;
        } catch (RejectedExecutionException e) { return false; }
    }
    public static void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[NetSqueeze] " + text));
            else NetSqueezeClient.LOGGER.info("[cache] {}", text);
        });
    }
}
