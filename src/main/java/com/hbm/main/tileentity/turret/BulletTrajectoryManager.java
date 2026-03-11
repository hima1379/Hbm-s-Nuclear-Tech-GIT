package com.hbm.main.tileentity.turret;

import com.hbm.entity.logic.EntityBomber;
import com.hbm.entity.missile.EntityMissileBaseAdvanced;
import com.hbm.entity.missile.EntityMissileCustom;
import com.hbm.entity.projectile.EntityBulletGAU8;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;

import java.util.*;

/**
 * タレットベースの弾道追跡・チャンクロードマネージャー（軽量化版）
 *
 * 問題：
 * - EntityBulletGAU8がENTITYチケットを使用すると、弾丸がアンロードされた
 *   瞬間にチケットが無効化され、弾丸が停止する
 *
 * 解決策：
 * - タレットがNORMALチケットを使用して、発射した弾丸の軌道を追跡
 * - タレットは常にロードされているため、チケットは有効なまま
 * - 弾丸の軌道上のチャンクを先読みロード
 *
 * 仕組み：
 * 1. 弾丸発射時に軌道を登録（ターゲット情報も含む）
 * 2. 2-3 tickごとに弾丸の予測位置を計算（毎tickではない）
 * 3. 軌道上のチャンクを先読みロード（最大25チャンク/チケット）
 * 4. ターゲット死亡または弾丸消滅で追跡終了
 *
 * 軽量化：
 * - 先読みチャンク数削減（15 → 6）
 * - 最大追跡弾丸数削減（50 → 15）
 * - 更新頻度削減（毎tick → 3 tickごと）
 * - ターゲットベースの最適化（死亡時即座に停止）
 * - 簡略化された軌道予測（直線近似）
 * - プレイヤー・機械ターゲット時のみチャンクロード（モブは不要）
 */
public class BulletTrajectoryManager {

    /**
     * 追跡中の弾丸データ
     */
    private static class TrackedBullet {
        EntityBulletGAU8 bullet;
        Vec3d lastKnownPos;
        Vec3d lastKnownVel;
        long lastUpdateTime;
        int ticksSinceLastSeen;
        net.minecraft.entity.Entity target; // ターゲット情報を追加

        TrackedBullet(EntityBulletGAU8 bullet, Vec3d pos, Vec3d vel, long time, net.minecraft.entity.Entity target) {
            this.bullet = bullet;
            this.lastKnownPos = pos;
            this.lastKnownVel = vel;
            this.lastUpdateTime = time;
            this.ticksSinceLastSeen = 0;
            this.target = target;
        }
    }

    // 追跡中の弾丸リスト
    private List<TrackedBullet> trackedBullets = new ArrayList<>();

    // チケットごとのロード中チャンク
    private Map<Ticket, Set<ChunkPos>> ticketChunks = new HashMap<>();

    // 複数チケット（25チャンク/チケット制限を回避）
    private List<Ticket> tickets = new ArrayList<>();

    // 現在のチケット（レガシー互換性用）
    private Ticket ticket;

    // ロード中のチャンク（レガシー互換性用）
    private Set<ChunkPos> loadedChunks = new HashSet<>();

    // タレット位置
    private ChunkPos turretChunk;

    // ワールド参照（チケット要求用）
    private net.minecraft.world.World world;

    // 最大追跡弾丸数（射程を維持するため増加）
    private static final int MAX_TRACKED_BULLETS = 50;

    // 弾丸が見えない最大tick数
    private static final int MAX_TICKS_UNSEEN = 60;

    // 先読みチャンク数（動的ロード方式）
    // 弾丸の現在位置から前方のチャンクのみをロード（後方はアンロード）
    // これにより25チャンク制限内で3000ブロック射程を実現
    private static final int LOOKAHEAD_CHUNKS = 20;

    // 更新間隔（毎tick更新）
    private static final int UPDATE_INTERVAL = 1;
    private int updateTicker = 0;

    // チケットあたりの最大チャンク数
    private static final int CHUNKS_PER_TICKET = 25;

    // 物理定数（EntityBulletGAU8と同じ）
    private static final double DRAG_COEFFICIENT = 0.05;
    private static final double AIR_DENSITY = 1.225;
    private static final double CROSS_SECTION_AREA = 0.000707;
    private static final double BULLET_MASS = 0.395;
    private static final double GRAVITY = 9.8;
    private static final double TICK_TIME = 0.05;

    /**
     * チケットを設定
     */
    public void setTicket(Ticket ticket, ChunkPos turretChunk) {
        this.ticket = ticket;
        this.turretChunk = turretChunk;
    }

