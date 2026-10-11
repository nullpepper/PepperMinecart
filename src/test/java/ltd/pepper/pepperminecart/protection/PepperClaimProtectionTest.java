package ltd.pepper.pepperminecart.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import ltd.pepper.claim.ClaimApi;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * PepperClaim 后端的降级与异常纪律：门面缺失（未安装/未就绪）一律放行、非法旗标不逃逸事件栈
 * 且不当拒绝、接线旗标在 PepperClaim 真实目录里确实已注册且作用域正确。
 */
class PepperClaimProtectionTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("claim-test");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Location cartLocation() {
        return new Location(world, 12, 64, -3);
    }

    /** 记录 WARN 的测试 logger。 */
    private static final class RecordingLogger {
        final Logger logger = Logger.getLogger("PepperMinecartTest-" + UUID.randomUUID());
        final List<LogRecord> records = new ArrayList<>();
        final AtomicInteger warnCount = new AtomicInteger();

        RecordingLogger() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        warnCount.incrementAndGet();
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }
    }

    @Test
    void availabilityIsFalseWhenPepperClaimPluginIsAbsent() {
        assertFalse(PepperClaimProtection.available(),
                "MockBukkit 环境未加载 PepperClaim：门面应判为不可用（降级放行）");
    }

    @Test
    void nullApiPassesEveryGate() {
        PepperClaimProtection protection = new PepperClaimProtection(() -> null, null);

        assertFalse(protection.denied(player, cartLocation(), ClaimProtection.FLAG_PLACE),
                "门面为 null（未就绪）→ 放行");
        assertFalse(protection.denied(player, cartLocation(), ClaimProtection.FLAG_DESTROY));
        assertEquals(Optional.empty(), protection.blockUseFlag(Material.CRAFTING_TABLE));
        assertFalse(protection.environmentDenied(cartLocation(), ClaimProtection.FLAG_ENVIRONMENT_EJECT));
    }

    @Test
    void worldlessLocationPassesWithoutConsultingApi() {
        PepperClaimProtection protection =
                new PepperClaimProtection(throwingSupplier(new AssertionError("不应触发 API 调用")), null);

        assertFalse(protection.denied(player, new Location(null, 0, 0, 0), ClaimProtection.FLAG_PLACE),
                "拿不到世界 → 不判");
        assertFalse(protection.environmentDenied(new Location(null, 0, 0, 0),
                ClaimProtection.FLAG_ENVIRONMENT_EJECT));
    }

    @Test
    void illegalFlagExceptionIsSwallowedAndWarnedOnlyOnce() {
        RecordingLogger recorder = new RecordingLogger();
        PepperClaimProtection protection = new PepperClaimProtection(
                throwingSupplier(new IllegalArgumentException("unknown claim flag 'typo'")), recorder.logger);

        for (int i = 0; i < 3; i++) {
            assertFalse(protection.denied(player, cartLocation(), "typo"),
                    "非法旗标不得当拒绝处理，也不得逃逸事件栈");
        }
        assertEquals(1, recorder.warnCount.get(), "异常按「一次」记录（热路径不刷屏）");
    }

    @Test
    void missingClaimClassesDegradeToPassAndWarnOnlyOnce() {
        RecordingLogger recorder = new RecordingLogger();
        PepperClaimProtection protection = new PepperClaimProtection(
                throwingSupplier(new NoClassDefFoundError("ltd/pepper/claim/ClaimApi")), recorder.logger);

        for (int i = 0; i < 3; i++) {
            assertFalse(protection.denied(player, cartLocation(), ClaimProtection.FLAG_PLACE),
                    "PepperClaim 未安装 → 零行为");
            assertFalse(protection.environmentDenied(cartLocation(), ClaimProtection.FLAG_ENVIRONMENT_EJECT));
        }
        assertEquals(Optional.empty(), protection.blockUseFlag(Material.ANVIL));
        assertEquals(1, recorder.warnCount.get(), "类缺失只记一次（后续调用静默放行，不刷屏）");
    }

    /**
     * 接线旗标真实性交叉校验：本插件只传 {@link ClaimProtection} 里那几个 id，必须在 PepperClaim 的
     * 真实旗标目录里已注册（否则 {@code ClaimApi} 会 fail-fast 抛 IllegalArgumentException），
     * 且环境来源用的 id 必须是领地级（玩家级在环境路径会被拒）。
     *
     * <p>联调包若被瘦身成只含 {@code ClaimApi}，本用例按「跳过」处理（不做假设）。</p>
     */
    @Test
    void wiredFlagIdsAreRegisteredAndScopedInRealCatalog() throws Exception {
        Class<?> catalog;
        try {
            catalog = Class.forName("ltd.pepper.claim.judge.FlagCatalog");
        } catch (ClassNotFoundException missing) {
            catalog = null;
        }
        assumeTrue(catalog != null, "联调包不含 FlagCatalog（已瘦身）：跳过旗标目录交叉校验");

        @SuppressWarnings("unchecked")
        Set<String> wired = (Set<String>) catalog.getMethod("wiredIds").invoke(null);
        @SuppressWarnings("unchecked")
        Set<String> territory = (Set<String>) catalog.getMethod("territoryIds").invoke(null);
        @SuppressWarnings("unchecked")
        Set<String> playerScoped = (Set<String>) catalog.getMethod("playerIds").invoke(null);

        assertTrue(wired.contains(ClaimProtection.FLAG_PLACE), "place 必须是已注册旗标");
        assertTrue(wired.contains(ClaimProtection.FLAG_DESTROY), "destroy 必须是已注册旗标");
        assertTrue(wired.contains(ClaimProtection.FLAG_ENVIRONMENT_EJECT), "mobgriefing 必须是已注册旗标");

        assertTrue(playerScoped.contains(ClaimProtection.FLAG_PLACE), "place 是玩家级（调用方传玩家）");
        assertTrue(playerScoped.contains(ClaimProtection.FLAG_DESTROY), "destroy 是玩家级（调用方传玩家）");
        assertTrue(territory.contains(ClaimProtection.FLAG_ENVIRONMENT_EJECT),
                "mobgriefing 必须是领地级，否则环境来源（actor=null）调用会被 ClaimApi 拒绝");

        // 界面闸按材质路由：矿车可能承载的方块，PepperClaim 要么不把关（放行），要么给出已注册旗标。
        for (Material material : List.of(Material.CRAFTING_TABLE, Material.ANVIL, Material.CHEST,
                Material.BARREL, Material.DISPENSER, Material.LOOM, Material.GRINDSTONE,
                Material.ENCHANTING_TABLE, Material.SHULKER_BOX, Material.STONE)) {
            @SuppressWarnings("unchecked")
            Optional<String> flag = (Optional<String>) catalog.getMethod("flagForMaterial", String.class)
                    .invoke(null, material.name());
            flag.ifPresent(id -> assertTrue(wired.contains(id),
                    material + " 路由到未注册旗标：" + id));
        }
    }

    /** 固定抛出给定错误的门面句柄（验证异常纪律，不依赖真实 API 的拒绝行为）。 */
    private static java.util.function.Supplier<ClaimApi> throwingSupplier(Throwable error) {
        return () -> {
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error instanceof Error e) {
                throw e;
            }
            throw new IllegalStateException(error);
        };
    }
}
