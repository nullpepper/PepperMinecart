package ltd.pepper.claim.judge;

/** 与真实 PepperClaim 同形状的判定结果：true 表示允许。 */
@FunctionalInterface
public interface ClaimCheckResult {
    boolean allowed();
}
