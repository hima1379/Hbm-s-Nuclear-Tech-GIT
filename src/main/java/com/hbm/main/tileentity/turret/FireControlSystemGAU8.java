package com.hbm.main.tileentity.turret;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/**
 * GAU-8用の超高精度射撃管制システム（次世代AI搭載版）
 *
 * 主要機能:
 * - カルマンフィルターによる高度なターゲット状態推定
 * - 加速度・機動予測システム
 * - Runge-Kutta 4次法による超高精度弾道計算
 * - 高度依存空気密度・マッハ数依存抗力係数
 * - 複数弾道解の探索と最適解選択
 * - 風補正システム
 * - 命中確率計算
 * - 学習・適応システム
 * - 詳細な診断・デバッグ機能
 *
 * 物理モデル（EntityBulletGAU8と完全に同期、実測データに基づく）:
 * - 初速: 1,013 m/s (実測値)
 * - 最大射程: 3,660 m
 * - 基準空気密度: 1.225 kg/m³ (海面高度)
 * - 基準抗力係数: 0.05 (流線型貫通弾、亜音速)
 * - 重力: 9.8 m/s²
 * - Runge-Kutta 4次法による超高精度弾道計算
 */
public class FireControlSystemGAU8 {

    // ============================================================
    // 物理定数 (EntityBulletGAU8と同じ - 実測データに基づく)
    // ============================================================
    private static final double BULLET_MASS = 0.395;           // kg
    private static final double INITIAL_VELOCITY = 1013.0;     // m/s
    private static final double BASE_DRAG_COEFFICIENT = 0.05;  // 基準抗力係数
    private static final double CROSS_SECTION_AREA = 0.000707; // m²
    private static final double SEA_LEVEL_AIR_DENSITY = 1.225; // kg/m³
    private static final double GRAVITY = 9.8;                 // m/s²
    private static final double SPEED_OF_SOUND = 340.29;       // m/s (15°C)

    // ============================================================
    // シミュレーション設定
    // ============================================================
    private static final double TIME_STEP = 0.005;            // 5ms間隔（高精度化: 10ms -> 5ms）
    private static final int MAX_ITERATIONS = 120000;         // 最大600秒
    private static final double CONVERGENCE_THRESHOLD = 0.1;  // 0.1ブロック以内で収束（超高精度化）
    private static final double MIN_BULLET_SPEED = 30.0;      // 最小弾速

    // ============================================================
    // 反復計算設定（大幅強化）
    // ============================================================
    private static final int MAX_FIRE_SOLUTION_ITERATIONS = 50;  // 反復回数を大幅増加（20 -> 50）
    private static final double ANGLE_ADJUSTMENT_RATE = 0.7;     // 角度調整レート向上（0.6 -> 0.7）
    private static final int MULTI_ANGLE_SAMPLES = 12;           // 複数角度サンプリング数（新規）

    // ============================================================
    // カルマンフィルター設定（距離適応型・超音速対応）
    // ============================================================
    private static final double BASE_PROCESS_NOISE = 0.1;       // 基準プロセスノイズ
    private static final double BASE_MEASUREMENT_NOISE = 0.3;   // 基準測定ノイズ（500m基準）
    private static final int TRACKING_HISTORY_SIZE = 30;        // 追跡履歴サイズ（20→30に増加）

    // 距離別測定ノイズスケーリング（実測データに基づく）
    private static final double NOISE_SCALE_500M = 0.5;         // 500m: 0.15m
    private static final double NOISE_SCALE_1000M = 2.0;        // 1000m: 0.6m
    private static final double NOISE_SCALE_2000M = 8.0;        // 2000m: 2.4m
    private static final double NOISE_SCALE_3000M = 18.0;       // 3000m: 5.4m

    // ターゲット速度分類（超音速対応）
    private static final double SPEED_SUBSONIC = 100.0;         // 100 m/s以下: 亜音速
    private static final double SPEED_TRANSONIC = 340.0;        // 340 m/s: 音速
    private static final double SPEED_SUPERSONIC = 680.0;       // 680 m/s: マッハ2

    // ============================================================
    // ターゲット追跡データベース
    // ============================================================
    private static final Map<Integer, TargetTrackingData> trackingDatabase = new HashMap<>();
    private static final long TRACKING_TIMEOUT = 10000; // 10秒でタイムアウト

    // ============================================================
    // 学習データベース
    // ============================================================
    private static final Map<String, LearningData> learningDatabase = new HashMap<>();
    private static final int MAX_LEARNING_SAMPLES = 100;

    // ============================================================
    // 風データ（将来的に動的に取得可能）
    // ============================================================
    private static Vec3d globalWind = new Vec3d(0, 0, 0); // デフォルトは無風

    /**
     * 方向ベクトルから射撃角度を計算（新アーキテクチャ）
     *
     * @param direction 正規化された方向ベクトル
     * @return [pitch, yaw] の配列（ラジアン）
     */
    private static double[] calculateFireAngles(Vec3d direction) {
        // Pitch (仰角): Y成分から計算
        double pitch = Math.asin(direction.y);

        // Yaw (方位角): X,Z成分から計算
        // Minecraftの座標系: -Math.atan2(X, Z)
        double yaw = -Math.atan2(direction.x, direction.z);

        return new double[]{pitch, yaw};
    }

    /**
     * 射撃角度を直接計算（新アーキテクチャ）
     * 親クラスのturnTowards()を使わず、弾道計算から直接pitch/yawを算出
     *
     * @param turretPos タレット位置
     * @param target ターゲットエンティティ
     * @return 射撃解（aim angleを含む）、見つからない場合はnull
     */
    public static FireSolution calculateOptimalFireSolution(Vec3d turretPos, Entity target) {
        if (target == null) {
            return null;
        }

        Vec3d targetPos = target.getPositionVector().add(0, target.height * 0.5, 0);

        // エンティティの実際の速度を取得（Minecraftの単位：ブロック/tick → m/s変換）
        // 1 tick = 1/20秒なので、速度を20倍してm/sに変換
        Vec3d targetVelocity = new Vec3d(
            target.motionX * 20.0,
            target.motionY * 20.0,
            target.motionZ * 20.0
        );

        // ターゲット追跡データを取得または作成
        TargetTrackingData trackingData = getOrCreateTrackingData(target);

        // タレット位置を設定（距離適応型カルマンフィルター用）
        trackingData.turretPosition = turretPos;

        // 速度情報を含めて追跡データを更新
        trackingData.updateWithVelocity(targetPos, targetVelocity, System.currentTimeMillis());

        // カルマンフィルターで状態推定
        TargetState estimatedState = trackingData.getEstimatedState();

        // CRITICAL FIX: 1tick先読み予測（発射遅延補償）
        // ミサイルの現在位置から偏差射撃するのではなく、
        // 1tick後の未来位置を予測してから偏差射撃を計算する
        // これにより検出→計算→発射の遅延を事前補償
        double TICK_TIME = 0.05; // 1 tick = 0.05秒 = 1/20秒
        Vec3d futurePosition = predictTargetPosition(estimatedState, TICK_TIME);

        // 1tick後の状態を構築（位置のみ更新、速度・加速度・ジャークは維持）
        TargetState futureState = new TargetState(
            futurePosition,                // 1tick後の予測位置
            estimatedState.velocity,       // 速度は変わらない
            estimatedState.acceleration,   // 加速度は変わらない
            estimatedState.jerk            // ジャークは変わらない
        );

        // 高度な予測位置計算（1tick後の未来位置を基準に）
        FireSolution solution = calculateAdvancedFireSolution(
            turretPos,
            futureState,  // 現在位置ではなく1tick後の未来位置を使用
            trackingData
        );

        // 学習データから補正
        if (solution != null) {
            applyLearningCorrection(solution, estimatedState, trackingData);

            // TIMING LOG: 射撃解計算完了時刻を記録
            solution.calculationTime = System.currentTimeMillis();
        }

        return solution;
    }

    /**
     * レガシーAPI互換メソッド
     */
    public static Vec3d calculateAimPoint(Vec3d turretPos, Vec3d targetPos, Vec3d targetVel) {
        // ダミーエンティティIDを使用
        TargetTrackingData trackingData = new TargetTrackingData(-1);
        trackingData.update(targetPos, System.currentTimeMillis());

        if (targetVel != null) {
            trackingData.velocity = targetVel;
        }

        TargetState state = trackingData.getEstimatedState();
        FireSolution solution = calculateAdvancedFireSolution(turretPos, state, trackingData);

        return solution != null ? solution.aimPoint : null;
    }

