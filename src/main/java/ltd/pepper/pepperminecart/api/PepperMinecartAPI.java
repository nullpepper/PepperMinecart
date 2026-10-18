package ltd.pepper.pepperminecart.api;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Minecart;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * PepperMinecart 对外服务，通过 Bukkit ServicesManager 获取：
 *
 * <pre>{@code
 * PepperMinecartAPI api = getServer().getServicesManager().load(PepperMinecartAPI.class);
 * api.registerCartType(this, new MyCartType());
 * }</pre>
 *
 * 服务在 PepperMinecart 启用时注册，依赖方请在 plugin.yml 声明 {@code depend: [PepperMinecart]}，
 * 并在自己的 onEnable 中获取。所有方法都要求在主线程调用。
 */
public interface PepperMinecartAPI {

    /**
     * 注册矿车类型。{@code owner} 被禁用时其注册的类型会自动注销。
     * 相同 id 会替换旧类型；若方块已被其他类型使用，之后放置的该方块改用新类型（已有矿车不受影响）。
     *
     * @throws IllegalArgumentException id 使用了保留命名空间（{@code minecraft}、{@code bukkit}、
     *                                  {@code pepperminecart}），或方块集合含有非方块材质
     */
    void registerCartType(Plugin owner, CartType type);

    /**
     * 注销矿车类型。该类型的已有矿车改按普通方块矿车处理，方块不会丢失。内置类型不可注销。
     *
     * @throws NullPointerException {@code id} 为 {@code null}（注册那边用的是
     *                              {@link IllegalArgumentException}，这里对齐 {@code Map#remove} 的约定）
     */
    void unregisterCartType(NamespacedKey id);

    @Nullable CartType getCartType(NamespacedKey id);

    /** 放上该方块会产生的矿车类型；没有专门类型的方块返回 null（按普通方块处理）。 */
    @Nullable CartType getCartTypeForBlock(Material block);

    /** 载着方块的矿车；普通矿车返回 null。 */
    @Nullable ManagedCart getCart(Minecart minecart);
}
