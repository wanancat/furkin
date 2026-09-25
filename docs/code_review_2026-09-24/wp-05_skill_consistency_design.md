# WP-05 技能退款、Schema 与热重载一致性设计

- 文档状态：M-02、M-04 已实施并完成运行期验证
- 审计日期：2026-09-25
- 基线提交：`6638717`（`docs: 固化网络协议版本治理规则`）
- 对应问题：`M-02` 洗点退款未乘技能 `cost`、技能 schema 缺少加载期校验；`M-04` 热重载后已有属性效果可能残留、流血语义漂移
- 适用版本：Minecraft 1.20.1 / Forge 47.2.0

---

## 1. 现状与边界

- `FurkinData` 与 `FurkinArchiveEntry` 原本只保存技能等级和可用技能点，无法还原每级实际支付成本。
- `SkillLoader` 原本不拒绝 `cost <= 0`、非法 `maxLevel`、非法 `tier` 和非正 `requiresLevel`。
- 属性效果通过 `AttributeModifier` 持久化。数据包删除技能或修改属性目标后，当前移除逻辑只按新树查定义，旧 modifier 可能残留。
- `SkillRegistry.reloadTree` 原本只替换技能树和缓存，不重建已加载实体的技能效果。
- 流血效果每次 tick 从当前 JSON 读取 DPS，属于实时重载语义，但原文档没有明确声明。

## 2. 决策

### 2.1 实际支付成本

- 使用按技能累计的 `skillInvestments`：技能 id → 实际累计支付技能点。
- 只进入实体持久化 NBT 和服务器级绒亲档案，不进入 `syncNBT()`，因此不改变网络包结构或协议版本。
- 每次成功加点追加当前 `cost`；洗点按累计支付总额退款，不再按等级数退款。
- 旧档缺少该字段时标记为 unknown，首次加点或洗点按当前技能定义迁移；技能定义已删除时按每级 1 点保守兜底并记录 WARN。

### 2.2 Schema 校验

加载期拒绝以下定义，并跳过该技能：

- `cost <= 0`
- `maxLevel == 0` 或非 `-1` 的负值
- `tier < 1`
- `requiresLevel` 不是对象，或其中任一值 `<= 0`

### 2.3 热重载效果

- 内置 `furkin:attribute` 使用固定 modifier 名称 `furkin.skill.attribute` 做统一清理，再按当前技能树重建。
- `/reload` 后在服务器线程遍历所有已加载维度中的已加载绒亲，执行清理和重建。
- 实体入世时再次执行清理和重建，覆盖重载时区块未加载的实体。
- 不扫描未加载档案，不加载区块，不引入 tick 轮询。
- 第三方 effect 不承诺跨定义删除的自动清理；当前稳定公开 API 不暴露 effect 实现注册面。

### 2.4 流血语义

- `/reload` 立即改变现有流血的 DPS，但不改写已施加的效果持续时间。
- 技能定义删除或参数失效后，现有流血不再造成伤害，效果自然到期。
- 该语义需同步写入 `SKILL_TREE.md` 和双语 changelog。

## 3. 实施拆分

### WP-05-02 M-02 实际支付成本与 schema

状态：已完成。

- `SkillLoader`：加入四项加载期校验。
- `FurkinData` / `FurkinArchiveEntry`：加入实际支付表、旧档 unknown 标记和持久化读写。
- `SkillProgress`：加点累计实付、洗点按实付退款、旧档一次性迁移。
- 收回、死亡、召唤和技能档案回写路径同步复制实付表。
- 网络同步继续只发送技能等级和技能点，协议保持 `2`。

### WP-05-03 M-04 热重载重建

状态：已完成。

- 加入 `furkin:attribute` 统一清理。
- 加入重载后的已加载实体重建和实体入世校准。
- 加入流血实时语义的文档、变更记录和运行期测试。

## 4. M-02 验证结果

执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat compileJava --console=plain
.\gradlew.bat build --console=plain
.\gradlew.bat runServer --console=plain
```

- `compileJava`：`BUILD SUCCESSFUL`
- `build`：`BUILD SUCCESSFUL`
- 第一轮临时服务端夹具：`WP05_M02_FIXTURE_OK checks=28 failed=0`。
- 覆盖：合法 `cost=2` 加载、非法定义拒绝、首级与热改成本后的累计退款、网络同步不含实付表、实体与档案 NBT 往返、旧档案 unknown 标记和旧实体迁移退款。
- schema 专项轮：`WP05_M02_SCHEMA_FIXTURE_OK checks=7 failed=0`；覆盖合法 `cost=2` 通过，以及 `cost=0`、`cost=-1`、`maxLevel=0`、`maxLevel=-2`、`tier=0`、`requiresLevel=0` 全部拒绝。
- 合并证据：`checks=35 failed=0`。
- 两轮夹具及临时技能定义均已删除；删除后重新 `build` 通过，最终 JAR 不含 `Wp05` / `wp05`。
- 删除夹具后的 `runServer` 到达 `Done (2.669s)!`，`latest.log` 无 `[ERROR]` / `[FATAL]` / 类加载异常 / 注册失败。
- 日志中预期的非法技能拒绝 WARN 与临时夹具 WARN/INFO 均来自测试输入，已随夹具删除；剩余为 oshi/PDH、WMI、旧版 AI 状态等既有噪声。

## 5. M-04 验证结果

- `AttributeEffect`：所有技能属性 modifier 使用固定名称 `furkin.skill.attribute`；重建时遍历注册属性表并清除本模组添加的全部技能属性 modifier，再按当前技能树重挂。
- `SkillRuntimeCalibrator`：重载后置位，服务器 tick 末尾遍历所有已加载维度的已加载绒亲；不加载区块、不轮询。实体入世、召唤、洗点和解绑清理也走统一清理路径。
- 流血实时语义：`/reload` 立即改变已有流血 DPS，不重写持续时间；定义删除或参数失效后已有流血不再造成伤害并自然到期。
- 临时服务端夹具：`WP05_M04_FIXTURE_OK checks=26 failed=0`。
- 覆盖：属性目标切换、删除技能后的清理、已加载实体扫描、实体入世校准、属性 NBT 往返后入世清理、真实资源重载、流血 DPS 热更新、持续时间保持、定义失效停伤。
- 夹具删除后重新 `build`，最终 JAR 不含 `Wp05` / `wp05`；无夹具 `runServer` 通过且日志无 `[ERROR]` / `[FATAL]` / 异常栈。

## 6. 未完成与残余验证

- 未单独执行进程级重启；旧属性 modifier 的 NBT 往返与再次入世清理已在同一 `runServer` 流程中验证。
- 旧档中已删除且曾使用非 1 成本的第三方技能无法还原准确历史支付额，只能按最低成本 1 兜底。
- 不构造真实旧客户端/新服务端混连；WP-05 没有网络包结构变更，协议治理结论沿用 WP-04。
