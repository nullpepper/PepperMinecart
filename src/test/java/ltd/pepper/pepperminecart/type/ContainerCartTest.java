package ltd.pepper.pepperminecart.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import ltd.pepper.pepperminecart.config.TestConfigs;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ContainerCartTest extends TypeTestSupport {

    private InMemoryCart barrelCart(ContainerCart type, ItemStack... contents) {
        return new InMemoryCart(spawnCart(), type, containerWith(Material.BARREL, contents));
    }

    private static ContainerCart withPolicy(String policy) {
        return new ContainerCart(TestConfigs.with(Map.of("container-pickup-policy", policy)));
    }

    @Test
    void openingShowsTheContentsInTheBlocksOwnMenuType() {
        ContainerCart type = withPolicy("PICKUP");
        InMemoryCart cart = barrelCart(type, new ItemStack(Material.DIAMOND, 5));
        PlayerMock player = server.addPlayer();

        assertTrue(type.onUse(cart, player));

        Inventory open = player.getOpenInventory().getTopInventory();
        assertEquals(InventoryType.BARREL, open.getType());
        assertEquals(5, count(open.getContents(), Material.DIAMOND));
    }

    @Test
    void closingTheMenuSavesTheChangesIntoTheBlockItem() {
        ContainerCart type = withPolicy("PICKUP");
        InMemoryCart cart = barrelCart(type);
        PlayerMock player = server.addPlayer();
        type.onUse(cart, player);

        player.getOpenInventory().getTopInventory().setItem(3, new ItemStack(Material.EMERALD, 2));
        cart.closeMenus();

        assertEquals(2, count(BlockItems.contents(cart.blockItem()), Material.EMERALD));
    }

    @Test
    void everyoneLooksAtTheSameInventory() {
        ContainerCart type = withPolicy("PICKUP");
        InMemoryCart cart = barrelCart(type);
        PlayerMock first = server.addPlayer();
        PlayerMock second = server.addPlayer();

        type.onUse(cart, first);
        type.onUse(cart, second);

        assertSame(first.getOpenInventory().getTopInventory(), second.getOpenInventory().getTopInventory(),
                "同一辆矿车只有一份库存，与原版容器一样多人看到同一份");
    }

    @Test
    void pickupPolicyKeepsTheContentsInsideTheReturnedItem() {
        ContainerCart type = withPolicy("PICKUP");
        InMemoryCart cart = barrelCart(type, new ItemStack(Material.DIAMOND, 5));

        ItemStack returned = type.takeOff(cart);

        assertNotNull(returned);
        assertEquals(5, count(BlockItems.contents(returned), Material.DIAMOND));
        assertTrue(cart.drops.isEmpty());
    }

    @Test
    void dropPolicySpillsTheContentsAndReturnsAnEmptyContainer() {
        ContainerCart type = withPolicy("DROP");
        InMemoryCart cart = barrelCart(type, new ItemStack(Material.DIAMOND, 5), new ItemStack(Material.EMERALD, 1));

        ItemStack returned = type.takeOff(cart);

        assertNotNull(returned);
        assertEquals(Material.BARREL, returned.getType());
        assertTrue(BlockItems.nonEmptyContents(returned).isEmpty());
        assertEquals(5, count(cart.drops.toArray(ItemStack[]::new), Material.DIAMOND));
        assertEquals(1, count(cart.drops.toArray(ItemStack[]::new), Material.EMERALD));
    }

    @Test
    void forbiddenPolicyRefusesWithAMessage() {
        ContainerCart type = withPolicy("FORBIDDEN");
        PlayerMock player = server.addPlayer();

        assertFalse(type.canTakeOff(barrelCart(type), player));
        assertEquals("容器类方块取下已在配置中禁止", player.nextMessage());
    }

    @Test
    void shulkerBoxesAreAlwaysTakenOffWithTheirContents() {
        PlayerMock player = server.addPlayer();
        for (String policy : new String[] {"FORBIDDEN", "DROP"}) {
            ContainerCart type = withPolicy(policy);
            InMemoryCart cart = new InMemoryCart(spawnCart(), type,
                    containerWith(Material.SHULKER_BOX, new ItemStack(Material.DIAMOND, 5)));

            assertTrue(type.canTakeOff(cart, player), policy + " 不约束潜影盒");
            assertEquals(5, count(BlockItems.contents(type.takeOff(cart)), Material.DIAMOND), policy);
        }
    }

    @Test
    void shulkerBoxesAndBarrelsAreContainerBlocks() {
        ContainerCart type = new ContainerCart(TestConfigs.defaults());

        assertTrue(type.blocks().containsAll(java.util.Set.of(Material.BARREL, Material.SHULKER_BOX,
                Material.RED_SHULKER_BOX, Material.DROPPER, Material.TRAPPED_CHEST)));
        assertFalse(type.blocks().contains(Material.CHEST), "箱子会变成原版箱子矿车");
        assertFalse(type.blocks().contains(Material.HOPPER), "漏斗会变成原版漏斗矿车");
    }
}
