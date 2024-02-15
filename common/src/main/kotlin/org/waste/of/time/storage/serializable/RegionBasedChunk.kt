package org.waste.of.time.storage.serializable

import net.minecraft.SharedConstants
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.entity.BlockEntity
import net.minecraft.block.entity.LockableContainerBlockEntity
import net.minecraft.fluid.Fluid
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtLongArray
import net.minecraft.nbt.NbtOps
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKeys
import net.minecraft.text.MutableText
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.ChunkSectionPos
import net.minecraft.world.ChunkSerializer
import net.minecraft.world.LightType
import net.minecraft.world.biome.BiomeKeys
import net.minecraft.world.chunk.BelowZeroRetrogen
import net.minecraft.world.chunk.PalettedContainer
import net.minecraft.world.chunk.WorldChunk
import net.minecraft.world.gen.chunk.BlendingData
import net.minecraft.world.level.storage.LevelStorage
import org.waste.of.time.Utils.addAuthor
import org.waste.of.time.WorldTools
import org.waste.of.time.WorldTools.config
import org.waste.of.time.extension.IBlockEntityContainerExtension
import org.waste.of.time.manager.MessageManager.translateHighlight
import org.waste.of.time.manager.StatisticManager
import org.waste.of.time.storage.Cacheable
import org.waste.of.time.storage.CustomRegionBasedStorage
import org.waste.of.time.storage.RegionBased
import org.waste.of.time.storage.cache.HotCache

class RegionBasedChunk(val chunk: WorldChunk) : RegionBased(chunk.pos, chunk.world, "region"), Cacheable {
    // storing a reference to the block entities in the chunk
    // so it doesn't get removed out from under us while the chunk is unloaded
    private var cachedBlockEntities: Map<BlockPos, BlockEntity>? = null

    fun cacheBlockEntities() {
        cachedBlockEntities = HashMap(chunk.blockEntities)
    }

    private fun getBlockEntities() : Map<BlockPos, BlockEntity> {
        return cachedBlockEntities ?: chunk.blockEntities
    }

    override fun shouldStore() = config.capture.chunks

    override val verboseInfo: MutableText
        get() = translateHighlight(
            "worldtools.capture.saved.chunks",
            chunkPos,
            dimension
        )

    override val anonymizedInfo: MutableText
        get() = translateHighlight(
            "worldtools.capture.saved.chunks.anonymized",
            dimension
        )

    private val stateIdContainer = PalettedContainer.createPalettedContainerCodec(
        Block.STATE_IDS,
        BlockState.CODEC,
        PalettedContainer.PaletteProvider.BLOCK_STATE,
        Blocks.AIR.defaultState
    )

    override fun cache() {
        HotCache.chunks[chunkPos] = this
        HotCache.savedChunks.add(chunkPos.toLong())
    }

    override fun flush() {
        HotCache.chunks.remove(chunkPos)
    }

    override fun incrementStats() {
        StatisticManager.chunks++
        StatisticManager.dimensions.add(dimension)
    }

    override fun writeToStorage(session: LevelStorage.Session, storage: CustomRegionBasedStorage, cachedStorages: MutableMap<String, CustomRegionBasedStorage>) {
        // avoiding `emit` here due to flow order issues when capture is stopped
        // i.e. if EndFlow is emitted before this, these are not written because they're behind it in the flow
        HotCache.getEntitySerializableForChunk(chunkPos, world)?.store(session, cachedStorages)
            ?: run {
                // remove any previously stored entities in this chunk
                RegionBasedEntities(chunkPos, emptySet(), world).store(session, cachedStorages)
        }
        if (this.chunk.isEmpty) return
        super.writeToStorage(session, storage, cachedStorages)
    }

    /**
     * See [net.minecraft.world.ChunkSerializer.serialize]
     */
    override fun compound(storage: CustomRegionBasedStorage) = NbtCompound().apply {
        addAuthor()

        putInt("DataVersion", SharedConstants.getGameVersion().saveVersion.id)
        putInt(ChunkSerializer.X_POS_KEY, chunk.pos.x)
        putInt("yPos", chunk.bottomSectionCoord)
        putInt(ChunkSerializer.Z_POS_KEY, chunk.pos.z)
        putLong("LastUpdate", chunk.world.time)
        putLong("InhabitedTime", chunk.inhabitedTime)
        putString("Status", Registries.CHUNK_STATUS.getId(chunk.status).toString())

        genBackwardsCompat(chunk)

        if (!chunk.upgradeData.isDone) {
            put("UpgradeData", chunk.upgradeData.toNbt())
        }

        put(ChunkSerializer.SECTIONS_KEY, generateSections(chunk))

        if (chunk.isLightOn) {
            putBoolean(ChunkSerializer.IS_LIGHT_ON_KEY, true)
        }

        put("block_entities", NbtList().apply {
            upsertBlockEntities(this, storage)
        })

        getTickSchedulers(chunk)
        genPostProcessing(chunk)

        // skip structures
        if (config.debug.logSavedChunks)
            WorldTools.LOG.info("Chunk saved: $chunkPos ($dimension)")
    }

    private fun upsertBlockEntities(
        outputNbt: NbtList,
        storage: CustomRegionBasedStorage
    ) {
        val existingContainers = getExistingContainerBlockEntities(storage, chunkPos)
        getBlockEntities().entries.map {
            if (it.value is LockableContainerBlockEntity) {
                existingContainers.find { existing -> existing.pos == it.key }?.let { existing ->
                    mergeInventoryContainerContents(existing, it.value as LockableContainerBlockEntity)
                }
            }
            getPackedBlockEntityNbt(it)
        }.forEach { outputNbt.add(it) }
    }

