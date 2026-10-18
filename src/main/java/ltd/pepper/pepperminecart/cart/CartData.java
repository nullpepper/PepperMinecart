package ltd.pepper.pepperminecart.cart;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/**
 * 插件写在矿车实体上的数据（PersistentDataContainer，随实体一起保存）。
 *
 * <ul>
 *   <li>{@code pepperminecart:cart-type}：矿车类型 id；有它即视为“载着方块的矿车”；</li>
 *   <li>{@code pepperminecart:item}：方块物品，用 Paper 的物品序列化保存（含容器内容、自定义名等全部组件，
 *       并随原版数据版本自动升级）。</li>
 * </ul>
 *
 * 键名与 1.0 版本保持一致，已有存档无需迁移。
 */
public final class CartData {

    static final String NAMESPACE = "pepperminecart";

    private static final NamespacedKey TYPE = new NamespacedKey(NAMESPACE, "cart-type");
    private static final NamespacedKey BLOCK_ITEM = new NamespacedKey(NAMESPACE, "item");

    private CartData() {
    }

    public static boolean isManaged(Minecart minecart) {
        return minecart.getPersistentDataContainer().has(TYPE);
    }

    /** 矿车类型 id；未受管或 id 已损坏时返回 null。 */
    public static @Nullable NamespacedKey type(Minecart minecart) {
        String id = minecart.getPersistentDataContainer().get(TYPE, PersistentDataType.STRING);
        return id == null ? null : NamespacedKey.fromString(id);
    }

    /**
     * 保存的方块物品；没有时返回 null。
     *
     * @throws RuntimeException 数据损坏、无法反序列化时
     */
    public static @Nullable ItemStack blockItem(Minecart minecart) {
        byte[] bytes = minecart.getPersistentDataContainer().get(BLOCK_ITEM, PersistentDataType.BYTE_ARRAY);
        return bytes == null ? null : ItemStack.deserializeBytes(bytes);
    }

    static void write(Minecart minecart, NamespacedKey type, ItemStack blockItem) {
        minecart.getPersistentDataContainer().set(TYPE, PersistentDataType.STRING, type.toString());
        setBlockItem(minecart, blockItem);
    }

    static void setBlockItem(Minecart minecart, ItemStack blockItem) {
        minecart.getPersistentDataContainer().set(BLOCK_ITEM, PersistentDataType.BYTE_ARRAY, blockItem.serializeAsBytes());
    }

    /** 删除插件自己的数据，以及类型命名空间下的数据（类型自己写在矿车上的冷却、状态等）。 */
    static void clear(Minecart minecart, String typeNamespace) {
        PersistentDataContainer data = minecart.getPersistentDataContainer();
        // 先复制键集：getKeys() 是活视图，边遍历边删除会抛 ConcurrentModificationException
        for (NamespacedKey key : List.copyOf(data.getKeys())) {
            if (key.getNamespace().equals(NAMESPACE) || key.getNamespace().equals(typeNamespace)) {
                data.remove(key);
            }
        }
    }
}
