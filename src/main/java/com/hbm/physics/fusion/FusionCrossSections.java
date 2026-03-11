package com.hbm.physics.fusion;

/**
 * 核融合反応の断面積とMaxwell平均反応率 <σv> を計算
 *
 * 主要な反応：
 * - D + D → He-3 + n (3.27 MeV)
 * - D + D → T + p (4.03 MeV)
 * - D + T → He-4 + n (17.6 MeV)
 * - D + He-3 → He-4 + p (18.3 MeV)
 * - He-3 + He-3 → He-4 + 2p (12.86 MeV)
 */
public class FusionCrossSections {

    // 反応タイプ列挙型
    public enum ReactionType {
        D_D,      // Deuterium-Deuterium
        D_T,      // Deuterium-Tritium
        D_HE3,    // Deuterium-Helium-3
        HE3_HE3,  // Helium-3 - Helium-3
        EXOTIC    // 架空反応（DHC, BALEFIRE等）
    }

    /**
     * Maxwell平均反応率 <σv> を計算 (m³/s)
     *
     * Bosch-Hale parametrizationを使用（D-T反応）
     * 他の反応は文献値の近似式
     *
     * @param type 反応タイプ
     * @param temperatureKeV 温度 (keV)
     * @return <σv> (m³/s)
     */
    public static double getSigmaV(ReactionType type, double temperatureKeV) {
        switch (type) {
            case D_T:
                return getSigmaV_DT(temperatureKeV);
            case D_D:
                return getSigmaV_DD(temperatureKeV);
            case D_HE3:
                return getSigmaV_DHE3(temperatureKeV);
            case HE3_HE3:
                return getSigmaV_HE3HE3(temperatureKeV);
            case EXOTIC:
                // 架空反応は通常の10倍の反応率（ゲームバランス）
                return getSigmaV_DT(temperatureKeV) * 10.0;
            default:
                return 0.0;
        }
    }

    /**
     * 温度(K)から<σv>を計算（オーバーロード）
     */
    public static double getSigmaV(ReactionType type, PlasmaState state) {
        double avgTempKeV = state.getAverageTemperatureKeV();
        return getSigmaV(type, avgTempKeV);
    }

    /**
     * D-T反応の<σv> (Bosch-Hale parameterization)
     * 最も重要な核融合反応
     *
     * 参考: Bosch & Hale, Nucl. Fusion 32 (1992) 611
     */
    private static double getSigmaV_DT(double T_keV) {
        if (T_keV < 0.2) return 0.0;  // 低温では無視

        // Bosch-Hale係数（D-T反応用）
        double C1 = 1.17302e-9;
        double C2 = 1.51361e-2;
        double C3 = 7.51886e-2;
        double C4 = 4.60643e-3;
        double C5 = 1.35000e-2;
        double C6 = -1.06750e-4;
        double C7 = 1.36600e-5;

        double xi = Math.pow(((C2 / T_keV) * ((C4 * T_keV * T_keV) + 1.0)) /
                     ((C3 * T_keV) + 1.0), 1.0/3.0);

        double sigmaV = C1 * Math.exp(-3.0 * xi) / (T_keV * T_keV * T_keV);

        // 高温での修正
        sigmaV *= (1.0 + C5 * T_keV + C6 * T_keV * T_keV + C7 * T_keV * T_keV * T_keV);

        return sigmaV; // m³/s
    }

    /**
     * D-D反応の<σv>
     * D + D → He-3 + n (50%)
     * D + D → T + p (50%)
     */
    private static double getSigmaV_DD(double T_keV) {
        if (T_keV < 1.0) return 0.0;

        // 近似式（文献値ベース）
        // D-D反応はD-Tより約100倍弱い
        double B_g = 31.4;  // Gamowエネルギー (keV)
        double eta = Math.sqrt(B_g / T_keV);

        // Gamowピーク近似
        double sigmaV = 2.33e-14 * Math.pow(T_keV, -2.0/3.0) *
                        Math.exp(-eta);

        return sigmaV; // m³/s
    }

