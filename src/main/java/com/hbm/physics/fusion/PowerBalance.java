package com.hbm.physics.fusion;

/**
 * 核融合反応器のエネルギー収支計算
 *
 * エネルギー保存則:
 * dE/dt = P_heating + P_alpha - P_losses
 *
 * - P_heating: 外部加熱（RF、NBI等）
 * - P_alpha: α粒子による自己加熱
 * - P_losses: 放射損失 + 輸送損失
 *
 * 温度発展方程式:
 * dT/dt = (2/3) × (dE/dt) / (n × V × k_B)
 */
public class PowerBalance {

    /**
     * 正味パワーを計算（エネルギー収支）
     *
     * @param heatingPower 外部加熱パワー (W)
     * @param alphaPower α粒子加熱パワー (W)
     * @param lossPower 損失パワー (W)
     * @return 正味パワー (W) - 正なら温度上昇、負なら温度低下
     */
    public static double calculateNetPower(double heatingPower,
                                           double alphaPower,
                                           double lossPower) {
        return heatingPower + alphaPower - lossPower;
    }

    /**
     * 制動放射損失を計算
     *
     * P_brems = C_brems × n² × T^(1/2) × V × Z_eff
     *
     * @param state プラズマ状態
     * @param volume プラズマ体積 (m³)
     * @param Z_eff 実効原子番号（不純物レベル、通常1.5-3.0）
     * @return 制動放射損失 (W)
     */
    public static double calculateBremsstrahlungLoss(PlasmaState state,
                                                      double volume,
                                                      double Z_eff) {
        double tempKeV = state.getAverageTemperatureKeV();
        if (tempKeV <= 0) return 0.0;

        // 制動放射係数: C_brems ≈ 5.35e-37 W·m³·keV^(-1/2)
        double C_brems = 5.35e-37;

        return C_brems * state.density * state.density * Math.sqrt(tempKeV) * volume * Z_eff;
    }

    /**
     * シンクロトロン放射損失を計算
     *
     * P_sync = C_sync × n × T² × B² × V
     *
     * @param state プラズマ状態
     * @param volume プラズマ体積 (m³)
     * @return シンクロトロン損失 (W)
     */
    public static double calculateSynchrotronLoss(PlasmaState state, double volume) {
        double tempKeV = state.getAverageTemperatureKeV();
        if (tempKeV <= 0 || state.magneticField <= 0) return 0.0;

        // シンクロトロン係数: C_sync ≈ 6.2e-17 W·m³·keV^(-2)·T^(-2)
        double C_sync = 6.2e-17;

        return C_sync * state.density * tempKeV * tempKeV *
               state.magneticField * state.magneticField * volume;
    }

    /**
     * 輸送損失を計算（エネルギー閉じ込め時間による）
     *
     * P_transport = E_thermal / τ_E
     *
     * @param state プラズマ状態
     * @param volume プラズマ体積 (m³)
     * @param confinementTime エネルギー閉じ込め時間 (s)
     * @return 輸送損失 (W)
     */
    public static double calculateTransportLoss(PlasmaState state,
                                                 double volume,
                                                 double confinementTime) {
        if (confinementTime <= 0) return Double.POSITIVE_INFINITY;

        // 熱エネルギー: E = (3/2) × n × V × k_B × (T_i + T_e)
        double thermalEnergy = (3.0/2.0) * state.density * volume *
                               PlasmaState.BOLTZMANN *
                               (state.temperatureIon + state.temperatureElectron);

        return thermalEnergy / confinementTime;
    }

    /**
     * 全損失パワーを計算
     *
     * @param state プラズマ状態
     * @param volume プラズマ体積 (m³)
     * @param confinementTime 閉じ込め時間 (s)
     * @param Z_eff 実効原子番号
     * @return 総損失パワー (W)
     */
    public static double calculateTotalLosses(PlasmaState state,
                                              double volume,
                                              double confinementTime,
                                              double Z_eff) {
        double brems = calculateBremsstrahlungLoss(state, volume, Z_eff);
        double sync = calculateSynchrotronLoss(state, volume);
        double transport = calculateTransportLoss(state, volume, confinementTime);

        return brems + sync + transport;
    }

