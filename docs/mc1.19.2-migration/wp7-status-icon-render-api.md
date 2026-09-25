# WP7d：1.19.2 头顶状态图标渲染 API

## 结论

`FurkinStatusIconRenderer` 可以保留原有 billboard、高度偏移、亮度和渲染距离判定，
只替换矩阵类型与渲染阶段。

## 矩阵类型

1.20.1 使用 `org.joml.Matrix4f`；1.19.2 的 `PoseStack` 和
`RenderLevelStageEvent#getProjectionMatrix()` 使用 `com.mojang.math.Matrix4f`。

javap 已确认：

- `PoseStack.Pose#pose()` 返回 `com.mojang.math.Matrix4f`
- `VertexConsumer#vertex(com.mojang.math.Matrix4f, float, float, float)` 存在
- `RenderType#entityTranslucent(ResourceLocation)` 存在

因此只替换 import，顶点写入链不需要改写。

## 渲染阶段

1.20.1 的阶段顺序包含：

```text
AFTER_CUTOUT_BLOCKS
AFTER_ENTITIES
AFTER_BLOCK_ENTITIES
AFTER_TRANSLUCENT_BLOCKS
```

1.19.2 没有 `AFTER_ENTITIES`，可用阶段为：

```text
AFTER_SKY
AFTER_SOLID_BLOCKS
AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS
AFTER_CUTOUT_BLOCKS
AFTER_TRANSLUCENT_BLOCKS
AFTER_TRIPWIRE_BLOCKS
AFTER_PARTICLES
AFTER_WEATHER
```

采用 `AFTER_PARTICLES`。它在 1.19.2 中位于实体、方块实体和粒子绘制之后，
是最接近 `AFTER_ENTITIES` 语义的可用稳定节点。

`AFTER_WEATHER` 作为后备方案：如果实机发现图标被粒子或天气遮挡不理想，
只替换阶段常量即可，不需要改渲染主体。

## 预期行为

以下逻辑保持不变：

- 面向相机的 billboard 旋转
- 头顶高度偏移
- `LightTexture.FULL_BRIGHT` 全亮
- `RenderType.entityTranslucent` 贴图渲染
- `ForgeMod.NAMETAG_DISTANCE` 距离判定
- `showStatusIcon` 客户端开关

## 验证状态

编译层验证完成。第三人称、第一人称、远处实体、粒子和天气遮挡关系仍需在
WP9 客户端实机测试中确认。

## 相关文件

- `FurkinStatusIconRenderer.java`
- `docs/mc1.19.2-migration/wp7-creative-tab-api.md`
