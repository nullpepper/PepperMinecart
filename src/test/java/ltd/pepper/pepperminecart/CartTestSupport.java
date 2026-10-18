package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.api.PepperMinecartAPI;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * MockBukkit 集成测试的公共装配：真实加载插件，交互冷却为 0，关闭音效
 * （MockBukkit 没有实现方块的音效组）。矿车生成在 (0.5, 64, 0.5)，玩家站在两格外。
 */
abstract class CartTestSupport {

    protected ServerMock server;
    protected PepperMinecartPlugin plugin;
    protected WorldMock world;
    protected PepperMinecartAPI api;

    @BeforeEach
    void startServer() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
        world = server.addSimpleWorld("carts");
        api = server.getServicesManager().load(PepperMinecartAPI.class);
        setConfig("interaction-cooldown-ms", 0);
        setConfig("sounds.place", false);
        setConfig("sounds.take-off", false);
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    /** 等价于改 config.yml 后执行 /pm reload（跳过磁盘往返）。 */
    protected void setConfig(String path, Object value) {
        plugin.getConfig().set(path, value);
        plugin.pluginConfig().apply(plugin.getConfig().getValues(true));
    }

    protected Location cartLocation() {
        return new Location(world, 0.5, 64, 0.5);
    }

    protected RideableMinecart spawnCart() {
        return world.spawn(cartLocation(), RideableMinecart.class);
    }

    protected PlayerMock newPlayer() {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 64, 2.5));
        return player;
    }

    protected PlayerInteractEntityEvent rightClick(Player player, Entity target) {
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    protected PlayerInteractEntityEvent sneakRightClick(PlayerMock player, Entity target) {
        player.setSneaking(true);
        PlayerInteractEntityEvent event = rightClick(player, target);
        player.setSneaking(false);
        return event;
    }

    /** 让玩家手持 {@code block} 潜行右键一辆新矿车，返回放置后的矿车（原版特殊矿车会是新实体）。 */
    protected Minecart placeOnNewCart(PlayerMock player, ItemStack block) {
        RideableMinecart cart = spawnCart();
        player.getInventory().setItemInMainHand(block);
        assertTrue(sneakRightClick(player, cart).isCancelled(), "前置：潜行手持方块右键空矿车应放上方块");
        return onlyMinecart();
    }

    protected Minecart placeOnNewCart(PlayerMock player, Material block) {
        return placeOnNewCart(player, new ItemStack(block));
    }

    protected Minecart onlyMinecart() {
        List<Minecart> minecarts = world.getEntitiesByClass(Minecart.class).stream()
                .filter(Entity::isValid)
                .toList();
        assertEquals(1, minecarts.size(), "世界里应恰好有一辆矿车");
        return minecarts.get(0);
    }

    protected ManagedCart managed(Minecart minecart) {
        ManagedCart cart = api.getCart(minecart);
        assertNotNull(cart, "矿车应载着方块");
        return cart;
    }

    protected void removeCart(Minecart minecart, EntityRemoveEvent.Cause cause) {
        server.getPluginManager().callEvent(new EntityRemoveEvent(minecart, cause));
    }

    /**
     * 模拟领地/防掉落类插件：取消一切掉落物生成。
     * MockBukkit 的 {@code WorldMock} 会照真实 Paper 的做法把被取消的掉落物实体丢弃，
     * 因此 {@code World#dropItem} 的返回值仍非 null，只有 {@code Item#isValid()} 会变成 false。
     */
    protected void cancelItemSpawns() {
        cancelItemSpawnsOf(null);
    }

    /** 只取消某种材质的掉落物生成（null 表示全部），用来构造「掉了一半才被拦下」的场景。 */
    protected void cancelItemSpawnsOf(Material material) {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onItemSpawn(ItemSpawnEvent event) {
                if (material == null || event.getEntity().getItemStack().getType() == material) {
                    event.setCancelled(true);
                }
            }
        }, MockBukkit.createMockPlugin("no-drops"));
    }

    protected List<ItemStack> droppedItems(Material material) {
        return world.getEntitiesByClass(Item.class).stream()
                .filter(Entity::isValid)
                .map(Item::getItemStack)
                .filter(item -> item.getType() == material)
                .toList();
    }

    protected int countInInventory(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    protected ItemStack findInInventory(Player player, Material material) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                return item;
            }
        }
        return null;
    }

    protected static boolean showsBlock(Minecart minecart) {
        return minecart.getDisplayBlockData() != null && !minecart.getDisplayBlockData().getMaterial().isAir();
    }
}
