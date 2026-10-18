package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.type.ContainerCart;
import ltd.pepper.pepperminecart.type.PlainBlockCart;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** 扩展插件通过 PepperMinecartAPI 注册自己的矿车类型。 */
class CartTypeApiTest extends CartTestSupport {

    private Plugin extension;

    @BeforeEach
    void createExtensionPlugin() {
        extension = MockBukkit.createMockPlugin("dripstone");
    }

    /** README 里的示例类型：钟乳石矿车，可以坐人，坐在上面每秒扣血。 */
    static final class DripstoneCart implements CartType {
        static final NamespacedKey ID = new NamespacedKey("dripstone", "cart");
        static final NamespacedKey HURT = new NamespacedKey("dripstone", "hurt");
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger uses = new AtomicInteger();

        @Override
        public NamespacedKey id() {
            return ID;
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.POINTED_DRIPSTONE);
        }

        @Override
        public boolean allowsPassengers() {
            return true;
        }

        @Override
        public boolean onUse(ManagedCart cart, Player player) {
            uses.incrementAndGet();
            return true;
        }

        @Override
        public void onTick(ManagedCart cart) {
            ticks.incrementAndGet();
            if (!cart.isCoolingDown(HURT)) {
                cart.startCooldown(HURT, 20);
            }
        }
    }

    @Test
    void registeredTypeHandlesItsBlocks() {
        DripstoneCart type = new DripstoneCart();
        api.registerCartType(extension, type);
        PlayerMock player = newPlayer();

        Minecart cart = placeOnNewCart(player, Material.POINTED_DRIPSTONE);

        assertSame(type, managed(cart).type());
        assertSame(type, api.getCartTypeForBlock(Material.POINTED_DRIPSTONE));
        assertTrue(rightClick(player, cart).isCancelled(), "onUse 返回 true 时取消原版交互");
        assertEquals(1, type.uses.get());
    }

    @Test
    void onTickRunsEveryTickUntilTheBlockIsTakenOff() {
        DripstoneCart type = new DripstoneCart();
        api.registerCartType(extension, type);
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.POINTED_DRIPSTONE);

        server.getScheduler().performTicks(3);
        assertEquals(3, type.ticks.get());

        sneakRightClick(player, cart);
        server.getScheduler().performTicks(3);
        assertEquals(3, type.ticks.get());
    }

    @Test
    void cooldownsFollowTheWorldsGameTime() {
        api.registerCartType(extension, new DripstoneCart());
        ManagedCart cart = managed(placeOnNewCart(newPlayer(), Material.POINTED_DRIPSTONE));
        NamespacedKey key = new NamespacedKey("dripstone", "test");

        assertFalse(cart.isCoolingDown(key));
        cart.startCooldown(key, 40);
        assertTrue(cart.isCoolingDown(key));
        ((WorldMock) cart.minecart().getWorld()).setGameTime(cart.minecart().getWorld().getGameTime() + 41);
        assertFalse(cart.isCoolingDown(key));
    }

    @Test
    void canPlaceCanRefuseAPlacement() {
        api.registerCartType(extension, new CartType() {
            @Override
            public NamespacedKey id() {
                return new NamespacedKey("dripstone", "picky");
            }

            @Override
            public Set<Material> blocks() {
                return Set.of(Material.POINTED_DRIPSTONE);
            }

            @Override
            public boolean canPlace(Player player, ItemStack block) {
                return false;
            }
        });
        PlayerMock player = newPlayer();
        Minecart cart = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.POINTED_DRIPSTONE));

        assertFalse(sneakRightClick(player, cart).isCancelled());
        assertNull(api.getCart(cart));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void typesCanCustomiseTheDisplayAndTheReturnedItem() {
        api.registerCartType(extension, new CartType() {
            @Override
            public NamespacedKey id() {
                return new NamespacedKey("dripstone", "ore");
            }

            @Override
            public Set<Material> blocks() {
                return Set.of(Material.DIAMOND_ORE);
            }

            @Override
            public BlockData displayBlock(ItemStack blockItem) {
                return Material.STONE.createBlockData(); // 伪装成石头
            }

            @Override
            public ItemStack takeOff(ManagedCart cart) {
                return new ItemStack(Material.DIAMOND, 2);
            }
        });
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.DIAMOND_ORE);
        assertEquals(Material.STONE, cart.getDisplayBlockData().getMaterial());

        sneakRightClick(player, cart);

        assertEquals(2, countInInventory(player, Material.DIAMOND));
        assertEquals(0, countInInventory(player, Material.DIAMOND_ORE));
    }

    @Test
    void convertToSwapsTheEntityAndKeepsItManaged() {
        api.registerCartType(extension, new CartType() {
            @Override
            public NamespacedKey id() {
                return new NamespacedKey("dripstone", "crate");
            }

            @Override
            public Set<Material> blocks() {
                return Set.of(Material.OAK_PLANKS);
            }

            @Override
            public void onPlaced(ManagedCart cart, Player player) {
                cart.convertTo(StorageMinecart.class);
            }
        });
        PlayerMock player = newPlayer();
        Minecart original = spawnCart();
        original.customName(Component.text("木箱车"));
        player.getInventory().setItemInMainHand(new ItemStack(Material.OAK_PLANKS));

        sneakRightClick(player, original);

        Minecart converted = onlyMinecart();
        assertTrue(converted instanceof StorageMinecart);
        assertFalse(original.isValid(), "旧实体已移除");
        assertEquals(Component.text("木箱车"), converted.customName());
        assertEquals(Material.OAK_PLANKS, managed(converted).blockType(), "数据随实体迁移");
    }

    @Test
    void reregisteringATypeAppliesToExistingCarts() {
        api.registerCartType(extension, new DripstoneCart());
        Minecart cart = placeOnNewCart(newPlayer(), Material.POINTED_DRIPSTONE);
        DripstoneCart newer = new DripstoneCart();

        api.registerCartType(extension, newer);

        assertSame(newer, managed(cart).type(), "类型在使用时才按 id 解析，晚注册、重注册都立即生效");
    }

    @Test
    void cartsOfAnUnregisteredTypeAreTreatedAsPlainBlocks() {
        api.registerCartType(extension, new DripstoneCart());
        Minecart cart = placeOnNewCart(newPlayer(), Material.POINTED_DRIPSTONE);

        api.unregisterCartType(DripstoneCart.ID);

        assertEquals(PlainBlockCart.ID, managed(cart).type().id());
        assertEquals(Material.POINTED_DRIPSTONE, managed(cart).blockType());
    }

    @Test
    void typesAreUnregisteredWhenTheirPluginIsDisabled() {
        api.registerCartType(extension, new DripstoneCart());

        server.getPluginManager().disablePlugin(extension);

        assertNull(api.getCartType(DripstoneCart.ID));
        assertNull(api.getCartTypeForBlock(Material.POINTED_DRIPSTONE));
    }

    @Test
    void reservedNamespacesAndNonBlocksAreRejected() {
        for (String namespace : new String[] {"pepperminecart", "minecraft", "bukkit"}) {
            CartType type = typeWith(new NamespacedKey(namespace, "x"), Material.STONE);
            assertThrows(IllegalArgumentException.class, () -> api.registerCartType(extension, type), namespace);
        }
        CartType notABlock = typeWith(new NamespacedKey("dripstone", "gem"), Material.DIAMOND);
        assertThrows(IllegalArgumentException.class, () -> api.registerCartType(extension, notABlock));
    }

    @Test
    void builtInTypesCannotBeUnregistered() {
        assertThrows(IllegalArgumentException.class, () -> api.unregisterCartType(ContainerCart.ID));
        assertTrue(api.getCartType(ContainerCart.ID) instanceof ContainerCart);
    }

    @Test
    void extensionsCanTakeOverABuiltInBlockForNewCarts() {
        PlayerMock player = newPlayer();
        Minecart before = placeOnNewCart(player, Material.BARREL);
        CartType barrels = typeWith(new NamespacedKey("dripstone", "barrel"), Material.BARREL);

        api.registerCartType(extension, barrels);
        Minecart after = world.spawn(cartLocation().add(3, 0, 0), RideableMinecart.class);
        player.getInventory().setItemInMainHand(new ItemStack(Material.BARREL));
        sneakRightClick(player, after);

        assertSame(barrels, managed(after).type());
        assertEquals(ContainerCart.ID, managed(before).type().id(), "已有的矿车仍是原来的类型");
    }

    private static CartType typeWith(NamespacedKey id, Material block) {
        return new CartType() {
            @Override
            public NamespacedKey id() {
                return id;
            }

            @Override
            public Set<Material> blocks() {
                return Set.of(block);
            }
        };
    }
}
