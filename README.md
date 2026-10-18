# PepperMinecart

Minecraft **Paper 1.21.4+** 服务端插件：把方块放到矿车上，实现**移动工作站 / 移动仓库**。

- **潜行 + 右键空矿车**（手持方块）：放上 1 个方块，矿车显示该方块
- **潜行 + 右键载着方块的矿车**：把方块取下（进背包 / 落地 / 禁止，可配置）
- **右键载着方块的矿车**：打开方块对应的原版界面（工作台、铁砧、容器等）

## 功能特性

| 类别 | 支持 |
|---|---|
| 工作站 | 工作台、砂轮、织布机、制图台、锻造台、切石机、附魔台：打开的就是原版界面，附魔台会计算矿车周围的书架 |
| 铁砧 | 原版铁砧界面；按原版规则受损（完整 → 开裂 → 损坏 → 损毁，几率可配置，创造模式不受损） |
| 容器 | 木桶、投掷器、陷阱箱、潜影盒、烟熏炉、高炉、酿造台、合成器：使用方块自己的原版界面类型，内容装在方块物品里随矿车保存（与潜影盒随身携带内容的方式相同），取下的就是带着内容的方块 |
| 原版特殊矿车 | 箱子/漏斗/熔炉/TNT/命令方块放上后**直接变成原版的箱子矿车、漏斗矿车……**，之后一切都是原版行为；取下时内容装进方块物品、命令写回命令方块，矿车变回普通矿车。合成出来的原版矿车同样可以取下（`vanilla-carts.allow-pickup` 可关） |
| 发射器矿车 | 9 格容器；驶过充能的激活铁轨时随机弹出 1 个物品（间隔、偏移、速度可配置） |
| 数据安全 | 数据存在矿车实体自身（显示方块用原版 NBT，方块物品存 PDC），随区块保存；矿车被摧毁时掉落方块；区块卸载、随乘客下线保存、TNT 矿车爆炸都不会多掉方块 |
| 交互体验 | 绑定在矿车上的界面与原版矿车容器一样：矿车消失、方块被取下或走出 8 格即关闭；放置/取下使用该方块自己的原版音效；交互冷却防连点 |
| 扩展性 | 统一的 `CartType` 接口，第三方插件可注册全新矿车类型；内置类型全部是它的普通实现（见下文“扩展指南”） |

## 安装

1. 将 `PepperMinecart-2.0.0-all.jar` 放入服务器 `plugins/` 目录（不带 `-all` 的是瘦包，无内置 bstats，部署会 `NoClassDefFoundError`）
2. 重启服务器（或 `/reload`）
3. 首次启动自动生成带注释的 `plugins/PepperMinecart/config.yml`

> 依赖：Paper 1.21.4+（非 Folia），Java 21+，前置插件 **PepperLib ≥ 0.15.0**（配置体系由它提供，
> 本插件不打包 lib 类）。

## 配置

所有配置项在 `config.yml` 中均有中文注释，修改后执行 `/pm reload` 热重载，无需重启。

```yaml
take-off-mode: INVENTORY      # 取下后：INVENTORY 进背包（放不下的掉在玩家脚下）| DROP 掉在矿车旁 | DISABLED 禁止取下
allow-all-blocks: true        # true=除黑名单外全部允许；false=仅允许白名单
disabled-blocks: [BEDROCK, BARRIER]
enabled-blocks: []
respect-protection: true      # 与领地插件协作（总开关；false = 不受任何领地限制）
protection-backend: BOTH      # PEPPERCLAIM 只问 PepperClaim | EVENTS 只发伪方块事件 | BOTH 两者都问、任一拒即拒
display-block-offset: 6       # 显示方块高度（1/16 格）
interaction-cooldown-ms: 250  # 交互冷却
sounds: { place: true, take-off: true }
container-pickup-policy: PICKUP  # 容器类矿车（不含潜影盒）：PICKUP 带内容取下 | DROP 内容撒地、容器按 take-off-mode | FORBIDDEN 禁止取下
vanilla-carts:
  allow-pickup: true          # 是否允许从原版特殊矿车（箱子/漏斗/熔炉/TNT/命令）上取下方块；关闭后潜行右键交给原版
anvil-damage:
  enabled: true
  chance-per-use: 0.12        # 与原版铁砧相同的 12%
  drop-on-break: false        # 损毁时是否掉落损坏的铁砧
dispenser-cart:
  cooldown-ticks: 40
  eject-offset: 0.6
  eject-speed: 0.5
```

