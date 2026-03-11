package com.hbm.items.armor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import javax.annotation.Nullable;

/**
 * 装甲貫通システム - Armor Penetration System (Enhanced)
 *
 * このシステムはArmorShieldSystemの装甲を貫通するダメージソースを提供します。
 *
 * 強化版の特徴:
 * - あらゆるダメージ上限システムを突破
 * - Parasite modなどのダメージキャップを無視
 * - 直接体力を減らすことで全ての防御を回避
 * - イベントシステムを完全にバイパス
 *
 * 貫通レベル1以上（Integer.MAX_VALUEまで）:
 * - レベルN: レベルN未満の装甲を貫通可能（貫通レベル > 装甲レベルの場合のみ貫通成功）
 *
 * 装甲がない、または貫通可能な場合:
 * - 全ての防御メカニズムを無視
 * - ダメージ上限を無視
 * - 指定したダメージを確実に与える
 */
public class ArmorPenetrationSystem {

    /**
     * 装甲貫通ダメージソース
     */
    public static class DamageSourceArmorPenetration extends EntityDamageSource {

        private final int penetrationLevel;
        private final float trueDamage;
        private boolean bypassesArmor = true;
        private boolean bypassesInvulnerability = true;
        private boolean bypassesMagic = true;
        private boolean absoluteDamage = true; // 絶対ダメージフラグ

        public DamageSourceArmorPenetration(String damageTypeIn, @Nullable Entity damageSourceEntityIn, int penetrationLevel, float trueDamage) {
            super(damageTypeIn, damageSourceEntityIn);
            this.penetrationLevel = penetrationLevel;
            this.trueDamage = trueDamage;
        }

        /**
         * 貫通レベルを取得
         */
        public int getPenetrationLevel() {
            return penetrationLevel;
        }

        /**
         * 真のダメージ量を取得（防御無視）
         */
        public float getTrueDamage() {
            return trueDamage;
        }

        /**
         * 絶対ダメージかどうか
         */
        public boolean isAbsoluteDamage() {
            return absoluteDamage;
        }

        @Override
        public DamageSourceArmorPenetration setDamageBypassesArmor() {
            this.bypassesArmor = true;
            return this;
        }

        @Override
        public boolean isUnblockable() {
            return true;
        }

        @Override
        public boolean isDamageAbsolute() {
            return true;
        }

        @Override
        public ITextComponent getDeathMessage(EntityLivingBase entityLivingBaseIn) {
            String key = "death.attack.armor_penetration";
            if (damageSourceEntity != null) {
                return new TextComponentTranslation(key + ".player",
                        entityLivingBaseIn.getDisplayName(),
                        damageSourceEntity.getDisplayName(),
                        penetrationLevel);
            }
            return new TextComponentTranslation(key,
                    entityLivingBaseIn.getDisplayName(),
                    penetrationLevel);
        }
    }

    /**
     * 装甲貫通ダメージを作成
     *
     * @param source ダメージ元のエンティティ
     * @param penetrationLevel 貫通レベル（1以上、Integer.MAX_VALUEまで）
     * @param damage ダメージ量
     * @return 装甲貫通ダメージソース
     */
    public static DamageSourceArmorPenetration createPenetrationDamage(@Nullable Entity source, int penetrationLevel, float damage) {
        penetrationLevel = Math.max(1, penetrationLevel);
        return new DamageSourceArmorPenetration("armor_penetration", source, penetrationLevel, damage)
                .setDamageBypassesArmor();
    }

    /**
     * レベル指定の貫通ダメージを作成（簡易版）
     */
    public static DamageSourceArmorPenetration createPenetrationDamage(int level, float damage) {
        return createPenetrationDamage(null, level, damage);
    }

    /**
     * 最大レベル貫通ダメージを作成（Integer.MAX_VALUE レベル - あらゆる装甲を貫通）
     */
    public static DamageSourceArmorPenetration createMaxPenetrationDamage(@Nullable Entity source, float damage) {
        return createPenetrationDamage(source, Integer.MAX_VALUE, damage);
    }

    /**
     * ダメージが装甲貫通タイプかチェック
     */
    public static boolean isPenetrationDamage(DamageSource source) {
        return source instanceof DamageSourceArmorPenetration;
    }

    /**
     * 貫通レベルを取得（貫通ダメージでない場合は0を返す）
     */
    public static int getPenetrationLevel(DamageSource source) {
        if (source instanceof DamageSourceArmorPenetration) {
            return ((DamageSourceArmorPenetration) source).getPenetrationLevel();
        }
        return 0;
    }

    /**
     * 真のダメージ量を取得
     */
    public static float getTrueDamage(DamageSource source) {
        if (source instanceof DamageSourceArmorPenetration) {
            return ((DamageSourceArmorPenetration) source).getTrueDamage();
        }
        return 0;
    }

    /**
     * 装甲を貫通できるかチェック
     *
     * @param penetrationLevel 貫通レベル
     * @param armorLevel 装甲レベル
     * @return 貫通可能ならtrue（貫通レベルが装甲レベルを厳密に上回る場合のみ）
     */
    public static boolean canPenetrate(int penetrationLevel, int armorLevel) {
        return penetrationLevel > armorLevel; // 同値はアーマー側が勝つ
    }

