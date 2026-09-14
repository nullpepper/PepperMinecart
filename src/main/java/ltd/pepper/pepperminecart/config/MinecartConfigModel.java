package ltd.pepper.pepperminecart.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import ltd.pepper.lib.config.ConfigComment;
import ltd.pepper.lib.config.ConfigHeader;
import ltd.pepper.lib.config.ConfigIssue;
import ltd.pepper.lib.config.ConfigModel;
import ltd.pepper.lib.config.ConfigPath;
import ltd.pepper.lib.config.ConfigRange;
import ltd.pepper.lib.config.IssueCollector;
import ltd.pepper.lib.config.IssueLevel;
import ltd.pepper.pepperminecart.api.TakeOffResult;
import org.bukkit.Material;

/**
 * PepperMinecart {@code config.yml} 的注解绑定模型（PepperLib 自研 {@code @ConfigModel}）。
 *
 * <p>数值字段用 {@link ConfigRange @ConfigRange(clamp=true)} 复刻旧 {@code intInRange}/
 * {@code doubleInRange} 的**夹紧**语义（越界修正到边界而非回落默认，与 PluginConfig 旧行为
 * 逐值一致）；枚举大小写不敏感（lib S15 与旧 {@code parseEnum} 一致）；NaN/Infinity 由 lib
 * 拦截回落默认。</p>
 *
 * <p>方块黑/白名单以原始 {@code List<String>} 绑定，经 {@link #resolve}（post-load）用
 * {@link Material#matchMaterial} 解析为不可变 {@code Set<Material>}（未知材质/非方块记 WARN
 * 并跳过——writer 与旧 parseMaterials 同语义）。</p>
 */
@ConfigModel
@ConfigHeader({
    "PepperMinecart 配置文件",
    "修改后执行 /pm reload（别名 /pepperminecart reload）热重载，无需重启服务器"
})
public class MinecartConfigModel {

    @ConfigComment({
        "取下策略（对所有方块生效；容器类方块额外受下方 container-pickup-policy 约束，",
        "其 FORBIDDEN 优先级更高）：",
        "  INVENTORY - 取下后进玩家背包（放不下掉落地面）",
        "  DROP      - 取下后掉落到地面",
        "  DISABLED  - 禁止取下"
    })
    @ConfigPath("take-off-mode")
    TakeOffResult takeOffMode = TakeOffResult.INVENTORY;

    @ConfigComment({
        "true  = 除 disabled-blocks 外所有方块都允许放置",
        "false = 仅允许 enabled-blocks 中列出的方块"
    })
    @ConfigPath("allow-all-blocks")
    boolean allowAllBlocks = true;

    @ConfigComment("禁止放置的方块（allow-all-blocks: true 时生效），填写方块材质名")
    @ConfigPath("disabled-blocks")
    List<String> disabledBlocks = List.of("BEDROCK", "BARRIER");

    @ConfigComment("仅允许放置的方块（allow-all-blocks: false 时生效）")
    @ConfigPath("enabled-blocks")
    List<String> enabledBlocks = List.of();

    @ConfigComment({
        "与领地类插件协作：放置/取下时模拟 BlockPlaceEvent/BlockBreakEvent，",
        "被其他插件取消则禁止操作。关闭后为纯实体交互，不受领地限制。"
    })
    @ConfigPath("respect-protection")
    boolean respectProtection = true;

    @ConfigComment("方块在矿车上的显示高度（单位 1/16 格），根据视觉效果调整")
    @ConfigRange(min = 0, max = 16, clamp = true)
    @ConfigPath("display-block-offset")
    int displayBlockOffset = 6;

    @ConfigComment("玩家与矿车交互的最小间隔（毫秒），防止快速连点")
    @ConfigRange(min = 0, clamp = true)
    @ConfigPath("interaction-cooldown-ms")
    int interactionCooldownMs = 250;

    // ---- 音效 ----

    @ConfigComment("放置方块音效")
    @ConfigPath("sounds.place")
    boolean soundPlace = true;

    @ConfigComment("取下方块音效")
    @ConfigPath("sounds.take-off")
    boolean soundTakeOff = true;

    // ---- 容器类矿车取下策略 ----

