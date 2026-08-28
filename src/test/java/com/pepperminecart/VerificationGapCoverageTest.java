package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.config.ContainerPickupPolicy;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.registry.handler.WorkstationHandler;
import com.pepperminecart.storage.CartData;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 走查缺口定向补齐测试（验证交付，不修改 src/main）：
 * 受管矿车禁止乘坐、工作站七种界面打开、原版特殊矿车取下开关、
 * HOPPER/TNT/命令方块转换、容器 FORBIDDEN 策略、配置键默认值与解析。
 */
class VerificationGapCoverageTest {

    private ServerMock server;
    private PepperMinecartPlugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("gap-test");
        plugin.getConfig().set("interaction-cooldown-ms", 0);
        plugin.getConfig().set("sounds.place", false);
        plugin.getConfig().set("sounds.take-off", false);
        applyConfig();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void applyConfig() {
        plugin.pluginConfig().apply(plugin.getConfig());
    }

    private void setConfig(String key, Object value) {
        plugin.getConfig().set(key, value);
        applyConfig();
    }

    private RideableMinecart spawnCart() {
        return world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
    }

    private PlayerInteractEntityEvent interact(PlayerMock player, Entity clicked) {
        PlayerInteractEntityEvent e = new PlayerInteractEntityEvent(player, clicked, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return e;
    }

    private RideableMinecart placeBlock(PlayerMock player, Material material, int amount) {
        RideableMinecart cart = spawnCart();
        player.setSneaking(true);
        player.getInventory().setItemInMainHand(new ItemStack(material, amount));
        PlayerInteractEntityEvent e = interact(player, cart);
        assertTrue(e.isCancelled(), "潜行 + 手持方块应触发放置");
        return cart;
    }

    private int countInInventory(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == material) {
                total += it.getAmount();
            }
        }
        return total;
    }

    private List<Item> droppedItems(Material material) {
        return world.getEntities().stream()
                .filter(en -> en instanceof Item i && i.getItemStack().getType() == material)
                .map(en -> (Item) en)
                .toList();
    }

    /** 统计玩家身上与地面上的钻石总量（含容器物品 BlockStateMeta 内嵌内容），用于断言不丢失、不复制。 */
    private int preservedDiamondTotal(PlayerMock player) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            total += diamondCountIn(it);
        }
        for (Item drop : droppedItems(Material.DIAMOND)) {
            total += drop.getItemStack().getAmount();
        }
        return total;
    }

    private int diamondCountIn(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        int total = 0;
        if (item.getType() == Material.DIAMOND) {
            total += item.getAmount();
        }
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                && bsm.getBlockState() instanceof org.bukkit.block.Container container) {
            for (ItemStack content : container.getInventory().getContents()) {
                if (content != null && content.getType() == Material.DIAMOND) {
                    total += content.getAmount();
                }
            }
        }
        return total;
    }

    // ---- 缺口 7：受管矿车禁止乘坐（onMount 默认 allowRiding=false 取消 EntityMountEvent） ----

    @Test
    void mount_managedCartIsCancelled() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.GRINDSTONE, 1);

        EntityMountEvent e = new EntityMountEvent(player, cart);
        server.getPluginManager().callEvent(e);

        assertTrue(e.isCancelled(), "受管矿车默认应禁止乘坐");
    }

    @Test
    void mount_unmanagedCartIsAllowed() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();

        EntityMountEvent e = new EntityMountEvent(player, cart);
        server.getPluginManager().callEvent(e);

        assertFalse(e.isCancelled(), "非受管空矿车应放行原版乘坐");
    }

    // ---- 缺口 2：工作站七种界面打开 ----
    // MockBukkit 的 HumanEntityMock 对全部 open* 工作站方法抛 UnimplementedOperationException，
    // 因此用动态代理 Player 直接驱动 WorkstationHandler.onInteract，验证路由到正确的 open* 方法。

    @Test
    void workstation_onInteractRoutesToExpectedOpenMethod() {
        Map<Material, String> expected = Map.of(
                Material.CRAFTING_TABLE, "openWorkbench",
                Material.GRINDSTONE, "openGrindstone",
                Material.LOOM, "openLoom",
                Material.CARTOGRAPHY_TABLE, "openCartographyTable",
                Material.SMITHING_TABLE, "openSmithingTable",
                Material.STONECUTTER, "openStonecutter",
                Material.ENCHANTING_TABLE, "openEnchanting"
        );
        for (Map.Entry<Material, String> entry : expected.entrySet()) {
            PlayerMock real = server.addPlayer();
            RideableMinecart cart = placeBlock(real, entry.getKey(), 1);
            CartContext ctx = plugin.engine().contextOrNull(cart);
            assertTrue(ctx != null, entry.getKey() + " 应已受管");

            InventoryView dummyView = real.openInventory(Bukkit.createInventory(null, 9));
            assertTrue(dummyView != null, "前置：MockBukkit 应能创建 InventoryView");

            AtomicReference<String> opened = new AtomicReference<>();
            Player proxy = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    (p, method, args) -> {
                        if (method.getName().startsWith("open")) {
                            opened.set(method.getName());
                            return dummyView;
                        }
                        if (method.getName().equals("getUniqueId")) {
                            return UUID.randomUUID();
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

            boolean handled = new WorkstationHandler(plugin.sessions()).onInteract(proxy, ctx);

            assertTrue(handled, entry.getKey() + " 应由工作站处理器接管");
            assertEquals(entry.getValue(), opened.get(), entry.getKey() + " 应路由到正确的 open* 方法");
        }
    }

    // ---- 缺口 3/4：外来原版特殊矿车取下 + vanilla-carts.allow-pickup 开关 ----

    @Test
    void vanillaPickup_allowTrue_takesOffForeignStorageMinecartWithoutLosingContents() {
        setConfig("vanilla-carts.allow-pickup", true);
        PlayerMock player = server.addPlayer();
        StorageMinecart foreign = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        foreign.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        assertFalse(CartData.isManaged(foreign), "前置：外来矿车无插件 PDC");

        player.setSneaking(true);
        PlayerInteractEntityEvent e = interact(player, foreign);

        assertTrue(e.isCancelled(), "允许取下时潜行右键外来箱子矿车应被插件处理");
        List<Minecart> carts = world.getEntities().stream()
                .filter(en -> en instanceof Minecart)
                .map(en -> (Minecart) en)
                .toList();
        assertEquals(1, carts.size());
        assertEquals(EntityType.MINECART, carts.get(0).getType(), "取下后应还原为普通矿车");
        assertEquals(1, countInInventory(player, Material.CHEST), "箱子物品应返还给玩家");
        // 内容物两条合法路径：随箱子物品 BlockStateMeta 内嵌回收（MockBukkit 的 CHEST 物品带
        // BlockStateMetaMock；代码推断真实 Paper 上无模板外来矿车返回无 meta 物品，内容物走引擎预掉落）；
        // 无论哪条路径，总钻石数量必须保持 3（不丢失、不复制），且旧矿车原版库存被清空。
        assertEquals(3, preservedDiamondTotal(player), "内容物钻石总量不应丢失或复制");
        for (ItemStack it : foreign.getInventory().getContents()) {
            assertTrue(it == null || it.getType().isAir(), "旧矿车原版库存应已清空");
        }
    }

    @Test
    void vanillaPickup_allowFalse_ignoresForeignStorageMinecart() {
        setConfig("vanilla-carts.allow-pickup", false);
        PlayerMock player = server.addPlayer();
        StorageMinecart foreign = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);
        foreign.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));

        player.setSneaking(true);
        PlayerInteractEntityEvent e = interact(player, foreign);

        assertFalse(e.isCancelled(), "关闭开关后外来箱子矿车应放行原版行为，不被插件取下");
        assertEquals(EntityType.CHEST_MINECART, foreign.getType(), "矿车不应被还原");
        assertEquals(0, countInInventory(player, Material.CHEST), "玩家不应获得箱子物品");
    }

    // ---- 缺口 4：特殊矿车类型转换（HOPPER / TNT / 命令方块） ----

    @Test
    void place_hopperConvertsToHopperMinecartAndBack() {
        PlayerMock player = server.addPlayer();
        placeBlock(player, Material.HOPPER, 1);

        Minecart converted = singleMinecart();
        assertEquals(EntityType.HOPPER_MINECART, converted.getType(), "漏斗放置后应转换为漏斗矿车");

        player.setSneaking(true);
        interact(player, converted);

        assertEquals(EntityType.MINECART, singleMinecart().getType(), "取下后应还原为普通矿车");
        assertEquals(1, countInInventory(player, Material.HOPPER), "漏斗物品应回到背包");
    }

    @Test
    void place_tntConvertsToTntMinecartAndBack() {
        PlayerMock player = server.addPlayer();
        placeBlock(player, Material.TNT, 1);

        Minecart converted = singleMinecart();
        assertEquals(EntityType.TNT_MINECART, converted.getType(), "TNT 放置后应转换为 TNT 矿车");

        player.setSneaking(true);
        interact(player, converted);

        assertEquals(EntityType.MINECART, singleMinecart().getType(), "取下后应还原为普通矿车");
        assertEquals(1, countInInventory(player, Material.TNT), "TNT 物品应回到背包");
    }

    @Test
    void place_commandBlockConvertsToCommandMinecartAndBack() {
        PlayerMock player = server.addPlayer();
        placeBlock(player, Material.COMMAND_BLOCK, 1);

        Minecart converted = singleMinecart();
        assertEquals(EntityType.COMMAND_BLOCK_MINECART, converted.getType(), "命令方块放置后应转换为命令方块矿车");

        player.setSneaking(true);
        interact(player, converted);

        assertEquals(EntityType.MINECART, singleMinecart().getType(), "取下后应还原为普通矿车");
        assertEquals(1, countInInventory(player, Material.COMMAND_BLOCK), "命令方块物品应回到背包");
    }

    // ---- 缺口 5：容器 FORBIDDEN 策略对内置木桶生效 ----

    @Test
    void containerPickupPolicy_forbidden_blocksBarrelTakeOff() {
        setConfig("container-pickup-policy", "FORBIDDEN");
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = placeBlock(player, Material.BARREL, 1);

        player.setSneaking(true);
        PlayerInteractEntityEvent e = interact(player, cart);

        assertTrue(e.isCancelled(), "FORBIDDEN 策略应禁止取下容器矿车");
        assertTrue(CartData.isManaged(cart), "矿车应保持受管");
        assertTrue(new CartBlockDisplay(cart).has(), "显示方块应保留");
        assertEquals(0, countInInventory(player, Material.BARREL), "玩家不应拿回木桶");
        assertTrue(droppedItems(Material.BARREL).isEmpty(), "木桶不应落地");
    }

    // ---- 配置键（vanilla-carts.allow-pickup / container-pickup-policy）解析与默认 ----

    @Test
    void config_defaultsForVanillaPickupAndContainerPolicy() {
        assertTrue(plugin.pluginConfig().vanillaCartPickupAllowed(), "默认应允许取下原版特殊矿车");
        assertEquals(ContainerPickupPolicy.PICKUP, plugin.pluginConfig().containerPickupPolicy(),
                "容器策略默认应为 PICKUP");
    }

    @Test
    void config_overridesForVanillaPickupAndContainerPolicy() {
        setConfig("vanilla-carts.allow-pickup", false);
        setConfig("container-pickup-policy", "DROP");
        assertFalse(plugin.pluginConfig().vanillaCartPickupAllowed());
        assertEquals(ContainerPickupPolicy.DROP, plugin.pluginConfig().containerPickupPolicy());

        setConfig("container-pickup-policy", "BOGUS");
        assertEquals(ContainerPickupPolicy.PICKUP, plugin.pluginConfig().containerPickupPolicy(),
                "非法容器策略应回退 PICKUP");
    }

    private Minecart singleMinecart() {
        List<Minecart> carts = world.getEntities().stream()
                .filter(en -> en instanceof Minecart)
                .map(en -> (Minecart) en)
                .toList();
        assertEquals(1, carts.size(), "世界中应恰好有 1 个矿车");
        return carts.get(0);
    }
}
