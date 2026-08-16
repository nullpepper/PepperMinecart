package com.pepperminecart.engine;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffOutcome;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.api.VanillaCartSupport;
import com.pepperminecart.config.ContainerPickupPolicy;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.container.CartSessionManager;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 受管矿车引擎：维护受管矿车集合（放置/区块加载加入，销毁/卸载/取下移除），
 * 每 tick 调度 onTick、路由移动/乘坐/下车/销毁事件，执行取下编排，处理破坏掉落与显示校验。
 */
public class CartEngine implements Listener {

    private final Plugin plugin;
    private final CartTypeRegistry registry;
    private final PluginConfig config;
    private final Map<UUID, CartContextImpl> contexts = new HashMap<>();
    /** 会话管理器（构造顺序：engine → tracker → sessions 后经 setter 注入） */
    private CartSessionManager sessionManager;
    private BukkitTask tickTask;

    public CartEngine(Plugin plugin, CartTypeRegistry registry, PluginConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    public void setSessionManager(CartSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void start() {
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        contexts.clear();
    }

    /** 插件启用时扫描已加载区块中的存量受管矿车（ChunkLoadEvent 不会为已加载区块再次触发）。 */
    public void registerLoadedCarts() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                forEachMinecartInChunk(chunk, this::registerManagedCart);
            }
        }
    }

    // ---- 类型解析 ----

    /**
     * 解析矿车类型：PDC cart-type id 优先，显示方块 material 兜底（兼容第三方显示/老数据）。
     * 对“cart-type 存在但注册表已无对应类型”或“普通矿车显示特殊矿车材质”的情况，
     * 回退到安全通用处理，避免矿车变成无法取下/销毁时丢物品的“死车”。
     */
    public CartTypeHandler resolve(Minecart cart) {
        String id = CartData.getCartType(cart);
        if (id != null) {
            try {
                CartTypeHandler handler = registry.byId(NamespacedKey.fromString(id));
                if (handler != null) {
                    if (handler instanceof VanillaCartSupport && cart.getType() == EntityType.MINECART) {
                        // 普通矿车 + 特殊类型 PDC：转换中间状态（addCart 写入 PDC 后、onPlaced
                        // 完成实体转换前中断，如服务器崩溃/插件禁用）。此时不能路由到
                        // SpecialCartHandler——它按实体类型反查材质与库存，普通矿车取不到容器内容，
                        // 取下/销毁会重建白板物品导致存储物品（含容器内容）丢失。回退通用处理器，
                        // 按存储物品原样归还。
                        return genericFallback();
                    }
                    return handler;
                }
            } catch (IllegalArgumentException ex) {
                // PDC cart-type 被第三方写入非法字符串时记录日志，便于排查“矿车不按预期路由”
                plugin.getLogger().warning("[PepperMinecart] 矿车 PDC cart-type 含非法 NamespacedKey，已走回退解析: "
                        + id + " (" + cart.getUniqueId() + ")");
            }
            // 类型已失效：若实体本身是原版特殊矿车（箱子/漏斗等），仍交给支持它的处理器安全接管；
            // 否则回退通用处理器，至少能按存储物品/原始材质/显示材质取下或掉落，避免数据丢失。
            CartTypeHandler vanilla = resolveVanillaPickup(cart);
            return vanilla != null ? vanilla : genericFallback();
        }
        Material m = new CartBlockDisplay(cart).material();
        if (m != null && m != Material.AIR) {
            CartTypeHandler handler = registry.byMaterial(m);
            if (handler instanceof VanillaCartSupport && cart.getType() == EntityType.MINECART) {
                // 普通矿车显示 CHEST/HOPPER/FURNACE 等特殊矿车材质时，不能路由到 SpecialCartHandler：
                // 它按实体类型反查材质，普通矿车会返回 null，导致取下/销毁丢物品。
                return genericFallback();
            }
            return handler != null ? handler : genericFallback();
        }
        // 显示缺失但仍有存储物品（老数据/第三方清除了显示）：按存储物品类型安全解析
        ItemStack stored = CartData.getItem(cart);
        if (stored != null) {
            CartTypeHandler handler = registry.byMaterial(stored.getType());
            if (handler instanceof VanillaCartSupport && cart.getType() == EntityType.MINECART) {
                return genericFallback();
            }
            return handler != null ? handler : genericFallback();
        }
        return null;
    }

    /** 未知/异常类型的安全兜底：按存储物品、原始材质或显示方块执行通用取下/掉落。 */
    private CartTypeHandler genericFallback() {
        return GenericBlockHandler.INSTANCE;
    }

    public boolean isManaged(Minecart cart) {
        return contexts.containsKey(cart.getUniqueId()) || CartData.isManaged(cart);
    }

    // ---- 原版特殊矿车（外来无插件数据的箱子矿车等） ----

    /** 是否为原版特殊矿车（箱子/漏斗/熔炉/TNT/命令矿车）——按注册表中声明 VanillaCartSupport 的类型识别。 */
    public boolean isVanillaCart(Minecart cart) {
        return vanillaSupportHandler(cart) != null;
    }

    /** 按实体类型反查支持原版特殊矿车的处理器（仅用于取下外来原版矿车）。 */
    public CartTypeHandler resolveVanillaPickup(Minecart cart) {
        return vanillaSupportHandler(cart);
    }

    /** 查找声明支持该矿车实体类型的处理器；无则 null。 */
    private CartTypeHandler vanillaSupportHandler(Minecart cart) {
        for (CartTypeHandler handler : registry.all()) {
            if (handler instanceof VanillaCartSupport support && support.supportsVanillaCart(cart)) {
                return handler;
            }
        }
        return null;
    }

    // ---- 上下文管理 ----

    /** 放置流程：写入类型 id、整物品与原始材质，登记上下文。 */
    public CartContextImpl addCart(Minecart cart, CartTypeHandler type, ItemStack placedItem) {
        CartData.setCartType(cart, type.getId().toString());
        CartData.setItem(cart, placedItem);
        CartData.setOriginalMaterial(cart, placedItem.getType());
        CartContextImpl ctx = new CartContextImpl(this, config, cart, type);
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world != null && loc != null) {
            ctx.updateSnapshot(world, loc);
        }
        contexts.put(cart.getUniqueId(), ctx);
        return ctx;
    }

    /** 获取受管上下文；非受管矿车返回 null。注册表类型变化时自动重建 context。
     *  只有已写入插件 PDC 的矿车才会被纳入受管集合；仅有显示方块/第三方数据的矿车
     *  不会被意外收养（临时取下流程请使用 {@link #contextFor}）。 */
    public CartContextImpl contextOrNull(Minecart cart) {
        CartContextImpl ctx = contexts.get(cart.getUniqueId());
        if (ctx == null && !CartData.isManaged(cart)) {
            return null;
        }
        return contextForInternal(cart, resolve(cart));
    }

    /** 用已知类型获取/登记上下文（不写 PDC；用于外来原版矿车的取下流程）。 */
    public CartContextImpl contextFor(Minecart cart, CartTypeHandler type) {
        return contextForInternal(cart, type);
    }

    /** 统一上下文获取/刷新入口：已有 context 且类型变化时重建，避免交互路径继续使用旧 handler。 */
    private CartContextImpl contextForInternal(Minecart cart, CartTypeHandler type) {
        CartContextImpl ctx = contexts.get(cart.getUniqueId());
        if (type == null) {
            // 当前无法解析时：已有 context 保留（避免正在使用的旧类型被临时注销打断），无 context 则返回 null
            return ctx;
        }
        if (ctx == null) {
            ctx = new CartContextImpl(this, config, cart, type);
            World world = cart.getWorld();
            Location loc = cart.getLocation();
            if (world != null && loc != null) {
                ctx.updateSnapshot(world, loc);
            }
            contexts.put(cart.getUniqueId(), ctx);
        } else if (ctx.getType() != type && type != GenericBlockHandler.INSTANCE) {
            // 同一矿车的类型被覆盖/重注册后，旧 context 仍持有旧 handler；这里重建以使用最新类型。
            // 但若解析结果只是“未知/失效类型”的通用兜底，则保留已有 context，
            // 避免正在使用的旧类型被临时注销时被打断。
            // 用对象身份比较：同 id 但不同实例（重注册替换 handler）也应刷新为新实例（EngineStateFixTest）。
            CartContextImpl rebuilt = new CartContextImpl(this, config, cart, type);
            // 迁移旧 context 的运行时状态：掉落位置快照与销毁掉落开关不能随重建静默重置
            rebuilt.updateSnapshot(ctx.lastWorld(), ctx.lastLocation());
            rebuilt.setDropOnDestroy(ctx.dropOnDestroy());
            World world = cart.getWorld();
            Location loc = cart.getLocation();
            if (world != null && loc != null) {
                rebuilt.updateSnapshot(world, loc);
            }
            contexts.put(cart.getUniqueId(), rebuilt);
            ctx = rebuilt;
        }
        return ctx;
    }

    /** 获取受管上下文；非受管矿车抛出异常。 */
    public CartContextImpl context(Minecart cart) {
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            throw new IllegalStateException("矿车不受管理: " + cart.getUniqueId());
        }
        return ctx;
    }

    public void removeCart(Minecart cart) {
        removeCart(cart.getUniqueId());
    }

    public void removeCart(UUID id) {
        contexts.remove(id);
    }

    /** 实体替换后重新绑定跟踪（特殊矿车转换）。 */
    public void rebind(UUID oldId, Minecart newCart) {
        CartContextImpl ctx = contexts.remove(oldId);
        if (ctx != null) {
            contexts.put(newCart.getUniqueId(), ctx);
        }
    }

    // ---- 会话联动 ----

    /** 回写并关闭该矿车仍打开中的容器界面（取下/销毁前调用，防编辑丢失与幽灵界面）。 */
    public void flushCartSessions(Minecart cart) {
        if (sessionManager != null) {
            sessionManager.flushAndClose(cart);
        }
    }

    /** 延迟 1 tick 关闭该矿车全部界面（用于铁砧报废等不能在事件链中同步 close 的场景）。 */
    public void closeCartSessionsLater(Minecart cart) {
        plugin.getServer().getScheduler().runTask(plugin, () -> flushCartSessions(cart));
    }

    /** 统一异常屏障：单个 handler 回调异常只记录并跳过，不污染事件链路。 */
    private void safeHandler(String action, CartTypeHandler handler, Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + handler.getId() + " " + action + " 异常，已跳过该矿车", ex);
        }
    }

    /** 交互回调的异常安全入口（供 InteractionListener 使用）。 */
    public boolean safeInteract(Player player, CartContextImpl ctx) {
        try {
            return ctx.getType().onInteract(player, ctx);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onInteract 异常，已按未处理交互跳过", ex);
            return false;
        }
    }

    // ---- 取下编排（交互监听器完成前置判定后委托） ----

    /**
     * 执行取下流程：
     * <ol>
     *   <li>回写并关闭该矿车打开中的容器界面（保证取下物品包含最新编辑）；</li>
     *   <li>调用类型 {@code onTakeOff}——返回 {@link TakeOffOutcome#HANDLED} 时处理器已完整接管，直接返回；</li>
     *   <li>特殊矿车还原为普通矿车（先生成替代实体再移除旧实体，失败回滚）；
     *       普通矿车清除显示与业务数据并解除管理；</li>
     *   <li>按取下策略分发物品：容器类 + DROP 策略先清空内容物落地，其余交给
     *       {@code giveItem}（背包优先、溢出落地）。</li>
     * </ol>
     */
    public void takeOff(Player player, CartContextImpl ctx, TakeOffResult mode) {
        Minecart cart = ctx.getMinecart();
        if (mode == TakeOffResult.DISABLED) {
            // 禁止取下：不还原矿车、不交付物品。正常路径由 InteractionListener 前置拦截，
            // 这里防御 API 直接调用（配置为 DISABLED 或容器策略 FORBIDDEN 时不应执行取下流程）。
            // 若调用方刚用 contextFor 登记了临时 context（非 PDC 常驻矿车），取下未发生时应移除，
            // 防止残留受管集合（禁止乘坐/每 tick 回调/销毁误判）；常驻受管矿车（有 PDC）保留跟踪。
            if (!CartData.isManaged(cart)) {
                removeCart(cart.getUniqueId());
            }
            return;
        }
        // 提前记录位置快照：即使实体在流程中失效，后续 giveItem/dropItem 仍有可用的掉落位置
        World snapshotWorld = cart.getWorld();
        Location snapshotLoc = cart.getLocation();
        if (snapshotWorld != null && snapshotLoc != null) {
            ctx.updateSnapshot(snapshotWorld, snapshotLoc);
        }
        boolean handled = false;
        try {
            try {
                flushCartSessions(cart);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 取下前回写/关闭矿车界面异常，本次取下已取消: " + cart.getUniqueId(), ex);
                return;
            }

            TakeOffOutcome outcome;
            try {
                outcome = ctx.getType().onTakeOff(player, ctx, mode);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onTakeOff 异常，本次取下已取消: "
                                + cart.getUniqueId(), ex);
                return;
            }
            if (outcome == TakeOffOutcome.HANDLED) {
                handled = true;
                return;
            }

            // 提前捕获取下物品（随后清 PDC/移除实体后将无法读取原始数据）
            ItemStack item;
            try {
                item = ctx.getType().getTakeOffItem(ctx);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " getTakeOffItem 异常，本次取下已取消: "
                                + cart.getUniqueId(), ex);
                return;
            }
            if (item == null || item.getType().isAir()) {
                // 拿不到可返还物品时不要清空矿车数据，避免矿车被还原但玩家一无所得；
                // 同时必须移除本次流程登记的 context，防止外来原版矿车残留受管集合
                plugin.getLogger().warning("[PepperMinecart] 取下矿车时未能取得可返还物品，已取消取下: "
                        + cart.getUniqueId());
                return;
            }

            Minecart current = ctx.getMinecart();
            Location loc = current.isValid()
                    ? current.getLocation()
                    : (cart.getLocation() != null ? cart.getLocation() : ctx.lastLocation());
            if (current.isValid()) {
                if (current.getType() != EntityType.MINECART) {
                    // 特殊矿车 → 还原为普通矿车
                    RideableMinecart normal;
                    try {
                        // 先成功生成替代矿车再移除原矿车：避免生成被其它插件取消时旧矿车已删、内容全损
                        normal = MinecartSwap.spawnReplacement(MinecartSwap.capture(current), RideableMinecart.class);
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.WARNING, "[PepperMinecart] 还原普通矿车失败，本次取下已取消", ex);
                        return; // 原矿车保持原样（内容未动、物品未交付）
                    }
                    // 迁移自定义名，避免命名过的特殊矿车取下后名字丢失
                    if (current.customName() != null) {
                        normal.customName(current.customName());
                        normal.setCustomNameVisible(current.isCustomNameVisible());
                    }
                    // 再解除管理并清数据、移除实体，防止 EntityRemoveEvent 把旧矿车误判为销毁而重复掉落
                    removeCart(current);
                    CartData.clear(current, ctx.getType().getId().getNamespace());
                    // 显式处置原版库存内容物后再移除实体——不依赖 Paper 内部 dropContents：
                    // 若返还物品已内嵌容器内容（BlockStateMeta 的 inventory），则清空原版库存是安全的
                    // （内容已转移到返还物品）；否则（返还物品未内嵌内容：DROP 策略已倾倒或异常路径）
                    // 必须先把原版库存逐格掉落，绝不能静默丢失。清空后再 remove()，即便 Paper 随后
                    // 触发原版 dropContents 也读到空库存，不会二次掉落。
                    drainOrClearVanillaInventory(item, current, loc);
                    current.remove();
                    // 上下文重定向到新普通矿车：后续 giveItem/dropItem 落在有效实体上（旧实体已移除）
                    ctx.repoint(normal);
                } else {
                    new CartBlockDisplay(current).clear();
                    CartData.clear(current, ctx.getType().getId().getNamespace());
                    removeCart(current);
                }
            }

            // 容器类 + DROP 策略：内容物全部掉落地面，容器物品清空后按 take-off-mode 处理
            try {
                if (ctx.getType().isContainerPickupControlled(item.getType())
                        && config.containerPickupPolicy() == ContainerPickupPolicy.DROP) {
                    spillContainerContents(item, loc);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " isContainerPickupControlled 异常，已跳过内容物倾倒: "
                                + cart.getUniqueId(), ex);
            }
            // 交给 giveItem 按本次取下策略统一分发（背包优先、溢出落地；DROP 直接落地）
            ctx.giveItem(player, item, mode);

            if (config.soundTakeOff() && loc != null && loc.getWorld() != null) {
                loc.getWorld().playSound(loc, Sound.BLOCK_STONE_BREAK, 1f, 1f);
            }
        } finally {
            // 非 HANDLED 路径无论正常完成/提前取消/实体失效，都确保不泄漏受管 context
            if (!handled) {
                removeCart(cart.getUniqueId());
            }
        }
    }

    /** 清空容器物品的内容物并全部掉落到矿车位置地面。 */
    private static void spillContainerContents(ItemStack item, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return; // 位置/世界不可用，无法掉落内容物；至少不 NPE
        }
        if (!(item.getItemMeta() instanceof BlockStateMeta bsm)) {
            return;
        }
        if (!(bsm.getBlockState() instanceof org.bukkit.block.Container container)) {
            return;
        }
        for (ItemStack content : container.getInventory().getContents()) {
            if (content != null && !content.getType().isAir()) {
                loc.getWorld().dropItemNaturally(loc, content);
            }
        }
        container.getInventory().clear();
        bsm.setBlockState(container);
        item.setItemMeta(bsm);
    }

    /**
     * 移除原版特殊矿车前显式处置其原版库存内容物（不依赖 Paper 内部 dropContents）：
     * <ul>
     *   <li>若返还物品已内嵌容器内容（BlockStateMeta 的 inventory 非空），说明内容已安全转移到
     *       返还物品，直接清空原版库存（随后 remove() 即便触发原版 dropContents 也读到空库存，不重复掉落）；</li>
     *   <li>否则（返还物品未内嵌内容：DROP 策略已倾倒、异常路径等），必须先把原版库存逐格掉落到位置，
     *       再清空，绝不能静默丢失。位置不可用时保留库存不清空，交由调用方/后续处理兜底。</li>
     * </ul>
     */
    private static void drainOrClearVanillaInventory(ItemStack returnedItem, Minecart cart, Location loc) {
        if (!(cart instanceof InventoryHolder holder)) {
            return; // 非容器矿车（熔炉/TNT/命令矿车）无原版库存
        }
        org.bukkit.inventory.Inventory inv = holder.getInventory();
        if (containsEmbeddedContents(returnedItem)) {
            inv.clear();
            return;
        }
        // 未内嵌内容：逐格掉落再清空
        if (loc != null && loc.getWorld() != null) {
            for (ItemStack content : inv.getContents()) {
                if (content != null && !content.getType().isAir()) {
                    loc.getWorld().dropItemNaturally(loc, content);
                }
            }
        }
        inv.clear();
    }

    /** 返还物品的 BlockStateMeta 容器库存中是否已内嵌内容（非空）。 */
    private static boolean containsEmbeddedContents(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta bsm)) {
            return false;
        }
        if (!(bsm.getBlockState() instanceof org.bukkit.block.Container container)) {
            return false;
        }
        for (ItemStack it : container.getInventory().getContents()) {
            if (it != null && !it.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    // ---- tick ----

    private void tick() {
        if (contexts.isEmpty()) {
            return;
        }
        // 快照遍历：onTick/失效清理可能触发 removeCart 修改 contexts，直接遍历会抛 CME；
        // 用 ArrayList 快照而非 Map.copyOf（每 tick 全表拷贝时不必要的不可变包装开销）
        for (CartContextImpl ctx : new ArrayList<>(contexts.values())) {
            Minecart cart = ctx.getMinecart();
            if (!cart.isValid() || cart.isDead()) {
                // 实体已失效（未触发 EntityRemoveEvent 的异常路径）：回写/关闭打开中的容器会话，
                // 避免幽灵界面与编辑残留；不在此处掉落物品，防止与实体持久化数据重复
                try {
                    flushCartSessions(cart);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 清理失效矿车会话时异常，已跳过该矿车: " + cart.getUniqueId(), ex);
                }
                removeCart(cart.getUniqueId());
                continue;
            }
            World world = cart.getWorld();
            Location loc = cart.getLocation();
            if (world != null && loc != null) {
                ctx.updateSnapshot(world, loc);
            }
            try {
                ctx.getType().onTick(ctx);
            } catch (RuntimeException ex) {
                // 单个矿车类型的 tick 异常只跳过该矿车：repeating task 抛未捕获异常会被
                // Bukkit 调度器整体取消，导致所有受管矿车的 tick/失效清理停摆直到重启
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onTick 异常，已跳过该矿车: "
                                + cart.getUniqueId(), ex);
            }
        }
    }

    // ---- 事件路由 ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent e) {
        if (!(e.getMount() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            return;
        }
        if (!ctx.getType().allowRiding()) {
            e.setCancelled(true);
            return;
        }
        safeHandler("onMount", ctx.getType(), () -> ctx.getType().onMount(e.getEntity(), ctx));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getDismounted() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            return;
        }
        safeHandler("onDismount", ctx.getType(), () -> ctx.getType().onDismount(e.getEntity(), ctx));
    }

    /** 矿车移动（事件驱动，替代位置差分轮询）：分发到 onMove（如投掷器铁轨检测）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            return;
        }
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world != null && loc != null) {
            ctx.updateSnapshot(world, loc);
        }
        try {
            ctx.getType().onMove(ctx);
        } catch (RuntimeException ex) {
            // 与 onTick 一致：单个矿车类型的移动回调异常只跳过该矿车，
            // 避免异常传播污染其他监听器
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车类型 " + ctx.getType().getId() + " onMove 异常，已跳过该矿车: "
                            + cart.getUniqueId(), ex);
        }
    }

    /**
     * 矿车从世界移除时统一清理（破坏、/kill、插件 remove() 等所有路径）。
     * 区块卸载（UNLOAD）不在此处理——实体仍会保存并重载，由 onChunkUnload 清理跟踪，
     * 会话由 {@link #flushCartSessions} 的调用方（取下流程）处理。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent e) {
        if (e.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            // 区块卸载：实体仍会保存并重载，但先回写并关闭该矿车仍打开中的容器界面
            // （卸载后实体脱离，无法再安全写回 PDC），并移除 context 防止残留过期引用
            if (e.getEntity() instanceof Minecart cart) {
                try {
                    flushCartSessions(cart);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 矿车卸载时回写/关闭会话异常: " + cart.getUniqueId(), ex);
                } finally {
                    removeCart(cart.getUniqueId());
                }
            }
            return;
        }
        if (!(e.getEntity() instanceof Minecart cart)) {
            return;
        }
        CartContextImpl ctx = contextOrNull(cart);
        if (ctx == null) {
            return;
        }
        // 提前缓存世界/位置：EntityRemoveEvent 触发时实体可能已不在世界中，
        // 后续 getWorld()/getLocation() 可能返回 null，避免 NPE 或静默丢失掉落
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        ctx.updateSnapshot(world, loc);
        // 先回写并关闭打开中的容器界面，保证掉落物品包含最新编辑
        try {
            flushCartSessions(cart);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[PepperMinecart] 矿车销毁时回写/关闭会话异常: " + cart.getUniqueId(), ex);
        }
        CartTypeHandler type = ctx.getType();
        // 原版特殊矿车（箱子/漏斗/熔炉/TNT/命令）的实体死亡路径（DEATH 且实体掉落开启）已由服务端
        // 按原版设计掉落“合并物品（Minecart with Chest 等）+ 内容物”（并清空原版库存），
        // 插件刻意跳过处理器补掉逻辑，避免与原版掉落重复；
        // 其余路径（第三方 remove()/DISCARD/DESPAWN/PLUGIN 等）不预清空原版库存——这里不依赖
        // Paper 内部 dropContents 是否触发，而是由 onCartDestroyed 的处理器（SpecialCartHandler
        // 逐格掉落 + clear + setDropOnDestroy(false)）确定性接管内容物处置，既防丢失也防二次掉落。
        boolean vanillaDeathDropped = e.getCause() == EntityRemoveEvent.Cause.DEATH
                && world != null && Boolean.TRUE.equals(world.getGameRuleValue(GameRule.DO_ENTITY_DROPS));
        if (vanillaDeathDropped && isVanillaCart(cart)) {
            ctx.setDropOnDestroy(false);
            removeCart(cart.getUniqueId());
            return;
        }
        try {
            try {
                type.onCartDestroyed(ctx);
            } catch (RuntimeException ex) {
                // 单个类型销毁回调异常只跳过该回调，仍走默认掉落与上下文清理，避免 ctx 残留
                plugin.getLogger().log(Level.WARNING,
                        "[PepperMinecart] 矿车类型 " + type.getId() + " onCartDestroyed 异常，已继续默认掉落逻辑: "
                                + cart.getUniqueId(), ex);
            }
            if (ctx.dropOnDestroy()) {
                ItemStack item = null;
                try {
                    item = type.getTakeOffItem(ctx);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 矿车类型 " + type.getId() + " getTakeOffItem 异常，已跳过掉落: "
                                    + cart.getUniqueId(), ex);
                }
                if (item != null && !item.getType().isAir()) {
                    try {
                        // 统一走 ctx.dropItem：实体 world/location 不可用时自动回退到最后已知快照，
                        // 避免 EntityRemoveEvent 阶段实体已失效导致默认掉落静默丢失。
                        ctx.dropItem(item);
                    } catch (RuntimeException ex) {
                        // 掉落异常（如世界不可用）只记录，不阻断上下文清理
                        plugin.getLogger().log(Level.WARNING,
                                "[PepperMinecart] 矿车类型 " + type.getId() + " 掉落物品异常，已跳过掉落: "
                                        + cart.getUniqueId(), ex);
                    }
                }
            }
        } finally {
            // 无论正常完成/回调异常/掉落异常，都确保不泄漏受管 context
            removeCart(cart.getUniqueId());
        }
    }

    /** 安全遍历区块内矿车：单个矿车处理异常只跳过该矿车，不中断整个区块事件。 */
    private void forEachMinecartInChunk(Chunk chunk, Consumer<Minecart> action) {
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Minecart cart) {
                try {
                    action.accept(cart);
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 处理区块矿车异常，已跳过该矿车: " + cart.getUniqueId(), ex);
                }
            }
        }
    }

    /** 登记单个受管矿车：解析 context，并保留 ChunkLoad 的显示一致性处理。 */
    private void registerManagedCart(Minecart cart) {
        if (!CartData.isManaged(cart)) {
            return;
        }
        contextOrNull(cart);
        // 显示校验：仅普通矿车（特殊矿车由实体自身渲染）；存储物品存在但显示缺失 → 恢复。
        // 仅对方块材质恢复显示：非方块 ITEM（数据损坏/API 写入异常数据）直接跳过，
        // 避免 setDisplayBlockData(createBlockData) 抛 IllegalArgumentException 打断区块遍历
        if (cart.getType() == EntityType.MINECART) {
            ItemStack item = CartData.getItem(cart);
            if (item != null && item.getType().isBlock() && !new CartBlockDisplay(cart).has()) {
                try {
                    new CartBlockDisplay(cart).set(item.getType());
                    // 显示恢复后重新解析并登记上下文（老数据可能因显示缺失未在首次解析时登记）
                    contextOrNull(cart);
                } catch (RuntimeException ex) {
                    // 显示恢复失败（如数据异常）：矿车仍按 PDC 保持受管（tick/销毁兜底仍生效），
                    // 仅记录并跳过显示恢复，不让异常扩散到整个区块遍历
                    plugin.getLogger().log(Level.WARNING,
                            "[PepperMinecart] 恢复矿车显示方块失败，已跳过: " + cart.getUniqueId(), ex);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent e) {
        forEachMinecartInChunk(e.getChunk(), this::registerManagedCart);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent e) {
        forEachMinecartInChunk(e.getChunk(), cart -> {
            // 回写并关闭该矿车仍打开中的容器界面（卸载后实体脱离，无法再安全写回）；
            // 移除上下文跟踪（投掷器冷却存 PDC，随实体保存，无需在此清理）
            try {
                flushCartSessions(cart);
            } finally {
                removeCart(cart.getUniqueId());
            }
        });
    }
}
