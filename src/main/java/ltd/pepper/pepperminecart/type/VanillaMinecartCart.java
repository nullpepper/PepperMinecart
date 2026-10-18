package ltd.pepper.pepperminecart.type;

import java.util.Map;
import java.util.Set;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.config.PluginConfig;
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

        // 箱子、漏斗里装的东西搬进矿车；装不下的（以及熔炉里的东西，熔炉矿车没有库存）掉在原地
        ItemStack[] contents = BlockItems.contents(block);
        Inventory inventory = minecart instanceof InventoryHolder holder ? holder.getInventory() : null;
        for (int slot = 0; slot < contents.length; slot++) {
            if (inventory != null && slot < inventory.getSize()) {
                inventory.setItem(slot, contents[slot]);
            } else if (contents[slot] != null && !contents[slot].isEmpty()) {
                cart.dropItem(contents[slot]);
            }
        }
        if (minecart instanceof CommandMinecart commandMinecart) {
            commandMinecart.setCommand(BlockItems.command(block));
        }
        // 方块物品留作取下时的模板（保留自定义名等）；内容此后由原版矿车保管，不再重复保存
        cart.setBlockItem(BlockItems.withContents(block, new ItemStack[0]));
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
        // （原版移除容器矿车时通常已经撒过，这里只处理剩下的）
        if (minecart instanceof InventoryHolder holder) {
            BlockItems.nonEmpty(holder.getInventory().getContents()).forEach(cart::dropItem);
            holder.getInventory().clear();
        }
        ItemStack block = withCartState(cart);
        if (block != null) {
            cart.dropItem(block);
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
            block = BlockItems.withContents(block, holder.getInventory().getContents());
        }
        if (minecart instanceof CommandMinecart command) {
            block = BlockItems.withCommand(block, command.getCommand());
        }
        return block;
    }
}