    /**
     * 損失パワーの内訳を取得
     *
     * @param state プラズマ状態
     * @param volume プラズマ体積 (m³)
     * @param confinementTime 閉じ込め時間 (s)
     * @param Z_eff 実効原子番号
     * @return [制動放射, シンクロトロン, 輸送] (W)
     */
    public static double[] getLossBreakdown(PlasmaState state,
                                            double volume,
                                            double confinementTime,
                                            double Z_eff) {
        double brems = calculateBremsstrahlungLoss(state, volume, Z_eff);
        double sync = calculateSynchrotronLoss(state, volume);
        double transport = calculateTransportLoss(state, volume, confinementTime);

        return new double[]{brems, sync, transport};
    }

    /**
     * 温度変化率を計算 (K/s)
     *
     * dT/dt = (2/3) × P_net / (n × V × k_B)
     *
     * @param netPower 正味パワー (W)
     * @param state プラズマ状態
     * @param volume プラズマ体積 (m³)
     * @return 温度変化率 (K/s)
     */
    public static double calculateTemperatureChangeRate(double netPower,
                                                         PlasmaState state,
                                                         double volume) {
        if (state.density <= 0 || volume <= 0) return 0.0;

        // dT/dt = (2/3) × P_net / (n × V × k_B)
        double particleCount = state.density * volume;
        double dTdt = (2.0 / 3.0) * netPower / (particleCount * PlasmaState.BOLTZMANN);

        return dTdt;
    }

    /**
     * RK4法で温度を時間発展させる
     *
     * @param state 現在のプラズマ状態（このメソッドで更新される）
     * @param volume プラズマ体積 (m³)
     * @param confinementTime 閉じ込め時間 (s)
     * @param externalHeating 外部加熱パワー (W)
     * @param fusionPower 核融合パワー (W)
     * @param alphaHeatingFraction α加熱の割合（通常0.2-0.3）
     * @param Z_eff 実効原子番号
     * @param deltaTime 時間刻み (s)
     * @param reactionType 反応タイプ
     */
    public static void evolveTemperatureRK4(PlasmaState state,
                                            double volume,
                                            double confinementTime,
                                            double externalHeating,
                                            double fusionPower,
                                            double alphaHeatingFraction,
                                            double Z_eff,
                                            double deltaTime,
                                            FusionCrossSections.ReactionType reactionType) {
        // α加熱パワー
        double alphaPower = FusionReactionRate.calculateAlphaHeating(
            fusionPower, reactionType, alphaHeatingFraction
        );

        // **ゲーム最適化版: エネルギーベースの前進オイラー法**
        // RK4は剛性方程式で振動するため、単純で安定な方法を使用

        double avgTemp = (state.temperatureIon + state.temperatureElectron) / 2.0;
        System.out.println("[PowerBalance] Euler inputs: externalHeating=" + externalHeating + " W, alphaPower=" + alphaPower + " W, avgTemp=" + avgTemp + " K");

        // 適応的損失計算（温度依存的にスムーズにスケーリング）
        double totalLosses = calculateAdaptiveLosses(state, volume, confinementTime, Z_eff, avgTemp);

        // 正味パワー
        double totalHeating = externalHeating + alphaPower;
        double netPower = totalHeating - totalLosses;

        System.out.println("[PowerBalance] Losses=" + totalLosses + " W, NetPower=" + netPower + " W");

        // エネルギー変化: ΔE = P_net × Δt
        double deltaEnergy = netPower * deltaTime;

        // 現在の熱エネルギー: E = (3/2) × n × V × k_B × (T_i + T_e)
        double currentEnergy = (3.0/2.0) * state.density * volume * PlasmaState.BOLTZMANN *
                               (state.temperatureIon + state.temperatureElectron);

        // 新しいエネルギー
        double newEnergy = Math.max(0, currentEnergy + deltaEnergy);

        // エネルギーから温度を計算: T = E / ((3/2) × n × V × k_B)
        double totalTemp = newEnergy / ((3.0/2.0) * state.density * volume * PlasmaState.BOLTZMANN);

        // 元の温度
        double oldAvgTemp = (state.temperatureIon + state.temperatureElectron) / 2.0;
        double newAvgTemp = totalTemp / 2.0;

        // 1tickあたりの温度変化を制限（安定性・リアリズム）
        // 最大上昇: 15 MK/tick (300 MK/s) - 点火まで約3 tick
        // 最大降下: 15 MK/tick (300 MK/s) - 急激な冷却を防止
        double maxTempChangePerTick = 1.5e7;
        double tempChange = newAvgTemp - oldAvgTemp;

        if (tempChange > maxTempChangePerTick) {
            System.out.println("[PowerBalance] Temperature RISE limited: " + tempChange + " K -> " + maxTempChangePerTick + " K");
            newAvgTemp = oldAvgTemp + maxTempChangePerTick;
            totalTemp = newAvgTemp * 2.0;
        } else if (tempChange < -maxTempChangePerTick) {
            // 温度降下も制限（急激な冷却を防止）
            System.out.println("[PowerBalance] Temperature DROP limited: " + tempChange + " K -> -" + maxTempChangePerTick + " K");
            newAvgTemp = oldAvgTemp - maxTempChangePerTick;
            totalTemp = newAvgTemp * 2.0;
        }

        // イオンと電子で均等に分配
        state.temperatureIon = totalTemp / 2.0;
        state.temperatureElectron = totalTemp / 2.0;

        System.out.println("[PowerBalance] DeltaE=" + deltaEnergy + " J, OldTemp=" + oldAvgTemp + " K, NewTemp=" + newAvgTemp + " K");

        // 温度の物理的制限（最小値のみ、最大値はTileEntityで燃料レートに応じて制限）
        state.temperatureIon = Math.max(300.0, state.temperatureIon);
        state.temperatureElectron = Math.max(300.0, state.temperatureElectron);

        System.out.println("[PowerBalance] AFTER clamp: Ti=" + state.temperatureIon + " K, Te=" + state.temperatureElectron + " K");

        // 圧力とベータを更新
        state.pressure = state.calculatePressure();
        state.plasmaBeta = state.calculateBeta();
    }