    private fun getExistingContainerBlockEntities(
        storage: CustomRegionBasedStorage,
        chunkPos: ChunkPos
    ): List<LockableContainerBlockEntity> {
        return storage.getBlockEntities(chunkPos)
            .filterIsInstance<LockableContainerBlockEntity>()
            .toList()
    }

    // in-place merge onto the new block entity container contents
    private fun mergeInventoryContainerContents(
        existing: LockableContainerBlockEntity,
        new: LockableContainerBlockEntity
    ) {
        if ((new as IBlockEntityContainerExtension).wtContentsRead) return
        if (existing.pos != new.pos) {
            WorldTools.LOG.warn("[Container Merge] BlockEntity at ${new.pos} is not at the expected pos ${existing.pos}")
            return
        }
        if (existing.javaClass != new.javaClass) {
            WorldTools.LOG.warn("[Container Merge] BlockEntity type ${new.javaClass} is not the expected type ${existing.javaClass}")
            return
        }
        // todo: we need extra context to know if the newStacks have been captured during the WDL
        //  or if we just loaded the block entity without viewing its content
        //  e.g. the player reloads a block entity and removes all items from its inventory during WDL
        var shouldOverwrite = true
        for (i in 0 until new.size()) {
            if (new.getStack(i) != ItemStack.EMPTY) {
                shouldOverwrite = false
                break
            }
        }
        if (shouldOverwrite) {
            for (i in 0 until existing.size()) {
                new.setStack(i, existing.getStack(i))
            }
        }
    }

    private fun getPackedBlockEntityNbt(entry: Map.Entry<BlockPos, BlockEntity>): NbtCompound {
        val blockEntity: BlockEntity = entry.value
        var nbtCompound: NbtCompound = blockEntity.createNbtWithIdentifyingData()
        nbtCompound.putBoolean("keepPacked", false)
        return nbtCompound
    }

    private fun generateSections(chunk: WorldChunk) = NbtList().apply {
        val biomeRegistry = chunk.world.registryManager.get(RegistryKeys.BIOME)
        val biomeCodec = PalettedContainer.createReadableContainerCodec(
            biomeRegistry.indexedEntries,
            biomeRegistry.createEntryCodec(),
            PalettedContainer.PaletteProvider.BIOME,
            biomeRegistry.entryOf(BiomeKeys.PLAINS)
        )
        val lightingProvider = chunk.world.chunkManager.lightingProvider

        (lightingProvider.bottomY until lightingProvider.topY).forEach { y ->
            val sectionCoord = chunk.sectionCoordToIndex(y)
            val inSection = sectionCoord in (0 until chunk.sectionArray.size)
            val blockLightSection =
                lightingProvider[LightType.BLOCK].getLightSection(ChunkSectionPos.from(chunk.pos, y))
            val skyLightSection =
                lightingProvider[LightType.SKY].getLightSection(ChunkSectionPos.from(chunk.pos, y))

            if (!inSection && blockLightSection == null && skyLightSection == null) return@forEach

            add(NbtCompound().apply {
                if (inSection) {
                    val chunkSection = chunk.sectionArray[sectionCoord]

                    put(
                        "block_states",
                        stateIdContainer.encodeStart(NbtOps.INSTANCE, chunkSection.blockStateContainer).getOrThrow(
                            false
                        ) { WorldTools.LOG.error(it) }
                    )
                    put(
                        "biomes",
                        biomeCodec.encodeStart(NbtOps.INSTANCE, chunkSection.biomeContainer).getOrThrow(
                            false
                        ) { WorldTools.LOG.error(it) }
                    )
                }
                if (blockLightSection != null && !blockLightSection.isUninitialized) {
                    putByteArray(ChunkSerializer.BLOCK_LIGHT_KEY, blockLightSection.asByteArray())
                }
                if (skyLightSection != null && !skyLightSection.isUninitialized) {
                    putByteArray(ChunkSerializer.SKY_LIGHT_KEY, skyLightSection.asByteArray())
                }
                if (isEmpty) return@forEach
                putByte("Y", y.toByte())
            })
        }
    }

    private fun NbtCompound.genBackwardsCompat(chunk: WorldChunk) {
        chunk.blendingData?.let { bleedingData ->
            BlendingData.CODEC.encodeStart(NbtOps.INSTANCE, bleedingData).resultOrPartial {
                WorldTools.LOG.error(it)
            }.ifPresent {
                put("blending_data", it)
            }
        }

        chunk.belowZeroRetrogen?.let { belowZeroRetrogen ->
            BelowZeroRetrogen.CODEC.encodeStart(NbtOps.INSTANCE, belowZeroRetrogen).resultOrPartial {
                WorldTools.LOG.error(it)
            }.ifPresent {
                put("below_zero_retrogen", it)
            }
        }
    }

    private fun NbtCompound.getTickSchedulers(chunk: WorldChunk) {
        val tickSchedulers = chunk.tickSchedulers
        val time = chunk.world.levelProperties.time

        put("block_ticks", tickSchedulers.blocks().toNbt(time) { block: Block? ->
            Registries.BLOCK.getId(block).toString()
        })
        put("fluid_ticks", tickSchedulers.fluids().toNbt(time) { fluid: Fluid? ->
            Registries.FLUID.getId(fluid).toString()
        })
    }

    private fun NbtCompound.genPostProcessing(chunk: WorldChunk) {
        put("PostProcessing", ChunkSerializer.toNbt(chunk.postProcessingLists))

        put(ChunkSerializer.HEIGHTMAPS_KEY, NbtCompound().apply {
            chunk.heightmaps.filter {
                chunk.status.heightmapTypes.contains(it.key)
            }.forEach { (key, value) ->
                put(key.getName(), NbtLongArray(value.asLongArray()))
            }
        })
    }
}
