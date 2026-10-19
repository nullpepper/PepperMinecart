# PepperMinecart 开发文档

> 面向维护者与贡献者。用户文档见根目录 `README.md`。

## 1. 设计原则

1. **能交给原版的都交给原版。** 界面用原版界面（`MenuType`、方块自己的库存类型），物品归还用原版界面的关闭逻辑，
   箱子/漏斗/熔炉/TNT/命令方块直接变成原版矿车实体，显示方块用原版实体数据，铁砧受损照搬原版规则。
   插件只补原版没有的那一层：把“方块”和“矿车”关联起来。
2. **新增矿车类型不改核心。** 引擎只认识 `CartType` 接口；内置类型与第三方类型走同一个注册表、同一套钩子。
3. **数据只有一份，就在实体上。** 类型 id 与方块物品存矿车 PDC，显示存原版 NBT；`ManagedCart` 只是实体之上的视图，
   随用随建，不缓存状态，因此不存在“引擎记录与实体不一致”的问题。

## 2. 技术栈与构建

- Java 21，Gradle Kotlin DSL，`io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT`；
- 测试：JUnit 5 + MockBukkit；
- 产物由 Shadow 打包，relocate `org.bstats`；
- 前置库 PepperLib（`ltd.pepper:pepper-lib`，版本见 `gradle.properties`）从 `mavenLocal()` 解析。

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew clean build
```

## 3. 源码结构

| 包 | 职责 |
|---|---|
| `api` | 对外扩展接口：`CartType`（类型钩子）、`ManagedCart`（矿车视图）、`PepperMinecartAPI`（服务）、保护事件 |
| `cart` | 引擎：`CartManager`（生命周期编排，兼 API 实现）、`CartListener`（事件 → 操作）、`CartRegistry`、`CartData`（PDC 读写）、`CartMenus`（矿车界面的打开与关闭）、`ManagedCartImpl` |
| `type` | 内置矿车类型：`PlainBlockCart`、`WorkstationCart`、`AnvilCart`、`ContainerCart`、`DispenserCart`、`VanillaMinecartCart`，以及方块物品数据工具 `BlockItems` |
| `config` | 配置模型（PepperLib `@ConfigModel`）与门面 `PluginConfig` |
| `protection` | 领地保护：`ProtectionGateway` 组合 PepperClaim 联动与伪方块事件两种后端 |
| `cooldown` | 玩家交互冷却 |
| `command` | `/pm reload` |

## 4. 核心流程

### 4.1 放上方块（`CartManager#place`）

1. 只接受空着的普通矿车：没有乘客、没有插件数据、也没有别人设置的显示方块；
2. 主手是方块且配置允许 → 按方块找类型（没有专门类型的用 `PlainBlockCart`）→ `canPlace` → 领地保护 → 冷却；
3. 扣 1 个物品（创造模式不扣，同原版）→ 写入类型 id 与方块物品 → 显示方块 → `onPlaced`。

`onPlaced` 抛异常时方块已经在矿车上、物品已扣，状态一致，玩家可以正常取下。

### 4.2 使用（`CartManager#use`）

普通矿车上的方块界面没有原版方块事件，先问领地保护再调用 `onUse`；原版特殊矿车（箱子矿车等）的右键是原版交互，
直接交给类型（默认什么也不做，原版打开界面）。

### 4.3 取下（`CartManager#takeOff`）

1. `take-off-mode: DISABLED` 或类型 `canTakeOff` 返回 false → 不处理，点击交给原版；
2. 有乘客、领地拒绝、冷却中 → 吞掉点击；
3. **先关闭矿车的所有界面**（容器界面在关闭时写回方块物品）→ `takeOff` 得到要交付的物品 →
   `removeBlock` 清除数据并恢复原版外观 → 按模式交付，播放该方块的原版破坏音效。

### 4.4 移除（`CartManager#onRemoved`，监听 `EntityRemoveEvent`）

- `UNLOAD`（区块卸载）与 `PLAYER_QUIT`（乘客下线，矿车随玩家保存）是暂时离开：只关闭界面，不掉落；
- 其余视为摧毁：关闭界面后调用 `onDestroyed`，默认掉落方块物品；
- 原版特殊矿车：`DEATH` 且 doEntityDrops 开启时原版会掉落“箱子矿车”这类物品，插件不补掉；
  `EXPLODE`（TNT 矿车自爆）不返还 TNT。

### 4.5 矿车界面（`CartMenus`）

方块界面在矿车处没有真实方块，打开时关闭原版的可用性检查（`checkReachable(false)`），改由 `CartMenus` 每 tick 按矿车检查：
矿车无效、类型 id 变了（方块被取下、铁砧损毁）或玩家离开 8 格就关闭——与原版矿车容器的 `stillValid` 一致。
界面里的物品如何退还由原版界面自己负责。

### 4.6 实体转换（`ManagedCart#convertTo`）

用 `World#spawn` 的预处理回调生成新实体（位置、速度、名字、记分板标签、全部 PDC 在加入世界前就设好），
再清掉旧实体的插件数据与库存后移除——旧实体的移除不会被当成摧毁，原版也不会把库存撒出来。

## 5. 内置类型速览

