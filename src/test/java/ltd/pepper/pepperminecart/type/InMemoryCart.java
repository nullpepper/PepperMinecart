package ltd.pepper.pepperminecart.type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;
import org.jetbrains.annotations.Nullable;

/**
 * 把方块物品放在内存里的 {@link ManagedCart}，用于单独测试某个矿车类型。
 * （MockBukkit 序列化物品时会丢掉容器内容，经过真实存储就无法检查内容。）
 */
final class InMemoryCart implements ManagedCart {

    private final CartType type;
    private Minecart minecart;
    private @Nullable ItemStack block;
    private final Map<NamespacedKey, Long> cooldowns = new HashMap<>();
    final List<ItemStack> drops = new ArrayList<>();
    final List<Runnable> closeCallbacks = new ArrayList<>();
    /** 还能成功掉落几次；0 表示之后的掉落全被取消（模拟领地/防掉落插件）。 */
    private int dropBudget = Integer.MAX_VALUE;

    InMemoryCart(Minecart minecart, CartType type, @Nullable ItemStack block) {
        this.minecart = minecart;
        this.type = type;
        this.block = block == null ? null : block.clone();
    }

    /** 模拟所有打开的界面被关闭。 */
    void closeMenus() {
        List.copyOf(closeCallbacks).forEach(Runnable::run);
        closeCallbacks.clear();
    }

    @Override
    public Minecart minecart() {
        return minecart;
    }

    @Override
    public CartType type() {
        return type;
    }

    @Override
    public @Nullable ItemStack blockItem() {
        return block != null ? block.clone() : type.nativeBlock(minecart);
    }

    @Override
    public Material blockType() {
        ItemStack item = blockItem();
        return item == null ? Material.AIR : item.getType();
    }

    @Override
    public void setBlockItem(ItemStack blockItem) {
        block = blockItem.clone();
    }

    @Override
    public void removeBlock() {
        block = null;
    }

    @Override
    public boolean dropItem(ItemStack item) {
        if (dropBudget <= 0) {
            return false;
        }
        dropBudget--;
        drops.add(item.clone());
        return true;
    }

    /** 让前 {@code successfulDrops} 次掉落成功、之后的全部被取消。 */
    void failDropsAfter(int successfulDrops) {
        this.dropBudget = successfulDrops;
    }

    @Override
    public @Nullable InventoryView openMenu(Player player, MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menu,
                                            @Nullable Runnable onClose) {
        throw new UnsupportedOperationException("MockBukkit 不能构建 MenuType 界面");
    }

    @Override
    public @Nullable InventoryView openInventory(Player player, Inventory inventory, @Nullable Runnable onClose) {
        InventoryView view = player.openInventory(inventory);
        if (view != null && onClose != null) {
            closeCallbacks.add(onClose);
        }
        return view;
    }

    @Override
    public <T extends Minecart> T convertTo(Class<T> minecartClass) {
        T replacement = minecart.getWorld().spawn(minecart.getLocation(), minecartClass);
        minecart.remove();
        minecart = replacement;
        return replacement;
    }

    @Override
    public boolean isCoolingDown(NamespacedKey key) {
        return minecart.getWorld().getGameTime() < cooldowns.getOrDefault(key, 0L);
    }

    @Override
    public void startCooldown(NamespacedKey key, int ticks) {
        cooldowns.put(key, minecart.getWorld().getGameTime() + ticks);
    }
}
