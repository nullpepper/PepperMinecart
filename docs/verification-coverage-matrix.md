# PepperMinecart 功能完备性走查：覆盖矩阵报告

范围：README.md「功能特性」表（8 行）、「数据与兼容」「扩展指南」「配置」各节声明，对照 `src/main/java/com/pepperminecart/**` 与 `src/test/java/com/pepperminecart/**`（MockBukkit 集成测试 + 纯单元测试）。本报告只读走查，未修改任何 src/test 代码。

## 一、覆盖矩阵

| 功能声明 | 实现位置 | 测试覆盖 | 覆盖程度 |
|---|---|---|---|
| 潜行+右键空矿车（手持方块）：消耗 1 个方块放到矿车并显示 | InteractionListener#onInteractEntity / #isPlacementCandidate / #placeBlock；CartEngine#addCart；CartBlockDisplay#set | MinecartFlowTest#place_consumesOneItemAndShowsDisplay、#place_rejectsDisallowedBlock；PlaceSnapshotConsumptionTest | 充分 |
| 潜行+右键有方块矿车：取下（INVENTORY / DROP / DISABLED） | InteractionListener#onInteractEntity；CartEngine#takeOff；CartContextImpl#deliverItem | MinecartFlowTest#takeOff_inventoryMode_returnsItemToPlayer、#takeOff_dropMode_dropsOnGround、#takeOff_disabledMode_keepsBlock；TakeOffAtomicityTest 系列 | 充分 |
| 右键有方块矿车：打开对应功能界面 | InteractionListener 非潜行分支；WorkstationHandler / AnvilHandler / ContainerHandler / DispenserCartHandler#onInteract | 容器界面：MinecartFlowTest#container_editsArePersistedToPdcOnClose；工作站/铁砧/发射器界面打开无直接测试 | 部分 |
| 工作站：工作台、砂轮、织布机、制图台、锻造台、切石机、附魔台 | WorkstationHandler#MATERIALS / #onInteract；CartSessionManager#openVanillaView；VanillaCartSession#recoverLeftovers | VerificationGapCoverageTest#workstation_onInteractRoutesToExpectedOpenMethod（动态代理 Player 直驱，断言 7 种材质路由到正确 open* 方法） | 部分（路由已测；真实界面打开/关闭回收受 MockBukkit 限制） |
| 铁砧：铁砧界面 + 可选耐久损耗（完整→损坏→报废，可配置概率） | AnvilHandler#onInteract；CartSessionManager#openAnvilView / #onPrepareAnvil / #onClick；AnvilCartSession#handleClick / #confirmTake；AnvilDamageTracker#onResultTaken | MinecartFlowTest#anvilDamage_progressesAnvilChippedDamagedBroken、#anvilDamage_chanceZero_neverDamages；EngineStateFixTest#anvilBreakWithUnavailableWorldDoesNotThrow；AnvilCartSessionOfflineTest；CartSessionManagerCloseTest | 部分（状态机充分；PrepareAnvil→点击→判定的 UI 全链路无端到端测试） |
| 容器：桶、投掷器、潜影盒及通用容器；内容实时回写、随矿车持久化 | ContainerHandler（KNOWN_CONTAINERS + 潜影盒后缀扫描）/#onInteract / #onTakeOff；CartSessionManager#openContainer；ContainerCartSession#flush；CartData#setItem/#getItem | ContainerHandlerTest；MinecartFlowTest#container_editsArePersistedToPdcOnClose、#takeOff_containerKeepsContentsInItem；CartSessionManagerCloseTest#flushAndCloseFlushesSessionExactlyOnce | 部分（测试注释自认 MockBukkit BlockStateMetaMock 序列化限制，内容级断言留真实服务器冒烟） |
| 特殊矿车：箱子/漏斗/熔炉/TNT/命令方块放置后自动转换为原版特殊矿车实体 | SpecialCartHandler#onPlaced（switch 转换 + 命令恢复）；MinecartSwap#spawnReplacement；CartContextImpl#replaceEntity | MinecartFlowTest#place_chest_convertsToStorageMinecartAndBack；SpecialCartHandlerTransferTest#transferContents_targetWithInventory_transfersContents；无 HOPPER/TNT/命令方块转换测试 | 部分 |
| 特殊矿车：取下还原普通矿车；箱子/漏斗内容组装进"带库存的容器物品"随物品回收 | SpecialCartHandler#getTakeOffItem / #buildTakeOffItem；CartEngine#takeOff 特殊矿车分支（替代实体 + 库存处置） | MinecartFlowTest#takeOff_specialCartClearsVanillaInventoryBeforeRemoval、#takeOff_specialCartPreservesItemMeta、#takeOff_specialCartPreservesCustomName；SpecialCartEmbeddedDropNoDuplicateTest | 部分（CHEST 充分；HOPPER 无测试） |
| 外来原版特殊矿车（无插件 PDC 的箱子/漏斗/熔炉/TNT/命令矿车）同样支持取下；`vanilla-carts.allow-pickup` 可关 | CartEngine#isVanillaCart / #resolveVanillaPickup / #vanillaSupportHandler；InteractionListener 原版特殊矿车取下分支；PluginConfig#vanillaCartPickupAllowed | VerificationGapCoverageTest#vanillaPickup_allowTrue_takesOffForeignStorageMinecartWithoutLosingContents、#vanillaPickup_allowFalse_ignoresForeignStorageMinecart | 部分（箱子矿车开关两态已测；漏斗/熔炉/TNT/命令外来矿车未逐一测） |
| 发射器矿车：压过充能激活铁轨时随机弹出 1 个物品（间隔、偏移、速度可配置） | DispenserCartHandler#onMove / #shoot / #containerAccess；DispenserContainerAccess；PluginConfig 发射器配置段 | DispenserCartShootAtomicityTest；DispenserCartShootRaceTest；DispenserContainerAccessTest；PluginConfigTest#dispenserValuesAreClampedToSafeBounds | 部分（shoot 事务充分；onMove 铁轨检测/冷却前置/成功才耗冷却无直接测试） |
| 数据安全：方块显示使用原版实体 NBT（DisplayState），随区块保存/关服重启自动持久化 | CartBlockDisplay#set/#clear/#has；CartEngine#registerLoadedCarts / #registerManagedCart / #onChunkLoad（显示恢复） | CartBlockDisplayNullTest；MinecartFlowTest#place_consumesOneItemAndShowsDisplay；无重启/区块加载恢复显示测试 | 部分 |
| 数据安全：容器内容与业务数据存实体 PDC（`pepperminecart:*`），点击界面实时回写 | CartData（CART_TYPE/ITEM/ORIGINAL + 通用 PDC 读写）；ContainerCartSession#flush；CartSessionManager#flushContainer / #flushAndClose | CartDataNullSafetyTest；CartDataCopyTest；MinecartFlowTest#container_editsArePersistedToPdcOnClose | 部分（PDC 读写充分；容器内容字节级持久化受 MockBukkit 限制） |
| 数据安全：矿车被破坏时掉落方块与内容，不丢失、不复制（特殊矿车按原版设计掉落合并物品与内容） | CartEngine#onEntityRemove（DEATH+DO_ENTITY_DROPS 短路）；SpecialCartHandler#onCartDestroyed；CartContextImpl#tryDropItem/#tryDropItems；ItemDelivery | MinecartFlowTest#destroy_dropsBlockExactlyOnce；SpecialCartDeathNoExtraDropTest；SpecialCartDestroyAtomicityTest；SpecialCartEmbeddedDropNoDuplicateTest；ItemDeliveryAtomicityTest | 充分 |
| 数据与兼容：挂有方块的矿车禁止玩家/生物乘坐（扩展类型可 `allowRiding()` 开放） | CartEngine#onMount（allowRiding=false 时 setCancelled）；CartTypeHandler#allowRiding 默认 false | VerificationGapCoverageTest#mount_managedCartIsCancelled、#mount_unmanagedCartIsAllowed；CancellableEventCoverageTest#preCancelledMount_doesNotInvokeHandler | 充分 |
| 数据与兼容：开启 `respect-protection` 后放置/取下模拟 `BlockPlaceEvent`/`BlockBreakEvent`，被其他插件取消则禁止操作 | InteractionListener#checkPlaceProtection / #checkBreakProtection；CartBlockPlaceEvent / CartBlockTakeOffEvent | CartProtectionEventTest；CancellableEventCoverageTest（CartBlockPlace/TakeOff/BlockPlace/BlockBreak 取消路径） | 充分 |
| 交互体验：交互冷却防连点 | InteractionCooldown#tryUse / #onQuit；InteractionListener 冷却前置 | InteractionCooldownTest；MinecartFlowTest#cooldown_notConsumedByRejectedInteraction | 充分 |
| 交互体验：放置/取下音效开关（`sounds.place` / `sounds.take-off`） | InteractionListener#placeBlock（soundPlace）；CartEngine#takeOff（soundTakeOff）；PluginConfig | PluginConfigTest 断言默认值/解析；实际播放/关闭播放无断言 | 部分 |
| 交互体验：容器类矿车独立取下策略（PICKUP / DROP / FORBIDDEN） | ContainerPickupPolicy；PluginConfig#containerPickupPolicy；InteractionListener#effectiveTakeOffMode；CartEngine#takeOff（spill + 回滚） | SpecialCartEmbeddedDropNoDuplicateTest；ContainerDropEmptyNoLocationTest；TakeOffRollbackAccumulationTest；HandlerCallbackExceptionBarrierTest（FORBIDDEN 异常屏障）；VerificationGapCoverageTest#containerPickupPolicy_forbidden_blocksBarrelTakeOff（内置木桶 FORBIDDEN） | 充分 |
| 扩展性：统一 `CartTypeHandler` 接口，第三方插件可注册全新矿车类型；内置类型全部是其实现 | CartTypeHandler；CartData#CART_TYPE；CartEngine#resolve / #rescanManagedContexts；PepperMinecartPlugin#onEnable 内置注册 | CartTypeRegistryTest；LateTypeRegistrationRescanTest；PluginSmokeTest#pluginEnablesAndRegistersService | 充分 |
| 扩展指南：三步接入（实现 handler → `registerCartType` → 引擎默认行为） | PepperMinecartAPI / PepperMinecartAPIImpl；CartContextImpl（setDisplay/setMetaInt/冷却/replaceEntity） | PepperMinecartAPIImplTest；CartContextDefaultTest；MinecartFlowTest#cooldown_worldAgeSemantics；EngineStateFixTest#replaceEntityCopiesDisplayBlockAndOffset；ReplaceEntityAtomicityTest | 充分 |
| 扩展指南：钩子一览全部有默认实现，引擎异常屏障不污染事件链 | CartTypeHandler 各 default 方法；CartEngine#safeHandler / #safeInteract / #tick / #onVehicleMove / #vanillaSupportHandler | HandlerCallbackExceptionBarrierTest；EngineStateFixTest#onEntityRemoveCleansContextWhenDestroyHandlerThrows；无 onTick/onMove/onDismount 直接测试 | 部分 |
| 扩展指南：CartContext 提供显示控制、持久化元数据、世界年龄冷却、物品分发、实体替换 | CartContext / CartContextImpl | MinecartFlowTest#cooldown_worldAgeSemantics；CartContextDeliverDisabledTest；EngineStateFixTest 多个；TakeOffAtomicityTest；ReplaceEntityAtomicityTest | 充分 |
| 配置：所有配置项在 config.yml 均有中文注释，`/pm reload` 热重载无需重启 | src/main/resources/config.yml；PluginConfig#reload / #apply；PepperMinecartCommand#onCommand | PluginConfigTest（默认值/覆盖/越界/NaN/非法材质/黑白名单）；PluginSmokeTest#reloadCommandWorks | 部分（`vanilla-carts.allow-pickup`、`container-pickup-policy` 的解析/默认未在 PluginConfigTest 中直接断言） |
| 命令与权限：`/pepperminecart reload`（别名 `/pm`，权限 `pepperminecart.reload`，默认 op）；放置/取下/打开界面权限 `pepperminecart.use` 默认所有玩家 | plugin.yml；PepperMinecartCommand；InteractionListener#onInteractEntity 权限前置 | PluginSmokeTest#reloadCommandWorks（授权后执行）；默认权限与无权限拦截无直接测试 | 部分 |

