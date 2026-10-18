package ltd.pepper.pepperminecart.type;

import java.util.Map;
import java.util.Set;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 原版本来就有对应矿车的方块：箱子、漏斗、熔炉、TNT、命令方块。放上去后矿车直接变成原版的箱子矿车、
 * 漏斗矿车……，此后的一切（界面、漏斗吸取、熔炉推车、TNT 爆炸、执行命令、被破坏时的掉落）都是原版行为。
 *
 * <p>取下时反过来：容器内容装进方块物品、命令写回命令方块，矿车变回普通矿车。
 * 没经过本插件的原版矿车（例如合成出来的箱子矿车）同样可以取下，受 {@code vanilla-carts.allow-pickup} 控制。</p>
 */
public final class VanillaMinecartCart implements CartType {

    /** 沿用 1.0 版本的 id（special），已有存档无需迁移。 */
    public static final NamespacedKey ID = new NamespacedKey("pepperminecart", "special");

    private static final Map<Material, Class<? extends Minecart>> MINECART_FOR_BLOCK = Map.of(
            Material.CHEST, StorageMinecart.class,
            Material.HOPPER, HopperMinecart.class,
            Material.FURNACE, PoweredMinecart.class,
            Material.TNT, ExplosiveMinecart.class,
            Material.COMMAND_BLOCK, CommandMinecart.class,
            Material.CHAIN_COMMAND_BLOCK, CommandMinecart.class,
            Material.REPEATING_COMMAND_BLOCK, CommandMinecart.class
    );

    private static final Map<EntityType, Material> BLOCK_FOR_MINECART = Map.of(
            EntityType.CHEST_MINECART, Material.CHEST,
            EntityType.HOPPER_MINECART, Material.HOPPER,
            EntityType.FURNACE_MINECART, Material.FURNACE,
            EntityType.TNT_MINECART, Material.TNT,
            EntityType.COMMAND_BLOCK_MINECART, Material.COMMAND_BLOCK
    );

    private final PluginConfig config;

    public VanillaMinecartCart(PluginConfig config) {
        this.config = config;
    }

    @Override
    public NamespacedKey id() {
        return ID;
    }

    @Override
    public Set<Material> blocks() {
        return MINECART_FOR_BLOCK.keySet();
    }

    /** 原版矿车自己会画出它的方块。 */
    @Override
    public @Nullable BlockData displayBlock(ItemStack blockItem) {
        return null;
    }

    @Override
    public @Nullable ItemStack nativeBlock(Minecart minecart) {
        Material block = BLOCK_FOR_MINECART.get(minecart.getType());
        return block == null ? null : new ItemStack(block);
    }