    /**
     * D-He3反応の<σv>
     * D + He-3 → He-4 + p (18.3 MeV)
     * アニュートロニック反応（中性子なし）
     */
    private static double getSigmaV_DHE3(double T_keV) {
        if (T_keV < 5.0) return 0.0;

        // 近似式（高温で有効）
        double B_g = 68.7;  // Gamowエネルギー (keV)
        double eta = Math.sqrt(B_g / T_keV);

        double sigmaV = 5.51e-13 * Math.pow(T_keV, -2.0/3.0) *
                        Math.exp(-eta);

        // ピーク温度での最適化
        if (T_keV > 50.0 && T_keV < 100.0) {
            sigmaV *= 1.5; // ピーク付近でブースト
        }

        return sigmaV; // m³/s
    }

    /**
     * He3-He3反応の<σv>
     * He-3 + He-3 → He-4 + 2p (12.86 MeV)
     * 完全アニュートロニック
     */
    private static double getSigmaV_HE3HE3(double T_keV) {
        if (T_keV < 10.0) return 0.0;

        // 非常に高温が必要
        double B_g = 183.6;  // Gamowエネルギー (keV)
        double eta = Math.sqrt(B_g / T_keV);

        double sigmaV = 5.59e-12 * Math.pow(T_keV, -2.0/3.0) *
                        Math.exp(-eta);

        return sigmaV; // m³/s
    }

    /**
     * 反応タイプから点火温度を取得 (K)
     *
     * 現実の核融合物理に基づく点火温度：
     * - D-T: 最も容易（最低温度）
     * - D-D: D-Tの約10倍の温度が必要
     * - D-He3: D-Tの約20-25倍
     * - He3-He3: D-Tの約40倍
     *
     * これらの値は<σv>が実用レベルに達する最低温度を示す
     */
    public static double getIgnitionTemperature(ReactionType type) {
        switch (type) {
            case D_T:
                return 4.0e7;    // 40 MK (4×10^7 K) - 最も容易な核融合反応
            case D_D:
                return 4.5e8;    // 450 MK (4.5×10^8 K) - D-Tの約10倍の温度が必要
            case D_HE3:
                return 1.0e9;    // 1000 MK (1×10^9 K) - アニュートロニック、高温要求
            case HE3_HE3:
                return 1.6e9;    // 1600 MK (1.6×10^9 K) - 完全アニュートロニック、最高温
            case EXOTIC:
                return 2.0e8;    // 200 MK (2×10^8 K) - 架空反応（ゲームバランス考慮）
            default:
                return 1.0e8;
        }
    }

    /**
     * 反応タイプから最適温度を取得 (K)
     * <σv>が最大となる温度
     */
    public static double getOptimalTemperature(ReactionType type) {
        switch (type) {
            case D_T:
                return 7.4e8;    // 740 million K (~64 keV)
            case D_D:
                return 1.7e8;    // 170 million K (~15 keV)
            case D_HE3:
                return 5.0e8;    // 500 million K
            case HE3_HE3:
                return 1.0e9;    // 1 billion K
            case EXOTIC:
                return 5.0e8;    // 500 million K
            default:
                return 1.0e9;
        }
    }

    /**
     * 反応タイプごとの温度スケーリング係数
     *
     * この係数は各反応タイプが到達可能な最大温度を決定します。
     * 実際の最大温度 = baseTemperature × scalingFactor × √(fuelRate)
     *
     * 物理的背景：
     * - D-T: 標準的な核融合反応、基準値
     * - D-D: D-Tより高温で効率が良い（Gamowピークが高温側）
     * - D-He3: アニュートロニック反応、さらに高温が必要
     * - He3-He3: 完全アニュートロニック、超高温要求
     * - EXOTIC: 反物質反応、通常の核融合とは桁違いのエネルギー
     *
     * @param type 反応タイプ
     * @return 温度スケーリング係数（無次元）
     */
    public static double getTemperatureScalingFactor(ReactionType type) {
        switch (type) {
            case D_T:
                return 1.0;      // 基準値（200 MK × 1.0 × √10 = 632 MK at fuelRate=10）
            case D_D:
                return 1.5;      // D-Tより50%高い上限（300 MK × √10 = 948 MK）
            case D_HE3:
                return 2.5;      // D-Tの2.5倍（500 MK × √10 = 1580 MK）
            case HE3_HE3:
                return 4.0;      // D-Tの4倍（800 MK × √10 = 2530 MK）
            case EXOTIC:
                return 15.0;     // 反物質反応：桁違い（3000 MK × √10 = 9487 MK ≈ 10 GK）
            default:
                return 1.0;
        }
    }

