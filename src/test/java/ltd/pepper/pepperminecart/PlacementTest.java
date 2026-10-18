package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ltd.pepper.pepperminecart.type.PlainBlockCart;
import ltd.pepper.pepperminecart.type.WorkstationCart;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 潜行右键空矿车放上方块。 */
class PlacementTest extends CartTestSupport {

    @Test
    void placingConsumesOneBlockAndShowsItOnTheCart() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, new ItemStack(Material.GRINDSTONE, 3));

        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(WorkstationCart.ID, managed(cart).type().id());
        assertEquals(Material.GRINDSTONE, managed(cart).blockType());
        assertEquals(Material.GRINDSTONE, cart.getDisplayBlockData().getMaterial());
        assertEquals(6, cart.getDisplayBlockOffset(), "显示高度取自 display-block-offset");
    }

    @Test
    void blocksWithoutADedicatedTypeBecomePlainBlockCarts() {
        Minecart cart = placeOnNewCart(newPlayer(), Material.STONE);

        assertEquals(PlainBlockCart.ID, managed(cart).type().id());
        assertEquals(Material.STONE, cart.getDisplayBlockData().getMaterial());
    }

    @Test
    void creativePlayersKeepTheirBlock() {
        PlayerMock player = newPlayer();
        player.setGameMode(GameMode.CREATIVE);

        placeOnNewCart(player, new ItemStack(Material.STONE, 3));

        assertEquals(3, player.getInventory().getItemInMainHand().getAmount(), "与原版放置方块一致，创造模式不消耗");
    }

    @Test
    void blacklistedBlocksAreNotPlaced() {
        setConfig("disabled-blocks", List.of("DIAMOND_BLOCK"));
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_BLOCK, 2));

        assertFalse(sneakRightClick(player, cart).isCancelled());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertNull(api.getCart(cart));
    }

    @Test
    void allowListModeOnlyAcceptsListedBlocks() {
        setConfig("allow-all-blocks", false);
        setConfig("enabled-blocks", List.of("STONE"));
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();

        player.getInventory().setItemInMainHand(new ItemStack(Material.GRINDSTONE));
        sneakRightClick(player, cart);
        assertNull(api.getCart(cart), "名单外的方块不能放");

        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        sneakRightClick(player, cart);
        assertEquals(Material.STONE, managed(cart).blockType());
    }

    @Test
    void itemsThatAreNotBlocksAreIgnored() {
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND));

        assertFalse(sneakRightClick(player, cart).isCancelled());
        assertNull(api.getCart(cart));
    }

    @Test
    void cartsShowingSomeoneElsesDisplayBlockAreLeftAlone() {
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();
        cart.setDisplayBlockData(Material.GOLD_BLOCK.createBlockData()); // 例如地图作者用命令设置的显示方块
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));

        assertFalse(sneakRightClick(player, cart).isCancelled());
        assertNull(api.getCart(cart));
        assertEquals(Material.GOLD_BLOCK, cart.getDisplayBlockData().getMaterial(), "别人的显示方块保持不变");
        assertTrue(droppedItems(Material.GOLD_BLOCK).isEmpty() && countInInventory(player, Material.GOLD_BLOCK) == 0,
                "也不能把别人的显示方块“取下”变成物品");
    }

    @Test
    void clickingWithoutSneakingIsLeftToVanilla() {
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));

        assertFalse(rightClick(player, cart).isCancelled(), "不潜行右键空矿车是原版的乘坐");
        assertNull(api.getCart(cart));
    }

    @Test
    void rejectedPlacementDoesNotUseUpTheInteractionCooldown() {
        setConfig("interaction-cooldown-ms", 5000);
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();

        player.getInventory().setItemInMainHand(new ItemStack(Material.BEDROCK)); // 默认黑名单
        sneakRightClick(player, cart);
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        sneakRightClick(player, cart);

        assertEquals(Material.STONE, managed(cart).blockType(), "被拒绝的点击不应占用冷却");
    }

    @Test
    void placingTooFastIsSwallowedWithoutConsumingAnything() {
        setConfig("interaction-cooldown-ms", 5000);
        PlayerMock player = newPlayer();
        placeOnNewCart(player, new ItemStack(Material.STONE, 2));
        RideableMinecart second = world.spawn(cartLocation().add(3, 0, 0), RideableMinecart.class);

        assertTrue(sneakRightClick(player, second).isCancelled());
        assertNull(api.getCart(second));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void interactionsCancelledByOtherPluginsAreIgnored() {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void cancel(PlayerInteractEntityEvent event) {
                event.setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("cancels-interactions"));
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 3));

        sneakRightClick(player, cart);

        assertNull(api.getCart(cart));
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void playersWithoutPermissionCannotPlace() {
        PlayerMock player = newPlayer();
        player.addAttachment(plugin, "pepperminecart.use", false);
        RideableMinecart cart = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));

        assertFalse(sneakRightClick(player, cart).isCancelled());
        assertNull(api.getCart(cart));
    }
}
