package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.PepperMinecartAPI;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.storage.CartData;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.registry.handler.SpecialCartHandler;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;

/** 针对引擎状态管理修复的回归测试。 */
class EngineStateFixTest {

    private ServerMock server;
    private PepperMinecartPlugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private RideableMinecart spawnCart() {
        return world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
    }

    @Test
    void failedTakeOffRemovesTemporaryContext() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        CartTypeHandler nullItemHandler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:null-item");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public ItemStack getTakeOffItem(com.pepperminecart.api.CartContext ctx) {
                return null;
            }
        };

        // 模拟外来原版矿车：只有临时 context，没有任何 PDC 数据
        CartContextImpl ctx = plugin.engine().contextFor(cart, nullItemHandler);
        assertTrue(plugin.engine().isManaged(cart), "contextFor 后应被临时登记");

        plugin.engine().takeOff(player, ctx, TakeOffResult.INVENTORY);

        assertFalse(plugin.engine().isManaged(cart), "取下失败后不应残留临时 context");
        assertNull(plugin.engine().contextOrNull(cart), "无 PDC 数据时不应再被解析为受管矿车");
    }

    @Test
    void contextOrNullDoesNotAdoptDisplayOnlyCart() {
        RideableMinecart cart = spawnCart();
        new CartBlockDisplay(cart).set(Material.BARREL);
        assertFalse(CartData.isManaged(cart), "仅显示方块的矿车不应有插件 PDC");

        assertNull(plugin.engine().contextOrNull(cart), "contextOrNull 不应收养无 PDC 的显示矿车");
        assertFalse(plugin.engine().isManaged(cart), "contextOrNull 后不应进入受管集合");
    }

    @Test
    void clearRemovesHandlerNamespaceMetadata() {
        RideableMinecart cart = spawnCart();
        var key = org.bukkit.NamespacedKey.fromString("dripstone:interval");
        cart.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.INTEGER, 20);

        CartData.clear(cart, "dripstone");

        assertFalse(cart.getPersistentDataContainer().has(key), "扩展处理器命名空间下的元数据应被清理");
    }

    @Test
    void replaceEntityCopiesDisplayBlockAndOffset() {
        RideableMinecart oldCart = spawnCart();
        RideableMinecart newCart = spawnCart();

        CartContextImpl ctx = plugin.engine().addCart(oldCart, new GenericBlockHandler(), new ItemStack(Material.STONE));
        ctx.setDisplay(Material.STONE);
        ctx.setDisplayOffset(7);

        ctx.replaceEntity(newCart);

        assertEquals(Material.STONE, new CartBlockDisplay(newCart).material(), "实体替换应迁移显示方块");
        assertEquals(7, newCart.getDisplayBlockOffset(), "实体替换应迁移显示偏移");
        assertFalse(new CartBlockDisplay(oldCart).has(), "旧实体显示应被清理");
    }

    @Test
    void contextOrNullRefreshesReplacedHandler() {
        RideableMinecart cart = spawnCart();
        NamespacedKey id = NamespacedKey.fromString("test:same-id");

        CartTypeHandler first = handler(id, "first");
        CartTypeHandler second = handler(id, "second");

        plugin.engine().addCart(cart, first, new ItemStack(Material.STONE));
        assertSame(first, plugin.engine().context(cart).getType());

        PepperMinecartAPI api = server.getServicesManager().load(PepperMinecartAPI.class);
        api.registerCartType(second);

        assertSame(second, plugin.engine().contextOrNull(cart).getType(),
                "同 id 类型被覆盖后，已有 context 应刷新为新 handler");
    }

    @Test
    void contextForRefreshesReplacedHandler() {
        RideableMinecart cart = spawnCart();
        NamespacedKey id = NamespacedKey.fromString("test:same-id");

        CartTypeHandler first = handler(id, "first");
        CartTypeHandler second = handler(id, "second");

        plugin.engine().addCart(cart, first, new ItemStack(Material.STONE));
        assertSame(first, plugin.engine().context(cart).getType());

        PepperMinecartAPI api = server.getServicesManager().load(PepperMinecartAPI.class);
        api.registerCartType(second);

        CartTypeHandler resolved = plugin.engine().resolve(cart);
        assertSame(second, plugin.engine().contextFor(cart, resolved).getType(),
                "contextFor 在已有 context 类型变化时也应刷新为新 handler");
    }

    @Test
    void dropItemFallsBackToSnapshotWhenEntityInvalid() {
        Minecart fake = fakeMinecart(null, null, UUID.randomUUID());
        CartContextImpl ctx = new CartContextImpl(plugin.engine(), plugin.pluginConfig(), fake, new GenericBlockHandler());
        Location loc = new Location(world, 10, 64, 10);
        ctx.updateSnapshot(world, loc);

        ctx.dropItem(new ItemStack(Material.DIAMOND));

        assertTrue(world.getEntities().stream()
                        .anyMatch(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND),
                "实体 getWorld/getLocation 不可用时，应回退到快照位置掉落");
    }

    @Test
    void takeOffRemovesContextForInvalidCart() {
        PlayerMock player = server.addPlayer();
        UUID id = UUID.randomUUID();
        Minecart fake = fakeMinecart(null, null, id);
        CartTypeHandler handler = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:invalid-cart");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public ItemStack getTakeOffItem(com.pepperminecart.api.CartContext ctx) {
                return new ItemStack(Material.STONE);
            }
        };

        CartContextImpl ctx = plugin.engine().contextFor(fake, handler);
        assertTrue(plugin.engine().isManaged(fake), "contextFor 后应被临时登记");

        plugin.engine().takeOff(player, ctx, TakeOffResult.INVENTORY);

        assertFalse(plugin.engine().isManaged(fake), "实体已失效的取下流程也应清理 context");
    }

    @Test
    void resolveIntermediateSpecialStateFallsBackToGeneric() {
        RideableMinecart cart = spawnCart();
        // 转换中间状态：addCart 写入 PDC 后、onPlaced 完成实体转换前中断
        CartData.setCartType(cart, SpecialCartHandler.ID.toString());
        CartData.setItem(cart, new ItemStack(Material.CHEST));
        CartData.setOriginalMaterial(cart, Material.CHEST);

        CartTypeHandler resolved = plugin.engine().resolve(cart);

        assertSame(GenericBlockHandler.INSTANCE, resolved,
                "普通矿车带 special PDC 应回退通用处理器，避免 SpecialCartHandler 按实体类型取不到存储物品");
    }

    @Test
    void giveItemDisabledModeDeliversNothing() {
        PlayerMock player = server.addPlayer();
        RideableMinecart cart = spawnCart();
        CartContextImpl ctx = plugin.engine().addCart(cart, new GenericBlockHandler(), new ItemStack(Material.STONE));

        ctx.giveItem(player, new ItemStack(Material.DIAMOND), TakeOffResult.DISABLED);

        for (ItemStack it : player.getInventory().getContents()) {
            assertTrue(it == null || it.getType() != Material.DIAMOND, "DISABLED 不应把物品放进背包");
        }
        assertTrue(world.getEntities().stream()
                        .noneMatch(e -> e instanceof Item item && item.getItemStack().getType() == Material.DIAMOND),
                "DISABLED 不应把物品掉落到地面");
    }

    @Test
    void releaseCartClearsManagementAndPdc() {
        RideableMinecart cart = spawnCart();
        CartContextImpl ctx = plugin.engine().addCart(cart, new GenericBlockHandler(), new ItemStack(Material.STONE));
        ctx.setDisplay(Material.STONE);
        assertTrue(plugin.engine().isManaged(cart), "放置后应受管");

        ctx.releaseCart();

        assertFalse(plugin.engine().isManaged(cart), "releaseCart 后引擎不应再跟踪");
        assertFalse(CartData.isManaged(cart), "releaseCart 应清除插件 PDC");
        assertFalse(new CartBlockDisplay(cart).has(), "releaseCart 应清除显示方块");
    }

    @Test
    void onEntityRemoveCleansContextWhenDestroyHandlerThrows() {
        // 用无 PDC 的 fake 矿车：真实矿车销毁后 PDC 随实体消失，无法在存活实体上断言；
        // fake 的 PDC 恒为空，isManaged 仅反映 contexts 集合，正可验证 context 清理
        Minecart cart = fakeMinecart(null, null, UUID.randomUUID());
        CartTypeHandler throwing = new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return NamespacedKey.fromString("test:throwing-destroy");
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public void onCartDestroyed(com.pepperminecart.api.CartContext ctx) {
                throw new IllegalStateException("模拟销毁回调异常");
            }
        };
        plugin.engine().contextFor(cart, throwing);
        assertTrue(plugin.engine().isManaged(cart), "contextFor 后应被临时登记");

        server.getPluginManager().callEvent(new EntityRemoveEvent(cart, EntityRemoveEvent.Cause.DISCARD));

        assertFalse(plugin.engine().isManaged(cart), "销毁回调/掉落异常后也必须清理 context（finally 保证）");
    }

    private static CartTypeHandler handler(NamespacedKey id, String name) {
        return new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return id;
            }

            @Override
            public Set<Material> handledMaterials() {
                return Set.of();
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    /** 最小 Minecart 代理：测试中只关心 getWorld/getLocation/getUniqueId/isValid。 */
    private static Minecart fakeMinecart(World world, Location location, UUID id) {
        return (Minecart) Proxy.newProxyInstance(
                EngineStateFixTest.class.getClassLoader(),
                new Class<?>[]{Minecart.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWorld":
                            return world;
                        case "getLocation":
                            return location;
                        case "getUniqueId":
                            return id;
                        case "isValid":
                            return false;
                        case "getPersistentDataContainer":
                            return new PersistentDataContainerMock();
                        default:
                            break;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == double.class) return 0.0d;
                    if (rt == float.class) return 0.0f;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == char.class) return (char) 0;
                    return null;
                });
    }
}
