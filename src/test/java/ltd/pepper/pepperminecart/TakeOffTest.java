package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import ltd.pepper.pepperminecart.api.CartType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 潜行右键载着方块的矿车取下方块。 */
class TakeOffTest extends CartTestSupport {

    @Test
    void inventoryModeHandsTheBlockBack() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, new ItemStack(Material.GRINDSTONE, 3));

        assertTrue(sneakRightClick(player, cart).isCancelled());

        assertEquals(3, countInInventory(player, Material.GRINDSTONE));
        assertNull(api.getCart(cart), "取下后是普通矿车");
        assertFalse(showsBlock(cart), "显示恢复为原版的空矿车");
        assertTrue(droppedItems(Material.GRINDSTONE).isEmpty());
    }

    @Test
    void leftoversOfAFullInventoryLandAtThePlayersFeet() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.GRINDSTONE);
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }

        sneakRightClick(player, cart);

        assertEquals(1, droppedItems(Material.GRINDSTONE).size(), "与原版 /give 一致：背包放不下就掉在玩家脚下");
    }

    @Test
    void dropModeDropsTheBlockNextToTheCart() {
        setConfig("take-off-mode", "DROP");
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, new ItemStack(Material.GRINDSTONE, 3));

        sneakRightClick(player, cart);

        assertEquals(2, countInInventory(player, Material.GRINDSTONE));
        assertEquals(1, droppedItems(Material.GRINDSTONE).size());
        assertNull(api.getCart(cart));
    }

    @Test
    void disabledModeKeepsTheBlockOnTheCart() {
        setConfig("take-off-mode", "DISABLED");
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, new ItemStack(Material.GRINDSTONE, 3));

        sneakRightClick(player, cart);

        assertEquals(2, countInInventory(player, Material.GRINDSTONE));
        assertEquals(Material.GRINDSTONE, managed(cart).blockType());
        assertTrue(showsBlock(cart));
    }

    @Test
    void cartsWithPassengersCannotBeEmptied() {
        api.registerCartType(MockBukkit.createMockPlugin("seats"), new SeatCart());
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.OAK_STAIRS);
        PlayerMock rider = newPlayer();
        assertTrue(cart.addPassenger(rider), "前置：该类型允许乘坐");

        sneakRightClick(player, cart);

        assertEquals(Material.OAK_STAIRS, managed(cart).blockType());
        assertEquals(0, countInInventory(player, Material.OAK_STAIRS));
    }

    @Test
    void takingOffClearsTheTypesDataButKeepsOtherPluginsData() {
        api.registerCartType(MockBukkit.createMockPlugin("seats"), new SeatCart());
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.OAK_STAIRS);
        NamespacedKey typeData = new NamespacedKey("seats", "wear");
        NamespacedKey otherData = new NamespacedKey("someoneelse", "owner");
        cart.getPersistentDataContainer().set(typeData, PersistentDataType.INTEGER, 3);
        cart.getPersistentDataContainer().set(otherData, PersistentDataType.STRING, "Steve");

        sneakRightClick(player, cart);

        assertFalse(cart.getPersistentDataContainer().has(typeData), "类型命名空间下的数据随方块一起清除");
        assertTrue(cart.getPersistentDataContainer().has(otherData), "其他插件的数据不受影响");
    }

    @Test
    void cartsOfAnUninstalledTypeStillGiveTheirBlockBack() {
        api.registerCartType(MockBukkit.createMockPlugin("seats"), new SeatCart());
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.OAK_STAIRS);
        api.unregisterCartType(SeatCart.ID);

        assertTrue(sneakRightClick(player, cart).isCancelled());

        assertEquals(1, countInInventory(player, Material.OAK_STAIRS), "类型不在了，方块也不会丢");
        assertNull(api.getCart(cart));
    }

    @Test
    void sneakClickingAnEmptyCartWithEmptyHandsDoesNothing() {
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();

        assertFalse(sneakRightClick(player, cart).isCancelled());
        assertNull(api.getCart(cart));
    }

    @Test
    void returnedItemKeepsItsName() {
        PlayerMock player = newPlayer();
        ItemStack named = new ItemStack(Material.STONE);
        named.editMeta(meta -> meta.customName(net.kyori.adventure.text.Component.text("基石")));
        Minecart cart = placeOnNewCart(player, named);

        sneakRightClick(player, cart);

        ItemStack returned = findInInventory(player, Material.STONE);
        assertNotNull(returned);
        assertEquals(net.kyori.adventure.text.Component.text("基石"), returned.getItemMeta().customName());
    }

    /** 测试用扩展类型：可以坐人的楼梯矿车。 */
    static final class SeatCart implements CartType {
        static final NamespacedKey ID = new NamespacedKey("seats", "stairs");

        @Override
        public NamespacedKey id() {
            return ID;
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.OAK_STAIRS);
        }

        @Override
        public boolean allowsPassengers() {
            return true;
        }
    }
}
