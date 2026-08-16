package com.pepperminecart.delivery;

import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 统一的物品交付/掉落原语。
 *
 * <p>Bukkit 中 {@link World#dropItemNaturally(Location, ItemStack)} 会触发可被其他插件取消的
 * {@code ItemSpawnEvent}；事件被取消时返回 null，物品实体不会生成。调用方必须依据返回值决定
 * 是否清空源槽位/源库存，否则会出现“容器已扣物品、地上却没有”的静默丢失。
 * 此前该规则散落在多个调用点且全部忽略返回值，本类收敛为唯一的交付判定。</p>
 */
public final class ItemDelivery {

    /** 交付结果：源槽位只有在 DELIVERED 时才允许清空。 */
    public enum Result {
        /** 已全部进入玩家背包或全部成功掉落。 */
        DELIVERED,
        /** 掉落事件被其他插件取消（或部分取消），物品未被安全交付。 */
        CANCELED,
        /** 无可用掉落位置，物品未被交付。 */
        NO_LOCATION
    }

    private ItemDelivery() {
    }

    /**
     * 先尝试放入玩家背包；放不下的部分掉落到 {@code fallbackWorld}/{@code fallbackLocation}。
     * 任一次掉落被取消或位置不可用，返回非 DELIVERED，调用方必须保留源物品。
     */
    public static Result giveOrDrop(Player player, ItemStack item, World fallbackWorld, Location fallbackLocation) {
        if (item == null || item.getType().isAir()) {
            return Result.DELIVERED;
        }
        if (player != null) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(item.clone());
            if (left.isEmpty()) {
                return Result.DELIVERED;
            }
            return dropAll(fallbackWorld, fallbackLocation, left.values().toArray(ItemStack[]::new));
        }
        return dropAll(fallbackWorld, fallbackLocation, item);
    }

    /**
     * 掉落一个物品；返回生成的实体。世界/位置不可用或 {@code ItemSpawnEvent} 被取消时返回 null。
     */
    public static Item dropItem(World world, Location location, ItemStack item) {
        if (world == null || location == null || item == null || item.getType().isAir()) {
            return null;
        }
        return world.dropItemNaturally(location, item.clone());
    }

    /**
     * 依次掉落全部物品：只有全部成功才返回 DELIVERED；
     * 中途有掉落被取消返回 CANCELED；位置不可用返回 NO_LOCATION。
     */
    public static Result dropAll(World world, Location location, ItemStack... items) {
        if (world == null || location == null || location.getWorld() == null) {
            return Result.NO_LOCATION;
        }
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (world.dropItemNaturally(location, item.clone()) == null) {
                return Result.CANCELED;
            }
        }
        return Result.DELIVERED;
    }
}
