package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import ltd.pepper.pepperminecart.type.VanillaMinecartCart;
import net.kyori.adventure.text.Component;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.entity.EntityRemoveEvent.Cause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 箱子、漏斗、熔炉、TNT、命令方块放上矿车后变成原版矿车，取下时变回来。 */
class VanillaMinecartTest extends CartTestSupport {

    @Test
    void blocksBecomeTheirVanillaMinecartAndBack() {
        Map<Material, EntityType> conversions = Map.of(
                Material.CHEST, EntityType.CHEST_MINECART,
                Material.HOPPER, EntityType.HOPPER_MINECART,
                Material.FURNACE, EntityType.FURNACE_MINECART,
                Material.TNT, EntityType.TNT_MINECART,
                Material.COMMAND_BLOCK, EntityType.COMMAND_BLOCK_MINECART);
        conversions.forEach((block, minecartType) -> {
            PlayerMock player = newPlayer();
            Minecart converted = placeOnNewCart(player, block);
            assertEquals(minecartType, converted.getType(), block.name());
            assertEquals(VanillaMinecartCart.ID, managed(converted).type().id());

            sneakRightClick(player, converted);

            Minecart restored = onlyMinecart();
            assertEquals(EntityType.MINECART, restored.getType(), block.name());
            assertNull(api.getCart(restored));
            assertEquals(1, countInInventory(player, block), block.name());
            restored.remove();
        });
    }

    @Test
    void conversionKeepsTheMinecartsNameAndTheBlocksName() {
        PlayerMock player = newPlayer();
        ItemStack namedChest = new ItemStack(Material.CHEST);
        namedChest.editMeta(meta -> meta.customName(Component.text("宝藏箱")));
        Minecart converted = placeOnNewCart(player, namedChest);
        converted.customName(Component.text("运宝车"));

        sneakRightClick(player, converted);

        assertEquals(Component.text("运宝车"), onlyMinecart().customName(), "矿车的名字跟着实体走");
        assertEquals(Component.text("宝藏箱"), findInInventory(player, Material.CHEST).getItemMeta().customName(),
                "方块物品的名字原样返还");
    }

    @Test
    void contentsComeBackInsideTheChestItem() {
        PlayerMock player = newPlayer();
        StorageMinecart converted = (StorageMinecart) placeOnNewCart(player, Material.CHEST);
        converted.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));

        sneakRightClick(player, converted);

        assertEquals(3, diamondsInside(findInInventory(player, Material.CHEST)));
        assertTrue(converted.getInventory().isEmpty(), "旧矿车的库存已清空，原版移除它时不会再撒出一份");
        assertTrue(droppedItems(Material.DIAMOND).isEmpty());
    }

    @Test
    void chestMinecartsNotMadeByThePluginCanBeEmptiedToo() {
        PlayerMock player = newPlayer();
        StorageMinecart crafted = world.spawn(cartLocation(), StorageMinecart.class);
        crafted.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));

        assertTrue(sneakRightClick(player, crafted).isCancelled());

        assertEquals(EntityType.MINECART, onlyMinecart().getType());
        assertEquals(3, diamondsInside(findInInventory(player, Material.CHEST)));
    }

    @Test
    void disablingVanillaPickupLeavesChestMinecartsToVanilla() {
        setConfig("vanilla-carts.allow-pickup", false);
        PlayerMock player = newPlayer();
        StorageMinecart crafted = world.spawn(cartLocation(), StorageMinecart.class);

        assertFalse(sneakRightClick(player, crafted).isCancelled(), "交给原版：打开箱子矿车的界面");
        assertEquals(EntityType.CHEST_MINECART, onlyMinecart().getType());
        assertEquals(0, countInInventory(player, Material.CHEST));
    }

    @Test
    void brokenChestMinecartLeavesTheDropsToVanilla() {
        StorageMinecart converted = (StorageMinecart) placeOnNewCart(newPlayer(), Material.CHEST);
        converted.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        world.setGameRule(GameRule.DO_ENTITY_DROPS, true);

        removeCart(converted, Cause.DEATH);

        assertTrue(droppedItems(Material.CHEST).isEmpty(), "原版掉落的“箱子矿车”里已经包含箱子，不能再补一个");
        assertNotNull(converted.getInventory().getItem(0), "内容由原版撒出，插件不碰");
    }

    @Test
    void chestMinecartRemovedWithoutVanillaDropsStillDropsItsChest() {
        StorageMinecart converted = (StorageMinecart) placeOnNewCart(newPlayer(), Material.CHEST);
        converted.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        world.setGameRule(GameRule.DO_ENTITY_DROPS, false);

        removeCart(converted, Cause.DEATH);

        assertEquals(1, droppedItems(Material.CHEST).size());
        assertEquals(1, droppedItems(Material.DIAMOND).size(), "像破坏箱子一样撒出内容");
    }

    @Test
    void discardedChestMinecartDropsItsChestAndContents() {
        StorageMinecart converted = (StorageMinecart) placeOnNewCart(newPlayer(), Material.CHEST);
        converted.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));

        removeCart(converted, Cause.DISCARD);

        assertEquals(1, droppedItems(Material.CHEST).size());
        assertEquals(1, droppedItems(Material.DIAMOND).size());
    }

    @Test
    void explodedTntMinecartDoesNotGiveItsTntBack() {
        Minecart converted = placeOnNewCart(newPlayer(), Material.TNT);

        removeCart(converted, Cause.EXPLODE);

        assertTrue(droppedItems(Material.TNT).isEmpty(), "TNT 已经炸掉了，再掉一个就是刷 TNT");
    }

    private static int diamondsInside(ItemStack item) {
        assertNotNull(item);
        if (!(item.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof Container container)) {
            return 0;
        }
        int total = 0;
        for (ItemStack content : container.getSnapshotInventory().getContents()) {
            if (content != null && content.getType() == Material.DIAMOND) {
                total += content.getAmount();
            }
        }
        return total;
    }
}