| 类型（id） | 方块 | 实现要点 |
|---|---|---|
| `PlainBlockCart`（`generic`） | 没有专门类型的方块 | 全部默认行为；也是类型丢失时的兜底 |
| `WorkstationCart`（`workstation`） | 工作台等 7 种 | 方块 → `MenuType` 对照表，新增工作站只加一行 |
| `AnvilCart`（`anvil`） | 三种铁砧 | 原版铁砧界面；“点击结果槽 + 同一次点击里第一个输入槽被清空”即判定取走结果，按原版规则受损 |
| `ContainerCart`（`container`） | 木桶、潜影盒等 | 按方块自己的库存类型建界面，内容读写方块物品的容器数据；同一辆矿车所有查看者共用一份库存 |
| `DispenserCart`（`dispenser`） | 发射器 | 继承 `ContainerCart`，`onMove` 检测充能激活铁轨 |
| `VanillaMinecartCart`（`special`） | 箱子、漏斗、熔炉、TNT、命令方块 | 放上即 `convertTo` 原版矿车；`nativeBlock` 让原版矿车也能取下 |

## 6. 约定

- **不做数据自愈。** 读不出的方块物品只记日志、跳过本次操作，不猜测、不删除数据。
- **扩展钩子的异常交给 Bukkit。** 事件里的异常由 Bukkit 记录且不影响其他插件；唯一的例外是 tick 循环，
  一辆矿车的 `onTick` 出错只记录日志，不拖累其他矿车。
- **命名空间。** `pepperminecart` 保留给内置类型；矿车取下时清除 `pepperminecart` 与类型命名空间下的 PDC 键，
  其他插件的数据不动。
- **保护判定只在上游动作做一次**（放上、取下、打开界面、环境弹出），详见 `ProtectionGateway` 的类注释。

## 7. 测试

```bash
./gradlew test
```

- `PlacementTest`、`TakeOffTest`、`DestroyTest`、`RidingTest`、`CartMenuTest`、`VanillaMinecartTest`、
  `CartTypeApiTest`、`ProtectionIntegrationTest`、`RobustnessTest`：加载真实插件的 MockBukkit 集成测试，按玩家可见的行为断言
  （`RobustnessTest` 专门盯失败路径：交付被取消、放置回调抛异常、存档数据损坏、实体转换）；
- `type` 包下的测试：用内存中的 `InMemoryCart` 单独测试每个内置类型
  （MockBukkit 序列化物品时会丢失容器内容，涉及内容的断言放在这一层）；
- `config`、`protection`、`cooldown`：各自模块的单元测试。

MockBukkit 的已知限制：不能构建 `MenuType` 界面（工作站/铁砧界面的打开只验证对应关系），没有方块音效组
（测试中关闭音效），激活铁轨的方块数据不是 `RedstoneRail`（测试中用代理）。新增功能或修复问题时请同步补充测试。

### 交互冒烟（真实客户端）

`tools/interaction-smoke/` 用一个真实的离线客户端（mineflayer）连进 Paper 1.21.4，按真人操作序列点矿车，49 项断言：
铺轨放矿车、潜行右键装/取方块、工作站开原版界面、容器塞取内容并取下再装回、领地保护拒绝、原版箱/漏斗/熔炉矿车
的来回转换、铁砧界面、发射器压激活铁轨弹出、以及「交付被别的插件取消」时矿车保持原样。断言的全是玩家可见的结果
（背包数量、界面类型、容器内容、实体元数据里的显示方块），补上 MockBukkit 测不到的协议交互与原版界面那一层。

```bash
cd tools/interaction-smoke && npm install
cd stubs && SERVER_DIR=/path/to/paper-server ./build.sh          # 两个替身插件
cd /path/to/paper-server && STUB_CLAIM=1 STUB_HOOKS=1 SERVER_DIR=$PWD MC_PORT=25599 /path/to/run.sh
```

`STUB_CLAIM` 需要 `PepperClaim` 替身（`/claimstub on|off`），`STUB_HOOKS` 需要 `SmokeHooks` 替身
（`/smokehook on|off`，取消所有 `ItemSpawnEvent`）；不装时对应的几组自动跳过。

细节与前置条件（离线 UUID 进 ops.json、`online-mode=false`、矿车必须放在铁轨上）见该目录的 README。

## 8. 2.0.0 迁移注意

破坏性变更的完整清单见 CHANGELOG，下面三条最容易踩：

- **`ManagedCart#dropItem` 现在返回 `boolean`**：表示这一件是否真的交付成功。注意 Paper 的
  `World#dropItem(Naturally)` 即使生成被 `ItemSpawnEvent` 取消也照样返回实体、**从不返回 null**，
  判断取消只能用 `Item#isValid()`（被取消的实体已被 discard）。插件自己的交付原语 `CartManager#deliver`
  就是这么判的，扩展插件要判断交付结果请照做。
- **受管判定收窄为「PDC 里有类型键」**：`CartData#isManaged` 只看 `pepperminecart:cart-type`，
  旧版「有方块物品键也算」的 OR 分支与 `PepperMinecartAPI#isManagedCart(Minecart)` 都已删除。
  需要判断一辆矿车是否受管时，用 `PepperMinecartAPI#getCart(Minecart) != null`。
- **不再有异常屏障**：除 tick 循环外，扩展回调抛出的异常会直接冒泡到 Bukkit 事件系统（旧版会吞掉异常
  并取保守结果）。依赖这一点的扩展需要自己 `try/catch`。
- **`pepperminecart:cart-type` 损坏时按普通方块处理**：`CartData#type` 解析失败返回 `null`，
  `CartManager#find` 会退到 `nativeBlock` / 默认类型，不会删除数据。