    /**
     * 高度な射撃解計算
     */
    private static FireSolution calculateAdvancedFireSolution(
        Vec3d turretPos,
        TargetState targetState,
        TargetTrackingData trackingData
    ) {
        double distance = turretPos.distanceTo(targetState.position);

        // 射程チェック
        if (distance > 3660.0) {
            return null;
        }

        // ターゲットの速度
        double targetSpeed = targetState.velocity.length();

        // 静止または低速ターゲットの場合
        if (targetSpeed < 0.5) {
            return calculateStaticTargetSolution(turretPos, targetState);
        }

        // 遠距離（600m以上）の場合、専用の高精度計算を優先
        if (distance >= 600.0) {
            FireSolution longRangeSolution = calculateLongRangeFireSolution(
                turretPos, targetState, trackingData
            );
            if (longRangeSolution != null && longRangeSolution.error < 10.0) {
                return longRangeSolution;
            }
        }

        // 動的ターゲットの場合、複数のアプローチを試す
        List<FireSolution> candidates = new ArrayList<>();

        // アプローチ1: 反復法（改良版）
        FireSolution iterativeSolution = calculateIterativeFireSolution(
            turretPos, targetState, trackingData
        );
        if (iterativeSolution != null) {
            candidates.add(iterativeSolution);
        }

        // アプローチ2: 複数角度サンプリング
        FireSolution sampledSolution = calculateMultiAngleSampledSolution(
            turretPos, targetState, trackingData
        );
        if (sampledSolution != null) {
            candidates.add(sampledSolution);
        }

        // アプローチ3: 加速度予測ベース
        if (targetState.acceleration.lengthSquared() > 0.1) {
            FireSolution accelSolution = calculateAccelerationBasedSolution(
                turretPos, targetState, trackingData
            );
            if (accelSolution != null) {
                candidates.add(accelSolution);
            }
        }

        // 最良の解を選択
        FireSolution bestSolution = selectBestSolution(candidates, targetState, distance);

        return bestSolution;
    }

    /**
     * 静止ターゲット用の射撃解（強化版）
     * 遠距離の場合は重力補償を強化し、より広範囲を探索
     */
    private static FireSolution calculateStaticTargetSolution(Vec3d turretPos, TargetState targetState) {
        Vec3d delta = targetState.position.subtract(turretPos);
        double distance = delta.length();

        // 遠距離の静止ターゲットには専用計算を使用
        if (distance >= 600.0) {
            Vec3d delta3d = targetState.position.subtract(turretPos);
            double horizontalDistance = Math.sqrt(delta3d.x * delta3d.x + delta3d.z * delta3d.z);
            double verticalDelta = delta3d.y;

            // 方位角
            double azimuth = Math.atan2(delta3d.z, delta3d.x);

            // 初期仰角推定（重力補償強化版）
            double baseElevation = calculateInitialElevation(horizontalDistance, verticalDelta, distance);

            FireSolution bestSolution = null;
            double bestError = Double.MAX_VALUE;

            // 距離に応じたサンプリング設定（3000m対応強化）
            int numSamples;
            double angleStep;
            if (distance < 1000) {
                numSamples = 21;
                angleStep = 0.02; // ±0.2 rad（約±11.5度）
            } else if (distance < 2000) {
                numSamples = 35;
                angleStep = 0.025; // ±0.4375 rad（約±25度）
            } else if (distance < 3000) {
                numSamples = 55;
                angleStep = 0.03; // ±0.825 rad（約±47度）
            } else {
                // 3000m以上: 超広範囲探索
                numSamples = 75;
                angleStep = 0.04; // ±1.5 rad（約±86度）
            }

            // 仰角を細かくサンプリング
            for (int i = -(numSamples / 2); i <= (numSamples / 2); i++) {
                double testElevation = baseElevation + i * angleStep;

                Vec3d direction = new Vec3d(
                    Math.cos(testElevation) * Math.cos(azimuth),
                    Math.sin(testElevation),
                    Math.cos(testElevation) * Math.sin(azimuth)
                );

                BallisticResult result = simulateTrajectoryRK4(turretPos, direction, targetState.position, i + numSamples);

                if (result != null && result.closestDistance < bestError) {
                    bestError = result.closestDistance;

                    Vec3d aimPoint = turretPos.add(direction.scale(1000));

                    double hitProbability = 1.0 - Math.min(1.0, result.closestDistance / 10.0);

                    // 射撃角度を計算
                    double[] fireAngles = calculateFireAngles(direction);

                    bestSolution = new FireSolution(
                        aimPoint,
                        result.flightTime,
                        result.closestDistance,
                        hitProbability,
                        "STATIC_LONG_RANGE",
                        fireAngles[0], // pitch
                        fireAngles[1]  // yaw
                    );

                    // 十分に良い解が見つかったら終了（距離に応じた閾値 - 3000m対応）
                    double threshold;
                    if (distance < 1000) {
                        threshold = CONVERGENCE_THRESHOLD; // 0.1m
                    } else if (distance < 2000) {
                        threshold = CONVERGENCE_THRESHOLD * 5; // 0.5m
                    } else if (distance < 3000) {
                        threshold = CONVERGENCE_THRESHOLD * 15; // 1.5m
                    } else {
                        threshold = CONVERGENCE_THRESHOLD * 30; // 3.0m（限界射程）
                    }
                    if (bestError < threshold) {
                        System.out.println("[FCS-GAU8] Static long-range solution |" +
                            " Distance: " + String.format("%.0f", distance) + "m" +
                            " | Error: " + String.format("%.2f", bestError) + "m" +
                            " | Elevation: " + String.format("%.2f", Math.toDegrees(testElevation)) + "°");
                        return bestSolution;
                    }
                }
            }

            if (bestSolution != null) {
                System.out.println("[FCS-GAU8] Static long-range best solution |" +
                    " Distance: " + String.format("%.0f", distance) + "m" +
                    " | Error: " + String.format("%.2f", bestError) + "m");
            }

            return bestSolution;
        }

        // 近距離の場合は従来の反復計算
        double estimatedTime = distance / INITIAL_VELOCITY;
        Vec3d aimPoint = targetState.position;
        FireSolution bestSolution = null;
        double bestError = Double.MAX_VALUE;

        for (int i = 0; i < 30; i++) {  // 10 -> 30回に増加
            Vec3d direction = aimPoint.subtract(turretPos).normalize();
            BallisticResult result = simulateTrajectoryRK4(turretPos, direction, aimPoint, 0);

            if (result == null) break;

            // 最良解を記録
            if (result.closestDistance < bestError) {
                bestError = result.closestDistance;

                // 射撃角度を計算
                double[] fireAngles = calculateFireAngles(direction);

                bestSolution = new FireSolution(
                    aimPoint,
                    result.flightTime,
                    result.closestDistance,
                    1.0 - Math.min(1.0, result.closestDistance / 10.0),
                    "STATIC_ITERATIVE",
                    fireAngles[0], // pitch
                    fireAngles[1]  // yaw
                );
            }

            // 十分に良い解が見つかれば早期終了
            if (result.closestDistance < CONVERGENCE_THRESHOLD) {
                return bestSolution;
            }

            // 補正
            aimPoint = aimPoint.add(result.missVector.scale(0.8));
        }

        // 収束しなくても最良の解を返す（nullではなく）
        if (bestSolution != null && bestError < distance * 0.2) {  // 距離の20%以内なら許容
            System.out.println("[FCS-GAU8] Static mid-range solution (partial convergence) |" +
                " Distance: " + String.format("%.0f", distance) + "m" +
                " | Error: " + String.format("%.2f", bestError) + "m");
            return bestSolution;
        }

        return null;
    }