    /**
     * 反応タイプごとのパワー出力スケーリング係数
     *
     * 核融合パワーに適用される追加の倍率。
     * EXOTIC反応（反物質）は通常の核融合とは桁違いのエネルギーを生成。
     *
     * @param type 反応タイプ
     * @return パワースケーリング係数（無次元）
     */
    public static double getPowerScalingFactor(ReactionType type) {
        switch (type) {
            case D_T:
            case D_D:
            case D_HE3:
            case HE3_HE3:
                return 1.0;      // 通常の核融合反応
            case EXOTIC:
                return 10.0;     // 反物質反応：パワー出力10倍
            default:
                return 1.0;
        }
    }

    /**
     * 反応あたりのエネルギー出力 (J)
     */
    public static double getEnergyPerReaction(ReactionType type) {
        // 1 MeV = 1.602176634e-13 J
        double MeV_to_J = 1.602176634e-13;

        switch (type) {
            case D_D:
                return 3.65 * MeV_to_J;  // 平均 (3.27 + 4.03)/2
            case D_T:
                return 17.6 * MeV_to_J;
            case D_HE3:
                return 18.3 * MeV_to_J;
            case HE3_HE3:
                return 12.86 * MeV_to_J;
            case EXOTIC:
                return 50.0 * MeV_to_J;  // 架空反応は高出力
            default:
                return 0.0;
        }
    }

    /**
     * 中性子が運ぶエネルギーの割合（0.0-1.0）
     */
    public static double getNeutronFraction(ReactionType type) {
        switch (type) {
            case D_T:
                return 0.80;  // 14.1 MeV / 17.6 MeV
            case D_D:
                return 0.50;  // 50%の反応が中性子生成
            case D_HE3:
                return 0.0;   // アニュートロニック
            case HE3_HE3:
                return 0.0;   // 完全アニュートロニック
            case EXOTIC:
                return 0.90;  // 架空反応は高中性子出力
            default:
                return 0.0;
        }
    }

    /**
     * 反応タイプをレシピ名から取得
     *
     * レシピ名の例:
     * - "fus.dt" → D_T (Deuterium-Tritium)
     * - "fus.dd" → D_D (Deuterium-Deuterium)
     * - "fus.h3" → HE3_HE3 (Helium-3 fusion)
     * - "fus.dhe3" → D_HE3 (Deuterium-Helium-3)
     * - "fus.dhc", "fus.bf", "fus.stellar" → EXOTIC (架空反応)
     *
     * @param name レシピ名（例: "fus.dt"）
     * @return 対応する反応タイプ
     */
    public static ReactionType getTypeFromName(String name) {
        if (name == null) return ReactionType.EXOTIC;

        String nameLower = name.toLowerCase();

        // D-T反応（最も一般的）
        if (nameLower.contains("dt") || nameLower.contains("d-t")) {
            return ReactionType.D_T;
        }

        // D-D反応
        if (nameLower.contains("dd") || nameLower.contains("d-d")) {
            return ReactionType.D_D;
        }

        // D-He3反応（アニュートロニック）
        if (nameLower.contains("dhe3") || nameLower.contains("d-he3") || nameLower.contains("dh3")) {
            return ReactionType.D_HE3;
        }

        // He3-He3反応（完全アニュートロニック）
        // "fus.h3"はHe3のみを使用する反応
        if ((nameLower.contains("h3") || nameLower.contains("he3")) &&
            !nameLower.contains("dhe3") && !nameLower.contains("th4")) {
            return ReactionType.HE3_HE3;
        }

        // 上記に該当しない場合は架空反応（EXOTIC）
        // fus.dhc, fus.bf, fus.stellar, fus.tcl, fus.cl, fus.do など
        return ReactionType.EXOTIC;
    }
}
