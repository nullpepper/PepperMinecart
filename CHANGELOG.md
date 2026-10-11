# Changelog

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循语义化版本。

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