    /**
     * 遠距離（600m以上）専用の高精度射撃解計算（強化版）
     * 重力補償を強化し、より広範囲の仰角を探索
     */
    private static FireSolution calculateLongRangeFireSolution(
        Vec3d turretPos,
        TargetState targetState,
        TargetTrackingData trackingData
    ) {
        Vec3d delta = targetState.position.subtract(turretPos);
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double verticalDelta = delta.y;
        double totalDistance = delta.length();

        // 方位角
        double azimuth = Math.atan2(delta.z, delta.x);

        // 初期仰角推定（重力補償強化版）
        double baseElevation = calculateInitialElevation(horizontalDistance, verticalDelta, totalDistance);

        FireSolution bestSolution = null;
        double bestError = Double.MAX_VALUE;

        // 遠距離用：距離に応じたサンプリング設定（3000m対応強化）
        // より多くのサンプルとより広い角度範囲で探索し、3000mでも確実に解を発見
        int numSamples;
        double angleRange;
        if (totalDistance < 1000) {
            // 600-1000m: 標準サンプリング
            numSamples = 25;
            angleRange = 0.05; // ±2.86度
        } else if (totalDistance < 1500) {
            // 1000-1500m: 詳細サンプリング
            numSamples = 35;
            angleRange = 0.08; // ±4.58度
        } else if (totalDistance < 2000) {
            // 1500-2000m: より広範囲を探索
            numSamples = 45;
            angleRange = 0.12; // ±6.88度
        } else if (totalDistance < 2500) {
            // 2000-2500m: 極めて広範囲を探索
            numSamples = 55;
            angleRange = 0.18; // ±10.3度
        } else if (totalDistance < 3000) {
            // 2500-3000m: 最大範囲探索（限界射程）
            numSamples = 70;
            angleRange = 0.25; // ±14.3度
        } else {
            // 3000m以上: 超広範囲探索（理論限界）
            numSamples = 90;
            angleRange = 0.35; // ±20度
        }

        for (int i = 0; i < numSamples; i++) {
            // 中心周辺を密に、外側を疎にサンプリング（二次曲線分布）
            double t = (i - numSamples / 2.0) / (numSamples / 2.0); // -1.0 to 1.0
            double angleOffset = Math.signum(t) * t * t * angleRange; // 二次曲線
            double testElevation = baseElevation + angleOffset;

            // 方向ベクトル
            Vec3d direction = new Vec3d(
                Math.cos(testElevation) * Math.cos(azimuth),
                Math.sin(testElevation),
                Math.cos(testElevation) * Math.sin(azimuth)
            );

            // 飛行時間推定
            double estimatedTime = estimateFlightTime(totalDistance, testElevation);

            // ターゲット予測位置
            Vec3d predictedPos = predictTargetPosition(targetState, estimatedTime);

            // 反復最適化（各角度で、遠距離ほど反復回数を増やす - 3000m対応）
            Vec3d currentPrediction = predictedPos;
            int maxIterations;
            if (totalDistance < 1000) {
                maxIterations = 10;
            } else if (totalDistance < 2000) {
                maxIterations = 15;
            } else if (totalDistance < 3000) {
                maxIterations = 20;
            } else {
                maxIterations = 25;
            }

            // 収束検出用変数
            double previousError = Double.MAX_VALUE;
            double previousFlightTime = estimatedTime;
            int divergenceCount = 0;

            for (int iter = 0; iter < maxIterations; iter++) {
                // シミュレーション
                BallisticResult result = simulateTrajectoryRK4(turretPos, direction, currentPrediction, i * 100 + iter);

                if (result == null) break;

                double error = result.closestDistance;

                // 発散検出: 誤差が連続して増加している場合は反復を停止
                if (error > previousError * 1.1) {
                    divergenceCount++;
                    if (divergenceCount >= 2) {
                        // 2回連続で悪化 → 発散しているので最良解を返す
                        if (totalDistance >= 2000 && i == 0 && iter <= 3) {
                            System.out.println("[FCS-GAU8] Iteration diverging, stopping at iter " + iter +
                                " | Error: " + String.format("%.2f", error) + "m" +
                                " | Previous: " + String.format("%.2f", previousError) + "m");
                        }
                        break;
                    }
                } else {
                    divergenceCount = 0;
                }

                // 最良解を記録
                if (error < bestError) {
                    bestError = error;

                    Vec3d aimPoint = turretPos.add(direction.scale(1000));

                    double hitProbability = calculateHitProbability(
                        error,
                        totalDistance,
                        targetState.velocity.length()
                    );

                    // 射撃角度を計算
                    double[] fireAngles = calculateFireAngles(direction);

                    bestSolution = new FireSolution(
                        aimPoint,
                        result.flightTime,
                        error,
                        hitProbability,
                        "LONG_RANGE",
                        fireAngles[0], // pitch
                        fireAngles[1]  // yaw
                    );
                }

                // 収束判定（遠距離ほど緩和 - 3000m対応）
                // 3000mで5m以内の精度を目標とする（現実的な着弾分散を考慮）
                double convergenceThreshold;
                if (totalDistance < 1000) {
                    convergenceThreshold = CONVERGENCE_THRESHOLD; // 0.1m
                } else if (totalDistance < 2000) {
                    convergenceThreshold = CONVERGENCE_THRESHOLD * 3; // 0.3m
                } else if (totalDistance < 3000) {
                    convergenceThreshold = CONVERGENCE_THRESHOLD * 10; // 1.0m
                } else {
                    convergenceThreshold = CONVERGENCE_THRESHOLD * 20; // 2.0m（限界射程）
                }
                if (error < convergenceThreshold) {
                    return bestSolution;
                }

                // 飛行時間の変化が小さい場合は収束と判定
                double flightTimeDelta = Math.abs(result.flightTime - previousFlightTime);
                if (iter > 0 && flightTimeDelta < 0.01) {
                    // 飛行時間が10ms未満の変化 → 収束
                    if (totalDistance >= 2000 && i == 0) {
                        System.out.println("[FCS-GAU8] Converged at iter " + iter +
                            " | Error: " + String.format("%.2f", error) + "m" +
                            " | Flight time stable: " + String.format("%.3f", result.flightTime) + "s");
                    }
                    break;
                }

                // 誤差の変化が小さい場合も収束と判定
                double errorDelta = Math.abs(error - previousError);
                double errorChangeRate = previousError > 0 ? errorDelta / previousError : 0;
                if (iter > 0 && errorChangeRate < 0.05) {
                    // 誤差変化が5%未満 → 収束
                    if (totalDistance >= 2000 && i == 0) {
                        System.out.println("[FCS-GAU8] Converged at iter " + iter +
                            " | Error: " + String.format("%.2f", error) + "m" +
                            " | Error change: " + String.format("%.1f%%", errorChangeRate * 100));
                    }
                    break;
                }

                // 飛行時間を更新して目標位置を再予測
                // これにより、弾道の実際の飛行時間における目標の正確な位置を計算
                double prevEstimatedTime = estimatedTime;
                estimatedTime = result.flightTime;
                Vec3d prevPrediction = currentPrediction;
                currentPrediction = predictTargetPosition(targetState, estimatedTime);

                // CRITICAL FIX: 方向ベクトルを更新（移動目標対応）
                // 予測位置が更新されたので、それに向かって射撃するように方向ベクトルを再計算
                Vec3d deltaToTarget = currentPrediction.subtract(turretPos);
                double horizontalDist = Math.sqrt(deltaToTarget.x * deltaToTarget.x + deltaToTarget.z * deltaToTarget.z);
                double newAzimuth = Math.atan2(deltaToTarget.z, deltaToTarget.x);
                double newElevation = Math.atan2(deltaToTarget.y, horizontalDist);

                // 方向ベクトルを新しい予測位置に向けて更新
                direction = new Vec3d(
                    Math.cos(newElevation) * Math.cos(newAzimuth),
                    Math.sin(newElevation),
                    Math.cos(newElevation) * Math.sin(newAzimuth)
                );

                // デバッグログ: 反復改善の詳細（2000m以上かつ最初の3回の反復のみ）
                if (totalDistance >= 2000 && iter < 3 && i == 0) {
                    double predictionShift = currentPrediction.distanceTo(prevPrediction);
                    System.out.println("[FCS-GAU8] Iteration #" + iter +
                        " | Error: " + String.format("%.2f", error) + "m" +
                        " (Δ " + String.format("%+.2f", error - previousError) + "m)" +
                        " | Flight time: " + String.format("%.3f", estimatedTime) + "s" +
                        " (Δ " + String.format("%+.3f", flightTimeDelta) + "s)" +
                        " | Prediction shift: " + String.format("%.2f", predictionShift) + "m");
                }

                // 次の反復のために前回値を保存
                previousError = error;
                previousFlightTime = result.flightTime;
            }

            // 早期終了判定（距離に応じた許容誤差 - 3000m対応）
            double earlyExitThreshold;
            if (totalDistance < 1000) {
                earlyExitThreshold = 2.0; // 2m以内で早期終了
            } else if (totalDistance < 2000) {
                earlyExitThreshold = 5.0; // 5m以内
            } else if (totalDistance < 3000) {
                earlyExitThreshold = 10.0; // 10m以内（十分な精度）
            } else {
                earlyExitThreshold = 20.0; // 20m以内（限界射程）
            }
            if (bestError < earlyExitThreshold) {
                break;
            }
        }

        // デバッグログ（3000m射撃では常に詳細ログ）
        if (bestSolution != null) {
            String logLevel = totalDistance >= 2500 ? "!!!" : (totalDistance >= 2000 ? "!!" : "");

            // 目標速度ベクトルの成分を表示（偏差射撃が正しく機能しているか確認用）
            Vec3d vel = targetState.velocity;
            String velocityInfo = String.format("V[%.1f, %.1f, %.1f]", vel.x, vel.y, vel.z);

            System.out.println("[FCS-GAU8] " + logLevel + " Long range solution found |" +
                " Distance: " + String.format("%.0f", totalDistance) + "m" +
                " | Error: " + String.format("%.2f", bestError) + "m" +
                " | Samples: " + numSamples +
                " | Hit prob: " + String.format("%.1f%%", bestSolution.hitProbability * 100) +
                " | Flight time: " + String.format("%.2fs", bestSolution.flightTime) +
                " | Target " + velocityInfo);

            // 3000m近傍の超長距離射撃では追加情報を出力
            if (totalDistance >= 2500) {
                System.out.println("[FCS-GAU8] EXTREME RANGE |" +
                    " Horizontal: " + String.format("%.0f", horizontalDistance) + "m" +
                    " | Vertical: " + String.format("%.0f", verticalDelta) + "m" +
                    " | Target speed: " + String.format("%.1f", targetState.velocity.length()) + " m/s");
            }
        } else {
            // 解が見つからない場合は警告
            System.out.println("[FCS-GAU8] WARNING: No solution found |" +
                " Distance: " + String.format("%.0f", totalDistance) + "m" +
                " | Samples tested: " + numSamples);
        }

        return bestSolution;
    }

