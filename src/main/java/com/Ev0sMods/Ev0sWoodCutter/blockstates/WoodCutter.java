package com.Ev0sMods.Ev0sWoodCutter.blockstates;




import com.Ev0sMods.Ev0sWoodCutter.interactions.CutterFarmingStageInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.HarvesterCropInteraction;
import com.Ev0sMods.Ev0sWoodCutter.interactions.WoodcutterChangeStateInteraction;
import com.hypixel.hytale.builtin.adventure.farming.FarmingSystems;
import com.hypixel.hytale.builtin.adventure.farming.interactions.HarvestCropInteraction;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.asset.common.BlockyAnimationCache;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ResourceType;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
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
import it.unimi.dsi.fastutil.objects.ObjectList;
import voidbond.arcio.ArcioPlugin;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.components.BlockUUIDComponent;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("removal")
public class WoodCutter extends ItemContainerState implements TickableBlockState, ItemContainerBlockState {
    public World w;
    private int square;
    public Store<EntityStore> entities;
    public static final BuilderCodec<WoodCutter> CODEC = BuilderCodec.builder(WoodCutter.class, WoodCutter::new, BlockState.BASE_CODEC)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();
    public Data data;
    public int timer = 0;

    /** Whether we have already ensured our ArcIO components exist on this block entity. */
    private boolean arcioInitialized = false;
    /** Whether the looping animation is currently playing. */
    private boolean isAnimating = false;

    /** True when ArcIO is on the server at runtime. */
    private static final boolean ARCIO_PRESENT;
    static {
        boolean found = false;
        try { Class.forName("voidbond.arcio.components.ArcioMechanismComponent"); found = true; }
        catch (ClassNotFoundException ignored) {}
        ARCIO_PRESENT = found;
    }

