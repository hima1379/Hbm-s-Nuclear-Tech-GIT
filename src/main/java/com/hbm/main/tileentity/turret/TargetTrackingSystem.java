package com.hbm.main.tileentity.turret;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Phalanx CIWSスタイルのターゲット追跡システム
 *
 * 機能:
 * - クローズドループ追跡: ターゲットの位置履歴から速度と加速度を推定
 * - 適応的予測: ターゲットの機動性を学習して予測精度を向上
 * - リアルタイム補正: 複数フレームのデータで予測を継続的に改善
 *
 * Phalanx CIWSとの類似点:
 * - Block 1Aの適応フィルタリング: 機動するターゲットに対応
 * - 高次言語コンピュータ(HOLC): より高度な予測アルゴリズム
 * - リアルタイム追跡とリード計算
 */
public class TargetTrackingSystem {

    /**
     * ターゲット追跡データ
     */
    private static class TrackingData {
        Vec3d lastPosition;
        Vec3d lastVelocity;
        Vec3d lastAcceleration;
        long lastUpdateTime;
        int updateCount;
        double confidence;  // 予測の信頼度 (0.0-1.0)

        TrackingData(Vec3d position, long time) {
            this.lastPosition = position;
            this.lastVelocity = new Vec3d(0, 0, 0);
            this.lastAcceleration = new Vec3d(0, 0, 0);
            this.lastUpdateTime = time;
            this.updateCount = 1;
            this.confidence = 0.1;  // 初期は低い信頼度
        }
    }

    // ターゲットごとの追跡データ
    private Map<Integer, TrackingData> trackingDatabase = new HashMap<>();

    // 最大追跡履歴（古いエントリを削除）
    private static final int MAX_TRACKING_ENTRIES = 100;

    // 信頼度の閾値
    private static final double MIN_CONFIDENCE_FOR_ACCELERATION = 0.3;
    private static final double MAX_CONFIDENCE = 1.0;

    /**
     * ターゲットを更新し、予測位置を計算
     *
     * @param entity ターゲットエンティティ
     * @param currentTime 現在時刻 (tick)
     * @param predictionTime 予測時間 (秒)
     * @return 予測位置
     */
    public Vec3d updateAndPredict(Entity entity, long currentTime, double predictionTime) {
        Vec3d currentPos = new Vec3d(entity.posX, entity.posY + entity.height * 0.5, entity.posZ);
        int entityId = entity.getEntityId();

        TrackingData data = trackingDatabase.get(entityId);

        if (data == null) {
            // 新しいターゲット - 追跡データを初期化
            data = new TrackingData(currentPos, currentTime);
            trackingDatabase.put(entityId, data);

            // データベースが大きくなりすぎたら古いエントリを削除
            if (trackingDatabase.size() > MAX_TRACKING_ENTRIES) {
                cleanupOldEntries(currentTime);
            }

            // 初回は現在位置を返す
            return currentPos;
        }

        // 時間差分を計算 (秒)
        double deltaTime = (currentTime - data.lastUpdateTime) / 20.0;

        if (deltaTime < 0.001) {
            // 時間が経過していない - 前回の予測を使用
            return predictPosition(data, predictionTime);
        }

        // 速度を計算
        Vec3d displacement = currentPos.subtract(data.lastPosition);
        Vec3d currentVelocity = displacement.scale(1.0 / deltaTime);

        // 加速度を計算
        Vec3d velocityChange = currentVelocity.subtract(data.lastVelocity);
        Vec3d currentAcceleration = velocityChange.scale(1.0 / deltaTime);

        // 適応フィルタリング: 急激な変化を検出
        double velocityChangeRate = velocityChange.length() / Math.max(data.lastVelocity.length(), 1.0);

        // 信頼度を更新
        data.updateCount++;
        data.confidence = Math.min(MAX_CONFIDENCE, data.confidence + 0.1);

        // 急激な機動が検出された場合、信頼度を下げて再学習
        if (velocityChangeRate > 0.5 && data.updateCount > 5) {
            data.confidence = Math.max(0.3, data.confidence * 0.7);
        }

        // データを更新
        data.lastPosition = currentPos;
        data.lastVelocity = currentVelocity;
        data.lastAcceleration = currentAcceleration;
        data.lastUpdateTime = currentTime;

        // 予測位置を計算
        return predictPosition(data, predictionTime);
    }

    /**
     * 追跡データから予測位置を計算
     *
     * @param data 追跡データ
     * @param predictionTime 予測時間 (秒)
     * @return 予測位置
     */
    private Vec3d predictPosition(TrackingData data, double predictionTime) {
        Vec3d predictedPos = data.lastPosition;

        // 速度ベースの予測（常に適用）
        predictedPos = predictedPos.add(data.lastVelocity.scale(predictionTime));

        // 加速度ベースの予測（信頼度が十分な場合のみ）
        if (data.confidence >= MIN_CONFIDENCE_FOR_ACCELERATION) {
            // 加速度の寄与を制限（過剰な予測を防ぐ）
            double accelWeight = Math.min(data.confidence, 0.8);
            Vec3d accelContribution = data.lastAcceleration.scale(0.5 * predictionTime * predictionTime * accelWeight);

            // 加速度の寄与が大きすぎる場合は制限
            double accelMagnitude = accelContribution.length();
            if (accelMagnitude > 50.0) {
                accelContribution = accelContribution.normalize().scale(50.0);
            }

            predictedPos = predictedPos.add(accelContribution);
        }

        return predictedPos;
    }

    /**
     * ターゲットの現在速度を取得
     *
     * @param entity ターゲットエンティティ
     * @return 速度ベクトル (m/s)、追跡データがない場合はnull
     */
    public Vec3d getTrackedVelocity(Entity entity) {
        TrackingData data = trackingDatabase.get(entity.getEntityId());
        return data != null ? data.lastVelocity : null;
    }

    /**
     * ターゲットの現在加速度を取得
     *
     * @param entity ターゲットエンティティ
     * @return 加速度ベクトル (m/s²)、追跡データがない場合はnull
     */
    public Vec3d getTrackedAcceleration(Entity entity) {
        TrackingData data = trackingDatabase.get(entity.getEntityId());
        return data != null ? data.lastAcceleration : null;
    }

    /**
     * ターゲットの追跡信頼度を取得
     *
     * @param entity ターゲットエンティティ
     * @return 信頼度 (0.0-1.0)、追跡データがない場合は0.0
     */
    public double getTrackingConfidence(Entity entity) {
        TrackingData data = trackingDatabase.get(entity.getEntityId());
        return data != null ? data.confidence : 0.0;
    }

    /**
     * ターゲットの追跡をリセット
     *
     * @param entity ターゲットエンティティ
     */
    public void resetTracking(Entity entity) {
        trackingDatabase.remove(entity.getEntityId());
    }

    /**
     * 古い追跡エントリを削除
     *
     * @param currentTime 現在時刻 (tick)
     */
    private void cleanupOldEntries(long currentTime) {
        // 30秒以上更新されていないエントリを削除
        long expireTime = currentTime - (30 * 20);
        trackingDatabase.entrySet().removeIf(entry -> entry.getValue().lastUpdateTime < expireTime);
    }

    /**
     * すべての追跡データをクリア
     */
    public void clearAll() {
        trackingDatabase.clear();
    }
}
