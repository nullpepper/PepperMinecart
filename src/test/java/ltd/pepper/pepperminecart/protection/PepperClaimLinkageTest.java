package ltd.pepper.pepperminecart.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 装载边界：{@link PepperClaimLinkage} 必须在 {@code ltd.pepper.claim.ClaimApi} 不在运行时
 * 类路径的情况下**也装载得起来**，并且如实回答「没装」。
 *
 * <p>这是实机冒烟抓到的 P0 的回归测试：2.0.0 曾经在没装 PepperClaim 的服务器上
 * {@code onEnable} 直接 {@code NoClassDefFoundError: ltd.pepper.claim.ClaimApi}，整个插件拒绝
 * 启用。测试类路径里带着联动瘦包，所以这里用「假装没装」的装载器驱动那条分支。</p>
 */
class PepperClaimLinkageTest {

    private static final String CLAIM_PACKAGE = "ltd.pepper.claim.";

    /** 对 {@code ltd.pepper.claim.*} 一律抛 ClassNotFoundException 的装载器。 */
    private static ClassLoader hidingPepperClaim() {
        ClassLoader parent = PepperClaimLinkageTest.class.getClassLoader();
        return new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(CLAIM_PACKAGE)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }

            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                return loadClass(name, false);
            }
        };
    }

    @Test
    void reportsAbsentWhenTheApiIsNotOnTheClassPath() {
        assertFalse(PepperClaimLinkage.installed(hidingPepperClaim()),
                "ClaimApi 不可见时必须报「未安装」，而不是抛错");
    }

    @Test
    void reportsPresentWhenTheApiIsOnTheClassPath() {
        assertTrue(PepperClaimLinkage.installed(PepperClaimLinkageTest.class.getClassLoader()),
                "测试类路径带着联动瘦包，应报「已安装」");
    }
}
