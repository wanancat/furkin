package com.wanancat.furkin.internal.revive;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 复活仪式的结构判定（M4.2，设计稿 §3.4「复活」）。
 *
 * <p>结构 = 5×5 俯视菱形，中心一块羊毛、四边各 3 朵花（共 12 朵），
 * 四角与内圈为空。玩家手持魂石右键中心羊毛触发。</p>
 *
 * <pre>
 * 空 花 花 花 空
 * 花 空 空 空 花
 * 花 空 羊毛 空 花
 * 花 空 空 空 花
 * 空 花 花 花 空
 * </pre>
 *
 * <p><b>判定口径（2026-09-22 乌狸定）</b>：</p>
 * <ul>
 *   <li>「花」= 任意花，用官方 tag {@link BlockTags#FLOWERS} 判（涵盖小朵花
 *       {@code FlowerBlock} 系 + 两格高花 {@code TallFlowerBlock} 系；
 *       {@code instanceof FlowerBlock} 会漏掉两格高花，因其父类是
 *       {@code DoublePlantBlock}）。</li>
 *   <li>「空位」<b>不严格判空</b>（只看 12 朵花 + 中心羊毛到位）。</li>
 *   <li>花与羊毛<b>必须同一层</b>（同 Y）。</li>
 *   <li>结构<b>保留</b>（花 / 羊毛不消耗，代价由魂石承担）。</li>
 * </ul>
 *
 * <p><b>手法参照</b>：原版传送门 {@code PortalShape} 构造器逐格
 * {@code getBlockState} 判边框 + 内部空气；本判定是同一模式、更简单的
 * 「固定相对坐标逐格判」版本。</p>
 */
public final class ReviveStructure {

    /** 12 朵花的相对坐标（相对中心羊毛，dz 为南向、与 {@code BlockPos} 的 z 同号）。 */
    private static final BlockPos[] FLOWER_OFFSETS = {
            // 上边（3 朵）
            new BlockPos(-1, 0, -2), new BlockPos(0, 0, -2), new BlockPos(1, 0, -2),
            // 左边（3 朵）
            new BlockPos(-2, 0, -1), new BlockPos(-2, 0, 0), new BlockPos(-2, 0, 1),
            // 右边（3 朵）
            new BlockPos(2, 0, -1), new BlockPos(2, 0, 0), new BlockPos(2, 0, 1),
            // 下边（3 朵）
            new BlockPos(-1, 0, 2), new BlockPos(0, 0, 2), new BlockPos(1, 0, 2),
    };

    private ReviveStructure() {
    }

    /**
     * 判定 {@code center}（玩家右键的那块羊毛）四周是否构成有效复活结构。
     *
     * @param level  所在世界
     * @param center 中心羊毛的方块坐标（其 Y 即花与羊毛的判定层）
     * @return 结构是否有效
     */
    public static boolean matches(Level level, BlockPos center) {
        // 中心必须是羊毛方块（BlockTags.WOOL，不含地毯 WOOL_CARPETS）。
        BlockState centerState = level.getBlockState(center);
        if (!centerState.is(BlockTags.WOOL)) {
            return false;
        }

        // 12 朵花逐一判（同层：dy 已在 offset 里固定为 0）。
        for (BlockPos offset : FLOWER_OFFSETS) {
            BlockPos flowerPos = center.offset(offset.getX(), offset.getY(), offset.getZ());
            BlockState flowerState = level.getBlockState(flowerPos);
            if (!flowerState.is(BlockTags.FLOWERS)) {
                return false;
            }
        }
        return true;
    }
}