    /**
     * 反復法による射撃解計算（改良版）
     */
    private static FireSolution calculateIterativeFireSolution(
        Vec3d turretPos,
        TargetState targetState,
        TargetTrackingData trackingData
    ) {
        // 初期飛行時間推定
        double distance = turretPos.distanceTo(targetState.position);
        double estimatedFlightTime = estimateFlightTime(distance, 0);

        // 予測位置の初期値
        Vec3d predictedPos = predictTargetPosition(targetState, estimatedFlightTime);

        Vec3d bestAimPoint = predictedPos;
        double bestError = Double.MAX_VALUE;
        double bestFlightTime = estimatedFlightTime;

        // 反復最適化
        for (int iteration = 0; iteration < MAX_FIRE_SOLUTION_ITERATIONS; iteration++) {
            Vec3d delta = predictedPos.subtract(turretPos);
            Vec3d direction = delta.normalize();

            // 高精度弾道シミュレーション
            BallisticResult result = simulateTrajectoryRK4(
                turretPos,
                direction,
                predictedPos,
                iteration
            );

            if (result == null) {
                break;
            }

            double error = result.closestDistance;

            // 収束チェック
            if (error < CONVERGENCE_THRESHOLD) {
                double hitProbability = calculateHitProbability(
                    error, distance, targetState.velocity.length()
                );

                // 射撃角度を計算
                double[] fireAngles = calculateFireAngles(direction);

                return new FireSolution(
                    predictedPos,
                    result.flightTime,
                    error,
                    hitProbability,
                    "ITERATIVE",
                    fireAngles[0], // pitch
                    fireAngles[1]  // yaw
                );
            }

            // より良い解を記録
            if (error < bestError) {
                bestError = error;
                bestAimPoint = predictedPos;
                bestFlightTime = result.flightTime;
            }

            // 適応的な補正率
            double adaptiveRate = ANGLE_ADJUSTMENT_RATE * (1.0 - (double)iteration / MAX_FIRE_SOLUTION_ITERATIONS);
            Vec3d correction = result.missVector.scale(adaptiveRate);
            predictedPos = predictedPos.add(correction);

            // 飛行時間を更新して再予測
            estimatedFlightTime = result.flightTime;
            predictedPos = predictTargetPosition(targetState, estimatedFlightTime);
        }

        // 収束しなかった場合、最良の解を返す
        if (bestError < 5.0) { // 5ブロック以内なら許容
            double hitProbability = calculateHitProbability(
                bestError, distance, targetState.velocity.length()
            );

            // 射撃角度を計算
            Vec3d bestDirection = bestAimPoint.subtract(turretPos).normalize();
            double[] fireAngles = calculateFireAngles(bestDirection);

            return new FireSolution(
                bestAimPoint,
                bestFlightTime,
                bestError,
                hitProbability,
                "ITERATIVE_BEST",
                fireAngles[0], // pitch
                fireAngles[1]  // yaw
            );
        }

        return null;
    }

    /**
     * 複数角度サンプリングによる射撃解
     * 遠距離の重力補償を改善
     */
    private static FireSolution calculateMultiAngleSampledSolution(
        Vec3d turretPos,
        TargetState targetState,
        TargetTrackingData trackingData
    ) {
        Vec3d delta = targetState.position.subtract(turretPos);
        double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double verticalDelta = delta.y;
        double totalDistance = delta.length();

        // 基準仰角を計算（重力補償を含む改良版）
        double baseElevation = calculateInitialElevation(horizontalDistance, verticalDelta, totalDistance);

        FireSolution bestSolution = null;
        double bestScore = Double.MAX_VALUE;

        // 複数の仰角でサンプリング（遠距離ほどサンプリング範囲を拡大）
        double angleRange = totalDistance > 600 ? 0.04 : 0.02; // 600m超では±0.24 rad、それ以下は±0.12 rad

        for (int i = 0; i < MULTI_ANGLE_SAMPLES; i++) {
            double angleOffset = (i - MULTI_ANGLE_SAMPLES / 2.0) * angleRange;
            double testElevation = baseElevation + angleOffset;

            // 方位角
            double azimuth = Math.atan2(delta.z, delta.x);

            // 方向ベクトル
            Vec3d direction = new Vec3d(
                Math.cos(testElevation) * Math.cos(azimuth),
                Math.sin(testElevation),
                Math.cos(testElevation) * Math.sin(azimuth)
            );

            // 飛行時間推定（改良版を使用）
            double estimatedTime = estimateFlightTime(totalDistance, testElevation);
            Vec3d predictedPos = predictTargetPosition(targetState, estimatedTime);

            // シミュレーション
            BallisticResult result = simulateTrajectoryRK4(turretPos, direction, predictedPos, i);

            if (result != null) {
                double score = result.closestDistance;

                if (score < bestScore) {
                    bestScore = score;
                    Vec3d aimPoint = turretPos.add(direction.scale(1000)); // 照準点

                    double hitProbability = calculateHitProbability(
                        score,
                        turretPos.distanceTo(targetState.position),
                        targetState.velocity.length()
                    );

                    // 射撃角度を計算
                    double[] fireAngles = calculateFireAngles(direction);

                    bestSolution = new FireSolution(
                        aimPoint,
                        result.flightTime,
                        score,
                        hitProbability,
                        "MULTI_ANGLE",
                        fireAngles[0], // pitch
                        fireAngles[1]  // yaw
                    );
                }
            }
        }

        return bestSolution;
    }

    /**
     * 重力補償を含む初期仰角の計算（強化版）
     * 遠距離での弾道降下を正確に考慮
     */
    private static double calculateInitialElevation(double horizontalDistance, double verticalDelta, double totalDistance) {
        // 直線的な仰角
        double straightLineElevation = Math.atan2(verticalDelta, horizontalDistance);

        // 飛行時間の推定（距離に応じた減速を考慮）
        double estimatedTime = estimateFlightTime(totalDistance, straightLineElevation);

        // 重力による降下量: h = 0.5 * g * t^2
        double gravityDrop = 0.5 * GRAVITY * estimatedTime * estimatedTime;

        // 空気抵抗による追加降下（実測データに基づく改良版）
        // 600mまで：ほぼ無視できる（GAU-8は非常にフラットな弾道）
        // 600-1500m：徐々に増加
        // 1500m以上：急激に増加
        double dragDrop;
        if (totalDistance < 600) {
            dragDrop = horizontalDistance * 0.0001; // 最小限
        } else if (totalDistance < 1500) {
            // 600-1500m: 0.0001から0.0008へ線形増加
            double t = (totalDistance - 600) / 900.0;
            double dragFactor = 0.0001 + t * (0.0008 - 0.0001);
            dragDrop = horizontalDistance * dragFactor;
        } else {
            // 1500m以上: 0.0008から0.0015へ線形増加
            double t = Math.min(1.0, (totalDistance - 1500) / 1500.0);
            double dragFactor = 0.0008 + t * (0.0015 - 0.0008);
            dragDrop = horizontalDistance * dragFactor;
        }

        // 総降下量を補償するための仰角補正
        double totalDrop = gravityDrop + dragDrop;
        double elevationCorrection = Math.atan2(totalDrop, horizontalDistance);

        // 遠距離補正係数の強化（3000m対応 - Goalkeeper性能を超える射程）
        // 実弾道データに基づき、3000mでの重力降下を正確に補償
        double longRangeFactor = 1.0;
        if (totalDistance > 600) {
            // 600-1500m: 1.0から1.3へ（標準的な長距離補正）
            if (totalDistance < 1500) {
                double t = (totalDistance - 600) / 900.0;
                longRangeFactor = 1.0 + t * 0.3;
            }
            // 1500-2500m: 1.3から2.0へ（強力な補正 - 実測データ調整）
            else if (totalDistance < 2500) {
                double t = (totalDistance - 1500) / 1000.0;
                longRangeFactor = 1.3 + t * 0.7;
            }
            // 2500-3000m: 2.0から2.5へ（極限補正 - 限界射程）
            else if (totalDistance < 3000) {
                double t = (totalDistance - 2500) / 500.0;
                longRangeFactor = 2.0 + t * 0.5;
            }
            // 3000m以上: 2.5から3.0へ（理論限界）
            else if (totalDistance < 3660) {
                double t = (totalDistance - 3000) / 660.0;
                longRangeFactor = 2.5 + t * 0.5;
            }
            // 最大射程超過: 3.0固定
            else {
                longRangeFactor = 3.0;
            }
            elevationCorrection *= longRangeFactor;
        }

        // デバッグログ（遠距離の場合のみ）
        if (totalDistance > 600 && totalDistance % 500 < 50) {
            System.out.println("[FCS-GAU8] Elevation calc |" +
                " Distance: " + String.format("%.0f", totalDistance) + "m" +
                " | Gravity drop: " + String.format("%.2f", gravityDrop) + "m" +
                " | Drag drop: " + String.format("%.2f", dragDrop) + "m" +
                " | Total drop: " + String.format("%.2f", totalDrop) + "m" +
                " | Base elevation: " + String.format("%.2f", Math.toDegrees(straightLineElevation)) + "°" +
                " | Correction: " + String.format("%.2f", Math.toDegrees(elevationCorrection)) + "°" +
                " | Factor: " + String.format("%.2f", longRangeFactor));
        }

        return straightLineElevation + elevationCorrection;
    }

