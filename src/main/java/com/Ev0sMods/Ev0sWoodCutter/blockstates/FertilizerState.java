package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.Ev0sMods.Ev0sWoodCutter.interactions.CutterFarmingStageInteraction;
import com.hypixel.hytale.builtin.adventure.farming.FarmingSystems;
import com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ResourceType;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import it.unimi.dsi.fastutil.objects.ObjectList;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("removal")
public class FertilizerState extends ItemContainerState implements TickableBlockState, ItemContainerBlockState {
    public World w;
    private int square;
    public ItemContainer ic;
    public static final BuilderCodec<FertilizerState> CODEC = BuilderCodec.builder(FertilizerState.class, FertilizerState::new, BlockState.BASE_CODEC)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();
    public Data data;
    public int timer = 0;
    public int processingTimer = 0;
    public int durationTimer = 0;
    public boolean isProcessing = false;
    public boolean hasFertilizer = false;
    public boolean hasWater = false;
    public boolean hasFertilizerWater = false;
    public boolean hasConsumedResources = false;
    public int inputTimer = 0; // Timer for managing input processing

    @Override
    public void tick(
            float v,
            int i,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        World w = store.getExternalData().getWorld();
        
        // Check for items in the block's inventory slots
        if (processingTimer%20 == 0) { // Check every 20 ticks (about every second) to reduce overhead
        checkInputItems(w);
        fixSlotAssignments(w);
        }
        if (isProcessing) {
            processingTimer++;
            durationTimer++;
            
            // Water + Fertilizer: 1800 ticks for 1 minute (60 seconds * 30 TPS = 1800 ticks)
            if (hasFertilizer && hasWater && !hasFertilizerWater) {
                if (processingTimer >= 1800) {
                    applyGrowthTick(w);
                    processingTimer = 0;
                    
                    // Stop after 1 minute (60 seconds * 30 TPS = 1800 ticks)
                    if (durationTimer >= 1800) {
                        stopProcessing();
                    }
                }
            }
            // Fertilizer + Fertilizer Water: 15 ticks for 5 minutes (300 seconds * 30 TPS = 9000 ticks)
            else if (hasFertilizer && hasFertilizerWater) {
                if (processingTimer >= 15) {
                    applyGrowthTick(w);
                    processingTimer = 0;
                    
                    // Stop after 5 minutes (300 seconds * 30 TPS = 9000 ticks)
                    if (durationTimer >= 9000) {
                        stopProcessing();
                    }
                }
            } else {
                // If we don't have the right combination, stop processing
                stopProcessing();
            }
        } else {
            timer++;
            if (timer >= 300) { // Reduced from 150 to 300 (tick every 10 seconds instead of 5)
                timer = 0;
                // Only apply growth tick when fueled (has fertilizer and water)
                if (hasFertilizer && (hasWater || hasFertilizerWater)) {
                    applyGrowthTick(w);
                }
            }
        }
        
        // Check if we need to stop processing due to lack of resources
        if (isProcessing && !hasFertilizer) {
            stopProcessing();
        }
    }

    private void checkInputItems(World w) {
        // Check items in the container slots
        if (this.getItemContainer() != null) {
            // Slot 0: Fertilizer
            ItemStack fertilizerSlot = this.getItemContainer().getItemStack((short)0);
            // Slot 1: Water or Fertilizer Water
            ItemStack waterSlot = this.getItemContainer().getItemStack((short)1);
            
            hasFertilizer = fertilizerSlot != null && fertilizerSlot.getItemId().equals("Tool_Fertilizer");
            hasWater = waterSlot != null && (
                waterSlot.getItemId().equals("Container_Bucket_State_Filled_Water") ||
                waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Water")
            );
            hasFertilizerWater = waterSlot != null && (
                waterSlot.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
                waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
            );
            
            // Only start processing if we have fertilizer and either water or fertilizer water
            if (hasFertilizer && (hasWater || hasFertilizerWater)) {
                isProcessing = true;
                // Don't reset processingTimer here - only reset it after applying growth tick
                hasConsumedResources = false; // Reset consumption flag when starting
            } else {
                isProcessing = false;
            }
        } else {
            hasFertilizer = false;
            hasWater = false;
            hasFertilizerWater = false;
            isProcessing = false;
        }
    }