    /**
     * 装甲システムをチェックして貫通処理を実行
     * このメソッドはLivingAttackEventで呼ばれるべき
     */
    public static void handlePenetrationAttack(LivingAttackEvent event) {
        if (!isPenetrationDamage(event.getSource())) {
            return;
        }

        EntityLivingBase entity = event.getEntityLiving();
        DamageSourceArmorPenetration penetrationSource = (DamageSourceArmorPenetration) event.getSource();
        int penetrationLevel = penetrationSource.getPenetrationLevel();
        float trueDamage = penetrationSource.getTrueDamage();

        // プレイヤーの装甲システムをチェック
        if (entity instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entity;
            ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

            if (!chestStack.isEmpty() && chestStack.getItem() instanceof ArmorShieldSystem) {
                ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();
                int armorLevel = armor.getArmorLevel(chestStack);

                // 装甲が有効かチェック
                if (armor.isShieldActive(chestStack)) {
                    // 貫通可能かチェック
                    if (canPenetrate(penetrationLevel, armorLevel)) {
                        // 貫通成功 - 絶対ダメージを与える
                        applyAbsoluteTrueDamage(entity, trueDamage, penetrationLevel, armorLevel);

                        if (!player.world.isRemote) {
                            player.sendMessage(new TextComponentString(
                                    "" + TextFormatting.DARK_RED + "[装甲貫通] " +
                                            TextFormatting.RED + "貫通レベル " + penetrationLevel +
                                            TextFormatting.YELLOW + " > " +
                                            TextFormatting.GOLD + "装甲レベル " + armorLevel +
                                            TextFormatting.RED + " - 貫通成功!"));
                        }

                        event.setCanceled(true);
                        return;
                    } else {
                        // 貫通失敗 - 装甲レベルが高い
                        if (!player.world.isRemote) {
                            player.sendMessage(new TextComponentString(
                                    "" + TextFormatting.GREEN + "[装甲防御] " +
                                            TextFormatting.GOLD + "装甲レベル " + armorLevel +
                                            TextFormatting.AQUA + " > " +
                                            TextFormatting.YELLOW + "貫通レベル " + penetrationLevel +
                                            TextFormatting.GREEN + " - 防御成功!"));
                        }

                        event.setCanceled(true);
                        return;
                    }
                }
            }
        }

        // 装甲がない、または装甲が無効な場合 - 絶対ダメージを与える
        applyAbsoluteTrueDamage(entity, trueDamage, penetrationLevel, 0);

        if (!entity.world.isRemote && entity instanceof EntityPlayer) {
            ((EntityPlayer) entity).sendMessage(new TextComponentString(
                    "" + TextFormatting.DARK_RED + "[装甲貫通] " +
                            TextFormatting.RED + "レベル " + penetrationLevel + " 貫通ダメージ!"));
        }

        event.setCanceled(true);
    }

