package ltd.pepper.pepperminecart.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.Map;
import ltd.pepper.pepperminecart.config.TestConfigs;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 铁砧受损。MockBukkit 打不开 MenuType 界面，这里直接打开一个铁砧库存并登记给铁砧矿车，
 * 再按真实服务端的顺序发出事件：点击结果槽 → 原版清空第一个输入槽 → PrepareAnvilEvent。
 */
class AnvilCartTest extends TypeTestSupport {

    private PlayerMock player;
    private AnvilInventory anvilInventory;
    private InventoryView view;

    private AnvilCart anvil(Map<String, Object> config) {
        return new AnvilCart(TestConfigs.with(config));
    }

    private InMemoryCart openAnvilCart(AnvilCart type, Material anvilBlock) {
        InMemoryCart cart = new InMemoryCart(spawnCart(), type, new ItemStack(anvilBlock));
        player = server.addPlayer();
        anvilInventory = (AnvilInventory) server.createInventory(null, InventoryType.ANVIL);
        view = player.openInventory(anvilInventory);
        type.opened(player, cart);
        return cart;
    }

    /** 玩家点击结果槽；{@code taken} 表示这次点击真的取走了结果（原版随即清空第一个输入槽）。 */
    private void clickResult(AnvilCart type, boolean taken) {
        anvilInventory.setFirstItem(new ItemStack(Material.IRON_SWORD));
        type.onClick(new InventoryClickEvent(view, InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        if (taken) {
            anvilInventory.setFirstItem(null);
        }
        type.onPrepare(new PrepareAnvilEvent(anvilView(), null));
    }

    @Test
    void takingResultsWearsTheAnvilDownStepByStep() {
        AnvilCart type = anvil(Map.of("anvil-damage.chance-per-use", 1.0));
        InMemoryCart cart = openAnvilCart(type, Material.ANVIL);

        clickResult(type, true);
        assertEquals(Material.CHIPPED_ANVIL, cart.blockType());
        clickResult(type, true);
        assertEquals(Material.DAMAGED_ANVIL, cart.blockType());
        clickResult(type, true);
        assertNull(cart.blockItem(), "损坏的铁砧再受损就没了");
    }

    @Test
    void clickingTheResultWithoutTakingItDoesNotWearTheAnvil() {
        AnvilCart type = anvil(Map.of("anvil-damage.chance-per-use", 1.0));
        InMemoryCart cart = openAnvilCart(type, Material.ANVIL);

        clickResult(type, false); // 例如经验不够、背包已满

        assertEquals(Material.ANVIL, cart.blockType());
    }

    @Test
    void theTakeMustHappenWithinTheSameClick() {
        AnvilCart type = anvil(Map.of("anvil-damage.chance-per-use", 1.0));
        InMemoryCart cart = openAnvilCart(type, Material.ANVIL);
        anvilInventory.setFirstItem(new ItemStack(Material.IRON_SWORD));
        type.onClick(new InventoryClickEvent(view, InventoryType.SlotType.RESULT, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL));

        server.getScheduler().performOneTick();
        anvilInventory.setFirstItem(null);
        type.onPrepare(new PrepareAnvilEvent(anvilView(), null));

        assertEquals(Material.ANVIL, cart.blockType());
    }

    @Test
    void creativePlayersNeverWearTheAnvil() {
        AnvilCart type = anvil(Map.of("anvil-damage.chance-per-use", 1.0));
        InMemoryCart cart = openAnvilCart(type, Material.ANVIL);
        player.setGameMode(GameMode.CREATIVE);

        clickResult(type, true);

        assertEquals(Material.ANVIL, cart.blockType(), "与原版一致：创造模式不会让铁砧受损");
    }

    @Test
    void zeroChanceOrDisabledDamageNeverWearsTheAnvil() {
        for (Map<String, Object> config : java.util.List.<Map<String, Object>>of(
                Map.of("anvil-damage.chance-per-use", 0.0),
                Map.of("anvil-damage.chance-per-use", 1.0, "anvil-damage.enabled", false))) {
            AnvilCart type = anvil(config);
            InMemoryCart cart = openAnvilCart(type, Material.ANVIL);

            for (int i = 0; i < 10; i++) {
                clickResult(type, true);
            }

            assertEquals(Material.ANVIL, cart.blockType(), config.toString());
        }
    }

    @Test
    void brokenAnvilDropsWhenConfigured() {
        AnvilCart type = anvil(Map.of("anvil-damage.chance-per-use", 1.0, "anvil-damage.drop-on-break", true));
        openAnvilCart(type, Material.DAMAGED_ANVIL);

        clickResult(type, true);

        assertEquals(1, world.getEntitiesByClass(Item.class).stream()
                .filter(item -> item.getItemStack().getType() == Material.DAMAGED_ANVIL).count());
    }

    private AnvilView anvilView() {
        return (AnvilView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AnvilView.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPlayer" -> player;
                    case "getTopInventory" -> anvilInventory;
                    case "getBottomInventory" -> player.getInventory();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType() == boolean.class ? false
                            : method.getReturnType() == int.class ? 0 : null;
                });
    }
}
