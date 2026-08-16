package com.pepperminecart.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pepperminecart.api.CartTypeHandler;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** CartTypeRegistry id / 材质路由一致性测试（覆盖同 id 覆盖注册与注销场景）。 */
class CartTypeRegistryTest {

    private static CartTypeHandler handler(NamespacedKey id, Set<Material> materials) {
        return new CartTypeHandler() {
            @Override
            public NamespacedKey getId() {
                return id;
            }

            @Override
            public Set<Material> handledMaterials() {
                return materials;
            }
        };
    }

    @Test
    void reRegisterSameIdCleansOldMaterialMappings() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        CartTypeHandler first = handler(NamespacedKey.fromString("test:cart"), Set.of(Material.CHEST));
        CartTypeHandler second = handler(NamespacedKey.fromString("test:cart"), Set.of(Material.HOPPER));
        registry.register(first);
        registry.register(second);

        // 同 id 覆盖后：byId 指向新处理器，旧处理器的材质映射被清理，不再指向已覆盖的旧处理器
        assertEquals(second, registry.byId(NamespacedKey.fromString("test:cart")));
        assertNull(registry.byMaterial(Material.CHEST));
        assertEquals(second, registry.byMaterial(Material.HOPPER));
    }

    @Test
    void unregisterRemovesOnlyCurrentOwner() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        CartTypeHandler a = handler(NamespacedKey.fromString("test:a"), Set.of(Material.CHEST));
        CartTypeHandler b = handler(NamespacedKey.fromString("test:b"), Set.of(Material.CHEST));
        registry.register(a);
        registry.register(b); // b 接管 CHEST 的路由
        registry.unregister(NamespacedKey.fromString("test:b"));

        // b 注销后 CHEST 不再路由到已注销的 b；a 被 b 覆盖、未重新注册，不自动恢复
        assertNull(registry.byMaterial(Material.CHEST));
        assertNull(registry.byId(NamespacedKey.fromString("test:b")));
    }

    @Test
    void duplicateMaterialWarnedAndLastWins() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        CartTypeHandler a = handler(NamespacedKey.fromString("test:a"), Set.of(Material.CHEST));
        CartTypeHandler b = handler(NamespacedKey.fromString("test:b"), Set.of(Material.CHEST));
        registry.register(a);
        registry.register(b);

        assertEquals(b, registry.byMaterial(Material.CHEST));
        assertEquals(a, registry.byId(NamespacedKey.fromString("test:a")));
    }

    @Test
    void nullMaterialSetIsRejectedBeforeAnyStateChange() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        NamespacedKey id = NamespacedKey.fromString("test:null-mats");
        assertThrows(IllegalArgumentException.class, () -> registry.register(handler(id, null)));
        assertNull(registry.byId(id), "校验失败时 byId 不得半更新");
    }

    @Test
    void nullMaterialElementIsRejectedBeforeAnyStateChange() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        NamespacedKey id = NamespacedKey.fromString("test:null-element");
        Set<Material> materials = new HashSet<>();
        materials.add(Material.CHEST);
        materials.add(null);
        assertThrows(IllegalArgumentException.class, () -> registry.register(handler(id, materials)));
        assertNull(registry.byId(id), "校验失败时 byId 不得半更新");
        assertNull(registry.byMaterial(Material.CHEST), "校验失败时 byMaterial 不得半更新");
    }

    @Test
    void sameInstanceReRegisterWithChangedMaterialsCleansOldMapping() {
        CartTypeRegistry registry = new CartTypeRegistry(null);
        Set<Material> materials = new HashSet<>(Set.of(Material.CHEST));
        CartTypeHandler mutable = handler(NamespacedKey.fromString("test:mutable"), materials);
        registry.register(mutable);

        materials.remove(Material.CHEST);
        materials.add(Material.HOPPER);
        registry.register(mutable);

        assertEquals(mutable, registry.byMaterial(Material.HOPPER));
        assertNull(registry.byMaterial(Material.CHEST), "同一实例重注册后旧材质映射必须清理");
    }
}
