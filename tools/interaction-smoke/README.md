# 交互冒烟（真实客户端）

用一个真实的离线客户端（[mineflayer](https://github.com/PrismarineJS/mineflayer)）连进 Paper，按真人的操作序列去点矿车，
断言的全是玩家能看到的结果：背包里物品数量的增减、界面有没有打开、容器里的东西还在不在、实体元数据里的显示方块有没有跟着变。

它补的是 MockBukkit 测不到的那一层：协议交互（潜行右键走的是服务端的哪条分支）、原版界面真的能不能开、物品真的有没有进出背包。
本轮实机冒烟抓到的「没装 PepperClaim 时插件整个无法启用」就是这一层独有的问题——单测与 MockBukkit 都发现不了。

## 覆盖的场景

一次完整跑 49 项断言，分十段：

| 段 | 场景 | 断言 |
| --- | --- | --- |
| 1 | 铺铁轨、放矿车 | 铁轨落地、矿车实体生成、手上的矿车被消耗 |
| 2 | 潜行右键装方块 / 取下 | 手上的方块 −1、显示方块进实体元数据；取下后方块回到背包、显示方块从元数据消失 |
| 3 | 工作站类方块（制图台） | 装上去、普通右键开出原版 `minecraft:cartography`（39 格）、取下来回到背包 |
| 4 | 领地保护（需 PepperClaim 替身） | 判定拒绝时方块不离开背包；解除后立即成功；再取下回到背包 |
| 5 | 容器类方块（木桶） | 装上去 → 开 `minecraft:generic_9x3`（63 格）→ 塞进 8 个石头 → 关界面 → 重开内容还在 → 取下交还带内容的木桶 → 装回 → 内容仍是 8 个 |
| 6 | 原版矿车（箱子） | 装上后变 `chest_minecart`、开箱界面、塞进去的物品随取下写进方块物品、取下变回普通矿车、箱子回到背包、装回后内容还在 |
| 7 | 原版矿车（漏斗、熔炉） | 分别变成 `hopper_minecart` / `furnace_minecart`，取下回到对应方块 |
| 8 | 铁砧矿车 | 装上去、开 `minecraft:anvil`、取下回到背包 |
| 9 | 发射器矿车 | 装上去、开 `minecraft:generic_3x3`（45 格）、塞 8 支箭，压过充能激活铁轨弹出 1 件并扣库存 |
| 10 | 交付被取消（需 SmokeHooks 替身） | 背包塞满 + 掉落全禁时取下：矿车与显示方块保持原样、方块不消失、地上不掉东西；对照组（只放开掉落）方块正常丢到地上、矿车上不再有它 |

## 前置条件

- Paper **1.21.4**（`api-version: '1.21.4'`，低版本装不上），`server-port` 与下面一致；
- `online-mode=false`（用离线名字 `SmokeBot` 连）；
- 服务端目录放好 `PepperLib.jar` 与本插件的 `PepperMinecart-2.0.0-all.jar`；
- `ops.json` 里有 `SmokeBot` 的离线 UUID（脚本要用 `/give`、`/tp`、`/fill`、`/data`）。离线 UUID 是
  `UUID.nameUUIDFromBytes("OfflinePlayer:SmokeBot")`，即 `99f0a0b2-37a3-3506-ab3f-7ba635ed7c58`：
  ```json
  [{"uuid":"99f0a0b2-37a3-3506-ab3f-7ba635ed7c58","name":"SmokeBot","level":4,"bypassesPlayerLimit":true}]
  ```
- `spawn-protection=0`（脚本会在出生点附近搭台子）；
- Node.js 18+。

## 替身插件

后两组场景需要两个只有十几行、`plugin.yml` 里写明名字的替身插件，源码在 `stubs/`：

| 替身 | 干什么 | 命令 |
| --- | --- | --- |
| `SmokeHooks` | 取消所有 `ItemSpawnEvent`，模拟「别的插件不许掉落」 | `/smokehook on\|off` |
| `PepperClaim` | 提供与真实门面同签名的 `ltd.pepper.claim.ClaimApi` | `/claimstub on\|off` |

```bash
cd tools/interaction-smoke/stubs
SERVER_DIR=/path/to/server ./build.sh        # 编译并复制进 plugins/
```

`build.sh` 用 Gradle 缓存里的 `paper-api:1.21.4` 编译（也可用 `PAPER_API=` 指定）。
不需要替身时加 `STUB_CLAIM=0 STUB_HOOKS=0`（默认就是关），这两组会自动跳过。

## 跑起来

```bash
cd tools/interaction-smoke
npm install
cd /path/to/server          # 放着 paper.jar 的服务端目录
STUB_CLAIM=1 STUB_HOOKS=1 SERVER_DIR=$PWD MC_PORT=25599 /path/to/tools/interaction-smoke/run.sh
```

退出码就是断言结果：`0` 全部通过，非 `0` 为失败（并打印失败项）。

## 说明

- **矿车必须放在铁轨上**：原版 `MinecartItem#useOn` 要求点中的方块属于 `BlockTags.RAILS`，否则直接 `FAIL`。
- 潜行右键是手工构造 `use_entity` 包（mineflayer 的 `activateEntity` 把 `sneaking` 写死成 `false`），
  再配 `entity_action` 切潜行状态，与真人客户端发出的包一致。
- 每段先清场：`freshCart()` 杀掉旧矿车与掉落物、清背包、重新铺轨放一辆空车。不清场的话上一段的矿车与内容会串进来，
  曾导致「箱子矿车」一段莫名失败。
- 单次点击可能被服务端吞掉（交互冷却、实体还在客户端同步中），`attach` / `takeOff` / `openWindow`
  按**玩家可见结果的重试**，而不是固定等待。
- 脚本会 `/clear` 玩家背包、在 x≈2 处搭台子铺轨，结束时 `/kill @e[type=minecart]`，只适合一次性测试服。
