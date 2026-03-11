package com.hbm.explosion;

/**
 * 火球物理パラメータ計算クラス (Fireball Physics Calculator)
 *
 * Taylor-Sedov爆風理論とGlasstone & Dolan核兵器効果に基づく
 * 火球の成長、温度変化、熱パルスタイミングを計算
 */
public class FireballPhysics {

    // 爆発威力 (キロトン)
    private final double yieldKilotons;

    // 火球パラメータ
    private final double maxRadius;           // 最大半径 (m)
    private final double growthTime;          // 成長時間 (s)
    private final double firstPulseEnd;       // 第1パルス終了 (s)
    private final double secondPulseStart;    // 第2パルス開始 (s)
    private final double secondPulseEnd;      // 第2パルス終了 (s)
    private final double totalThermalEnergy;  // 総熱エネルギー (J)

    /**
     * コンストラクタ: 全パラメータを事前計算
     *
     * @param yieldKt 爆発威力 (キロトン TNT換算)
     */
    public FireballPhysics(double yieldKt) {
        this.yieldKilotons = yieldKt;

        // 最大半径: R_max = 52 × Y^(1/3) メートル
        this.maxRadius = 52.0 * Math.pow(yieldKt, 1.0 / 3.0);

        // 成長時間: t_growth ≈ 1.0 × Y^0.44 秒
        this.growthTime = 1.0 * Math.pow(yieldKt, 0.44);

        // 熱パルスタイミング (Glasstone & Dolan準拠)
        this.firstPulseEnd = 0.010;  // 第1パルス: 10ms (X線主体)
        this.secondPulseStart = 0.012;  // 最低温度期間: 12ms
        this.secondPulseEnd = growthTime * 10.0;  // 第2パルス終了: 数秒

        // 総熱エネルギー: 爆発エネルギーの35%
        // 1キロトン = 4.184 × 10^12 J
        this.totalThermalEnergy = yieldKt * 4.184e12 * 0.35;
    }

    /**
     * 時刻tでの火球半径を計算
     * Taylor-Sedov成長則: R(t) = R_max × (t/t_max)^0.4
     *
     * @param t 時刻 (秒)
     * @return 火球半径 (メートル)
     */
    public double getRadius(double t) {
        if (t >= growthTime) {
            return maxRadius;
        }

        // Taylor-Sedov成長曲線
        return maxRadius * Math.pow(t / growthTime, 0.4);
    }

    /**
     * 時刻tでの火球表面温度を計算
     * 2パルス熱放射モデル
     *
     * @param t 時刻 (秒)
     * @return 表面温度 (℃)
     */
    public double getSurfaceTemperature(double t) {
        if (t < firstPulseEnd) {
            // 第1パルス: 20,000°C → 8,000°C
            // X線による急激な加熱と初期冷却
            double frac = t / firstPulseEnd;
            return 20000.0 - frac * 12000.0;

        } else if (t < secondPulseStart) {
            // 最低温度期間: 3,000°C
            // ショック波再圧縮前の低温期
            return 3000.0;

        } else if (t < secondPulseEnd * 0.3) {
            // 第2パルス上昇: 3,000°C → 7,700°C
            // ショック波再圧縮による再加熱
            double frac = (t - secondPulseStart) / (secondPulseEnd * 0.3 - secondPulseStart);
            return 3000.0 + frac * 4700.0;

        } else if (t < secondPulseEnd) {
            // 第2パルス下降: 7,700°C → 5,000°C
            // 火球膨張による冷却
            double duration = secondPulseEnd - secondPulseEnd * 0.3;
            double frac = (t - secondPulseEnd * 0.3) / duration;
            return 7700.0 - frac * 2700.0;

        } else {
            // パルス終了後: 5,000°C以下
            return 5000.0;
        }
    }

    /**
     * 時刻tで火球が可視かどうか
     *
     * @param t 時刻 (秒)
     * @return 可視ならtrue
     */
    public boolean isVisible(double t) {
        return t <= secondPulseEnd;
    }

    /**
     * 火球の最大半径を取得
     *
     * @return 最大半径 (メートル)
     */
    public double getMaxRadius() {
        return maxRadius;
    }

    /**
     * 火球の成長時間を取得
     *
     * @return 成長時間 (秒)
     */
    public double getGrowthTime() {
        return growthTime;
    }

    /**
     * 第2パルス終了時刻を取得
     *
     * @return 終了時刻 (秒)
     */
    public double getSecondPulseEnd() {
        return secondPulseEnd;
    }

    /**
     * 総熱エネルギーを取得
     *
     * @return 熱エネルギー (ジュール)
     */
    public double getTotalThermalEnergy() {
        return totalThermalEnergy;
    }

    /**
     * 爆発威力を取得
     *
     * @return 威力 (キロトン)
     */
    public double getYieldKilotons() {
        return yieldKilotons;
    }
}
