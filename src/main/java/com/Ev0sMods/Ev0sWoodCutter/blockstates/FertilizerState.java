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
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
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
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import it.unimi.dsi.fastutil.objects.ObjectList;
import voidbond.arcio.ArcioPlugin;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.components.BlockUUIDComponent;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("removal")
public class FertilizerState extends ItemContainerState implements TickableBlockState, ItemContainerBlockState {

    // ── Fertilizer type registry ──────────────────────────────────────────────
    /**
     * Describes every fertilizer item accepted in slot 0.
     *
     * tickInterval – ticks between growth applications (at 30 TPS; 1800 = 60 s).
     * treeOnly     – when true only sapling/tree blocks are targeted.
     * standaloneWater – when true the item does NOT require liquid in slot 1.
     */
    public enum FertilizerType {
        NONE                (0,    false, false),
        STANDARD_WATER      (1800, false, false),  // any 'fertil*' item + liquid (fert water halves interval)
        // NoCube's Orchard — all require liquid in slot 1
        NOCUBE_TREE         (900,  true,  false),  // NoCube_Ingredient_Tree_Fertilizer (trees only)
        // NoCube's Cultivation — all require liquid in slot 1
        NOCUBE_LIME         (900,  false, false),  // NoCube_Tool_Fertilizer_Lime
        NOCUBE_BONE         (450,  false, false),  // NoCube_Tool_Fertilizer_Bone
        NOCUBE_SEASHELL     (225,  false, false),  // NoCube_Tool_Fertilizer_Seashell
        NOCUBE_ELITE        (113,  false, false);  // NoCube_Tool_Fertilizer_Elite

        public final int tickInterval;
        public final boolean treeOnly;
        /** True = no water in slot 1 required; consumes only from slot 0. */
        public final boolean standalone;

        FertilizerType(int tickInterval, boolean treeOnly, boolean standalone) {
            this.tickInterval = tickInterval;
            this.treeOnly = treeOnly;
            this.standalone = standalone;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    public World w;
    private int square;
    public ItemContainer ic;
    public static final BuilderCodec<FertilizerState> CODEC = BuilderCodec.builder(FertilizerState.class, FertilizerState::new, BlockState.BASE_CODEC)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();
    public Data data;
    public int timer = 0;
    public int processingTimer = 0;

    /** Tracks the last known ArcIO signal state so we only log on changes. */
    private boolean lastArcioActive = false;
    /** Whether we have already ensured our ArcIO components exist on this block entity. */
    private boolean arcioInitialized = false;
    /** Whether the On animation is currently active. */
    private boolean isAnimating = false;
    /** Countdown ticks holding the On state so the animation can play to completion. */
    private int animHoldTimer = 0;
    /** How many ticks to hold On before allowing a transition back to Off (~2 s at 30 TPS). */
    private static final int ANIM_HOLD_TICKS = 60;

    /** True when ArcIO is on the server at runtime. */
    private static final boolean ARCIO_PRESENT;
    /** True when HyUI is on the server at runtime. */
    static final boolean HYUI_PRESENT;
    static {
        boolean arcio = false;
        try { Class.forName("voidbond.arcio.components.ArcioMechanismComponent"); arcio = true; }
        catch (ClassNotFoundException ignored) {}
        ARCIO_PRESENT = arcio;

        boolean hyui = false;
        try { Class.forName("au.ellie.hyui.builders.PageBuilder"); hyui = true; }
        catch (ClassNotFoundException ignored) {}
        HYUI_PRESENT = hyui;
    }

    public int durationTimer = 0;
    public boolean isProcessing = false;
    public boolean hasFertilizer = false;
    public boolean hasWater = false;
    public boolean hasFertilizerWater = false;
    public boolean hasConsumedResources = false;
    public int inputTimer = 0;
    /** Active fertilizer type determined from slot 0. Package-private for UI access. */
    FertilizerType activeFertilizerType = FertilizerType.NONE;
    /** Effective tick interval — halved when fertilizer water is present in slot 1. */
    public int effectiveTickInterval = 0;
    /** Counter driving the UI auto-refresh every ~15 ticks. */
    private int uiTick = 0;

    @Override
    public void tick(
            float v,
            int i,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        World w = store.getExternalData().getWorld();
        if (w == null) return;

        // Countdown the animation hold timer every tick.
        if (animHoldTimer > 0) {
            animHoldTimer--;
            // Timer just expired — transition back to Off now.
            if (animHoldTimer == 0 && isAnimating) {
                applyBlockState(w, "Off");
                isAnimating = false;
            }
        }

        // If ArcIO is installed, register as a mechanism and check signal.
        if (ARCIO_PRESENT) {
            ensureArcioComponents(w);
            boolean active = isArcioActive(w);
            if (active != lastArcioActive) {
                lastArcioActive = active;
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] ArcIO signal %s",
                    getBlockX(), getBlockY(), getBlockZ(),
                    active ? "ON - fertilizer enabled" : "OFF - fertilizer paused");
            }
            if (!active) {
                setAnimState(w, false);
                return;
            }
        }

