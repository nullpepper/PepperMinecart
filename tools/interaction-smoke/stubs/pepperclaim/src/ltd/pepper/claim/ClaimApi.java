package ltd.pepper.claim;

import java.util.Optional;
import java.util.UUID;
import ltd.pepper.claim.judge.ClaimCheckResult;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * 仅用于联调冒烟的 PepperClaim 门面替身：与真实门面同签名。
 * {@link #setDenyAll(boolean)} 可让所有判定一律拒绝，用来验证插件的保护闸确实拦得住。
 */
public final class ClaimApi {

    private static volatile ClaimApi instance;
    private static volatile boolean denyAll;

    private ClaimApi() {
    }

    public static ClaimApi get() {
        return instance;
    }

    public static void bootstrap() {
        instance = new ClaimApi();
    }

    public static void setDenyAll(boolean deny) {
        denyAll = deny;
    }

    public static boolean isDenyAll() {
        return denyAll;
    }

    public boolean denied(Player player, String world, int x, int y, int z, String flag) {
        return denyAll;
    }

    public ClaimCheckResult checkFlag(UUID player, String world, int x, int y, int z, String flag) {
        return () -> !denyAll;
    }

    public Optional<String> blockUseFlag(Material material) {
        return Optional.of("container");
    }
}
