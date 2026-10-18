package ltd.pepper.pepperminecart.type;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.jetbrains.annotations.Nullable;

/**
 * 读写方块物品里的方块实体数据：容器内容（原版 {@code minecraft:container} 组件）与命令方块的命令。
 * 与原版一样，带着这些数据的方块物品放回世界后就是原来的那个方块。
 */
final class BlockItems {

    private BlockItems() {
    }

    /** 容器方块物品里装的东西（按槽位，可能含空槽）；不是容器时返回空数组。 */
    static ItemStack[] contents(ItemStack blockItem) {
        Container container = container(blockItem);
        return container == null ? new ItemStack[0] : container.getSnapshotInventory().getContents();
    }

    /** {@link #contents} 去掉空槽。 */
    static List<ItemStack> nonEmptyContents(ItemStack blockItem) {
        return nonEmpty(contents(blockItem));
    }

    static List<ItemStack> nonEmpty(ItemStack[] items) {
        return Arrays.stream(items).filter(Objects::nonNull).filter(item -> !item.isEmpty()).toList();
    }

    /** 换上新内容的副本；不是容器物品时返回原物品的副本。 */
    static ItemStack withContents(ItemStack blockItem, ItemStack[] contents) {
        ItemStack copy = blockItem.clone();
        if (copy.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof Container container) {
            container.getSnapshotInventory().setContents(contents);
            meta.setBlockState(container);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    /** 该容器方块的原版界面类型（木桶 → BARREL，潜影盒 → SHULKER_BOX……）；不是容器时返回 null。 */
    static @Nullable InventoryType inventoryType(ItemStack blockItem) {
        Container container = container(blockItem);
        return container == null ? null : container.getSnapshotInventory().getType();
    }

    /** 命令方块物品里的命令；没有时为空串。 */
    static String command(ItemStack blockItem) {
        if (blockItem.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof CommandBlock block) {
            return block.getCommand();
        }
        return "";
    }

    /** 写入命令的副本；不是命令方块物品时返回原物品的副本。 */
    static ItemStack withCommand(ItemStack blockItem, String command) {
        ItemStack copy = blockItem.clone();
        if (copy.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof CommandBlock block) {
            block.setCommand(command);
            meta.setBlockState(block);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private static @Nullable Container container(ItemStack blockItem) {
        return blockItem.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof Container container
                ? container
                : null;
    }
}