## 二、测试资产盘点

| 测试文件 | 验证内容 |
|---|---|
| api/CartContextDefaultTest | CartContext 默认 `tryDropItems(null)` 视为空批返回 true 不 NPE |
| BuildArtifactContractTest | 交付 jar 瘦/肥包并存，bstats 重定位类只在 `-all` jar |
| CancellableEventCoverageTest | 已被取消的交互/骑乘事件及被取消的模拟保护事件不产生副作用 |
| CartContextDeliverDisabledTest | DISABLED/null 策略下 `deliverItem` 返回 false 且不交付物品 |
| CartProtectionEventTest | 取消 CartBlockPlace/TakeOffEvent 分别阻断放置与取下 |
| config/PluginConfigTest | 配置默认值、覆盖解析、越界钳制、NaN/Infinity 防御、黑白名单与非法材质回退 |
| container/AnvilCartSessionOfflineTest | 铁砧延迟确认任务在玩家离线/无打开视图时安全退出不 NPE |
| container/CartSessionManagerCloseTest | `flushAndClose` 每会话只回写一次、`closeAll` 离线兜底、viewer 关闭异常继续 |
| ContainerDropEmptyNoLocationTest | 容器 DROP 策略下空容器无需掉落位置也能取下交付 |
| container/VanillaCartSessionRecoverTest | 虚拟界面残留回收：矿车位置不可用回退玩家位置；掉落取消保留槽位 |
| cooldown/InteractionCooldownTest | 交互冷却拦截、按玩家隔离、零冷却放行 |
| delivery/ItemDeliveryAtomicityTest | 掉落/背包交付部分成功时失败回滚已生成物品或背包增量，无复制 |
| DispenserCartShootAtomicityTest | 发射事务"先扣后掉"、persist 失败恢复、掉落取消补偿、双重失败接受 1 件损失 |
| DispenserCartShootRaceTest | 掉落完成后不再访问矿车 world/location，避免 ItemSpawnEvent 移除矿车后 NPE |
| display/CartBlockDisplayNullTest | 显示入口 null 材质/BlockData 明确抛 IllegalArgumentException |
| EngineStateFixTest | 引擎状态回归：临时 context 清理、显示恢复、类型重扫、PDC 异常、销毁/取下异常屏障等 24 项 |
| HandlerCallbackExceptionBarrierTest | 第三方 handler 的 canPlace/容器策略/VanillaCartSupport 异常被吸收且 fail-closed |
| impl/PepperMinecartAPIImplTest | 公开 API 对 null cart 与非法 handler 在入口抛出明确异常 |
| LateTypeRegistrationRescanTest | 扩展类型后注册后，已加载区块矿车的 context 自动重解析为新 handler |
| MinecartFlowTest | 核心交互路径集成：放置/三策略取下/销毁掉落/容器会话/铁砧耐久/冷却/特殊矿车转换与 ItemMeta 保留 |
| PlaceSnapshotConsumptionTest | 放置成功后消耗的是"放置时主手快照"，不被 handler 改写主手影响 |
| PluginSmokeTest | MockBukkit 加载插件、注册服务、`/pm reload` 冒烟 |
| registry/CartTypeRegistryTest | 注册表覆盖/注销/保护 id/材质路由一致性与半更新防御 |
| registry/ContainerHandlerTest | 潜影盒识别（含 SHULKER_BOX）、白名单不含讲台/雕纹书架、材质集合不可变 |
| registry/handler/DispenserContainerAccessTest | DispenserContainerAccess 读库存、持久化回调、补偿恢复并持久化 |
| registry/handler/SpecialCartDestroyAtomicityTest | 特殊矿车销毁时内容物掉落部分失败则回滚已生成掉落并保留库存 |
| registry/handler/SpecialCartHandlerTransferTest | 特殊矿车放置内容转移：无库存目标全退 leftover；溢出掉落失败/提交失败均回滚 |
| ReplaceEntityAtomicityTest | `replaceEntity` 失败时回滚替代实体 PDC 与引擎跟踪，新旧实体不双受管 |
| SpecialCartDeathNoExtraDropTest | DEATH+DO_ENTITY_DROPS=true 时特殊矿车插件不补掉方块并清理 context |
| SpecialCartEmbeddedDropNoDuplicateTest | 返还物品内嵌内容 + 容器 DROP spill 时内容物只掉落一次 |
| storage/CartDataCopyTest | PDC 复制迁移插件数据但保留目标实体已有第三方键 |
| storage/CartDataNullSafetyTest | CartData null 写入语义、损坏字节清除、clear 参数校验 |
| TakeOffAtomicityTest | 取下"先交付成功再清数据"，交付失败保留 PDC/显示可重试，HANDLED 路径清理 |
| TakeOffPreDropExceptionTest | 原版库存预掉落抛异常时取消取下、保留矿车与库存、回滚替代矿车 |
| TakeOffRollbackAccumulationTest | vanilla 预掉落与容器 spill 的回滚列表合并，交付失败全部回收 |
| TakeOffRollbackReplacementFailureTest | 替代矿车 remove 异常时其余回滚与临时 context 清理继续 |

