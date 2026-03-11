package com.hbm.physics.fusion;

/**
 * 核融合反応率と出力パワーを計算
 *
 * 核融合パワー: P = n² × <σv> × E_fusion × V
 * - n: 粒子密度 (particles/m³)
 * - <σv>: Maxwell平均反応率 (m³/s)
 * - E_fusion: 反応あたりのエネルギー (J)
 * - V: プラズマ体積 (m³)
 */
public class FusionReactionRate {

    /**
     * 核融合パワーを計算 (W)
     *
     * @param state プラズマ状態
     * @param reactionType 反応タイプ
     * @param volumeM3 プラズマ体積 (m³)
     * @return 核融合パワー (W)
     */
    public static double calculateFusionPower(PlasmaState state,
                                               FusionCrossSections.ReactionType reactionType,
                                               double volumeM3) {
        // 温度が点火温度未満なら0
        double ignitionTemp = FusionCrossSections.getIgnitionTemperature(reactionType);
        if (state.temperatureIon < ignitionTemp) {
            return 0.0;
        }

        // <σv>を取得 (m³/s)
        double sigmaV = FusionCrossSections.getSigmaV(reactionType, state);

        // 反応あたりのエネルギー (J)
        double energyPerReaction = FusionCrossSections.getEnergyPerReaction(reactionType);

        // 反応率: R = n² × <σv> (reactions/m³/s)
        // D-D, D-T等の2粒子反応の場合、両方の密度が必要だが簡略化のため同じ密度を使用
        double reactionRate = state.density * state.density * sigmaV;

        // 核融合パワー: P = R × E × V (W)
        double fusionPower = reactionRate * energyPerReaction * volumeM3;

        return fusionPower;
    }

    /**
     * 中性子パワーと荷電粒子パワーに分離
     *
     * @param totalPower 総核融合パワー (W)
     * @param reactionType 反応タイプ
     * @return [中性子パワー, 荷電粒子パワー] (W)
     */
    public static double[] splitNeutronAndChargedPower(double totalPower,
                                                        FusionCrossSections.ReactionType reactionType) {
        double neutronFraction = FusionCrossSections.getNeutronFraction(reactionType);

        double neutronPower = totalPower * neutronFraction;
        double chargedPower = totalPower * (1.0 - neutronFraction);

        return new double[]{neutronPower, chargedPower};
    }

    /**
     * 中性子フラックスを計算 (n/s)
     *
     * D-T反応の場合: 1反応 = 1中性子
     *
     * @param fusionPower 核融合パワー (W)
     * @param reactionType 反応タイプ
     * @return 中性子フラックス (neutrons/s)
     */
    public static double calculateNeutronFlux(double fusionPower,
                                               FusionCrossSections.ReactionType reactionType) {
        double neutronFraction = FusionCrossSections.getNeutronFraction(reactionType);
        if (neutronFraction == 0.0) return 0.0;  // アニュートロニック反応

        double energyPerReaction = FusionCrossSections.getEnergyPerReaction(reactionType);

        // 総反応率 (reactions/s)
        double reactionRatePerSec = fusionPower / energyPerReaction;

        // 中性子を生成する反応の数
        // D-T: 100%の反応が1中性子
        // D-D: 50%の反応が1中性子
        double neutronFlux = reactionRatePerSec * neutronFraction;

        return neutronFlux;
    }

    /**
     * α粒子（荷電粒子）による自己加熱パワーを計算 (W)
     *
     * D-T反応: He-4 (α粒子) が3.5 MeVを持ち、プラズマ内で減速→加熱
     * 加熱効率は約80-90%（一部は放射損失）
     *
     * @param fusionPower 総核融合パワー (W)
     * @param reactionType 反応タイプ
     * @param heatingEfficiency 加熱効率 (0.0-1.0)
     * @return 自己加熱パワー (W)
     */
    public static double calculateAlphaHeating(double fusionPower,
                                                FusionCrossSections.ReactionType reactionType,
                                                double heatingEfficiency) {
        // 荷電粒子パワー
        double[] powers = splitNeutronAndChargedPower(fusionPower, reactionType);
        double chargedPower = powers[1];

        // 加熱効率を適用
        return chargedPower * heatingEfficiency;
    }

    /**
     * Q値（核融合利得）を計算
     *
     * Q = P_fusion / P_heating
     * Q < 1: エネルギー損失
     * Q = 1: ブレークイーブン
     * Q > 1: エネルギー利得
     * Q > 10: 商業運転可能
     *
     * @param fusionPower 核融合パワー (W)
     * @param heatingPower 外部加熱パワー (W)
     * @return Q値
     */
    public static double calculateQValue(double fusionPower, double heatingPower) {
        if (heatingPower <= 0.0) return 0.0;
        return fusionPower / heatingPower;
    }

    /**
     * 燃料消費率を計算 (particles/s または kg/s)
     *
     * @param fusionPower 核融合パワー (W)
     * @param reactionType 反応タイプ
     * @return 燃料消費率 (particles/s)
     */
    public static double calculateFuelConsumptionRate(double fusionPower,
                                                       FusionCrossSections.ReactionType reactionType) {
        double energyPerReaction = FusionCrossSections.getEnergyPerReaction(reactionType);

        // 反応率 (reactions/s)
        double reactionRate = fusionPower / energyPerReaction;

        // D-T反応の場合: 1反応 = 1 D + 1 T = 2粒子消費
        return reactionRate * 2.0;
    }

    /**
     * トリチウム増殖率（TBR: Tritium Breeding Ratio）を考慮した
     * 実効燃料消費率を計算
     *
     * TBR > 1.0 の場合、トリチウムが自給自足可能
     *
     * @param fuelConsumptionRate 基本燃料消費率 (particles/s)
     * @param neutronFlux 中性子フラックス (n/s)
     * @param tritiumBreedingRatio トリチウム増殖率（通常1.05-1.2）
     * @return 実効消費率（増殖を差し引いた値）
     */
    public static double calculateEffectiveFuelConsumption(double fuelConsumptionRate,
                                                            double neutronFlux,
                                                            double tritiumBreedingRatio) {
        // 増殖されるトリチウム量 (particles/s)
        double tritiumProduced = neutronFlux * tritiumBreedingRatio;

        // 実効消費 = 消費 - 生成
        // ただしDは増殖されないので、Tのみ補正
        double effectiveConsumption = fuelConsumptionRate - tritiumProduced;

        return Math.max(0.0, effectiveConsumption);
    }

    /**
     * 反応率効率を計算（最適温度に対する現在温度の効率）
     *
     * @param state プラズマ状態
     * @param reactionType 反応タイプ
     * @return 効率 (0.0-1.0)
     */
    public static double calculateReactionEfficiency(PlasmaState state,
                                                      FusionCrossSections.ReactionType reactionType) {
        double currentSigmaV = FusionCrossSections.getSigmaV(reactionType, state);

        // 最適温度での<σv>
        double optimalTemp = FusionCrossSections.getOptimalTemperature(reactionType);
        PlasmaState optimalState = new PlasmaState(optimalTemp, optimalTemp, state.density, state.magneticField);
        double maxSigmaV = FusionCrossSections.getSigmaV(reactionType, optimalState);

        if (maxSigmaV <= 0.0) return 0.0;

        return Math.min(1.0, currentSigmaV / maxSigmaV);
    }
}
