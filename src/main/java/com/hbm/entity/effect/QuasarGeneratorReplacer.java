package com.hbm.entity.effect;

import java.lang.reflect.Field;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;

/**
 * チャンク生成システムを動的に置き換えるユーティリティ
 *
 * クエーサーが存在するディメンションのチャンクジェネレーターを
 * QuasarVoidChunkGeneratorに置き換えることで、
 * 新規チャンクが最初から空になるようにする
 */
public class QuasarGeneratorReplacer {

    private static Field chunkGeneratorField = null;
    private static boolean reflectionInitialized = false;

    /**
     * リフレクションの初期化
     */
    private static void initReflection() {
        if (reflectionInitialized) {
            return;
        }

        // 試行するフィールド名のリスト
        String[] possibleFieldNames = {
                "chunkGenerator",           // 開発環境
                "field_186029_c",          // 1.12.2 難読化版1
                "field_73246_d",           // 1.12.2 難読化版2
                "generator",               // 別名
                "chunkGen"                 // 別名
        };

        for (String fieldName : possibleFieldNames) {
            try {
                chunkGeneratorField = ChunkProviderServer.class.getDeclaredField(fieldName);
                chunkGeneratorField.setAccessible(true);
                reflectionInitialized = true;
                System.out.println("[QuasarGeneratorReplacer] Successfully initialized reflection with field: " + fieldName);
                return;
            } catch (NoSuchFieldException e) {
                // 次のフィールド名を試す
                continue;
            } catch (Exception e) {
                System.err.println("[QuasarGeneratorReplacer] Unexpected error with field " + fieldName + ":");
                e.printStackTrace();
            }
        }

        // すべて失敗した場合
        System.err.println("[QuasarGeneratorReplacer] Failed to initialize reflection - tried all known field names");
        System.err.println("[QuasarGeneratorReplacer] Available fields in ChunkProviderServer:");
        for (java.lang.reflect.Field field : ChunkProviderServer.class.getDeclaredFields()) {
            System.err.println("  - " + field.getName() + " : " + field.getType().getName());
        }
        reflectionInitialized = false;
    }

    /**
     * 指定されたワールドのチャンクジェネレーターをQuasarVoidChunkGeneratorに置き換える
     *
     * @param world 対象のワールド
     * @return 置き換えに成功した場合はtrue
     */
    public static boolean replaceChunkGenerator(World world) {
        if (world == null || world.isRemote) {
            return false;
        }

        initReflection();

        if (!reflectionInitialized || chunkGeneratorField == null) {
            System.err.println("[QuasarGeneratorReplacer] Reflection not initialized, cannot replace generator");
            return false;
        }

        try {
            IChunkProvider chunkProvider = world.getChunkProvider();

            if (!(chunkProvider instanceof ChunkProviderServer)) {
                System.err.println("[QuasarGeneratorReplacer] ChunkProvider is not ChunkProviderServer");
                return false;
            }

            ChunkProviderServer chunkProviderServer = (ChunkProviderServer) chunkProvider;

            // 現在のジェネレーターを取得
            IChunkGenerator currentGenerator = (IChunkGenerator) chunkGeneratorField.get(chunkProviderServer);

            // すでにQuasarVoidChunkGeneratorの場合はスキップ
            if (currentGenerator instanceof QuasarVoidChunkGenerator) {
                System.out.println("[QuasarGeneratorReplacer] Generator already replaced for dimension " +
                        world.provider.getDimension());
                return true;
            }

            // 新しいQuasarVoidChunkGeneratorを作成
            QuasarVoidChunkGenerator voidGenerator = new QuasarVoidChunkGenerator(world);

            // チャンクジェネレーターを置き換え
            chunkGeneratorField.set(chunkProviderServer, voidGenerator);

            System.out.println("[QuasarGeneratorReplacer] Successfully replaced chunk generator for dimension " +
                    world.provider.getDimension());
            System.out.println("[QuasarGeneratorReplacer] Old generator: " + currentGenerator.getClass().getName());
            System.out.println("[QuasarGeneratorReplacer] New generator: " + voidGenerator.getClass().getName());

            return true;

        } catch (Exception e) {
            System.err.println("[QuasarGeneratorReplacer] Failed to replace chunk generator:");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 指定されたワールドのチャンクジェネレーターを元に戻す
     *
     * @param world 対象のワールド
     * @param originalGenerator 元のジェネレーター
     * @return 復元に成功した場合はtrue
     */
    public static boolean restoreChunkGenerator(World world, IChunkGenerator originalGenerator) {
        if (world == null || world.isRemote || originalGenerator == null) {
            return false;
        }

        initReflection();

        if (!reflectionInitialized || chunkGeneratorField == null) {
            return false;
        }

        try {
            IChunkProvider chunkProvider = world.getChunkProvider();

            if (!(chunkProvider instanceof ChunkProviderServer)) {
                return false;
            }

            ChunkProviderServer chunkProviderServer = (ChunkProviderServer) chunkProvider;
            chunkGeneratorField.set(chunkProviderServer, originalGenerator);

            System.out.println("[QuasarGeneratorReplacer] Restored original chunk generator for dimension " +
                    world.provider.getDimension());

            return true;

        } catch (Exception e) {
            System.err.println("[QuasarGeneratorReplacer] Failed to restore chunk generator:");
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 指定されたワールドの現在のチャンクジェネレーターを取得
     *
     * @param world 対象のワールド
     * @return 現在のチャンクジェネレーター、取得できない場合はnull
     */
    public static IChunkGenerator getCurrentGenerator(World world) {
        if (world == null || world.isRemote) {
            return null;
        }

        initReflection();

        if (!reflectionInitialized || chunkGeneratorField == null) {
            return null;
        }

        try {
            IChunkProvider chunkProvider = world.getChunkProvider();

            if (!(chunkProvider instanceof ChunkProviderServer)) {
                return null;
            }

            ChunkProviderServer chunkProviderServer = (ChunkProviderServer) chunkProvider;
            return (IChunkGenerator) chunkGeneratorField.get(chunkProviderServer);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 指定されたワールドがQuasarVoidChunkGeneratorを使用しているか確認
     *
     * @param world 対象のワールド
     * @return QuasarVoidChunkGeneratorを使用している場合はtrue
     */
    public static boolean isUsingVoidGenerator(World world) {
        IChunkGenerator generator = getCurrentGenerator(world);
        return generator instanceof QuasarVoidChunkGenerator;
    }
}