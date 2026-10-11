package ltd.pepper.pepperminecart.protection;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import ltd.pepper.claim.ClaimApi;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * PepperClaim 后端：把端口方法转发给 {@link ClaimApi}（PepperClaim 的公共门面）。
 *
 * <p><b>运行时零耦合</b>：{@code ClaimApi} 由 {@code softdepend: [PepperClaim]} 决定可见性，
 * 联调包只在编译期提供签名（绝不 shade）。因此每个入口都先取 API 句柄、判空放行：
 * PepperClaim 未安装（类不可加载 → {@link LinkageError}）或未就绪（{@code ClaimApi.get() == null}）
 * 时**全部放行**，本插件行为与未接入时完全一致。</p>
 *
 * <p><b>异常纪律</b>：{@link ClaimApi} 对未知/空白旗标与「环境来源传玩家级旗标」抛
 * {@link IllegalArgumentException}（fail-fast，不静默放行）。这类异常**不得**漏到事件栈，
 * 也**不得**当拒绝处理（会把拼错的 id 变成隐性全拦）：捕获后记一次 WARN 并按放行处理。
 * 接线侧只传 {@link ClaimProtection} 里那几个已注册 id，正常情况下这条路径不可达。</p>
 *
 * <p>判定与事件路径同源：{@code denied(...)} 内部已按事件路径渠道发出节流提示并计入属主拒绝
 * 计数，故本插件**不再发自己的提示**（会双份）。</p>
 */
public final class PepperClaimProtection implements ClaimProtection {

    private final Supplier<ClaimApi> apiSupplier;
    private final Logger logger;

    /** 异常按「一次」记录（避免热路径刷屏）：flag 异常与类缺失各自一次。 */
    private volatile boolean invalidFlagWarned;
    private volatile boolean linkageWarned;

    /** 服务器路径：默认句柄 = {@link ClaimApi#get()}（经 {@link #lookupApi()} 包住类缺失）。 */
    public PepperClaimProtection(Logger logger) {
        this(PepperClaimProtection::lookupApi, logger);
    }

    /** 依赖注入路径：句柄由外部提供（测试/未来多来源）。 */
    public PepperClaimProtection(Supplier<ClaimApi> apiSupplier, Logger logger) {
        this.apiSupplier = Objects.requireNonNull(apiSupplier, "apiSupplier");
        this.logger = logger;
    }

    /**
     * PepperClaim 门面是否就绪（{@code ClaimApi.get() != null}）；未安装时返回 false。
     * 仅用于启动可观测日志与装配决策，不参与判定。
     */
    public static boolean available() {
        return lookupApi() != null;
    }

    /** 取门面；PepperClaim 未安装（类不在运行时类路径）时按未就绪处理。 */
    private static ClaimApi lookupApi() {
        try {
            return ClaimApi.get();
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public boolean denied(Player player, Location location, String flagId) {
        if (player == null || location == null || location.getWorld() == null) {
            return false; // 无从判定：放行（判定通道不可用时不拦，与「无领地即放行」同一方向）
        }
        try {
            ClaimApi api = apiSupplier.get();
            if (api == null) {
                return false;
            }
            return api.denied(
                    player,
                    location.getWorld().getName(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ(),
                    flagId);
        } catch (IllegalArgumentException ex) {
            warnInvalidFlag(flagId, ex);
            return false;
        } catch (LinkageError ex) {
            warnLinkage(ex);
            return false;
        }
    }

    @Override
    public Optional<String> blockUseFlag(Material material) {
        if (material == null) {
            return Optional.empty();
        }
        try {
            ClaimApi api = apiSupplier.get();
            return api == null ? Optional.empty() : api.blockUseFlag(material);
        } catch (IllegalArgumentException ex) {
            warnInvalidFlag(material.name(), ex);
            return Optional.empty();
        } catch (LinkageError ex) {
            warnLinkage(ex);
            return Optional.empty();
        }
    }

    @Override
    public boolean environmentDenied(Location location, String flagId) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        try {
            ClaimApi api = apiSupplier.get();
            if (api == null) {
                return false;
            }
            // 环境来源：actor 传 null（只接受领地级旗标，接线侧传的 mobgriefing 属领地级）
            return !api.checkFlag(
                            null,
                            location.getWorld().getName(),
                            location.getBlockX(),
                            location.getBlockY(),
                            location.getBlockZ(),
                            flagId)
                    .allowed();
        } catch (IllegalArgumentException ex) {
            warnInvalidFlag(flagId, ex);
            return false;
        } catch (LinkageError ex) {
            warnLinkage(ex);
            return false;
        }
    }

    private void warnInvalidFlag(String flagId, IllegalArgumentException ex) {
        if (invalidFlagWarned || logger == null) {
            return;
        }
        invalidFlagWarned = true;
        logger.log(Level.WARNING,
                "[PepperMinecart] PepperClaim 拒绝了非法旗标 id '" + flagId + "'，本次按放行处理（请检查接线）："
                        + ex.getMessage());
    }

    private void warnLinkage(LinkageError ex) {
        if (linkageWarned || logger == null) {
            return;
        }
        linkageWarned = true;
        logger.log(Level.WARNING,
                "[PepperMinecart] PepperClaim 类不可加载（未安装该插件？），领地保护降级为放行", ex);
    }
}