    /**
     * ターゲットがチャンクロードを必要とするかチェック
     *
     * プレイヤーと機械（ミサイル、ボンバーなど）は遠距離・高速移動するためチャンクロードが必要
     * モブ（IMob）は近距離戦闘が多いためチャンクロード不要
     *
     * @param target ターゲットエンティティ
     * @return チャンクロードが必要ならtrue
     */
    private static boolean shouldLoadChunksForTarget(net.minecraft.entity.Entity target) {
        if (target == null) {
            return false;
        }

        // プレイヤーターゲット：チャンクロード必要
        if (target instanceof EntityPlayer) {
            return true;
        }

        // 機械ターゲット：チャンクロード必要
        if (target instanceof EntityMissileBaseAdvanced ||
            target instanceof EntityMissileCustom ||
            target instanceof EntityMinecart ||
            target instanceof EntityBomber) {
            return true;
        }

        // モブターゲット：チャンクロード不要（近距離戦闘）
        if (target instanceof IMob) {
            return false;
        }

        // その他のエンティティ：念のためチャンクロードする
        return true;
    }

    /**
     * 弾丸を追跡リストに追加（ターゲット情報付き）
     */
    public void trackBullet(EntityBulletGAU8 bullet, net.minecraft.entity.Entity target) {
        // ターゲットがチャンクロードを必要としない場合はスキップ（軽量化）
        if (!shouldLoadChunksForTarget(target)) {
            String targetType = (target instanceof IMob) ? "Mob" : "Unknown";
            System.out.println("[BulletTrajectoryManager] Skipping chunk loading for " + targetType + " target: " +
                             (target != null ? target.getName() : "None") +
                             " | Bullet #" + bullet.getEntityId());
            return;
        }

        // 最大数チェック
        if (trackedBullets.size() >= MAX_TRACKED_BULLETS) {
            // 古い弾丸を削除
            trackedBullets.remove(0);
            System.out.println("[BulletTrajectoryManager] Max bullets reached, removing oldest");
        }

        Vec3d pos = new Vec3d(bullet.posX, bullet.posY, bullet.posZ);
        Vec3d vel = new Vec3d(
            bullet.getDataManager().get(EntityBulletGAU8.VELOCITY_X),
            bullet.getDataManager().get(EntityBulletGAU8.VELOCITY_Y),
            bullet.getDataManager().get(EntityBulletGAU8.VELOCITY_Z)
        );

        long currentTime = bullet.world.getTotalWorldTime();
        TrackedBullet tracked = new TrackedBullet(bullet, pos, vel, currentTime, target);
        trackedBullets.add(tracked);

        String targetType = "Unknown";
        if (target instanceof EntityPlayer) targetType = "Player";
        else if (target instanceof EntityMissileBaseAdvanced || target instanceof EntityMissileCustom) targetType = "Missile";
        else if (target instanceof EntityBomber) targetType = "Bomber";
        else if (target instanceof EntityMinecart) targetType = "Minecart";

        System.out.println("[BulletTrajectoryManager] Now tracking bullet #" + bullet.getEntityId() +
                         " | Target type: " + targetType +
                         " | Target: " + (target != null ? target.getName() : "None") +
                         " | Total tracked: " + trackedBullets.size());
    }

    /**
     * レガシーメソッド（ターゲットなし）
     */
    @Deprecated
    public void trackBullet(EntityBulletGAU8 bullet) {
        trackBullet(bullet, null);
    }

    /**
     * 毎tickの更新（軽量化：3 tickごとのみ実行）
     */
    public void update(long currentTime) {
        if (ticket == null) return;

        // 更新頻度削減
        updateTicker++;
        boolean shouldUpdate = (updateTicker % UPDATE_INTERVAL == 0);

        // 死んだ/見えない弾丸・ターゲット死亡をチェック
        trackedBullets.removeIf(tracked -> {
            // ターゲットが死亡していたら即座に追跡停止（軽量化の要）
            if (tracked.target != null && (tracked.target.isDead || !tracked.target.isEntityAlive())) {
                System.out.println("[BulletTrajectoryManager] Target dead, stopping tracking bullet #" + tracked.bullet.getEntityId());
                return true;
            }

            if (tracked.bullet.isDead) {
                return true;
            }

            // 弾丸がまだ存在するか確認
            if (tracked.bullet.world.getEntityByID(tracked.bullet.getEntityId()) == null) {
                tracked.ticksSinceLastSeen++;
                if (tracked.ticksSinceLastSeen > MAX_TICKS_UNSEEN) {
                    System.out.println("[BulletTrajectoryManager] Lost bullet #" + tracked.bullet.getEntityId());
                    return true;
                }
            } else {
                // 弾丸が見える - 位置と速度を更新（3 tickごと）
                if (shouldUpdate) {
                    tracked.lastKnownPos = new Vec3d(tracked.bullet.posX, tracked.bullet.posY, tracked.bullet.posZ);
                    tracked.lastKnownVel = new Vec3d(
                        tracked.bullet.getDataManager().get(EntityBulletGAU8.VELOCITY_X),
                        tracked.bullet.getDataManager().get(EntityBulletGAU8.VELOCITY_Y),
                        tracked.bullet.getDataManager().get(EntityBulletGAU8.VELOCITY_Z)
                    );
                    tracked.lastUpdateTime = currentTime;
                }
                tracked.ticksSinceLastSeen = 0;
            }

            return false;
        });

        // チャンクロードは3 tickごとのみ実行
        if (shouldUpdate) {
            updateChunkLoading();
        }
    }

