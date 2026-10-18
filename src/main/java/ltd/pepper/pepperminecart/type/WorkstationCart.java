package ltd.pepper.pepperminecart.type;

import java.util.Map;
import java.util.Set;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;
import org.jetbrains.annotations.Nullable;

/**
 * 工作站（工作台、砂轮、织布机……）：右键打开该方块的原版界面，一切交给原版——
 * 合成、附魔（会计算矿车周围的书架）、关闭界面时退还材料，都与方块摆在矿车位置时完全相同。
 */
public final class WorkstationCart implements CartType {

    public static final NamespacedKey ID = new NamespacedKey("pepperminecart", "workstation");

    /** 方块 → 它的原版界面。支持新的工作站只需在这里加一行。 */
    private static final Map<Material, MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>>> MENUS = Map.of(
            Material.CRAFTING_TABLE, MenuType.CRAFTING,
            Material.GRINDSTONE, MenuType.GRINDSTONE,
            Material.LOOM, MenuType.LOOM,
            Material.CARTOGRAPHY_TABLE, MenuType.CARTOGRAPHY_TABLE,
            Material.SMITHING_TABLE, MenuType.SMITHING,
            Material.STONECUTTER, MenuType.STONECUTTER,
            Material.ENCHANTING_TABLE, MenuType.ENCHANTMENT
    );

    @Override
    public NamespacedKey id() {
        return ID;
    }

    @Override
    public Set<Material> blocks() {
        return MENUS.keySet();
    }

    @Override
    public boolean onUse(ManagedCart cart, Player player) {
        MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menu = menuFor(cart.blockType());
        if (menu == null) {
            return false;
        }
        cart.openMenu(player, menu);
        return true;
    }

    static @Nullable MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menuFor(Material block) {
        return MENUS.get(block);
    }
}