    /**
     * 加速度ベースの射撃解
     */
    private static FireSolution calculateAccelerationBasedSolution(
        Vec3d turretPos,
        TargetState targetState,
        TargetTrackingData trackingData
    ) {
        double distance = turretPos.distanceTo(targetState.position);
        double estimatedTime = estimateFlightTime(distance, 0);

        // 加速度を考慮した予測
        Vec3d predictedPos = targetState.position
            .add(targetState.velocity.scale(estimatedTime))
            .add(targetState.acceleration.scale(0.5 * estimatedTime * estimatedTime));

        // 反復最適化
        for (int i = 0; i < 20; i++) {
            Vec3d direction = predictedPos.subtract(turretPos).normalize();
            BallisticResult result = simulateTrajectoryRK4(turretPos, direction, predictedPos, i);

            if (result == null) break;

            if (result.closestDistance < CONVERGENCE_THRESHOLD) {
                double hitProbability = calculateHitProbability(
                    result.closestDistance,
                    distance,
                    targetState.velocity.length()
                );

                // 射撃角度を計算
                double[] fireAngles = calculateFireAngles(direction);

                return new FireSolution(
                    predictedPos,
                    result.flightTime,
                    result.closestDistance,
                    hitProbability,
                    "ACCELERATION",
                    fireAngles[0], // pitch
                    fireAngles[1]  // yaw
                );
            }

            predictedPos = predictedPos.add(result.missVector.scale(0.7));
            estimatedTime = result.flightTime;

            // 加速度を再考慮
            predictedPos = targetState.position
                .add(targetState.velocity.scale(estimatedTime))
                .add(targetState.acceleration.scale(0.5 * estimatedTime * estimatedTime));
        }

        return null;
    }

    /**
     * Runge-Kutta 4次法による超高精度弾道シミュレーション
     */
    private static BallisticResult simulateTrajectoryRK4(
        Vec3d startPos,
        Vec3d direction,
        Vec3d targetPos,
        int debugId
    ) {
        // 初速ベクトル
        Vec3d velocity = direction.scale(INITIAL_VELOCITY);
        Vec3d position = startPos;

        double closestDistance = Double.MAX_VALUE;
        Vec3d closestPoint = null;
        double flightTime = 0;

        for (int step = 0; step < MAX_ITERATIONS; step++) {
            double speed = velocity.length();

            if (speed < MIN_BULLET_SPEED) {
                break;
            }

            // Runge-Kutta 4次法
            RK4State k1 = calculateDerivatives(position, velocity);
            RK4State k2 = calculateDerivatives(
                position.add(k1.velocity.scale(TIME_STEP * 0.5)),
                velocity.add(k1.acceleration.scale(TIME_STEP * 0.5))
            );
            RK4State k3 = calculateDerivatives(
                position.add(k2.velocity.scale(TIME_STEP * 0.5)),
                velocity.add(k2.acceleration.scale(TIME_STEP * 0.5))
            );
            RK4State k4 = calculateDerivatives(
                position.add(k3.velocity.scale(TIME_STEP)),
                velocity.add(k3.acceleration.scale(TIME_STEP))
            );

            // 位置と速度を更新
            Vec3d positionIncrement = k1.velocity.add(k2.velocity.scale(2))
                .add(k3.velocity.scale(2)).add(k4.velocity).scale(TIME_STEP / 6.0);
            Vec3d velocityIncrement = k1.acceleration.add(k2.acceleration.scale(2))
                .add(k3.acceleration.scale(2)).add(k4.acceleration).scale(TIME_STEP / 6.0);

            position = position.add(positionIncrement);
            velocity = velocity.add(velocityIncrement);

            flightTime += TIME_STEP;

            // ターゲットまでの距離チェック
            double distance = position.distanceTo(targetPos);

            if (distance < closestDistance) {
                closestDistance = distance;
                closestPoint = position;
            }

            // 通過判定
            if (step > 20 && distance > closestDistance + 10.0) {
                break;
            }

            // 地面衝突
            if (position.y < -100) {
                break;
            }
        }

        if (closestPoint == null) {
            return null;
        }

        Vec3d missVector = targetPos.subtract(closestPoint);

        return new BallisticResult(closestDistance, missVector, flightTime, closestPoint);
    }

    /**
     * RK4法用の微分計算
     */
    private static RK4State calculateDerivatives(Vec3d position, Vec3d velocity) {
        double speed = velocity.length();

        if (speed < 0.001) {
            return new RK4State(velocity, new Vec3d(0, -GRAVITY, 0));
        }

        // 高度による空気密度
        double altitude = position.y;
        double airDensity = calculateAirDensity(altitude);

        // マッハ数による抗力係数
        double mach = speed / SPEED_OF_SOUND;
        double dragCoefficient = calculateDragCoefficient(mach);

        // 空気抵抗力
        double dragForce = 0.5 * airDensity * speed * speed * dragCoefficient * CROSS_SECTION_AREA;
        double dragAccel = dragForce / BULLET_MASS;

        // 速度方向の単位ベクトル
        Vec3d velDirection = velocity.normalize();

        // 加速度 = 空気抵抗 + 重力 + 風の影響
        Vec3d dragAccelVec = velDirection.scale(-dragAccel);
        Vec3d gravityAccelVec = new Vec3d(0, -GRAVITY, 0);

        // 風の影響（簡易モデル）
        Vec3d windEffect = globalWind.subtract(velocity).scale(0.001);

        Vec3d totalAccel = dragAccelVec.add(gravityAccelVec).add(windEffect);

        return new RK4State(velocity, totalAccel);
    }

    /**
     * 高度による空気密度計算（標準大気モデル）
     */
    private static double calculateAirDensity(double altitude) {
        if (altitude < 0) altitude = 0;

        // 簡易指数モデル: ρ = ρ₀ * exp(-altitude / 8500)
        // 8500mは大気のスケールハイト
        return SEA_LEVEL_AIR_DENSITY * Math.exp(-altitude / 8500.0);
    }

    /**
     * マッハ数による抗力係数計算
     */
    private static double calculateDragCoefficient(double mach) {
        // 実測データに基づくモデル
        if (mach < 0.8) {
            // 亜音速: ほぼ一定
            return BASE_DRAG_COEFFICIENT;
        } else if (mach < 1.2) {
            // 遷音速: 急激に増加（波動抵抗）
            double transonic = (mach - 0.8) / 0.4; // 0-1に正規化
            return BASE_DRAG_COEFFICIENT * (1.0 + 3.0 * transonic);
        } else {
            // 超音速: 緩やかに減少
            return BASE_DRAG_COEFFICIENT * (4.0 - 0.5 * (mach - 1.2));
        }
    }

    /**
     * ターゲット位置予測（ジャーク対応超高精度版）
     *
     * テイラー展開による3次予測:
     * s(t) = s₀ + v₀t + (1/2)a₀t² + (1/6)j₀t³
     *
     * この予測により、高機動ターゲットの軌道変化を
     * より正確に追跡できる
     */
    private static Vec3d predictTargetPosition(TargetState state, double time) {
        // 1次項: 速度による移動
        Vec3d positionOffset = state.velocity.scale(time);

        // 2次項: 加速度による移動
        Vec3d accelOffset = state.acceleration.scale(0.5 * time * time);

        // 3次項: ジャークによる移動（高機動ターゲット対応）
        Vec3d jerkOffset = state.jerk.scale((1.0/6.0) * time * time * time);

        return state.position.add(positionOffset).add(accelOffset).add(jerkOffset);
    }

