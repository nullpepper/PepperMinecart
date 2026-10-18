package ltd.pepper.pepperminecart.cart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import ltd.pepper.pepperminecart.api.CartType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/** 已注册的矿车类型：按 id 查找（读取存档）与按方块查找（放置方块）。 */
public final class CartRegistry {

    /** 原版与 Bukkit 的命名空间：类型数据随取下清除，绝不能波及这两个命名空间。 */
    private static final Set<String> FORBIDDEN_NAMESPACES = Set.of("minecraft", "bukkit");

    private final Logger logger;
    private final Map<NamespacedKey, Registration> byId = new LinkedHashMap<>();
    private final Map<Material, CartType> byBlock = new HashMap<>();

    private record Registration(Plugin owner, CartType type) {
    }

    public CartRegistry(Logger logger) {
        this.logger = logger;
    }

    public void register(Plugin owner, CartType type) {
        NamespacedKey id = type.id();
        if (id == null || FORBIDDEN_NAMESPACES.contains(id.getNamespace())) {
            throw new IllegalArgumentException("矿车类型 id 不能为空，也不能使用 minecraft/bukkit 命名空间: " + id);
        }
        Set<Material> blocks = Set.copyOf(type.blocks());
        for (Material block : blocks) {
            if (!block.isBlock()) {
                throw new IllegalArgumentException("矿车类型 " + id + " 声明了非方块材质: " + block);
            }
        }

        if (byId.containsKey(id)) {
            logger.warning("矿车类型 " + id + " 被重新注册，旧类型已替换");
            unregister(id);
        }
        byId.put(id, new Registration(owner, type));
        for (Material block : blocks) {
            CartType previous = byBlock.put(block, type);
            if (previous != null) {
                logger.warning("方块 " + block + " 原属矿车类型 " + previous.id() + "，现改由 " + id + " 处理");
            }
        }
    }

    public void unregister(NamespacedKey id) {
        Registration removed = byId.remove(id);
        if (removed != null) {
            byBlock.values().removeIf(type -> type == removed.type());
        }
    }

    /** 注销某个插件注册的全部类型（该插件被禁用时调用）。 */
    public void unregisterAll(Plugin owner) {
        List<NamespacedKey> owned = new ArrayList<>();
        byId.forEach((id, registration) -> {
            if (registration.owner() == owner) {
                owned.add(id);
            }
        });
        owned.forEach(this::unregister);
    }

    public @Nullable CartType byId(@Nullable NamespacedKey id) {
        Registration registration = id == null ? null : byId.get(id);
        return registration == null ? null : registration.type();
    }

    public @Nullable CartType forBlock(Material block) {
        return byBlock.get(block);
    }

    public Collection<CartType> all() {
        return byId.values().stream().map(Registration::type).toList();
    }
}
