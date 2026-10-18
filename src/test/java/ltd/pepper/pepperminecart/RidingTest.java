package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.entity.EntityMountEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** 载着方块的矿车默认不能坐人。 */
class RidingTest extends CartTestSupport {

    @Test
    void cartsCarryingABlockCannotBeRidden() {
        Minecart cart = placeOnNewCart(newPlayer(), Material.GRINDSTONE);

        assertTrue(mount(newPlayer(), cart).isCancelled());
    }

    @Test
    void emptyCartsCanBeRidden() {
        RideableMinecart cart = spawnCart();

        assertFalse(mount(newPlayer(), cart).isCancelled());
    }

    @Test
    void typesCanAllowPassengers() {
        api.registerCartType(MockBukkit.createMockPlugin("seats"), new TakeOffTest.SeatCart());
        Minecart cart = placeOnNewCart(newPlayer(), Material.OAK_STAIRS);

        assertFalse(mount(newPlayer(), cart).isCancelled());
    }

    private EntityMountEvent mount(PlayerMock rider, Minecart cart) {
        EntityMountEvent event = new EntityMountEvent(rider, cart);
        server.getPluginManager().callEvent(event);
        return event;
    }
}
