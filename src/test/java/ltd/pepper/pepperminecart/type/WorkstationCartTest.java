package ltd.pepper.pepperminecart.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.MenuType;
import org.junit.jupiter.api.Test;

/** 工作站打开的就是该方块的原版界面（MockBukkit 无法真正打开 MenuType 界面，只验证对应关系）。 */
class WorkstationCartTest extends TypeTestSupport {

    @Test
    void everyWorkstationOpensItsOwnVanillaMenu() {
        Map<Material, MenuType> expected = Map.of(
                Material.CRAFTING_TABLE, MenuType.CRAFTING,
                Material.GRINDSTONE, MenuType.GRINDSTONE,
                Material.LOOM, MenuType.LOOM,
                Material.CARTOGRAPHY_TABLE, MenuType.CARTOGRAPHY_TABLE,
                Material.SMITHING_TABLE, MenuType.SMITHING,
                Material.STONECUTTER, MenuType.STONECUTTER,
                Material.ENCHANTING_TABLE, MenuType.ENCHANTMENT);

        assertEquals(expected.keySet(), new WorkstationCart().blocks());
        expected.forEach((block, menu) -> assertEquals(menu, WorkstationCart.menuFor(block), block.name()));
        assertNull(WorkstationCart.menuFor(Material.STONE));
    }
}
