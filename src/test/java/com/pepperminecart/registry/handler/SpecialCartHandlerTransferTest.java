package com.pepperminecart.registry.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.inventory.meta.BlockStateMetaMock;

/**
 * 回归测试：放置物品携带的容器内容转移到目标矿车库存时的边界行为。
 * 目标矿车没有原版库存（如 PoweredMinecart 熔炉矿车不实现 InventoryHolder）时，
 * 内容必须全部作为 leftovers 返回给调用方掉落，绝不能静默丢弃。
 */
class SpecialCartHandlerTransferTest {

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

    /** 构造带容器内容（3 格：输入/燃料/成品）的方块物品（直接内存构造，绕过 MockBukkit 的序列化限制）。 */
    private ItemStack blockItemWithContents(Material material, ItemStack input, ItemStack fuel, ItemStack output) {
        ItemStack placed = new ItemStack(material);
        BlockStateMeta meta = new BlockStateMetaMock(material);
        if (meta.getBlockState() instanceof Container container) {
            // Bukkit 契约：getBlockState() 返回快照副本，改完必须 setBlockState 写回
            container.getInventory().setItem(0, input);
            container.getInventory().setItem(1, fuel);
            container.getInventory().setItem(2, output);
            meta.setBlockState(container);
        }
        placed.setItemMeta(meta);
        return placed;
    }

    @Test
    void transferContents_targetWithoutInventory_returnsAllContentsAsLeftovers() {
        // 熔炉矿车（PoweredMinecart）不实现 InventoryHolder：这里用无库存的普通矿车模拟同一分支
        ItemStack placed = blockItemWithContents(Material.FURNACE,
                new ItemStack(Material.IRON_ORE, 2), new ItemStack(Material.COAL, 3), new ItemStack(Material.IRON_INGOT, 1));
        Minecart plain = world.spawn(new Location(world, 0, 64, 0), RideableMinecart.class);

        List<ItemStack> leftovers = SpecialCartHandler.transferContents(plain, placed);

        assertEquals(3, leftovers.size(), "无库存目标应把全部内容作为 leftovers 退回，不得静默丢弃");
        long ironOre = leftovers.stream().filter(i -> i.getType() == Material.IRON_ORE).mapToInt(ItemStack::getAmount).sum();
        long coal = leftovers.stream().filter(i -> i.getType() == Material.COAL).mapToInt(ItemStack::getAmount).sum();
        long ingot = leftovers.stream().filter(i -> i.getType() == Material.IRON_INGOT).mapToInt(ItemStack::getAmount).sum();
        assertEquals(2, ironOre, "输入物应全部退回");
        assertEquals(3, coal, "燃料应全部退回");
        assertEquals(1, ingot, "成品应全部退回");
    }

    @Test
    void transferContents_targetWithInventory_transfersContents() {
        ItemStack placed = blockItemWithContents(Material.CHEST,
                new ItemStack(Material.DIAMOND, 2), new ItemStack(Material.EMERALD, 3), null);
        StorageMinecart chestCart = world.spawn(new Location(world, 0, 64, 0), StorageMinecart.class);

        List<ItemStack> leftovers = SpecialCartHandler.transferContents(chestCart, placed);

        assertTrue(leftovers.isEmpty(), "有库存目标容量足够时不应产生 leftovers");
        assertEquals(Material.DIAMOND, chestCart.getInventory().getItem(0).getType(), "内容应转入目标库存");
        assertEquals(2, chestCart.getInventory().getItem(0).getAmount());
        assertEquals(Material.EMERALD, chestCart.getInventory().getItem(1).getType());
        assertEquals(3, chestCart.getInventory().getItem(1).getAmount());
    }
}