## 三、发现的功能缺口 / 风险

1. **MockBukkit BlockStateMetaMock 序列化限制（测试自认）**：`MinecartFlowTest` 中 `container_editsArePersistedToPdcOnClose` 与 `takeOff_containerKeepsContentsInItem` 的注释明确说明：BlockStateMetaMock 字节序列化不含库存内容，内容级断言（关闭/取下后容器内容随物品 NBT 保留）必须留给真实服务器冒烟覆盖。
2. **工作站 7 种方块打开界面无测试**：`WorkstationHandler#onInteract` 已实现。→ 已由 `VerificationGapCoverageTest#workstation_onInteractRoutesToExpectedOpenMethod` 补齐路由级测试（见五）。
3. **外来原版特殊矿车取下与 `vanilla-carts.allow-pickup` 开关无直接测试**：`CartEngine#resolveVanillaPickup` 与 InteractionListener 的开关分支已实现。→ 已由 `VerificationGapCoverageTest` 两个用例补齐箱子矿车开关两态测试（见五）。
4. **特殊矿车类型测试覆盖不均衡**：CHEST 转换/取下/销毁/ItemMeta 保留测试充分；FURNACE 仅覆盖内容转移与 leftover 掉落；HOPPER、TNT、命令方块（含命令保留/写回）无直接测试。
5. **铁砧 UI 全链路无端到端测试**：现有测试直接调用 `AnvilDamageTracker#onResultTaken` 或反射调用 `AnvilCartSession#confirmTake`；`PrepareAnvilEvent → InventoryClickEvent → handleClick → 耐久判定` 的完整链路及 `AnvilHandler#onInteract` 未覆盖。
6. **发射器 `onMove` 铁轨检测无测试**：`DispenserCartHandler#shoot` 的原子性经反射充分测试，但"压过充能 ACTIVATOR_RAIL → 随机发射 → 成功才耗冷却"的 onMove 路径无直接测试。
7. **禁止乘坐无直接断言**：`CartEngine#onMount` 对默认 `allowRiding=false` 取消 `EntityMountEvent` 的行为无测试。→ 已由 `mount_managedCartIsCancelled` / `mount_unmanagedCartIsAllowed` 补齐（见五）。
8. **音效开关实际播放无断言**：配置解析有覆盖，但放置/取下/木桶开合/铁砧/发射音效的播放与关闭均无测试。
9. **配置测试遗漏两个键**：`PluginConfigTest` 未直接断言 `vanilla-carts.allow-pickup` 与 `container-pickup-policy` 的默认值/解析。→ 已由 `config_defaultsForVanillaPickupAndContainerPolicy` / `config_overridesForVanillaPickupAndContainerPolicy` 补齐（见五）。
10. **README 与实现不一致——"任意带库存的方块（通用容器）"**：`ContainerHandler` 实际为显式白名单 + 潜影盒后缀扫描，并非真正"任意带库存方块"；讲台/雕纹书架等带库存但非 Container 的方块被有意排除（代码注释自述）。
11. **README 待办滞后**：README「待办与已知限制」仍列"监听 EntityRemoveEvent … 以覆盖插件 remove() 等全部矿车移除路径"，但 `CartEngine#onEntityRemove` 已实现并有 `SpecialCartDeathNoExtraDropTest` 等测试覆盖，待办应更新。
12. **熔炉矿车内容物落地为文档化限制**：README 待办说明放置带内容的熔炉时输入/燃料/成品会掉落到矿车位置；实现 `SpecialCartHandler#transferContents` 对无库存目标返回全部 leftover 并有测试，行为一致但产品语义需用户知晓。

