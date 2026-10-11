package ltd.pepper.pepperminecart.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * 组合根逐档行为：{@code EVENTS}/{@code PEPPERCLAIM}/{@code BOTH} 的咨询面与拒绝合并、
 * {@code respect-protection: false} 全放行短路、四个闸的旗标映射。全部用假策略（纯 JVM，无服务端）。
 */
class ProtectionGatewayTest {

    /** 假策略：记录调用并按预设判决返回（便于断言「谁被咨询、传了什么旗标」）。 */
    private static final class Fake implements ClaimProtection {

        final List<String> calls = new ArrayList<>();
        boolean placeDenied;
        boolean takeOffDenied;
        boolean guiDenied;
        boolean environmentDenied;
        Optional<String> guiFlag = Optional.empty();

        @Override
        public boolean denied(Player player, Location location, String flagId) {
            calls.add("denied:" + flagId);
            if (FLAG_PLACE.equals(flagId)) {
                return placeDenied;
            }
            if (FLAG_DESTROY.equals(flagId)) {
                return takeOffDenied;
            }
            return guiDenied;
        }

        @Override
        public Optional<String> blockUseFlag(Material material) {
            calls.add("use:" + material);
            return guiFlag;
        }

        @Override
        public boolean environmentDenied(Location location, String flagId) {
            calls.add("env:" + flagId);
            return environmentDenied;
        }

        boolean consulted() {
            return !calls.isEmpty();
        }
    }

    private static ProtectionGateway gateway(ProtectionBackend backend, boolean respect,
                                             ClaimProtection events, ClaimProtection claim) {
        return new ProtectionGateway(() -> respect, () -> backend, events, claim);
    }

    @Test
    void eventsBackendConsultsOnlyEventsStrategy() {
        Fake events = new Fake();
        Fake claim = new Fake();
        events.placeDenied = true;
        events.takeOffDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.EVENTS, true, events, claim);

