package ltd.pepper.pepperminecart.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ltd.pepper.lib.config.ConfigFileStore;
import ltd.pepper.lib.config.ConfigValues;
import ltd.pepper.pepperminecart.api.TakeOffResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Minecart 与 PepperLib {@code ConfigFileStore} 的接线（对齐 TreeCut M22）：主树文件生命周期
 * 由 store 承载——首跑 copy-once 材质化（注释随文件保留）、注释查询/编辑、值级合法性信号、
 * 显式 upgrade 补键；运行期 reload 仍走 store 且损坏时保留旧快照。
 */
class MinecartConfigStoreTest {

    @TempDir
    Path dataFolder;

    private ConfigFileStore<MinecartConfigModel> loadStore() {
        return ConfigFileStore.load(
            MinecartConfigModel.class,
            dataFolder,
            "config.yml",
            MinecartConfigStoreTest.class.getClassLoader(),
            MinecartConfigModel::resolve);
    }

    /** 首跑：jar 内注释充分的 config.yml 材质化；二次装载不覆盖管理员改动。 */
    @Test
    void firstRunMaterializesAndSecondLoadKeepsAdminEdits() throws IOException {
        assertFalse(Files.exists(dataFolder.resolve("config.yml")), "前置：dataFolder 无 config.yml");
        ConfigFileStore<MinecartConfigModel> store = loadStore();

        Path file = dataFolder.resolve("config.yml");
        assertTrue(Files.exists(file), "首跑材质化");
        assertTrue(store.text().contains("PepperMinecart 配置文件"), "头部注释随文件保留");
        assertEquals(TakeOffResult.INVENTORY, store.get().takeOffMode);
        assertTrue(store.lastIssues().isEmpty(), "发布配置装载无告警: " + store.lastIssues());

        Files.writeString(
            file,
            Files.readString(file, StandardCharsets.UTF_8).replace("take-off-mode: INVENTORY", "take-off-mode: DROP"),
            StandardCharsets.UTF_8);
        ConfigFileStore<MinecartConfigModel> again = loadStore();

        assertEquals(TakeOffResult.DROP, again.get().takeOffMode, "copy-once：管理员改动保留");
    }

    /** 注释面：条目注释可查询、可原地替换（字节保真），不影响其它条目。 */
    @Test
    void commentsQueryableAndEditable() {
        ConfigFileStore<MinecartConfigModel> store = loadStore();

        assertFalse(store.blockComments("take-off-mode").isEmpty(), "块注释可查询");
        store.setComments("take-off-mode", List.of("接线测试：取下策略"), null);

        assertTrue(store.text().contains("# 接线测试：取下策略"));
        assertTrue(store.text().contains("allow-all-blocks: true"), "其它条目未丢");
        assertEquals(List.of("接线测试：取下策略"), store.blockComments("take-off-mode"));
    }

    /** 值级信号：缺失键 = MISSING，类型不符 = INVALID（静默，不产生绑定告警）。 */
    @Test
    void valuesExposeMissingAndInvalidStatus() throws IOException {
        Path file = dataFolder.resolve("config.yml");
        Files.writeString(
            file,
            "# 精简\n"
                + "take-off-mode: INVENTORY\n"
                + "display-block-offset: not-a-number\n",
            StandardCharsets.UTF_8);

        ConfigFileStore<MinecartConfigModel> store = loadStore();

        assertEquals(ConfigValues.Status.PRESENT, store.values().status("take-off-mode"));
        assertEquals(ConfigValues.Status.INVALID, store.values().status("display-block-offset"));
        assertEquals(ConfigValues.Status.MISSING, store.values().status("interaction-cooldown-ms"));
        assertTrue(store.values().missingKeys().contains("interaction-cooldown-ms"));
        assertFalse(store.values().allValidInResource(), "存在缺失/非法条目 → 非全合法");
        // 类型不符静默回落默认（§9.5）：不应产生绑定告警
        assertTrue(store.lastIssues().isEmpty(), "类型不符不记 issue: " + store.lastIssues());
        assertEquals(6, store.get().displayBlockOffset, "回落模型默认");
    }

    /** 文档编辑 → 即时重绑定（store.set 后模型与 getter 面同快照）。 */
    @Test
    void setRebindsModelImmediately() {
        ConfigFileStore<MinecartConfigModel> store = loadStore();

        store.set("display-block-offset", 12);

        assertEquals(12, store.get().displayBlockOffset);
        assertTrue(store.text().contains("display-block-offset: 12"));
    }

    /** 显式 upgrade 补键：只补缺失块 + 备份原文件；不调用则不动盘。 */
    @Test
    void upgradeAddsMissingKeysOnlyOnExplicitCall() throws IOException {
        loadStore();
        Path file = dataFolder.resolve("config.yml");
        String full = Files.readString(file, StandardCharsets.UTF_8);
        String pruned = full.lines()
            .filter(line -> !line.startsWith("respect-protection:"))
            .reduce("", (a, b) -> a + b + "\n", (a, b) -> a + b);
        assertFalse(pruned.contains("respect-protection:"), "前置：键已剔除");
        Files.writeString(file, pruned, StandardCharsets.UTF_8);

        ConfigFileStore<MinecartConfigModel> prunedStore = loadStore();
        assertEquals(
            ConfigValues.Status.MISSING, prunedStore.values().status("respect-protection"), "缺键信号可见");
        String beforeUpgrade = Files.readString(file, StandardCharsets.UTF_8);

        // 触发闸门是"磁盘版本 < schemaVersion"；无 configVersion 键的文件按家族规范视为 v1，
        // 故测试用 2 演示补键路径（正式需要补键时按新 schema 版本号调用）。
        prunedStore.upgrade(2);

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("respect-protection: true"), "缺失键被补入");
        assertTrue(after.contains("allow-all-blocks"), "已有键保持");
        assertTrue(Files.exists(dataFolder.resolve("config.yml.bak-v1")), "补键前备份");
        assertFalse(beforeUpgrade.contains("respect-protection"), "升级前磁盘确实缺键");
    }

    /** 重载：磁盘被改坏（非法值）仍可用默认快照继续服务，不抛异常。 */
    @Test
    void reloadKeepsServingAfterBrokenEdit() throws IOException {
        loadStore();
        Path file = dataFolder.resolve("config.yml");

        Files.writeString(file, "take-off-mode: [broken\n", StandardCharsets.UTF_8);
        ConfigFileStore<MinecartConfigModel> store = loadStore();

        assertTrue(store.get() != null, "损坏文件回落内置默认");
        assertTrue(
            store.lastIssues().stream().anyMatch(i -> i.level() == ltd.pepper.lib.config.IssueLevel.ERROR),
            "损坏装载记 ERROR 通告: " + store.lastIssues());
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).isEmpty(), "不覆盖用户文件");
    }
}