        // Check inventory every 20 ticks to reduce overhead.
        if (processingTimer % 20 == 0) {
            checkInputItems(w);
            fixSlotAssignments(w);
        }

        if (isProcessing) {
            processingTimer++;
            durationTimer++;

            int interval = effectiveTickInterval;
            if (interval <= 0) {
                // No valid fertilizer type — stop.
                stopProcessing();
                setAnimState(w, false);
            } else if (processingTimer >= interval) {
                int advanced = applyGrowthTick(w, activeFertilizerType.treeOnly);
                consumeResources();
                processingTimer = 0;
                setAnimState(w, advanced > 0);
                // One dose = one growth application.
                stopProcessing();
            }
        } else {
            timer++;
            if (timer >= 300) {
                timer = 0;
                if (activeFertilizerType != FertilizerType.NONE) {
                    int advanced = applyGrowthTick(w, activeFertilizerType.treeOnly);
                    consumeResources();
                    setAnimState(w, advanced > 0);
                }
            }
        }

        // Safety: if fertilizer was consumed mid-tick, stop.
        if (isProcessing && !hasFertilizer) {
            stopProcessing();
            setAnimState(w, false);
        }

        // UI auto-refresh every ~15 ticks (~500 ms at 30 TPS).
        if (HYUI_PRESENT) {
            uiTick++;
            if (uiTick >= 15) {
                uiTick = 0;
                FertilizerUIPage.tickRefresh(this, w.getEntityStore().getStore(), getBlockPosition());
            }
        }
    }

    @Override
    public void onOpen(Ref<EntityStore> playerEntityRef, World world, Store<EntityStore> store) {
        if (!HYUI_PRESENT) return;
        try {
            PlayerRef playerRef = store.getComponent(playerEntityRef, PlayerRef.getComponentType());
            if (playerRef == null) return;
            FertilizerUIPage.open(playerRef, playerEntityRef, store, getBlockPosition());
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] Failed to open UI: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
    }

    /** Returns true if the given item ID represents any fertilizer (our own or NoCube). */
    static boolean isFertilizer(String itemId) {
        return itemId != null && itemId.toLowerCase().contains("fertil");
    }

    /** Returns true if the given item ID is specifically fertilizer water (liquid slot only). */
    static boolean isFertilizerWater(String itemId) {
        if (itemId == null) return false;
        String id = itemId.startsWith("*") ? itemId.substring(1) : itemId;
        return id.equals("Container_Bucket_State_Filled_Fertilizer_Water");
    }

    private void checkInputItems(World w) {
        if (this.getItemContainer() == null) {
            hasFertilizer = false;
            hasWater = false;
            hasFertilizerWater = false;
            activeFertilizerType = FertilizerType.NONE;
            isProcessing = false;
            return;
        }

        ItemStack fertilizerSlot = this.getItemContainer().getItemStack((short) 0);
        ItemStack waterSlot      = this.getItemContainer().getItemStack((short) 1);

        hasFertilizer = fertilizerSlot != null && isFertilizer(fertilizerSlot.getItemId());
        hasWater = waterSlot != null && (
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Water")
        );
        hasFertilizerWater = waterSlot != null && (
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
        );

        // Resolve the specific fertilizer type from slot 0.
        // All types now require liquid in slot 1; fertilizer water halves the tick interval.
        boolean hasLiquid = hasWater || hasFertilizerWater;
        if (hasFertilizer) {
            String fid = fertilizerSlot.getItemId();
            if (fid.equals("NoCube_Ingredient_Tree_Fertilizer")) {
                activeFertilizerType = hasLiquid ? FertilizerType.NOCUBE_TREE : FertilizerType.NONE;
            } else if (fid.equals("NoCube_Tool_Fertilizer_Lime")) {
                activeFertilizerType = hasLiquid ? FertilizerType.NOCUBE_LIME : FertilizerType.NONE;
            } else if (fid.equals("NoCube_Tool_Fertilizer_Bone")) {
                activeFertilizerType = hasLiquid ? FertilizerType.NOCUBE_BONE : FertilizerType.NONE;
            } else if (fid.equals("NoCube_Tool_Fertilizer_Seashell")) {
                activeFertilizerType = hasLiquid ? FertilizerType.NOCUBE_SEASHELL : FertilizerType.NONE;
            } else if (fid.equals("NoCube_Tool_Fertilizer_Elite")) {
                activeFertilizerType = hasLiquid ? FertilizerType.NOCUBE_ELITE : FertilizerType.NONE;
            } else if (hasLiquid) {
                activeFertilizerType = FertilizerType.STANDARD_WATER;
            } else {
                // Standard fertilizer present but no liquid — cannot process.
                activeFertilizerType = FertilizerType.NONE;
            }
        } else {
            activeFertilizerType = FertilizerType.NONE;
        }

        // Fertilizer water halves the effective tick interval for faster growth.
        effectiveTickInterval = (activeFertilizerType.tickInterval > 0 && hasFertilizerWater)
                ? Math.max(1, activeFertilizerType.tickInterval / 2)
                : activeFertilizerType.tickInterval;

        boolean canProcess = activeFertilizerType != FertilizerType.NONE;
        if (canProcess) {
            if (!isProcessing) {
                isProcessing = true;
                hasConsumedResources = false;
            }
        } else {
            isProcessing = false;
        }
    }

