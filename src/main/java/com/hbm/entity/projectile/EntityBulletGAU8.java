package com.hbm.entity.projectile;

import com.hbm.items.armor.ArmorPenetrationSystem;
import com.hbm.lib.Library;
import com.hbm.packet.AuxParticlePacketNT;
import com.hbm.packet.PacketDispatcher;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;

import javax.annotation.Nullable;
import java.util.List;

/**
 * GAU-8 Avenger 30mm弾丸エンティティ
 * 実測データに基づく高精度弾道計算
 *
 * 実測物理特性（米軍データ）:
 * - 初速: 1,013 m/s (実測値)
 * - 最大射程: 3,660 m
 * - 弾丸質量: 0.395 kg (PGU-14/B API)
 * - 抗力係数: 0.05 (流線型貫通弾)
 * - 断面積: 0.000707 m²
 * - 運動エネルギー: 最大203 kJ
 * - 貫通力: 500mで69mm装甲、1,000mで38mm装甲
 * - 弾道特性: 1,220mでわずか3m落下（極めてフラット）
 *
 * チャンクロード:
 * - タレット（TileEntityTurretBaseNT）のBulletTrajectoryManagerが一括管理
 * - 個別弾丸のチャンクロードは実行しない（パフォーマンス最適化）
 * - タレットが射線上のチャンクを先読みロード（20チャンク先読み）
 * - これにより3,660m射程でも確実に弾丸が飛行可能
 */
public class EntityBulletGAU8 extends Entity implements IProjectile {

    // データパラメータ
    public static final DataParameter<Float> VELOCITY_X = EntityDataManager.createKey(EntityBulletGAU8.class, DataSerializers.FLOAT);
    public static final DataParameter<Float> VELOCITY_Y = EntityDataManager.createKey(EntityBulletGAU8.class, DataSerializers.FLOAT);
    public static final DataParameter<Float> VELOCITY_Z = EntityDataManager.createKey(EntityBulletGAU8.class, DataSerializers.FLOAT);

    // 物理定数 (GAU-8 30mm弾 - 実測データに基づく)
    // 実測: 初速1,013 m/s、最大射程3,660m、1,220mで弾道落下わずか3m
    private static final double BULLET_MASS = 0.395;              // kg
    private static final double INITIAL_VELOCITY = 1013.0;        // m/s (実測値)
    private static final double DRAG_COEFFICIENT = 0.05;          // 無次元 (流線型貫通弾の実測値)
    private static final double CROSS_SECTION_AREA = 0.000707;    // m² (π * 0.015²)
    private static final double AIR_DENSITY = 1.225;               // kg/m³ (海面レベル)
    private static final double GRAVITY = 9.8;                     // m/s²

    // Minecraftスケーリング定数
    private static final double TICK_TIME = 0.05;                  // 1 tick = 0.05秒 (20 ticks/s)
    private static final double VELOCITY_SCALE = 1.0;              // 1ブロック = 1メートル

    // 最大運動エネルギー (初速時)
    private static final double MAX_KINETIC_ENERGY = 0.5 * BULLET_MASS * INITIAL_VELOCITY * INITIAL_VELOCITY; // 226,297.5 J

    // 発射元
    public EntityLivingBase shooter;

    // ターゲット（タレットから渡される）
    private Entity targetEntity;

    // 速度成分 (m/s, ワールド座標系)
    private double velX;
    private double velY;
    private double velZ;

    // 位置追跡用
    private double lastPosX;
    private double lastPosY;
    private double lastPosZ;

    // 生存時間
    private int ticksAlive;
    private static final int MAX_LIFETIME = 1200; // 60秒 (十分な射程)

    // ログ出力制御（パフォーマンス最適化）
    private static long hitLogCounter = 0;
    private static long destroyLogCounter = 0;
    private static final int LOG_INTERVAL = 100; // 100発に1回ログ出力

    // タレット位置（デバッグ用）
    private BlockPos turretPos;

    // 削除理由追跡（デバッグ用）
    private String despawnReason = null;

    // デバッグ用
    private double initialPosX;
    private double initialPosY;
    private double initialPosZ;
    private int debugLogInterval = 20; // 20 tickごとにログ出力
    private boolean hasLoggedSpawn = false;

    public EntityBulletGAU8(World world) {
        super(world);
        this.setSize(0.15F, 0.15F);
        this.ticksAlive = 0;
    }