    @Override
    public void onPlaced(ManagedCart cart, Player player) {
        ItemStack block = cart.blockItem();
        Minecart minecart = cart.convertTo(MINECART_FOR_BLOCK.get(block.getType()));

        // 箱子、漏斗里装的东西搬进矿车；装不下的（以及熔炉里的东西，熔炉矿车没有库存）掉在原地。
        // 掉落被其他插件取消时（dropItem 返回 false）把那一件留在方块物品里，别让它既没进矿车
        // 也没掉出来——取下矿车时玩家还能连同方块一起拿回
        ItemStack[] contents = BlockItems.contents(block);
        Inventory inventory = minecart instanceof InventoryHolder holder ? holder.getInventory() : null;
        ItemStack[] kept = new ItemStack[contents.length];
        boolean anyKept = false;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack content = contents[slot];
            if (content == null || content.isEmpty()) {
                continue;
            }
            if (inventory != null && slot < inventory.getSize()) {
                inventory.setItem(slot, content);
            } else if (!cart.dropItem(content)) {
                kept[slot] = content;
                anyKept = true;
            }
        }
        if (minecart instanceof CommandMinecart commandMinecart) {
            commandMinecart.setCommand(BlockItems.command(block));
        }
        // 方块物品留作取下时的模板（保留自定义名等）；已装进矿车的内容由原版矿车保管、不再重复保存，
        // 只有没能掉出去的那几件继续留在里面
        cart.setBlockItem(BlockItems.withContents(block, anyKept ? kept : new ItemStack[0]));
    }

    @Override
    public boolean canTakeOff(ManagedCart cart, Player player) {
        return config.vanillaCartPickupAllowed();
    }

    @Override
    public @Nullable ItemStack takeOff(ManagedCart cart) {
        ItemStack block = withCartState(cart);
        if (block == null) {
            return null;
        }
        // 先把内容物落进矿车的方块物品：清库存与换实体都可能出问题（换实体要过别的插件的事件），
        // 落盘之后再动手，任何一步失败都不会让箱子/漏斗里的东西只存在于这个局部变量里
        cart.setBlockItem(block);
        if (cart.minecart() instanceof InventoryHolder holder) {
            holder.getInventory().clear(); // 内容已经装进方块物品
        }
        if (!(cart.minecart() instanceof RideableMinecart)) {
            cart.convertTo(RideableMinecart.class);
        }
        return block;
    }

    @Override
    public void onDestroyed(ManagedCart cart, EntityRemoveEvent.Cause cause) {
        Minecart minecart = cart.minecart();
        if (cause == EntityRemoveEvent.Cause.EXPLODE) {
            return; // TNT 矿车爆炸了：TNT 已经用掉
        }
        if (cause == EntityRemoveEvent.Cause.DEATH && BLOCK_FOR_MINECART.containsKey(minecart.getType())
                && Boolean.TRUE.equals(minecart.getWorld().getGameRuleValue(GameRule.DO_ENTITY_DROPS))) {
            return; // 原版会掉落“箱子矿车”这类物品，方块已经包含在里面
        }
        // 其余移除方式原版不掉矿车物品，把方块补掉出来。容器内容像破坏箱子一样撒在地上
        // （原版移除容器矿车时通常已经撒过，这里只处理剩下的）。此时矿车正在消失，掉落再被取消就
        // 没有地方收留了，只能留下日志说明丢了什么
        if (minecart instanceof InventoryHolder holder) {
            for (ItemStack content : BlockItems.nonEmpty(holder.getInventory().getContents())) {
                if (!cart.dropItem(content)) {
                    Bukkit.getLogger().warning("矿车 " + minecart.getUniqueId() + " 被摧毁时内容物掉落被取消，"
                            + content.getType() + " x" + content.getAmount() + " 已丢失");
                }
            }
            holder.getInventory().clear();
        }
        ItemStack block = withCartState(cart);
        if (block != null && !cart.dropItem(block)) {
            Bukkit.getLogger().warning("矿车 " + minecart.getUniqueId() + " 被摧毁时方块掉落被取消，"
                    + block.getType() + " x" + block.getAmount() + " 已丢失");
        }
    }

    /** 方块物品加上矿车当前的状态：箱子/漏斗矿车的内容、命令矿车的命令。 */
    private static @Nullable ItemStack withCartState(ManagedCart cart) {
        ItemStack block = cart.blockItem();
        if (block == null) {
            return null;
        }
        Minecart minecart = cart.minecart();
        if (minecart instanceof InventoryHolder holder) {
            block = BlockItems.withContents(block,
                    mergeContents(BlockItems.contents(block), holder.getInventory().getContents()));
        }
        if (minecart instanceof CommandMinecart command) {
            block = BlockItems.withCommand(block, command.getCommand());
        }
        return block;
    }

    /**
     * 合并方块物品里留下的内容与矿车库存：矿车占自己的槽位，矿车之外（当初装不下而留下的）
     * 保持原样，这样放置时没能掉出去的东西不会在取下时被库存覆盖掉。
     */
    private static ItemStack[] mergeContents(ItemStack[] kept, ItemStack[] live) {
        int size = Math.max(kept.length, live.length);
        ItemStack[] merged = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            ItemStack fromCart = slot < live.length ? live[slot] : null;
            ItemStack fromBlock = slot < kept.length ? kept[slot] : null;
            boolean cartHas = fromCart != null && !fromCart.isEmpty();
            boolean blockHas = fromBlock != null && !fromBlock.isEmpty();
            if (cartHas) {
                merged[slot] = fromCart;
                if (blockHas && !fromBlock.isSimilar(fromCart)) {
                    // 同一个槽位两边都有东西：矿车里的算更新，方块物品里那份另找空位，不能静默吞掉。
                    // 内置的原版矿车走不到这里（方块物品与实体库存同尺寸、同槽位），是给自定义类型留的余地。
                    spill(merged, fromBlock);
                }
            } else if (blockHas) {
                merged[slot] = fromBlock;
            }
        }
        return merged;
    }

    /** 把合并时多出来的一份放进空槽，或并进同类的堆；真的没有空间时记一条日志。 */
    private static void spill(ItemStack[] merged, ItemStack extra) {
        for (int slot = 0; slot < merged.length; slot++) {
            ItemStack current = merged[slot];
            if (current == null || current.isEmpty()) {
                merged[slot] = extra;
                return;
            }
            if (current.isSimilar(extra)) {
                merged[slot] = current.asQuantity(current.getAmount() + extra.getAmount());
                return;
            }
        }
        org.bukkit.Bukkit.getLogger().warning(
                "原版矿车取下时合并内容失败：" + extra.getType() + " ×" + extra.getAmount() + " 没有空槽可放");
    }
}
