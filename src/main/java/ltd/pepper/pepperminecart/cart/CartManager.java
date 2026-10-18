package ltd.pepper.pepperminecart.cart;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.api.PepperMinecartAPI;
import ltd.pepper.pepperminecart.config.PluginConfig;
import ltd.pepper.pepperminecart.config.TakeOffMode;
import ltd.pepper.pepperminecart.cooldown.InteractionCooldown;
import ltd.pepper.pepperminecart.protection.ProtectionGateway;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * 载着方块的矿车的完整生命周期：放上、使用、取下、摧毁，以及每 tick 调度。
 *
 * <p>Bukkit 事件由 {@link CartListener} 转交过来；具体行为交给各 {@link CartType}。
 * 本类同时实现对外服务 {@link PepperMinecartAPI}。</p>
 */
public final class CartManager implements PepperMinecartAPI {

    private final Plugin plugin;
    private final PluginConfig config;
    private final CartRegistry registry;
    private final CartMenus menus;
    private final ProtectionGateway protection;
    private final InteractionCooldown cooldown;
    /** 没有专门类型的方块，以及类型已不存在（插件被卸载）的矿车，都按它处理。 */
    private final CartType defaultType;

    /** 已加载的受管矿车，只用来分发 {@link CartType#onTick}。 */
    private final Map<UUID, Minecart> loadedCarts = new HashMap<>();
    private @Nullable BukkitTask tickTask;

    public CartManager(Plugin plugin, PluginConfig config, CartRegistry registry, CartMenus menus,
                       ProtectionGateway protection, InteractionCooldown cooldown, CartType defaultType) {
        this.plugin = plugin;
        this.config = config;
        this.registry = registry;
        this.menus = menus;
        this.protection = protection;
        this.cooldown = cooldown;
        this.defaultType = defaultType;
    }

