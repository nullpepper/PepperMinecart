package ltd.pepper.pepperminecart.protection;

import java.util.Optional;
import ltd.pepper.pepperminecart.api.event.CartBlockPlaceEvent;
import ltd.pepper.pepperminecart.api.event.CartBlockTakeOffEvent;
import ltd.pepper.pepperminecart.cart.CartData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 伪事件后端：模拟原版方块事件，兼容只认 BlockPlaceEvent/BlockBreakEvent 的保护插件（1.0 版本的唯一保护手段）。
 *
 * <p>每个动作依次发两个事件，任一被取消即拒绝：</p>
 * <ol>
 *   <li>矿车语义事件 {@link CartBlockPlaceEvent}/{@link CartBlockTakeOffEvent}——保护插件按
 *       「往矿车上放/取方块」精确判定（携带矿车实体与方块材质）；</li>
 *   <li>兼容旧插件的原版方块事件模拟 {@link BlockPlaceEvent}/{@link BlockBreakEvent}——
 *       以矿车所在方块（通常为脚下铁轨）作为代理方块。</li>
 * </ol>
 *
 * <p><b>拿不到位置即拒绝</b>（与历史实现一致）：宁可拦下合法操作，也不静默绕过领地保护。</p>
 *
 * <p><b>矿车实体来源</b>：端口签名只有 {@code (player, location, flagId)}，而语义事件需要矿车实体。
 * 调用方传入的 location 恒为 {@code cart.getLocation()}，故本实现按该位置就近查找矿车
 * （半径 0.5 格：原版碰撞使同一位置不可能容纳第二辆矿车，等价历史实现传入的那辆车）。
 * 查不到时退化为「只发原版方块事件」（仍保持旧插件的拦截能力）。</p>
 *
 * <p>{@link #blockUseFlag} 返回空、{@link #environmentDenied} 返回 false：伪事件策略天然
 * 无法覆盖「无对应方块事件的界面动作 / 无玩家来源的环境行为」，这两类由 Claim 后端负责。</p>
 */
public final class BukkitEventProtection implements ClaimProtection {

    /** 就近查找矿车的半径（格）：矿车 getLocation 为其中心，单辆车必命中。 */
    private static final double CART_LOOKUP_RADIUS = 0.5;

    @Override
    public boolean denied(Player player, Location location, String flagId) {
        if (player == null || location == null || location.getWorld() == null) {
            return true; // 等价历史实现：没有可信位置/发起者时按拒绝处理（fail-safe）
        }
        if (FLAG_PLACE.equals(flagId)) {
            return !checkPlace(player, location);
        }
        if (FLAG_DESTROY.equals(flagId)) {
            return !checkTakeOff(player, location);
        }
        return false; // 本策略只覆盖放置/取下
    }

    @Override
    public Optional<String> blockUseFlag(Material material) {
        return Optional.empty();
    }

    @Override
    public boolean environmentDenied(Location location, String flagId) {
        return false;
    }

    /** 放置：语义事件 + 原版方块事件模拟；返回 false = 被保护插件拒绝。 */
    private boolean checkPlace(Player player, Location location) {
        Material material = player.getInventory().getItemInMainHand().getType();
        Minecart cart = cartAt(location);
        if (cart != null) {
            CartBlockPlaceEvent cartEvent = new CartBlockPlaceEvent(player, cart, material);
            player.getServer().getPluginManager().callEvent(cartEvent);
            if (cartEvent.isCancelled()) {
                return false;
            }
        }
        Block block = location.getBlock();
        Block against = block.getRelative(BlockFace.DOWN);
        ItemStack item = new ItemStack(material);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), against, item, player, true,
                EquipmentSlot.HAND);
        player.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /** 取下：语义事件 + 原版方块事件模拟；返回 false = 被保护插件拒绝。 */
    private boolean checkTakeOff(Player player, Location location) {
        Minecart cart = cartAt(location);
        Material material = originalMaterialOf(cart);
        if (cart != null) {
            CartBlockTakeOffEvent cartEvent = new CartBlockTakeOffEvent(player, cart, material);
            player.getServer().getPluginManager().callEvent(cartEvent);
            if (cartEvent.isCancelled()) {
                return false;
            }
        }
        Block block = location.getBlock();
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        player.getServer().getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    /** 矿车上的方块：保存的方块物品优先；没有（例如原版箱子矿车）时取矿车显示的方块。 */
    private static Material originalMaterialOf(Minecart cart) {
        if (cart == null) {
            return Material.AIR;
        }
        try {
            ItemStack stored = CartData.blockItem(cart);
            if (stored != null) {
                return stored.getType();
            }
        } catch (RuntimeException ignored) {
            // 数据损坏时按显示的方块判定
        }
        BlockData display = cart.getDisplayBlockData();
        return display == null ? Material.AIR : display.getMaterial();
    }

    private static Minecart cartAt(Location location) {
        for (Entity entity : location.getWorld().getNearbyEntities(
                location, CART_LOOKUP_RADIUS, CART_LOOKUP_RADIUS, CART_LOOKUP_RADIUS)) {
            if (entity instanceof Minecart cart) {
                return cart;
            }
        }
        return null;
    }
}
