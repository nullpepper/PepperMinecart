# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循语义化版本。

## [2.0.0] - 2026-10-19 - 矿车类型架构重构

以可扩展性与“尽量复用原版”为目标重写了引擎与全部内置类型。存档数据（PDC 键与类型 id）保持不变，
已有矿车无需迁移；**扩展 API 不兼容旧版**，扩展插件需要按下表迁移（主版本号已提升为 2.0.0）。

插件的 `api-version` 同时提升为 `1.21.4`，与 README 声明的基线一致：1.21.0–1.21.3 会在加载阶段就被拒绝，
而不是启动成功、直到右键工作站或铁砧才抛 `NoSuchMethodError`。

测试规模变化：旧版 42 个测试类 / 198 个用例，本版 22 个类 / 153 个用例（重构本身先是 21 类 / 134 项，
两轮修复又补回 13 项）。删除的 34 个测试文件里，多数集中在旧版的「掉落被取消后的回滚补偿」与
「损坏矿车数据容错」上，取舍理由见下面「移除」一节。

### 破坏性变更（扩展 API）

| 旧 | 新 |
|---|---|
| `CartTypeHandler` | `CartType`（`getId` → `id`，`handledMaterials` → `blocks`，`onInteract` → `onUse`，`allowRiding` → `allowsPassengers`，`onCartDestroyed` → `onDestroyed(cart, cause)`） |
| `CartContext` | `ManagedCart`（方块物品读写、`openMenu`/`openInventory`、`convertTo`、冷却）；元数据直接用实体 PDC |
| `onTakeOff` + `TakeOffOutcome` + `getTakeOffItem` + `releaseCart` | `canTakeOff` + `takeOff`（返回交付物品）；插件负责清理 |
| `VanillaCartSupport`、`isContainerPickupControlled` | `nativeBlock`；容器取下策略由 `ContainerCart` 自己处理 |
| `onMount` / `onDismount` | 移除，需要时直接监听 Bukkit 的 `EntityMountEvent` 并用 `PepperMinecartAPI#getCart` 判断 |
| `registerCartType(handler)` | `registerCartType(plugin, type)`：扩展插件禁用时自动注销其类型 |
| `api.TakeOffResult` | `config.TakeOffMode` |

### 变更（复用原版）
- 工作站与铁砧改用 Paper 1.21.4 的 `MenuType` 打开原版界面（替代已弃用的 `openWorkbench` 等）；
  界面关闭时由原版界面自己退还物品，删除了插件自写的“残留物回收”。
- 矿车界面改按原版矿车容器的规则关闭：矿车消失、方块被取下或玩家离开 8 格即关闭，不再能隔着任意距离使用。
- 容器矿车用方块自己的库存类型创建界面，槽位规则由原版负责；同一辆矿车多人共享同一份库存。
  （原版的槽位限制挂在菜单的槽位上，只对玩家操作生效；插件自己往库存里写时仍可绕过，例如把潜影盒放进潜影盒。）
- 铁砧受损改为在“取走结果”的同一次点击里判定（替代延迟一 tick 的确认流程）；与原版一样创造模式不受损，
  使用音效交给原版界面播放。
- 放置/取下播放该方块自己的原版音效；创造模式放置不消耗物品；取下时背包放不下的物品与原版 `/give` 一样掉在玩家脚下。
- 实体转换在 `World#spawn` 的预处理回调里一次设好位置、速度、名字与数据。
- 受管矿车改由 `EntityAddToWorldEvent` 登记（替代按区块扫描），类型在使用时按 id 解析，晚注册、重注册立即生效。

- `vanilla-carts.allow-pickup: false` 或 `take-off-mode: DISABLED` 时，潜行右键原版特殊矿车交给原版处理，
  插件转换出的矿车与原版矿车行为一致：箱子矿车会打开原版界面，TNT 矿车会被点燃（刻意如此，不是漏判）。

