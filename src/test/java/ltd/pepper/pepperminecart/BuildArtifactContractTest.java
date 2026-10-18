package ltd.pepper.pepperminecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** 构建产物契约：交付 jar 必须携带重定位后的 bstats（org.bstats → ltd.pepper.pepperminecart.libs.bstats）。 */
class BuildArtifactContractTest {

    @Test
    void shadowJarContainsRelocatedBstatsAndDoesNotCollideWithThinJar() throws IOException {
        // 版本号只有一个来源：processResources 展开后的 plugin.yml。
        // 这里再硬编码一份，改版本号时就会漏改，并让 build/libs 里的旧产物把测试伪装成通过。
        String version = pluginVersion();
        Path fat = Path.of("build/libs/PepperMinecart-" + version + "-all.jar").toAbsolutePath();
        Path thin = Path.of("build/libs/PepperMinecart-" + version + ".jar").toAbsolutePath();

        assertTrue(Files.exists(thin), "应保留普通 jar（瘦包）产物: " + thin);
        assertTrue(Files.exists(fat), "shadowJar 应使用 -all 分类器，避免与瘦包同名互相覆盖: " + fat);
        assertTrue(containsRelocatedBstats(fat), "shadowJar 应包含 ltd/pepper/pepperminecart/libs/bstats 下的重定位类");
        assertFalse(containsRelocatedBstats(thin), "普通 jar 不应包含 shadowJar 重定位类（否则两者输出路径发生冲突）");
    }

    private static String pluginVersion() throws IOException {
        try (InputStream in = BuildArtifactContractTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "classpath 上应有 processResources 展开后的 plugin.yml");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("(?m)^version:\\s*'?([^'\\s]+)'?\\s*$").matcher(text);
            assertTrue(matcher.find(), "plugin.yml 里应有 version 字段");
            return matcher.group(1);
        }
    }

    private static boolean containsRelocatedBstats(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                if (entries.nextElement().getName().startsWith("ltd/pepper/pepperminecart/libs/bstats/")) {
                    return true;
                }
            }
        }
        return false;
    }
}