    /**
     * 絶対真のダメージを直接適用（全ての防御・上限を無視）
     *
     * これは以下をバイパスします：
     * - 全ての防御イベント
     * - ダメージキャップ
     * - 無敵時間
     * - ダメージリダクション
     * - Parasite modなどのダメージ上限
     */
    private static void applyAbsoluteTrueDamage(EntityLivingBase entity, float damage, int penetrationLevel, int bypassedArmorLevel) {
        // 現在の体力を取得
        float currentHealth = entity.getHealth();
        float newHealth = Math.max(0, currentHealth - damage);

        // 体力を直接設定（イベントをバイパス）
        entity.setHealth(newHealth);

        // 貫通成功によるHP減少をprotectedHealthに反映（翌tickのprotectPlayerHealthによる自動復元を防止）
        if (entity instanceof EntityPlayer) {
            ArmorShieldSystem.markPenetrationSuccess(((EntityPlayer) entity).getUniqueID(), newHealth);
        }

        // 無敵時間をリセット（連続ダメージを可能にする）
        entity.hurtResistantTime = 0;
        entity.hurtTime = 10;
        entity.maxHurtTime = 10;

        // 死亡チェック
        if (newHealth <= 0 && !entity.isDead) {
            entity.onDeath(DamageSource.GENERIC);
        }

        // ダメージ表示用のフィードバック
        if (!entity.world.isRemote) {
            if (entity instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) entity;
                player.sendMessage(new TextComponentString(
                        "" + TextFormatting.RED + ">>> " +
                                TextFormatting.DARK_RED + String.format("%.1f", damage) + " HP" +
                                TextFormatting.RED + " 絶対ダメージ <<<"));
            }
        }
    }

    /**
     * LivingHurtEventで貫通ダメージを処理
     * ダメージ量の変更を防ぐ
     */
    public static void handlePenetrationHurt(LivingHurtEvent event) {
        if (!isPenetrationDamage(event.getSource())) {
            return;
        }

        // 既にAttackEventで処理されているのでキャンセル
        event.setCanceled(true);
    }

    /**
     * LivingDamageEventで貫通ダメージを処理
     * 最終ダメージの変更を防ぐ
     */
    public static void handlePenetrationDamage(LivingDamageEvent event) {
        if (!isPenetrationDamage(event.getSource())) {
            return;
        }

        // 既にAttackEventで処理されているのでキャンセル
        event.setCanceled(true);
    }

    /**
     * エンティティに装甲貫通ダメージを与える
     *
     * @param target ターゲット
     * @param source ダメージ元
     * @param penetrationLevel 貫通レベル（1-100）
     * @param damage ダメージ量
     * @return ダメージが成功したかどうか
     */
    public static boolean dealPenetrationDamage(EntityLivingBase target, @Nullable Entity source, int penetrationLevel, float damage) {
        // プレイヤーの場合は装甲レベルをチェック
        if (target instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) target;
            ItemStack chestStack = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);

            if (!chestStack.isEmpty() && chestStack.getItem() instanceof ArmorShieldSystem) {
                ArmorShieldSystem armor = (ArmorShieldSystem) chestStack.getItem();
                int armorLevel = armor.getArmorLevel(chestStack);

                // 装甲が有効かチェック
                if (armor.isShieldActive(chestStack)) {
                    // 貫通可能かチェック
                    if (canPenetrate(penetrationLevel, armorLevel)) {
                        // 貫通成功 - ダメージを与える
                        applyAbsoluteTrueDamage(target, damage, penetrationLevel, armorLevel);

                        if (!player.world.isRemote) {
                            player.sendMessage(new TextComponentString(
                                    "" + TextFormatting.DARK_RED + "[装甲貫通] " +
                                            TextFormatting.RED + "貫通レベル " + penetrationLevel +
                                            TextFormatting.YELLOW + " > " +
                                            TextFormatting.GOLD + "装甲レベル " + armorLevel +
                                            TextFormatting.RED + " - 貫通成功!"));
                        }
                        return true;
                    } else {
                        // 貫通失敗 - 装甲レベルが高い
                        if (!player.world.isRemote) {
                            player.sendMessage(new TextComponentString(
                                    "" + TextFormatting.GREEN + "[装甲防御] " +
                                            TextFormatting.GOLD + "装甲レベル " + armorLevel +
                                            TextFormatting.AQUA + " > " +
                                            TextFormatting.YELLOW + "貫通レベル " + penetrationLevel +
                                            TextFormatting.GREEN + " - 防御成功!"));
                        }
                        return false; // ダメージを与えない
                    }
                }
            }
        }

        // 装甲がない、または装甲が無効な場合、またはプレイヤー以外の場合
        // 通常のダメージシステムを使用
        DamageSourceArmorPenetration damageSource = createPenetrationDamage(source, penetrationLevel, damage);
        boolean result = target.attackEntityFrom(damageSource, damage);

        // 通常のダメージが通らない場合のみ絶対ダメージを適用（装甲がないことを確認済み）
        if (!result && !(target instanceof EntityPlayer)) {
            applyAbsoluteTrueDamage(target, damage, penetrationLevel, 0);
            return true;
        }

        return result;
    }

    /**
     * プレイヤーに装甲貫通ダメージを与える（簡易版）
     */
    public static boolean dealPenetrationDamage(EntityLivingBase target, int penetrationLevel, float damage) {
        return dealPenetrationDamage(target, null, penetrationLevel, damage);
    }

    /**
     * ダメージ上限を持つエンティティに対して確実にダメージを与える
     *
     * この関数はイベントシステムを完全にバイパスし、
     * 体力を直接操作することであらゆるダメージキャップを無視します。
     *
     * @param target ターゲット
     * @param damage ダメージ量
     * @param penetrationLevel 貫通レベル（ログ用）
     */
    public static void dealAbsoluteDamageBypassAll(EntityLivingBase target, float damage, int penetrationLevel) {
        applyAbsoluteTrueDamage(target, damage, penetrationLevel, 0);
    }

    /**
     * レベル表示用のカラーコードを取得
     */
    public static String getLevelColor(int level) {
        if (level == Integer.MAX_VALUE) return "" + TextFormatting.OBFUSCATED + TextFormatting.DARK_RED;
        if (level >= 10000) return "" + TextFormatting.DARK_PURPLE;
        if (level >= 1000) return "" + TextFormatting.DARK_RED;
        if (level >= 500) return "" + TextFormatting.RED;
        if (level >= 100) return "" + TextFormatting.GOLD;
        if (level >= 50) return "" + TextFormatting.YELLOW;
        if (level >= 10) return "" + TextFormatting.WHITE;
        return "" + TextFormatting.GRAY;
    }

    /**
     * 貫通レベルの説明文を取得
     */
    public static String getPenetrationDescription(int level) {
        if (level == Integer.MAX_VALUE) return "絶対神貫通";
        if (level >= 10000) return "超越貫通";
        if (level >= 1000) return "極限貫通";
        if (level >= 500) return "絶対貫通";
        if (level >= 100) return "超重貫通";
        if (level >= 50) return "重貫通";
        if (level >= 10) return "中貫通";
        return "軽貫通";
    }
}