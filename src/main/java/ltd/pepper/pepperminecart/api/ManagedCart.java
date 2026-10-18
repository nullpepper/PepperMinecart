package ltd.pepper.pepperminecart.api;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;
import org.jetbrains.annotations.Nullable;

/**
 * 一辆载着方块的矿车。插件把它交给 {@link CartType} 的各个钩子，类型通过它读写方块、打开界面。
 *
 * <p>它只是矿车实体之上的一层视图：数据都保存在实体的 PersistentDataContainer 里，
 * 随区块保存，服务器重启后依旧存在。除了这里的方法，也可以直接对 {@link #minecart()} 使用 Bukkit API，
 * 例如把自己的数据写进它的 PersistentDataContainer（请使用类型 id 的命名空间，取下方块时会被清除）。</p>
 */
public interface ManagedCart {

    /** 矿车实体。{@link #convertTo} 之后指向新的实体。 */
    Minecart minecart();

    CartType type();

    /**
     * 矿车上方块物品的副本，带着它的全部数据（容器内容、自定义名等）。
     * 只有保存的数据损坏且类型无法给出 {@link CartType#nativeBlock 兜底方块} 时才返回 null。
     */
    @Nullable ItemStack blockItem();

    /** 方块物品的材质；没有方块物品时为 {@link Material#AIR}。 */
    Material blockType();

    /** 替换矿车上的方块物品并保存，显示的方块随之更新（例如铁砧受损变为开裂的铁砧）。 */
    void setBlockItem(ItemStack blockItem);

    /** 移除矿车上的方块但不交给任何人，矿车变回普通矿车（例如铁砧用坏了）。 */
    void removeBlock();

    /**
     * 在矿车所在位置掉落物品。
     *
     * @return 是否真的掉出来了；掉落被其他插件取消（例如领地插件取消 {@code ItemSpawnEvent}）时返回 false，
     * 调用方应放弃这次操作并保留矿车上的数据，否则物品会凭空消失
     */
    boolean dropItem(ItemStack item);

    /**
     * 打开一个原版方块界面（工作台、铁砧、织布机……），效果如同该方块就摆在矿车所在的位置。
     * 界面与矿车绑定：矿车消失、方块被取下或玩家走远时自动关闭，与原版矿车容器的规则一致。
     *
     * @return 打开的界面；被其他插件阻止时返回 null
     */
    default @Nullable InventoryView openMenu(Player player, MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menu) {
        return openMenu(player, menu, null);
    }

    /**
     * 同 {@link #openMenu(Player, MenuType.Typed)}，并在界面关闭时（无论原因）回调 {@code onClose}。
     */
    @Nullable InventoryView openMenu(Player player, MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menu,
                                     @Nullable Runnable onClose);

    /**
     * 向玩家打开一个库存界面，并与矿车绑定（关闭规则同 {@link #openMenu}）。
     * 界面关闭时（无论原因）回调 {@code onClose}，用于把改动写回矿车。
     *
     * @return 打开的界面；被其他插件阻止时返回 null
     */
    @Nullable InventoryView openInventory(Player player, Inventory inventory, @Nullable Runnable onClose);

    /**
     * 把矿车实体换成另一种原版矿车（例如普通矿车 → 箱子矿车），保留位置、朝向、速度、名字、
     * 记分板标签与全部持久化数据，并继续由插件管理。旧实体随即被移除，其库存里的物品不会保留，
     * 需要的话请先转移。
     */
    <T extends Minecart> T convertTo(Class<T> minecartClass);

    /** 冷却是否尚未结束。冷却按世界游戏时间计算：随矿车保存，不受 /time set 影响。 */
    boolean isCoolingDown(NamespacedKey key);

    /** 开始一段冷却，单位 tick。 */
    void startCooldown(NamespacedKey key, int ticks);
}