    public void start() {
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntitiesByClass(Minecart.class).forEach(this::trackIfManaged);
        }
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        menus.closeEverything();
        loadedCarts.clear();
    }

    // ---- 查找 ----

    /** 载着方块的矿车；普通矿车返回 null。 */
    @Nullable ManagedCartImpl find(Minecart minecart) {
        if (!CartData.isManaged(minecart)) {
            return null;
        }
        CartType type = registry.byId(CartData.type(minecart));
        if (type == null) {
            // 类型所属插件已卸载：原版特殊矿车交给能接管它的类型，其余按普通方块处理，方块都不会丢
            type = Objects.requireNonNullElse(typeForVanillaCart(minecart), defaultType);
        }
        return new ManagedCartImpl(this, minecart, type);
    }

    /** 不是本插件创建的原版特殊矿车（例如合成出来的箱子矿车）：找到能从它上面取下方块的类型。 */
    @Nullable ManagedCartImpl findVanillaCart(Minecart minecart) {
        CartType type = typeForVanillaCart(minecart);
        return type == null ? null : new ManagedCartImpl(this, minecart, type);
    }

    private @Nullable CartType typeForVanillaCart(Minecart minecart) {
        for (CartType type : registry.all()) {
            if (type.nativeBlock(minecart) != null) {
                return type;
            }
        }
        return null;
    }

    // ---- 放上方块 ----

    /**
     * 潜行右键空矿车：把主手的方块放上去。
     *
     * @return 这次交互是否由本插件处理（处理了就取消原版交互）
     */
    boolean place(Player player, Minecart minecart) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Material material = hand.getType();
        if (!canCarryBlock(minecart) || material.isAir() || !material.isBlock() || !config.isBlockAllowed(material)) {
            return false;
        }
        ItemStack block = hand.asOne();
        CartType type = Objects.requireNonNullElse(registry.forBlock(material), defaultType);
        if (!type.canPlace(player, block) || protection.denyPlace(player, minecart.getLocation())) {
            return false;
        }
        if (!cooldown.tryUse(player)) {
            return true; // 合法但点得太快：吞掉这次点击
        }

        consumeOne(player, hand);
        CartData.write(minecart, type.id(), block);
        ManagedCartImpl cart = new ManagedCartImpl(this, minecart, type);
        cart.showBlock(block);
        track(minecart);
        type.onPlaced(cart, player);

        if (config.soundPlace()) {
            playBlockSound(cart.minecart().getLocation(), block, true);
        }
        return true;
    }

    /** 只有空着的普通矿车能放方块：没有乘客，也没有别的插件或地图设置的显示方块。 */
    private static boolean canCarryBlock(Minecart minecart) {
        return minecart instanceof RideableMinecart
                && minecart.getPassengers().isEmpty()
                && !CartData.isManaged(minecart)
                && !showsBlock(minecart);
    }

    private static boolean showsBlock(Minecart minecart) {
        BlockData display = minecart.getDisplayBlockData();
        return display != null && !display.getMaterial().isAir();
    }

    /** 与原版放置方块一致：创造模式不消耗物品。 */
    private static void consumeOne(Player player, ItemStack hand) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        player.getInventory().setItemInMainHand(hand.getAmount() > 1 ? hand.asQuantity(hand.getAmount() - 1) : null);
    }

    // ---- 使用 ----

    /**
     * 不潜行右键载着方块的矿车。
     *
     * @return 是否取消原版交互
     */
    boolean use(Player player, ManagedCartImpl cart) {
        Minecart minecart = cart.minecart();
        if (!(minecart instanceof RideableMinecart)) {
            // 箱子矿车等原版特殊矿车：右键是原版交互，由保护插件自己把关，这里不加判定也不计冷却
            return cart.type().onUse(cart, player);
        }
        // 普通矿车上的方块界面没有对应的原版方块事件，要主动问领地保护
        if (protection.denyOpenGui(player, minecart.getLocation(), cart.blockType()) || !cooldown.tryUse(player)) {
            return true;
        }
        return cart.type().onUse(cart, player);
    }

    // ---- 取下方块 ----

    /**
     * 潜行右键载着方块的矿车：取下方块交给玩家，矿车恢复为普通矿车。
     *
     * @return 是否取消原版交互；取下被配置或类型拒绝时返回 false，这次点击交给原版（例如打开箱子矿车的界面）
     */
    boolean takeOff(Player player, ManagedCartImpl cart) {
        Minecart minecart = cart.minecart();
        TakeOffMode mode = config.takeOffMode();
        if (mode == TakeOffMode.DISABLED || !cart.type().canTakeOff(cart, player)) {
            return false;
        }
        if (!minecart.getPassengers().isEmpty()) {
            player.sendMessage("矿车上有人乘坐，无法取下");
            return true;
        }
        if (protection.denyTakeOff(player, minecart.getLocation()) || !cooldown.tryUse(player)) {
            return true;
        }

        menus.closeAll(minecart); // 先让界面把改动写回矿车，取下的才是最新的方块物品
        ItemStack block = cart.type().takeOff(cart);
        if (block == null) {
            plugin.getLogger().warning("矿车 " + minecart.getUniqueId() + " 没有可取下的方块物品（数据损坏？），已保持原样");
            return true;
        }
        cart.removeBlock();

        Location location = cart.minecart().getLocation();
        if (mode == TakeOffMode.INVENTORY) {
            giveOrDrop(player, block);
        } else {
            location.getWorld().dropItemNaturally(location, block);
        }
        if (config.soundTakeOff()) {
            playBlockSound(location, block, false);
        }
        return true;
    }

    /** 与原版 /give 一致：放进背包，放不下的掉在玩家脚下。 */
    private static void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
        }
    }

    /** 播放该方块被放置/破坏时的原版音效（音量、音调的换算与原版相同）。 */
    private static void playBlockSound(Location location, ItemStack block, boolean placing) {
        SoundGroup sounds = block.getType().createBlockData().getSoundGroup();
        location.getWorld().playSound(location, placing ? sounds.getPlaceSound() : sounds.getBreakSound(),
                SoundCategory.BLOCKS, (sounds.getVolume() + 1.0F) / 2.0F, sounds.getPitch() * 0.8F);
    }

    // ---- 移除 ----

    /** 矿车实体从世界移除。区块卸载与随乘客下线保存只是暂时离开，其余都视为摧毁。 */
    void onRemoved(Minecart minecart, EntityRemoveEvent.Cause cause) {
        ManagedCartImpl cart = find(minecart);
        if (cart == null) {
            return;
        }
        menus.closeAll(minecart);
        untrack(minecart);
        if (cause == EntityRemoveEvent.Cause.UNLOAD || cause == EntityRemoveEvent.Cause.PLAYER_QUIT) {
            return;
        }
        cart.type().onDestroyed(cart, cause);
    }

    /**
     * 用另一种矿车实体替换旧实体（{@link ManagedCart#convertTo} 的实现）。
     * 旧实体先清掉插件数据与库存再移除，这样它的移除不会被当成“摧毁”，原版也不会把库存撒出来。
     */
    <T extends Minecart> T replaceEntity(Minecart old, Class<T> minecartClass, String typeNamespace) {
        T replacement = old.getWorld().spawn(old.getLocation(), minecartClass, created -> {
            created.setVelocity(old.getVelocity());
            created.customName(old.customName());
            created.setCustomNameVisible(old.isCustomNameVisible());
            old.getScoreboardTags().forEach(created::addScoreboardTag);
            old.getPersistentDataContainer().copyTo(created.getPersistentDataContainer(), true);
        });
        if (!replacement.isValid()) {
            throw new IllegalStateException("无法生成 " + minecartClass.getSimpleName() + "（可能被其他插件取消）");
        }
        CartData.clear(old, typeNamespace);
        if (old instanceof InventoryHolder holder) {
            holder.getInventory().clear();
        }
        old.remove();
        untrack(old);
        track(replacement);
        return replacement;
    }

    // ---- 每 tick ----

    void trackIfManaged(Minecart minecart) {
        if (CartData.isManaged(minecart)) {
            track(minecart);
        }
    }

    void track(Minecart minecart) {
        loadedCarts.put(minecart.getUniqueId(), minecart);
    }

    void untrack(Minecart minecart) {
        loadedCarts.remove(minecart.getUniqueId(), minecart);
    }

    private void tick() {
        menus.tick();
        for (Minecart minecart : List.copyOf(loadedCarts.values())) {
            ManagedCartImpl cart = minecart.isValid() ? find(minecart) : null;
            if (cart == null) {
                untrack(minecart);
                continue;
            }
            try {
                cart.type().onTick(cart);
            } catch (RuntimeException e) {
                // 一辆矿车出错不能拖累其他矿车的 tick
                plugin.getLogger().log(Level.WARNING, "矿车类型 " + cart.type().id() + " 的 onTick 出错", e);
            }
        }
    }

    void onMove(Minecart minecart) {
        ManagedCartImpl cart = find(minecart);
        if (cart != null) {
            cart.type().onMove(cart);
        }
    }

    boolean allowsPassengers(Minecart minecart) {
        ManagedCartImpl cart = find(minecart);
        return cart == null || cart.type().allowsPassengers();
    }

    void onPluginDisabled(Plugin disabled) {
        registry.unregisterAll(disabled);
    }

    // ---- 供 ManagedCartImpl 使用 ----

    PluginConfig config() {
        return config;
    }

    CartMenus menus() {
        return menus;
    }

    Logger logger() {
        return plugin.getLogger();
    }

    // ---- PepperMinecartAPI ----

    @Override
    public void registerCartType(Plugin owner, CartType type) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        if (type.id() != null && CartData.NAMESPACE.equals(type.id().getNamespace())) {
            throw new IllegalArgumentException("命名空间 pepperminecart 保留给内置类型: " + type.id());
        }
        registry.register(owner, type);
    }

    @Override
    public void unregisterCartType(NamespacedKey id) {
        if (CartData.NAMESPACE.equals(id.getNamespace())) {
            throw new IllegalArgumentException("内置矿车类型不可注销: " + id);
        }
        registry.unregister(id);
    }

    @Override
    public @Nullable CartType getCartType(NamespacedKey id) {
        return registry.byId(id);
    }

    @Override
    public @Nullable CartType getCartTypeForBlock(Material block) {
        return registry.forBlock(block);
    }

    @Override
    public @Nullable ManagedCart getCart(Minecart minecart) {
        return find(minecart);
    }
}
