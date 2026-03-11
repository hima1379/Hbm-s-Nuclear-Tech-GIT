package com.hbm.entity.logic;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.List;

/**
 * タレット用のデコイエンティティ（完全不死身版 + 強制アグロシステム）
 *
 * このエンティティはタイルエンティティの位置に固定され、
 * システムレベルで強制的にすべての敵モブからヘイトを集めます。
 *
 * システムレベルの強制ヘイト：
 * - 5tickごと（0.25秒）に周囲50ブロック範囲のすべてのモブをスキャン
 * - EntityMob、EntityCreature、EntityLivingのsetAttackTarget()を強制呼び出し
 * - モブのAIロジックに関係なく、強制的にこのデコイを最優先ターゲットに設定
 * - EntityLivingBaseを継承し、プレイヤーサイズ（0.6F x 1.8F）で検出されやすく設定
 *
 * 完全無敵・不死身の仕組み：
 * - すべてのダメージ無効化（攻撃、爆発、炎、溺死、虚空、窒息等）
 * - すべてのポーション効果無効化（毒、ウィザー、弱体化等）
 * - setDead()をオーバーライドして通常は削除不可（タレット破壊時のみ削除可能）
 * - 衝突判定なし（noClip = true）
 * - 視覚的に透明（レンダリング無効化）
 *
 * ライフサイクル：
 * - 通常時：setDead()を呼んでも削除されない（allowDeath = false）
 * - タレット破壊時：allowDeath()を呼んでからsetDead()すると削除される
 * - デコイが攻撃されてもタレットは無傷、デコイは自動再生成される
 * - 周囲のモブは5tickごとに強制的にこのデコイをターゲットにリセットされる
 */
public class EntityTurretDecoy extends EntityLivingBase {

    private BlockPos turretPos;
    private int lifetime = 0;
    private static final int MAX_LIFETIME = 20; // 1秒ごとにリフレッシュされる想定
    private boolean allowDeath = false; // タレット破壊時のみtrueになり、削除を許可

    // システムレベルの強制ヘイト
    private static final double AGGRO_RANGE = 50.0D; // 50ブロック範囲のモブに強制ヘイト
    private static final int AGGRO_UPDATE_INTERVAL = 5; // 5tickごとにアグロ更新（0.25秒）
    private int aggroUpdateCounter = 0;

    public EntityTurretDecoy(World worldIn) {
        super(worldIn);

        // サイズを極小に設定（完全に見えなくする）
        // 強制ヘイトシステムでsetAttackTarget()を直接呼ぶため、サイズは関係ない
        this.setSize(0.001F, 0.001F);

        // 完全無敵
        this.setEntityInvulnerable(true);

        // 完全不可視に設定
        // 強制ヘイトシステム（setAttackTarget()直接呼び出し）により、
        // 不可視でもモブは確実にターゲットする
        this.setInvisible(true);

        // 炎ダメージ無効
        this.isImmuneToFire = true;

        // 溶岩ダメージ無効
        this.isImmuneToFire = true;

        // 衝突判定を無効化
        this.noClip = true;
    }

    public EntityTurretDecoy(World worldIn, BlockPos turretPos) {
        this(worldIn);
        this.turretPos = turretPos;

        // タレット位置に配置
        this.setPosition(turretPos.getX() + 0.5, turretPos.getY() + 1.0, turretPos.getZ() + 0.5);
    }

    @Override
    protected void applyEntityAttributes() {
        super.applyEntityAttributes();

        // 最大体力を設定（実際にはダメージを受けないが、AI検出用）
        this.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(20.0D);

        // 移動速度0（動かない）
        this.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(0.0D);

        // ノックバック耐性100%
        this.getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
    }

    /**
     * 利き手を返す（EntityLivingBaseの抽象メソッド実装）
     */
    @Override
    public EnumHandSide getPrimaryHand() {
        return EnumHandSide.RIGHT;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (!world.isRemote) {
            // タレット位置に固定
            if (turretPos != null) {
                this.setPosition(turretPos.getX() + 0.5, turretPos.getY() + 1.0, turretPos.getZ() + 0.5);

                // 速度を完全にゼロに
                this.motionX = 0;
                this.motionY = 0;
                this.motionZ = 0;
                this.velocityChanged = true;
            }

            // ライフタイムカウント（デバッグ用のみ、削除はタレット側で管理）
            lifetime++;

            // 体力を常に最大に保つ（setHealthはオーバーライド済みで常に最大値）
            // （実際にはsetHealthをオーバーライドしているため、この呼び出しは不要だが明示的に記載）

            // ========================================
            // システムレベルの強制ヘイト
            // ========================================
            // 5tickごとに周囲のモブをスキャンし、強制的にこのデコイをターゲットに設定
            aggroUpdateCounter++;
            if (aggroUpdateCounter >= AGGRO_UPDATE_INTERVAL) {
                aggroUpdateCounter = 0;
                forceAggroNearbyMobs();
            }
        }
    }