## 命令与权限

| 命令 | 权限 | 默认 |
|---|---|---|
| `/pepperminecart reload`（别名 `/pm`） | `pepperminecart.reload` | op |
| 放置/取下/打开界面 | `pepperminecart.use` | 所有玩家 |

## 数据与兼容

- 方块显示使用原版实体数据（`DisplayState`），方块物品与类型 id 存在实体 PDC（`pepperminecart:cart-type`、
  `pepperminecart:item`），都随区块保存。键名与 1.0 版本相同，已有存档直接可用。
- 载着方块的矿车默认禁止乘坐（扩展类型可通过 `allowsPassengers()` 开放）。
- 矿车被摧毁：普通方块矿车掉落方块物品（容器内容在物品里）；原版特殊矿车被打坏时由原版掉落
  “箱子矿车”这类物品与内容，插件不再补掉，其余移除方式由插件补掉方块。
- 只有本插件放上的方块才能取下：别的插件或地图用命令设置的显示方块不会被当成可取下的方块。
- 与领地合作：
  - **PepperClaim（推荐）**：经其联动 API（`ClaimApi`）判定，受保护的动作为**放置、取下、打开矿车上的方块界面、
    发射器矿车环境弹出**。启动日志会打印一行后端状态：`[PepperMinecart] 领地保护后端: …`。
  - **其它领地插件**：`protection-backend` 含 `EVENTS` 时，放置/取下会模拟 `BlockPlaceEvent`/`BlockBreakEvent`
    （并发出矿车语义事件 `CartBlockPlaceEvent`/`CartBlockTakeOffEvent`），被取消即禁止操作。
  - `respect-protection: false` 关闭全部领地判定。

## 扩展指南：新增矿车类型

Paper 没有“自定义实体类型”，本插件以**原版矿车 + 类型 id（PDC）+ 行为钩子**提供扩展模型。
内置类型（`ltd.pepper.pepperminecart.type` 包）全部是 `CartType` 的普通实现，可直接参考。

**两步接入** —— 以“钟乳石矿车（坐上去每秒扣血）”为例：

```java
// 1) 实现类型：只覆盖需要的钩子
public final class DripstoneCart implements CartType {
    private static final NamespacedKey ID = new NamespacedKey("dripstone", "cart");
    private static final NamespacedKey HURT = new NamespacedKey("dripstone", "hurt");

    @Override public NamespacedKey id() { return ID; }
    @Override public Set<Material> blocks() { return Set.of(Material.POINTED_DRIPSTONE); }
    @Override public boolean allowsPassengers() { return true; }

    @Override public void onTick(ManagedCart cart) {
        if (cart.isCoolingDown(HURT)) return;
        for (Entity passenger : cart.minecart().getPassengers()) {
            if (passenger instanceof LivingEntity living) {
                living.damage(1.0, cart.minecart());
                cart.startCooldown(HURT, 20);
            }
        }
    }
}

// 2) 在你的插件 onEnable 中注册（plugin.yml 声明 depend: [PepperMinecart]）
PepperMinecartAPI api = getServer().getServicesManager().load(PepperMinecartAPI.class);
api.registerCartType(this, new DripstoneCart());
```

玩家把钟乳石放到空矿车上就得到“钟乳石矿车”：显示方块、保存数据、取下返还、破坏掉落、
关闭界面等通用流程都由插件完成。你的插件被禁用时，它注册的类型会自动注销；已有的矿车改按普通方块矿车处理，方块不会丢。

### `CartType` 钩子（全部有默认实现）