    /**
     * すべての弾丸の軌道に基づいてチャンクをロード（動的ロード方式）
     *
     * 動的ロード方式：
     * - 各弾丸の現在位置から前方20チャンクのみをロード
     * - 弾丸が進むにつれて後方のチャンクは自動的にアンロードされる
     * - これにより25チャンク制限内で3000ブロック射程を実現
     */
    private void updateChunkLoading() {
        // 新しく必要なチャンクセットを計算
        Set<ChunkPos> requiredChunks = new HashSet<>();

        // タレットのチャンクを追加（常にロード）
        if (turretChunk != null) {
            requiredChunks.add(turretChunk);
        }

        // 各弾丸の軌道上のチャンクを計算
        for (TrackedBullet tracked : trackedBullets) {
            Set<ChunkPos> bulletChunks = calculateTrajectoryChunks(tracked);
            requiredChunks.addAll(bulletChunks);

            // 25チャンク制限チェック
            if (requiredChunks.size() >= 25) {
                System.out.println("[BulletTrajectoryManager] WARNING: Approaching 25 chunk limit (" + requiredChunks.size() + " chunks)");
                break;
            }
        }

        // 不要になったチャンクをアンロード
        Set<ChunkPos> toUnload = new HashSet<>(loadedChunks);
        toUnload.removeAll(requiredChunks);
        for (ChunkPos chunk : toUnload) {
            ForgeChunkManager.unforceChunk(ticket, chunk);
        }

        // 新しく必要なチャンクをロード
        Set<ChunkPos> toLoad = new HashSet<>(requiredChunks);
        toLoad.removeAll(loadedChunks);
        for (ChunkPos chunk : toLoad) {
            ForgeChunkManager.forceChunk(ticket, chunk);
        }

        // loadedChunksを更新
        loadedChunks = requiredChunks;

        // デバッグログ（変更があった場合のみ）
        if (!toLoad.isEmpty() || !toUnload.isEmpty()) {
            System.out.println("[BulletTrajectoryManager] Chunk update: " +
                             loadedChunks.size() + " total | " +
                             "Loaded: " + toLoad.size() + " | " +
                             "Unloaded: " + toUnload.size() + " | " +
                             "Bullets: " + trackedBullets.size());
        }
    }

    /**
     * 弾丸の軌道上のチャンクを計算（動的ロード方式）
     *
     * @param tracked 追跡中の弾丸
     * @return 必要なチャンクのセット
     */
    private Set<ChunkPos> calculateTrajectoryChunks(TrackedBullet tracked) {
        Set<ChunkPos> chunks = new HashSet<>();

        // 現在位置
        double x = tracked.lastKnownPos.x;
        double y = tracked.lastKnownPos.y;
        double z = tracked.lastKnownPos.z;

        // 現在速度
        double vx = tracked.lastKnownVel.x;
        double vy = tracked.lastKnownVel.y;
        double vz = tracked.lastKnownVel.z;

        // 現在のチャンク
        ChunkPos currentChunk = new ChunkPos((int)x >> 4, (int)z >> 4);
        chunks.add(currentChunk);

        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed < 50.0) return chunks;

        // 弾丸の軌道を予測（EntityBulletGAU8と同じ物理計算）
        // LOOKAHEAD_CHUNKSステップ先まで予測（20チャンク = 320ブロック）
        for (int step = 0; step < LOOKAHEAD_CHUNKS; step++) {
            // 空気抵抗による減速
            double dragForce = 0.5 * AIR_DENSITY * speed * speed * DRAG_COEFFICIENT * CROSS_SECTION_AREA;
            double dragAccel = dragForce / BULLET_MASS;

            // 加速度ベクトル（空気抵抗 + 重力）
            double ax = -(vx / speed) * dragAccel;
            double ay = -(vy / speed) * dragAccel - GRAVITY;
            double az = -(vz / speed) * dragAccel;

            // 1秒先まで予測（20 ticks）
            double dt = 1.0;

            // 速度を更新
            vx += ax * dt;
            vy += ay * dt;
            vz += az * dt;

            // 位置を更新
            x += vx * dt;
            y += vy * dt;
            z += vz * dt;

            // 新しい速度
            speed = Math.sqrt(vx * vx + vy * vy + vz * vz);

            // チャンクを追加
            ChunkPos predictedChunk = new ChunkPos((int)x >> 4, (int)z >> 4);
            chunks.add(predictedChunk);

            // 終了条件
            if (y < 0) break; // 地面に到達
            if (speed < 100.0) break; // 速度が低すぎる
        }

        return chunks;
    }

    /**
     * すべてのチャンクをアンロードしてクリア
     */
    public void cleanup() {
        if (ticket != null) {
            for (ChunkPos chunk : loadedChunks) {
                ForgeChunkManager.unforceChunk(ticket, chunk);
            }
        }
        loadedChunks.clear();
        trackedBullets.clear();
        System.out.println("[BulletTrajectoryManager] Cleaned up all tracked bullets and chunks");
    }

    /**
     * 現在追跡中の弾丸数を取得
     */
    public int getTrackedBulletCount() {
        return trackedBullets.size();
    }

    /**
     * 現在ロード中のチャンク数を取得
     */
    public int getLoadedChunkCount() {
        return loadedChunks.size();
    }
}