    /**
     * システムレベルの強制ヘイト：周囲のすべての敵モブを強制的にこのデコイにターゲットさせる
     *
     * 動作：
     * 1. AGGRO_RANGE内のすべてのEntityLivingをスキャン
     * 2. EntityMob、EntityCreature、EntityLiving（AI付き）を検出
     * 3. 各モブのsetAttackTarget()またはsetRevengeTarget()を強制呼び出し
     * 4. モブのAIがどのようなロジックでも、強制的にこのデコイを優先ターゲットに設定
     */
    private void forceAggroNearbyMobs() {
        // 周囲のエンティティをスキャン
        AxisAlignedBB scanBox = new AxisAlignedBB(
            this.posX - AGGRO_RANGE,
            this.posY - AGGRO_RANGE,
            this.posZ - AGGRO_RANGE,
            this.posX + AGGRO_RANGE,
            this.posY + AGGRO_RANGE,
            this.posZ + AGGRO_RANGE
        );

        List<EntityLiving> nearbyMobs = this.world.getEntitiesWithinAABB(EntityLiving.class, scanBox);

        // デバッグログ（20tickごと = 1秒ごと）
        boolean debugLog = (lifetime % 20 == 0);

        if (debugLog) {
            System.out.println("[TurretDecoy] ========================================");
            System.out.println("[TurretDecoy] FORCED AGGRO SCAN");
            System.out.println("[TurretDecoy] Decoy position: [" + String.format("%.1f", this.posX) +
                               ", " + String.format("%.1f", this.posY) +
                               ", " + String.format("%.1f", this.posZ) + "]");
            System.out.println("[TurretDecoy] Scan range: " + AGGRO_RANGE + " blocks");
            System.out.println("[TurretDecoy] Total entities found: " + nearbyMobs.size());
        }

        int aggroCount = 0;
        int skippedCount = 0;
        for (EntityLiving mob : nearbyMobs) {
            // 自分自身は除外（EntityLivingBaseとして比較）
            if (mob.getEntityId() == this.getEntityId()) {
                skippedCount++;
                continue;
            }

            // 死んでいるモブは除外
            if (!mob.isEntityAlive()) {
                skippedCount++;
                continue;
            }

            // モブのターゲットを強制的にこのデコイに設定
            EntityLivingBase currentTarget = mob.getAttackTarget();

            // 敵対モブ（EntityMob）の場合
            if (mob instanceof EntityMob) {
                mob.setAttackTarget(this);
                mob.setRevengeTarget(this);
                aggroCount++;

                if (debugLog && aggroCount <= 3) {
                    System.out.println("[TurretDecoy]   -> Set target on " + mob.getName() +
                                       " (EntityMob) at [" + String.format("%.1f", mob.posX) +
                                       ", " + String.format("%.1f", mob.posY) +
                                       ", " + String.format("%.1f", mob.posZ) + "]" +
                                       " | Previous target: " + (currentTarget != null ? currentTarget.getName() : "none"));
                }
            }
            // その他のEntityLiving（動物やニュートラルモブ）の場合
            else if (mob instanceof EntityCreature) {
                mob.setAttackTarget(this);
                mob.setRevengeTarget(this);
                aggroCount++;

                if (debugLog && aggroCount <= 3) {
                    System.out.println("[TurretDecoy]   -> Set target on " + mob.getName() +
                                       " (EntityCreature) at [" + String.format("%.1f", mob.posX) +
                                       ", " + String.format("%.1f", mob.posY) +
                                       ", " + String.format("%.1f", mob.posZ) + "]");
                }
            }
            // その他のEntityLiving全般
            else {
                // AIを持つすべてのEntityLivingに対して強制ターゲット設定
                mob.setAttackTarget(this);
                aggroCount++;

                if (debugLog && aggroCount <= 3) {
                    System.out.println("[TurretDecoy]   -> Set target on " + mob.getName() +
                                       " (EntityLiving) at [" + String.format("%.1f", mob.posX) +
                                       ", " + String.format("%.1f", mob.posY) +
                                       ", " + String.format("%.1f", mob.posZ) + "]");
                }
            }
        }

        if (debugLog) {
            System.out.println("[TurretDecoy] Total mobs targeted: " + aggroCount);
            System.out.println("[TurretDecoy] Skipped entities: " + skippedCount);
            System.out.println("[TurretDecoy] ========================================");
        }
    }

    /**
     * ライフタイムをリフレッシュ（タレットがまだ存在していることを示す）
     */
    public void refreshLifetime() {
        this.lifetime = 0;
    }

    /**
     * デコイの削除を許可する（タレット破壊時のみ呼ばれる）
     */
    public void allowDeath() {
        this.allowDeath = true;
    }

