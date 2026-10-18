package ltd.pepper.pepperminecart.config;

/** 取下方块后物品的去向（{@code config.yml} 的 {@code take-off-mode}）。 */
public enum TakeOffMode {
    /** 放进玩家背包，放不下的掉在玩家脚下（与原版 /give 一致） */
    INVENTORY,
    /** 掉落在矿车旁 */
    DROP,
    /** 禁止取下 */
    DISABLED
}
