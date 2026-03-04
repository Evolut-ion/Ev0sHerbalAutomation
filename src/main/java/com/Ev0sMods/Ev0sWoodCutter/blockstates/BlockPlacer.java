package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.asset.common.BlockyAnimationCache;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import it.unimi.dsi.fastutil.objects.ObjectList;

import com.hypixel.hytale.component.spatial.SpatialResource;
import voidbond.arcio.ArcioPlugin;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.components.BlockUUIDComponent;

import javax.annotation.Nonnull;
import java.util.Objects;

@SuppressWarnings("removal")
public class BlockPlacer extends ItemContainerState implements TickableBlockState, ItemContainerBlockState {

    public World w;
    private int square;
    public Store<EntityStore> entities;
    public Ref<EntityStore> ref;
    public static final BuilderCodec<BlockPlacer> CODEC = BuilderCodec.builder(BlockPlacer.class, BlockPlacer::new, BlockState.BASE_CODEC)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();
    public Data data;
    public int timer = 0;

    /** Tracks the last known ArcIO signal state so we only log on changes. */
    private boolean lastArcioActive = false;
    /** Whether we have already ensured our ArcIO components exist on this block entity. */
    private boolean arcioInitialized = false;

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
        if (++timer < 150) return;
        timer = 0;

        World w = store.getExternalData().getWorld();
        if (w == null) return;

        // If ArcIO is installed, register as a mechanism and check signal.
        if (ARCIO_PRESENT) {
            ensureArcioComponents(w);
            boolean active = isArcioActive(w);
            if (active != lastArcioActive) {
                lastArcioActive = active;
                HytaleLogger.getLogger().atInfo().log(
                    "[BlockPlacer %d,%d,%d] ArcIO signal %s",
                    getBlockX(), getBlockY(), getBlockZ(),
                    active ? "ON - placer enabled" : "OFF - placer paused");
            }
            if (!active) return;
        }

        var stack = this.getItemContainer().getItemStack((short) 0);
        if (stack == null || stack.isEmpty()) return;

        int baseX = this.getBlockX();
        int baseY = this.getBlockY()+3;
        int baseZ = this.getBlockZ();

        int remaining = stack.getQuantity();

