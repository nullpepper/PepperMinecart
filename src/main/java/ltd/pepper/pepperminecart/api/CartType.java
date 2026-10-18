package ltd.pepper.pepperminecart.api;

import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockDataMeta;
import org.jetbrains.annotations.Nullable;

/**
 * 一种矿车类型：描述某些方块放到矿车上之后的行为。
 *
 * <p>玩家潜行右键空矿车放上方块时，插件按方块找到对应类型，矿车随即成为该类型的受管矿车。
 * 通用流程由插件完成——扣除物品、保存方块、显示方块、禁止乘坐、取下返还、破坏掉落、
 * 关闭绑定在矿车上的界面——类型只需覆盖自己关心的钩子，所有钩子都有默认实现。</p>
 *
 * <p>新增类型：实现本接口，再在插件启用时调用
 * {@link PepperMinecartAPI#registerCartType(org.bukkit.plugin.Plugin, CartType)}。
 * 内置类型（工作站、铁砧、容器、发射器、原版特殊矿车）都是本接口的普通实现，
 * 可作为参考：{@code ltd.pepper.pepperminecart.type} 包。</p>
 */
public interface CartType {

    /**
     * 唯一 id，保存在每辆该类型的矿车上。
     * 命名空间请使用自己插件的 namespace；矿车取下时，该命名空间下写在矿车上的数据会一并清除。
     */
    NamespacedKey id();

    /** 放上这些方块时矿车成为本类型。返回空集表示本类型不由放置方块产生。 */
    Set<Material> blocks();

    /**
     * 额外的放置条件。插件已经检查过配置黑白名单、矿车状态与领地保护。
     *
     * @param block 将要放上矿车的方块物品（数量为 1）
     */
    default boolean canPlace(Player player, ItemStack block) {
        return true;
    }

    /** 方块已经放上矿车（数据已保存、显示已设置）之后调用。 */
    default void onPlaced(ManagedCart cart, Player player) {
    }

    /**
     * 玩家不潜行右键矿车时调用，通常用来打开界面。
     *
     * @return true 表示已处理，插件会取消原版交互（例如乘坐）
     */
    default boolean onUse(ManagedCart cart, Player player) {
        return false;
    }

    /**
     * 是否允许取下方块。返回 false 时这次点击交给原版处理（例如打开箱子矿车的界面），
     * 需要的话请自行向玩家说明原因。
     */
    default boolean canTakeOff(ManagedCart cart, Player player) {
        return true;
    }

    /**
     * 把方块从矿车上取下，返回交给玩家的物品；返回 null 表示没有可交付的物品，取下会被取消。
     * 之后插件会清除矿车数据、恢复原版外观，并按 {@code take-off-mode} 交付物品。
     */
    default @Nullable ItemStack takeOff(ManagedCart cart) {
        return cart.blockItem();
    }

    /**
     * 矿车被摧毁（不含区块卸载、随乘客下线保存这类“暂时离开”）。默认把方块物品掉落在原地。
     *
     * @param cause 移除原因；{@code DEATH} 表示被打坏或被杀死，原版此时会按 doEntityDrops 掉落矿车物品
     */
    default void onDestroyed(ManagedCart cart, EntityRemoveEvent.Cause cause) {
        ItemStack block = cart.blockItem();
        if (block != null) {
            cart.dropItem(block);
        }
    }

    /** 每 tick 调用一次（仅已加载的矿车）。 */
    default void onTick(ManagedCart cart) {
    }

    /** 矿车移动时调用（{@link org.bukkit.event.vehicle.VehicleMoveEvent}）。 */
    default void onMove(ManagedCart cart) {
    }

    /** 是否允许生物乘坐。默认 false：载着方块的矿车不能坐人。 */
    default boolean allowsPassengers() {
        return false;
    }

    /**
     * 矿车上显示的方块；返回 null 表示保持矿车的原版外观（例如箱子矿车自己会画出箱子）。
     * 默认显示方块物品本身，物品带有方块状态（{@code minecraft:block_state}）时一并沿用，与原版放置方块一致。
     */
    default @Nullable BlockData displayBlock(ItemStack blockItem) {
        Material material = blockItem.getType();
        if (blockItem.getItemMeta() instanceof BlockDataMeta meta && meta.hasBlockData()) {
            return meta.getBlockData(material);
        }
        return material.createBlockData();
    }

    /**
     * 原版矿车天然“载着”的方块，例如箱子矿车载着箱子。不适用时返回 null。
     *
     * <p>用途有二：让玩家也能从不是本插件创建的原版矿车上取下方块；以及在矿车没有保存方块物品时
     * 作为兜底。只有把方块变成原版特殊矿车实体的类型才需要实现。</p>
     */
    default @Nullable ItemStack nativeBlock(Minecart minecart) {
        return null;
    }
}
