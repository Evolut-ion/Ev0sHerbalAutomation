package com.Ev0sMods.Ev0sWoodCutter.interactions;
import com.hypixel.hytale.builtin.adventure.farming.interactions.ChangeFarmingStageInteraction;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CutterFarmingStageInteraction extends SimpleBlockInteraction {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final BuilderCodec<CutterFarmingStageInteraction> CODEC;
    public int targetStage = -1;
    @Nullable
    public Integer increaseBy = null;
    @Nullable
    public Integer decreaseBy = null;
    @Nullable
    public String targetStageSet = null;

    @Nonnull
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void interactWithBlock(@NotNull World world, @NotNull CommandBuffer<EntityStore> commandBuffer, @NotNull InteractionType interactionType, @NotNull InteractionContext interactionContext, @org.jetbrains.annotations.Nullable ItemStack itemStack, @NotNull Vector3i vector3i, @NotNull CooldownHandler cooldownHandler) {

    }

    public void interactWithBlock(@Nonnull World world, @Nonnull InteractionType type, @Nullable ItemStack itemInHand, @Nonnull Vector3i targetBlock) {
        int x = targetBlock.getX();
        int y = targetBlock.getY();
        int z = targetBlock.getZ();
        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Starting interaction at pos=(%d, %d, %d), increaseBy=%s, decreaseBy=%s, targetStage=%d, targetStageSet=%s", x, y, z, this.increaseBy, this.decreaseBy, this.targetStage, this.targetStageSet);
        WorldChunk worldChunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (worldChunk == null) {
            //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: worldChunk is null at pos=(%d, %d, %d)", x, y, z);
            //context.getState().state = InteractionState.Failed;
        } else {
            BlockType blockType = worldChunk.getBlockType(targetBlock);
            if (blockType == null) {
                //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: blockType is null at pos=(%d, %d, %d)", x, y, z);
                //context.getState().state = InteractionState.Failed;
            } else {
                //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Block type: %s (id=%s)", blockType.getId(), blockType.getClass().getSimpleName());
                FarmingData farmingConfig = blockType.getFarming();
                if (farmingConfig != null && farmingConfig.getStages() != null) {
                    //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Farming config found. StartingStageSet=%s, StageSetAfterHarvest=%s, AvailableStageSets=%s", farmingConfig.getStartingStageSet(), farmingConfig.getStageSetAfterHarvest(), farmingConfig.getStages() != null ? farmingConfig.getStages().keySet() : "null");
                    Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
                    WorldTimeResource worldTimeResource = (WorldTimeResource)world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType());
                    Instant now = worldTimeResource.getGameTime();
                    Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunkRef == null) {
                        //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: chunkRef is null at pos=(%d, %d, %d)", x, y, z);
                        //context.getState().state = InteractionState.Failed;
                    } else {
                        BlockComponentChunk blockComponentChunk = (BlockComponentChunk)chunkStore.getComponent(chunkRef, BlockComponentChunk.getComponentType());
                        if (blockComponentChunk == null) {
                           // ((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: blockComponentChunk is null at pos=(%d, %d, %d)", x, y, z);
                            //context.getState().state = InteractionState.Failed;
                        } else {
                            int blockIndexColumn = ChunkUtil.indexBlockInColumn(x, y, z);
                            Ref<ChunkStore> blockRef = blockComponentChunk.getEntityReference(blockIndexColumn);
                            boolean hadExistingBlockRef = blockRef != null;
                            //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Initial blockRef from getEntityReference: %s", hadExistingBlockRef ? "exists" : "null");
                            String initialStageSetLookup = this.targetStageSet != null ? this.targetStageSet : farmingConfig.getStartingStageSet();
                            FarmingStageData[] stages = (FarmingStageData[])farmingConfig.getStages().get(initialStageSetLookup);
                            if (stages != null && stages.length != 0) {
                                //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Initial stages lookup: stageSet=%s, stageCount=%d", initialStageSetLookup, stages.length);
                                FarmingBlock farmingBlock;
                                if (blockRef == null) {
                                    //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Creating new block entity (harvest0 pattern)");
                                    Holder<ChunkStore> blockEntity = ChunkStore.REGISTRY.newHolder();
                                    blockEntity.putComponent(BlockStateInfo.getComponentType(), new BlockModule.BlockStateInfo(blockIndexColumn, chunkRef));
                                    farmingBlock = new FarmingBlock();
                                    farmingBlock.setLastTickGameTime(now);
                                    farmingBlock.setCurrentStageSet(this.targetStageSet != null ? this.targetStageSet : farmingConfig.getStartingStageSet());
                                    int initStage = Math.max(0, stages.length - 2);
                                    farmingBlock.setGrowthProgress((float)initStage);
                                    blockEntity.addComponent(FarmingBlock.getComponentType(), farmingBlock);
                                    blockRef = chunkStore.addEntity(blockEntity, AddReason.SPAWN);
                                    //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Created new block entity with FarmingBlock: stageSet=%s, initialProgress=%d (second-to-last to avoid removal)", farmingBlock.getCurrentStageSet(), initStage);
                                    if (blockRef != null) {
                                        farmingBlock.setGrowthProgress((float)(stages.length - 1));
                                        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Updated growthProgress to %d (actual final stage)", stages.length - 1);
                                    }
                                } else {
                                    farmingBlock = (FarmingBlock)chunkStore.getComponent(blockRef, FarmingBlock.getComponentType());
                                    boolean hadExistingFarmingBlock = farmingBlock != null;
                                   // ((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Block entity exists, FarmingBlock component: %s", hadExistingFarmingBlock ? "exists" : "null");
                                    if (farmingBlock == null) {
                                        farmingBlock = new FarmingBlock();
                                        farmingBlock.setLastTickGameTime(now);
                                        farmingBlock.setCurrentStageSet(this.targetStageSet != null ? this.targetStageSet : farmingConfig.getStartingStageSet());
                                        farmingBlock.setGrowthProgress((float)(stages.length - 1));
                                        chunkStore.putComponent(blockRef, FarmingBlock.getComponentType(), farmingBlock);
                                        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Added FarmingBlock to existing entity: stageSet=%s, initialProgress=%d", farmingBlock.getCurrentStageSet(), stages.length - 1);
                                    } else {
                                        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Existing FarmingBlock: stageSet=%s, growthProgress=%.2f, lastTickGameTime=%d", farmingBlock.getCurrentStageSet(), farmingBlock.getGrowthProgress(), farmingBlock.getLastTickGameTime());
                                    }
                                }

                                if (blockRef == null) {
                                    //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: blockRef is still null after entity creation");
                                    //context.getState().state = InteractionState.Failed;
                                } else {
                                    String stageSetName = this.targetStageSet != null ? this.targetStageSet : farmingBlock.getCurrentStageSet();
                                    stages = (FarmingStageData[])farmingConfig.getStages().get(stageSetName);
                                    if (stages != null && stages.length != 0) {
                                        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Using stageSet=%s with %d stages", stageSetName, stages.length);
                                        int currentStage = (int)farmingBlock.getGrowthProgress();
                                        int originalCurrentStage = currentStage;
                                        if (currentStage >= stages.length) {
                                            //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Clamping currentStage from %d to %d (was >= stages.length)", currentStage, stages.length - 1);
                                            currentStage = stages.length - 1;
                                        }

                                        int stageIndex;
                                        if (this.increaseBy != null) {
                                            stageIndex = currentStage + this.increaseBy;
                                            //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Mode=INCREASE: %d + %d = %d", currentStage, this.increaseBy, stageIndex);
                                        } else if (this.decreaseBy != null) {
                                            stageIndex = currentStage - this.decreaseBy;
                                            //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Mode=DECREASE: %d - %d = %d", currentStage, this.decreaseBy, stageIndex);
                                        } else {
                                            stageIndex = this.targetStage;
                                            if (stageIndex < 0) {
                                                stageIndex = stages.length - 1;
                                            }

                                            //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Mode=ABSOLUTE: targetStage=%d, resolved=%d", this.targetStage, stageIndex);
                                        }

                                        int preClampStageIndex = stageIndex;
                                        if (stageIndex < 0) {
                                            stageIndex = 0;
                                        }

                                        if (stageIndex >= stages.length) {
                                            stageIndex = stages.length - 1;
                                        }

                                        if (preClampStageIndex != stageIndex) {
                                            //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Clamped stageIndex from %d to %d", preClampStageIndex, stageIndex);
                                        }

                                        int previousStageIndex = (int)farmingBlock.getGrowthProgress();
                                        FarmingStageData previousStage = null;
                                        FarmingStageData[] currentStages = (FarmingStageData[])farmingConfig.getStages().get(farmingBlock.getCurrentStageSet());
                                        if (currentStages != null && previousStageIndex >= 0 && previousStageIndex < currentStages.length) {
                                            previousStage = currentStages[previousStageIndex];
                                        }

                                        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Previous stage data: index=%d, hasPreviousStage=%s", previousStageIndex, previousStage != null);
                                        farmingBlock.setCurrentStageSet(stageSetName);
                                        farmingBlock.setGrowthProgress((float)stageIndex);
                                        farmingBlock.setExecutions(0);
                                        farmingBlock.setGeneration(farmingBlock.getGeneration() + 1);
                                        farmingBlock.setLastTickGameTime(now);
                                        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Updated FarmingBlock: stageSet=%s, growthProgress=%d, generation=%d", stageSetName, stageIndex, farmingBlock.getGeneration());
                                        Ref<ChunkStore> sectionRef = world.getChunkStore().getChunkSectionReference(ChunkUtil.chunkCoordinate(x), ChunkUtil.chunkCoordinate(y), ChunkUtil.chunkCoordinate(z));
                                        if (sectionRef != null) {
                                            BlockSection section = (BlockSection)chunkStore.getComponent(sectionRef, BlockSection.getComponentType());
                                            if (section != null) {
                                                section.scheduleTick(ChunkUtil.indexBlock(x, y, z), now);
                                            }

                                            stages[stageIndex].apply(chunkStore, sectionRef, blockRef, x, y, z, previousStage);
                                            //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] Applied stage %d via stages[%d].apply()", stageIndex, stageIndex);
                                        } else {
                                            //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] sectionRef was null - could not apply stage!");
                                        }

                                        worldChunk.setTicking(x, y, z, true);
                                        //((HytaleLogger.Api)LOGGER.atInfo()).log("[ChangeFarmingStage] SUCCESS: Changed stage from %d to %d at pos=(%d, %d, %d)", originalCurrentStage, stageIndex, x, y, z);
                                    } else {
                                        //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: stages null/empty after re-fetch with stageSet=%s", stageSetName);
                                        //context.getState().state = InteractionState.Failed;
                                    }
                                }
                            } else {
                                //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: stages is null or empty for stageSet=%s", initialStageSetLookup);
                                //context.getState().state = InteractionState.Failed;
                            }
                        }
                    }
                } else {
                    //((HytaleLogger.Api)LOGGER.atWarning()).log("[ChangeFarmingStage] FAILED: farmingConfig is null or has no stages. blockType=%s, hasFarmingConfig=%s", blockType.getId(), farmingConfig != null);
                    //context.getState().state = InteractionState.Failed;
                }
            }
        }
    }

    protected void simulateInteractWithBlock(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nullable ItemStack itemInHand, @Nonnull World world, @Nonnull Vector3i targetBlock) {
    }

    @Nonnull
    public String toString() {
        int var10000 = this.targetStage;
        return "ChangeFarmingStageInteraction{targetStage=" + var10000 + ", increaseBy=" + this.increaseBy + ", decreaseBy=" + this.decreaseBy + ", targetStageSet='" + this.targetStageSet + "'} " + super.toString();
    }
    static {
        CODEC = BuilderCodec.builder(CutterFarmingStageInteraction.class, CutterFarmingStageInteraction::new, SimpleBlockInteraction.CODEC).build();
    }

}