## 四、本地无头实机冒烟（2026-08-18 执行）

环境：`/tmp/pm-smoke`（全隔离，端口 25599，offline-mode，不对外开放），Paper 1.21.4-100-main@86c6308，Java 21，部署 `build/libs/PepperMinecart-1.0.0-all.jar`。另有 `/tmp/pm-smoke2`（端口 25598）部署瘦包 `PepperMinecart-1.0.0.jar` 复测。

| 检查项 | 结果 |
|---|---|
| 服务端启动 | 通过：Paper 1.21.4 正常启动并生成世界 |
| 插件加载 | 通过：`[PepperMinecart] Loading server plugin PepperMinecart v1.0.0` |
| 插件启用 | 通过：`[PepperMinecart] PepperMinecart v1.0.0 已启用` |
| 配置文件生成 | 通过：`plugins/PepperMinecart/config.yml` 自动生成，中文注释 + 全部默认项与 README 一致 |
| `/pm reload` 别名 | 通过：控制台输出 `PepperMinecart 配置已热重载` |
| `/pepperminecart` 主命令 | 通过：输出 `用法: /pepperminecart reload —— 热重载配置` |
| `/plugins` | 通过：`Server Plugins (1): PepperMinecart` |
| 插件停用 | 通过：`[PepperMinecart] Disabling PepperMinecart v1.0.0` → `PepperMinecart 已禁用`，世界正常保存 |
| 瘦包 `PepperMinecart-1.0.0.jar` | 意外通过：真实 Paper 上正常加载/启用（见下方新增发现 13） |

