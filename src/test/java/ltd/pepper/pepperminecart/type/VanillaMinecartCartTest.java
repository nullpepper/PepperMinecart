package ltd.pepper.pepperminecart.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ltd.pepper.pepperminecart.config.TestConfigs;
import org.bukkit.Material;
import org.bukkit.block.CommandBlock;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.Test;

/** 方块与原版矿车之间的内容搬运（端到端流程见 VanillaMinecartTest）。 */
class VanillaMinecartCartTest extends TypeTestSupport {

    private final VanillaMinecartCart type = new VanillaMinecartCart(TestConfigs.defaults());

    @Test
    void chestContentsMoveIntoTheChestMinecart() {
        InMemoryCart cart = new InMemoryCart(spawnCart(), type,
                containerWith(Material.CHEST, new ItemStack(Material.DIAMOND, 5), new ItemStack(Material.EMERALD, 2)));

        type.onPlaced(cart, server.addPlayer());

        StorageMinecart chestMinecart = assertInstanceOf(StorageMinecart.class, cart.minecart());
        assertEquals(new ItemStack(Material.DIAMOND, 5), chestMinecart.getInventory().getItem(0), "槽位保持不变");
        assertEquals(new ItemStack(Material.EMERALD, 2), chestMinecart.getInventory().getItem(1));
        assertTrue(BlockItems.nonEmptyContents(cart.blockItem()).isEmpty(), "内容只保存在矿车里，模板里不重复保存");
    }

    @Test
    void takingOffPacksTheContentsBackIntoTheChest() {
        InMemoryCart cart = new InMemoryCart(spawnCart(), type, new ItemStack(Material.CHEST));
        type.onPlaced(cart, server.addPlayer());
        StorageMinecart chestMinecart = (StorageMinecart) cart.minecart();
        chestMinecart.getInventory().setItem(4, new ItemStack(Material.GOLD_INGOT, 7));

        ItemStack chest = type.takeOff(cart);

        assertEquals(7, count(BlockItems.contents(chest), Material.GOLD_INGOT));
        assertTrue(chestMinecart.getInventory().isEmpty(), "旧矿车清空，避免原版移除时再撒一份");
        assertInstanceOf(RideableMinecart.class, cart.minecart());
    }

    @Test
    void furnaceBecomesAFurnaceMinecart() {
        InMemoryCart cart = new InMemoryCart(spawnCart(), type, new ItemStack(Material.FURNACE));

        type.onPlaced(cart, server.addPlayer());

        assertInstanceOf(PoweredMinecart.class, cart.minecart());
    }

    @Test
    void commandTravelsBetweenTheBlockAndTheMinecart() {
        ItemStack commandBlock = new ItemStack(Material.COMMAND_BLOCK);
        BlockStateMeta meta = (BlockStateMeta) commandBlock.getItemMeta();
        CommandBlock state = (CommandBlock) meta.getBlockState();
        state.setCommand("say hi");
        meta.setBlockState(state);
        commandBlock.setItemMeta(meta);
        InMemoryCart cart = new InMemoryCart(spawnCart(), type, commandBlock);

        type.onPlaced(cart, server.addPlayer());
        CommandMinecart commandMinecart = assertInstanceOf(CommandMinecart.class, cart.minecart());
        assertEquals("say hi", commandMinecart.getCommand());

        commandMinecart.setCommand("say bye");
        assertEquals("say bye", BlockItems.command(type.takeOff(cart)));
    }

    @Test
    void vanillaMinecartsCarryTheirBlockByNature() {
        Minecart chestMinecart = world.spawn(cartLocation(), StorageMinecart.class);

        assertEquals(Material.CHEST, type.nativeBlock(chestMinecart).getType());
        assertNull(type.nativeBlock(spawnCart()), "普通矿车没有天然的方块");
        assertNull(type.displayBlock(new ItemStack(Material.CHEST)), "原版矿车自己画出方块，不设置显示方块");
    }
}