| 钩子 | 说明 |
|---|---|
| `canPlace` | 额外的放置条件 |
| `onPlaced` | 方块放上之后（数据已保存、显示已设置） |
| `onUse` | 不潜行右键，通常用来打开界面；返回 true 取消原版交互 |
| `canTakeOff` | 是否允许取下；返回 false 时这次点击交给原版 |
| `takeOff` | 取下时交给玩家的物品（默认：矿车上的方块物品） |
| `onDestroyed` | 矿车被摧毁（不含区块卸载），默认掉落方块物品 |
| `onTick` / `onMove` | 每 tick / 矿车移动时 |
| `allowsPassengers` | 是否允许乘坐（默认 false） |
| `displayBlock` | 矿车上显示的方块；返回 null 保持原版外观 |
| `nativeBlock` | 原版矿车天然载着的方块（如箱子矿车 → 箱子），让未经插件的原版矿车也能取下 |

### `ManagedCart`（传给钩子的矿车）

| 方法 | 说明 |
|---|---|
| `minecart()` / `type()` | 矿车实体与类型；其余一切可直接用 Bukkit API，自定义数据请写在类型命名空间下（取下时自动清除） |
| `blockItem()` / `setBlockItem()` / `blockType()` | 读写矿车上的方块物品（含容器内容等全部数据），显示随之更新 |
| `removeBlock()` | 方块消失，矿车变回普通矿车（如铁砧用坏了） |
| `openMenu(player, MenuType.X)` | 打开原版方块界面，效果如同方块摆在矿车位置；与矿车绑定，自动按原版规则关闭 |
| `openInventory(player, inventory, onClose)` | 打开自定义库存并绑定到矿车，关闭时回调以便写回 |
| `convertTo(Class)` | 换成另一种原版矿车实体（保留位置、速度、名字与数据） |
| `isCoolingDown` / `startCooldown` | 按世界游戏时间计算的冷却，随矿车保存，不受 `/time set` 影响 |
| `dropItem` | 在矿车位置掉落物品 |

## FAQ

**Q：取下容器矿车（木桶等）时里面的东西怎么办？**
A：由 `container-pickup-policy` 控制——`PICKUP` 内容随方块物品带走；`DROP` 内容撒在地上、容器物品按 `take-off-mode`
处理；`FORBIDDEN` 禁止取下。潜影盒不受该策略约束。

**Q：矿车上的烟熏炉、高炉、酿造台会工作吗？**
A：不会。方块并不真的在世界里，它们在矿车上只用来储物。（普通熔炉例外：它会变成原版的熔炉矿车，用来推车。）

**Q：铁砧“损毁”了掉落什么？**
A：默认直接消失；`anvil-damage.drop-on-break: true` 时掉落损坏的铁砧。

**Q：支持 Folia 吗？**
A：暂不支持（`folia-supported: false`）。

**Q：会复制方块吗？**
A：方块只在矿车被真正摧毁时掉落一次；区块卸载、乘客下线带走矿车、TNT 矿车自爆、原版已经掉落“箱子矿车”时都不会多掉。

## 构建

```bash
./gradlew build        # 产物: build/libs/PepperMinecart-2.0.0-all.jar
./gradlew test         # 单元测试 + MockBukkit 集成测试
```

## 待办与已知限制

- bStats 插件 ID 占位（0 = 关闭），发布前在 [bstats.org](https://bstats.org) 注册后替换 `PepperMinecartPlugin.BSTATS_PLUGIN_ID`。
- 熔炉取下为普通熔炉物品（熔炉矿车的燃料不保留）；放上带内容的熔炉时内容掉在原地（熔炉矿车没有库存）。
- 命令方块的连锁/循环类型只是物品上保留，矿车按普通命令矿车运行（原版只有一种命令矿车）。
- 原版特殊矿车被打坏时按原版掉落“箱子矿车”等物品，放上时那个箱子物品的自定义名不会保留在其中。
- 木桶开合动画需要发包伪造方块，暂以原版音效替代。
