package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.type.ContainerCart;
import ltd.pepper.pepperminecart.type.PlainBlockCart;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 坏数据、坏类型声明与失败的事务：出错时不能弄丢玩家的东西，也不能拖垮别的矿车。
 * 这些用例对应审查报告里的 S2（tick 屏障）、S3（放置事务）、S4（实体替换）与 S10（注册校验）。
 */
class RobustnessTest extends CartTestSupport {

    private static final NamespacedKey TYPE_KEY = new NamespacedKey("pepperminecart", "cart-type");
    private static final NamespacedKey ITEM_KEY = new NamespacedKey("pepperminecart", "item");

    @Test
    void unreadableCartTypeIdFallsBackToTheDefaultType() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.GRINDSTONE);
        cart.getPersistentDataContainer().set(TYPE_KEY, PersistentDataType.STRING, "too:many:parts:here");

        ManagedCart managed = assertDoesNotThrow(() -> api.getCart(cart), "id 解析不了时不能抛异常");

        assertNotNull(managed, "id 坏掉的矿车按默认类型处理，方块不会因此丢失");
        assertEquals(PlainBlockCart.ID, managed.type().id());
        assertDoesNotThrow(() -> server.getScheduler().performTicks(2), "坏数据不能让 tick 任务整体失败");
        assertTrue(cart.isValid());
    }

    @Test
    void unreadableBlockItemDoesNotBreakTheCart() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.GRINDSTONE);
        cart.getPersistentDataContainer().set(ITEM_KEY, PersistentDataType.BYTE_ARRAY, new byte[] {1, 2, 3, 4});

        assertNotNull(managed(cart));
        assertDoesNotThrow(() -> managed(cart).blockType(), "损坏的方块物品读不出来时只能记日志，不能抛给调用方");
        assertDoesNotThrow(() -> server.getScheduler().performTicks(2));
        assertTrue(cart.isValid(), "一辆车的坏数据不该让它自己被移除，更不该影响别的车");
    }

    @Test
    void oneThrowingCartTypeDoesNotStopTheTickLoop() {
        AtomicInteger healthyTicks = new AtomicInteger();
        api.registerCartType(plugin, new ThrowingTickCart());
        api.registerCartType(plugin, new CountingTickCart(healthyTicks));
        PlayerMock player = newPlayer();
        placeOnNewCart(player, Material.MAGMA_BLOCK);
        RideableMinecart second = spawnCart();
        player.getInventory().setItemInMainHand(new ItemStack(Material.SPONGE));
        assertTrue(sneakRightClick(player, second).isCancelled());

        assertDoesNotThrow(() -> server.getScheduler().performTicks(2), "一个类型抛异常不能让整条 tick 任务被取消");

        assertTrue(healthyTicks.get() > 0, "另一辆矿车的 onTick 必须照常执行");
    }

    @Test
    void failedPlacementGivesTheBlockBackExactlyOnce() {
        api.registerCartType(plugin, new ThrowingOnPlacedCart());
        PlayerMock player = newPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.BEEHIVE, 3));
        RideableMinecart cart = spawnCart();

        assertFalse(sneakRightClick(player, cart).isCancelled(), "放置失败时把这次点击交还原版");

        assertNull(api.getCart(cart), "失败后矿车必须恢复成普通矿车");
        assertFalse(showsBlock(cart), "失败后显示方块也要撤掉");
        assertTrue(cart.isValid());
        assertEquals(3, countInInventory(player, Material.BEEHIVE), "退还的必须正好是扣掉的那一个，不能多退");
    }

    @Test
    void convertingACartKeepsItsPassengerOnBoard() {
        api.registerCartType(MockBukkit.createMockPlugin("seats"), new TakeOffTest.SeatCart());
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.OAK_STAIRS);
        PlayerMock rider = newPlayer();
        assertTrue(cart.addPassenger(rider), "前置：该类型允许乘坐");

        Minecart replacement = managed(cart).convertTo(HopperMinecart.class);

        assertTrue(replacement.isValid());
        assertEquals(1, replacement.getPassengers().size(), "换车不能把乘客甩下去");
        assertSame(rider, replacement.getPassengers().get(0));
        assertFalse(cart.isValid(), "旧实体要被移除");
        assertNotNull(api.getCart(replacement), "插件数据要跟着搬到新实体");
        assertFalse(showsBlock(replacement), "原版箱车自己会画出箱子，不该再叠一个显示方块");
    }

    @Test
    void convertingToAPlainCartKeepsTheDisplayedBlock() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.GRINDSTONE);
        assertTrue(showsBlock(cart), "前置：普通矿车靠显示方块展示外观");

        Minecart replacement = managed(cart).convertTo(RideableMinecart.class);

        assertTrue(showsBlock(replacement), "换成普通矿车时显示方块要跟着搬过去，不能凭空消失");
        assertNotNull(api.getCart(replacement));
    }

    @Test
    void enteringTheWorldRestoresAMissingDisplayBlock() {
        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.GRINDSTONE);
        cart.setDisplayBlockData(null);
        assertFalse(showsBlock(cart), "前置：显示方块已经被别的插件清掉");

        server.getPluginManager().callEvent(new EntityAddToWorldEvent(cart, world));

        assertTrue(showsBlock(cart), "重新进入世界时要按保存的方块物品把显示补回来");
    }

    @Test
    void workstationBlocksAreNoLongerContainerCarts() {
        assertNull(api.getCartTypeForBlock(Material.SMOKER), "烟熏炉的界面只剩用不上的专用槽位");
        assertNull(api.getCartTypeForBlock(Material.BLAST_FURNACE), "高炉同理");
        assertNull(api.getCartTypeForBlock(Material.BREWING_STAND), "酿造台在矿车上不会酿造，界面也只收酿造材料");
        assertEquals(ContainerCart.ID, api.getCartTypeForBlock(Material.BARREL).id(), "真正的储物方块仍然是容器矿车");

        PlayerMock player = newPlayer();
        Minecart cart = placeOnNewCart(player, Material.BREWING_STAND);
        assertEquals(PlainBlockCart.ID, managed(cart).type().id(), "改按普通方块矿车处理，方块照样能取回");
    }

    @Test
    void alreadyCancelledMountEventIsLeftAlone() {
        AtomicInteger asked = new AtomicInteger();
        api.registerCartType(plugin, new CountingSeatsCart(asked));
        Minecart cart = placeOnNewCart(newPlayer(), Material.BOOKSHELF);

        EntityMountEvent event = new EntityMountEvent(newPlayer(), cart);
        event.setCancelled(true);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "已经取消的挂载事件不该被本插件改回去");
        assertEquals(0, asked.get(), "预取消的事件不该进入 allowsPassengers 判定（ignoreCancelled）");
    }

    @Test
    void registryRejectsBrokenTypeDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> api.registerCartType(plugin, new NullBlocksCart()));
        assertThrows(IllegalArgumentException.class, () -> api.registerCartType(plugin, new NonBlockCart()));
        assertThrows(IllegalArgumentException.class, () -> api.registerCartType(plugin, new ReservedNamespaceCart()));
        assertThrows(NullPointerException.class, () -> api.unregisterCartType(null));
    }

    /** 拒绝载客并数「被询问了几次」，用来证明预取消的事件没有惊动处理器。 */
    static final class CountingSeatsCart implements CartType {
        private final AtomicInteger asked;

        CountingSeatsCart(AtomicInteger asked) {
            this.asked = asked;
        }

        @Override
        public NamespacedKey id() {
            return new NamespacedKey("broken", "counting-seats");
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.BOOKSHELF);
        }

        @Override
        public boolean allowsPassengers() {
            asked.incrementAndGet();
            return false;
        }
    }

    /** 声明了 null 方块集合的坏类型。 */
    static final class NullBlocksCart implements CartType {
        @Override
        public NamespacedKey id() {
            return new NamespacedKey("broken", "null-blocks");
        }

        @Override
        public Set<Material> blocks() {
            return null;
        }
    }

    /** 声明了非方块材质的坏类型。 */
    static final class NonBlockCart implements CartType {
        @Override
        public NamespacedKey id() {
            return new NamespacedKey("broken", "not-a-block");
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.DIAMOND);
        }
    }

    /** 想占用原版命名空间的坏类型。 */
    static final class ReservedNamespaceCart implements CartType {
        @Override
        public NamespacedKey id() {
            return new NamespacedKey("minecraft", "hijack");
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.BEDROCK);
        }
    }

    /** onTick 必抛的类型：用来验证一辆车出错不会取消整条 repeating task。 */
    static final class ThrowingTickCart implements CartType {
        @Override
        public NamespacedKey id() {
            return new NamespacedKey("broken", "throwing-tick");
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.MAGMA_BLOCK);
        }

        @Override
        public void onTick(ManagedCart cart) {
            throw new IllegalStateException("boom");
        }
    }

    /** 数 tick 次数的类型：用来证明别的矿车照常被调度。 */
    static final class CountingTickCart implements CartType {
        private final AtomicInteger ticks;

        CountingTickCart(AtomicInteger ticks) {
            this.ticks = ticks;
        }

        @Override
        public NamespacedKey id() {
            return new NamespacedKey("broken", "counting-tick");
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.SPONGE);
        }

        @Override
        public void onTick(ManagedCart cart) {
            ticks.incrementAndGet();
        }
    }

    /** onPlaced 必抛的类型：用来验证放置事务会把矿车与物品都恢复原样。 */
    static final class ThrowingOnPlacedCart implements CartType {
        @Override
        public NamespacedKey id() {
            return new NamespacedKey("broken", "throwing-placed");
        }

        @Override
        public Set<Material> blocks() {
            return Set.of(Material.BEEHIVE);
        }

        @Override
        public void onPlaced(ManagedCart cart, Player player) {
            throw new IllegalStateException("boom");
        }
    }
}
