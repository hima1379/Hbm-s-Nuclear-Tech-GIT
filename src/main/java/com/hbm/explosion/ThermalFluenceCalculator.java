package com.hbm.explosion;

/**
 * 時間積分熱フルエンス計算機 (Time-Integrated Thermal Fluence Calculator)
 *
 * 火球の全持続時間にわたる熱放射を時間積分し、
 * 各距離での総熱フルエンス (J/m²) をルックアップテーブルに保存
 *
 * これにより、O(時間ステップ × 距離²) → O(距離²) の計算量削減を実現
 */
public class ThermalFluenceCalculator {

    // 物理定数
    private static final double STEFAN_BOLTZMANN = 5.670374419e-8;  // W/(m²·K⁴)
    private static final double ATMOSPHERIC_ABSORPTION = 0.00012;   // m⁻¹ (海面レベル)

    // 火球物理オブジェクト
    private final FireballPhysics fireball;

    // フルエンスルックアップテーブル
    private final double[] fluenceLookupTable;
    private final int tableSize;
    private final double maxRange;

    /**
     * コンストラクタ: ルックアップテーブルを生成
     *
     * @param fireball 火球物理オブジェクト
     */
    public ThermalFluenceCalculator(FireballPhysics fireball) {
        this.fireball = fireball;

        // 最大範囲: 火球半径の2.5倍
        this.maxRange = fireball.getMaxRadius() * 2.5;

        // テーブルサイズ: 距離0から最大範囲まで1m刻み
        this.tableSize = (int) Math.ceil(maxRange) + 1;
        this.fluenceLookupTable = new double[tableSize];

        // ルックアップテーブル計算（重い処理は初期化時のみ）
        computeFluenceLookupTable();
    }

    /**
     * ルックアップテーブル生成
     * 各距離での時間積分熱フルエンスを事前計算
     */
    private void computeFluenceLookupTable() {
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < tableSize; i++) {
            double distance = i;
            fluenceLookupTable[i] = integrateTimeForDistance(distance);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("[THERMAL] Fluence lookup table computed: " + tableSize +
                " entries in " + elapsed + "ms");
    }

    /**
     * 特定距離での時間積分熱フルエンスを計算
     * 数値積分により火球の全持続時間にわたる熱放射を累積
     *
     * @param distance 火球中心からの距離 (m)
     * @return 総熱フルエンス (J/m²)
     */
    private double integrateTimeForDistance(double distance) {
        // 火球内部は無限大のエネルギー
        if (distance < fireball.getMaxRadius()) {
            return Double.POSITIVE_INFINITY;
        }

        double totalFluence = 0.0;
        double dt = 0.01;  // 10msステップ（十分な精度）

        // 火球の可視期間全体を積分
        double endTime = fireball.getSecondPulseEnd();

        for (double t = 0; t <= endTime; t += dt) {
            double radius = fireball.getRadius(t);
            double tempCelsius = fireball.getSurfaceTemperature(t);
            double tempKelvin = tempCelsius + 273.15;

            // Stefan-Boltzmann法則: P = σ·A·T⁴
            double surfaceArea = 4.0 * Math.PI * radius * radius;
            double totalPower = STEFAN_BOLTZMANN * surfaceArea * Math.pow(tempKelvin, 4.0);

            // 逆二乗則による減衰
            // 実効距離 = 観測点距離 - 火球半径 (火球表面からの距離)
            double effectiveDistance = Math.max(distance - radius, 1.0);
            double intensity = totalPower / (4.0 * Math.PI * effectiveDistance * effectiveDistance);

            // 大気吸収 (Beer's法則): I = I₀ × exp(-μ·d)
            double transmission = Math.exp(-ATMOSPHERIC_ABSORPTION * effectiveDistance);

            // フルエンス率 (W/m²)
            double fluenceRate = intensity * transmission;

            // 時間積分 (J/m²)
            totalFluence += fluenceRate * dt;
        }

        return totalFluence;
    }

    /**
     * 特定距離での総熱フルエンスを高速取得
     * ルックアップテーブルからO(1)で取得
     *
     * @param distance 火球中心からの距離 (m)
     * @return 総熱フルエンス (J/m²)
     */
    public double getFluence(double distance) {
        // 火球内部チェック
        if (distance < fireball.getMaxRadius()) {
            return Double.POSITIVE_INFINITY;
        }

        // テーブル範囲外は0
        if (distance >= maxRange) {
            return 0.0;
        }

        // 最近傍インデックス取得
        int index = (int) Math.min(distance, tableSize - 1);
        return fluenceLookupTable[index];
    }

    /**
     * 最大範囲を取得
     *
     * @return 最大範囲 (m)
     */
    public double getMaxRange() {
        return maxRange;
    }

    /**
     * 特定距離での熱フルエンスをデバッグ出力
     *
     * @param distance 距離 (m)
     */
    public void debugFluenceAtDistance(double distance) {
        double fluence = getFluence(distance);
        System.out.printf("[THERMAL DEBUG] Distance: %.1fm, Fluence: %.2e J/m²%n",
                distance, fluence);
    }
}