    /**
     * setDeadをオーバーライドして通常時は削除を無効化
     * タレット破壊時（allowDeath = true）のみ削除を許可
     */
    @Override
    public void setDead() {
        if (allowDeath) {
            // タレット破壊時のみ本当に削除
            super.setDead();
        }
        // 通常時は何もしない（削除されない）
    }

    /**
     * すべてのダメージを無効化
     */
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        // ダメージを一切受けない
        return false;
    }

    /**
     * プッシュ無効
     */
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /**
     * プッシュ無効
     */
    @Override
    public boolean canBePushed() {
        return false;
    }

    /**
     * 水中呼吸可能
     */
    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    /**
     * エンティティがAIターゲットとして有効か
     * trueを返すことで、すべてのモブAIから認識される
     */
    @Override
    public boolean isEntityAlive() {
        return !this.isDead;
    }

    /**
     * エンティティの種類（通常のモブとして扱う）
     */
    @Override
    public boolean isNonBoss() {
        return true; // 通常のモブとして扱う（モブAIがターゲットにしやすい）
    }

    /**
     * NBTへの書き込み
     */
    @Override
    public void writeEntityToNBT(NBTTagCompound compound) {
        super.writeEntityToNBT(compound);
        if (turretPos != null) {
            compound.setInteger("turretX", turretPos.getX());
            compound.setInteger("turretY", turretPos.getY());
            compound.setInteger("turretZ", turretPos.getZ());
        }
        compound.setInteger("lifetime", lifetime);
        compound.setBoolean("allowDeath", allowDeath);
    }

    /**
     * NBTからの読み込み
     */
    @Override
    public void readEntityFromNBT(NBTTagCompound compound) {
        super.readEntityFromNBT(compound);
        if (compound.hasKey("turretX")) {
            this.turretPos = new BlockPos(
                compound.getInteger("turretX"),
                compound.getInteger("turretY"),
                compound.getInteger("turretZ")
            );
        }
        this.lifetime = compound.getInteger("lifetime");
        this.allowDeath = compound.getBoolean("allowDeath");
    }

    /**
     * デスポーン防止
     * 注: canDespawn()はEntityLivingではなくEntityLivingやEntityMobのメソッドなので、
     * ここでは定義しない（EntityLivingBaseにはこのメソッドがない）
     */

    /**
     * エンティティの名前（デバッグ用）
     */
    @Override
    public String getName() {
        return "Turret Decoy";
    }

    /**
     * すべてのポーション効果を無効化
     */
    @Override
    public boolean isPotionApplicable(net.minecraft.potion.PotionEffect potioneffectIn) {
        return false; // すべてのポーション効果を拒否
    }

    /**
     * 虚空ダメージ無効化
     */
    @Override
    protected void outOfWorld() {
        // 何もしない（虚空ダメージを受けない）
    }

    @Override
    public Iterable<ItemStack> getArmorInventoryList() {
        // 空のリストを返す（nullを返すとクラッシュする）
        return java.util.Collections.emptyList();
    }

    @Override
    public ItemStack getItemStackFromSlot(EntityEquipmentSlot slotIn) {
        // ItemStack.EMPTYを返す（nullを返すとModEventHandlerでクラッシュする）
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemStackToSlot(EntityEquipmentSlot slotIn, ItemStack stack) {
        // 何もしない（アイテムを装備できない）
    }

    /**
     * 窒息ダメージ無効化
     */
    @Override
    protected boolean canTriggerWalking() {
        return false;
    }

    /**
     * 毒・ウィザー効果等の持続ダメージ無効化
     */
    @Override
    protected void onDeathUpdate() {
        // 何もしない（死亡処理を無効化）
    }

    /**
     * 爆発ダメージを含むすべてのダメージを無効化（追加保護）
     */
    @Override
    public boolean isEntityInvulnerable(DamageSource source) {
        return true; // すべてのダメージソースに対して無敵
    }

    /**
     * 炎ダメージ無効化
     * 注: isImmuneToFire()はfinalメソッドなのでオーバーライド不可
     * コンストラクタで isImmuneToFire = true を設定済み
     */

    /**
     * エンティティが燃えている状態を無効化
     */
    @Override
    public void setFire(int seconds) {
        // 何もしない（燃えない）
    }

    /**
     * 溺死ダメージ無効化
     */
    @Override
    public int getAir() {
        return 300; // 常に最大空気量
    }

    /**
     * 空気量設定を無視
     */
    @Override
    public void setAir(int air) {
        // 何もしない（空気量は常に最大）
    }

    /**
     * 体力設定を無視（常に最大体力）
     */
    @Override
    public void setHealth(float health) {
        super.setHealth(this.getMaxHealth()); // 常に最大体力に設定
    }

    /**
     * ダメージインジケーターを無効化
     */
    @Override
    public boolean hitByEntity(Entity entityIn) {
        return true; // ダメージを受けたことにしない
    }
}
