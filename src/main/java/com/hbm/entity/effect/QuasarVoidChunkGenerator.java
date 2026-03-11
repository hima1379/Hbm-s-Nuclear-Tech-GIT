package com.hbm.entity.effect;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.gen.IChunkGenerator;

/**
 * クエーサー専用の完全空チャンクジェネレーター
 *
 * チャンク生成システムを完全に無効化し、絶対に空のチャンクのみを生成します。
 * すべての生成ステージを完全にバイパスします。
 */
public class QuasarVoidChunkGenerator implements IChunkGenerator {

    private final World world;
    private static final Biome VOID_BIOME = Biomes.VOID;

    public QuasarVoidChunkGenerator(World world) {
        this.world = world;
        System.out.println("[QuasarVoidChunkGenerator] Initialized COMPLETE VOID generator for dimension " +
                world.provider.getDimension());
    }

    /**
     * チャンクを生成 - 完全に空のチャンクのみ生成
     * すべてのブロックが空気、すべての処理をスキップ
     */
    @Override
    public Chunk generateChunk(int x, int z) {
        // 完全に空のChunkPrimerを作成
        ChunkPrimer primer = new ChunkPrimer();

        // すべてのブロックを明示的に空気に設定
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = 0; by < 256; by++) {
                    primer.setBlockState(bx, by, bz, Blocks.AIR.getDefaultState());
                }
            }
        }

        // 完全に空のチャンクを生成
        Chunk chunk = new Chunk(this.world, primer, x, z);

        // バイオーム配列を全てVoidに設定
        byte[] biomeArray = chunk.getBiomeArray();
        byte voidBiomeId = (byte) Biome.getIdForBiome(VOID_BIOME);
        for (int i = 0; i < biomeArray.length; i++) {
            biomeArray[i] = voidBiomeId;
        }

        // スカイライトマップを生成（完全に明るい）
        chunk.generateSkylightMap();

        // チャンクを即座にロード済みとしてマーク
        chunk.setTerrainPopulated(true);
        chunk.setLightPopulated(true);
        chunk.markDirty();

        // デバッグログ（スパム防止のため一部のみ）
        if (x % 16 == 0 && z % 16 == 0) {
            System.out.println("[QuasarVoidChunkGenerator] Generated COMPLETE VOID chunk at [" + x + ", " + z + "]");
        }

        return chunk;
    }

    /**
     * チャンクを配置（デコレーション）- 完全に無効化
     * このメソッドが呼ばれても何もしない
     */
    @Override
    public void populate(int x, int z) {
        // 絶対に何も生成しない
        // 構造物、鉱石、植物、すべて無効

        // さらに確実にするため、チャンク内の非空気ブロックを削除
        Chunk chunk = world.getChunk(x, z);
        if (chunk != null && !chunk.isEmpty()) {
            clearChunkBlocks(chunk);
        }
    }

    /**
     * チャンク内のすべての非空気ブロックを削除
     */
    private void clearChunkBlocks(Chunk chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cleared = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 256; y++) {
                    pos.setPos(chunk.x * 16 + x, y, chunk.z * 16 + z);

                    if (!chunk.getBlockState(pos).getBlock().equals(Blocks.AIR)) {
                        chunk.setBlockState(pos, Blocks.AIR.getDefaultState());
                        cleared++;
                    }
                }
            }
        }

        if (cleared > 0) {
            chunk.markDirty();
            System.out.println("[QuasarVoidChunkGenerator] Cleared " + cleared +
                    " blocks from chunk [" + chunk.x + ", " + chunk.z + "]");
        }
    }

    /**
     * 初期構造物を生成 - 完全に無効化
     */
    @Override
    public boolean generateStructures(Chunk chunkIn, int x, int z) {
        // 構造物を一切生成しない
        return false;
    }

    /**
     * 可能なスポーン位置を取得 - 空リスト
     * モブスポーン完全無効化
     */
    @Override
    public List<Biome.SpawnListEntry> getPossibleCreatures(EnumCreatureType creatureType, BlockPos pos) {
        return Collections.emptyList();
    }

    /**
     * 最も近い構造物を取得 - 常にnull
     */
    @Nullable
    @Override
    public BlockPos getNearestStructurePos(World worldIn, String structureName, BlockPos position, boolean findUnexplored) {
        return null;
    }

    /**
     * チャンクを再配置（再生成） - 何もしない
     */
    @Override
    public void recreateStructures(Chunk chunkIn, int x, int z) {
        // 構造物の再生成を無効化
    }

    /**
     * 指定された構造物内にいるか - 常にfalse
     */
    @Override
    public boolean isInsideStructure(World worldIn, String structureName, BlockPos pos) {
        return false;
    }
}