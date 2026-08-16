package com.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.impl.CartContextImpl;
import com.pepperminecart.registry.CartTypeRegistry;
import com.pepperminecart.registry.handler.GenericBlockHandler;
import com.pepperminecart.storage.CartData;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** replaceEntity 事务性：复制/迁移中途失败时，不得留下两个带插件 PDC 的“受管”实体。 */
class ReplaceEntityAtomicityTest {

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
    void rebindFailureRollsBackCopiedPdcOnReplacement() {
        PluginMock plugin = MockBukkit.createMockPlugin("atomicity");
        CartEngine boomEngine = new CartEngine(plugin, new CartTypeRegistry(null), new PluginConfig(null)) {
            @Override
            public void rebind(UUID oldId, Minecart newCart) {
                throw new IllegalStateException("simulated rebind failure");
            }
        };

        RideableMinecart oldCart = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);
        RideableMinecart newCart = world.spawn(new Location(world, 1, 64, 0), RideableMinecart.class);
        CartData.setCartType(oldCart, "pepperminecart:test");
        CartData.setItem(oldCart, new ItemStack(Material.DIAMOND));

        CartContextImpl ctx = new CartContextImpl(boomEngine, new PluginConfig(null), oldCart,
                GenericBlockHandler.INSTANCE);

        assertThrows(IllegalStateException.class, () -> ctx.replaceEntity(newCart));

        assertTrue(CartData.isManaged(oldCart), "失败时旧实体必须保留原有受管数据");
        assertFalse(CartData.isManaged(newCart), "失败时替代实体上的复制 PDC 必须被回滚清除");
    }
}
