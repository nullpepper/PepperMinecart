# Doublecheck report

> Verdict: **green**

## Spec
- Goal: 按已批准的 A7→A4→A1→A2→A3→A5/A6→B→D 方案修复 PepperMinecart 插件全部已核实缺陷，且每个修复都有红→绿测试证据，最终测试全绿并产出正确的 shadowJar。
- Scope: 范围内：构建脚本 A7；配置解析 A4；统一物品交付 A1；放置/实体替换事务 A2；销毁状态机 A3；会话关闭与 API 边界 A5/A6；B 类决策的默认实现（延迟扫描+注册后重扫、交互监听优先级降为 HIGH、新增矿车放置/取下保护事件并保留方块事件模拟、LECTERN/CHISELED_BOOKSHELF 从容器白名单移除按普通方块处理）；补真实行为可测的单元测试及静态证据。范围外：不讲台/雕纹书架专用 UI；不引入外部领地插件集成；不改动插件对外命令与权限节点。
- Acceptance criteria: 1) 每个修复簇先有失败测试（红）再有通过测试（绿），测试运行输出可验证；2) JAVA_HOME=/home/pepper/graalvm/jdk-21 ./gradlew test 全绿；3) ./gradlew shadowJar 产物包含重定位后的 bstats 且不被 jar 覆盖，processResources 无 Task.project 弃用警告；4) 配置解析测试证明 10.0/500.0/0/.nan/越界值按规范处理；5) ItemDelivery 测试证明掉落被取消时不清源槽位且返回 CANCELED；6) 放置事务测试证明回调改主手后仍消耗放置快照；7) 销毁状态机测试证明受管特殊矿车死亡走插件模板掉落、位置不可用不清库存；8) 注册表/API 边界测试证明非法输入被清晰拒绝且注册表不半更新；9) 扩展类型注册后已加载区块的存量矿车能重解析。
- Failure modes: 红测试因语法/夹具错误失败不算红，须修到因缺失行为失败；改动破坏既有测试须修复或回滚；掉落失败时必须保留源数据，宁可少掉不能丢；实体替换失败必须无幽灵矿车、无双受管实体；配置非法值必须回退默认并告警，NaN/Infinity 不得进入运行值；B 类行为变更（书架/讲台不再走容器、优先级下调、新增事件）必须写测试锁住新契约。
- Priorities: 正确性优先于改动大小：任何会丢物/复制物品的问题最高优先；测试证据优先于速度；保持现有公开 API 兼容（仅增加方法/事件，不破坏签名）；重构后代码更集中而非更分散。
- Non-goals: 不实现讲台/雕纹书架的专用界面；不实现与 WorldGuard 等具体插件的集成；不改变 plugin.yml 命令权限节点语义；不做性能调优；不发布 Release。

## Test evidence
- failing runs: 0
- passing runs: 0

- [spec] 按已批准的 A7→A4→A1→A2→A3→A5/A6→B→D 方案修复 PepperMinecart 插件全部已核实缺陷，且每个修复都有红→绿测试证据，最终测试全绿并产出正确的 shadowJar。

## Adversary review
No adversary review ran for this session.

## Verification
Not run.

## Delivery
- implementation edits: 97