    /**
     * ゲーム最適化版: 適応的損失計算
     * 温度に応じてスムーズに損失項を導入
     */
    private static double calculateAdaptiveLosses(PlasmaState state,
                                                   double volume,
                                                   double confinementTime,
                                                   double Z_eff,
                                                   double avgTemp) {
        // 輸送損失（常に有効）
        double transportLoss = calculateTransportLoss(state, volume, confinementTime);

        // 制動放射（1 MK以上で段階的に導入）
        double bremsstrahlungLoss = 0;
        if (avgTemp > 1.0e6) {
            double bremsFactor = Math.min(1.0, (avgTemp - 1.0e6) / 3.9e7); // 1-40 MKで0→1
            bremsstrahlungLoss = calculateBremsstrahlungLoss(state, volume, Z_eff) * bremsFactor;
        }

        // シンクロトロン損失（点火温度40 MK以上で段階的に導入）
        // 点火前は損失を最小限に抑える
        // 高温でのサチュレーション（飽和）を追加して暴走を防止
        double synchrotronLoss = 0;
        if (avgTemp > 4.0e7) {
            double syncFactor = Math.min(1.0, (avgTemp - 4.0e7) / 1.6e8); // 40-200 MKで0→1

            // 高温でのサチュレーション: 300 MK以上では損失増加が鈍化
            // これにより、温度が高くなりすぎても損失が爆発的に増えない
            double tempSaturationFactor = 1.0;
            if (avgTemp > 3.0e8) {
                // 300 MK以上: log スケーリングで増加を抑制
                double excessTemp = avgTemp - 3.0e8;
                tempSaturationFactor = 1.0 + Math.log(1.0 + excessTemp / 3.0e8) / 2.0;
            }

            double baseLoss = calculateSynchrotronLoss(state, volume);
            synchrotronLoss = baseLoss * syncFactor * 0.1 / tempSaturationFactor; // 係数を1/10に、高温で除算
        }

        double totalLoss = transportLoss + bremsstrahlungLoss + synchrotronLoss;

        System.out.println("[PowerBalance] Loss breakdown: transport=" + transportLoss +
                          " W, brems=" + bremsstrahlungLoss + " W (" + (avgTemp > 1.0e6 ? Math.min(1.0, (avgTemp - 1.0e6) / 9.0e6) * 100 : 0) + "%), " +
                          "sync=" + synchrotronLoss + " W (" + (avgTemp > 1.0e7 ? Math.min(1.0, (avgTemp - 1.0e7) / 9.0e7) * 100 : 0) + "%)");

        return totalLoss;
    }

