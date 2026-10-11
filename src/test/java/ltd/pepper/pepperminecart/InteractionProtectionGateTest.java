package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import ltd.pepper.pepperminecart.cooldown.InteractionCooldown;
import ltd.pepper.pepperminecart.impl.CartContextImpl;
import ltd.pepper.pepperminecart.interaction.InteractionListener;
import ltd.pepper.pepperminecart.protection.ClaimProtection;
import ltd.pepper.pepperminecart.protection.ProtectionBackend;
import ltd.pepper.pepperminecart.protection.ProtectionGateway;
import ltd.pepper.pepperminecart.registry.CartTypeRegistry;
import ltd.pepper.pepperminecart.registry.handler.DispenserCartHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RedstoneRail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.meta.BlockStateMetaMock;

/**
 * 补上盲区的两处接线（用假 Claim 策略驱动，验证「真的被闸住」）：
 * <ol>
 *   <li>打开矿车上的虚拟方块界面（#3）：被拒则取消交互且**不打开界面**；策略不把关的材质放行；
 *       原版特殊矿车不双判；{@code respect-protection: false} 时短路放行。</li>
 *   <li>发射器矿车压充能激活轨弹出（#4）：环境来源被拒则不弹出（无玩家无提示）。</li>
 * </ol>
 */
class InteractionProtectionGateTest {

    private ServerMock server;
    private World world;
    private PepperMinecartPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("gate-test");
        plugin.getConfig().set("interaction-cooldown-ms", 0);
        plugin.pluginConfig().apply(plugin.getConfig().getValues(true));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 假 Claim 策略：按预设判决并记录调用（观察「谁被问、问的是哪个旗标」）。 */
    private static final class FakeClaim implements ClaimProtection {

        final List<String> calls = new ArrayList<>();
        Optional<String> guiFlag = Optional.empty();
        boolean guiDenied;
        boolean environmentDenied;

        @Override
        public boolean denied(Player player, Location location, String flagId) {
            calls.add("denied:" + flagId);
            return guiDenied;
        }

        @Override
        public Optional<String> blockUseFlag(Material material) {
            calls.add("use:" + material.name());
            return guiFlag;
        }

        @Override
        public boolean environmentDenied(Location location, String flagId) {
            calls.add("env:" + flagId);
            return environmentDenied;
        }
    }