    private int applyGrowthTick(World w, boolean treeOnly) {
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
        
        // Apply growth to blocks in the affected area.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Vector3i targetPos = new Vector3i(x, baseY, z);

                try {
                    WorldChunk chunk = w.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk != null) {
                        BlockType blockType = chunk.getBlockType(targetPos);
                        if (blockType != null && blockType.getFarming() != null) {
                            // Tree-only fertilizers skip non-sapling blocks.
                            if (treeOnly) {
                                String bid = blockType.getId();
                                boolean isSapling = bid.startsWith("Plant_Sapling_") || bid.contains("_Sapling");
                                if (!isSapling) continue;
                            }
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
                    HytaleLogger.getLogger().atWarning().log(
                        "[Fertilizer %d,%d,%d] growth tick failed at (%d,%d): %s",
                        getBlockX(), getBlockY(), getBlockZ(), x, z, e.getMessage());
                }
            }
        }
        
        
        return cropsAdvanced;
    }

    private void consumeResources() {
        if (this.getItemContainer() == null) return;

        // Consume one fertilizer from slot 0 (any item containing 'fertil').
        ItemStack fertilizerSlot = this.getItemContainer().getItemStack((short) 0);
        if (fertilizerSlot != null && isFertilizer(fertilizerSlot.getItemId())) {
            this.getItemContainer().removeItemStackFromSlot((short) 0, 1);
        }

        // NoCube fertilizers are standalone – they do not consume liquid from slot 1.
        if (activeFertilizerType.standalone) return;

        ItemStack waterSlot = this.getItemContainer().getItemStack((short) 1);
        if (waterSlot != null && (
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Water") ||
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
        )) {
            this.getItemContainer().removeItemStackFromSlot((short) 1, 1);
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
        activeFertilizerType = FertilizerType.NONE;
    }

    private void setAnimState(World w, boolean on) {
        if (on) {
            // Trigger On and (re)start the hold timer.
            animHoldTimer = ANIM_HOLD_TICKS;
            spawnFertilizerParticles(w);
            if (!isAnimating) {
                applyBlockState(w, "On");
                isAnimating = true;
            }
        } else {
            // Only switch to Off once the hold timer has expired.
            if (animHoldTimer > 0) return;
            if (isAnimating) {
                applyBlockState(w, "Off");
                isAnimating = false;
            }
        }
    }

    private void spawnFertilizerParticles(World w) {
        try {
            double cx = getBlockX() + 0.5;
            double cy = getBlockY() + 0.5;
            double cz = getBlockZ() + 0.5;
            ComponentAccessor<EntityStore> accessor = w.getEntityStore().getStore();
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx + 1, cy, cz), accessor);
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx - 1, cy, cz), accessor);
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx, cy, cz + 1), accessor);
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx, cy, cz - 1), accessor);
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] spawnFertilizerParticles failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
    }

    private void applyBlockState(World world, String stateName) {
        try {
            int bx = getBlockX(), by = getBlockY(), bz = getBlockZ();
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) return;
            BlockType current = chunk.getBlockType(new Vector3i(bx, by, bz));
            if (current == null) return;
            String stateKey = current.getBlockKeyForState(stateName);
            if (stateKey == null) return;
            var assetMap = BlockType.getAssetMap();
            int idx = assetMap.getIndex(stateKey);
            if (idx == Integer.MIN_VALUE) return;
            BlockType target = (BlockType) assetMap.getAsset(idx);
            int rot = chunk.getRotationIndex(bx, by, bz);
            chunk.setBlock(bx, by, bz, idx, target, rot,
                    SetBlockSettings.NONE,
                    SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] applyBlockState '%s' failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), stateName, e.getMessage());
        }
    }


    @Override
    public boolean initialize(BlockType blockType) {
        if (super.initialize(blockType) && blockType.getState() instanceof Data data) {
            this.data = data;
            // Initialize item container for the processing bench
            ic = new SimpleItemContainer((short)2); // 2 slots: fertilizer and water/fertilizer water
            
            // Set slot filters to only allow specific items in specific slots.
            // Slot 0: Any item whose ID contains 'fertil' (case-insensitive) —
            //         covers our own items, NoCube's Orchard, NoCube's Cultivation, etc.
            //         Fertilizer water is excluded here — it belongs in slot 1.
            ic.setSlotFilter(FilterActionType.ADD, (short) 0, (actionType, container, slot, itemStack) ->
                itemStack != null && isFertilizer(itemStack.getItemId()) && !isFertilizerWater(itemStack.getItemId()));
            
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
        
        // Check if items are in wrong slots.
        boolean slot0Wrong = slot0 != null && (!isFertilizer(slot0.getItemId()) || isFertilizerWater(slot0.getItemId()));
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

    /**
     * Ensures this block entity has the ArcIO mechanism and UUID components
     * so it can be wired into ArcIO networks via manathreads.
     */
    private void ensureArcioComponents(World world) {
        if (arcioInitialized) return;
        try {
            int bx = getBlockX(), by = getBlockY(), bz = getBlockZ();
            Store<ChunkStore> cs = world.getChunkStore().getStore();
            Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                    ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunkRef == null) return;
            BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                    chunkRef, BlockComponentChunk.getComponentType());
            if (bcc == null) return;
            Ref<ChunkStore> blockRef = bcc.getEntityReference(ChunkUtil.indexBlockInColumn(bx, by, bz));
            if (blockRef == null) return;

            BlockUUIDComponent uuid = (BlockUUIDComponent) cs.getComponent(
                    blockRef, BlockUUIDComponent.getComponentType());
            if (uuid == null) {
                uuid = BlockUUIDComponent.randomUUID();
                uuid.setPosition(new Vector3i(bx, by, bz));
                cs.putComponent(blockRef, BlockUUIDComponent.getComponentType(), uuid);
                ArcioPlugin.get().putUUID(uuid.getUuid(), blockRef);
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] Registered ArcIO UUID: %s",
                    bx, by, bz, uuid.getUuid());
            }

            ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                    blockRef, ArcioMechanismComponent.getComponentType());
            if (mech == null) {
                mech = new ArcioMechanismComponent("Fertilizer", 0, 1);
                cs.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] Added ArcIO mechanism component (type=Fertilizer)",
                    bx, by, bz);
            }

            arcioInitialized = true;
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] Failed to ensure ArcIO components: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
    }

    /**
     * Checks if this block's ArcIO mechanism is active.
     * First checks the block's own mechanism component (for manathread connections),
     * then falls back to checking adjacent mechanisms for backwards compatibility.
     */
    private boolean isArcioActive(World world) {
        try {
            int bx = getBlockX(), by = getBlockY(), bz = getBlockZ();
            Store<ChunkStore> cs = world.getChunkStore().getStore();
            Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                    ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunkRef != null) {
                BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                        chunkRef, BlockComponentChunk.getComponentType());
                if (bcc != null) {
                    Ref<ChunkStore> blockRef = bcc.getEntityReference(
                            ChunkUtil.indexBlockInColumn(bx, by, bz));
                    if (blockRef != null) {
                        ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                                blockRef, ArcioMechanismComponent.getComponentType());
                        if (mech != null) {
                            int signal = mech.getStrongestInputSignal(world);
                            int required = mech.getRequiredSignal();
                            if (signal > 0 && signal >= required) return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] ArcIO own-signal check failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
        return hasAdjacentActiveArcioMechanism(world);
    }

    /**
     * Returns true if any of the 6 directly adjacent blocks has an ArcIO
     * MechanismComponent whose signal meets or exceeds its required threshold (i.e. is "On").
     */
    private boolean hasAdjacentActiveArcioMechanism(World world) {
        try {
            Store<ChunkStore> cs = world.getChunkStore().getStore();
            int bx = getBlockX(), by = getBlockY(), bz = getBlockZ();
            int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for (int[] off : offsets) {
                int nx = bx + off[0], ny = by + off[1], nz = bz + off[2];
                Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                        ChunkUtil.indexChunkFromBlock(nx, nz));
                if (chunkRef == null) continue;
                BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                        chunkRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;
                Ref<ChunkStore> blockRef = bcc.getEntityReference(ChunkUtil.indexBlockInColumn(nx, ny, nz));
                if (blockRef == null) continue;
                ArcioMechanismComponent mc = (ArcioMechanismComponent) cs.getComponent(
                        blockRef, ArcioMechanismComponent.getComponentType());
                if (mc != null) {
                    int signal = mc.getStrongestInputSignal(world);
                    int required = mc.getRequiredSignal();
                    if (signal > 0 && signal >= required) return true;
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] ArcIO adjacent check failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
        return false;
    }

    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new, StateData.DEFAULT_CODEC)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}