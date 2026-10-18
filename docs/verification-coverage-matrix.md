# PepperMinecart 功能覆盖矩阵

README「功能特性」「数据与兼容」「扩展指南」各项声明与实现、测试的对应关系。
集成测试位于 `src/test/java/ltd/pepper/pepperminecart/`（加载真实插件），类型单元测试位于其 `type/` 子包。

| 功能声明 | 实现 | 测试 |
|---|---|---|
| 潜行右键空矿车放上方块：扣 1 个、显示方块、创造模式不扣 | `CartManager#place` | `PlacementTest` |
| 黑白名单、非方块、他人显示方块、权限、冷却 | `CartManager#place`、`InteractionCooldown` | `PlacementTest` |
| 取下三种模式；背包满时掉在玩家脚下；有乘客不能取下 | `CartManager#takeOff` | `TakeOffTest` |
| 取下清除类型命名空间数据、保留其他插件数据 | `CartData#clear` | `TakeOffTest` |
| 摧毁时掉落一次；卸载/乘客下线不掉落 | `CartManager#onRemoved`、`CartType#onDestroyed` | `DestroyTest` |
| 载着方块禁止乘坐，类型可开放 | `CartListener#onMount` | `RidingTest` |
| 矿车界面按原版规则关闭（走远、取下、摧毁、插件禁用） | `CartMenus` | `CartMenuTest` |
| 工作站打开对应的原版界面 | `WorkstationCart` | `type/WorkstationCartTest` |
| 铁砧按原版规则受损；创造模式、几率 0、关闭时不受损；损毁可掉落 | `AnvilCart` | `type/AnvilCartTest` |
| 容器：原版界面类型、关闭写回、多人共享、PICKUP/DROP/FORBIDDEN、潜影盒例外 | `ContainerCart`、`BlockItems` | `type/ContainerCartTest`、`CartMenuTest` |
| 发射器：充能激活铁轨弹出、冷却、空库存不耗冷却、界面同步、环境保护闸 | `DispenserCart` | `type/DispenserCartTest` |
| 箱子/漏斗/熔炉/TNT/命令方块 ↔ 原版矿车，名字、内容、命令往返 | `VanillaMinecartCart` | `VanillaMinecartTest`、`type/VanillaMinecartCartTest` |
| 原版矿车：外来矿车可取下、开关关闭交给原版、原版掉落不重复、TNT 爆炸不返还 | `VanillaMinecartCart` | `VanillaMinecartTest` |
| 扩展 API：注册/保留命名空间/非方块拒绝/内置不可注销/插件禁用自动注销/重注册生效/类型丢失兜底 | `CartManager`（API）、`CartRegistry` | `CartTypeApiTest`、`TakeOffTest`、`DestroyTest` |
| 扩展钩子：`onUse`、`onTick`、`canPlace`、`displayBlock`、`takeOff`、`convertTo`、冷却 | `CartType`、`ManagedCartImpl` | `CartTypeApiTest` |
| 取下/放置的交付事务：交付被取消时保留原样、只放回没交付的部分、放置异常归还物品 | `CartManager#takeOff/place/deliver` | `TakeOffTest`、`RobustnessTest` |
| 实体转换迁移乘客与显示方块；转换失败清掉新实体 | `CartManager#replaceEntity` | `RobustnessTest` |
| 受管矿车登记时补回显示方块（第三方清掉显示后不再隐形） | `CartManager#track` | `RobustnessTest` |
| 类型 id / 方块物品数据损坏时按普通方块处理，不拖垮 tick 循环 | `CartManager#find/tick`、`CartData` | `RobustnessTest` |
| 注册校验：空 id、非方块材质、保留命名空间；注销时的判空 | `CartRegistry#register` | `CartTypeApiTest`、`RobustnessTest` |
| 领地保护：伪事件后端的四种取消、界面闸（按材质路由旗标、原版矿车不重复判、总开关） | `protection` 包、`CartManager#use` | `ProtectionIntegrationTest`、`protection/*` |
| 配置解析、热重载、默认文件 | `config` 包、`PepperMinecartCommand` | `config/*`、`PluginSmokeTest` |
| 交付产物（`-all` 包含重定位后的 bStats） | `build.gradle.kts` | `BuildArtifactContractTest` |

## 已移除的能力（当前无覆盖）

下面这些能力在 2.0.0 里被删除，删除是有意的（理由见 CHANGELOG 的「移除」），但它们此前有测试守护，
删除后没有任何测试覆盖，列在这里以便评估回归风险：

| 已移除的能力 | 旧实现 | 现状 |
|---|---|---|
| 掉落被其他插件取消后的多层回滚与补偿 | `delivery/ItemDelivery`（部分成功回滚、背包回滚）、`CartEngine` 的取下事务 | 只保留「未交付的部分放回矿车」这一步，由 `TakeOffTest`、`RobustnessTest` 覆盖；旧实现依赖 `World#dropItem(Naturally)` 的返回值判定，而它在真实 Paper 上从不返回 null，因此旧补偿只在 MockBukkit 里生效 |
| 扩展回调的异常屏障 | `CartEngine` 的 `safeHandler` / `safeInteract` / `isManagedSafe` / `contextOrNullSafe` | 只剩 tick 循环有屏障（`RobustnessTest#oneThrowingCartTypeDoesNotStopTheTickLoop`）；其余扩展回调抛出的异常直接冒泡到 Bukkit 事件系统 |
| 损坏存档数据的容错 | `EngineStateFixTest`（27 项） | 只剩 `RobustnessTest` 里 3 项（类型 id 缺失、方块物品反序列化失败、tick 抛异常），其余场景无覆盖 |
| 预取消的原版挂载事件 | 旧版在 `EntityMountEvent` 已被取消时仍走自己的处理 | `RidingTest` 只覆盖插件自己取消挂载 |

## 需要真实服务器验证的部分

MockBukkit 无法覆盖以下行为，发布前应在 Paper 服务器上按 [`smoke-test-checklist.md`](smoke-test-checklist.md) 冒烟：

- 工作站、铁砧界面的实际打开（MockBukkit 不能构建 `MenuType` 界面）；
- 容器内容经 PDC 保存后的往返（MockBukkit 序列化物品会丢失容器内容，相关断言放在类型单元测试中）；
- 原版特殊矿车被打坏、被移除时原版自身的掉落；
- 放置/取下的方块音效（MockBukkit 没有方块音效组）。
