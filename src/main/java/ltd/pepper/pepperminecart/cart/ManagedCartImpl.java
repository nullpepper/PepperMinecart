package ltd.pepper.pepperminecart.cart;

import java.util.logging.Level;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.view.builder.LocationInventoryViewBuilder;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/** {@link ManagedCart} 的实现：矿车实体 + 类型，数据全部读写实体本身，因此可以随用随建。 */
final class ManagedCartImpl implements ManagedCart {

    private final CartManager manager;
    private final CartType type;
    private Minecart minecart;

    ManagedCartImpl(CartManager manager, Minecart minecart, CartType type) {
        this.manager = manager;
        this.minecart = minecart;
        this.type = type;
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
        try {
            ItemStack stored = CartData.blockItem(minecart);
            if (stored != null) {
                return stored;
            }
        } catch (RuntimeException e) {
            manager.logger().log(Level.WARNING, "矿车 " + minecart.getUniqueId() + " 保存的方块物品已损坏，无法读取", e);
        }
        return type.nativeBlock(minecart);
    }

    @Override
    public Material blockType() {
        ItemStack block = blockItem();
        return block == null ? Material.AIR : block.getType();
    }

    @Override
    public void setBlockItem(ItemStack blockItem) {
        CartData.setBlockItem(minecart, blockItem);
        showBlock(blockItem);
    }

    /** 按类型显示方块；类型返回 null 时保持矿车原版外观。 */
    void showBlock(ItemStack blockItem) {
        BlockData display = type.displayBlock(blockItem);
        if (display != null) {
            minecart.setDisplayBlockData(display);
            minecart.setDisplayBlockOffset(manager.config().displayBlockOffset());
        }
    }

    @Override
    public void removeBlock() {
        CartData.clear(minecart, type.id().getNamespace());
        minecart.setDisplayBlockData(null); // null = 恢复该矿车的原版显示
        manager.untrack(minecart);
    }

    @Override
    public void dropItem(ItemStack item) {
        minecart.getWorld().dropItemNaturally(minecart.getLocation(), item);
    }

    @Override
    public @Nullable InventoryView openMenu(Player player, MenuType.Typed<?, ? extends LocationInventoryViewBuilder<?>> menu,
                                            @Nullable Runnable onClose) {
        return manager.menus().openMenu(player, minecart, type.id(), menu, onClose);
    }

    @Override
    public @Nullable InventoryView openInventory(Player player, Inventory inventory, @Nullable Runnable onClose) {
        return manager.menus().openInventory(player, minecart, type.id(), inventory, onClose);
    }

    @Override
    public <T extends Minecart> T convertTo(Class<T> minecartClass) {
        T replacement = manager.replaceEntity(minecart, minecartClass, type.id().getNamespace());
        minecart = replacement;
        return replacement;
    }

    @Override
    public boolean isCoolingDown(NamespacedKey key) {
        long readyAt = minecart.getPersistentDataContainer().getOrDefault(key, PersistentDataType.LONG, 0L);
        return minecart.getWorld().getGameTime() < readyAt;
    }

    @Override
    public void startCooldown(NamespacedKey key, int ticks) {
        long readyAt = minecart.getWorld().getGameTime() + Math.max(0, ticks);
        minecart.getPersistentDataContainer().set(key, PersistentDataType.LONG, readyAt);
    }
}
