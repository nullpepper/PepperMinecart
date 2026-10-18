package ltd.pepper.pepperminecart.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import ltd.pepper.pepperminecart.config.TestConfigs;
import ltd.pepper.pepperminecart.protection.ClaimProtection;
import ltd.pepper.pepperminecart.protection.ProtectionBackend;
import ltd.pepper.pepperminecart.protection.ProtectionGateway;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RedstoneRail;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class DispenserCartTest extends TypeTestSupport {

    /** 只记录与按预设拒绝“环境来源”动作的保护策略。 */
    private static final class FakeProtection implements ClaimProtection {
        final List<String> calls = new ArrayList<>();
        boolean denyEnvironment;

        @Override
        public boolean denied(Player player, Location location, String flagId) {
            return false;
        }

        @Override
        public Optional<String> blockUseFlag(Material material) {
            return Optional.empty();
        }

        @Override
        public boolean environmentDenied(Location location, String flagId) {
            calls.add(flagId);
            return denyEnvironment;
        }
    }

    private final FakeProtection claims = new FakeProtection();
    private boolean respectProtection = true;

    private DispenserCart dispenser() {
        ClaimProtection allowAll = new FakeProtection();
        ProtectionGateway gateway = new ProtectionGateway(() -> respectProtection, () -> ProtectionBackend.BOTH,
                allowAll, claims);
        return new DispenserCart(TestConfigs.defaults(), gateway);
    }

    private InMemoryCart loadedDispenserOnRail(DispenserCart type, boolean powered, ItemStack... contents) {
        InMemoryCart cart = new InMemoryCart(spawnCart(), type, containerWith(Material.DISPENSER, contents));
        placeActivatorRail(cart.minecart().getLocation(), powered);
        return cart;
    }

    @Test
    void poweredActivatorRailEjectsOneItem() {
        DispenserCart type = dispenser();
        InMemoryCart cart = loadedDispenserOnRail(type, true, new ItemStack(Material.ARROW, 3));

        type.onMove(cart);

        assertEquals(1, droppedCount(Material.ARROW));
        assertEquals(2, count(BlockItems.contents(cart.blockItem()), Material.ARROW), "弹出的那一件从库存中扣除");
        assertTrue(cart.isCoolingDown(new org.bukkit.NamespacedKey("pepperminecart", "dispenser-cooldown")));
    }

    @Test
    void cooldownSpacesOutTheEjections() {
        DispenserCart type = dispenser();
        InMemoryCart cart = loadedDispenserOnRail(type, true, new ItemStack(Material.ARROW, 3));

        type.onMove(cart);
        type.onMove(cart);

        assertEquals(1, droppedCount(Material.ARROW));
    }

    @Test
    void unpoweredRailDoesNothing() {
        DispenserCart type = dispenser();
        InMemoryCart cart = loadedDispenserOnRail(type, false, new ItemStack(Material.ARROW, 3));

        type.onMove(cart);

        assertEquals(0, droppedCount(Material.ARROW));
    }

    @Test
    void emptyDispenserDoesNotStartTheCooldown() {
        DispenserCart type = dispenser();
        InMemoryCart cart = loadedDispenserOnRail(type, true);

        type.onMove(cart);
        cart.setBlockItem(containerWith(Material.DISPENSER, new ItemStack(Material.ARROW)));
        type.onMove(cart);

        assertEquals(1, droppedCount(Material.ARROW), "空发射不消耗冷却，补货后立即可以发射");
    }

    @Test
    void openMenuShowsTheEjectionImmediately() {
        DispenserCart type = dispenser();
        InMemoryCart cart = loadedDispenserOnRail(type, true, new ItemStack(Material.ARROW, 3));
        var player = server.addPlayer();
        type.onUse(cart, player);

        type.onMove(cart);

        assertEquals(2, count(player.getOpenInventory().getTopInventory().getContents(), Material.ARROW),
                "正在查看的界面里同步少了一件");
    }

    @Test
    void landClaimsCanStopTheEjection() {
        claims.denyEnvironment = true;
        DispenserCart type = dispenser();
        InMemoryCart cart = loadedDispenserOnRail(type, true, new ItemStack(Material.ARROW, 3));

        type.onMove(cart);

        assertEquals(0, droppedCount(Material.ARROW));
        assertEquals(3, count(BlockItems.contents(cart.blockItem()), Material.ARROW));
        assertEquals(List.of(ClaimProtection.FLAG_ENVIRONMENT_EJECT), claims.calls, "环境来源只问领地级旗标");
    }

    @Test
    void protectionIsNotConsultedWhenRespectProtectionIsOff() {
        claims.denyEnvironment = true;
        respectProtection = false;
        DispenserCart type = dispenser();
        InMemoryCart cart = loadedDispenserOnRail(type, true, new ItemStack(Material.ARROW, 3));

        type.onMove(cart);

        assertEquals(1, droppedCount(Material.ARROW));
        assertTrue(claims.calls.isEmpty());
    }

    private int droppedCount(Material material) {
        return world.getEntitiesByClass(Item.class).stream()
                .map(Item::getItemStack)
                .filter(item -> item.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    /**
     * MockBukkit 的激活铁轨方块数据只实现了 Rail 而没有实现 RedstoneRail（与真实服务端不同），
     * 这里用代理提供“充能/未充能的激活铁轨”。
     */
    private void placeActivatorRail(Location location, boolean powered) {
        RedstoneRail rail = (RedstoneRail) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RedstoneRail.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isPowered" -> powered;
                    case "getMaterial", "getPlacementMaterial" -> Material.ACTIVATOR_RAIL;
                    case "clone" -> proxy;
                    case "getAsString" -> "minecraft:activator_rail[powered=" + powered + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ActivatorRail(powered=" + powered + ")";
                    default -> method.getReturnType() == boolean.class ? false
                            : method.getReturnType() == int.class ? 0 : null;
                });
        Block block = location.getBlock();
        block.setType(Material.ACTIVATOR_RAIL);
        block.setBlockData(rail);
    }
}
