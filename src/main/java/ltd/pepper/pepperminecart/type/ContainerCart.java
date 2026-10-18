package ltd.pepper.pepperminecart.type;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import ltd.pepper.pepperminecart.api.CartType;
import ltd.pepper.pepperminecart.api.ManagedCart;
import ltd.pepper.pepperminecart.config.ContainerPickupPolicy;
import ltd.pepper.pepperminecart.config.PluginConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

/**
 * 容器方块（木桶、投掷器、潜影盒、酿造台……）：右键打开它的原版界面，内容保存在矿车上的方块物品里。
 *
 * <p>方块物品本身就能装东西（原版的 {@code minecraft:container} 组件，潜影盒就是这样随身携带内容的），
 * 所以取下或破坏矿车时交还的是带着内容的方块物品，放回世界就是原来那个容器。
 * 界面使用该方块的原版界面类型，槽位规则（潜影盒里不能放潜影盒、燃料槽只放燃料……）由原版负责。
 * 方块并不真的在世界里，所以熔炉不会烧炼、酿造台不会酿造，它们在矿车上只用来储物。</p>
 *
 * <p>取下规则受 {@code container-pickup-policy} 约束（潜影盒除外，它本就是随身容器）。</p>
 */
public class ContainerCart implements CartType {

    public static final NamespacedKey ID = new NamespacedKey("pepperminecart", "container");

    private final NamespacedKey id;
    private final Set<Material> blocks;
    private final PluginConfig config;
    /** 正在被查看的矿车库存。同一辆矿车的所有查看者共用一份，与原版容器一致。 */
    private final Map<UUID, Inventory> openInventories = new HashMap<>();

    public ContainerCart(PluginConfig config) {
        this(ID, defaultBlocks(), config);
    }

    protected ContainerCart(NamespacedKey id, Set<Material> blocks, PluginConfig config) {
        this.id = id;
        this.blocks = Set.copyOf(blocks);
        this.config = config;
    }

    /** 箱子与漏斗不在其中：它们会变成原版的箱子矿车、漏斗矿车（见 {@link VanillaMinecartCart}）。 */
    private static Set<Material> defaultBlocks() {
        Set<Material> blocks = new HashSet<>(Tag.SHULKER_BOXES.getValues());
        blocks.addAll(Set.of(Material.BARREL, Material.TRAPPED_CHEST, Material.DROPPER,
                Material.BREWING_STAND, Material.CRAFTER));
        return blocks;
    }

    @Override
    public NamespacedKey id() {
        return id;
    }

    @Override
    public Set<Material> blocks() {
        return blocks;
    }

    @Override
    public boolean onUse(ManagedCart cart, Player player) {
        UUID cartId = cart.minecart().getUniqueId();
        Inventory inventory = openInventories.get(cartId);
        boolean firstViewer = inventory == null;
        if (inventory == null) {
            inventory = loadInventory(cart);
            if (inventory == null) {
                return false;
            }
        }
        Inventory shared = inventory;
        if (cart.openInventory(player, shared, () -> closed(cart, shared, player)) != null) {
            openInventories.put(cartId, shared);
            if (firstViewer) {
                playContainerSound(cart, true);
            }
        }
        return true;
    }

    private void closed(ManagedCart cart, Inventory inventory, Player player) {
        save(cart, inventory);
        boolean lastViewer = inventory.getViewers().stream().allMatch(viewer -> viewer.equals(player));
        if (lastViewer) {
            openInventories.remove(cart.minecart().getUniqueId(), inventory);
            playContainerSound(cart, false);
        }
    }

    @Override
    public boolean canTakeOff(ManagedCart cart, Player player) {
        if (followsPickupPolicy(cart) && config.containerPickupPolicy() == ContainerPickupPolicy.FORBIDDEN) {
            player.sendMessage("容器类方块取下已在配置中禁止");
            return false;
        }
        return true;
    }

    @Override
    public @Nullable ItemStack takeOff(ManagedCart cart) {
        ItemStack block = cart.blockItem();
        if (block != null && followsPickupPolicy(cart) && config.containerPickupPolicy() == ContainerPickupPolicy.DROP) {
            ItemStack[] contents = BlockItems.contents(block);
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack content = contents[slot];
                if (content == null || content.isEmpty()) {
                    continue;
                }
                if (!cart.dropItem(content)) {
                    // 有物品掉不出来（掉落被其他插件取消）：把它和后面还没掉出去的留在方块物品里交给玩家；
                    // 前面已经掉出去的必须从内容里扣掉，否则会复制
                    ItemStack[] remaining = contents.clone();
                    for (int done = 0; done < slot; done++) {
                        remaining[done] = null;
                    }
                    return BlockItems.withContents(block, remaining);
                }
            }
            block = BlockItems.withContents(block, new ItemStack[0]);
        }
        return block;
    }

    private static boolean followsPickupPolicy(ManagedCart cart) {
        return !Tag.SHULKER_BOXES.isTagged(cart.blockType());
    }

    /** 矿车当前的库存：有人正在看时用共享的那一份（所有人看到的是同一份），否则从方块物品读出。 */
    protected @Nullable Inventory inventory(ManagedCart cart) {
        Inventory open = openInventories.get(cart.minecart().getUniqueId());
        return open != null ? open : loadInventory(cart);
    }

    /** 把库存写回矿车上的方块物品。 */
    protected void save(ManagedCart cart, Inventory inventory) {
        ItemStack block = cart.blockItem();
        if (block != null) {
            cart.setBlockItem(BlockItems.withContents(block, inventory.getContents()));
        }
    }

    private static @Nullable Inventory loadInventory(ManagedCart cart) {
        ItemStack block = cart.blockItem();
        InventoryType type = block == null ? null : BlockItems.inventoryType(block);
        if (type == null) {
            return null;
        }
        Inventory inventory = Bukkit.createInventory(null, type, title(block));
        inventory.setContents(BlockItems.contents(block));
        return inventory;
    }

    /** 与原版容器一致：改过名的用它的名字，否则用方块名。 */
    private static Component title(ItemStack block) {
        ItemMeta meta = block.getItemMeta();
        return meta != null && meta.hasCustomName() ? meta.customName() : Component.translatable(block.getType());
    }

    /** 原版里木桶、箱子、潜影盒打开与关上都有声音（第一个人打开、最后一个人关上时），其余容器没有。 */
    private static void playContainerSound(ManagedCart cart, boolean opening) {
        Material block = cart.blockType();
        Sound sound;
        if (block == Material.BARREL) {
            sound = opening ? Sound.BLOCK_BARREL_OPEN : Sound.BLOCK_BARREL_CLOSE;
        } else if (block == Material.TRAPPED_CHEST) {
            sound = opening ? Sound.BLOCK_CHEST_OPEN : Sound.BLOCK_CHEST_CLOSE;
        } else if (Tag.SHULKER_BOXES.isTagged(block)) {
            sound = opening ? Sound.BLOCK_SHULKER_BOX_OPEN : Sound.BLOCK_SHULKER_BOX_CLOSE;
        } else {
            return;
        }
        float pitch = 0.9F + ThreadLocalRandom.current().nextFloat() * 0.1F;
        cart.minecart().getWorld().playSound(cart.minecart().getLocation(), sound, SoundCategory.BLOCKS, 0.5F, pitch);
    }
}
