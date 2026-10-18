package ltd.pepper.pepperminecart.config;

import java.util.Map;

/** 供其他包的测试直接构造配置（{@link PluginConfig#fromMap} 是包内可见的测试入口）。 */
public final class TestConfigs {

    private TestConfigs() {
    }

    /** 点号键，例如 {@code "anvil-damage.chance-per-use"}；未给出的键取默认值。 */
    public static PluginConfig with(Map<String, Object> values) {
        return PluginConfig.fromMap(values);
    }

    public static PluginConfig defaults() {
        return with(Map.of());
    }
}