        for (int dx = -2; dx <= 2 && remaining > 0; dx++) {
            for (int dz = -2; dz <= 2 && remaining > 0; dz++) {

                int x = baseX + dx;
                int y = baseY;
                int z = baseZ + dz;

                // Check if there's a fertilizer 3 blocks above this position
                int fertilizerY = y + 3;
                var fertilizerBlock = w.getBlockType(x, fertilizerY, z);
                if (fertilizerBlock != null && fertilizerBlock.getId().contains("Fertilizer")) {
                    HytaleLogger.getLogger().atInfo().log("BlockPlacer: Skipping seed placement at (" + x + ", " + y + ", " + z + ") - fertilizer found at (" + x + ", " + fertilizerY + ", " + z + ")");
                    continue;
                }
                if (fertilizerBlock != null && fertilizerBlock.getId().contains("Test_Fert")) {
                    HytaleLogger.getLogger().atInfo().log("BlockPlacer: Skipping seed placement at (" + x + ", " + y + ", " + z + ") - fertilizer found at (" + x + ", " + fertilizerY + ", " + z + ")");
                    continue;
                }

                // Skip if there's already a sapling here
                var existing = w.getBlockType(x, y, z);
                if (existing != null && existing.getId().contains("Sapling")) {
                    continue;

                } if (existing != null && existing.getId().contains("Test")) {
                    continue;

                }if (existing != null && existing.getId().contains("Hopper")) {
                    continue;

                } if (existing != null && existing.getId().contains("Sucker")) {
                    continue;

                }
                if (existing != null && existing.getId().contains("Plant_Crop")) {
                    continue;

                }
                // Get this block's entity reference for animation using spatial resource
                Ref<EntityStore> blockEntityRef = null;
                Store<EntityStore> entityStore = w.getEntityStore().getStore();
                SpatialResource<Ref<EntityStore>, EntityStore> spatial = (SpatialResource<Ref<EntityStore>, EntityStore>) entityStore.getResource(EntityModule.get().getEntitySpatialResourceType());
                Vector3d blockPos = new Vector3d(getBlockX(), getBlockY(), getBlockZ());
                ObjectList<Ref<EntityStore>> foundEntities = ObjectList.of();
                spatial.getSpatialStructure().collectCylinder(blockPos, 0.5, 0.5, foundEntities);
                if (!foundEntities.isEmpty()) {
                    blockEntityRef = foundEntities.get(0);
                }
                this.ref = blockEntityRef;

                if(stack.getItemId().contains("Sapling")){
                    w.setBlock(
                            x,
                            y,
                            z,
                            stack.getBlockKey(),
                            3332
                    );
                    this.getItemContainer().removeItemStackFromSlot((short) 0, 1);
                    remaining--;
                    // Play animation when sapling is placed
                    if (blockEntityRef != null) {
                        AnimationUtils.playAnimation(blockEntityRef, AnimationSlot.Status, "place_anim.blockyanim", false, (ComponentAccessor<EntityStore>)entities);
                    }
                } else if (stack.getItemId().contains("Seeds")) {
                    w.setBlock(
                            x,
                            y,
                            z,
                            stack.getItemId().replace("_Seeds", "_Crop") + "_Block",
                            3332
                    );
                    this.getItemContainer().removeItemStackFromSlot((short) 0, 1);
                    remaining--;
                    // Play animation when seed is placed
                    if (blockEntityRef != null) {
                        AnimationUtils.playAnimation(blockEntityRef, AnimationSlot.Status, "place_anim.blockyanim", false, (ComponentAccessor<EntityStore>)entities);
                    }
                }
            }
        }
        this.ref = ref;
    }

    @Override
    public boolean initialize(BlockType blockType) {
        if (super.initialize(blockType) && blockType.getState() instanceof Data data) {
            this.data = data;
            //SpatialResource<Ref<EntityStore>, EntityStore> playerSpatialResource = (SpatialResource) .getResource(EntityModule.get().getPlayerSpatialResourceType());
            //if()
            setItemContainer(new SimpleItemContainer((short)1));
            return true;
        }

        return false;

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
                    "[BlockPlacer %d,%d,%d] Registered ArcIO UUID: %s",
                    bx, by, bz, uuid.getUuid());
            }

            ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                    blockRef, ArcioMechanismComponent.getComponentType());
            if (mech == null) {
                mech = new ArcioMechanismComponent("BlockPlacer", 0, 1);
                cs.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                HytaleLogger.getLogger().atInfo().log(
                    "[BlockPlacer %d,%d,%d] Added ArcIO mechanism component (type=BlockPlacer)",
                    bx, by, bz);
            }

            arcioInitialized = true;
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[BlockPlacer %d,%d,%d] Failed to ensure ArcIO components: %s",
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
                "[BlockPlacer %d,%d,%d] ArcIO own-signal check failed: %s",
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
                    if (signal >= required) return true;
                }
            }
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[BlockPlacer %d,%d,%d] ArcIO adjacent check failed: %s",
                getBlockX(), getBlockY(), getBlockZ(), e.getMessage());
        }
        return false;
    }

    public static ComponentType<EntityStore, BlockEntity> getComponentType() {
        ComponentRegistryProxy<EntityStore> entityStoreRegistry = EntityModule.get().getEntityStoreRegistry();
        return EntityModule.get().getBlockEntityComponentType();
    }
    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<BlockPlacer.Data> CODEC = BuilderCodec.builder(BlockPlacer.Data.class, BlockPlacer.Data::new, StateData.DEFAULT_CODEC)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}