    /**
     * 飛行時間の推定（距離と仰角から）
     * 遠距離での減速を考慮した改良版
     */
    private static double estimateFlightTime(double distance, double elevation) {
        // 距離に応じた平均速度の推定
        // 近距離: 90%、中距離: 75%、遠距離: 60%
        double avgVelocityRatio;
        if (distance < 300) {
            avgVelocityRatio = 0.90;
        } else if (distance < 600) {
            // 300-600m: 90%から75%へ線形補間
            double t = (distance - 300) / 300.0;
            avgVelocityRatio = 0.90 - t * 0.15;
        } else if (distance < 1200) {
            // 600-1200m: 75%から60%へ線形補間
            double t = (distance - 600) / 600.0;
            avgVelocityRatio = 0.75 - t * 0.15;
        } else {
            // 1200m以上: 60%から50%へ
            double t = Math.min(1.0, (distance - 1200) / 2400.0);
            avgVelocityRatio = 0.60 - t * 0.10;
        }

        double avgVelocity = INITIAL_VELOCITY * avgVelocityRatio;

        // 仰角による補正（上向きの場合、重力の影響で飛行時間が長くなる）
        double elevationFactor = 1.0 + Math.abs(Math.sin(elevation)) * 0.2;

        return (distance / avgVelocity) * elevationFactor;
    }

    /**
     * 命中確率計算
     */
    private static double calculateHitProbability(double error, double distance, double targetSpeed) {
        // 誤差要因
        double errorFactor = Math.exp(-error / 2.0); // 2ブロック誤差で36%

        // 距離要因
        double distanceFactor = 1.0 - (distance / 5000.0);
        distanceFactor = Math.max(0.1, distanceFactor);

        // 速度要因
        double speedFactor = 1.0 - (targetSpeed / 200.0);
        speedFactor = Math.max(0.3, speedFactor);

        // 総合確率
        double probability = errorFactor * distanceFactor * speedFactor;

        return Math.max(0.0, Math.min(1.0, probability));
    }

    /**
     * 最良の射撃解を選択
     */
    private static FireSolution selectBestSolution(
        List<FireSolution> candidates,
        TargetState targetState,
        double distance
    ) {
        if (candidates.isEmpty()) {
            return null;
        }

        // スコアリング
        FireSolution best = null;
        double bestScore = -1;

        for (FireSolution solution : candidates) {
            // スコア = 命中確率 - 誤差ペナルティ
            double score = solution.hitProbability - (solution.error * 0.1);

            if (score > bestScore) {
                bestScore = score;
                best = solution;
            }
        }

        return best;
    }

    /**
     * 学習データからの補正適用
     */
    private static void applyLearningCorrection(
        FireSolution solution,
        TargetState state,
        TargetTrackingData trackingData
    ) {
        String learningKey = generateLearningKey(state, trackingData);
        LearningData learningData = learningDatabase.get(learningKey);

        if (learningData != null && learningData.sampleCount > 5) {
            // 学習された補正を適用
            solution.aimPoint = solution.aimPoint.add(learningData.averageCorrection);
        }
    }

    /**
     * 射撃結果のフィードバック（学習用）
     */
    public static void recordShotResult(
        FireSolution solution,
        Vec3d actualHitPos,
        Vec3d targetPos,
        TargetState state,
        TargetTrackingData trackingData
    ) {
        if (solution == null || actualHitPos == null) {
            return;
        }

        String learningKey = generateLearningKey(state, trackingData);
        LearningData learningData = learningDatabase.get(learningKey);

        if (learningData == null) {
            learningData = new LearningData();
            learningDatabase.put(learningKey, learningData);
        }

        // 誤差ベクトルを記録
        Vec3d errorVector = targetPos.subtract(actualHitPos);
        learningData.addSample(errorVector);
    }

    /**
     * 学習キー生成
     */
    private static String generateLearningKey(TargetState state, TargetTrackingData trackingData) {
        double distance = state.position.length();
        double speed = state.velocity.length();

        // 距離と速度で分類
        int distanceBucket = (int)(distance / 500.0); // 500ブロックごと
        int speedBucket = (int)(speed / 20.0);        // 20 m/sごと

        return String.format("D%d_S%d", distanceBucket, speedBucket);
    }

    /**
     * ターゲット追跡データを取得または作成
     */
    private static TargetTrackingData getOrCreateTrackingData(Entity entity) {
        int entityId = entity.getEntityId();
        long currentTime = System.currentTimeMillis();

        // タイムアウトしたデータをクリーンアップ
        trackingDatabase.entrySet().removeIf(entry ->
            currentTime - entry.getValue().lastUpdateTime > TRACKING_TIMEOUT
        );

        TargetTrackingData data = trackingDatabase.get(entityId);
        if (data == null) {
            data = new TargetTrackingData(entityId);
            trackingDatabase.put(entityId, data);
        }

        return data;
    }

    /**
     * グローバル風を設定
     */
    public static void setGlobalWind(Vec3d wind) {
        globalWind = wind;
    }

    /**
     * ターゲットの速度を推定（レガシーメソッド）
     */
    public static Vec3d estimateTargetVelocity(Entity entity) {
        double vx = entity.motionX * 20.0;
        double vy = entity.motionY * 20.0;
        double vz = entity.motionZ * 20.0;

        return new Vec3d(vx, vy, vz);
    }

    /**
     * 射程チェック
     */
    public static boolean isWithinRange(Vec3d turretPos, Vec3d targetPos) {
        double distance = turretPos.distanceTo(targetPos);
        return distance <= 3660.0;
    }

    /**
     * 最大有効射程を計算
     */
    public static double calculateMaxRange(double elevation) {
        Vec3d direction = new Vec3d(
            Math.cos(elevation),
            Math.sin(elevation),
            0
        );

        Vec3d position = new Vec3d(0, 100, 0); // 海面上100m
        Vec3d velocity = direction.scale(INITIAL_VELOCITY);

        double maxRange = 0;

        for (int step = 0; step < MAX_ITERATIONS; step++) {
            RK4State k1 = calculateDerivatives(position, velocity);

            position = position.add(k1.velocity.scale(TIME_STEP));
            velocity = velocity.add(k1.acceleration.scale(TIME_STEP));

            if (position.y > 0) {
                maxRange = Math.sqrt(position.x * position.x + position.z * position.z);
            } else {
                break;
            }

            if (velocity.length() < MIN_BULLET_SPEED) {
                break;
            }
        }

        return maxRange;
    }

    /**
     * 診断情報を取得
     */
    public static DiagnosticInfo getDiagnosticInfo() {
        return new DiagnosticInfo(
            trackingDatabase.size(),
            learningDatabase.size(),
            globalWind
        );
    }

    // ============================================================
    // 内部クラス
    // ============================================================

    /**
     * 射撃解
     */
    public static class FireSolution {
        public Vec3d aimPoint;              // 照準点（デバッグ用）
        public double flightTime;           // 飛行時間
        public double error;                // 予測誤差
        public double hitProbability;       // 命中確率
        public String method;               // 計算手法

        // 新アーキテクチャ: 射撃角度を直接指定
        public double firePitch;            // 射撃仰角（ラジアン）
        public double fireYaw;              // 射撃方位角（ラジアン）

        // TIMING: 遅延tick測定用
        public long calculationTime;        // 計算完了時刻（ミリ秒）

        public FireSolution(Vec3d aimPoint, double flightTime, double error,
                          double hitProbability, String method) {
            this.aimPoint = aimPoint;
            this.flightTime = flightTime;
            this.error = error;
            this.hitProbability = hitProbability;
            this.method = method;
            this.firePitch = 0;
            this.fireYaw = 0;
        }

        // 新コンストラクタ: 射撃角度付き
        public FireSolution(Vec3d aimPoint, double flightTime, double error,
                          double hitProbability, String method,
                          double firePitch, double fireYaw) {
            this.aimPoint = aimPoint;
            this.flightTime = flightTime;
            this.error = error;
            this.hitProbability = hitProbability;
            this.method = method;
            this.firePitch = firePitch;
            this.fireYaw = fireYaw;
        }

        @Override
        public String toString() {
            return String.format(
                "FireSolution[method=%s, error=%.2f, time=%.2fs, prob=%.1f%%]",
                method, error, flightTime, hitProbability * 100
            );
        }
    }