    /**
     * 簡易オイラー法で温度を更新（高速だが精度低）
     *
     * @param state プラズマ状態
     * @param volume 体積 (m³)
     * @param confinementTime 閉じ込め時間 (s)
     * @param externalHeating 外部加熱 (W)
     * @param fusionPower 核融合パワー (W)
     * @param alphaHeatingFraction α加熱の割合
     * @param Z_eff 実効原子番号
     * @param deltaTime 時間刻み (s)
     * @param reactionType 反応タイプ
     */
    public static void evolveTemperatureEuler(PlasmaState state,
                                              double volume,
                                              double confinementTime,
                                              double externalHeating,
                                              double fusionPower,
                                              double alphaHeatingFraction,
                                              double Z_eff,
                                              double deltaTime,
                                              FusionCrossSections.ReactionType reactionType) {
        // α加熱パワー
        double alphaPower = FusionReactionRate.calculateAlphaHeating(
            fusionPower, reactionType, alphaHeatingFraction
        );

        // 損失計算
        double losses = calculateTotalLosses(state, volume, confinementTime, Z_eff);
        double netPower = calculateNetPower(externalHeating, alphaPower, losses);

        // 温度変化率
        double dTdt = calculateTemperatureChangeRate(netPower, state, volume);

        // 温度更新
        state.temperatureIon += dTdt * deltaTime / 2.0;
        state.temperatureElectron += dTdt * deltaTime / 2.0;

        // 温度の物理的制限
        state.temperatureIon = Math.max(300.0, Math.min(1.0e8, state.temperatureIon));
        state.temperatureElectron = Math.max(300.0, Math.min(1.0e8, state.temperatureElectron));

        // 圧力とベータを更新
        state.pressure = state.calculatePressure();
        state.plasmaBeta = state.calculateBeta();
    }

    /**
     * パワー密度を計算 (W/m³)
     *
     * @param power パワー (W)
     * @param volume 体積 (m³)
     * @return パワー密度 (W/m³)
     */
    public static double calculatePowerDensity(double power, double volume) {
        if (volume <= 0) return 0.0;
        return power / volume;
    }

    /**
     * 壁負荷を計算 (MW/m²)
     * 中性子とプラズマ熱負荷の合計
     *
     * @param fusionPower 核融合パワー (W)
     * @param reactionType 反応タイプ
     * @param wallArea 第一壁面積 (m²)
     * @return 壁負荷 (MW/m²)
     */
    public static double calculateWallLoading(double fusionPower,
                                              FusionCrossSections.ReactionType reactionType,
                                              double wallArea) {
        if (wallArea <= 0) return 0.0;

        // 中性子パワーと荷電粒子パワーに分離
        double[] powers = FusionReactionRate.splitNeutronAndChargedPower(fusionPower, reactionType);
        double neutronPower = powers[0];
        double chargedPower = powers[1];

        // 中性子は壁に到達（100%）
        // 荷電粒子は一部のみ壁に到達（約30%、残りはダイバータ）
        double wallPower = neutronPower + chargedPower * 0.3;

        // MW/m²に変換
        return (wallPower / wallArea) / 1.0e6;
    }