### 修复
- 容器矿车直接打开物品里的方块实体快照（位于 `BlockPos.ZERO`）：按 Paper 1.21.4 源码，木桶的开合逻辑会对该坐标
  `setBlock`，即在世界 (0, 0, 0) 放下一个木桶。现改为按方块的库存类型新建界面。
  （该结论已在 Paper `ver/1.21.4` 源码中端到端核对：`CraftMetaBlockState#getBlockState` 在物品没有方块实体坐标时
  用 `BlockPos.ZERO`，`CraftHumanEntity` 打开界面时又把玩家的世界塞给这个方块实体，随后木桶的开合逻辑就会写那个坐标。）
- 乘客下线（`PLAYER_QUIT`，矿车随玩家保存）被当成矿车被摧毁，方块掉落后随玩家回来的矿车仍载着方块（复制）。
- 插件放上的 TNT 矿车爆炸（`EXPLODE`）后会再掉落一个 TNT（复制）。
- 别的插件或地图用命令设置的显示方块可被潜行右键“取下”成物品（凭空获得方块）。
- **取下/放置回到「先交付、后清理」的事务顺序**：交付被取消时撤销这一步并保留原样，不再出现「方块已经没了、物品也没到手」。
  `ManagedCart#dropItem` 现在返回是否真的交付成功——注意 Paper 的 `World#dropItem(Naturally)` 即使生成被 `ItemSpawnEvent`
  取消也照样返回实体、**从不返回 null**，是否被取消只能靠 `Item#isValid()` 判断（下面「移除」里那套补偿之所以只在
  MockBukkit 里生效，正是因为它用返回值判断）。
- 取下失败时只把**没有交付出去的那部分**写回矿车，而不是用取下前的整份快照回滚：交付可能只成功一部分（背包装下了一部分、
  剩下的掉落又被取消），用整份快照回滚会把已经到手的部分再复制一份。
- 容器矿车在 `container-pickup-policy: DROP` 下内容物逐件掉落：某一件被取消时，已经掉出去的不再随方块回到矿车
  （否则等于整箱复制），没掉出去的仍留在方块里。
- 发射器矿车改为先弹射、弹出成功后才扣掉那一格的库存；弹射被取消时不再凭空少一件。
- 铁砧矿车报废前先确认掉落的铁砧真的落地了，掉落被取消时取消这次报废、铁砧留在矿车上。
- 放置方块时若类型回调抛异常，回滚已扣除的物品与矿车上的方块/显示，不再留下「方块已放上、物品已扣」的中间态；
  如果类型在这一步已经把矿车换成了新实体（例如箱子矿车），新实体与它已经装上的库存会一起清掉。
- 烟熏炉、高炉与酿造台不再作为容器矿车：矿车不会运行方块自己的逻辑（烧炼、酿造），原版界面又只开放它们的
  专用槽位（燃料槽只收燃料、结果槽永远放不进东西），当存储用几乎不可用；这三种方块现在按普通方块处理。
  木桶、投掷器、陷阱箱、合成器与潜影盒不受影响。`container-pickup-policy: DROP` 时播完音效的库存写回也
  重新读取了当前格子，避免覆盖外部改动。
- 矿车数据读取失败不再逃出 tick 循环：一辆数据损坏的矿车只报废它自己，不会取消整条 repeating task。
- 实体转换（`ManagedCart#convertTo`）会把乘客与显示方块一起搬到新实体上，转换中途抛异常时清掉已经生成的新实体，
  不再把乘客甩下车，也不留下一辆多出来的矿车。
- 受管矿车登记时（`EntityAddToWorldEvent`）按方块物品补回显示方块：被第三方插件清掉显示后不再变成看不见方块的「隐形车」。
- 事件语义回到旧版：`EntityRemoveEvent` 恢复 `HIGHEST` 优先级、`VehicleMoveEvent` 恢复 `ignoreCancelled`，
  让读不到数据的矿车不至于把异常抛进事件系统。
- 注册矿车类型时显式校验 id 与材质（`Set.copyOf` 之前判空、拒绝 `minecraft`/`bukkit` 命名空间），
  注销时不再因 id 的命名空间为空而抛异常。

