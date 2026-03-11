package com.hbm.physics.fusion;

/**
 * Lawson判定基準の実装
 *
 * 核融合の点火条件を判定する重要な物理法則
 * トリプルプロダクト: n × τ_E × T > 閾値
 *
 * - n: 粒子密度 (particles/m³)
 * - τ_E: エネルギー閉じ込め時間 (s)
 * - T: 温度 (keV)
 *
 * D-T反応の場合:
 * - ブレークイーブン (Q=1): n·τ_E ≈ 10²⁰ s/m³ at T ≈ 10 keV
 * - 点火 (self-sustaining): n·τ_E ≈ 5×10²¹ s/m³ at T ≈ 15-20 keV
 */
public class LawsonCriterion {

    /**
     * トリプルプロダクト (n × τ_E × T) を計算
     *
     * @param state プラズマ状態
     * @param confinementTime エネルギー閉じ込め時間 τ_E (s)
     * @return トリプルプロダクト (keV·s/m³)
     */
    public static double calculateTripleProduct(PlasmaState state, double confinementTime) {
        // 平均温度 (keV)
        double tempKeV = state.getAverageTemperatureKeV();

        // n × τ_E × T
        return state.density * confinementTime * tempKeV;
    }

    /**
     * ブレークイーブン条件をチェック (Q ≥ 1)
     * 核融合出力 = 加熱入力
     *
     * @param state プラズマ状態
     * @param confinementTime 閉じ込め時間 (s)
     * @param reactionType 反応タイプ
     * @return ブレークイーブンに達している場合 true
     */
    public static boolean checkBreakeven(PlasmaState state,
                                          double confinementTime,
                                          FusionCrossSections.ReactionType reactionType) {
        double tripleProduct = calculateTripleProduct(state, confinementTime);
        double threshold = getBreakevenTripleProduct(reactionType, state.getAverageTemperatureKeV());

        return tripleProduct >= threshold;
    }

    /**
     * 点火条件をチェック (自己持続的核融合)
     * α粒子加熱のみで損失を補える状態
     *
     * @param state プラズマ状態
     * @param confinementTime 閉じ込め時間 (s)
     * @param reactionType 反応タイプ
     * @return 点火条件を満たす場合 true
     */
    public static boolean checkIgnition(PlasmaState state,
                                         double confinementTime,
                                         FusionCrossSections.ReactionType reactionType) {
        double tripleProduct = calculateTripleProduct(state, confinementTime);
        double threshold = getIgnitionTripleProduct(reactionType, state.getAverageTemperatureKeV());

        return tripleProduct >= threshold;
    }

    /**
     * ブレークイーブンに必要なトリプルプロダクト閾値を取得
     * 温度依存性を考慮
     *
     * @param type 反応タイプ
     * @param tempKeV 温度 (keV)
     * @return 閾値 (keV·s/m³)
     */
    public static double getBreakevenTripleProduct(FusionCrossSections.ReactionType type,
                                                    double tempKeV) {
        switch (type) {
            case D_T:
                // D-T: 最適温度10-15 keVで n·τ_E ≈ 10²⁰ s/m³
                if (tempKeV < 5.0) return 1.0e22;      // 低温では非常に高い閾値
                if (tempKeV < 10.0) return 5.0e20;      // 上昇中
                if (tempKeV <= 20.0) return 1.0e20;     // 最適範囲
                return 2.0e20 * (tempKeV / 20.0);       // 高温では損失増加

            case D_D:
                // D-Dは約10倍困難
                return getBreakevenTripleProduct(FusionCrossSections.ReactionType.D_T, tempKeV) * 10.0;

            case D_HE3:
                // D-He3は高温が必要で約5倍困難
                if (tempKeV < 20.0) return 1.0e22;
                return getBreakevenTripleProduct(FusionCrossSections.ReactionType.D_T, tempKeV) * 5.0;

            case HE3_HE3:
                // He3-He3は非常に困難
                if (tempKeV < 50.0) return 1.0e23;
                return getBreakevenTripleProduct(FusionCrossSections.ReactionType.D_T, tempKeV) * 20.0;

            case EXOTIC:
                // 架空反応は容易（ゲームバランス）
                return getBreakevenTripleProduct(FusionCrossSections.ReactionType.D_T, tempKeV) * 0.1;

            default:
                return 1.0e22;
        }
    }

