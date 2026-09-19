# Furkin（绒亲）

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-blue)

![Forge](https://img.shields.io/badge/Forge-47.2.0-orange)

![License](https://img.shields.io/badge/License-MIT-green)

轻量化的**伴侣宠物框架**：把原版动物契约成有成长、有技能树、能穿装备、可复活的伴侣宠物，
并开放 API 让其他模组把自己的生物也接入成为伴侣。

> English docs: [README.md](./README.md)。

---

## 特性

- **契约** — 一张道具即可契约任意已注册物种，无需事先驯服。
- **技能树** — 共享主干 + 专属分支（狗主战斗、猫主侦察/辅助）。
- **装备** — 对齐原版 4 槽，第三方盔甲零配置自动可穿。
- **复活** — 魂石制复活，成长不丢失。

## 依赖

| 需求             | 版本   |
| ---------------- | ------ |
| Minecraft        | 1.20.1 |
| Minecraft Forge  | 47.2.0 |
| Java             | 17     |

**零强制前置。** 不需要任何依赖库模组。

## 安装

1. 安装 [Minecraft Forge 1.20.1](https://files.minecraftforge.net)（47.2.0 或更高）。
2. 从 releases 下载最新 `.jar`。
3. 放入你的 `.minecraft/mods` 文件夹。

## 用法

制作一张**绒亲契约**，右键任意有效动物即可将其契约成伴侣。
制作一本**绒亲录**来管理你的伴侣（召唤、收回、技能点、装备）。

> 详细游戏内指引随 1.0 版本发布。

## 模组开发者

Furkin 定位为**前置模组**：依赖它，通过公开 API（`com.wanancat.furkin.api`）
注册你自己的物种、技能与效果。

```java
// 注册一个物种
FurkinApi.registerSpecies(EntityType.WOLF, ...);
```

任何已注册的物种都可以——哪怕是僵尸，只要你觉得它够毛绒绒。:)

完整 API 见 `api` 包。后续会提供独立的示例模组。

## 从源码构建

```bash
# 需要 JDK 17
./gradlew build
```

模组 jar 产物位于 `build/libs/furkin-<version>.jar`。

运行开发客户端或服务器：

```bash
./gradlew runClient
./gradlew runServer
```

## 许可证

[MIT](./LICENSE.txt)。可自由使用、修改与再分发——包括整合包和作为依赖。

## 致谢

- **作者**：wanancat
- 基于 Minecraft Forge 模组框架构建。
