package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 插件冒烟测试：MockBukkit 真实加载插件，验证 onEnable / /pm reload / onDisable 全路径。 */
class PluginSmokeTest {

    private ServerMock server;
    private PepperMinecartPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PepperMinecartPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginEnablesAndRegistersService() {
        assertTrue(plugin.isEnabled());
        assertNotNull(server.getServicesManager().load(ltd.pepper.pepperminecart.api.PepperMinecartAPI.class));
    }

    @Test
    void reloadCommandWorks() {
        var player = server.addPlayer();
        player.addAttachment(plugin, "pepperminecart.reload", true);
        assertDoesNotThrow(() -> server.execute("pm", player, "reload"));
    }

    /**
     * 启动可观测日志（实机冒烟唯一观察点）：逐档措辞固定；enable 阶段已真实打印（本类 setUp 装载无异常）。
     * 测试环境未加载 PepperClaim：应走「未检测到 + 降级」文案。
     */
    @Test
    void backendLogLineWordingIsStable() {
        assertTrue(PepperMinecartPlugin
                .protectionBackendLogLine(ltd.pepper.pepperminecart.protection.ProtectionBackend.BOTH, true, true)
                .contains("PepperClaim 联动 READY"));
        assertTrue(PepperMinecartPlugin
                .protectionBackendLogLine(ltd.pepper.pepperminecart.protection.ProtectionBackend.PEPPERCLAIM, true, true)
                .contains("PepperClaim 联动 READY"));
        assertTrue(PepperMinecartPlugin
                .protectionBackendLogLine(ltd.pepper.pepperminecart.protection.ProtectionBackend.EVENTS, true, true)
                .contains("该档不咨询"));

        assertTrue(PepperMinecartPlugin
                .protectionBackendLogLine(ltd.pepper.pepperminecart.protection.ProtectionBackend.BOTH, false, false)
                .contains("未检测到 PepperClaim，降级为伪事件策略"));
        assertTrue(PepperMinecartPlugin
                .protectionBackendLogLine(ltd.pepper.pepperminecart.protection.ProtectionBackend.EVENTS, false, false)
                .contains("未检测到 PepperClaim，降级为伪事件策略"));
        assertTrue(PepperMinecartPlugin
                .protectionBackendLogLine(ltd.pepper.pepperminecart.protection.ProtectionBackend.PEPPERCLAIM, false, false)
                .contains("无保护插件可用"));

        // PepperClaim 已安装但其运行时上下文异步装载中：必须与「未安装」区分（尾部观测会补报就绪）
        assertTrue(PepperMinecartPlugin
                .protectionBackendLogLine(ltd.pepper.pepperminecart.protection.ProtectionBackend.BOTH, false, true)
                .contains("已安装，联动 API 装载中"));
    }
}