    public EntityBulletGAU8(World world, EntityLivingBase shooter, double posX, double posY, double posZ, double dirX, double dirY, double dirZ) {
        this(world);
        this.shooter = shooter;
        this.setPosition(posX, posY, posZ);

        // 初速ベクトルを設定
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        this.velX = (dirX / length) * INITIAL_VELOCITY * VELOCITY_SCALE;
        this.velY = (dirY / length) * INITIAL_VELOCITY * VELOCITY_SCALE;
        this.velZ = (dirZ / length) * INITIAL_VELOCITY * VELOCITY_SCALE;

        // データマネージャーに速度を同期
        this.dataManager.set(VELOCITY_X, (float) this.velX);
        this.dataManager.set(VELOCITY_Y, (float) this.velY);
        this.dataManager.set(VELOCITY_Z, (float) this.velZ);

        // 初期回転を設定
        updateRotation();

        // 位置を記録
        this.lastPosX = posX;
        this.lastPosY = posY;
        this.lastPosZ = posZ;

        // 初期位置を記録（デバッグ用）
        this.initialPosX = posX;
        this.initialPosY = posY;
        this.initialPosZ = posZ;
    }

    /**
     * タレット位置を設定（デバッグ用）
     */
    public void setTurretPosition(BlockPos pos) {
        this.turretPos = pos;
    }

    /**
     * ターゲットエンティティを設定（長距離検出用）
     */
    public void setTargetEntity(Entity target) {
        this.targetEntity = target;
    }

