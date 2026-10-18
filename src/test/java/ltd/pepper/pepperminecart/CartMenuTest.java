package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.event.entity.EntityRemoveEvent.Cause;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 绑定在矿车上的界面：与原版矿车容器一样，矿车不在了、方块没了或人走远了就关闭。 */
class CartMenuTest extends CartTestSupport {

    private PlayerMock player;
    private Minecart cart;
    private Inventory menu;
    private final AtomicInteger closeCalls = new AtomicInteger();

    private void openMenuOnStoneCart() {
        player = newPlayer();
        cart = placeOnNewCart(player, Material.STONE);
        menu = server.createInventory(null, InventoryType.CHEST);
        assertNotNull(managed(cart).openInventory(player, menu, closeCalls::incrementAndGet));
    }

    private boolean menuOpen() {
        return player.getOpenInventory().getTopInventory() == menu;
    }

    @Test
    void menuStaysOpenWhileThePlayerIsNearby() {
        openMenuOnStoneCart();

        server.getScheduler().performTicks(5);

        assertTrue(menuOpen());
        assertEquals(0, closeCalls.get());
    }

    @Test
    void walkingAwayClosesTheMenu() {
        openMenuOnStoneCart();

        player.teleport(new Location(world, 30, 64, 0));
        server.getScheduler().performOneTick();

        assertNotSame(menu, player.getOpenInventory().getTopInventory());
        assertEquals(1, closeCalls.get(), "关闭回调恰好一次");
    }

    @Test
    void takingTheBlockOffClosesTheMenu() {
        openMenuOnStoneCart();
        PlayerMock other = newPlayer();

        sneakRightClick(other, cart);

        assertNotSame(menu, player.getOpenInventory().getTopInventory());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void destroyingTheCartClosesTheMenuBeforeTheBlockDrops() {
        openMenuOnStoneCart();

        removeCart(cart, Cause.DEATH);

        assertNotSame(menu, player.getOpenInventory().getTopInventory());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void closingTheMenuNormallyRunsTheCallbackOnce() {
        openMenuOnStoneCart();

        player.closeInventory();
        server.getScheduler().performOneTick();

        assertEquals(1, closeCalls.get());
    }

    @Test
    void disablingThePluginClosesAndSavesEveryMenu() {
        openMenuOnStoneCart();

        server.getPluginManager().disablePlugin(plugin);

        assertEquals(1, closeCalls.get());
    }

    @Test
    void containerCartsOpenTheirInventoryOnRightClick() {
        player = newPlayer();
        Minecart barrel = placeOnNewCart(player, Material.BARREL);
        PlayerMock other = newPlayer();

        assertTrue(rightClick(player, barrel).isCancelled());
        rightClick(other, barrel);

        assertEquals(InventoryType.BARREL, player.getOpenInventory().getTopInventory().getType());
        assertSame(player.getOpenInventory().getTopInventory(), other.getOpenInventory().getTopInventory());
    }

    @Test
    void forbiddenContainerPolicyKeepsTheBarrelOnTheCart() {
        setConfig("container-pickup-policy", "FORBIDDEN");
        player = newPlayer();
        Minecart barrel = placeOnNewCart(player, Material.BARREL);

        sneakRightClick(player, barrel);

        assertEquals(Material.BARREL, managed(barrel).blockType());
        assertEquals(0, countInInventory(player, Material.BARREL));
        assertEquals("容器类方块取下已在配置中禁止", player.nextMessage());
    }
}