    /**
     * 距離に基づいて測定ノイズを計算（距離適応型カルマンフィルター）
     *
     * 実測データに基づく距離別ノイズモデル:
     * - 近距離（500m以下）: 高精度（0.15-0.3m）
     * - 中距離（1000m）: 中精度（0.6-1.2m）
     * - 遠距離（2000m）: 低精度（2.4-4.8m）
     * - 超遠距離（3000m）: 最低精度（5.4-10.8m）
     */
    private static double calculateMeasurementNoise(double distance) {
        double noiseScale;

        if (distance < 500) {
            // 500m以下: 線形補間（1.0 → 0.5）
            noiseScale = 1.0 - (distance / 500.0) * 0.5;
        } else if (distance < 1000) {
            // 500-1000m: 線形補間（0.5 → 2.0）
            double t = (distance - 500) / 500.0;
            noiseScale = NOISE_SCALE_500M + t * (NOISE_SCALE_1000M - NOISE_SCALE_500M);
        } else if (distance < 2000) {
            // 1000-2000m: 線形補間（2.0 → 8.0）
            double t = (distance - 1000) / 1000.0;
            noiseScale = NOISE_SCALE_1000M + t * (NOISE_SCALE_2000M - NOISE_SCALE_1000M);
        } else if (distance < 3000) {
            // 2000-3000m: 線形補間（8.0 → 18.0）
            double t = (distance - 2000) / 1000.0;
            noiseScale = NOISE_SCALE_2000M + t * (NOISE_SCALE_3000M - NOISE_SCALE_2000M);
        } else {
            // 3000m以上: 最大ノイズ
            noiseScale = NOISE_SCALE_3000M;
        }

        return BASE_MEASUREMENT_NOISE * noiseScale;
    }

    /**
     * ターゲット速度に基づいてプロセスノイズを計算
     *
     * 高速・高機動ターゲットほどプロセスノイズを大きくし、
     * 予測の不確実性を反映する
     */
    private static double calculateProcessNoise(double targetSpeed, double acceleration) {
        double speedFactor = 1.0;
        double accelFactor = 1.0;

        // 速度ファクター（超音速対応）
        if (targetSpeed < SPEED_SUBSONIC) {
            // 亜音速（0-100 m/s）: 標準
            speedFactor = 1.0 + (targetSpeed / SPEED_SUBSONIC) * 0.5;
        } else if (targetSpeed < SPEED_TRANSONIC) {
            // 高速亜音速（100-340 m/s）: 中程度の不確実性
            double t = (targetSpeed - SPEED_SUBSONIC) / (SPEED_TRANSONIC - SPEED_SUBSONIC);
            speedFactor = 1.5 + t * 1.5;
        } else if (targetSpeed < SPEED_SUPERSONIC) {
            // 超音速（340-680 m/s）: 高い不確実性
            double t = (targetSpeed - SPEED_TRANSONIC) / (SPEED_SUPERSONIC - SPEED_TRANSONIC);
            speedFactor = 3.0 + t * 3.0;
        } else {
            // 極超音速（680+ m/s）: 最大不確実性
            speedFactor = 6.0 + Math.min((targetSpeed - SPEED_SUPERSONIC) / 500.0, 4.0);
        }

        // 加速度ファクター（高機動ターゲット対応）
        double accelMagnitude = Math.abs(acceleration);
        if (accelMagnitude > 50.0) {
            // 極端な機動（50+ m/s²）
            accelFactor = 3.0;
        } else if (accelMagnitude > 20.0) {
            // 高機動（20-50 m/s²）
            accelFactor = 2.0;
        } else if (accelMagnitude > 5.0) {
            // 中程度の機動（5-20 m/s²）
            accelFactor = 1.0 + (accelMagnitude - 5.0) / 15.0;
        }

        return BASE_PROCESS_NOISE * speedFactor * accelFactor;
    }

    /**
     * ターゲットを分類（将来的な拡張用）
     */
    private static String classifyTarget(double speed) {
        if (speed < SPEED_SUBSONIC) {
            return "SUBSONIC";
        } else if (speed < SPEED_TRANSONIC) {
            return "HIGH_SUBSONIC";
        } else if (speed < SPEED_SUPERSONIC) {
            return "SUPERSONIC";
        } else {
            return "HYPERSONIC";
        }
    }

    /**
     * ターゲット状態（ジャーク対応）
     */
    public static class TargetState {
        public Vec3d position;
        public Vec3d velocity;
        public Vec3d acceleration;
        public Vec3d jerk;  // 加速度変化率（超高精度予測用）

        public TargetState(Vec3d position, Vec3d velocity, Vec3d acceleration) {
            this(position, velocity, acceleration, Vec3d.ZERO);
        }

        public TargetState(Vec3d position, Vec3d velocity, Vec3d acceleration, Vec3d jerk) {
            this.position = position;
            this.velocity = velocity;
            this.acceleration = acceleration;
            this.jerk = jerk;
        }
    }

    /**
     * ターゲット追跡データ
     */
    private static class TargetTrackingData {
        int entityId;
        List<TrackingSnapshot> history;
        long lastUpdateTime;

        // カルマンフィルター状態（ジャーク予測対応）
        Vec3d position;
        Vec3d velocity;
        Vec3d acceleration;
        Vec3d jerk;  // 加速度の変化率（超高精度予測用）
        double[] covariance; // 9x9の共分散行列（簡略化）

        // タレット位置（距離計算用）
        Vec3d turretPosition;

        public TargetTrackingData(int entityId) {
            this.entityId = entityId;
            this.history = new ArrayList<>();
            this.position = Vec3d.ZERO;
            this.velocity = Vec3d.ZERO;
            this.acceleration = Vec3d.ZERO;
            this.jerk = Vec3d.ZERO;
            this.covariance = new double[9];
            Arrays.fill(covariance, 1.0);
            this.turretPosition = Vec3d.ZERO;
        }

        public void update(Vec3d newPosition, long timestamp) {
            // 履歴に追加
            history.add(new TrackingSnapshot(newPosition, timestamp));

            // 履歴サイズを制限
            if (history.size() > TRACKING_HISTORY_SIZE) {
                history.remove(0);
            }

            // カルマンフィルター更新
            if (history.size() >= 2) {
                updateKalmanFilter(newPosition, timestamp);
            } else {
                position = newPosition;
            }

            lastUpdateTime = timestamp;
        }

        /**
         * 速度情報を含めた更新（より正確な予測が可能）
         */
        public void updateWithVelocity(Vec3d newPosition, Vec3d measuredVelocity, long timestamp) {
            // 履歴に追加
            history.add(new TrackingSnapshot(newPosition, timestamp));

            // 履歴サイズを制限
            if (history.size() > TRACKING_HISTORY_SIZE) {
                history.remove(0);
            }

            // カルマンフィルター更新（速度測定値を使用）
            if (history.size() >= 2) {
                updateKalmanFilterWithVelocity(newPosition, measuredVelocity, timestamp);
            } else {
                position = newPosition;
                velocity = measuredVelocity;
            }

            lastUpdateTime = timestamp;
        }

        private void updateKalmanFilter(Vec3d measurement, long timestamp) {
            if (history.size() < 2) {
                position = measurement;
                return;
            }

            // 時間差分
            TrackingSnapshot prev = history.get(history.size() - 2);
            double dt = (timestamp - prev.timestamp) / 1000.0; // 秒に変換

            if (dt <= 0 || dt > 1.0) {
                position = measurement;
                return;
            }

            // 予測ステップ
            Vec3d predictedPos = position.add(velocity.scale(dt))
                .add(acceleration.scale(0.5 * dt * dt));
            Vec3d predictedVel = velocity.add(acceleration.scale(dt));

            // 測定による速度推定
            Vec3d measuredVel = measurement.subtract(position).scale(1.0 / dt);

            // カルマンゲイン（簡易版・固定パラメータ使用）
            double kalmanGain = BASE_PROCESS_NOISE / (BASE_PROCESS_NOISE + BASE_MEASUREMENT_NOISE);

            // 更新ステップ
            Vec3d innovation = measurement.subtract(predictedPos);
            position = predictedPos.add(innovation.scale(kalmanGain));

            Vec3d velInnovation = measuredVel.subtract(predictedVel);
            velocity = predictedVel.add(velInnovation.scale(kalmanGain * 0.5));

            // 加速度推定（差分法）
            if (history.size() >= 3) {
                TrackingSnapshot prev2 = history.get(history.size() - 3);
                double dt2 = (prev.timestamp - prev2.timestamp) / 1000.0;

                if (dt2 > 0 && dt2 < 1.0) {
                    Vec3d vel1 = prev.position.subtract(prev2.position).scale(1.0 / dt2);
                    Vec3d vel2 = measurement.subtract(prev.position).scale(1.0 / dt);
                    Vec3d newAccel = vel2.subtract(vel1).scale(1.0 / ((dt + dt2) / 2.0));

                    // 加速度のスムージング
                    acceleration = acceleration.scale(0.7).add(newAccel.scale(0.3));
                }
            }
        }

