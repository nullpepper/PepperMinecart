package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.api.event.CartBlockPlaceEvent;
import ltd.pepper.pepperminecart.api.event.CartBlockTakeOffEvent;
import ltd.pepper.pepperminecart.cart.CartListener;
import ltd.pepper.pepperminecart.cart.CartManager;
import ltd.pepper.pepperminecart.cart.CartMenus;
import ltd.pepper.pepperminecart.cooldown.InteractionCooldown;
import ltd.pepper.pepperminecart.protection.ClaimProtection;
import ltd.pepper.pepperminecart.protection.ProtectionBackend;
import ltd.pepper.pepperminecart.protection.ProtectionGateway;
import ltd.pepper.pepperminecart.type.PlainBlockCart;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 领地保护：放上、取下、打开矿车上的方块界面都要经过保护判定。 */
class ProtectionIntegrationTest extends CartTestSupport {

    // ---- 伪事件后端（默认启用）：别的保护插件取消事件即拒绝 ----

    /** 模拟一个保护插件：取消所有该类事件。 */
    private void cancelEvery(Class<? extends Event> eventClass) {
        server.getPluginManager().registerEvent(eventClass, new Listener() {
        }, EventPriority.NORMAL, (listener, event) -> {
            if (eventClass.isInstance(event)) {
                ((Cancellable) event).setCancelled(true);
            }
        }, MockBukkit.createMockPlugin("claims"));
    }

    @Test
    void cancelledCartBlockPlaceEventStopsThePlacement() {
        cancelEvery(CartBlockPlaceEvent.class);
        assertPlacementBlocked();
    }

    @Test
    void cancelledBlockPlaceEventStopsThePlacement() {
        cancelEvery(BlockPlaceEvent.class);
        assertPlacementBlocked();
    }

    @Test
    void cancelledCartBlockTakeOffEventStopsTheTakeOff() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.STONE);
        cancelEvery(CartBlockTakeOffEvent.class);

        sneakRightClick(player, cart);

        assertEquals(Material.STONE, managed(cart).blockType());
    }

    @Test
    void cancelledBlockBreakEventStopsTheTakeOff() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.STONE);
        cancelEvery(BlockBreakEvent.class);

        sneakRightClick(player, cart);

        assertEquals(Material.STONE, managed(cart).blockType());
    }

    private void assertPlacementBlocked() {
        PlayerMock player = newPlayer();
        RideableMinecart cart = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 3));

        sneakRightClick(player, cart);

        assertNull(api.getCart(cart));
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount(), "物品不得被消耗");
    }

    // ---- 打开矿车上的方块界面（PepperClaim 后端） ----

    /** 按预设判决并记录被问了什么的 Claim 策略。 */
    private static final class FakeClaim implements ClaimProtection {
        final List<String> calls = new ArrayList<>();
        Optional<String> guiFlag = Optional.empty();
        boolean deny;

        @Override
        public boolean denied(Player player, Location location, String flagId) {
            calls.add("denied:" + flagId);
            return deny;
        }

        @Override
        public Optional<String> blockUseFlag(Material material) {
            calls.add("use:" + material.name());
            return guiFlag;
        }

        @Override
        public boolean environmentDenied(Location location, String flagId) {
            calls.add("env:" + flagId);
            return deny;
        }
    }

    private static final ClaimProtection ALLOW_ALL = new FakeClaim();

    /** 与插件共用类型注册表、但使用假 Claim 策略的一套交互处理；直接调用，不注册为监听器。 */
    private CartListener listenerWith(FakeClaim claim) {
        ProtectionGateway gateway = new ProtectionGateway(() -> plugin.pluginConfig().respectProtection(),
                () -> ProtectionBackend.BOTH, ALLOW_ALL, claim);
        CartManager manager = new CartManager(plugin, plugin.pluginConfig(), plugin.registry(), new CartMenus(), gateway,
                new InteractionCooldown(plugin.pluginConfig()), new PlainBlockCart());
        return new CartListener(manager);
    }

    /** 右键就“打开界面”的测试类型（MockBukkit 打不开真实的工作台界面）。 */
    private final AtomicInteger opened = new AtomicInteger();

    private Minecart tableCart(PlayerMock player) {
        api.registerCartType(MockBukkit.createMockPlugin("tables"), new CartType() {
            @Override
            public NamespacedKey id() {
                return new NamespacedKey("tables", "table");
            }

            @Override
            public Set<Material> blocks() {
                return Set.of(Material.FLETCHING_TABLE);
            }

            @Override
            public boolean onUse(ManagedCart cart, Player user) {
                opened.incrementAndGet();
                return true;
            }
        });
        return placeOnNewCart(player, Material.FLETCHING_TABLE);
    }

    private PlayerInteractEntityEvent rightClickWith(CartListener listener, Player player, Minecart cart) {
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND);
        listener.onInteract(event);
        return event;
    }

    @Test
    void deniedClaimKeepsTheMenuClosed() {
        PlayerMock player = newPlayer();
        Minecart cart = tableCart(player);
        FakeClaim claim = new FakeClaim();
        claim.guiFlag = Optional.of("table");
        claim.deny = true;

        assertTrue(rightClickWith(listenerWith(claim), player, cart).isCancelled());

        assertEquals(0, opened.get());
        assertEquals(List.of("use:FLETCHING_TABLE", "denied:table"), claim.calls, "按方块材质路由到对应旗标");
    }

    @Test
    void blocksTheClaimPluginDoesNotGuardOpenNormally() {
        PlayerMock player = newPlayer();
        Minecart cart = tableCart(player);
        FakeClaim claim = new FakeClaim();
        claim.deny = true; // 但没有旗标管这个方块

        rightClickWith(listenerWith(claim), player, cart);

        assertEquals(1, opened.get());
    }

    @Test
    void vanillaMinecartMenusAreLeftToTheClaimPluginsOwnChecks() {
        PlayerMock player = newPlayer();
        Minecart chestMinecart = placeOnNewCart(player, Material.CHEST);
        FakeClaim claim = new FakeClaim();
        claim.guiFlag = Optional.of("container");
        claim.deny = true;

        assertFalse(rightClickWith(listenerWith(claim), player, chestMinecart).isCancelled(), "原版箱子矿车界面照常打开");
        assertTrue(claim.calls.isEmpty(), "原版交互由保护插件自己的监听器把关，这里不重复判定");
    }

    @Test
    void respectProtectionOffSkipsEveryCheck() {
        setConfig("respect-protection", false);
        PlayerMock player = newPlayer();
        Minecart cart = tableCart(player);
        FakeClaim claim = new FakeClaim();
        claim.guiFlag = Optional.of("table");
        claim.deny = true;

        rightClickWith(listenerWith(claim), player, cart);

        assertEquals(1, opened.get());
        assertTrue(claim.calls.isEmpty());
    }
}
