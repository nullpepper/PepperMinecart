package ltd.pepper.pepperminecart.protection;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * 领地保护组合根：按 {@code protection-backend} 档位决定咨询哪些策略，对外只暴露四个动作闸。
 *
 * <ul>
 *   <li>{@code EVENTS} → 只咨询 {@link BukkitEventProtection}（今天的行为）；</li>
 *   <li>{@code PEPPERCLAIM} → 只咨询 {@link PepperClaimProtection}；</li>
 *   <li>{@code BOTH}（默认）→ 两个都咨询，<b>任一拒即拒</b>。</li>
 * </ul>
 *
 * <p>{@code respect-protection: false} 时**所有**闸短路放行（含本次新接入的盲区）——保持该配置
 * 的历史语义：关掉保护协作即不受任何领地限制。</p>
 *
 * <p>策略由外部注入（便于以假实现做纯 JVM 测试）；配置值经 {@link BooleanSupplier}/
 * {@link Supplier} 实时读取，故 {@code /pm reload} 后立即生效。</p>
 *
 * <p><b>只问这四个动作（不重复判上游）</b>：矿车语义下的保护判定点收敛为「放置」「取下」「打开
 * 矿车上的虚拟方块界面」「环境来源弹出」四处。其余动作刻意不再判：</p>
 * <ul>
 *   <li>铁砧耐久/报废：发生在「打开铁砧界面」之后，上游已闸；</li>
 *   <li>特殊矿车转换：放置的结果（上游已闸）；</li>
 *   <li>显示方块增删：放置/取下的结果（上游已闸）；</li>
 *   <li>工作站残留物交付：开界面的结果（上游已闸）；</li>
 *   <li>容器内容编辑与回写：开容器界面的结果（上游已闸）；</li>
 *   <li>矿车破坏补掉：PepperClaim 自身的 {@code vehicledestroy} 路径已闸；</li>
 *   <li>乘坐/穿行：PepperClaim 自身的 {@code riding}/{@code boarding} 路径已闸。</li>
 * </ul>
 * <p>这就是 PepperClaim 的保护模型：**上游动作判一次**。再判一遍只会重复计数、重复提示，
 * 并在两侧判定口径不一致时产生互相打架的体验。</p>
 */
public final class ProtectionGateway {

    private final BooleanSupplier respectProtection;
    private final Supplier<ProtectionBackend> backend;
    private final ClaimProtection events;
    private final ClaimProtection claim;

    public ProtectionGateway(BooleanSupplier respectProtection, Supplier<ProtectionBackend> backend,
                             ClaimProtection events, ClaimProtection claim) {
        this.respectProtection = Objects.requireNonNull(respectProtection, "respectProtection");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.events = Objects.requireNonNull(events, "events");
        this.claim = Objects.requireNonNull(claim, "claim");
    }

    /** 放置方块到矿车。 */
    public boolean denyPlace(Player player, Location location) {
        if (!respectProtection.getAsBoolean()) {
            return false;
        }
        ProtectionBackend b = backend.get();
        boolean denied = false;
        if (consultsEvents(b)) {
            denied = events.denied(player, location, ClaimProtection.FLAG_PLACE);
        }
        if (consultsClaim(b)) {
            denied |= claim.denied(player, location, ClaimProtection.FLAG_PLACE);
        }
        return denied;
    }

    /** 从矿车取下方块。 */
    public boolean denyTakeOff(Player player, Location location) {
        if (!respectProtection.getAsBoolean()) {
            return false;
        }
        ProtectionBackend b = backend.get();
        boolean denied = false;
        if (consultsEvents(b)) {
            denied = events.denied(player, location, ClaimProtection.FLAG_DESTROY);
        }
        if (consultsClaim(b)) {
            denied |= claim.denied(player, location, ClaimProtection.FLAG_DESTROY);
        }
        return denied;
    }

    /**
     * 打开矿车上的虚拟方块界面（工作站/铁砧/容器/发射器矿车…）。把关旗标由策略按方块材质路由
     * （{@link ClaimProtection#blockUseFlag}）；策略不把关该材质（官方清单外）则放行。
     */
    public boolean denyOpenGui(Player player, Location location, Material material) {
        if (!respectProtection.getAsBoolean()) {
            return false;
        }
        ProtectionBackend b = backend.get();
        boolean denied = false;
        if (consultsEvents(b)) {
            denied = guiDenied(events, player, location, material);
        }
        if (consultsClaim(b)) {
            denied |= guiDenied(claim, player, location, material);
        }
        return denied;
    }

    /** 环境来源（机器/物理机制，无玩家语境）驱动的动作：发射器矿车压充能激活轨弹出物品。 */
    public boolean denyEnvironmentEject(Location location) {
        if (!respectProtection.getAsBoolean()) {
            return false;
        }
        ProtectionBackend b = backend.get();
        boolean denied = false;
        if (consultsEvents(b)) {
            denied = events.environmentDenied(location, ClaimProtection.FLAG_ENVIRONMENT_EJECT);
        }
        if (consultsClaim(b)) {
            denied |= claim.environmentDenied(location, ClaimProtection.FLAG_ENVIRONMENT_EJECT);
        }
        return denied;
    }

    private static boolean guiDenied(ClaimProtection strategy, Player player, Location location, Material material) {
        Optional<String> flag = strategy.blockUseFlag(material);
        return flag.isPresent() && strategy.denied(player, location, flag.get());
    }

    private static boolean consultsEvents(ProtectionBackend backend) {
        return backend == ProtectionBackend.EVENTS || backend == ProtectionBackend.BOTH;
    }

    private static boolean consultsClaim(ProtectionBackend backend) {
        return backend == ProtectionBackend.PEPPERCLAIM || backend == ProtectionBackend.BOTH;
    }
}
