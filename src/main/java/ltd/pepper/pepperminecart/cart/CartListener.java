package ltd.pepper.pepperminecart.cart;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;

/** 把 Bukkit 事件翻译成 {@link CartManager} 的操作。 */
public final class CartListener implements Listener {

    private final CartManager carts;

    public CartListener(CartManager carts) {
        this.carts = carts;
    }

    /**
     * 右键矿车：
     * <ul>
     *   <li>潜行 + 载着方块（或原版特殊矿车）→ 取下方块；</li>
     *   <li>潜行 + 空的普通矿车 + 手持方块 → 放上方块；</li>
     *   <li>不潜行 + 载着方块 → 使用（打开界面等）；</li>
     *   <li>其余情况，以及插件没有接手的点击，交给原版（例如坐上空矿车、打开箱子矿车）。</li>
     * </ul>
     * 使用 HIGH 优先级：让保护插件（通常是 HIGHEST）有机会先取消事件。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Minecart minecart)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("pepperminecart.use")) {
            return;
        }

        ManagedCartImpl cart = carts.find(minecart);
        if (player.isSneaking()) {
            if (cart == null) {
                cart = carts.findVanillaCart(minecart);
            }
            boolean handled = cart != null ? carts.takeOff(player, cart) : carts.place(player, minecart);
            if (handled) {
                event.setCancelled(true);
            }
        } else if (cart != null && carts.use(player, cart)) {
            event.setCancelled(true);
        }
    }

    /** 载着方块的矿车默认不能坐人（类型可通过 {@code allowsPassengers()} 放开）。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getMount() instanceof Minecart minecart && !carts.allowsPassengers(minecart)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            carts.onMove(minecart);
        }
    }

    /** 与旧版一致用 HIGHEST：被别的插件取消过的移除不该再触发本插件的销毁处理。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof Minecart minecart) {
            carts.onRemoved(minecart, event.getCause());
        }
    }

    /** 区块加载、实体生成等任何进入世界的途径都会触发，用来登记需要 tick 的矿车。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAddToWorld(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Minecart minecart) {
            carts.trackIfManaged(minecart);
        }
    }

    /** 扩展插件被禁用时注销它注册的矿车类型，避免继续调用已卸载插件的代码。 */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        carts.onPluginDisabled(event.getPlugin());
    }
}
