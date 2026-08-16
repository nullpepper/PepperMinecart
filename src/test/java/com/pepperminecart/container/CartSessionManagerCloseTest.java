package com.pepperminecart.container;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.pepperminecart.config.PluginConfig;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** flushAndClose 的关闭路径必须单一：同一会话不得被 flush 两次（一次显式 + 一次关闭事件）。 */
class CartSessionManagerCloseTest {

    private ServerMock server;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void flushAndCloseFlushesSessionExactlyOnce() throws Exception {
        PluginMock plugin = MockBukkit.createMockPlugin("session-close");
        CartSessionManager manager = new CartSessionManager(plugin, new PluginConfig(null), null);
        server.getPluginManager().registerEvents(manager, plugin);

        PlayerMock player = server.addPlayer();
        RideableMinecart cart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        Inventory top = server.createInventory(null, 9);
        top.setItem(0, new ItemStack(Material.DIAMOND));
        InventoryView view = player.openInventory(top);

        AtomicInteger flushes = new AtomicInteger();
        CartSession counting = new CartSession(cart, top, view) {
            @Override
            public void flush() {
                flushes.incrementAndGet();
            }

            @Override
            public void onPlayerClose(Player player) {
                flush();
            }
        };
        Field openField = CartSessionManager.class.getDeclaredField("open");
        openField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, CartSession> open = (Map<UUID, CartSession>) openField.get(manager);
        open.put(player.getUniqueId(), counting);

        manager.flushAndClose(cart);

        assertEquals(1, flushes.get(), "flushAndClose 只能回写一次，不得经关闭事件二次 flush");
    }
}