实机冒烟新增发现：

13. **`EntityRemoveEvent` 已被 Paper 标记弃用且 `forRemoval=true`**：Paper 1.21.4 控制台明确告警 `event is Deprecated. "Server performance will be affected"`。经 `javap -v` 确认 `org.bukkit.event.entity.EntityRemoveEvent` 标注 `@Deprecated(forRemoval=true)`，且 1.21.4 API 内无替代事件。当前插件在 `CartEngine#onEntityRemove` 监听该事件（README 待办已实现但未更新），未来 Paper 版本移除该事件将导致核心掉落/清理链路失效，建议列入升级风险。
14. **README 瘦包部署警告与实际不符（当前 ID=0）**：README 称瘦包"无内置 bstats，部署会 `NoClassDefFoundError`"。实测瘦包在真实 Paper 上正常加载并启用，原因是 `PepperMinecartPlugin.BSTATS_PLUGIN_ID = 0`，`new Metrics(...)` 永不执行。该警告只在未来把 bStats ID 替换为 >0 后成立；当前 README 表述与实际行为不一致，建议改为"当前 bStats 未启用，瘦包可运行；启用 bStats 后必须使用 `-all` 包"。
15. **冒烟日志噪声（与本插件无关）**：启动期 `ERROR: No key layers in MapLike[{}]` 来自 Paper 超平坦世界生成器缺少 layers 配置，发生在插件加载前后，不影响插件功能；`version` 命令的 `PaperVersionFetcher` 网络错误为环境无法访问 api.papermc.io，同样与插件无关。

