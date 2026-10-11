package ltd.pepper.pepperminecart.protection;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * 领地保护端口：把「这动作在这个点是否被保护拒绝」抽象成三个无 PepperClaim 依赖的方法，
 * 便于纯 JVM 假实现测试（{@link ProtectionGateway} 只依赖本接口，不依赖任何策略实现）。
 *
 * <p>语义约定：拒绝的**反馈文案由策略自行发出**（{@code true} = 已拒且提示已发），
 * 调用方不得再发自己的提示，否则玩家侧会双份。</p>
 */
public interface ClaimProtection {

    /** 放置方块到矿车（语义等价于该点的 {@code place}）。 */
    String FLAG_PLACE = "place";

    /** 从矿车取下方块（语义等价于该点的 {@code destroy}）。 */
    String FLAG_DESTROY = "destroy";

    /**
     * 环境来源（机器/物理机制，无玩家语境）的口径旗标：领地级旗标，PepperClaim 的环境路径接受它。
     * 发射器矿车压充能激活轨弹出物品属于此类。
     */
    String FLAG_ENVIRONMENT_EJECT = "mobgriefing";

    /**
     * 玩家动作闸（放置/取下等有玩家语境的动作）。
     *
     * @param player 发起玩家（非 null；调用方均为玩家动作）
     * @param location 动作点（矿车所在地，通常为脚下铁轨）
     * @param flagId 已接线的旗标 id（见本接口常量）
     * @return {@code true} = 已拒绝该动作（且提示已由策略发出），调用方应取消动作
     */
    boolean denied(Player player, Location location, String flagId);

    /**
     * 方块材质 → 实际把关的旗标（官方清单：{@code anvil}/{@code table}/{@code loom}/{@code container}/…）。
     * {@link Optional#empty()} = 本策略不把关该方块，调用方直接放行而不是自行编造旗标。
     */
    Optional<String> blockUseFlag(Material material);

    /**
     * 无玩家来源的动作闸（机器/物理机制引发的环境行为）。
     *
     * @param location 动作点
     * @param flagId 已接线的**领地级**旗标 id（玩家级旗标在环境路径会被拒）
     * @return {@code true} = 已拒绝（无玩家可提示，无反馈文案）
     */
    boolean environmentDenied(Location location, String flagId);
}
