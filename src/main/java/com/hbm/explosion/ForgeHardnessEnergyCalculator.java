package com.hbm.explosion;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Forgeブロック硬度ベースのエネルギー計算
 *
 * **設計原則:**
 * 1. block.getBlockHardness() を使用 - 全てのブロックに対応
 * 2. 硬度1ごとに物理的耐性が比例増加
 * 3. 爆風と熱の両方に適用可能
 * 4. 深度による補正（地下では抵抗増加）
 *
 * **物理モデル:**
 * - Energy Cost = Base × Hardness × Depth Factor
 * - Hardness = Forge hardness value (0 = air, 0.5 = dirt, 3 = stone, 50 = obsidian, -1 = bedrock)
 * - Depth Factor = 1.0 + (depth / 10)^0.5 (地下では抵抗増加)
 */
public class ForgeHardnessEnergyCalculator {

    // === 基本エネルギー定数 ===
    private static final double BLAST_BASE_ENERGY = 1.0e6;      // 爆風: 1 MJ/block
    private static final double THERMAL_BASE_ENERGY = 5.0e5;    // 熱: 500 kJ/block

    // === 硬度スケーリング係数 ===
    private static final double HARDNESS_SCALING = 1.5;         // 硬度1ごとに1.5倍

    // === 深度係数 ===
    private static final double DEPTH_SCALING = 0.1;            // 深度10mで1.5倍

    /**
     * 爆風破壊のエネルギーコスト計算
     *
     * @param world ワールド
     * @param pos ブロック座標
     * @param state ブロック状態
     * @param depthBelowSurface 地表からの深さ (m)
     * @return エネルギーコスト (J)
     */
    public static double calculateBlastEnergyCost(World world, BlockPos pos, IBlockState state,
                                                   double depthBelowSurface) {
        Block block = state.getBlock();

        // Bedrock: 無限エネルギー
        if (block == Blocks.BEDROCK) {
            return Double.POSITIVE_INFINITY;
        }

        // Air: コストゼロ
        if (block == Blocks.AIR) {
            return 0.0;
        }

        // Forge硬度取得
        float hardness = block.getBlockHardness(state, world, pos);

        // 硬度-1 = 破壊不可能 (bedrock相当)
        if (hardness < 0) {
            return Double.POSITIVE_INFINITY;
        }

        // 硬度0 = 瞬時破壊 (草、花など)
        if (hardness == 0) {
            return BLAST_BASE_ENERGY * 0.01;
        }

        // **硬度比例スケーリング**
        // hardness 1.0 → 1.5x
        // hardness 2.0 → 2.25x (1.5^2)
        // hardness 3.0 → 3.375x (1.5^3)
        double hardnessFactor = Math.pow(HARDNESS_SCALING, hardness);

        // **深度補正** (地下では抵抗増加)
        double depthFactor = 1.0 + Math.sqrt(depthBelowSurface * DEPTH_SCALING);

        return BLAST_BASE_ENERGY * hardnessFactor * depthFactor;
    }

    /**
     * 熱破壊のエネルギーコスト計算
     *
     * @param world ワールド
     * @param pos ブロック座標
     * @param state ブロック状態
     * @param depthBelowSurface 地表からの深さ (m)
     * @return エネルギーコスト (J)
     */
    public static double calculateThermalEnergyCost(World world, BlockPos pos, IBlockState state,
                                                     double depthBelowSurface) {
        Block block = state.getBlock();

        // Bedrock: 破壊不可
        if (block == Blocks.BEDROCK) {
            return Double.POSITIVE_INFINITY;
        }

        // Air: コストゼロ
        if (block == Blocks.AIR) {
            return 0.0;
        }

        // Forge硬度取得
        float hardness = block.getBlockHardness(state, world, pos);

        // 硬度-1 = 破壊不可能
        if (hardness < 0) {
            return Double.POSITIVE_INFINITY;
        }

        // 硬度0 = 瞬時破壊
        if (hardness == 0) {
            return THERMAL_BASE_ENERGY * 0.01;
        }

        // **硬度比例スケーリング** (熱も同じ)
        double hardnessFactor = Math.pow(HARDNESS_SCALING, hardness);

        // **深度補正** (熱は地下に浸透しにくい)
        // 爆風よりも強い減衰
        double depthFactor = 1.0 + Math.sqrt(depthBelowSurface * DEPTH_SCALING * 2.0);

        // **材料ボーナス** (可燃物は熱に弱い)
        double materialBonus = 1.0;
        if (isFlammable(block)) {
            materialBonus = 0.3;  // 可燃物は70%減
        } else if (isMeltable(block)) {
            materialBonus = 0.5;  // 融解物は50%減
        }

        return THERMAL_BASE_ENERGY * hardnessFactor * depthFactor * materialBonus;
    }

    /**
     * ブロックが飛ばされるかの判定
     *
     * @param world ワールド
     * @param pos ブロック座標
     * @param state ブロック状態
     * @param blastPressure 爆風圧力 (Pa)
     * @return true = 飛ばされる
     */
    public static boolean shouldBlockBeLaunched(World world, BlockPos pos, IBlockState state,
                                                 double blastPressure) {
        Block block = state.getBlock();

        if (block == Blocks.AIR || block == Blocks.BEDROCK) {
            return false;
        }

        float hardness = block.getBlockHardness(state, world, pos);

        if (hardness < 0) {
            return false;  // 破壊不可
        }

        // **打ち上げ閾値: 硬度に比例**
        // hardness 0.5 (土) → 10 kPa
        // hardness 1.5 (石) → 30 kPa
        // hardness 3.0 (鉄) → 60 kPa
        double launchThreshold = 20000.0 * (1.0 + hardness);

        return blastPressure >= launchThreshold;
    }

    // === 材料判定ヘルパー ===

    private static boolean isFlammable(Block block) {
        return block == Blocks.LEAVES || block == Blocks.LEAVES2 ||
               block == Blocks.LOG || block == Blocks.LOG2 ||
               block == Blocks.PLANKS ||
               block == Blocks.WOOL || block == Blocks.CARPET ||
               block == Blocks.TALLGRASS || block == Blocks.DEADBUSH ||
               block == Blocks.VINE ||
               block == Blocks.OAK_FENCE || block == Blocks.SPRUCE_FENCE ||
               block == Blocks.BIRCH_FENCE || block == Blocks.JUNGLE_FENCE ||
               block == Blocks.DARK_OAK_FENCE || block == Blocks.ACACIA_FENCE;
    }

    private static boolean isMeltable(Block block) {
        return block == Blocks.GLASS || block == Blocks.STAINED_GLASS ||
               block == Blocks.GLASS_PANE || block == Blocks.STAINED_GLASS_PANE ||
               block == Blocks.ICE || block == Blocks.PACKED_ICE ||
               block == Blocks.SNOW || block == Blocks.SNOW_LAYER;
    }

    /**
     * デバッグ用: ブロック情報表示
     */
    public static String getBlockInfo(World world, BlockPos pos, IBlockState state) {
        Block block = state.getBlock();
        float hardness = block.getBlockHardness(state, world, pos);
        double blastCost = calculateBlastEnergyCost(world, pos, state, 0);
        double thermalCost = calculateThermalEnergyCost(world, pos, state, 0);

        return String.format("%s [hardness=%.1f, blast=%.2e J, thermal=%.2e J]",
            block.getRegistryName(), hardness, blastCost, thermalCost);
    }
}
