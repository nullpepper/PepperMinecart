package ltd.pepper.pepperminecart.type;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

/**
 * 铁砧：右键打开原版铁砧界面；按原版规则，生存模式下每次取走结果都有几率让铁砧受损
 * （铁砧 → 开裂的铁砧 → 损坏的铁砧 → 损毁），几率与开关见 config.yml 的 anvil-damage。
 *
 * <p>原版的受损逻辑写在铁砧方块上（{@code AnvilMenu#onTake} 只在该位置真有铁砧时生效），矿车上没有真方块，
 * 所以由这里按同样的规则执行。“取走结果”的识别方式：玩家点击结果槽后，原版会在同一次点击里清空第一个
 * 输入槽并重新计算结果（触发 {@link PrepareAnvilEvent}），没取走（经验不够、背包满了）则不会清空。</p>
 */
public final class AnvilCart implements CartType, Listener {

    public static final NamespacedKey ID = new NamespacedKey("pepperminecart", "anvil");

    private final PluginConfig config;
    /** 玩家 → 他正在使用的铁砧矿车。 */
    private final Map<UUID, ManagedCart> openAnvils = new HashMap<>();
    /** 玩家最近一次点击结果槽的 tick。 */
    private final Map<UUID, Integer> resultClickTicks = new HashMap<>();

    public AnvilCart(PluginConfig config) {
        this.config = config;
    }

    @Override
    public NamespacedKey id() {
        return ID;
    }

    @Override
    public Set<Material> blocks() {
        return Set.of(Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL);
    }

    @Override
    public boolean onUse(ManagedCart cart, Player player) {
        UUID playerId = player.getUniqueId();
        if (cart.openMenu(player, MenuType.ANVIL, () -> closed(playerId)) != null) {
            opened(player, cart);
        }
        return true;
    }

    void opened(Player player, ManagedCart cart) {
        openAnvils.put(player.getUniqueId(), cart);
    }

    private void closed(UUID playerId) {
        openAnvils.remove(playerId);
        resultClickTicks.remove(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        UUID playerId = event.getWhoClicked().getUniqueId();
        if (!openAnvils.containsKey(playerId)) {
            return;
        }
        if (event.getSlotType() == InventoryType.SlotType.RESULT) {
            resultClickTicks.put(playerId, Bukkit.getCurrentTick());
        } else {
            resultClickTicks.remove(playerId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        ManagedCart cart = openAnvils.get(playerId);
        Integer clickTick = resultClickTicks.get(playerId);
        if (cart == null || clickTick == null || clickTick != Bukkit.getCurrentTick()) {
            return;
        }
        ItemStack firstInput = event.getInventory().getFirstItem();
        if (firstInput == null || firstInput.isEmpty()) {
            resultClickTicks.remove(playerId);
            resultTaken(cart, player);
        }
    }

    /** 原版 {@code AnvilBlock#damage} 的规则；创造模式不会让铁砧受损。 */
    private void resultTaken(ManagedCart cart, Player player) {
        if (!config.anvilDamageEnabled() || player.getGameMode() == GameMode.CREATIVE
                || ThreadLocalRandom.current().nextDouble() >= config.anvilDamageChance()) {
            return;
        }
        ItemStack anvil = cart.blockItem();
        if (anvil == null) {
            return;
        }
        Material worn = worn(anvil.getType());
        if (worn != null) {
            cart.setBlockItem(anvil.withType(worn)); // “使用铁砧”的音效原版界面已经播放
            return;
        }
        // 用坏了：先把损坏的铁砧交出去，成功之后才让方块消失；
        // 界面在下一 tick 因方块不在了而关闭，与原版一致
        Location location = cart.minecart().getLocation();
        if (config.anvilDropOnBreak()) {
            // Paper 的 dropItemNaturally 即使生成被取消也返回实体，只能靠实体是否还有效判断
            var dropped = location.getWorld().dropItemNaturally(location, anvil);
            if (dropped == null || !dropped.isValid()) {
                // 掉落被其他插件取消：保留铁砧，否则物品会直接消失
                Bukkit.getLogger().warning("铁砧矿车 " + cart.minecart().getUniqueId() + " 报废后的掉落被其他插件取消，已取消报废");
                return;
            }
        }
        cart.removeBlock();
        location.getWorld().playSound(location, Sound.BLOCK_ANVIL_DESTROY, SoundCategory.BLOCKS, 1.0F, 1.0F);
    }

    private static @Nullable Material worn(Material anvil) {
        return switch (anvil) {
            case ANVIL -> Material.CHIPPED_ANVIL;
            case CHIPPED_ANVIL -> Material.DAMAGED_ANVIL;
            default -> null;
        };
    }
}
