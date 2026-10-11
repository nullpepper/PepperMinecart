package ltd.pepper.pepperminecart.protection;

/**
 * 领地保护后端档位（{@code config.yml} 的 {@code protection-backend}）：
 *
 * <ul>
 *   <li>{@link #EVENTS} —— 只咨询伪事件策略（{@link BukkitEventProtection}）：与历史行为逐条等价，
 *       老用户不动配置即无感知。</li>
 *   <li>{@link #PEPPERCLAIM} —— 只咨询 Claim 策略（{@link PepperClaimProtection}，PepperClaim 未安装
 *       或未就绪时全部放行）。</li>
 *   <li>{@link #BOTH}（默认）—— 两个策略都咨询，**任一拒即拒**；PepperClaim 未就绪时天然退化为
 *       {@link #EVENTS} 的效果。</li>
 * </ul>
 *
 * <p>非法配置值由 PepperLib 绑定层回落默认值 {@link #BOTH} 并记 WARN（未知枚举值 → 默认值 +
 * 提示级问题），无需本类自行解析。</p>
 */
public enum ProtectionBackend {
    PEPPERCLAIM,
    EVENTS,
    BOTH
}