    /** 什么都不拒的事件策略替身（本类只验证 Claim 侧接线）。 */
    private static final ClaimProtection NOOP_EVENTS = new ClaimProtection() {
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
            return false;
        }
    };

    private ProtectionGateway gateway(ClaimProtection claim) {
        return new ProtectionGateway(() -> plugin.pluginConfig().respectProtection(),
                () -> ProtectionBackend.BOTH, NOOP_EVENTS, claim);
    }

    private InteractionListener listener(ClaimProtection claim) {
        return new InteractionListener(plugin, plugin.engine(), new CartTypeRegistry(null),
                plugin.pluginConfig(), new InteractionCooldown(plugin.pluginConfig()), gateway(claim));
    }

    private RideableMinecart spawnCart() {
        return world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
    }

    private RideableMinecart placeBlock(PlayerMock player, Material material) {
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        player.getInventory().setItemInMainHand(new ItemStack(material, 1));
        PlayerInteractEntityEvent place =
                new PlayerInteractEntityEvent(player, cart, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        assertTrue(place.isCancelled(), "前置：潜行 + 手持方块应放置成功");
        player.setSneaking(false);
        return cart;
    }

    /** 代理 Player：记录 open* 调用（MockBukkit 的 openWorkbench 等未实现，无法用真实 PlayerMock 观察）。 */
    private Player openRecordingPlayer(PlayerMock real, AtomicReference<String> opened) {
        InventoryView dummy = real.openInventory(Bukkit.createInventory(null, 9));
        assertNotNull(dummy, "前置：MockBukkit 应能创建 InventoryView");
        return (Player) Proxy.newProxyInstance(
                InteractionProtectionGateTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("open")
                            && method.getReturnType() == InventoryView.class) {
                        opened.set(method.getName());
                        return dummy;
                    }
                    switch (method.getName()) {
                        case "hasPermission":
                            return true;
                        case "isSneaking":
                            return false;
                        case "getUniqueId":
                            return real.getUniqueId();
                        case "getInventory":
                            return real.getInventory();
                        case "getServer":
                            return server;
                        default:
                            break;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    if (method.getReturnType() == double.class) {
                        return 0.0d;
                    }
                    if (method.getReturnType() == float.class) {
                        return 0.0f;
                    }
                    return null;
                });
    }

    // ---- 接线 #3：打开矿车上的虚拟方块界面 ----

    @Test
    void deniedGuiOpenCancelsInteractAndDoesNotOpenScreen() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.CRAFTING_TABLE);
        FakeClaim claim = new FakeClaim();
        claim.guiFlag = Optional.of("table");
        claim.guiDenied = true;
        AtomicReference<String> opened = new AtomicReference<>();

        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(
                openRecordingPlayer(player, opened), cart, EquipmentSlot.HAND);
        listener(claim).onInteractEntity(e);

        assertTrue(e.isCancelled(), "界面被拒时交互必须取消");
        assertNull(opened.get(), "界面被拒时不得打开界面（不得走 safeInteract）");
        assertEquals(List.of("use:CRAFTING_TABLE", "denied:table"), claim.calls,
                "先按矿车上的方块材质路由旗标，再以该 id 判定");
    }

    @Test
    void guiOpenProceedsWhenStrategyDoesNotGateMaterial() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.CRAFTING_TABLE);
        FakeClaim claim = new FakeClaim();
        claim.guiFlag = Optional.empty();
        AtomicReference<String> opened = new AtomicReference<>();

        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(
                openRecordingPlayer(player, opened), cart, EquipmentSlot.HAND);
        listener(claim).onInteractEntity(e);

        assertEquals("openWorkbench", opened.get(), "策略不把关该材质 → 照常打开界面");
        assertEquals(List.of("use:CRAFTING_TABLE"), claim.calls, "不把关时不得再问 denied");
    }

    @Test
    void guiOpenForPlainBlockIsNotGated() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.STONE);
        FakeClaim claim = new FakeClaim();
        claim.guiDenied = true; // 拒一切界面旗标：若实现编造旗标去判，本用例会失败
        AtomicReference<String> opened = new AtomicReference<>();

        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(
                openRecordingPlayer(player, opened), cart, EquipmentSlot.HAND);
        listener(claim).onInteractEntity(e);

        assertFalse(e.isCancelled(), "普通方块矿车没有界面：放行原版行为");
        assertNull(opened.get());
        assertEquals(List.of("use:STONE"), claim.calls, "官方清单外材质直接放行");
    }

    @Test
    void vanillaSpecialCartGuiIsNotDoubleGated() {
        PlayerMock player = server.addPlayer();
        StorageMinecart cart = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        FakeClaim claim = new FakeClaim();
        claim.guiFlag = Optional.of("container");
        claim.guiDenied = true;
        AtomicReference<String> opened = new AtomicReference<>();

        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(
                openRecordingPlayer(player, opened), cart, EquipmentSlot.HAND);
        listener(claim).onInteractEntity(e);

        assertFalse(e.isCancelled(), "未受管的原版特殊矿车不受管：放行原版行为");
        assertTrue(claim.calls.isEmpty(), "原版箱/漏斗矿车由 PepperClaim 自身监听器把关，本项目不得双判");
    }

    @Test
    void respectProtectionFalseBypassesGuiGate() {
        plugin.getConfig().set("respect-protection", false);
        plugin.pluginConfig().apply(plugin.getConfig().getValues(true));

        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.CRAFTING_TABLE);
        FakeClaim claim = new FakeClaim();
        claim.guiFlag = Optional.of("table");
        claim.guiDenied = true;
        AtomicReference<String> opened = new AtomicReference<>();

        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(
                openRecordingPlayer(player, opened), cart, EquipmentSlot.HAND);
        listener(claim).onInteractEntity(e);

        assertEquals("openWorkbench", opened.get(), "关闭保护协作后界面照常打开");
        assertTrue(claim.calls.isEmpty(), "关闭保护协作后新盲区也不得咨询策略");
    }

    // ---- 接线 #4：发射器矿车压充能激活轨弹出 ----

    @Test
    void deniedEnvironmentEjectSuppressesDispense() {
        RideableMinecart cart = spawnCart();
        FakeClaim claim = new FakeClaim();
        claim.environmentDenied = true;
        DispenserCartHandler handler =
                new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions(), gateway(claim));
        CartContextImpl ctx = ejectReady(cart, handler);

        handler.onMove(ctx);

        assertTrue(droppedItems().isEmpty(), "环境弹出被拒时不得弹出物品");
        assertEquals(Material.DIAMOND, liveDispenser(cart).getInventory().getItem(0).getType(),
                "被拒时库存不得扣减");
        assertEquals(List.of("env:" + ClaimProtection.FLAG_ENVIRONMENT_EJECT), claim.calls,
                "环境来源只问领地级旗标 mobgriefing");
    }

    @Test
    void allowedEnvironmentEjectDispenses() {
        RideableMinecart cart = spawnCart();
        FakeClaim claim = new FakeClaim();
        claim.environmentDenied = false;
        DispenserCartHandler handler =
                new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions(), gateway(claim));
        CartContextImpl ctx = ejectReady(cart, handler);

        handler.onMove(ctx);

        assertFalse(droppedItems().isEmpty(), "对照组：允许时应弹出物品（证明闸门确实在判定）");
        assertEquals(List.of("env:" + ClaimProtection.FLAG_ENVIRONMENT_EJECT), claim.calls);
    }

    @Test
    void respectProtectionFalseBypassesEjectGate() {
        plugin.getConfig().set("respect-protection", false);
        plugin.pluginConfig().apply(plugin.getConfig().getValues(true));

        RideableMinecart cart = spawnCart();
        FakeClaim claim = new FakeClaim();
        claim.environmentDenied = true;
        DispenserCartHandler handler =
                new DispenserCartHandler(plugin.pluginConfig(), plugin.sessions(), gateway(claim));
        CartContextImpl ctx = ejectReady(cart, handler);

        handler.onMove(ctx);

        assertFalse(droppedItems().isEmpty(), "关闭保护协作后照常弹出");
        assertTrue(claim.calls.isEmpty(), "关闭保护协作后不得咨询策略");
    }

    // ---- helpers ----

    /** 装配一辆「压着充能激活轨、容器里有 1 颗钻石」的发射器矿车（走实时容器会话，与既有发射测试同路径）。 */
    private CartContextImpl ejectReady(RideableMinecart cart, DispenserCartHandler handler) {
        PlayerMock player = server.addPlayer();
        plugin.engine().addCart(cart, handler, dispenserItemWithDiamond());
        CartContextImpl ctx = plugin.engine().context(cart);
        assertNotNull(handler.onInteract(player, ctx), "前置：打开发射器容器界面");
        assertNotNull(plugin.sessions().liveContainer(cart), "前置：应有实时容器会话");
        liveDispenser(cart).getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
        powerRailAt(cart.getLocation());
        return ctx;
    }

    private org.bukkit.block.Dispenser liveDispenser(RideableMinecart cart) {
        return (org.bukkit.block.Dispenser) plugin.sessions().liveContainer(cart);
    }

    private static ItemStack dispenserItemWithDiamond() {
        ItemStack item = new ItemStack(Material.DISPENSER, 1);
        BlockStateMeta meta = new BlockStateMetaMock(Material.DISPENSER);
        org.bukkit.block.Dispenser dispenser = (org.bukkit.block.Dispenser) meta.getBlockState();
        dispenser.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
        meta.setBlockState(dispenser);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 在矿车所在方块写入「充能激活铁轨」。MockBukkit 的激活铁轨方块数据（RailDataMock）只实现
     * {@code Rail} 而不实现 {@code RedstoneRail}，与真实服务端不一致，故以代理提供真实形状的数据。
     */
    private void powerRailAt(Location location) {
        Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        RedstoneRail rail = (RedstoneRail) Proxy.newProxyInstance(
                InteractionProtectionGateTest.class.getClassLoader(),
                new Class<?>[]{RedstoneRail.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isPowered":
                            return true;
                        case "getMaterial":
                        case "getPlacementMaterial":
                            return Material.ACTIVATOR_RAIL;
                        case "clone":
                            return proxy;
                        case "getAsString":
                            return "minecraft:activator_rail[powered=true]";
                        case "toString":
                            return "PoweredActivatorRail";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            break;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
        block.setType(Material.ACTIVATOR_RAIL);
        block.setBlockData(rail);
        assertTrue(block.getBlockData() instanceof RedstoneRail, "前置：充能激活铁轨已写入世界");
    }

    private List<Item> droppedItems() {
        List<Item> out = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Item item) {
                out.add(item);
            }
        }
        return out;
    }
}
