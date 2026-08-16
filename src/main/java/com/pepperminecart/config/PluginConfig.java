package com.pepperminecart.config;

import com.pepperminecart.api.TakeOffResult;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** config.yml 配置模型：所有字段带默认值，/pm reload 后全部生效。 */
public class PluginConfig {

    private final JavaPlugin plugin;
    private final Logger logger;

    private TakeOffResult takeOffMode = TakeOffResult.INVENTORY;
    private boolean allowAllBlocks = true;
    private final Set<Material> disabledBlocks = new HashSet<>();
    private final Set<Material> enabledBlocks = new HashSet<>();
    private boolean respectProtection = true;
    private int displayBlockOffset = 6;
    private int interactionCooldownMs = 250;
    private boolean soundPlace = true;
    private boolean soundTakeOff = true;
    private boolean vanillaCartPickupAllowed = true;
    private ContainerPickupPolicy containerPickupPolicy = ContainerPickupPolicy.PICKUP;
    private boolean anvilDamageEnabled = true;
    private double anvilDamageChance = 0.12;
    private boolean anvilDropOnBreak = false;
    private int dispenserCooldownTicks = 40;
    private double dispenserEjectOffset = 0.6;
    private double dispenserEjectSpeed = 0.5;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin == null ? null : plugin.getLogger();
    }

    public void reload() {
        plugin.reloadConfig();
        apply(plugin.getConfig());
    }

    /** 从配置对象解析全部字段（可脱离服务器测试）。 */
    public void apply(FileConfiguration c) {
        String rawTakeOff = c.getString("take-off-mode");
        TakeOffResult parsedTakeOff = parseEnum(TakeOffResult.class, rawTakeOff, null);
        if (parsedTakeOff == null) {
            if (rawTakeOff != null && logger != null) {
                logger.warning("config.yml 中的 take-off-mode 无效（应为 INVENTORY / DROP / DISABLED），按 INVENTORY 处理");
            }
            takeOffMode = TakeOffResult.INVENTORY;
        } else {
            takeOffMode = parsedTakeOff;
        }
        allowAllBlocks = c.getBoolean("allow-all-blocks", true);
        parseMaterials(checkedList(c, "disabled-blocks"), disabledBlocks);
        parseMaterials(checkedList(c, "enabled-blocks"), enabledBlocks);
        respectProtection = c.getBoolean("respect-protection", true);

        int rawDisplayOffset = c.getInt("display-block-offset", 6);
        if (c.contains("display-block-offset") && !c.isInt("display-block-offset")) {
            if (logger != null) {
                logger.warning("config.yml 中的 display-block-offset 不是整数，按默认值 6 处理");
            }
            rawDisplayOffset = 6;
        }
        displayBlockOffset = Math.max(0, Math.min(16, rawDisplayOffset));
        if (rawDisplayOffset != displayBlockOffset && logger != null) {
            logger.warning("config.yml 中的 display-block-offset 超出 0-16 范围，已修正为 " + displayBlockOffset);
        }

        int rawCooldown = c.getInt("interaction-cooldown-ms", 250);
        if (c.contains("interaction-cooldown-ms") && !c.isInt("interaction-cooldown-ms")) {
            if (logger != null) {
                logger.warning("config.yml 中的 interaction-cooldown-ms 不是整数，按默认值 250 处理");
            }
            rawCooldown = 250;
        }
        interactionCooldownMs = Math.max(0, rawCooldown);
        if (rawCooldown != interactionCooldownMs && logger != null) {
            logger.warning("config.yml 中的 interaction-cooldown-ms 为负数，已修正为 0");
        }

        soundPlace = c.getBoolean("sounds.place", true);
        soundTakeOff = c.getBoolean("sounds.take-off", true);
        vanillaCartPickupAllowed = c.getBoolean("vanilla-carts.allow-pickup", true);
        ContainerPickupPolicy parsedPolicy = ContainerPickupPolicy.parse(c.getString("container-pickup-policy"));
        if (parsedPolicy == null) {
            if (logger != null) {
                logger.warning("config.yml 中的 container-pickup-policy 无效（应为 PICKUP / DROP / FORBIDDEN），按 PICKUP 处理");
            }
            containerPickupPolicy = ContainerPickupPolicy.PICKUP;
        } else {
            containerPickupPolicy = parsedPolicy;
        }
        anvilDamageEnabled = c.getBoolean("anvil-damage.enabled", true);
        double rawChance = c.getDouble("anvil-damage.chance-per-use", 0.12);
        if (c.contains("anvil-damage.chance-per-use") && !c.isDouble("anvil-damage.chance-per-use")) {
            if (logger != null) {
                logger.warning("config.yml 中的 anvil-damage.chance-per-use 不是数字，按默认值 0.12 处理");
            }
            rawChance = 0.12;
        }
        anvilDamageChance = Math.max(0.0, Math.min(1.0, rawChance));
        if (rawChance != anvilDamageChance && logger != null) {
            logger.warning("config.yml 中的 anvil-damage.chance-per-use 超出 0.0-1.0 范围，已修正为 " + anvilDamageChance);
        }
        anvilDropOnBreak = c.getBoolean("anvil-damage.drop-on-break", false);
        dispenserCooldownTicks = Math.max(1, c.getInt("dispenser-cart.cooldown-ticks", 40));
        dispenserEjectOffset = Math.max(0.0, c.getDouble("dispenser-cart.eject-offset", 0.6));
        dispenserEjectSpeed = Math.max(0.0, c.getDouble("dispenser-cart.eject-speed", 0.5));
    }

    // ---- getters ----

    public TakeOffResult takeOffMode() {
        return takeOffMode;
    }

    public boolean allowAllBlocks() {
        return allowAllBlocks;
    }

    public Set<Material> disabledBlocks() {
        // 不可变视图：调用方不能修改内部集合（热重载时 parseMaterials 会对同一集合 clear+重建）
        return Collections.unmodifiableSet(disabledBlocks);
    }

    public Set<Material> enabledBlocks() {
        return Collections.unmodifiableSet(enabledBlocks);
    }

    public boolean respectProtection() {
        return respectProtection;
    }

    public int displayBlockOffset() {
        return displayBlockOffset;
    }

    public int interactionCooldownMs() {
        return interactionCooldownMs;
    }

    public boolean soundPlace() {
        return soundPlace;
    }

    public boolean soundTakeOff() {
        return soundTakeOff;
    }

    /** 是否允许取下原版特殊矿车（箱子/漏斗/熔炉/TNT/命令矿车）上的物品。 */
    public boolean vanillaCartPickupAllowed() {
        return vanillaCartPickupAllowed;
    }

    /** 容器类矿车（木桶/投掷器/通用容器，不含潜影盒）取下策略。 */
    public ContainerPickupPolicy containerPickupPolicy() {
        return containerPickupPolicy;
    }

    public boolean anvilDamageEnabled() {
        return anvilDamageEnabled;
    }

    public double anvilDamageChance() {
        return anvilDamageChance;
    }

    public boolean anvilDropOnBreak() {
        return anvilDropOnBreak;
    }

    public int dispenserCooldownTicks() {
        return dispenserCooldownTicks;
    }

    public double dispenserEjectOffset() {
        return dispenserEjectOffset;
    }

    public double dispenserEjectSpeed() {
        return dispenserEjectSpeed;
    }

    /** 该方块是否允许放置到矿车上。 */
    public boolean isBlockAllowed(Material material) {
        if (allowAllBlocks) {
            return !disabledBlocks.contains(material);
        }
        return enabledBlocks.contains(material);
    }

    /** 键存在但值不是列表时（YAML 误写成标量，如 disabled-blocks: BEDROCK 少了 "-"）返回空列表并告警：
     *  Bukkit 的 getStringList 对非列表值返回空列表，若不告警，黑/白名单会被静默清空。 */
    private List<String> checkedList(FileConfiguration c, String path) {
        if (!c.contains(path) || c.isList(path)) {
            return c.getStringList(path);
        }
        if (logger != null) {
            logger.warning("config.yml 中的 " + path + " 应为列表（每行以 - 开头），当前写法无效，按空列表处理");
        }
        return List.of();
    }

    private void parseMaterials(List<String> names, Set<Material> out) {
        out.clear();
        for (String name : names) {
            // trim 掉首尾空白，避免 " BEDROCK " 匹配失败
            Material m = Material.matchMaterial(name.trim());
            if (m == null) {
                if (logger != null) {
                    logger.warning("config.yml 中的材质不存在: " + name);
                }
                continue;
            }
            // enabled-blocks 应只包含方块；非方块（物品材质如 DIAMOND）按方块语义无法放置，
            // 显式跳过避免配置误导。isBlock() 依赖服务端注册表，纯 JVM 单测中可能抛异常，
            // try 内失败时按方块接受（保持原行为，不因测试环境误过滤）
            try {
                if (!m.isBlock()) {
                    if (logger != null) {
                        logger.warning("config.yml 中的材质不是方块，已忽略: " + name);
                    }
                    continue;
                }
            } catch (Throwable ignored) {
                // 无服务端注册表的测试环境无法判定，接受该材质
            }
            out.add(m);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        if (value == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
