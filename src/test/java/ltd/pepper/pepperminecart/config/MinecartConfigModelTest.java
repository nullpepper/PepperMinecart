package ltd.pepper.pepperminecart.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import ltd.pepper.lib.config.Bindings;
import ltd.pepper.lib.config.IssueCollector;
import ltd.pepper.lib.yaml.YamlMap;
import org.junit.jupiter.api.Test;

/**
 * Minecart 迁移默认值等价护栏：模型出货的默认文件逐键等于发布资源 config.yml（模型 = 唯一
 * 事实源），且发布资源经 ConfigFileStore 路径装载无告警。
 */
class MinecartConfigModelTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = MinecartConfigModelTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void emitedDefaultsMatchShippedConfigValues() throws IOException {
        String emitted = Bindings.defaultsText(MinecartConfigModel.class);
        java.util.Map<String, Object> shipped = YamlMap.parse(resource("/config.yml"));
        java.util.Map<String, Object> model = YamlMap.parse(emitted);
        assertEquals(shipped, model, "模型出货默认文件与发布 config.yml 逐键相等");
    }

    @Test
    void shippedConfigBindsWithoutWarningsAndPreservesAliasBehavior() throws IOException {
        IssueCollector issues = new IssueCollector();
        MinecartConfigModel m = Bindings.load(
                MinecartConfigModel.class,
                YamlMap.parse(resource("/config.yml")),
                issues,
                MinecartConfigModel::resolve);
        assertTrue(issues.issues().isEmpty(), "发布配置不应有绑定告警: " + issues.issues());
        // 关键默认值抽查（与 PluginConfigTest.defaults 同源）
        assertEquals(6, m.displayBlockOffset);
        assertEquals(0.12, m.anvilDamageChance, 1e-9);
        assertEquals(0.5, m.dispenserEjectSpeed, 1e-9);
        assertEquals(40, m.dispenserCooldownTicks);
    }
}