### 移除
- 扩展回调的异常屏障（旧版的 `safeHandler` / `safeInteract` / `isManagedSafe` / `contextOrNullSafe`）：现在除 tick 循环之外，
  扩展钩子抛出的异常会直接冒泡到 Bukkit 事件系统。旧版会吞掉异常并取保守结果（放置抛异常→拒绝且不扣物品、容器策略抛异常→按
  `FORBIDDEN`、`vanillaSupport` 抛异常→按「不支持」），依赖这一点的扩展需要自己加 `try/catch`。
- 为“掉落被其他插件取消”设计的多层回滚与补偿：真实 Paper 上 `World#dropItem(Naturally)` 即使生成被取消也返回实体、
  从不返回 null，这套机制只在 MockBukkit 里生效，却让取下/放置流程难以阅读。原版对同类情况同样不做补偿。

## [未发布] - 领地保护接入与配置层迁移

### 新增
- **真正接入 PepperClaim 领地保护**（取代此前唯一手段「伪造原版方块事件」）：经官方联动 API
  `ltd.pepper.claim.ClaimApi` 问判定。`libs/PepperClaim-linkage-v1.jar` 作 **compileOnly 编译期
  基线（运行时零耦合，绝不 shade / 不 relocate）**，`plugin.yml` 加 `softdepend: [PepperClaim]`；
  判定与拒绝反馈与 PepperClaim 事件路径**同源**（属主拒绝计数 + 节流文案），玩家侧不可区分，
  也不会绕开属主统计。未安装 / 未就绪 PepperClaim 时一律放行（零行为变化）。
- 保护后端抽象（新包 `ltd.pepper.pepperminecart.protection`）：端口 `ClaimProtection`
  + `PepperClaimProtection`（ClaimApi 后端）+ `BukkitEventProtection`（历史伪事件后端）
  + 组合根 `ProtectionGateway`；两策略由外部注入，便于纯 JVM 假实现测试。
- 新配置项 `protection-backend: PEPPERCLAIM | EVENTS | BOTH`（默认 **BOTH**；非法值回落 BOTH + WARN）。
- 启动可观测日志（实机冒烟唯一观察点）：
  `[PepperMinecart] 领地保护后端: <档位>；…` —— 检测到 PepperClaim 报 `PepperClaim 联动 READY`，
  未检测到则明确写出降级（`PEPPERCLAIM` 档写「无保护插件可用」）。
- 补齐两处此前**完全没问保护**的盲区：
  1. 打开矿车上的虚拟方块界面（工作站/铁砧/容器/发射器矿车）：按矿车上的方块材质路由旗标
     （`ClaimApi#blockUseFlag` → `table`/`anvil`/`container`/`loom`/…），被拒则取消交互且不打开界面；
     只对插件自管普通矿车生效（原版箱/漏斗矿车由 PepperClaim 自身监听器把关，**不双判**）。
  2. 发射器矿车压充能激活轨弹出物品：环境来源（无玩家语境）按领地级旗标 `mobgriefing` 判定，
     被拒则不弹出（不扣库存、不消耗冷却）。

### 变更
- 放置/取下的保护判定改经 `ProtectionGateway`：`EVENTS` 档与旧行为**逐条等价**
  （自有 `CartBlockPlaceEvent`/`CartBlockTakeOffEvent` + 伪造 `BlockPlaceEvent`/`BlockBreakEvent`；
  拿不到位置/发起者仍按拒绝处理），由测试逐条守护；`BOTH`/`PEPPERCLAIM` 档额外问 ClaimApi。
- `respect-protection: false` 语义不变：所有闸（含新增盲区）一律短路放行。
- 刻意**不补**的盲区及理由（均为「已被闸的上游动作的后果」，按 PepperClaim「上游判定一次、
  不重复判」模型，避免重复计数与重复提示）：铁砧耐久/报废（开铁砧界面的后果）、特殊矿车转换
  （放置的后果）、显示方块增删（放置/取下的后果）、工作站残留物交付（开界面的后果）、
  容器内容编辑与回写（开容器界面的后果）、矿车破坏补掉（PepperClaim 自身 `vehicledestroy`
  路径已闸）、乘坐/穿行（PepperClaim `riding`/`boarding` 路径已闸）。