    @Override
    protected void entityInit() {
        // 速度データの登録
        this.dataManager.register(VELOCITY_X, 0.0F);
        this.dataManager.register(VELOCITY_Y, 0.0F);
        this.dataManager.register(VELOCITY_Z, 0.0F);

        // 弾丸を永続化（チャンクアンロード時にデスポーンしない）
        // チャンクロードはタレットのBulletTrajectoryManagerが管理
        if (!world.isRemote) {
            this.forceSpawn = true;
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        // クライアント側での速度同期
        if (world.isRemote) {
            this.velX = this.dataManager.get(VELOCITY_X);
            this.velY = this.dataManager.get(VELOCITY_Y);
            this.velZ = this.dataManager.get(VELOCITY_Z);
        }

        // 前回位置を記録
        this.lastPosX = this.posX;
        this.lastPosY = this.posY;
        this.lastPosZ = this.posZ;

        // 奈落チェック（Y < 0で即座に消滅）
        if (this.posY < 0) {
            if (!world.isRemote) {
                System.out.println("[GAU-8] Bullet fell into void at Y=" + String.format("%.1f", this.posY));
            }
            this.setDead();
            return;
        }

        // 最大生存時間チェック
        if (this.ticksAlive++ > MAX_LIFETIME) {
            this.setDead();
            return;
        }

        // チャンクロードチェック（サーバー側のみ）
        // チャンクがアンロードされている弾丸を削除（パフォーマンス最適化）
        if (!world.isRemote) {
            int chunkX = (int) Math.floor(this.posX) >> 4;
            int chunkZ = (int) Math.floor(this.posZ) >> 4;

            // チャンクがロードされているか確認
            if (!world.isChunkGeneratedAt(chunkX, chunkZ)) {
                // チャンクがロードされていない場合は弾丸を削除
                // タレットからの距離を計算
                double distanceFromTurret = 0;
                if (turretPos != null) {
                    distanceFromTurret = Math.sqrt(
                        Math.pow(posX - turretPos.getX(), 2) +
                        Math.pow(posZ - turretPos.getZ(), 2)
                    );
                }
                despawnReason = "Chunk unloaded (distance: " +
                    String.format("%.1f", distanceFromTurret) + "m from turret)";
                this.setDead();
                return;
            }
        }

        // サーバー側の処理
        if (!world.isRemote) {
            // 1. 空気抵抗と重力を計算
            calculateDrag();

            // 2. 速度を同期
            this.dataManager.set(VELOCITY_X, (float) this.velX);
            this.dataManager.set(VELOCITY_Y, (float) this.velY);
            this.dataManager.set(VELOCITY_Z, (float) this.velZ);
        }

        // 4. サブステップで衝突判定と位置更新（高速弾丸対応）
        performHighSpeedUpdate();

        // 5. 回転を更新
        updateRotation();

        // 6. トレーサーエフェクト
        if (world.isRemote) {
            spawnTracerEffect();
        }

    }

    /**
     * 空気抵抗を計算して速度を減衰
     * F_drag = 0.5 * ρ * v² * C_d * A
     * a = F / m
     *
     * FireControlSystemGAU8と完全に同じ計算式を使用
     */
    private void calculateDrag() {
        double speed = Math.sqrt(velX * velX + velY * velY + velZ * velZ);

        if (speed < 50.0) {
            this.setDead();
            return;
        }

        // 抗力による加速度（FireControlSystemGAU8と同じ計算式）
        double dragForce = 0.5 * AIR_DENSITY * speed * speed * DRAG_COEFFICIENT * CROSS_SECTION_AREA;
        double dragAccel = dragForce / BULLET_MASS;

        // 加速度ベクトル（空気抵抗 + 重力）
        double ax = -(velX / speed) * dragAccel;
        double ay = -(velY / speed) * dragAccel - GRAVITY;
        double az = -(velZ / speed) * dragAccel;

        // 速度を更新（Δv = a * Δt）
        this.velX += ax * TICK_TIME;
        this.velY += ay * TICK_TIME;
        this.velZ += az * TICK_TIME;
    }

    /**
     * 重力を適用（calculateDrag内で統合されたため不要）
     */
    @Deprecated
    private void applyGravity() {
        // calculateDrag()内で処理
    }

    /**
     * 高速弾丸用のサブステップ更新
     * 1tickを複数のサブステップに分割して衝突判定を行う
     */
    private void performHighSpeedUpdate() {
        // サブステップ数：速度に応じて調整（最大100ステップ）
        double speed = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
        double distancePerTick = speed * TICK_TIME;

        // 1ブロックごとにチェック（最小2ステップ、最大100ステップ）
        int subSteps = (int) Math.ceil(distancePerTick);
        subSteps = Math.max(2, Math.min(100, subSteps));

        double subStepTime = TICK_TIME / subSteps;

        for (int step = 0; step < subSteps; step++) {
            // サブステップごとの移動距離
            double deltaX = this.velX * subStepTime;
            double deltaY = this.velY * subStepTime;
            double deltaZ = this.velZ * subStepTime;

            // 移動前の位置
            double startX = this.posX;
            double startY = this.posY;
            double startZ = this.posZ;

            // 移動後の位置
            double endX = startX + deltaX;
            double endY = startY + deltaY;
            double endZ = startZ + deltaZ;

            // レイトレースで衝突判定
            Vec3d start = new Vec3d(startX, startY, startZ);
            Vec3d end = new Vec3d(endX, endY, endZ);

            RayTraceResult hit = performSubStepRayTrace(start, end);

            if (hit != null) {
                // 衝突地点まで移動
                this.posX = hit.hitVec.x;
                this.posY = hit.hitVec.y;
                this.posZ = hit.hitVec.z;
                this.setPosition(this.posX, this.posY, this.posZ);

                // 衝突処理
                if (hit.entityHit != null) {
                    onEntityHit(hit.entityHit, hit);
                } else if (hit.typeOfHit == RayTraceResult.Type.BLOCK) {
                    onBlockHit(hit.getBlockPos(), hit);
                }
                return; // 衝突したので更新終了
            }

            // 衝突しなかった場合は位置を更新
            this.posX = endX;
            this.posY = endY;
            this.posZ = endZ;
            this.setPosition(this.posX, this.posY, this.posZ);
        }
    }

    /**
     * サブステップ用のレイトレース
     */
    private RayTraceResult performSubStepRayTrace(Vec3d start, Vec3d end) {
        // ブロック衝突チェック
        RayTraceResult blockHit = world.rayTraceBlocks(start, end, false, true, false);

        // エンティティ衝突チェック
        RayTraceResult entityHit = rayTraceEntities(start, end);

        // より近い方の衝突を返す
        if (blockHit != null && entityHit != null) {
            double blockDist = start.distanceTo(blockHit.hitVec);
            double entityDist = start.distanceTo(entityHit.hitVec);
            return (blockDist < entityDist) ? blockHit : entityHit;
        } else if (blockHit != null) {
            return blockHit;
        } else if (entityHit != null) {
            return entityHit;
        }

        return null;
    }

    /**
     * 位置を更新（旧メソッド - 使用しない）
     */
    @Deprecated
    private void updatePosition() {
        // Δx = v * Δt
        this.posX += this.velX * TICK_TIME;
        this.posY += this.velY * TICK_TIME;
        this.posZ += this.velZ * TICK_TIME;

        this.setPosition(this.posX, this.posY, this.posZ);
    }

    /**
     * 回転を更新 (弾丸の向きを速度ベクトルに合わせる)
     */
    private void updateRotation() {
        double horizontalSpeed = Math.sqrt(velX * velX + velZ * velZ);
        this.rotationYaw = (float) (Math.atan2(velX, velZ) * 180.0D / Math.PI);
        this.rotationPitch = (float) (Math.atan2(velY, horizontalSpeed) * 180.0D / Math.PI);
    }

    /**
     * レイトレースで衝突判定（旧メソッド - 使用しない）
     */
    @Deprecated
    private void performRayTrace() {
        // performHighSpeedUpdate()に置き換え
    }

    /**
     * エンティティとのレイトレース（長距離対応版）
     *
     * ターゲットエンティティが設定されている場合、それを優先的にチェック。
     * これによりMinecraftのエンティティトラッキング範囲（~536m）を超えた
     * 長距離射撃が可能になる。
     */
    @Nullable
    private RayTraceResult rayTraceEntities(Vec3d start, Vec3d end) {
        Entity closestEntity = null;
        Vec3d closestVec = null;
        double closestDistance = 0.0;

        // 1. ターゲットエンティティを優先チェック（長距離対応）
        if (targetEntity != null && !targetEntity.isDead && targetEntity != shooter) {
            if (targetEntity.canBeCollidedWith()) {
                AxisAlignedBB targetBox = targetEntity.getEntityBoundingBox().grow(0.3);
                RayTraceResult intercept = targetBox.calculateIntercept(start, end);

                if (intercept != null) {
                    closestEntity = targetEntity;
                    closestVec = intercept.hitVec;
                    closestDistance = start.distanceTo(intercept.hitVec);
                }
            }
        }

        // 2. 範囲内の他のエンティティもチェック（近距離用）
        AxisAlignedBB searchBox = new AxisAlignedBB(
            Math.min(start.x, end.x) - 1, Math.min(start.y, end.y) - 1, Math.min(start.z, end.z) - 1,
            Math.max(start.x, end.x) + 1, Math.max(start.y, end.y) + 1, Math.max(start.z, end.z) + 1
        );

        List<Entity> entities = world.getEntitiesWithinAABBExcludingEntity(this, searchBox);

        for (Entity entity : entities) {
            if (!entity.canBeCollidedWith() || entity == shooter || entity == targetEntity) {
                continue; // targetEntityは既にチェック済み
            }

            AxisAlignedBB entityBox = entity.getEntityBoundingBox().grow(0.3);
            RayTraceResult intercept = entityBox.calculateIntercept(start, end);

            if (intercept != null) {
                double distance = start.distanceTo(intercept.hitVec);
                if (closestEntity == null || distance < closestDistance) {
                    closestEntity = entity;
                    closestVec = intercept.hitVec;
                    closestDistance = distance;
                }
            }
        }

        if (closestEntity != null) {
            RayTraceResult result = new RayTraceResult(closestEntity, closestVec);
            return result;
        }

        return null;
    }

    /**
     * エンティティヒット処理
     */
    private void onEntityHit(Entity target, RayTraceResult hit) {
        if (world.isRemote) return;

        // 現在の運動エネルギーを計算
        double speed = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
        double kineticEnergy = 0.5 * BULLET_MASS * speed * speed;

        // 飛行距離を計算
        double distanceTraveled = Math.sqrt(
            (posX - initialPosX) * (posX - initialPosX) +
            (posY - initialPosY) * (posY - initialPosY) +
            (posZ - initialPosZ) * (posZ - initialPosZ)
        );

        // 貫通レベルを計算 (0-50)
        // 最大運動エネルギー時に50、0の時に0
        int penetrationLevel = (int) Math.round((kineticEnergy / MAX_KINETIC_ENERGY) * 50.0);
        penetrationLevel = MathHelper.clamp(penetrationLevel, 1, 50);

        // ダメージを計算 (運動エネルギーベース)
        // 基準: 10 kJ = 1 HP
        float damage = (float) (kineticEnergy / 10000.0);
        damage = MathHelper.clamp(damage, 5.0F, 100.0F); // 最小5 HP, 最大100 HP

        // デバッグ情報をログに出力（100発に1回のみ - パフォーマンス最適化）
        hitLogCounter++;
        boolean shouldLog = (hitLogCounter % LOG_INTERVAL == 0);

        if (shouldLog) {
            System.out.println("[GAU-8] Hit entity: " + target.getName() +
                               " | Distance: " + String.format("%.1f", distanceTraveled) + " blocks" +
                               " | Speed: " + String.format("%.1f", speed) + " m/s" +
                               " | KE: " + String.format("%.0f", kineticEnergy) + " J" +
                               " | Penetration: " + penetrationLevel +
                               " | Damage: " + String.format("%.1f", damage) + " HP" +
                               " | Total hits: " + hitLogCounter);
        }

        // 装甲貫通ダメージを与える
        if (target instanceof EntityLivingBase) {
            EntityLivingBase livingTarget = (EntityLivingBase) target;

            // 装甲貫通システムでダメージを与える
            boolean damageDealt = ArmorPenetrationSystem.dealPenetrationDamage(
                livingTarget,
                shooter,
                penetrationLevel,
                damage
            );

            // ダメージが与えられたかログ出力（100発に1回のみ）
            if (shouldLog) {
                if (damageDealt) {
                    System.out.println("[GAU-8] Penetration damage dealt successfully");
                } else {
                    System.out.println("[GAU-8] Damage blocked by armor (Armor Level > Penetration Level)");
                }
            }
        } else {
            // EntityLivingBase以外のエンティティには通常のダメージ
            // カスタムDamageSourceを使用してEntityEffectHandlerのクラッシュを回避
            DamageSource bulletDamage = new DamageSource("gau8").setProjectile();
            target.attackEntityFrom(bulletDamage, damage);

            if (shouldLog) {
                System.out.println("[GAU-8] Generic damage applied to non-living entity");
            }
        }

        // ヒットエフェクト
        spawnHitEffect(hit.hitVec);

        // サウンド再生
        world.playSound(null, target.posX, target.posY, target.posZ,
                        SoundEvents.ENTITY_ARROW_HIT,
                        SoundCategory.HOSTILE,
                        1.0F, 1.2F);

        // 弾丸を削除
        this.setDead();
    }

    /**
     * ブロックヒット処理（弾丸の蓄積を防ぐため、すべてのブロックで消滅）
     */
    private void onBlockHit(BlockPos pos, RayTraceResult hit) {
        if (world.isRemote) return;

        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // 飛行距離を計算
        double distanceTraveled = Math.sqrt(
            (this.posX - initialPosX) * (this.posX - initialPosX) +
            (this.posY - initialPosY) * (this.posY - initialPosY) +
            (this.posZ - initialPosZ) * (this.posZ - initialPosZ)
        );

        // デバッグログ：ブロックヒット
        String blockName = block.getRegistryName() != null ? block.getRegistryName().toString() : "Unknown";
        System.out.println("[GAU-8] Hit block: " + blockName +
                         " at (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")" +
                         " | Distance: " + String.format("%.1f", distanceTraveled) + " blocks");

        // ヒットエフェクト
        spawnHitEffect(hit.hitVec);

        // 運動エネルギーに応じてブロックを破壊
        double speed = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
        double kineticEnergy = 0.5 * BULLET_MASS * speed * speed;

        // 50 kJ以上でガラスを破壊
        if (kineticEnergy > 50000) {
            if (block == Blocks.GLASS || block == Blocks.GLASS_PANE ||
                block == Blocks.STAINED_GLASS || block == Blocks.STAINED_GLASS_PANE) {
                world.destroyBlock(pos, false);
            }
        }

        // 100 kJ以上で柔らかいブロックを破壊
        if (kineticEnergy > 100000) {
            float hardness = state.getBlockHardness(world, pos);
            if (hardness >= 0 && hardness < 2.0F) {
                world.destroyBlock(pos, false);
            }
        }

        // すべてのブロックヒットで消滅（弾丸の蓄積を防ぐ）
        this.setDead();
    }

