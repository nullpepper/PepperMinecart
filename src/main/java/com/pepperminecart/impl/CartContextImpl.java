package com.pepperminecart.impl;

import com.pepperminecart.api.CartContext;
import com.pepperminecart.api.CartTypeHandler;
import com.pepperminecart.api.TakeOffResult;
import com.pepperminecart.config.PluginConfig;
import com.pepperminecart.display.CartBlockDisplay;
import com.pepperminecart.engine.CartEngine;
import com.pepperminecart.storage.CartData;
import java.util.ArrayList;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/** CartContext 默认实现：委托 CartData（PDC 整物品存储）与 CartBlockDisplay（显示方块）。 */
public class CartContextImpl implements CartContext {

    private final CartEngine engine;
    private final PluginConfig config;
    private Minecart cart;
    private final CartTypeHandler type;
    private boolean dropOnDestroy = true;
    /** 实体可能已失效时仍可安全掉落的位置快照（销毁/卸载等回调前由引擎刷新）。 */
    private World lastWorld;
    private Location lastLocation;

    public CartContextImpl(CartEngine engine, PluginConfig config, Minecart cart, CartTypeHandler type) {
        this.engine = engine;
        this.config = config;
        this.cart = cart;
        this.type = type;
    }

    /** 更新最后已知有效位置；供引擎在实体移除/销毁回调前调用，避免 handler 依赖已失效实体。
     *  仅在拿到有效位置时更新，避免实体已失效时用 null 覆盖旧快照。 */
    public void updateSnapshot(World world, Location location) {
        if (world != null && location != null) {
            this.lastWorld = world;
            this.lastLocation = location;
        }
    }

    /** 返回最后已知有效位置；实体已失效且无法取得当前位置时供引擎回退使用。 */
    public Location lastLocation() {
        return lastLocation;
    }

    @Override
    public Minecart getMinecart() {
        return cart;
    }

    @Override
    public CartTypeHandler getType() {
        return type;
    }

    @Override
    public World getWorld() {
        return cart.getWorld();
    }

    @Override
    public Location getLocation() {
        return cart.getLocation();
    }