        assertTrue(g.denyPlace(null, null), "EVENTS 档：事件策略拒即拒");
        assertTrue(g.denyTakeOff(null, null));
        assertFalse(claim.consulted(), "EVENTS 档不得咨询 Claim 策略");
    }

    @Test
    void eventsBackendIgnoresClaimDenial() {
        Fake events = new Fake();
        Fake claim = new Fake();
        claim.placeDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.EVENTS, true, events, claim);

        assertFalse(g.denyPlace(null, null), "EVENTS 档：Claim 策略拒也不生效");
        assertFalse(claim.consulted());
    }

    @Test
    void pepperClaimBackendConsultsOnlyClaimStrategy() {
        Fake events = new Fake();
        Fake claim = new Fake();
        events.placeDenied = true;
        events.takeOffDenied = true;
        claim.takeOffDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.PEPPERCLAIM, true, events, claim);

        assertFalse(g.denyPlace(null, null), "PEPPERCLAIM 档：事件策略拒不生效");
        assertFalse(events.consulted(), "PEPPERCLAIM 档不得咨询伪事件策略");
        assertTrue(g.denyTakeOff(null, null), "PEPPERCLAIM 档：Claim 策略拒即拒");
    }

    @Test
    void bothBackendDeniesWhenEitherStrategyDenies() {
        for (boolean eventDeny : new boolean[]{false, true}) {
            for (boolean claimDeny : new boolean[]{false, true}) {
                Fake events = new Fake();
                Fake claim = new Fake();
                events.placeDenied = eventDeny;
                claim.placeDenied = claimDeny;

                ProtectionGateway g = gateway(ProtectionBackend.BOTH, true, events, claim);

                assertEquals(eventDeny || claimDeny, g.denyPlace(null, null),
                        "BOTH：events=" + eventDeny + " claim=" + claimDeny + " 应任一拒即拒");
                assertTrue(events.consulted() && claim.consulted(), "BOTH 档两个策略都必须被咨询");
            }
        }
    }

    @Test
    void bothBackendSecondStrategyStillConsultedWhenFirstDenies() {
        Fake events = new Fake();
        Fake claim = new Fake();
        events.placeDenied = true;
        claim.placeDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.BOTH, true, events, claim);

        assertTrue(g.denyPlace(null, null));
        assertTrue(claim.consulted(), "前一策略已拒时后一策略仍应被咨询（口径一致，不做短路）");
    }

    @Test
    void takeOffGateForwardsDestroyFlag() {
        Fake events = new Fake();
        Fake claim = new Fake();
        claim.takeOffDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.PEPPERCLAIM, true, events, claim);
        g.denyTakeOff(null, null);

        assertEquals(List.of("denied:" + ClaimProtection.FLAG_DESTROY), claim.calls);
    }

    @Test
    void respectProtectionFalseShortCircuitsAllGatesWithoutConsultingStrategies() {
        Fake events = new Fake();
        Fake claim = new Fake();
        events.placeDenied = true;
        events.takeOffDenied = true;
        events.environmentDenied = true;
        claim.placeDenied = true;
        claim.takeOffDenied = true;
        claim.environmentDenied = true;
        claim.guiFlag = Optional.of("table");
        claim.guiDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.BOTH, false, events, claim);

        assertFalse(g.denyPlace(null, null));
        assertFalse(g.denyTakeOff(null, null));
        assertFalse(g.denyOpenGui(null, null, Material.CRAFTING_TABLE));
        assertFalse(g.denyEnvironmentEject(null));
        assertFalse(events.consulted(), "关闭保护协作时不得咨询任何策略");
        assertFalse(claim.consulted(), "关闭保护协作时不得咨询任何策略（含新盲区）");
    }

    @Test
    void openGuiGateRoutesFlagByMaterialAndOnlyGatesWhenStrategyCares() {
        Fake events = new Fake();
        Fake claim = new Fake();
        claim.guiFlag = Optional.of("table");
        claim.guiDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.BOTH, true, events, claim);
        assertTrue(g.denyOpenGui(null, null, Material.CRAFTING_TABLE), "策略把关该材质且拒 → 拒");
        assertEquals(List.of("use:CRAFTING_TABLE", "denied:table"), claim.calls,
                "界面闸先按材质路由旗标，再以该 id 判定");
        assertEquals(List.of("use:CRAFTING_TABLE"), events.calls,
                "事件策略的 blockUseFlag 恒空：被问了材质但不会产生 denied 调用");
    }

    @Test
    void openGuiGateAllowsMaterialOutsideOfficialList() {
        Fake events = new Fake();
        Fake claim = new Fake();
        claim.guiFlag = Optional.empty();

        ProtectionGateway g = gateway(ProtectionBackend.BOTH, true, events, claim);

        assertFalse(g.denyOpenGui(null, null, Material.STONE), "官方清单外材质 → 放行");
        assertEquals(List.of("use:STONE"), claim.calls, "不把关的材质不得再问 denied（避免编造旗标）");
    }

    @Test
    void environmentEjectGateUsesEnvironmentHook() {
        Fake events = new Fake();
        Fake claim = new Fake();
        claim.environmentDenied = true;

        ProtectionGateway g = gateway(ProtectionBackend.BOTH, true, events, claim);

        assertTrue(g.denyEnvironmentEject(null));
        assertEquals(List.of("env:" + ClaimProtection.FLAG_ENVIRONMENT_EJECT), claim.calls);

        Fake allowed = new Fake();
        ProtectionGateway g2 = gateway(ProtectionBackend.BOTH, true, new Fake(), allowed);
        assertFalse(g2.denyEnvironmentEject(null));
    }

    @Test
    void backendSwitchIsReadLivePerCall() {
        Fake events = new Fake();
        Fake claim = new Fake();
        claim.placeDenied = true;
        ProtectionBackend[] current = {ProtectionBackend.EVENTS};

        ProtectionGateway g = new ProtectionGateway(() -> true, () -> current[0], events, claim);

        assertFalse(g.denyPlace(null, null), "档位 EVENTS 时不咨询 Claim");
        current[0] = ProtectionBackend.PEPPERCLAIM;
        assertTrue(g.denyPlace(null, null), "档位切到 PEPPERCLAIM 后立即生效（/pm reload）");
    }

    @Test
    void respectFlagIsReadLivePerCall() {
        Fake events = new Fake();
        Fake claim = new Fake();
        claim.placeDenied = true;
        boolean[] respect = {false};

        ProtectionGateway g = new ProtectionGateway(() -> respect[0], () -> ProtectionBackend.BOTH, events, claim);

        assertFalse(g.denyPlace(null, null));
        respect[0] = true;
        assertTrue(g.denyPlace(null, null), "respect-protection 切换后立即生效（/pm reload）");
    }
}