### 变更（配置层迁移）
- 配置层迁移到 PepperLib 0.13.0 自研 `@ConfigModel` 体系：
  `PluginConfig` 改为 facade（`ConfigFileStore` + volatile `MinecartConfigModel`），
  `plugin.yml` 加 `depend: [PepperLib]`，`pepperlib-api.properties` 注入版本并挂
  `verifyPepperLibVersion` 门；默认 `config.yml` 由模型出货（带注释，键名逐字不变）。
- 行为等价：数值字段夹紧语义（`@ConfigRange(clamp=true)`）、枚举大小写不敏感、
  材质黑/白名单 post-load 解析（未知材质 WARN 跳过）均与旧 `intInRange/doubleInRange/
  parseEnum/parseMaterials` 一致；默认值等价测试守护。
- 附带修复：BuildArtifactContractTest 断言重定位路径由 `com/.../bstats` 改为
  `ltd.pepper.pepperminecart.libs.bstats`（存量断言错，非迁移引入）。
- **配置接线收口到 PepperLib 0.14.0**（对齐 PepperTreeCut M22）：
  - 去掉启动期 `saveDefaultConfig()` + 二次 `config.reload()`——主树文件生命周期完全由
    `ConfigFileStore` 承担（构造即 copy-once 材质化 + 注释文档 + 绑定 + post-load），
    启动只读盘一次、告警不再重复；
  - `PluginConfig` 暴露 store 面：`store()`（注释读写 / `upgrade` 补键）、`values()`
    （每键 PRESENT/MISSING/INVALID 值级信号）、`migrated()`（版本分叉标记）；
  - Minecart 无版本链（`config.yml` 无 `configVersion`，按家族规范视为 v1），故未注册
    `ConfigMigration`：`migrated()` 恒 false，未来引入版本链时再注册；
  - 新增 `MinecartConfigStoreTest` 6 例：首跑材质化 + copy-once 不覆盖、注释查询/编辑、
    值级信号（缺失 MISSING / 类型不符 INVALID 且静默）、`set` 即时重绑定、
    显式 `upgrade` 补键 + 备份、损坏文件回落默认不抛异常。

## [1.0.0] - 2026-08-18

### 新增
- 方块放到矿车上：潜行 + 右键空矿车（手持方块）放置，矿车显示该方块；潜行 + 右键取下
- 工作站矿车：工作台、砂轮、织布机、制图台、锻造台、切石机、附魔台
- 铁砧矿车：铁砧界面 + 可选耐久损耗（完整 → 损坏 → 报废，概率可配置）
- 容器矿车：桶、投掷器、潜影盒及任意带库存方块，内容实时回写、随矿车持久化
- 特殊矿车：箱子/漏斗/熔炉/TNT/命令方块自动转换为原版特殊矿车，取下还原普通矿车；支持取下原版特殊矿车
- 发射器矿车：压过充能激活铁轨时随机弹出物品（间隔、偏移、速度可配置）
- 扩展 API：`CartTypeHandler` / `CartContext` / `PepperMinecartAPI`，第三方插件可注册自定义矿车类型
- 配置系统：`take-off-mode`、`allow-all-blocks`、`respect-protection`、`container-pickup-policy`、`vanilla-carts`、`anvil-damage`、`dispenser-cart` 等，`/pm reload` 热重载

### 修复
- 放置/取下/销毁的原子性与防复制：回滚链、HANDLED 取下 PDC 判定、特殊矿车死亡掉落合并
- 配置解析加固与回滚路径修正；矿车内容物丢失与冷却时钟错误
- 会话关闭单一入口、铁砧延迟任务离线守卫、销毁状态机
- API 边界守卫、注册表先校验后提交、延迟注册后重扫
- 交付背包回滚检查 `removeItem` 返回值、发射器掉落原子性、实体失效守卫

### 变更
- 构建产物使用 `-all` 分类器，`bstats` 重定位至 `com.pepperminecart.libs.bstats`
- 工程规范：MIT LICENSE、CHANGELOG、开发文档与评审报告