    /**
     * 冷却要求を計算
     * 壁負荷とダイバータ負荷を含む
     *
     * @param fusionPower 核融合パワー (W)
     * @param reactionType 反応タイプ
     * @param transportLoss 輸送損失 (W)
     * @return 必要冷却パワー (W)
     */
    public static double calculateCoolingRequirement(double fusionPower,
                                                     FusionCrossSections.ReactionType reactionType,
                                                     double transportLoss) {
        // 中性子パワーは壁で熱に変換
        double[] powers = FusionReactionRate.splitNeutronAndChargedPower(fusionPower, reactionType);
        double neutronPower = powers[0];

        // 荷電粒子パワーの一部（約70%）はダイバータ
        double chargedPower = powers[1];
        double divertorPower = chargedPower * 0.7;

        // 輸送損失も冷却が必要
        return neutronPower + divertorPower + transportLoss;
    }

    /**
     * プラズマ圧力バランスをチェック
     * β限界を超えていないか確認
     *
     * @param state プラズマ状態
     * @param betaLimit β限界（通常0.05-0.10、5-10%）
     * @return 安定な場合 true
     */
    public static boolean checkPressureBalance(PlasmaState state, double betaLimit) {
        return state.plasmaBeta <= betaLimit;
    }

    /**
     * 点火パワー閾値を推定
     * 点火条件に到達するために必要な外部加熱パワー
     *
     * @param state プラズマ状態
     * @param volume 体積 (m³)
     * @param confinementTime 閉じ込め時間 (s)
     * @param Z_eff 実効原子番号
     * @return 必要な外部加熱パワー (W)
     */
    public static double estimateIgnitionPowerThreshold(PlasmaState state,
                                                        double volume,
                                                        double confinementTime,
                                                        double Z_eff) {
        // 点火温度での損失を計算
        double ignitionTemp = FusionCrossSections.getIgnitionTemperature(
            FusionCrossSections.ReactionType.D_T
        );

        PlasmaState ignitionState = state.copy();
        ignitionState.temperatureIon = ignitionTemp;
        ignitionState.temperatureElectron = ignitionTemp;

        double losses = calculateTotalLosses(ignitionState, volume, confinementTime, Z_eff);

        // α加熱を差し引いた必要パワー
        // 点火時には P_alpha ≈ 0.2 × P_fusion
        // P_fusion = P_heating / (1 - 0.2) = P_heating / 0.8
        // P_heating ≈ P_losses × 0.8

        return losses * 0.8;
    }

    /**
     * Spitzerの電気抵抗率を計算（古典的抵抗率）
     *
     * η = η_0 × Z_eff × ln(Λ) / T^(3/2)
     *
     * η_0 = 5.2×10^-5 Ω·m·keV^(3/2)（Spitzer定数）
     * Z_eff = 実効電荷数
     * ln(Λ) = Coulomb対数
     * T = 温度 (keV)
     *
     * @param temperatureK プラズマ温度 (K)
     * @param Z_eff 実効電荷数（D-T混合で通常1.5）
     * @param coulombLog Coulomb対数（典型的に15-20、省略可）
     * @return 電気抵抗率 (Ω·m)
     */
    public static double calculateSpitzerResistivity(double temperatureK, double Z_eff, double coulombLog) {
        // 温度をkeVに変換（1 eV = 11,604.5 K）
        double T_keV = temperatureK / 11604.5;

        if (T_keV <= 0) return 0.0;

        // Spitzer定数
        double eta_0 = 5.2e-5; // Ω·m·keV^(3/2)

        // η = η_0 × Z_eff × ln(Λ) / T^(3/2)
        return eta_0 * Z_eff * coulombLog / Math.pow(T_keV, 1.5);
    }

