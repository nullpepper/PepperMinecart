package ltd.pepper.pepperminecart.protection;

import java.util.logging.Logger;

/**
 * PepperClaim 联动的**唯一装载边界**。
 *
 * <p>为什么需要这一层：{@link PepperClaimProtection} 的构造器与字段在签名里引用
 * {@code ltd.pepper.claim.ClaimApi}。没装 PepperClaim 时，<b>实例化它本身</b>就会抛
 * {@link NoClassDefFoundError}——构造器里 `{@code PepperClaimProtection::lookupApi}` 这个
 * 方法引用的描述符要求先解析 {@code ClaimApi}。所以「未安装则降级」不能靠
 * {@link PepperClaimProtection#denied} 里的 catch，必须在**构造之前**就问清楚。</p>
 *
 * <p>本类刻意不在任何签名里引用 {@code ClaimApi}：类探测走 {@link Class#forName} 的字符串，
 * 因此它自己永远装载得起来，也就能在 ClaimApi 缺席时安全地回答「不在」。</p>
 */
public final class PepperClaimLinkage {

    private static final String CLAIM_API = "ltd.pepper.claim.ClaimApi";

    private PepperClaimLinkage() {
    }

    /** 运行时类路径里是否有 {@code ClaimApi}（{@code initialize=false}，不触发其静态初始化）。 */
    public static boolean installed() {
        return installed(PepperClaimLinkage.class.getClassLoader());
    }

    /**
     * 指定装载器下是否有 {@code ClaimApi}；测试用「假装没装」的装载器驱动这条分支。
     *
     * <p><b>为什么连 {@link RuntimeException} 一起吞</b>：{@code Class.forName} 在这里不是纯探测。
     * Paper 的插件类装载器组会在 {@code findClass} 阶段对字节码做重映射（
     * {@code PaperClassloaderBytecodeModifier} → ASM），一个坏掉的 PepperClaim jar（例如用更高
     * JDK 编译、class 版本超出 ASM 支持）会抛 {@code IllegalArgumentException: Unsupported class
     * file major version}——那是 {@link RuntimeException}，不是 {@link LinkageError}。这种
     * 「装了但根本用不了」与「没装」对本插件的结论相同：**这条通道不可用**，但插件必须照常启用。</p>
     */
    static boolean installed(ClassLoader loader) {
        try {
            Class.forName(CLAIM_API, false, loader);
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /**
     * PepperClaim 已安装**且**门面就绪；未安装时绝不触碰 {@link PepperClaimProtection}。
     *
     * <p>同样兜住 {@link RuntimeException}：探测通过不代表后续解析一定成功（见
     * {@link #installed(ClassLoader)} 的说明），这里只回答「现在能不能用」，不能把可选通道的
     * 任何装载故障升级成插件启用失败。</p>
     */
    public static boolean ready() {
        if (!installed()) {
            return false;
        }
        try {
            return PepperClaimProtection.available();
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /**
     * 构造 PepperClaim 通道；未安装时返回 {@code null}，调用方按「该通道不存在」处理。
     *
     * <p>已安装但构造失败（例如装了不兼容的版本，链接期出错）也返回 {@code null} 并记一条
     * WARNING：一条可选通道不可用不应拖垮整个插件启用。</p>
     */
    public static ClaimProtection create(Logger logger) {
        if (!installed()) {
            return null;
        }
        try {
            return new PepperClaimProtection(logger);
        } catch (Exception | LinkageError e) {
            logger.warning("PepperClaim 已安装但联动 API 无法装载，本通道停用（其余功能照常）：" + e);
            return null;
        }
    }
}