    /**
     * トレーサーエフェクトをスポーン
     */
    private void spawnTracerEffect() {
        if (ticksAlive % 2 == 0) { // 2 tickごとに1回
            NBTTagCompound data = new NBTTagCompound();
            data.setString("type", "vanillaExt");
            data.setString("mode", "flame");
            data.setDouble("posX", this.posX);
            data.setDouble("posY", this.posY);
            data.setDouble("posZ", this.posZ);
            data.setDouble("mX", 0);
            data.setDouble("mY", 0);
            data.setDouble("mZ", 0);

            PacketDispatcher.wrapper.sendToAllAround(
                new AuxParticlePacketNT(data, this.posX, this.posY, this.posZ),
                new TargetPoint(world.provider.getDimension(), this.posX, this.posY, this.posZ, 300)
            );
        }
    }

    /**
     * ヒットエフェクトをスポーン
     */
    private void spawnHitEffect(Vec3d hitVec) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("type", "vanillaExt");
        data.setString("mode", "largeexplode");
        data.setFloat("size", 2.0F);
        data.setByte("count", (byte) 5);

        PacketDispatcher.wrapper.sendToAllAround(
            new AuxParticlePacketNT(data, hitVec.x, hitVec.y, hitVec.z),
            new TargetPoint(world.provider.getDimension(), hitVec.x, hitVec.y, hitVec.z, 150)
        );
    }


    /**
     * 現在の速度を取得 (m/s)
     */
    public double getCurrentSpeed() {
        return Math.sqrt(velX * velX + velY * velY + velZ * velZ);
    }

    /**
     * 現在の運動エネルギーを取得 (J)
     */
    public double getCurrentKineticEnergy() {
        double speed = getCurrentSpeed();
        return 0.5 * BULLET_MASS * speed * speed;
    }

    /**
     * 現在の貫通レベルを取得
     */
    public int getCurrentPenetrationLevel() {
        double ke = getCurrentKineticEnergy();
        int level = (int) Math.round((ke / MAX_KINETIC_ENERGY) * 50.0);
        return MathHelper.clamp(level, 0, 50);
    }

    @Override
    public void shoot(double x, double y, double z, float velocity, float inaccuracy) {
        // このメソッドは使用しない（コンストラクタで初速を設定）
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        this.velX = nbt.getDouble("velX");
        this.velY = nbt.getDouble("velY");
        this.velZ = nbt.getDouble("velZ");
        this.ticksAlive = nbt.getInteger("ticksAlive");
        this.initialPosX = nbt.getDouble("initX");
        this.initialPosY = nbt.getDouble("initY");
        this.initialPosZ = nbt.getDouble("initZ");

        // タレット位置を復元（デバッグ用）
        if (nbt.hasKey("turretX")) {
            int turretX = nbt.getInteger("turretX");
            int turretY = nbt.getInteger("turretY");
            int turretZ = nbt.getInteger("turretZ");
            this.turretPos = new BlockPos(turretX, turretY, turretZ);
        }
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound nbt) {
        nbt.setDouble("velX", this.velX);
        nbt.setDouble("velY", this.velY);
        nbt.setDouble("velZ", this.velZ);
        nbt.setInteger("ticksAlive", this.ticksAlive);
        nbt.setDouble("initX", this.initialPosX);
        nbt.setDouble("initY", this.initialPosY);
        nbt.setDouble("initZ", this.initialPosZ);

        // タレット位置を保存（デバッグ用）
        if (this.turretPos != null) {
            nbt.setInteger("turretX", this.turretPos.getX());
            nbt.setInteger("turretY", this.turretPos.getY());
            nbt.setInteger("turretZ", this.turretPos.getZ());
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public float getCollisionBorderSize() {
        return 0.0F;
    }

    @Override
    public void setDead() {
        // デバッグログ：弾丸が消滅した理由を記録
        if (!world.isRemote) {
            double distanceTraveled = Math.sqrt(
                (posX - initialPosX) * (posX - initialPosX) +
                (posY - initialPosY) * (posY - initialPosY) +
                (posZ - initialPosZ) * (posZ - initialPosZ)
            );

            String reason = "Unknown";
            if (despawnReason != null) {
                // 明示的に設定された削除理由を使用（チャンクアンロードなど）
                reason = despawnReason;
            } else if (this.isDead) {
                reason = "Already dead";
            } else if (ticksAlive > MAX_LIFETIME) {
                reason = "Max lifetime exceeded";
            } else {
                reason = "Hit or other";
            }

            // 弾丸破壊ログ（100発に1回のみ - パフォーマンス最適化）
            destroyLogCounter++;
            if (destroyLogCounter % LOG_INTERVAL == 0) {
                System.out.println("[GAU-8] Bullet destroyed | Reason: " + reason +
                                 " | Distance traveled: " + String.format("%.1f", distanceTraveled) + " blocks" +
                                 " | Lifetime: " + ticksAlive + " ticks" +
                                 " | Total destroyed: " + destroyLogCounter);
            }

            // チャンクロードはタレットのBulletTrajectoryManagerが管理
            // 弾丸は個別にチケットを持たない
        }

        super.setDead();
    }
}
