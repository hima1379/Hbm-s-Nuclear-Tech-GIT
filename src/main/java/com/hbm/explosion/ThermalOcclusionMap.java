package com.hbm.explosion;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;

/**
 * 熱線遮蔽マップ (Thermal Occlusion Map)
 *
 * Fibonacci球面分布を使用した放射状スキャンにより、
 * 火球からの直接視界が遮蔽されたブロックを高速検出
 *
 * 完全なレイトレーシングより軽量で、十分な精度を提供
 */
public class ThermalOcclusionMap {

    private final World world;
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final double maxRadius;

    // 遮蔽されたブロックのセット
    private final Set<Long> occludedBlocks = new HashSet<>();

    /**
     * コンストラクタ
     *
     * @param world ワールド
     * @param x 火球中心X座標
     * @param y 火球中心Y座標
     * @param z 火球中心Z座標
     * @param maxR 最大スキャン範囲 (通常は火球半径の2.5倍)
     */
    public ThermalOcclusionMap(World world, double x, double y, double z, double maxR) {
        this.world = world;
        this.centerX = x;
        this.centerY = y;
        this.centerZ = z;
        this.maxRadius = maxR;
    }

    /**
     * 遮蔽マップ生成
     * Fibonacci球面分布で方向をサンプリングし、放射状にスキャン
     */
    public void generateOcclusionMap() {
        long startTime = System.currentTimeMillis();

        // 方向数: 火球の大きさに応じて調整 (1000-5000方向)
        int numDirections = Math.min(5000, Math.max(1000, (int) (maxRadius * maxRadius / 100)));

        // Fibonacci球面分布で方向生成
        double[][] directions = generateFibonacciSphere(numDirections);

        // 各方向にレイをトレース
        for (double[] dir : directions) {
            traceOcclusionRay(dir[0], dir[1], dir[2]);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("[THERMAL] Occlusion map generated: " + occludedBlocks.size() +
                " occluded blocks, " + numDirections + " rays, " + elapsed + "ms");
    }

    /**
     * 単一方向の遮蔽レイをトレース
     * 最初の障害物より後ろのブロックを遮蔽としてマーク
     *
     * @param dx 方向X成分 (正規化済み)
     * @param dy 方向Y成分 (正規化済み)
     * @param dz 方向Z成分 (正規化済み)
     */
    private void traceOcclusionRay(double dx, double dy, double dz) {
        boolean blocked = false;
        double step = 2.0;  // 2mステップ（粗いが高速、必要に応じて調整可能）

        // 火球半径の50%から250%までスキャン
        double startDist = maxRadius * 0.5;
        double endDist = maxRadius;

        for (double dist = startDist; dist <= endDist; dist += step) {
            int bx = (int) Math.round(centerX + dx * dist);
            int by = (int) Math.round(centerY + dy * dist);
            int bz = (int) Math.round(centerZ + dz * dist);

            // ワールド境界チェック
            if (by < 0 || by > 255) {
                break;
            }

            BlockPos pos = new BlockPos(bx, by, bz);
            Block block = world.getBlockState(pos).getBlock();

            // 固体ブロック検出
            if (block != Blocks.AIR && block != Blocks.BEDROCK) {
                if (!blocked) {
                    // 最初の障害物を検出
                    blocked = true;
                } else {
                    // 障害物の後ろのブロック = 遮蔽
                    long key = packCoords(bx, by, bz);
                    occludedBlocks.add(key);
                }
            }
        }
    }

    /**
     * Fibonacci球面分布で均等な方向ベクトルを生成
     *
     * @param numSamples サンプル数
     * @return 正規化された方向ベクトル配列 [n][3]
     */
    private double[][] generateFibonacciSphere(int numSamples) {
        double[][] directions = new double[numSamples][3];
        double goldenRatio = (1.0 + Math.sqrt(5.0)) / 2.0;

        for (int i = 0; i < numSamples; i++) {
            // Fibonacci螺旋アルゴリズム
            double theta = 2.0 * Math.PI * i / goldenRatio;
            double phi = Math.acos(1.0 - 2.0 * (i + 0.5) / numSamples);

            // 球面座標から直交座標へ
            double sinPhi = Math.sin(phi);
            directions[i][0] = Math.cos(theta) * sinPhi;  // x
            directions[i][1] = Math.cos(phi);              // y
            directions[i][2] = Math.sin(theta) * sinPhi;  // z
        }

        return directions;
    }

    /**
     * ブロックが遮蔽されているかチェック
     *
     * @param x ブロックX座標
     * @param y ブロックY座標
     * @param z ブロックZ座標
     * @return 遮蔽されていればtrue
     */
    public boolean isOccluded(int x, int y, int z) {
        long key = packCoords(x, y, z);
        return occludedBlocks.contains(key);
    }

    /**
     * 座標を64bit整数にパック
     * メモリ効率的なブロック座標保存
     *
     * @param x X座標
     * @param y Y座標
     * @param z Z座標
     * @return パックされた座標
     */
    private long packCoords(int x, int y, int z) {
        return ((long) x & 0xFFFFFFL) |
                (((long) y & 0xFFFFL) << 24) |
                (((long) z & 0xFFFFFFL) << 40);
    }

    /**
     * 遮蔽ブロック数を取得
     *
     * @return 遮蔽されたブロック数
     */
    public int getOccludedBlockCount() {
        return occludedBlocks.size();
    }
}