        /**
         * 速度測定値を使用した適応型カルマンフィルター更新（超高精度版）
         *
         * 新機能:
         * - 距離適応型ノイズパラメータ（2000m+対応）
         * - 超音速ターゲット対応（マッハ2+）
         * - ジャーク（加速度変化率）予測
         * - 高機動ターゲット対応
         */
        private void updateKalmanFilterWithVelocity(Vec3d measurement, Vec3d measuredVelocity, long timestamp) {
            if (history.size() < 2) {
                position = measurement;
                velocity = measuredVelocity;
                return;
            }

            // 時間差分
            TrackingSnapshot prev = history.get(history.size() - 2);
            double dt = (timestamp - prev.timestamp) / 1000.0; // 秒に変換

            if (dt <= 0 || dt > 1.0) {
                position = measurement;
                velocity = measuredVelocity;
                return;
            }

            // タレットからの距離を計算（適応型パラメータ用）
            double distance = turretPosition.length() > 0 ?
                turretPosition.distanceTo(measurement) : measurement.length();

            // 現在の速度と加速度
            double currentSpeed = measuredVelocity.length();
            double currentAccelMag = acceleration.length();

            // 距離と速度に基づいて適応型ノイズパラメータを計算
            double adaptiveMeasurementNoise = calculateMeasurementNoise(distance);
            double adaptiveProcessNoise = calculateProcessNoise(currentSpeed, currentAccelMag);

            // ジャークを考慮した予測ステップ（超高精度予測）
            // s(t) = s₀ + v₀t + (1/2)a₀t² + (1/6)j₀t³
            Vec3d predictedPos = position
                .add(velocity.scale(dt))
                .add(acceleration.scale(0.5 * dt * dt))
                .add(jerk.scale((1.0/6.0) * dt * dt * dt));

            // v(t) = v₀ + a₀t + (1/2)j₀t²
            Vec3d predictedVel = velocity
                .add(acceleration.scale(dt))
                .add(jerk.scale(0.5 * dt * dt));

            // a(t) = a₀ + j₀t
            Vec3d predictedAccel = acceleration.add(jerk.scale(dt));

            // イノベーション（測定値と予測値の差）を計算
            Vec3d positionInnovation = measurement.subtract(predictedPos);
            double innovationMagnitude = positionInnovation.length();

            // イノベーション適応型カルマンゲイン（重要: フィルター安定化の鍵）
            // 基本ゲイン
            double basePositionGain = adaptiveProcessNoise / (adaptiveProcessNoise + adaptiveMeasurementNoise);

            // イノベーションに基づくゲイン増幅（大きな誤差時は測定値をより信頼）
            double innovationBoost = 1.0;
            if (innovationMagnitude > 50.0) {
                // 巨大な誤差: 測定値を非常に強く信頼（フィルター発散防止）
                innovationBoost = 8.0;
            } else if (innovationMagnitude > 20.0) {
                // 大きな誤差: 測定値を強く信頼
                double t = (innovationMagnitude - 20.0) / 30.0;
                innovationBoost = 3.0 + t * 5.0; // 3.0 → 8.0
            } else if (innovationMagnitude > 5.0) {
                // 中程度の誤差: ゲインを適度に増加
                double t = (innovationMagnitude - 5.0) / 15.0;
                innovationBoost = 1.0 + t * 2.0; // 1.0 → 3.0
            }
            // innovationMagnitude ≤ 5.0: boost = 1.0（通常のゲイン）

            double positionGain = Math.min(basePositionGain * innovationBoost, 0.7);

            // 速度ゲインも誤差に応じて調整（超音速では測定値をより信頼）
            double velocityGain;
            if (currentSpeed < SPEED_SUBSONIC) {
                velocityGain = 0.7; // 亜音速: 標準
            } else if (currentSpeed < SPEED_TRANSONIC) {
                velocityGain = 0.8; // 高速亜音速: 高いゲイン
            } else {
                velocityGain = 0.9; // 超音速: 最高ゲイン（測定値を最も信頼）
            }

            // 大きなイノベーション時は速度ゲインも増加
            if (innovationMagnitude > 20.0) {
                velocityGain = Math.min(velocityGain * 1.3, 0.95);
            }

            // 位置の更新
            position = predictedPos.add(positionInnovation.scale(positionGain));

            // 速度の更新
            Vec3d velocityInnovation = measuredVelocity.subtract(predictedVel);
            velocity = predictedVel.add(velocityInnovation.scale(velocityGain));

            // 加速度推定（前回の加速度と新しい加速度の差分）
            Vec3d newAccel = velocity.subtract(predictedVel.subtract(acceleration.scale(dt))).scale(1.0 / dt);

            // 加速度スムージング（高速ターゲットほど新しい値を重視）
            double accelBlend = currentSpeed > SPEED_SUBSONIC ? 0.6 : 0.5;
            Vec3d prevAcceleration = acceleration;
            acceleration = acceleration.scale(1.0 - accelBlend).add(newAccel.scale(accelBlend));

            // ジャーク（加速度の変化率）推定
            if (history.size() >= 3) {
                // j = (a₁ - a₀) / dt
                Vec3d newJerk = acceleration.subtract(prevAcceleration).scale(1.0 / dt);

                // ジャークスムージング（高機動ターゲット対応）
                double jerkBlend = 0.3;
                if (currentAccelMag > 20.0) {
                    // 高機動ターゲット: より積極的に新しいジャークを採用
                    jerkBlend = 0.5;
                } else if (currentAccelMag > 5.0) {
                    // 中程度の機動
                    jerkBlend = 0.4;
                }
                jerk = jerk.scale(1.0 - jerkBlend).add(newJerk.scale(jerkBlend));
            }

            // デバッグログ（詳細版 - イノベーション適応型ゲイン表示）
            if (currentSpeed > 10.0 && timestamp % 1000 < 100) {
                String targetClass = classifyTarget(currentSpeed);
                System.out.println("[FCS-GAU8] Adaptive Tracking [" + targetClass + "] |" +
                    " Distance: " + String.format("%.0f", distance) + "m" +
                    " | Speed: " + String.format("%.1f", currentSpeed) + " m/s" +
                    " | Accel: " + String.format("%.1f", currentAccelMag) + " m/s²" +
                    " | Innovation: " + String.format("%.2f", innovationMagnitude) + "m" +
                    " | Gain: " + String.format("%.1f%%", positionGain * 100) +
                    " (×" + String.format("%.1f", innovationBoost) + ")" +
                    " | MN: " + String.format("%.2f", adaptiveMeasurementNoise) + "m");
            }
        }

        public TargetState getEstimatedState() {
            return new TargetState(position, velocity, acceleration, jerk);
        }
    }

    /**
     * 追跡スナップショット
     */
    private static class TrackingSnapshot {
        Vec3d position;
        long timestamp;

        public TrackingSnapshot(Vec3d position, long timestamp) {
            this.position = position;
            this.timestamp = timestamp;
        }
    }

    /**
     * 学習データ
     */
    private static class LearningData {
        List<Vec3d> corrections;
        Vec3d averageCorrection;
        int sampleCount;

        public LearningData() {
            this.corrections = new ArrayList<>();
            this.averageCorrection = Vec3d.ZERO;
            this.sampleCount = 0;
        }

        public void addSample(Vec3d correction) {
            corrections.add(correction);
            sampleCount++;

            // 最大サンプル数を超えたら古いものを削除
            if (corrections.size() > MAX_LEARNING_SAMPLES) {
                corrections.remove(0);
            }

            // 平均を再計算
            Vec3d sum = Vec3d.ZERO;
            for (Vec3d c : corrections) {
                sum = sum.add(c);
            }
            averageCorrection = sum.scale(1.0 / corrections.size());
        }
    }

    /**
     * 弾道シミュレーション結果
     */
    private static class BallisticResult {
        public final double closestDistance;
        public final Vec3d missVector;
        public final double flightTime;
        public final Vec3d impactPoint;

        public BallisticResult(double closestDistance, Vec3d missVector,
                             double flightTime, Vec3d impactPoint) {
            this.closestDistance = closestDistance;
            this.missVector = missVector;
            this.flightTime = flightTime;
            this.impactPoint = impactPoint;
        }
    }

    /**
     * RK4状態
     */
    private static class RK4State {
        public final Vec3d velocity;
        public final Vec3d acceleration;

        public RK4State(Vec3d velocity, Vec3d acceleration) {
            this.velocity = velocity;
            this.acceleration = acceleration;
        }
    }

    /**
     * 診断情報
     */
    public static class DiagnosticInfo {
        public final int trackedTargets;
        public final int learnedPatterns;
        public final Vec3d currentWind;

        public DiagnosticInfo(int trackedTargets, int learnedPatterns, Vec3d currentWind) {
            this.trackedTargets = trackedTargets;
            this.learnedPatterns = learnedPatterns;
            this.currentWind = currentWind;
        }

        @Override
        public String toString() {
            return String.format(
                "GAU-8 FCS: Tracking %d targets, %d learned patterns, Wind: %.1f m/s",
                trackedTargets, learnedPatterns, currentWind.length()
            );
        }
    }
}