    /**
     * 点火に必要なトリプルプロダクト閾値を取得
     * ブレークイーブンより約5倍高い
     *
     * @param type 反応タイプ
     * @param tempKeV 温度 (keV)
     * @return 閾値 (keV·s/m³)
     */
    public static double getIgnitionTripleProduct(FusionCrossSections.ReactionType type,
                                                   double tempKeV) {
        // 点火はブレークイーブンより約5倍厳しい
        return getBreakevenTripleProduct(type, tempKeV) * 5.0;
    }

    /**
     * 必要な閉じ込め時間を計算
     *
     * @param state プラズマ状態
     * @param reactionType 反応タイプ
     * @param targetCondition "breakeven" または "ignition"
     * @return 必要な τ_E (s)
     */
    public static double calculateRequiredConfinementTime(PlasmaState state,
                                                          FusionCrossSections.ReactionType reactionType,
                                                          String targetCondition) {
        double tempKeV = state.getAverageTemperatureKeV();

        double targetTripleProduct;
        if (targetCondition.equalsIgnoreCase("ignition")) {
            targetTripleProduct = getIgnitionTripleProduct(reactionType, tempKeV);
        } else {
            targetTripleProduct = getBreakevenTripleProduct(reactionType, tempKeV);
        }

        // τ_E = (target triple product) / (n × T)
        if (state.density <= 0 || tempKeV <= 0) return Double.POSITIVE_INFINITY;

        return targetTripleProduct / (state.density * tempKeV);
    }

    /**
     * 実際の閉じ込め時間を推定
     * 磁場強度とプラズマベータから計算
     *
     * Bohm拡散モデル: τ_E ∝ B² × a²
     * - B: 磁場強度 (T)
     * - a: プラズマ半径 (m)
     *
     * @param state プラズマ状態
     * @param plasmaRadius プラズマ小半径 (m)
     * @return 推定閉じ込め時間 (s)
     */
    public static double estimateConfinementTime(PlasmaState state, double plasmaRadius) {
        if (state.magneticField <= 0 || plasmaRadius <= 0) return 0.1;

        // ゲーム最適化版: 固定閉じ込め時間（安定性優先）
        // 実際のITERでは τ_E ∝ T^(0.5) だが、ゲームでは振動を防ぐため温度依存を排除

        double tempKeV = state.getAverageTemperatureKeV();
        if (tempKeV <= 0) return 0.1;

        // 基本閉じ込め時間: 磁場とプラズマサイズに依存のみ
        // τ_E ≈ C × B² × a² (温度依存なし - 安定性のため)
        double C_base = 0.5;  // 10倍に増加（安定化）
        double tau_base = C_base * state.magneticField * state.magneticField *
                          plasmaRadius * plasmaRadius;

        // 温度による緩やかな改善（H-modeへの遷移を模擬）
        // 10 keV以上で段階的に改善（最大2倍）
        double tempFactor = 1.0;
        if (tempKeV > 10.0) {
            tempFactor = Math.min(2.0, 1.0 + Math.log10(tempKeV / 10.0) * 0.3);
        }

        double tau_E = tau_base * tempFactor;

        // プラズマベータによる補正（高ベータで閉じ込め悪化）
        if (state.plasmaBeta > 0.05) {
            tau_E *= Math.max(0.5, 0.05 / state.plasmaBeta);  // 最低0.5倍まで
        }

        // 最小値を保証（安定性）
        return Math.max(0.1, tau_E);
    }

    /**
     * 閉じ込め性能指数 H を計算
     * H = τ_E (実測) / τ_E (経験則)
     *
     * H > 1: 経験則より良好
     * H = 1: 標準的
     * H < 1: 経験則より悪い
     *
     * @param actualConfinementTime 実際の閉じ込め時間 (s)
     * @param state プラズマ状態
     * @param plasmaRadius プラズマ小半径 (m)
     * @return H因子
     */
    public static double calculateHFactor(double actualConfinementTime,
                                          PlasmaState state,
                                          double plasmaRadius) {
        double empiricalTau = estimateConfinementTime(state, plasmaRadius);
        if (empiricalTau <= 0) return 0.0;

        return actualConfinementTime / empiricalTau;
    }