    /**
     * Spitzerの電気抵抗率を計算（Coulomb対数を自動計算）
     *
     * @param temperatureK プラズマ温度 (K)
     * @param density プラズマ密度 (/m³)
     * @param Z_eff 実効電荷数
     * @return 電気抵抗率 (Ω·m)
     */
    public static double calculateSpitzerResistivityFromDensity(double temperatureK, double density, double Z_eff) {
        // Coulomb対数の計算
        // ln(Λ) ≈ 17.3 - 0.5×ln(n_e / 10^20) + 1.5×ln(T_e / 1keV)
        double T_keV = temperatureK / 11604.5;
        double n_20 = density / 1e20;

        double coulombLog;
        if (T_keV > 0 && n_20 > 0) {
            coulombLog = 17.3 - 0.5 * Math.log(n_20) + 1.5 * Math.log(T_keV);
            // 物理的範囲に制限
            coulombLog = Math.max(10.0, Math.min(20.0, coulombLog));
        } else {
            coulombLog = 17.0; // デフォルト値
        }

        return calculateSpitzerResistivity(temperatureK, Z_eff, coulombLog);
    }

    /**
     * オーミック加熱パワーを計算
     *
     * P_ohmic = η × I_plasma² × L / A
     *
     * ここで簡易的に：
     * P_ohmic = η × I_plasma² × (トロイダル磁気軸長 / プラズマ断面積)
     *
     * より実用的には：
     * P_ohmic = V × j² × η
     * j = プラズマ電流密度 = I_plasma / A
     *
     * @param plasmaCurrent プラズマ電流 (A)（ITERでは15 MA）
     * @param resistivity Spitzer抵抗率 (Ω·m)
     * @param volume プラズマ体積 (m³)
     * @param crossSectionArea プラズマ断面積 (m²)
     * @return オーミック加熱パワー (W)
     */
    public static double calculateOhmicHeatingPower(double plasmaCurrent,
                                                     double resistivity,
                                                     double volume,
                                                     double crossSectionArea) {
        if (crossSectionArea <= 0) return 0.0;

        // 電流密度 j = I / A
        double currentDensity = plasmaCurrent / crossSectionArea;

        // P = V × j² × η
        return volume * currentDensity * currentDensity * resistivity;
    }

    /**
     * オーミック加熱パワーを計算（ITER簡易モデル）
     *
     * ITERの典型的なパラメータ：
     * - プラズマ電流: 15 MA
     * - プラズマ体積: 830 m³
     * - プラズマ断面積: 約20 m²（楕円形状）
     *
     * @param state プラズマ状態
     * @param plasmaCurrent プラズマ電流 (A)
     * @param volume プラズマ体積 (m³)
     * @param Z_eff 実効電荷数
     * @return オーミック加熱パワー (W)
     */
    public static double calculateOhmicHeatingPowerITER(PlasmaState state,
                                                         double plasmaCurrent,
                                                         double volume,
                                                         double Z_eff) {
        // 平均温度を使用
        double avgTemp = (state.temperatureIon + state.temperatureElectron) / 2.0;

        // Spitzer抵抗率を計算
        double resistivity = calculateSpitzerResistivityFromDensity(avgTemp, state.density, Z_eff);

        // ITERの断面積（典型値: a=2m、κ=1.7の楕円 → A ≈ π × a × b ≈ 21.4 m²）
        double crossSectionArea = 20.0; // m²

        return calculateOhmicHeatingPower(plasmaCurrent, resistivity, volume, crossSectionArea);
    }

    /**
     * オーミック加熱の温度依存性を確認
     *
     * 温度が上昇すると抵抗率がT^(-3/2)で減少するため、
     * オーミック加熱は高温で非効率になる。
     *
     * @param temperatureK 温度 (K)
     * @return 温度が2倍になった時のオーミック加熱の変化率（< 1なら減少）
     */
    public static double calculateOhmicHeatingScaling(double temperatureK) {
        // T → 2T の場合の抵抗率の変化
        // η(2T) / η(T) = (T / 2T)^(3/2) = (1/2)^(3/2) ≈ 0.35
        // つまり温度が2倍になると、オーミック加熱は約1/3に減少
        return Math.pow(2.0, -1.5);
    }
}
