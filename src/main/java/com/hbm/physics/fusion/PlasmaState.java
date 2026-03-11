package com.hbm.physics.fusion;

/**
 * プラズマ状態を表すデータクラス
 * イオン温度、電子温度、密度、圧力を保持
 */
public class PlasmaState {

    // Boltzmann定数 (J/K)
    public static final double BOLTZMANN = 1.380649e-23;

    // プラズマパラメータ
    public double temperatureIon;      // イオン温度 (K)
    public double temperatureElectron; // 電子温度 (K)
    public double density;             // 粒子密度 (particles/m³)
    public double pressure;            // 圧力 (Pa)

    // 磁場パラメータ
    public double magneticField;       // 磁場強度 (Tesla)
    public double plasmaBeta;          // プラズマベータ (圧力/磁気圧力)

    /**
     * デフォルトコンストラクタ（初期状態：冷たいプラズマ）
     */
    public PlasmaState() {
        this.temperatureIon = 1000.0;      // 1000 K
        this.temperatureElectron = 1000.0;
        this.density = 1.0e18;              // 10^18 /m³
        this.pressure = calculatePressure();
        this.magneticField = 5.0;           // 5 Tesla
        this.plasmaBeta = 0.0;
    }

    /**
     * パラメータ指定コンストラクタ
     */
    public PlasmaState(double Ti, double Te, double n, double B) {
        this.temperatureIon = Ti;
        this.temperatureElectron = Te;
        this.density = n;
        this.magneticField = B;
        this.pressure = calculatePressure();
        this.plasmaBeta = calculateBeta();
    }

    /**
     * 圧力を計算（理想気体の法則）
     * P = n * k_B * (T_i + T_e)
     * イオンと電子の両方が寄与
     */
    public double calculatePressure() {
        return density * BOLTZMANN * (temperatureIon + temperatureElectron);
    }

    /**
     * プラズマベータを計算
     * β = P_plasma / P_magnetic
     * P_magnetic = B² / (2μ₀)
     */
    public double calculateBeta() {
        if (magneticField <= 0) return 0.0;

        // 磁気圧力: B² / (2μ₀), μ₀ = 4π×10⁻⁷ H/m
        double mu0 = 4.0 * Math.PI * 1.0e-7;
        double magneticPressure = (magneticField * magneticField) / (2.0 * mu0);

        this.plasmaBeta = pressure / magneticPressure;
        return this.plasmaBeta;
    }

    /**
     * 平均温度を取得 (K)
     */
    public double getAverageTemperature() {
        return (temperatureIon + temperatureElectron) / 2.0;
    }

    /**
     * 平均温度を keV で取得
     * 1 eV = 11,604.5 K
     */
    public double getAverageTemperatureKeV() {
        return getAverageTemperature() / 11604.5;
    }

    /**
     * イオン温度を keV で取得
     */
    public double getIonTemperatureKeV() {
        return temperatureIon / 11604.5;
    }

    /**
     * 電子温度を keV で取得
     */
    public double getElectronTemperatureKeV() {
        return temperatureElectron / 11604.5;
    }

    /**
     * 状態を更新（温度、密度）
     */
    public void update(double Ti, double Te, double n) {
        this.temperatureIon = Math.max(0, Ti);
        this.temperatureElectron = Math.max(0, Te);
        this.density = Math.max(0, n);
        this.pressure = calculatePressure();
        this.plasmaBeta = calculateBeta();
    }

    /**
     * 温度を加熱（エネルギー追加）
     * @param energyJoules 追加エネルギー (J)
     * @param volume プラズマ体積 (m³)
     */
    public void addEnergy(double energyJoules, double volume) {
        if (volume <= 0 || density <= 0) return;

        // エネルギー密度から温度上昇を計算
        // E = (3/2) * N * k_B * T
        // N = n * V (粒子数)
        // ΔT = (2/3) * ΔE / (N * k_B)

        double particleCount = density * volume;
        double deltaT = (2.0 / 3.0) * energyJoules / (particleCount * BOLTZMANN);

        // イオンと電子に均等に分配（簡略化）
        this.temperatureIon += deltaT / 2.0;
        this.temperatureElectron += deltaT / 2.0;

        // 圧力とベータを更新
        this.pressure = calculatePressure();
        this.plasmaBeta = calculateBeta();
    }

    /**
     * 放射損失によるエネルギー減少
     * @param powerLossWatts 放射損失パワー (W)
     * @param volume プラズマ体積 (m³)
     * @param deltaTime 時間間隔 (s)
     */
    public void removeEnergy(double powerLossWatts, double volume, double deltaTime) {
        double energyLoss = powerLossWatts * deltaTime;
        addEnergy(-energyLoss, volume);
    }

    /**
     * プラズマが点火可能かチェック（簡易版）
     * D-Tの場合、約40M K以上必要
     */
    public boolean isIgnitable(double minTemperatureK) {
        return (temperatureIon >= minTemperatureK) && (temperatureElectron >= minTemperatureK * 0.9);
    }

    /**
     * デバッグ用文字列
     */
    @Override
    public String toString() {
        return String.format("PlasmaState[Ti=%.2e K (%.1f keV), Te=%.2e K (%.1f keV), n=%.2e /m³, P=%.2e Pa, β=%.3f%%]",
                temperatureIon, getIonTemperatureKeV(),
                temperatureElectron, getElectronTemperatureKeV(),
                density, pressure, plasmaBeta * 100.0);
    }

    /**
     * 状態のコピーを作成
     */
    public PlasmaState copy() {
        PlasmaState copy = new PlasmaState(temperatureIon, temperatureElectron, density, magneticField);
        copy.pressure = this.pressure;
        copy.plasmaBeta = this.plasmaBeta;
        return copy;
    }
}
