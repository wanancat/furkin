# WP-07 命令回执本地化（L-02）

- 状态：实现、构建、资源键校验、客户端启动烟测完成；中英文游戏内切换尚需人工验收
- 日期：2026-09-25
- 基线：WP-06 后的工作树
- 对应问题：`FurkinCommand` 的玩家可见回执大量硬编码英文
- 适用范围：Minecraft 1.19.2 / Forge 43.2.0

---

## 1. 审查结论

修复前 `FurkinCommand` 从召唤、列表、解绑、改名、加经验、模式、加点、查看、行囊到清仓都存在 `Component.literal(...)` 固定英文。命令文字是玩家可见输出，违反项目“玩家可见文本使用翻译键”的规则。

命令字面量和参数名（`furkin`、`summon`、`list`、`pet_id` 等）仍是 Brigadier 标识符，不属于本地化对象；UUID、短 ID、玩家自定义名字、物品名和技能 ID 继续作为动态参数传入。

## 2. 1.19.2 API 取证

对 Forge `43.2.0` 的 `CommandSourceStack` 执行 `javap`：

```text
public void sendSuccess(net.minecraft.network.chat.Component, boolean);
public void sendFailure(net.minecraft.network.chat.Component);
```

1.19.2 没有 1.20.1 所使用的 `sendSuccess(Supplier<Component>, boolean)` 重载。因此本分支不能照抄 1.20.1 的惰性 supplier 写法，已改为先构造 `Component.translatable(...)` 再发送；翻译键和参数仍在客户端语言环境下解析。

## 3. 实施结果

- `FurkinCommand` 的正式回执迁移到 `Component.translatable`，固定拼句改为完整语义键；动态数量、名字、模式、技能 ID 继续作为参数。
- 新增 34 个 `furkin.command.*` 键和 2 个共用 `furkin.msg.*` 键（`invalid_pet_id`、`unbind_not_found`）。
- `en_us.json` 与 `zh_cn.json` 均更新，键集合完全一致。
- 未修改命令树、权限、参数类型、业务行为、网络协议或存档格式。

## 4. 验证证据

- `compileJava`：`BUILD SUCCESSFUL`。
- `clean build`：`BUILD SUCCESSFUL`。
- 语言文件：`en_us` 与 `zh_cn` 各 164 键，集合差为空；`furkin.command.*` 共 34 键。
- 静态检查：`FurkinCommand` 不再存在非动态英文的 `Component.literal(...)`；剩余 literal 仅为 `?`、缩进、短 ID、完整 UUID、玩家输入名字等动态/结构内容。
- 静态键引用：命令中的固定翻译键均存在；`furkin.combat_mode.*` 的 follow / passive / protect / aggressive 四键均存在。
- `runClient` 启动到主菜单，日志无资源键缺失、类加载错误、项目包 `ERROR` 或 `FATAL`。

## 5. 残余与边界

- 本轮未在游戏内切换到中文/英文执行完整命令矩阵；固定键存在性、动态键存在性和代码路径已静态核对，但实际聊天栏语言切换仍作为人工验收残余。
- `not_owner`、损坏 capability、幽灵档案等异常分支仍需要第二玩家或故障夹具，属于 L-02 之外的功能验证边界。
- 命令行的玩家自定义名字保持字面量，符合“动态输入不翻译”的口径。
