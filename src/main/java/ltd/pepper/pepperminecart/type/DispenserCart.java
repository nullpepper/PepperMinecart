package ltd.pepper.pepperminecart.type;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.config.PluginConfig;
import ltd.pepper.pepperminecart.protection.ProtectionGateway;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.RedstoneRail;
import org.bukkit.entity.Minecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 发射器：一个 9 格容器（行为同 {@link ContainerCart}），驶过充能的激活铁轨时随机弹出一件物品，
 * 就像原版的漏斗矿车、TNT 矿车会对激活铁轨作出反应一样。间隔、偏移与速度见 config.yml 的 dispenser-cart。
 */
public final class DispenserCart extends ContainerCart {

    public static final NamespacedKey ID = new NamespacedKey("pepperminecart", "dispenser");

    /** 弹出冷却（保存在矿车上，取下方块时一并清除）。 */
    private static final NamespacedKey COOLDOWN = new NamespacedKey("pepperminecart", "dispenser-cooldown");

    private final PluginConfig config;
    private final ProtectionGateway protection;

    public DispenserCart(PluginConfig config, ProtectionGateway protection) {
        super(ID, Set.of(Material.DISPENSER), config);
        this.config = config;
        this.protection = protection;
    }

    @Override
    public void onMove(ManagedCart cart) {
        Minecart minecart = cart.minecart();
        if (cart.isCoolingDown(COOLDOWN) || !isOnPoweredActivatorRail(minecart)) {
            return;
        }
        // 弹出由铁轨触发，没有玩家参与：按“环境来源”询问领地保护
        if (protection.denyEnvironmentEject(minecart.getLocation())) {
            return;
        }
        if (dispense(cart)) {
            cart.startCooldown(COOLDOWN, config.dispenserCooldownTicks());
        }
    }

    /** 与原版矿车相同：铁轨在矿车所在方块，或者矿车略高时在其下方。 */
    private static boolean isOnPoweredActivatorRail(Minecart minecart) {
        Block block = minecart.getLocation().getBlock();
        return isPoweredActivatorRail(block) || isPoweredActivatorRail(block.getRelative(BlockFace.DOWN));
    }

    private static boolean isPoweredActivatorRail(Block block) {
        return block.getType() == Material.ACTIVATOR_RAIL
                && block.getBlockData() instanceof RedstoneRail rail
                && rail.isPowered();
    }

    /** 从随机一个非空槽位取出一件物品弹出去；库存为空时返回 false。 */
    private boolean dispense(ManagedCart cart) {
        Inventory inventory = inventory(cart);
        if (inventory == null) {
            return false;
        }
        List<Integer> filledSlots = IntStream.range(0, inventory.getSize())
                .filter(slot -> {
                    ItemStack item = inventory.getItem(slot);
                    return item != null && !item.isEmpty();
                })
                .boxed()
                .toList();
        if (filledSlots.isEmpty()) {
            return false;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int slot = filledSlots.get(random.nextInt(filledSlots.size()));
        ItemStack stack = inventory.getItem(slot);

        Minecart minecart = cart.minecart();
        double offset = config.dispenserEjectOffset();
        Location from = minecart.getLocation().add(
                random.nextDouble(-1, 1) * offset,
                0.4 + random.nextDouble() * offset * 0.5,
                random.nextDouble(-1, 1) * offset);
        Vector direction = new Vector(random.nextDouble() - 0.5, random.nextDouble() * 0.4 + 0.2, random.nextDouble() - 0.5);
        Vector velocity = direction.normalize().multiply(config.dispenserEjectSpeed());
        // 先弹出，成功后才从库存里扣：弹出被其他插件取消时物品留在矿车里，也不进入冷却
        var ejected = minecart.getWorld().dropItem(from, stack.asOne(), item -> item.setVelocity(velocity));
        if (ejected == null || !ejected.isValid()) {
            return false;
        }
        // dropItem 会同步触发 ItemSpawnEvent，外部监听器可能在这一瞬间改过这一格：
        // 只有确认还是同一份物品时才扣减，不要用过期快照覆盖玩家的改动
        ItemStack now = inventory.getItem(slot);
        if (now == null || !now.isSimilar(stack)) {
            return false;
        }
        inventory.setItem(slot, now.getAmount() > 1 ? now.asQuantity(now.getAmount() - 1) : null);
        save(cart, inventory);
        minecart.getWorld().playSound(minecart.getLocation(), Sound.BLOCK_DISPENSER_DISPENSE, SoundCategory.BLOCKS, 1.0F, 1.0F);
        return true;
    }
}