    @ConfigComment({
        "木桶/投掷器/通用容器等容器类方块（不含潜影盒）的取下规则，优先级高于 take-off-mode：",
        "  PICKUP    - 直接取下，内容物随容器物品（NBT）带走",
        "  DROP      - 内容物全部掉落到地面，容器物品本身按 take-off-mode 处理（进背包/落地）",
        "  FORBIDDEN - 禁止取下容器类方块",
        "潜影盒不参与本策略，与普通方块一样按 take-off-mode 处理。"
    })
    @ConfigPath("container-pickup-policy")
    ContainerPickupPolicy containerPickupPolicy = ContainerPickupPolicy.PICKUP;

    // ---- 原版特殊矿车 ----

    @ConfigComment({
        "是否允许潜行取下原版特殊矿车上的物品（箱子矿车→带库存的箱子、漏斗矿车→带库存的漏斗等，",
        "矿车还原为普通矿车；世界上已存在的原版矿车同样适用）。",
        "false - 原版特殊矿车一律不能取下（内容仍可通过原版界面访问）"
    })
    @ConfigPath("vanilla-carts.allow-pickup")
    boolean vanillaCartPickupAllowed = true;

    // ---- 铁砧耐久 ----

    @ConfigPath("anvil-damage.enabled")
    boolean anvilDamageEnabled = true;

    @ConfigComment("每次取走修复结果时铁砧损坏的概率（0.0 - 1.0）")
    @ConfigRange(min = 0.0, max = 1.0, clamp = true)
    @ConfigPath("anvil-damage.chance-per-use")
    double anvilDamageChance = 0.12;

    @ConfigComment("报废时是否掉落损坏的铁砧物品；false = 直接消失")
    @ConfigPath("anvil-damage.drop-on-break")
    boolean anvilDropOnBreak = false;

    // ---- 发射器矿车 ----

    @ConfigComment("两次弹出物品的最小间隔（tick，20 tick = 1 秒）")
    @ConfigRange(min = 1, max = 1200, clamp = true)
    @ConfigPath("dispenser-cart.cooldown-ticks")
    int dispenserCooldownTicks = 40;

    @ConfigComment("弹出位置的随机偏移（格）")
    @ConfigRange(min = 0.0, max = 8.0, clamp = true)
    @ConfigPath("dispenser-cart.eject-offset")
    double dispenserEjectOffset = 0.6;

    @ConfigComment("弹出初速度")
    @ConfigRange(min = 0.0, max = 4.0, clamp = true)
    @ConfigPath("dispenser-cart.eject-speed")
    double dispenserEjectSpeed = 0.5;

    // ------------------------------------------------------------------
    // 装载后处理产物（transient：不进 schema / 不参与默认文件发射）
    // ------------------------------------------------------------------

    transient Set<Material> disabledBlocksResolved = Set.of();
    transient Set<Material> enabledBlocksResolved = Set.of();

    /**
     * post-load 钩子：把绑定后的字符串列表解析为 {@link Set}<{@link Material}>。
     * 与旧 {@code parseMaterials} 语义一致：trim 后匹配；未知材质 / 非方块记 WARN 跳过；
     * 无服务端注册表时 {@code isBlock()} 抛异常按方块接受（测试环境兼容）。
     */
    public static void resolve(MinecartConfigModel m, IssueCollector issues) {
        m.disabledBlocksResolved = parseMaterials(m.disabledBlocks, "disabled-blocks", issues);
        m.enabledBlocksResolved = parseMaterials(m.enabledBlocks, "enabled-blocks", issues);
    }

    private static Set<Material> parseMaterials(List<String> names, String path, IssueCollector issues) {
        Set<Material> out = new HashSet<>();
        for (String name : names) {
            Material mat = Material.matchMaterial(name.trim());
            if (mat == null) {
                issues.add(new ConfigIssue(
                        IssueLevel.WARN, path, "config.yml 中的材质不存在: " + name, "修正材质名"));
                continue;
            }
            try {
                if (!mat.isBlock()) {
                    issues.add(new ConfigIssue(
                            IssueLevel.WARN, path, "config.yml 中的材质不是方块，已忽略: " + name, "修正材质名"));
                    continue;
                }
            } catch (RuntimeException ignored) {
                // 无服务端注册表的测试环境无法判定，接受该材质
            }
            out.add(mat);
        }
        return Collections.unmodifiableSet(out);
    }

    /** 枚举大小写归一（lib 已对 schema 枚举大小写不敏感；本方法供外部工具复用）。 */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        if (value == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