    @Override
    public Entity getRider() {
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof LivingEntity living && !living.isDead()) {
                return passenger;
            }
        }
        return null;
    }

    @Override
    public boolean hasRider() {
        return getRider() != null;
    }

    @Override
    public Material getOriginalMaterial() {
        // 优先返回放置时记录的原始材质（不随铁砧损坏链/显示方块变化）；
        // 老数据（无 ORIGINAL 键）回退到存储物品类型，再回退到显示方块
        Material original = CartData.getOriginalMaterial(cart);
        if (original != null) {
            return original;
        }
        ItemStack item = getBlockItem();
        return item != null ? item.getType() : getDisplayMaterial();
    }

    @Override
    public ItemStack getBlockItem() {
        return CartData.getItem(cart);
    }

    @Override
    public void setDisplay(Material material) {
        new CartBlockDisplay(cart).set(material);
        syncItemType(material);
    }

    @Override
    public void setDisplay(BlockData data) {
        new CartBlockDisplay(cart).set(data);
        syncItemType(data.getMaterial());
    }

    /** 显示方块变更时同步存储物品的类型（保持显示与物品一致，如铁砧损坏链）。 */
    private void syncItemType(Material material) {
        ItemStack item = getBlockItem();
        if (item != null && item.getType() != material) {
            // 带 BlockStateMeta（容器状态）的物品不重写类型，避免把容器 NBT 挂到无关材质上
            if (item.getItemMeta() instanceof BlockStateMeta) {
                return;
            }
            ItemStack updated = item.clone();
            updated.setType(material);
            CartData.setItem(cart, updated);
        }
    }

    @Override
    public Material getDisplayMaterial() {
        return new CartBlockDisplay(cart).material();
    }

    @Override
    public boolean hasDisplay() {
        return new CartBlockDisplay(cart).has();
    }

    @Override
    public void setDisplayOffset(int offset) {
        cart.setDisplayBlockOffset(offset);
    }

    @Override
    public int getDisplayOffset() {
        return cart.getDisplayBlockOffset();
    }

    @Override
    public void setMetaInt(NamespacedKey key, int value) {
        CartData.setInt(cart, key, value);
    }

    @Override
    public int getMetaInt(NamespacedKey key, int defaultValue) {
        return CartData.getInt(cart, key, defaultValue);
    }

    @Override
    public void setMetaString(NamespacedKey key, String value) {
        CartData.setString(cart, key, value);
    }

    @Override
    public String getMetaString(NamespacedKey key) {
        return CartData.getString(cart, key);
    }

    @Override
    public void setCooldown(NamespacedKey key, int ticks) {
        // 世界年龄驱动（World#getFullTime：单调递增、跨区块卸载/重启持久、不受 /time set 与
        // 系统时钟影响；随实体 PDC 保存，实体替换时随 PDC 一并复制）——与引擎调度语义一致
        CartData.setLong(cart, key, cart.getWorld().getFullTime() + Math.max(0, ticks));
    }

    @Override
    public boolean hasCooldown(NamespacedKey key) {
        return cart.getWorld().getFullTime() < CartData.getLong(cart, key, 0L);
    }

    @Override
    public void giveItem(Player player, ItemStack item) {
        giveItem(player, item, config.takeOffMode());
    }

    /** 引擎内部按本次实际取下策略分发，避免绕过调用方传入的 mode。 */
    public void giveItem(Player player, ItemStack item, TakeOffResult mode) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        switch (mode) {
            case INVENTORY -> giveOrDrop(player, item);
            case DROP -> dropItem(item);
            case DISABLED -> {
                // 禁止取下：不交付任何物品。正常交互路径在进入 takeOff 前已拦截（InteractionListener），
                // 此处为 API 直接调用/处理器误用时的防御：既不入背包也不落地
            }
        }
    }

    @Override
    public void dropItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world == null || loc == null) {
            // 实体已移除/失效时回退到最后已知位置，避免销毁回调静默丢失掉落
            world = lastWorld;
            loc = lastLocation;
        }
        if (world == null || loc == null) {
            return; // 仍无可用位置，只能放弃（防御）
        }
        world.dropItemNaturally(loc, item);
    }

    @Override
    public void replaceEntity(Minecart newCart) {
        CartData.copy(cart, newCart);
        // 迁移自定义名与可见性，尽力转移乘客（removePassenger 被取消时乘客留在旧矿车，
        // 旧矿车随后 remove() 会由服务端正常弹出，不强行转移）
        if (cart.customName() != null) {
            newCart.customName(cart.customName());
            newCart.setCustomNameVisible(cart.isCustomNameVisible());
        }
        for (Entity passenger : new ArrayList<>(cart.getPassengers())) {
            if (cart.removePassenger(passenger)) {
                newCart.addPassenger(passenger);
            }
        }
        // 显示方块/偏移是矿车实体属性，实体替换时应一并迁移
        BlockData displayData = cart.getDisplayBlockData();
        if (displayData != null && !displayData.getMaterial().isAir()) {
            newCart.setDisplayBlockData(displayData);
        }
        newCart.setDisplayBlockOffset(cart.getDisplayBlockOffset());
        engine.rebind(cart.getUniqueId(), newCart);
        // 旧实体已退休：清空本插件 PDC 与显示，防止其随后 remove() 时被 onEntityRemove
        // 重新解析为受管矿车造成双重掉落（第三方在 onTakeOff 中调用 replaceEntity 后尤其需要）
        CartData.clear(cart, type.getId().getNamespace());
        new CartBlockDisplay(cart).clear();
        cart = newCart;
        World world = newCart.getWorld();
        Location loc = newCart.getLocation();
        if (world != null && loc != null) {
            updateSnapshot(world, loc);
        }
    }

    /**
     * 取下流程中实体被替换为新的普通矿车后重定向内部引用：
     * 仅更新实体字段（上下文随后即废弃），不复制 PDC、不重绑定引擎跟踪，
     * 避免新矿车被引擎误跟踪（其无业务数据，也不应再视为受管矿车）。
     */
    public void repoint(Minecart newCart) {
        this.cart = newCart;
    }

    @Override
    public void setDropOnDestroy(boolean drop) {
        this.dropOnDestroy = drop;
    }

    @Override
    public void releaseCart() {
        // HANDLED 取下路径的清理助手：清除插件 PDC/显示并解除引擎跟踪，
        // 使矿车还原为空车（等效于引擎默认取下流程对普通矿车的清理）
        engine.removeCart(cart);
        CartData.clear(cart, type.getId().getNamespace());
        new CartBlockDisplay(cart).clear();
    }

    public boolean dropOnDestroy() {
        return dropOnDestroy;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        if (left.isEmpty()) {
            return;
        }
        World world = cart.getWorld();
        Location loc = cart.getLocation();
        if (world == null || loc == null) {
            // 实体已移除/失效时回退到最后已知位置，避免溢出物品静默丢失
            world = lastWorld;
            loc = lastLocation;
        }
        if (world == null || loc == null) {
            return; // 仍无可用位置，只能放弃（防御）
        }
        for (ItemStack rest : left.values()) {
            if (rest != null && !rest.getType().isAir()) {
                world.dropItemNaturally(loc, rest);
            }
        }
    }
}
