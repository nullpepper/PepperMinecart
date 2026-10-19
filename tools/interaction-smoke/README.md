# 交互冒烟（真实客户端）

用一个真实的离线客户端（[mineflayer](https://github.com/PrismarineJS/mineflayer)）连进 Paper，按真人的操作序列去点矿车，
断言的全是玩家能看到的结果：背包里物品数量的增减、界面有没有打开、容器里的东西还在不在、实体元数据里的显示方块有没有跟着变。

它补的是 MockBukkit 测不到的那一层：协议交互（潜行右键走的是服务端的哪条分支）、原版界面真的能不能开、物品真的有没有进出背包。

## 覆盖的场景

| 场景 | 断言 |
| --- | --- |
| 铺铁轨、放矿车 | 铁轨落地、矿车实体生成、手上的矿车被消耗 |
| 潜行右键：把方块装到矿车上 | 手上的方块 −1，显示方块进了实体元数据 |
| 潜行右键：取下方块 | 方块回到背包，显示方块从元数据里消失 |
| 工作站类方块（制图台） | 装上去、普通右键开出原版 `minecraft:cartography` 界面、取下来回到背包 |
| 领地保护（需要替身门面） | 判定拒绝时方块不会离开背包；解除后立刻可用 |
| 容器类方块（木桶） | 装上去 → 开界面 → 塞进一叠石头（8 个）→ 关界面 → 重开内容还在 → 取下交还带内容的木桶 → 装回后再开内容仍是 8 个 |

## 前置条件

- Paper **1.21.4**（`api-version: '1.21.4'`，低版本装不上），`server-port` 与下面一致；
- `online-mode=false`（用离线名字 `SmokeBot` 连）；
- 服务端目录放好 `PepperLib.jar` 与本插件的 `PepperMinecart-2.0.0-all.jar`；
- `ops.json` 里有 `SmokeBot` 的离线 UUID（脚本要用 `/give`、`/tp`、`/fill`）。离线 UUID 是
  `UUID.nameUUIDFromBytes("OfflinePlayer:SmokeBot")`，即 `99f0a0b2-37a3-3506-ab3f-7ba635ed7c58`：
  ```json
  [{"uuid":"99f0a0b2-37a3-3506-ab3f-7ba635ed7c58","name":"SmokeBot","level":4,"bypassesPlayerLimit":true}]
  ```
- `spawn-protection=0`（脚本会在出生点附近搭台子）；
- Node.js 18+。

## 跑起来

```bash
cd tools/interaction-smoke
npm install
cd /path/to/server          # 放着 paper.jar 的服务端目录
SERVER_DIR=$PWD MC_PORT=25599 /path/to/tools/interaction-smoke/run.sh
```

退出码就是断言结果：`0` 全部通过，非 `0` 为失败（并打印失败项）。

## 保护闸场景

需要服务端装一个提供 `ltd.pepper.claim.ClaimApi` 的替身插件（能通过 `/claimstub on|off` 切"一律拒绝 / 一律放行"）。
装上后加 `STUB_CLAIM=1` 再跑，会多出三条关于保护闸的断言。没有替身插件时这组自动跳过。

## 说明

- **矿车必须放在铁轨上**：原版 `MinecartItem#useOn` 要求点中的方块属于 `BlockTags.RAILS`，否则直接 `FAIL`。
- 潜行右键是手工构造 `use_entity` 包（mineflayer 的 `activateEntity` 把 `sneaking` 写死成 `false`），
  再配 `entity_action` 切潜行状态，与真人客户端发出的包一致。
- 脚本会 `/clear` 玩家背包、在 x≈2 处搭台子铺轨，结束时 `/kill @e[type=minecart]`，只适合一次性测试服。