    private void applyGrowthTick(World w) {
        
        int baseX = this.getBlockX();
        int baseY = this.getBlockY();
        int baseZ = this.getBlockZ();
        int rotation = getRotationIndex();

        // Define the area to affect based on rotation
        int minX, maxX, minZ, maxZ;
        
        switch (rotation) {
            case 0: // Facing positive Z
                minX = baseX - 2; maxX = baseX + 2;
                minZ = baseZ; maxZ = baseZ + 5;
                break;
            case 1: // Facing negative Z
                minX = baseX - 2; maxX = baseX + 2;
                minZ = baseZ - 5; maxZ = baseZ;
                break;
            case 2: // Facing positive X
                minX = baseX; maxX = baseX + 5;
                minZ = baseZ - 2; maxZ = baseZ + 2;
                break;
            case 3: // Facing negative X
                minX = baseX - 5; maxX = baseX;
                minZ = baseZ - 2; maxZ = baseZ + 2;
                break;
            default:
                minX = baseX - 2; maxX = baseX + 2;
                minZ = baseZ; maxZ = baseZ + 5;
                break;
        }
        
        
        int cropsFound = 0;
        int cropsAdvanced = 0;
        
        // Apply growth to crops in the affected area
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vector3i targetPos = new Vector3i(x, baseY, z);
                
                try {
                    // Get the chunk and check if there's a farming block at this position
                    WorldChunk chunk = w.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk != null) {
                        BlockType blockType = chunk.getBlockType(targetPos);
                        if (blockType != null && blockType.getFarming() != null) {
                            cropsFound++;
                            
                            // Get the FarmingBlock component and advance its growth
                            Store<ChunkStore> chunkStore = w.getChunkStore().getStore();
                            Ref<ChunkStore> chunkRef = w.getChunkStore().getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
                            if (chunkRef != null) {
                                BlockComponentChunk blockComponentChunk = (BlockComponentChunk)chunkStore.getComponent(chunkRef, BlockComponentChunk.getComponentType());
                                if (blockComponentChunk != null) {
                                    int blockIndexColumn = ChunkUtil.indexBlockInColumn(x, baseY, z);
                                    Ref<ChunkStore> blockRef = blockComponentChunk.getEntityReference(blockIndexColumn);
                                    if (blockRef != null) {
                                        FarmingBlock farmingBlock = (FarmingBlock)chunkStore.getComponent(blockRef, FarmingBlock.getComponentType());
                                        if (farmingBlock != null) {
                                            // Get the current stage and advance it
                                            float currentProgress = farmingBlock.getGrowthProgress();
                                            int currentStage = (int)currentProgress;
                                            
                                            // Get the stages to know the maximum
                                            FarmingData farmingConfig = blockType.getFarming();
                                            if (farmingConfig.getStages() != null) {
                                                String currentStageSet = farmingBlock.getCurrentStageSet();
                                                if (currentStageSet == null) {
                                                    currentStageSet = farmingConfig.getStartingStageSet();
                                                }
                                                
                                                FarmingStageData[] stages = (FarmingStageData[])farmingConfig.getStages().get(currentStageSet);
                                                if (stages != null && stages.length > 0) {
                                                    // Advance to next stage, but don't exceed the maximum
                                                    int nextStage = Math.min(currentStage + 1, stages.length - 1);
                                                    if (nextStage > currentStage) {
                                                        // Update the FarmingBlock component
                                                        farmingBlock.setGrowthProgress((float)nextStage);
                                                        farmingBlock.setGeneration(farmingBlock.getGeneration() + 1);
                                                        
                                                        // Get the previous stage data for the apply method
                                                        FarmingStageData previousStage = null;
                                                        if (currentStage >= 0 && currentStage < stages.length) {
                                                            previousStage = stages[currentStage];
                                                        }
                                                        
                                                        // Apply the stage change to the block
                                                        Ref<ChunkStore> sectionRef = w.getChunkStore().getChunkSectionReference(ChunkUtil.chunkCoordinate(x), ChunkUtil.chunkCoordinate(baseY), ChunkUtil.chunkCoordinate(z));
                                                        if (sectionRef != null) {
                                                            stages[nextStage].apply(chunkStore, sectionRef, blockRef, x, baseY, z, previousStage);
                                                            cropsAdvanced++;
                                                        } 
                                                    } 
                                                } 
                                            } 
                                        } 
                                    } 
                                } 
                            } 
                        } 
                    } 
                } catch (Exception e) {
                    //HytaleLogger.getLogger().atWarning().log("FertilizerState: Failed to apply growth to block at " + targetPos + ": " + e.getMessage());
                }
            }
        }
        
        
        // Consume resources after applying growth tick
        consumeResources();
    }

    private void consumeResources() {
        if (this.getItemContainer() != null) {
            // Remove one fertilizer from slot 0
            ItemStack fertilizerSlot = this.getItemContainer().getItemStack((short)0);
            if (fertilizerSlot != null && fertilizerSlot.getItemId().equals("Tool_Fertilizer")) {
                this.getItemContainer().removeItemStackFromSlot((short)0, 1);
            }
            
            // Remove one water or fertilizer water from slot 1
            ItemStack waterSlot = this.getItemContainer().getItemStack((short)1);
            if (waterSlot != null && (
                waterSlot.getItemId().equals("Container_Bucket_State_Filled_Water") ||
                waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Water") ||
                waterSlot.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
                waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
            )) {
                this.getItemContainer().removeItemStackFromSlot((short)1, 1);
                //HytaleLogger.getLogger().atInfo().log("FertilizerState: Consumed 1 water/fertilizer water from slot 1");
            }
        }
    }

    private void stopProcessing() {
        isProcessing = false;
        processingTimer = 0;
        durationTimer = 0;
        hasFertilizer = false;
        hasWater = false;
        hasFertilizerWater = false;
        hasConsumedResources = false;
    }


    @Override
    public boolean initialize(BlockType blockType) {
        if (super.initialize(blockType) && blockType.getState() instanceof Data data) {
            this.data = data;
            // Initialize item container for the processing bench
            ic = new SimpleItemContainer((short)2); // 2 slots: fertilizer and water/fertilizer water
            
            // Set slot filters to only allow specific items in specific slots
            // Slot 0: Only allow fertilizer
            ic.setSlotFilter(FilterActionType.ADD, (short)0, (actionType, container, slot, itemStack) -> {
                return itemStack != null && itemStack.getItemId().equals("Tool_Fertilizer");
            });
            
            // Slot 1: Only allow water or fertilizer water
            ic.setSlotFilter(FilterActionType.ADD, (short)1, (actionType, container, slot, itemStack) -> {
                return itemStack != null && (
                    itemStack.getItemId().equals("Container_Bucket_State_Filled_Water") ||
                    itemStack.getItemId().equals("*Container_Bucket_State_Filled_Water") ||
                    itemStack.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
                    itemStack.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
                );
            });
            
            return true;
        }
        return false;
    }



    public static ComponentType<EntityStore, BlockEntity> getComponentType() {
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = EntityModule.get().getEntityStoreRegistry();
        return EntityModule.get().getBlockEntityComponentType();
    }

    @Override
    public ItemContainer getItemContainer() {
        return ic;
    }



    private void fixSlotAssignments(World w) {
        if (this.getItemContainer() == null) return;
        
        ItemStack slot0 = this.getItemContainer().getItemStack((short)0);
        ItemStack slot1 = this.getItemContainer().getItemStack((short)1);
        
        // Check if items are in wrong slots
        boolean slot0Wrong = slot0 != null && !slot0.getItemId().equals("Tool_Fertilizer");
        boolean slot1Wrong = slot1 != null && !(
            slot1.getItemId().equals("Container_Bucket_State_Filled_Water") ||
            slot1.getItemId().equals("*Container_Bucket_State_Filled_Water") ||
            slot1.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
            slot1.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
        );
        
        if (slot0Wrong || slot1Wrong) {
            
            // Drop items that are in wrong slots
            if (slot0Wrong && slot0 != null) {
                dropItem(w, slot0);
                this.getItemContainer().removeItemStackFromSlot((short)0, slot0.getQuantity());
            }
            if (slot1Wrong && slot1 != null) {
                dropItem(w, slot1);
                this.getItemContainer().removeItemStackFromSlot((short)1, slot1.getQuantity());
            }
        }
    }

    private void dropItem(World w, ItemStack itemStack) {
        if (w == null || itemStack == null) return;
        
        // Just log the item being dropped, don't actually drop it
        //HytaleLogger.getLogger().atWarning().log("FertilizerState: Dropped item: " + itemStack.getItemId());
    }

    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new, StateData.DEFAULT_CODEC)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}