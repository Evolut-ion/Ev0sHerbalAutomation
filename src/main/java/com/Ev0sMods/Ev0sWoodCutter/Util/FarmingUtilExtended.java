package com.Ev0sMods.Ev0sWoodCutter.Util;


import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.HashUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.Rangef;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.HarvestingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.GrowthModifierAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.time.Instant;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class FarmingUtilExtended {
    private static final int MAX_SECONDS_BETWEEN_TICKS = 15;
    private static final int BETWEEN_RANDOM = 10;

    public static void tickFarming(CommandBuffer<ChunkStore> commandBuffer, BlockChunk blockChunk, BlockSection blockSection, Ref<ChunkStore> sectionRef, Ref<ChunkStore> blockRef, FarmingBlock farmingBlock, int x, int y, int z, boolean initialTick) {
        World world = ((ChunkStore)commandBuffer.getExternalData()).getWorld();
        WorldTimeResource worldTimeResource = (WorldTimeResource)world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType());
        Instant currentTime = worldTimeResource.getGameTime();
        BlockType blockType = farmingBlock.getPreviousBlockType() != null ? (BlockType)BlockType.getAssetMap().getAsset(farmingBlock.getPreviousBlockType()) : (BlockType)BlockType.getAssetMap().getAsset(blockSection.get(x, y, z));
        if (blockType != null) {
            if (blockType.getFarming() != null) {
                FarmingData farmingConfig = blockType.getFarming();
                if (farmingConfig.getStages() != null) {
                    float currentProgress = farmingBlock.getGrowthProgress();
                    int currentStage = (int)currentProgress;
                    String currentStageSet = farmingBlock.getCurrentStageSet();
                    FarmingStageData[] stages = currentStageSet != null ? (FarmingStageData[])farmingConfig.getStages().get(currentStageSet) : null;
                    if (stages == null) {
                        currentStageSet = farmingConfig.getStartingStageSet();
                        if (currentStageSet == null) {
                            return;
                        }

                        farmingBlock.setCurrentStageSet(currentStageSet);
                        stages = (FarmingStageData[])farmingConfig.getStages().get(currentStageSet);
                        blockChunk.markNeedsSaving();
                    }

                    if (stages != null) {
                        if (currentStage < 0) {
                            currentStage = 0;
                            currentProgress = 0.0F;
                            farmingBlock.setGrowthProgress(0.0F);
                        }

                        if (currentStage >= stages.length) {
                            commandBuffer.removeEntity(blockRef, RemoveReason.REMOVE);
                        } else {
                            long remainingTimeSeconds = currentTime.getEpochSecond() - farmingBlock.getLastTickGameTime().getEpochSecond();
                            ChunkSection section = (ChunkSection)commandBuffer.getComponent(sectionRef, ChunkSection.getComponentType());
                            int worldX = ChunkUtil.worldCoordFromLocalCoord(section.getX(), x);
                            int worldY = ChunkUtil.worldCoordFromLocalCoord(section.getY(), y);
                            int worldZ = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), z);

                            while(currentStage < stages.length) {
                                FarmingStageData stage = stages[currentStage];
                                if (stage.shouldStop(commandBuffer, sectionRef, blockRef, x, y, z)) {
                                    blockChunk.markNeedsSaving();
                                    farmingBlock.setGrowthProgress((float)stages.length);
                                    commandBuffer.removeEntity(blockRef, RemoveReason.REMOVE);
                                    break;
                                }

                                Rangef range = stage.getDuration();
                                if (range == null) {
                                    blockChunk.markNeedsSaving();
                                    commandBuffer.removeEntity(blockRef, RemoveReason.REMOVE);
                                    break;
                                }

                                double rand = HashUtil.random((long)farmingBlock.getGeneration(), (long)worldX, (long)worldY, (long)worldZ);
                                double baseDuration = (double)range.min + (double)(range.max - range.min) * rand;
                                long remainingDurationSeconds = Math.round(baseDuration * ((double)1.0F - (double)currentProgress % (double)1.0F));
                                double growthMultiplier = (double)1.0F;
                                if (farmingConfig.getGrowthModifiers() != null) {
                                    for(String modifierName : farmingConfig.getGrowthModifiers()) {
                                        GrowthModifierAsset modifier = (GrowthModifierAsset)GrowthModifierAsset.getAssetMap().getAsset(modifierName);
                                        if (modifier != null) {
                                            growthMultiplier *= modifier.getCurrentGrowthMultiplier(commandBuffer, sectionRef, blockRef, x, y, z, initialTick);
                                        }
                                    }
                                }

                                remainingDurationSeconds = Math.round((double)remainingDurationSeconds / growthMultiplier);
                                if (remainingTimeSeconds < remainingDurationSeconds) {
                                    currentProgress += (float)((double)remainingTimeSeconds / (baseDuration / growthMultiplier));
                                    farmingBlock.setGrowthProgress(currentProgress);
                                    long nextGrowthInNanos = (remainingDurationSeconds - remainingTimeSeconds) * 1000000000L;
                                    long randCap = (long)(((double)15.0F + (double)10.0F * HashUtil.random((long)farmingBlock.getGeneration() ^ 3405692655L, (long)worldX, (long)worldY, (long)worldZ)) * (double)world.getTps() * WorldTimeResource.getSecondsPerTick(world) * (double)1.0E9F);
                                    long cappedNextGrowthInNanos = Math.min(nextGrowthInNanos, randCap);
                                    blockSection.scheduleTick(ChunkUtil.indexBlock(x, y, z), currentTime.plusNanos(cappedNextGrowthInNanos));
                                    break;
                                }

                                remainingTimeSeconds -= remainingDurationSeconds;
                                ++currentStage;
                                currentProgress = (float)currentStage;
                                farmingBlock.setGrowthProgress(currentProgress);
                                blockChunk.markNeedsSaving();
                                farmingBlock.setGeneration(farmingBlock.getGeneration() + 1);
                                if (currentStage >= stages.length) {
                                    if (stages[currentStage - 1].implementsShouldStop()) {
                                        currentStage = stages.length - 1;
                                        farmingBlock.setGrowthProgress((float)currentStage);
                                        stages[currentStage].apply(commandBuffer, sectionRef, blockRef, x, y, z, stages[currentStage]);
                                    } else {
                                        farmingBlock.setGrowthProgress((float)stages.length);
                                        commandBuffer.removeEntity(blockRef, RemoveReason.REMOVE);
                                    }
                                } else {
                                    farmingBlock.setExecutions(0);
                                    stages[currentStage].apply(commandBuffer, sectionRef, blockRef, x, y, z, stages[currentStage - 1]);
                                }
                            }

                            farmingBlock.setLastTickGameTime(currentTime);
                        }
                    }
                }
            }
        }
    }

    public static void harvest(@Nonnull World world, @Nonnull ComponentAccessor<EntityStore> store, @Nonnull BlockType blockType, int rotationIndex, @Nonnull Vector3i blockPosition) {
        if (world.getGameplayConfig().getWorldConfig().isBlockGatheringAllowed()) {
            harvest0(store,  blockType, rotationIndex, blockPosition);
        }

    }

    @Nullable
    public static CapturedNPCMetadata generateCapturedNPCMetadata(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> entityRef, int roleIndex) {
        PersistentModel persistentModel = (PersistentModel)componentAccessor.getComponent(entityRef, PersistentModel.getComponentType());
        if (persistentModel == null) {
            return null;
        } else {
            ModelAsset modelAsset = (ModelAsset)ModelAsset.getAssetMap().getAsset(persistentModel.getModelReference().getModelAssetId());
            CapturedNPCMetadata meta = new CapturedNPCMetadata();
            if (modelAsset != null) {
                meta.setIconPath(modelAsset.getIcon());
            }

            meta.setRoleIndex(roleIndex);
            return meta;
        }
    }

    public static boolean harvest0(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull BlockType blockType, int rotationIndex, @Nonnull Vector3i blockPosition) {
        FarmingData farmingConfig = blockType.getFarming();
        if (farmingConfig != null && farmingConfig.getStages() != null) {
            if (blockType.getGathering().getHarvest() == null) {
                return false;
            } else {
                World world = ((EntityStore)store.getExternalData()).getWorld();
                Vector3d centerPosition = new Vector3d();
                blockType.getBlockCenter(rotationIndex, centerPosition);
                centerPosition.add(blockPosition);
                if (farmingConfig.getStageSetAfterHarvest() == null) {
                    giveDrops(store, centerPosition, blockType);
                    WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(blockPosition.x, blockPosition.z));
                    if (chunk != null) {
                        chunk.breakBlock(blockPosition.x, blockPosition.y, blockPosition.z);
                    }

                    return true;
                } else {
                    giveDrops(store, centerPosition, blockType);
                    Map<String, FarmingStageData[]> stageSets = farmingConfig.getStages();
                    FarmingStageData[] stages = (FarmingStageData[])stageSets.get(farmingConfig.getStartingStageSet());
                    if (stages == null) {
                        return false;
                    } else {
                        int currentStageIndex = stages.length - 1;
                        FarmingStageData previousStage = stages[currentStageIndex];
                        String newStageSet = farmingConfig.getStageSetAfterHarvest();
                        FarmingStageData[] newStages = (FarmingStageData[])stageSets.get(newStageSet);
                        if (newStages != null && newStages.length != 0) {
                            Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
                            Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(ChunkUtil.indexChunkFromBlock(blockPosition.x, blockPosition.z));
                            if (chunkRef == null) {
                                return false;
                            } else {
                                BlockComponentChunk blockComponentChunk = (BlockComponentChunk)chunkStore.getComponent(chunkRef, BlockComponentChunk.getComponentType());
                                if (blockComponentChunk == null) {
                                    return false;
                                } else {
                                    Instant now = ((WorldTimeResource)((EntityStore)store.getExternalData()).getWorld().getEntityStore().getStore().getResource(WorldTimeResource.getResourceType())).getGameTime();
                                    int blockIndexColumn = ChunkUtil.indexBlockInColumn(blockPosition.x, blockPosition.y, blockPosition.z);
                                    Ref<ChunkStore> blockRef = blockComponentChunk.getEntityReference(blockIndexColumn);
                                    FarmingBlock farmingBlock;
                                    if (blockRef == null) {
                                        Holder<ChunkStore> blockEntity = ChunkStore.REGISTRY.newHolder();
                                        blockEntity.putComponent(BlockStateInfo.getComponentType(), new BlockModule.BlockStateInfo(blockIndexColumn, chunkRef));
                                        farmingBlock = new FarmingBlock();
                                        farmingBlock.setLastTickGameTime(now);
                                        farmingBlock.setCurrentStageSet(newStageSet);
                                        blockEntity.addComponent(FarmingBlock.getComponentType(), farmingBlock);
                                        blockRef = chunkStore.addEntity(blockEntity, AddReason.SPAWN);
                                    } else {
                                        farmingBlock = (FarmingBlock)chunkStore.ensureAndGetComponent(blockRef, FarmingBlock.getComponentType());
                                    }

                                    farmingBlock.setCurrentStageSet(newStageSet);
                                    farmingBlock.setGrowthProgress(0.0F);
                                    farmingBlock.setExecutions(0);
                                    farmingBlock.setGeneration(farmingBlock.getGeneration() + 1);
                                    farmingBlock.setLastTickGameTime(now);
                                    Ref<ChunkStore> sectionRef = world.getChunkStore().getChunkSectionReference(ChunkUtil.chunkCoordinate(blockPosition.x), ChunkUtil.chunkCoordinate(blockPosition.y), ChunkUtil.chunkCoordinate(blockPosition.z));
                                    if (sectionRef == null) {
                                        return false;
                                    } else if (blockRef == null) {
                                        return false;
                                    } else {
                                        BlockSection section = (BlockSection)chunkStore.getComponent(sectionRef, BlockSection.getComponentType());
                                        if (section != null) {
                                            section.scheduleTick(ChunkUtil.indexBlock(blockPosition.x, blockPosition.y, blockPosition.z), now);
                                        }

                                        newStages[0].apply(chunkStore, sectionRef, blockRef, blockPosition.x, blockPosition.y, blockPosition.z, previousStage);
                                        return true;
                                    }
                                }
                            }
                        } else {
                            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(blockPosition.x, blockPosition.z));
                            if (chunk != null) {
                                chunk.breakBlock(blockPosition.x, blockPosition.y, blockPosition.z);
                            }

                            return false;
                        }
                    }
                }
            }
        } else {
            return false;
        }
    }

    protected static void giveDrops(@Nonnull ComponentAccessor<EntityStore> store, @Nonnull Vector3d origin, @Nonnull BlockType blockType) {
        HarvestingDropType harvest = blockType.getGathering().getHarvest();
        String itemId = harvest.getItemId();
        String dropListId = harvest.getDropListId();
        BlockHarvestUtils.getDrops(blockType, 1, itemId, dropListId);
    }
}

