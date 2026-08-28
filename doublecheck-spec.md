# Doublecheck spec

## Goal
仅修改服务端已部署的 config.yml，杜绝玩家通过矿车系统获得携带 NBT 的非原版容器物品（如"带库存的箱子/漏斗"），不引入代码改动、不重启服务器。

## Scope
改 /opt/docker/minecraft/mc/data/plugins/PepperMinecart/config.yml 两处：container-pickup-policy 置 DROP；vanilla-carts.allow-pickup 置 false。随后 /pm reload 热重载生效。不改源码、不改项目仓库默认 config.yml。

## Acceptance criteria
①config.yml 中 container-pickup-policy: DROP 且 vanilla-carts.allow-pickup: false（含注释说明）；②/ pm reload 成功且 latest.log 无配置解析告警；③行为正确：木桶/投掷器/通用容器矿车潜行取下→内容物落地、裸容器物品归还（无 NBT 内容）；原版特殊矿车（箱子/漏斗/熔炉等）潜行取下被取消；破坏特殊矿车仍为原版掉落（Minecart with Chest 等原版物品+内容物分离）。

## Failure modes
配置值拼写/格式错误→插件回退默认 PICKUP/true 并打告警，保护静默失效（reload 后须检查无告警）；reload 时存在打开中的容器界面→会话机制正常回写；RCON 不可用→回退 docker restart 生效；特殊矿车禁止取下后玩家无法以物品形式取回箱子/内容→内容仍可经原版界面访问，破坏矿车按原版掉落（可接受，符合策略诉求）。

## Priorities
安全策略优先于便捷（内容落地/禁取优先于保留 NBT 物品）；配置改动优先于代码改动；热重载优先于重启以不打扰在线玩家；保持潜影盒原版行为（其携带内容属原版可获得物品）。

## Non-goals
不改源码；不改项目仓库默认 config.yml；不追溯清理存量世界中已有矿车/玩家背包已存在的 NBT 容器物品（无可行手段）；不禁用整个矿车系统；不改变潜影盒取下语义；不改变特殊矿车被破坏时的原版掉落。
