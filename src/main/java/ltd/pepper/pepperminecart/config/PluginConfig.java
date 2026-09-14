package ltd.pepper.pepperminecart.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import ltd.pepper.lib.config.Bindings;
import ltd.pepper.lib.config.ConfigFileStore;
import ltd.pepper.lib.config.ConfigIssue;
import ltd.pepper.lib.config.ConfigValues;
import ltd.pepper.lib.config.IssueCollector;
import ltd.pepper.lib.config.IssueLevel;
import ltd.pepper.lib.yaml.YamlMap;
import ltd.pepper.pepperminecart.api.TakeOffResult;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * config.yml 配置模型门面：内部为 PepperLib 注解绑定 {@link MinecartConfigModel} +
 * {@link ConfigFileStore}（前置插件模式；首跑 copy-once 材质化含注释默认文件，随后只读绑定），
 * 对外保持旧 getter 面不变（/pm reload 后全部生效）。
 *
 * <p>数值随 {@code @ConfigRange(clamp=true)} 夹紧、枚举大小写不敏感、NaN/Infinity 回落默认，
 * 行为与旧手写 {@code intInRange}/{@code doubleInRange} 等价。材质集合经 post-load
 * 由 {@code MinecartConfigModel.resolve} 解析。</p>
 *
 * <p>store 面接线（对齐 TreeCut M22）：{@link #store()} 暴露注释读写 / {@code upgrade} /
 * 值级信号入口；{@link #values()} 给出每键 PRESENT/MISSING/INVALID（机器可读，区别于
 * WARN 通道）。Minecart 无版本链（config.yml 无 {@code configVersion}，按家族规范视为 v1），
 * 故未注册 {@code ConfigMigration}：{@link #migrated()} 恒 false，未来引入版本链时再注册。</p>
 */
public class PluginConfig {

    private final ConfigFileStore<MinecartConfigModel> store;
    private final Logger logger;
    private volatile MinecartConfigModel current;

    /** 服务器路径：PepperLib ConfigFileStore 装载（copy-once + 绑定 + post-load）。 */
    public PluginConfig(JavaPlugin plugin) {
        this.logger = plugin == null ? null : plugin.getLogger();
        this.store = plugin == null
                ? null
                : ConfigFileStore.load(
                        MinecartConfigModel.class,
                        plugin.getDataFolder().toPath(),
                        "config.yml",
                        plugin.getClass().getClassLoader(),
                        MinecartConfigModel::resolve);
        this.current = this.store != null ? this.store.get() : null;
        warnIssues("配置装载");
    }

    /** 纯 JVM 测试路径：从 YAML 文本直接绑定模型（无文件生命周期）。 */
    static PluginConfig fromYaml(String yaml) {
        IssueCollector issues = new IssueCollector();
        MinecartConfigModel model = Bindings.load(
                MinecartConfigModel.class, YamlMap.parse(yaml), issues, MinecartConfigModel::resolve);
        return new PluginConfig(model, issues);
    }

    /**
     * 纯 JVM 测试路径：从（点号或嵌套）值 map 直接绑定模型。点号键 {@code "sounds.place"} 先
     * 归一为嵌套 map，与 ConfigSchema 的点号取值语义一致。
     */
    static PluginConfig fromMap(Map<String, Object> values) {
        IssueCollector issues = new IssueCollector();
        MinecartConfigModel model = Bindings.load(
                MinecartConfigModel.class, nest(values), issues, MinecartConfigModel::resolve);
        return new PluginConfig(model, issues);
    }

    private PluginConfig(MinecartConfigModel fixed, IssueCollector issues) {
        this.logger = null;
        this.store = null;
        this.current = fixed;
        warnIssues(issues);
    }

    /** 测试 / 运行时重绑定：从（点号或嵌套）值 map 重建模型（旧构造函数 + apply 的等价面）。 */
    public void apply(Map<String, Object> values) {
        IssueCollector issues = new IssueCollector();
        this.current = Bindings.load(
                MinecartConfigModel.class, nest(values), issues, MinecartConfigModel::resolve);
        warnIssues(issues);
    }

    /** 点号键 → 嵌套 map（如 {@code "sounds.place" → {"sounds": {"place": …}}}）。 */
    private static Map<String, Object> nest(Map<String, Object> flat) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            String[] segs = e.getKey().split("\\.");
            Map<String, Object> cur = out;
            for (int i = 0; i < segs.length - 1; i++) {
                Object next = cur.get(segs[i]);
                if (!(next instanceof Map<?, ?>)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    cur.put(segs[i], m);
                    cur = m;
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) next;
                    cur = m;
                }
            }
            cur.put(segs[segs.length - 1], e.getValue());
        }
        return out;
    }

    private void warnIssues(String phase) {
        if (store != null && logger != null) {
            for (ConfigIssue i : store.lastIssues()) {
                if (i.level() == IssueLevel.WARN) {
                    logger.warning(phase + "：" + i.path() + " " + i.message());
                }
            }
        }
    }

    private void warnIssues(IssueCollector issues) {
        if (logger == null) {
            return;
        }
        for (ConfigIssue i : issues.issues()) {
            if (i.level() == IssueLevel.WARN) {
                logger.warning("配置解析： " + i.path() + " " + i.message());
            }
        }
    }

    /** /pm reload 热重载：重读磁盘并原子替换；损坏保留旧快照并记 ERROR。 */
    public void reload() {
        if (store == null) {
            return;
        }
        store.reload();
        this.current = store.get();
        warnIssues("配置重载");
    }

    /** 运行期主树存储快照（注释读写 / upgrade / 值级信号的接线口；与 getter 面同快照）。 */
    public ConfigFileStore<MinecartConfigModel> store() {
        return this.store;
    }

    /** 值级合法性信号（PRESENT/MISSING/INVALID；纯 JVM 测试路径返回 null）。 */
    public ConfigValues values() {
        return this.store == null ? null : this.store.values();
    }

    /** 最近一次装载是否发生版本迁移（模型与磁盘分叉；Minecart 当前无版本链，恒 false）。 */
    public boolean migrated() {
        return this.store != null && this.store.migrated();
    }

    // ---- getters ----

    public TakeOffResult takeOffMode() {
        return current.takeOffMode;
    }

    public boolean allowAllBlocks() {
        return current.allowAllBlocks;
    }

    public Set<Material> disabledBlocks() {
        return Collections.unmodifiableSet(current.disabledBlocksResolved);
    }

    public Set<Material> enabledBlocks() {
        return Collections.unmodifiableSet(current.enabledBlocksResolved);
    }

    public boolean respectProtection() {
        return current.respectProtection;
    }

    public int displayBlockOffset() {
        return current.displayBlockOffset;
    }

    public int interactionCooldownMs() {
        return current.interactionCooldownMs;
    }

    public boolean soundPlace() {
        return current.soundPlace;
    }

    public boolean soundTakeOff() {
        return current.soundTakeOff;
    }

    /** 是否允许取下原版特殊矿车（箱子/漏斗/熔炉/TNT/命令矿车）上的物品。 */
    public boolean vanillaCartPickupAllowed() {
        return current.vanillaCartPickupAllowed;
    }

    /** 容器类矿车（木桶/投掷器/通用容器，不含潜影盒）取下策略。 */
    public ContainerPickupPolicy containerPickupPolicy() {
        return current.containerPickupPolicy;
    }

    public boolean anvilDamageEnabled() {
        return current.anvilDamageEnabled;
    }

    public double anvilDamageChance() {
        return current.anvilDamageChance;
    }

    public boolean anvilDropOnBreak() {
        return current.anvilDropOnBreak;
    }

    public int dispenserCooldownTicks() {
        return current.dispenserCooldownTicks;
    }

    public double dispenserEjectOffset() {
        return current.dispenserEjectOffset;
    }

    public double dispenserEjectSpeed() {
        return current.dispenserEjectSpeed;
    }

    /** 该方块是否允许放置到矿车上。 */
    public boolean isBlockAllowed(Material material) {
        if (material == null) {
            return false;
        }
        if (current.allowAllBlocks) {
            return !current.disabledBlocksResolved.contains(material);
        }
        return current.enabledBlocksResolved.contains(material);
    }
}
