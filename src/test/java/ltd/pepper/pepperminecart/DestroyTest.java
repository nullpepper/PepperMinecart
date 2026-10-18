package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.event.entity.EntityRemoveEvent.Cause;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;

/** 矿车被摧毁时方块掉落；只是暂时离开世界时不掉落。 */
class DestroyTest extends CartTestSupport {

    @ParameterizedTest
    @EnumSource(value = Cause.class, names = {"DEATH", "DISCARD", "PLUGIN", "OUT_OF_WORLD"})
    void destroyedCartDropsItsBlockExactlyOnce(Cause cause) {
        Minecart cart = placeOnNewCart(newPlayer(), Material.GRINDSTONE);

        removeCart(cart, cause);

        assertEquals(1, droppedItems(Material.GRINDSTONE).size());
    }

    @ParameterizedTest
    @EnumSource(value = Cause.class, names = {"UNLOAD", "PLAYER_QUIT"})
    void cartLeavingTheWorldTemporarilyKeepsItsBlock(Cause cause) {
        Minecart cart = placeOnNewCart(newPlayer(), Material.GRINDSTONE);

        removeCart(cart, cause);

        assertTrue(droppedItems(Material.GRINDSTONE).isEmpty(), "矿车会随区块（或随乘客）保存后回来，此时掉落就是复制");
        assertNotNull(api.getCart(cart), "数据仍在矿车上");
    }

    @Test
    void cartOfAnUninstalledTypeStillDropsItsBlock() {
        api.registerCartType(MockBukkit.createMockPlugin("seats"), new TakeOffTest.SeatCart());
        Minecart cart = placeOnNewCart(newPlayer(), Material.OAK_STAIRS);
        api.unregisterCartType(TakeOffTest.SeatCart.ID);

        removeCart(cart, Cause.DEATH);

        assertEquals(1, droppedItems(Material.OAK_STAIRS).size());
    }

    @Test
    void containerCartDropsItsBlockWithTheContentsInside() {
        Minecart cart = placeOnNewCart(newPlayer(), Material.BARREL);

        removeCart(cart, Cause.DEATH);

        assertEquals(1, droppedItems(Material.BARREL).size(), "内容装在木桶物品里一起掉落，像潜影盒一样");
    }
}