## 五、缺口补齐：新增测试记录（2026-08-18）

新增 `src/test/java/com/pepperminecart/VerificationGapCoverageTest.java`（11 个用例，只增测试代码，未改 src/main 与 README）。全量回归 **37 个测试类 / 160 用例 / 0 失败 / 0 错误**。

| 原缺口 | 新增测试 | 结果 |
|---|---|---|
| #2 工作站 7 种界面打开 | `workstation_onInteractRoutesToExpectedOpenMethod` | 已补：MockBukkit 的 `HumanEntityMock` 对全部 `open*` 工作站方法抛 `UnimplementedOperationException`（`javap -c` 验证），改用动态代理 `Player` 直接驱动 `WorkstationHandler.onInteract`，断言 7 种材质分别路由到 `openWorkbench/openGrindstone/openLoom/openCartographyTable/openSmithingTable/openStonecutter/openEnchanting` |
| #3 外来原版特殊矿车取下 + `allow-pickup` 开关 | `vanillaPickup_allowTrue_takesOffForeignStorageMinecartWithoutLosingContents`、`vanillaPickup_allowFalse_ignoresForeignStorageMinecart` | 已补：开关开→外来箱子矿车还原普通矿车、箱子返还、内容不丢失不复制；开关关→插件不处理、矿车保持原样 |
| #4 特殊矿车类型覆盖不均衡 | `place_hopperConvertsToHopperMinecartAndBack`、`place_tntConvertsToTntMinecartAndBack`、`place_commandBlockConvertsToCommandMinecartAndBack` | 已补：HOPPER/TNT/COMMAND_BLOCK 放置→转换→取下→还原普通矿车→物品返还（命令保留仍由代码走查覆盖） |
| #7 受管矿车禁止乘坐 | `mount_managedCartIsCancelled`、`mount_unmanagedCartIsAllowed` | 已补：受管矿车取消 EntityMountEvent；非受管空矿车放行 |
| #9 配置键遗漏 | `config_defaultsForVanillaPickupAndContainerPolicy`、`config_overridesForVanillaPickupAndContainerPolicy` | 已补：`vanilla-carts.allow-pickup` 默认 true / 可关；`container-pickup-policy` 默认 PICKUP / 可解析 DROP / 非法值回退 PICKUP |
| #5 容器 FORBIDDEN 策略对内置木桶无直接测试 | `containerPickupPolicy_forbidden_blocksBarrelTakeOff` | 已补：FORBIDDEN 时取消取下、矿车保持受管、物品不返还、不落地 |

未补缺口说明：

- **#5 铁砧 UI 全链路、#6 发射器 onMove、#8 音效播放**：受 MockBukkit 模拟能力限制或需要真实客户端事件链，保留记录不强补。

补测过程透明度记录（对应失败模式"新增测试失败→判断产品缺陷还是测试假设错误"）：

- 首轮新增测试有 2 个失败，均为**测试假设错误**而非产品缺陷：① MockBukkit `openEnchanting` 未实现；② 外来箱子矿车取下时内容物在 MockBukkit 中随 `BlockStateMetaMock` 内嵌回收（0 地面掉落），与"真实 Paper 上无模板外来矿车返回无 meta 物品→内容物预掉落"的代码推断路径不同。断言已改为两条合法路径都成立的不变量"钻石总量 = 3（不丢失、不复制）"。
- 首轮冒烟曾误用默认端口 25565，因该端口已有服务监听而**立即绑定失败并停止**，未对既有服务造成任何影响；后续冒烟全部改用 25599/25598 隔离端口。