    /**
     * 点火マージンを計算（現在状態と点火条件の比率）
     *
     * @param state プラズマ状態
     * @param confinementTime 閉じ込め時間 (s)
     * @param reactionType 反応タイプ
     * @return マージン (1.0 = 点火条件達成, > 1.0 = 余裕あり)
     */
    public static double calculateIgnitionMargin(PlasmaState state,
                                                  double confinementTime,
                                                  FusionCrossSections.ReactionType reactionType) {
        double currentTripleProduct = calculateTripleProduct(state, confinementTime);
        double ignitionThreshold = getIgnitionTripleProduct(reactionType, state.getAverageTemperatureKeV());

        if (ignitionThreshold <= 0) return 0.0;

        return currentTripleProduct / ignitionThreshold;
    }

    /**
     * 閉じ込めパラメータが妥当な範囲かチェック
     *
     * @param confinementTime 閉じ込め時間 (s)
     * @param state プラズマ状態
     * @return 妥当な場合 true
     */
    public static boolean isValidConfinementTime(double confinementTime, PlasmaState state) {
        if (confinementTime <= 0) return false;

        // 物理的に実現可能な範囲
        // 最小: 0.001秒（非常に悪い閉じ込め）
        // 最大: 100秒（非現実的に良い閉じ込め）
        if (confinementTime < 0.001 || confinementTime > 100.0) return false;

        // トリプルプロダクトが物理的範囲内
        double tripleProduct = calculateTripleProduct(state, confinementTime);
        if (tripleProduct > 1.0e24) return false;  // 非現実的に高い

        return true;
    }

    /**
     * 損失メカニズムの評価
     *
     * @param state プラズマ状態
     * @param volume プラズマ体積 (m³)
     * @return 主要損失 [制動放射, シンクロトロン, 輸送] (W)
     */
    public static double[] evaluateLossMechanisms(PlasmaState state, double volume) {
        double bremsstrahlung = calculateBremsstrahlungLoss(state, volume);
        double synchrotron = calculateSynchrotronLoss(state, volume);

        // 輸送損失は閉じ込め時間に依存するため推定のみ
        // E_thermal / τ_E
        double thermalEnergy = (3.0/2.0) * state.density * volume *
                               PlasmaState.BOLTZMANN * state.getAverageTemperature();
        double estimatedTau = estimateConfinementTime(state, 1.0);  // 1m radius仮定
        double transport = (estimatedTau > 0) ? thermalEnergy / estimatedTau : 0.0;

        return new double[]{bremsstrahlung, synchrotron, transport};
    }

    /**
     * 制動放射損失を計算（簡易版）
     * P_brems ≈ 5.35e-37 × n² × T^(1/2) × V (W)
     *
     * @param state プラズマ状態
     * @param volume 体積 (m³)
     * @return 制動放射損失 (W)
     */
    private static double calculateBremsstrahlungLoss(PlasmaState state, double volume) {
        double tempKeV = state.getAverageTemperatureKeV();
        if (tempKeV <= 0) return 0.0;

        // 制動放射係数（簡易式）
        double C_brems = 5.35e-37;  // W·m³·keV^(-1/2)

        return C_brems * state.density * state.density * Math.sqrt(tempKeV) * volume;
    }

    /**
     * シンクロトロン放射損失を計算（簡易版）
     * P_sync ≈ 6.2e-17 × n × T² × B² × V (W)
     *
     * @param state プラズマ状態
     * @param volume 体積 (m³)
     * @return シンクロトロン損失 (W)
     */
    private static double calculateSynchrotronLoss(PlasmaState state, double volume) {
        double tempKeV = state.getAverageTemperatureKeV();
        if (tempKeV <= 0 || state.magneticField <= 0) return 0.0;

        // シンクロトロン係数（簡易式）
        double C_sync = 6.2e-17;  // W·m³·keV^(-2)·T^(-2)

        return C_sync * state.density * tempKeV * tempKeV *
               state.magneticField * state.magneticField * volume;
    }
}
