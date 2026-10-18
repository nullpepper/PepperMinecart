package ltd.pepper.pepperminecart.type;

import java.util.Arrays;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** 单独测试矿车类型：只需要 MockBukkit 服务器与一个世界，不加载插件。 */
abstract class TypeTestSupport {

    protected ServerMock server;
    protected WorldMock world;

    @BeforeEach
    void startServer() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("types");
    }

    @AfterEach
    void stopServer() {
        MockBukkit.unmock();
    }

    protected Location cartLocation() {
        return new Location(world, 0.5, 64, 0.5);
    }

    protected Minecart spawnCart() {
        return world.spawn(cartLocation(), RideableMinecart.class);
    }

    /** 装着这些东西的容器方块物品（第 i 个参数放第 i 格）。 */
    protected static ItemStack containerWith(Material container, ItemStack... contents) {
        ItemStack item = new ItemStack(container);
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        Container state = (Container) meta.getBlockState();
        for (int slot = 0; slot < contents.length; slot++) {
            state.getSnapshotInventory().setItem(slot, contents[slot]);
        }
        meta.setBlockState(state);
        item.setItemMeta(meta);
        return item;
    }

    protected static int count(ItemStack[] items, Material material) {
        return Arrays.stream(items)
                .filter(item -> item != null && item.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }
}
