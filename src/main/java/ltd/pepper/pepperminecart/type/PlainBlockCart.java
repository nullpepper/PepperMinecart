package ltd.pepper.pepperminecart.type;

import java.util.Set;
import ltd.pepper.pepperminecart.api.CartType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/**
 * 没有专门类型的方块：只是显示在矿车上，可以取下，矿车被破坏时掉落。
 * 类型已经不存在（所属插件被卸载）的矿车也按它处理，方块不会因此丢失。
 */
public final class PlainBlockCart implements CartType {

    public static final NamespacedKey ID = new NamespacedKey("pepperminecart", "generic");

    @Override
    public NamespacedKey id() {
        return ID;
    }

    @Override
    public Set<Material> blocks() {
        return Set.of();
    }
}