    @Override
    public void tick(
            float v,
            int i,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        if(timer >= 150) {
            timer = 0;
            World w = store.getExternalData().getWorld();
            if (w == null) return;

            // EntityStore needed for item collection.
            Store<EntityStore> entityStore = w.getEntityStore().getStore();
            this.entities = entityStore;

            // If ArcIO is installed, register as a mechanism and gate on signal.
            if (ARCIO_PRESENT) {
                ensureArcioComponents(w);
                boolean active = isArcioActive(w);
                HytaleLogger.getLogger().atInfo().log(
                    "[WoodCutter %d,%d,%d] ArcIO active=%b",
                    getBlockX(), getBlockY(), getBlockZ(), active);
                if (!active) {
                    // Signal off — ensure we are in Off state and stop.
                    if (isAnimating) {
                        applyBlockState(w, WoodcutterChangeStateInteraction.STATE_OFF);
                        isAnimating = false;
                    }
                    return;
                }
            }

            HytaleLogger.getLogger().atInfo().log(
                "[WoodCutter %d,%d,%d] Tick - scanning area (rot=%d)",
                getBlockX(), getBlockY(), getBlockZ(), getRotationIndex());

            // Tracks whether any harvesting occurs this tick — drives On/Off state for non-ArcIO.
            boolean didWork = false;

    /* ---------------------------------
       PHASE 0: Collect nearby dropped items
       --------------------------------- */
            for (Ref<EntityStore> itemRef : getAllItemsInBox(
                    this,
                    this.getBlockPosition(),
                    entityStore,
                    true, true, true
            )) {
                ItemComponent ic =
                        itemRef.getStore().getComponent(itemRef, ItemComponent.getComponentType());
                if (ic == null) continue;

                ItemStack stack = ic.getItemStack();
                if (stack == null) continue;

                if (this.getItemContainer().canAddItemStack(stack)) {
                    this.getItemContainer().addItemStack(stack);
                    entities.removeEntity(itemRef, RemoveReason.UNLOAD);
                }
            }

            int baseX = this.getBlockX();
            int baseY = this.getBlockY();
            int baseZ = this.getBlockZ();

            // List to track all sapling positions for growth
            List<Vector3i> saplingsToGrow = new ArrayList<>();

            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = 0; dz <= 5; dz++) {

                    int x = baseX + dx;
                    int y = baseY;
                    int z = baseZ + dz;
                    if(this.getRotationIndex()== 0){
                        z = baseZ + dz;
                    }
                    if(this.getRotationIndex()== 1){
                        z = baseZ - dz;
                    }
                    if(this.getRotationIndex()== 2){
                         x = baseX - dz;
                         z = baseZ + dx;
                    }
                    if(this.getRotationIndex()== 3){
                        x = baseX + dz;
                        z = baseZ + dx;
                    }
                   // chunk.markNeedsSaving();

                    BlockType block = w.getBlockType(x, y, z);
                    if (block == null) continue;

                    // If already a sapling, just queue it for growth
                    if (block.getId().startsWith("Plant_Sapling_")) {
                        saplingsToGrow.add(new Vector3i(x, y, z));
                        continue;
                    }
                    if (block.getId().contains("Seed")) {
                        continue;
                    }

                    // --- Crop handling: only harvest fully grown, skip immature ---
                    if (block.getId().contains("Plant_Crop_")) {
                        boolean isFullyGrown = false;
                        FarmingData farmingConfig = block.getFarming();

                        if (farmingConfig != null && farmingConfig.getStages() != null) {
                            Store<ChunkStore> chunkStore = w.getChunkStore().getStore();
                            Ref<ChunkStore> chunkRef = w.getChunkStore().getChunkReference(
                                    ChunkUtil.indexChunkFromBlock(x, z));
                            if (chunkRef != null) {
                                BlockComponentChunk blockComponentChunk =
                                        (BlockComponentChunk) chunkStore.getComponent(
                                                chunkRef, BlockComponentChunk.getComponentType());
                                if (blockComponentChunk != null) {
                                    int blockIndexColumn = ChunkUtil.indexBlockInColumn(x, y, z);
                                    Ref<ChunkStore> blockRef =
                                            blockComponentChunk.getEntityReference(blockIndexColumn);

                                    if (blockRef != null) {
                                        FarmingBlock farmingBlock =
                                                (FarmingBlock) chunkStore.getComponent(
                                                        blockRef, FarmingBlock.getComponentType());
                                        if (farmingBlock != null) {
                                            // Plant still has a FarmingBlock → check its stage
                                            float currentProgress = farmingBlock.getGrowthProgress();
                                            int currentStage = (int) currentProgress;

                                            String currentStageSet = farmingBlock.getCurrentStageSet();
                                            if (currentStageSet == null) {
                                                currentStageSet = farmingConfig.getStartingStageSet();
                                            }

                                            FarmingStageData[] stages =
                                                    (FarmingStageData[]) farmingConfig.getStages()
                                                            .get(currentStageSet);
                                            if (stages != null && stages.length > 0) {
                                                if (currentStage >= stages.length - 1) {
                                                    isFullyGrown = true;
                                                } else {
                                                    // Not mature – leave it alone
                                                    HytaleLogger.getLogger().atFine().log(
                                                            "[WoodCutter %d,%d,%d] Crop %s at (%d,%d,%d) still growing: stage %d/%d",
                                                            getBlockX(), getBlockY(), getBlockZ(),
                                                            block.getId(), x, y, z,
                                                            currentStage, stages.length - 1);
                                                }
                                            }
                                        } else {
                                            // FarmingBlock component was removed → growth completed
                                            isFullyGrown = true;
                                        }
                                    } else {
                                        // No block entity ref → FarmingBlock removed after growth finished
                                        isFullyGrown = true;
                                    }
                                }
                            }
                        } else {
                            // No farming stages defined → treat as harvestable
                            isFullyGrown = true;
                        }

                        if (isFullyGrown) {
                            WorldChunk cropChunk = w.getChunkIfInMemory(
                                    ChunkUtil.indexChunkFromBlock(x, z));
                            if (cropChunk != null) {
                                HytaleLogger.getLogger().atInfo().log(
                                        "[WoodCutter %d,%d,%d] Harvesting fully grown crop %s at (%d,%d,%d)",
                                        getBlockX(), getBlockY(), getBlockZ(),
                                        block.getId(), x, y, z);
                                cropChunk.setBlock(x, y, z, "Empty", 3332);
                                didWork = true;
                                if (block.getGathering() != null
                                        && block.getGathering().getHarvest() != null) {
                                    this.itemContainer.addItemStacks(
                                            BlockHarvestUtils.getDrops(block, 3, null,
                                                    block.getGathering().getHarvest().getDropListId()));
                                }
                            }
                        }
                        continue; // Crop handled – skip hardwood checks, move to next block
                    }

                    Item item = block.getItem();
                    if (item == null || item.getResourceTypes() == null) continue;

                    boolean isWood = false;
                    for (var rt : item.getResourceTypes()) {
                        if (rt.id != null && rt.id.startsWith("Wood_")) {
                            isWood = true;
                            break;
                        }
                    }
                    if (!isWood) continue;

                    WorldChunk chunk = w.getChunkIfInMemory(
                            ChunkUtil.indexChunkFromBlock(x, z)
                    );
                    if (chunk == null) continue;

                    HytaleLogger.getLogger().atInfo().log(
                        "[WoodCutter %d,%d,%d] Cutting tree %s at (%d,%d,%d)",
                        getBlockX(), getBlockY(), getBlockZ(),
                        block.getId(), x, y, z);

                    // Normalize the species name
                    String sapling = item.getBlockId()
                            .replaceFirst("^Wood_", "")
                            .replaceAll("_(Trunk|Full|Large|Mature|Stump)$", "");
                    sapling = sapling.replace("_Trunk", "");
                    //AnimationUtils.playAnimation(new Ref<EntityStore>(w.getEntityStore().getStore()), AnimationSlot.Status,"active.blockyanim", true, (ComponentAccessor<EntityStore>)w.getEntityStore().getStore() );
                    // Clear tree
                    chunk.breakBlock(x, y, z, 3332);
                    chunk.breakBlock(x, y + 1, z, 3332);
                    chunk.breakBlock(x, y - 1, z, 3332);
                    chunk.breakBlock(x, y - 2, z, 3332);
                    didWork = true;

                    // Prepare soil
                    chunk.setBlock(x, y - 1, z, "Soil_Grass");
                    chunk.setBlock(x, y - 2, z, "Soil_Grass");

                    // Plant sapling
                    //chunk.placeBlock(x, y, z, "Plant_Sapling_" + sapling, RotationTuple.NONE, 3332, true);

                    chunk.setTicking(x, y, z, true);

                    saplingsToGrow.add(new Vector3i(x, y, z));


                    
                    chunk.markNeedsSaving();
                }
            }

