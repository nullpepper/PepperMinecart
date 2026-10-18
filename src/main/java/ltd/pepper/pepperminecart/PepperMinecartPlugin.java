package ltd.pepper.pepperminecart;

import ltd.pepper.pepperminecart.api.PepperMinecartAPI;
import ltd.pepper.pepperminecart.cart.CartListener;
import ltd.pepper.pepperminecart.cart.CartManager;
import ltd.pepper.pepperminecart.cart.CartMenus;
import ltd.pepper.pepperminecart.cart.CartRegistry;
import ltd.pepper.pepperminecart.command.PepperMinecartCommand;
import ltd.pepper.pepperminecart.config.PluginConfig;
import ltd.pepper.pepperminecart.cooldown.InteractionCooldown;
import ltd.pepper.pepperminecart.protection.BukkitEventProtection;
import ltd.pepper.pepperminecart.protection.PepperClaimProtection;
import ltd.pepper.pepperminecart.protection.ProtectionBackend;
import ltd.pepper.pepperminecart.protection.ProtectionGateway;
import ltd.pepper.pepperminecart.type.AnvilCart;
import ltd.pepper.pepperminecart.type.ContainerCart;
import ltd.pepper.pepperminecart.type.DispenserCart;
import ltd.pepper.pepperminecart.type.PlainBlockCart;
import ltd.pepper.pepperminecart.type.VanillaMinecartCart;
import ltd.pepper.pepperminecart.type.WorkstationCart;
import org.bstats.bukkit.Metrics;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * PepperMinecart 主类：只负责组装——读取配置、准备领地保护、注册内置矿车类型、启动引擎与监听器、
 * 发布对外服务。注意：类不可声明为 final（MockBukkit 会子类化主类来加载插件）。
 */
public class PepperMinecartPlugin extends JavaPlugin {

    /** bStats 插件 ID：在 https://bstats.org 注册后替换（0 = 暂不启用统计）。 */
    private static final int BSTATS_PLUGIN_ID = 0;

    /** 联动尾部观测节奏（tick）：每 1 秒查一次，最多 15 次（约 15 秒）。 */
    private static final long LINKAGE_RECHECK_PERIOD_TICKS = 20L;
    private static final int LINKAGE_RECHECK_ATTEMPTS = 15;

    private PluginConfig config;
    private ProtectionGateway protection;
    private CartRegistry registry;
    private CartManager carts;

    // ---- 供测试使用（非公开 API） ----

    PluginConfig pluginConfig() {
        return config;
    }

    ProtectionGateway protectionGateway() {
        return protection;
    }

    CartRegistry registry() {
        return registry;
    }

    CartManager carts() {
        return carts;
    }

    @Override
    public void onEnable() {
        // 配置文件的生命周期由 PepperLib ConfigFileStore 负责（首次启动写出带注释的默认文件），无需 saveDefaultConfig()
        this.config = new PluginConfig(this);

        // 领地保护：EVENTS = 伪造原版方块事件（兼容各种保护插件）；PEPPERCLAIM = PepperClaim 联动 API
        this.protection = new ProtectionGateway(
                config::respectProtection,
                config::protectionBackend,
                new BukkitEventProtection(),
                new PepperClaimProtection(getLogger()));
        logProtectionBackend();

        InteractionCooldown cooldown = new InteractionCooldown(config);
        CartMenus menus = new CartMenus();
        this.registry = new CartRegistry(getLogger());
        PlainBlockCart plainBlock = new PlainBlockCart();
        this.carts = new CartManager(this, config, registry, menus, protection, cooldown, plainBlock);

        // 内置矿车类型：与扩展插件注册的类型走完全相同的接口
        AnvilCart anvil = new AnvilCart(config);
        registry.register(this, plainBlock);
        registry.register(this, new WorkstationCart());
        registry.register(this, anvil);
        registry.register(this, new ContainerCart(config));
        registry.register(this, new DispenserCart(config, protection));
        registry.register(this, new VanillaMinecartCart(config));

        registerListeners(new CartListener(carts), menus, cooldown, anvil);
        getServer().getServicesManager().register(PepperMinecartAPI.class, carts, this, ServicePriority.Normal);
        carts.start();

        var command = getCommand("pepperminecart");
        if (command != null) {
            command.setExecutor(new PepperMinecartCommand(config, cooldown));
        } else {
            getLogger().severe("未找到命令 'pepperminecart'，请检查 plugin.yml 的 commands 段");
        }

        if (BSTATS_PLUGIN_ID > 0) {
            new Metrics(this, BSTATS_PLUGIN_ID);
        }
        getLogger().info("PepperMinecart v" + getPluginMeta().getVersion() + " 已启用");
    }

    @Override
    public void onDisable() {
        if (carts != null) {
            carts.shutdown(); // 关闭所有矿车界面并写回，之后服务器才保存世界
        }
        getLogger().info("PepperMinecart 已禁用");
    }

    private void registerListeners(Listener... listeners) {
        for (Listener listener : listeners) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
    }

    /**
     * 启动时打印一行领地保护后端日志（实机冒烟观察点）。PepperClaim 已安装但联动 API 尚在异步装载时，
     * 就绪后再补一行（{@link #trackLateLinkage()}），避免把“装载中”误读为“未安装”。
     */
    private void logProtectionBackend() {
        boolean claimReady = PepperClaimProtection.available();
        boolean claimInstalled = getServer().getPluginManager().getPlugin("PepperClaim") != null;
        getLogger().info(protectionBackendLogLine(config.protectionBackend(), claimReady, claimInstalled));
        if (!claimReady && claimInstalled) {
            trackLateLinkage();
        }
    }

    /** 后端日志文案（纯函数，便于逐档测试措辞）。 */
    static String protectionBackendLogLine(ProtectionBackend backend, boolean claimReady, boolean claimInstalled) {
        String prefix = "[PepperMinecart] 领地保护后端: " + backend + "；";
        if (claimReady) {
            return backend == ProtectionBackend.EVENTS
                    ? prefix + "检测到 PepperClaim，但该档不咨询（沿用伪事件策略）"
                    : prefix + "PepperClaim 联动 READY";
        }
        if (claimInstalled) {
            return prefix + "PepperClaim 已安装，联动 API 装载中（"
                    + (backend == ProtectionBackend.PEPPERCLAIM ? "就绪前无保护插件可用，动作全部放行" : "就绪前按伪事件策略")
                    + "）";
        }
        return backend == ProtectionBackend.PEPPERCLAIM
                ? prefix + "未检测到 PepperClaim，无保护插件可用（动作全部放行）"
                : prefix + "未检测到 PepperClaim，降级为伪事件策略";
    }

    /** 每 {@value #LINKAGE_RECHECK_PERIOD_TICKS} tick 查一次，最多 {@value #LINKAGE_RECHECK_ATTEMPTS} 次；就绪即报一行并停止。 */
    private void trackLateLinkage() {
        BukkitTask[] task = new BukkitTask[1];
        int[] attempts = {0};
        task[0] = getServer().getScheduler().runTaskTimer(this, () -> {
            if (PepperClaimProtection.available()) {
                getLogger().info("[PepperMinecart] 领地保护后端: " + config.protectionBackend()
                        + "；PepperClaim 联动就绪（enable 期尚未装载完成）");
                task[0].cancel();
            } else if (++attempts[0] >= LINKAGE_RECHECK_ATTEMPTS) {
                task[0].cancel();
            }
        }, LINKAGE_RECHECK_PERIOD_TICKS, LINKAGE_RECHECK_PERIOD_TICKS);
    }
}
