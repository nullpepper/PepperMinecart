package com.pepperminecart.registry;

import com.pepperminecart.api.CartTypeHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/** id / Material → CartTypeHandler 注册表。 */
public class CartTypeRegistry {

    private final Logger logger;
    private final Map<NamespacedKey, CartTypeHandler> byId = new HashMap<>();
    private final Map<Material, CartTypeHandler> byMaterial = new HashMap<>();

    public CartTypeRegistry(Logger logger) {
        this.logger = logger;
    }

    public void register(CartTypeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        if (handler.getId() == null) {
            throw new IllegalArgumentException("handler.getId() 不能为 null");
        }
        // 先完整校验输入，再修改注册表：避免先改 byId、遍历材质时抛异常造成半更新
        Set<Material> materials = handler.handledMaterials();
        if (materials == null) {
            throw new IllegalArgumentException("handler.handledMaterials() 不能为 null: " + handler.getId());
        }
        for (Material material : materials) {
            if (material == null) {
                throw new IllegalArgumentException("handler.handledMaterials() 不能包含 null 材质: " + handler.getId());
            }
        }

        CartTypeHandler old = byId.get(handler.getId());
        if (old != null) {
            if (old != handler) {
                warn("矿车类型 id 重复注册，已覆盖: " + handler.getId());
            }
            // 撤销旧处理器仍持有的全部材质映射：按 byMaterial 当前路由清理，
            // 不依赖 old.handledMaterials() 的当前返回值（同一实例的动态集合可能已经变化）
            for (Map.Entry<Material, CartTypeHandler> entry : new ArrayList<>(byMaterial.entrySet())) {
                if (entry.getValue() == old) {
                    byMaterial.remove(entry.getKey(), old);
                }
            }
        }
        byId.put(handler.getId(), handler);
        for (Material m : materials) {
            CartTypeHandler prev = byMaterial.put(m, handler);
            if (prev != null && prev != handler) {
                warn("方块 " + m + " 已被 " + prev.getId() + " 注册，现由 " + handler.getId() + " 接管");
            }
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.warning(message);
        }
    }

    public void unregister(NamespacedKey id) {
        CartTypeHandler handler = byId.remove(id);
        if (handler != null) {
            for (Material m : handler.handledMaterials()) {
                byMaterial.remove(m, handler);
            }
        }
    }

    public CartTypeHandler byId(NamespacedKey id) {
        return id == null ? null : byId.get(id);
    }

    public CartTypeHandler byMaterial(Material material) {
        return material == null ? null : byMaterial.get(material);
    }

    public Collection<CartTypeHandler> all() {
        // 返回快照而非活视图：调用方（如引擎的 vanillaSupportHandler）遍历期间可能发生
        // register/unregister，byId.values() 活视图会抛 ConcurrentModificationException
        return Collections.unmodifiableCollection(new ArrayList<>(byId.values()));
    }
}