            // Always drive On/Off based on whether we actually harvested something.
            if (didWork != isAnimating) {
                applyBlockState(w, didWork
                        ? WoodcutterChangeStateInteraction.STATE_ON
                        : WoodcutterChangeStateInteraction.STATE_OFF);
                isAnimating = didWork;
            }
        } else{
            timer++;
        }
    }




    @Override
    public boolean initialize(BlockType blockType) {
        if (!super.initialize(blockType)) return false;
        // blockType.getState() may be null when block types load before our codec registers — don't gate on instanceof.
        if (blockType.getState() instanceof Data d) this.data = d;
        setItemContainer(new SimpleItemContainer((short) 15));
        if(ARCIO_PRESENT) {
            HytaleLogger.getLogger().atInfo().log(
                "[WoodCutter %d,%d,%d] ArcIO detected - woodcutter will respond to adjacent ArcIO signals",
                getBlockX(), getBlockY(), getBlockZ());
                
        } else {
            HytaleLogger.getLogger().atInfo().log(
                "[WoodCutter %d,%d,%d] ArcIO not detected - woodcutter will run continuously without external control",
                getBlockX(), getBlockY(), getBlockZ());
        }
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    /**
     * Changes the WoodCutter's State.Definition on the server so the client sees
     * the correct animation.  Uses NO_UPDATE_STATE so the existing BlockState
     * component (including the item inventory) is NOT reinitialized.
     * This is exactly how ChangeStateInteraction changes named states.
     */
    private void applyBlockState(World world, String stateName) {
        try {
            int bx = getBlockX(), by = getBlockY(), bz = getBlockZ();
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(bx, bz));
            if (chunk == null) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: chunk null", bx, by, bz);
                return;
            }
            BlockType current = chunk.getBlockType(new Vector3i(bx, by, bz));
            if (current == null) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: BlockType null", bx, by, bz);
                return;
            }
            // Mirror ChangeStateInteraction: getBlockKeyForState -> asset map index -> setBlock
            String stateKey = current.getBlockKeyForState(stateName);
            if (stateKey == null) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: no key for state '%s' (current=%s)",
                    bx, by, bz, stateName, current.getId());
                return;
            }
            var assetMap = BlockType.getAssetMap();
            int blockTypeIndex = assetMap.getIndex(stateKey);
            if (blockTypeIndex == Integer.MIN_VALUE) {
                HytaleLogger.getLogger().atWarning().log(
                    "[WoodCutter %d,%d,%d] applyBlockState: asset index not found for key '%s'",
                    bx, by, bz, stateKey);
                return;
            }
            BlockType target = (BlockType) assetMap.getAsset(blockTypeIndex);
            int rot = chunk.getRotationIndex(bx, by, bz);
            HytaleLogger.getLogger().atInfo().log(
                "[WoodCutter %d,%d,%d] setBlock to state '%s' key='%s' idx=%d rot=%d",
                bx, by, bz, stateName, stateKey, blockTypeIndex, rot);
            // Same flags as ArcIO ChangeStateInteraction with UpdateBlockState=false:
            // arg7=0 (NONE), arg8=262 (NO_UPDATE_STATE | NO_SEND_PARTICLES | 256)
            chunk.setBlock(bx, by, bz, blockTypeIndex, target, rot,
                SetBlockSettings.NONE,
                SetBlockSettings.NO_UPDATE_STATE | SetBlockSettings.NO_SEND_PARTICLES | 256);
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[WoodCutter %d,%d,%d] applyBlockState failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
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

            // Add BlockUUIDComponent if missing
            BlockUUIDComponent uuid = (BlockUUIDComponent) cs.getComponent(
                    blockRef, BlockUUIDComponent.getComponentType());
            if (uuid == null) {
                uuid = BlockUUIDComponent.randomUUID();
                uuid.setPosition(new Vector3i(bx, by, bz));
                cs.putComponent(blockRef, BlockUUIDComponent.getComponentType(), uuid);
                ArcioPlugin.get().putUUID(uuid.getUuid(), blockRef);
                HytaleLogger.getLogger().atInfo().log(
                    "[WoodCutter %d,%d,%d] Registered ArcIO UUID: %s",
                    bx, by, bz, uuid.getUuid());
            }

            // Add ArcioMechanismComponent if missing
            ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                    blockRef, ArcioMechanismComponent.getComponentType());
            if (mech == null) {
                mech = new ArcioMechanismComponent("Woodcutter", 0, 1);
                cs.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                HytaleLogger.getLogger().atInfo().log(
                    "[WoodCutter %d,%d,%d] Added ArcIO mechanism component (type=Woodcutter)",
                    bx, by, bz);
            }

            arcioInitialized = true;
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[WoodCutter %d,%d,%d] Failed to ensure ArcIO components: %s",
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
                            if (signal >= required) return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[WoodCutter %d,%d,%d] ArcIO own-signal check failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
        // Fallback: check adjacent ArcIO mechanisms
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
                    if (signal >= required) return true;
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[WoodCutter %d,%d,%d] ArcIO adjacent check failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
        return false;
    }

    public List<Ref<EntityStore>> getAllItemsInBox(WoodCutter hp, Vector3i pos, @Nonnull ComponentAccessor<EntityStore> components, boolean players, boolean entities, boolean items) {
        final ObjectList<Ref<EntityStore>> results = SpatialResource.getThreadLocalReferenceList();
        final ObjectList<Ref<Store>> results2 = SpatialResource.getThreadLocalReferenceList();
        final Vector3d min = new Vector3d(pos.x-.5, pos.y-.5 , pos.z-.5);
        final Vector3d max = new Vector3d(pos.x+.5, pos.y+.5, pos.z+.5);
        if (entities) {
            //components.getResource(EntityModule.get().getEntitySpatialResourceType()).getSpatialStructure()
        }
        if (players) {
            //components.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure().collectCylinder(new Vector3d(pos.x,pos.y,pos.z), 4, 8, results );
        }
        if (items) {
            components.getResource(EntityModule.get().getItemSpatialResourceType()).getSpatialStructure().collectCylinder(new Vector3d(pos.x,pos.y,pos.z+2), 20,20,results );
        }
        this.entities = (Store<EntityStore>) components;
        return results;
    }

    public static ComponentType<EntityStore, BlockEntity> getComponentType() {
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = EntityModule.get().getEntityStoreRegistry();
        return EntityModule.get().getBlockEntityComponentType();
    }
    /**
     * Gets a reference to this block's entity for use with entity store operations.
     */
    private Ref<ChunkStore> getBlockEntityReference(World world) {
        Store<ChunkStore> cs = world.getChunkStore().getStore();
        Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(
                ChunkUtil.indexChunkFromBlock(getBlockX(), getBlockZ()));
        if (chunkRef == null) return null;
        BlockComponentChunk bcc = (BlockComponentChunk) cs.getComponent(
                chunkRef, BlockComponentChunk.getComponentType());
        if (bcc == null) return null;
        return bcc.getEntityReference(ChunkUtil.indexBlockInColumn(getBlockX(), getBlockY(), getBlockZ()));
    }

    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new, StateData.DEFAULT_CODEC)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}
