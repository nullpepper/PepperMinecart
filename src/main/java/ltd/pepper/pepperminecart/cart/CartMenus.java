package ltd.pepper.pepperminecart.cart;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;
import org.jetbrains.annotations.Nullable;

/**
 * 记录玩家正在使用的矿车界面，并按原版规则关闭它们。
 *
 * <p>原版的方块界面每 tick 检查“方块还在、玩家没走远”（{@code AbstractContainerMenu#stillValid}），
 * 箱子矿车的界面检查“矿车还在、玩家没走远”。矿车上的方块界面没有真实方块可查，
 * 所以打开时关闭原版检查，改由这里按矿车做同样的检查：矿车消失、方块被取下或换了类型、
 * 玩家离开 8 格，界面就关闭。界面里的物品如何归还由原版界面自己负责（关闭时退回玩家背包）。</p>
 */
public final class CartMenus implements Listener {

    /** 与原版容器的可用距离一致：8 格。 */
    private static final double MAX_DISTANCE_SQUARED = 8.0 * 8.0;

    private final Map<UUID, OpenMenu> openMenus = new HashMap<>();

    private record OpenMenu(Player player, Minecart cart, NamespacedKey type, InventoryView view,
                            @Nullable Runnable onClose) {

        boolean stillValid() {
            return cart.isValid()
                    && type.equals(CartData.type(cart))
                    && player.getWorld().equals(cart.getWorld())
                    && player.getLocation().distanceSquared(cart.getLocation()) <= MAX_DISTANCE_SQUARED;
        }

        void closed() {
            if (onClose != null) {
                onClose.run();
            }
        }
    }

    @Nullable InventoryView openMenu(Player player, Minecart cart, NamespacedKey type,
                                     MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menu,
                                     @Nullable Runnable onClose) {
        InventoryView view = menu.builder()
                .location(cart.getLocation())
                .checkReachable(false) // 矿车处没有真实方块，可用性改由 tick() 按矿车检查
                .build(player);
        player.openInventory(view);
        return track(player, cart, type, view, onClose);
    }

    @Nullable InventoryView openInventory(Player player, Minecart cart, NamespacedKey type, Inventory inventory,
                                          @Nullable Runnable onClose) {
        InventoryView view = player.openInventory(inventory);
        return view == null ? null : track(player, cart, type, view, onClose);
    }

    /** 打开界面可能被其他插件取消（InventoryOpenEvent），只登记真正打开的界面。 */
    private @Nullable InventoryView track(Player player, Minecart cart, NamespacedKey type, InventoryView view,
                                          @Nullable Runnable onClose) {
        if (player.getOpenInventory().getTopInventory() != view.getTopInventory()) {
            return null;
        }
        openMenus.put(player.getUniqueId(), new OpenMenu(player, cart, type, view, onClose));
        return view;
    }

    /** 关闭某辆矿车的全部界面（方块即将取下或矿车被摧毁时）：界面中的改动先于后续流程写回。 */
    void closeAll(Minecart cart) {
        for (OpenMenu menu : List.copyOf(openMenus.values())) {
            if (menu.cart().getUniqueId().equals(cart.getUniqueId())) {
                menu.player().closeInventory(InventoryCloseEvent.Reason.CANT_USE);
            }
        }
    }

    /** 每 tick：关闭已经不能再用的界面。 */
    void tick() {
        for (OpenMenu menu : List.copyOf(openMenus.values())) {
            if (!menu.player().isOnline()) {
                forget(menu); // 正常情况下下线时原版已关闭界面；这里只是兜底，保证写回不被漏掉
            } else if (!menu.stillValid()) {
                menu.player().closeInventory(InventoryCloseEvent.Reason.CANT_USE);
            }
        }
    }

    /** 插件禁用时：写回并关闭全部界面。 */
    void closeEverything() {
        for (OpenMenu menu : List.copyOf(openMenus.values())) {
            forget(menu);
            menu.player().closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        }
    }

    private void forget(OpenMenu menu) {
        if (openMenus.remove(menu.player().getUniqueId(), menu)) {
            menu.closed();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        OpenMenu menu = openMenus.get(event.getPlayer().getUniqueId());
        // 打开新界面时旧界面会先触发关闭事件，只处理登记的那个界面
        if (menu != null && menu.view().getTopInventory() == event.getView().getTopInventory()) {
            forget(menu);
        }
    }
}
