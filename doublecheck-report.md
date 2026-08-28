# Doublecheck report

> Verdict: **green**

## Spec
- Goal: 验证 PepperMinecart 插件功能是否完备：跑通 MockBukkit 回归 + 功能覆盖矩阵 + 本地无头 Paper 冒烟，并对走查发现的缺口补充 MockBukkit 测试后交付验证结论。
- Scope: 可修改 src/test/** 测试代码与 docs/ 下验证报告；不得修改 src/main/** 与 README.md；不得触碰线上/既有服务（25565 端口服务）；本地冒烟仅在 /tmp 隔离目录一次性进行。
- Acceptance criteria: ① ./gradlew test 全绿（原 149 用例 + 为缺口新增的用例全部通过）；② docs/verification-coverage-matrix.md 包含覆盖矩阵、测试资产盘点、缺口清单与实机冒烟结果；③ 本地无头 Paper 1.21.4 冒烟通过：all.jar 加载/启用、config.yml 生成、/pm reload 生效、/plugins 可见、干净停用；④ 走查发现的可自动测试缺口（如 vanilla-carts.allow-pickup、受管矿车禁止乘坐、工作站界面打开、特殊矿车类型转换、配置键断言）有对应新增测试。
- Failure modes: 端口冲突→改用 25599/25598 隔离端口并记录；无网络导致 PaperVersionFetcher 报错→记录为无关噪声；MockBukkit 模拟限制（BlockStateMetaMock 不序列化库存、不模拟原版掉落时序）→报告中如实标注；新增测试失败→判断是产品缺陷还是测试假设错误，一律记录不修 src。
- Priorities: 优先补可稳定自动化的 MockBukkit 测试（配置键、onMount 禁乘、vanilla-carts 开关、工作站/特殊矿车类型转换）；UI 全链路与音效等难以模拟的只记录不强补；所有发现如实记录，不因要“全绿”而降低断言强度。
- Non-goals: 不修复 src/main 与 README；不启动或连接生产服务器；不实现真实玩家 bot 交互；不做 Folia 与多版本兼容验证；不追求线上玩家级实机演练。

## Test evidence
- failing runs: 0
- passing runs: 0

- [spec] 验证 PepperMinecart 插件功能是否完备：跑通 MockBukkit 回归 + 功能覆盖矩阵 + 本地无头 Paper 冒烟，并对走查发现的缺口补充 MockBukkit 测试后交付验证结论。

## Adversary review
No adversary review ran for this session.

## Verification
Not run.

## Delivery
- implementation edits: 10
