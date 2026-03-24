package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingData;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.state.TickableBlockState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import java.util.Map;
import voidbond.arcio.ArcioPlugin;
import voidbond.arcio.components.ArcioMechanismComponent;
import voidbond.arcio.components.BlockUUIDComponent;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Objects;

public class FertilizerState implements Component<ChunkStore>, TickableBlockState {

    // ── Fertilizer type registry ──────────────────────────────────────────────
    public enum FertilizerType {
        NONE                (0,    false, false),
        STANDARD_WATER      (1800, false, false),
        TOOL_COMPOST        (900,  false, false),
        TOOL_SUPER_COMPOST  (450,  false, false),
        TOOL_ULTRA_COMPOST  (450,  false, false),
        NOCUBE_TREE         (900,  true,  false),
        NOCUBE_LIME         (900,  false, false),
        NOCUBE_BONE         (450,  false, false),
        NOCUBE_SEASHELL     (225,  false, false),
        NOCUBE_ELITE        (113,  false, false);

        public final int tickInterval;
        public final boolean treeOnly;
        public final boolean standalone;

        FertilizerType(int tickInterval, boolean treeOnly, boolean standalone) {
            this.tickInterval = tickInterval;
            this.treeOnly = treeOnly;
            this.standalone = standalone;
        }
    }

    /** Set during plugin registration. */
    public static ComponentType<ChunkStore, FertilizerState> COMPONENT_TYPE;

    public World w;
    private int square;
    private SimpleItemContainer itemContainer;

    public static final BuilderCodec<FertilizerState> CODEC = BuilderCodec.builder(FertilizerState.class, FertilizerState::new)
            .append(new KeyedCodec<>("Size", Codec.INTEGER, true), (i, v) -> i.square = v, i -> i.square).add().build();

    public int timer = 0;
    public int processingTimer = 0;

    // --- Component position resolution ---
    private Vector3i cachedPosition = new Vector3i(0, 0, 0);
    private boolean positionResolved = false;
    private int cachedRotation = 0;

    /** Tracks the last known ArcIO signal state so we only log on changes. */
    private boolean lastArcioActive = false;
    /** Whether we have already ensured our ArcIO components exist on this block entity. */
    private boolean arcioInitialized = false;
    /** Cached ArcIO mechanism component — set once in ensureArcioComponents to avoid chunk-store lookups every tick. */
    private ArcioMechanismComponent cachedArcioMech = null;
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
    /** Snapshot of slot 0 item ID from the last checkInputItems call — used to detect real inventory changes. */
    private String lastSlot0Id = null;
    /** Snapshot of slot 0 quantity from the last checkInputItems call. */
    private int lastSlot0Qty = 0;
    /** Snapshot of slot 1 item ID from the last checkInputItems call. */
    private String lastSlot1Id = null;
    /** Snapshot of slot 1 quantity from the last checkInputItems call. */
    private int lastSlot1Qty = 0;
    /** Counter driving the UI auto-refresh every ~30 ticks while processing. */
    private int uiTick = 0;
    /** Dedicated periodic UI timer to force updates every 3 seconds (90 ticks). */
    private int periodicUiTimer = 0;
    /** Dedicated counter for throttling checkInputItems — always increments regardless of processing state. */
    private int inputCheckTimer = 0;
    /**
     * Set to true whenever displayed state changes (inputs, processing start/stop, resources consumed).
     * Causes an immediate UI push on the next tick rather than waiting for the timed interval.
     * Cleared after each push so idle machines never call renderPage.
     */
    boolean uiDirty = false;
    /** Cached block position — avoids repeated Vector3i construction in the UI path. */
    private Vector3i cachedBlockPos = null;
    /** Last progress % sent to the UI — used to only push when crossing a 10% boundary. */
    private int lastUiProgress = -1;

    public FertilizerState() {
        this.itemContainer = new SimpleItemContainer((short) 2);
        // Slot 0: Any item whose ID contains 'fertil' (case-insensitive),
        //         excluding fertilizer water which belongs in slot 1.
        itemContainer.setSlotFilter(FilterActionType.ADD, (short) 0, (actionType, container, slot, itemStack) ->
            itemStack != null && isFertilizer(itemStack.getItemId()) && !isFertilizerWater(itemStack.getItemId()));
        // Slot 1: Only allow water or fertilizer water
        itemContainer.setSlotFilter(FilterActionType.ADD, (short) 1, (actionType, container, slot, itemStack) -> {
            return itemStack != null && (
                itemStack.getItemId().equals("Container_Bucket_State_Filled_Water") ||
                itemStack.getItemId().equals("*Container_Bucket_State_Filled_Water") ||
                itemStack.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
                itemStack.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
            );
        });
    }

    public FertilizerState(FertilizerState other) {
        this(); // set up item container with filters
        this.square = other.square;
    }

    @Override
    public FertilizerState clone() {
        return new FertilizerState(this);
    }

    @Override
    public WorldChunk getChunk() {
        return null;
    }

    @Override
    public Vector3i getPosition() {
        return cachedPosition;
    }

    @Override
    public void invalidate() {
        // no-op
    }

    public ItemContainer getItemContainer() {
        return itemContainer;
    }

    /**
     * Try engine-provided position accessors via reflection before falling
     * back to the manual chunk-scan approach.
     */
    private void probeAndGetBlockPosition() {
        try {
            Class<?> sc = this.getClass().getSuperclass();
            if (sc != null) {
                for (String name : new String[]{"getBlockPosition", "getPosition", "getPos", "position"}) {
                    try {
                        java.lang.reflect.Method m = sc.getMethod(name);
                        Object r = m.invoke(this);
                        if (r instanceof Vector3i v3 && !(v3.x == 0 && v3.y == 0 && v3.z == 0)) {
                            cachedPosition = v3;
                            cachedBlockPos = v3;
                            positionResolved = true;
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Cached reflection handle for BlockComponentChunk.getEntityReferences(). */
    private static volatile java.lang.reflect.Method entityRefsMethod;
    private static volatile boolean entityRefsMethodResolved = false;

    @SuppressWarnings("unchecked")
    private void resolvePosition(Store<ChunkStore> store, Ref<ChunkStore> myRef) {
        try {
            int myIdx = myRef.getIndex();
            ChunkStore cs = store.getExternalData();
            var chunks = cs.getChunkIndexes();
            if (chunks == null || chunks.isEmpty()) return;

            for (long chunkIdx : chunks) {
                Ref<ChunkStore> colRef = cs.getChunkReference(chunkIdx);
                if (colRef == null) continue;
                BlockComponentChunk bcc = store.getComponent(colRef, BlockComponentChunk.getComponentType());
                if (bcc == null) continue;

                // Use reflection to invoke getEntityReferences() — the compile-time
                // return type (Int2ObjectMap) does not match the runtime descriptor.
                Map<?, ?> entityRefs = getEntityRefsViaReflection(bcc);
                if (entityRefs == null) continue;

                for (Map.Entry<?, ?> entry : entityRefs.entrySet()) {
                    Object key = entry.getKey();
                    Object val = entry.getValue();
                    if (!(key instanceof Integer blockIndex)) continue;
                    if (!(val instanceof Ref<?> blockRef)) continue;
                    if (blockRef.getIndex() == myIdx) {
                        int lx = ChunkUtil.xFromBlockInColumn(blockIndex);
                        int wy = ChunkUtil.yFromBlockInColumn(blockIndex);
                        int lz = ChunkUtil.zFromBlockInColumn(blockIndex);
                        int wx = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.xOfChunkIndex(chunkIdx), lx);
                        int wz = ChunkUtil.worldCoordFromLocalCoord(ChunkUtil.zOfChunkIndex(chunkIdx), lz);
                        cachedPosition = new Vector3i(wx, wy, wz);
                        cachedBlockPos = new Vector3i(wx, wy, wz);
                        positionResolved = true;
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> getEntityRefsViaReflection(BlockComponentChunk bcc) {
        try {
            if (!entityRefsMethodResolved) {
                synchronized (FertilizerState.class) {
                    if (!entityRefsMethodResolved) {
                        for (java.lang.reflect.Method m : bcc.getClass().getMethods()) {
                            if ("getEntityReferences".equals(m.getName()) && m.getParameterCount() == 0) {
                                m.setAccessible(true);
                                entityRefsMethod = m;
                                break;
                            }
                        }
                        entityRefsMethodResolved = true;
                    }
                }
            }
            if (entityRefsMethod == null) return null;
            Object result = entityRefsMethod.invoke(bcc);
            if (result instanceof Map<?, ?> map) return map;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void tick(
            float v,
            int index,
            ArchetypeChunk<ChunkStore> archetypeChunk,
            Store<ChunkStore> store,
            CommandBuffer<ChunkStore> commandBuffer
    ) {
        World w = store.getExternalData().getWorld();
        if (w == null) return;

        // Resolve position on first tick
        if (!positionResolved) {
            probeAndGetBlockPosition();
            if (!positionResolved) {
                resolvePosition(store, archetypeChunk.getReferenceTo(index));
            }
            if (!positionResolved) return;
        }

        // Resolve rotation from chunk
        WorldChunk posChunk = w.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(cachedPosition.x, cachedPosition.z));
        if (posChunk != null) {
            cachedRotation = posChunk.getRotationIndex(cachedPosition.x, cachedPosition.y, cachedPosition.z);
        }

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
        boolean arcioCurrentlyActive = true;
        if (ARCIO_PRESENT) {
            ensureArcioComponents(w, commandBuffer);
            boolean active = isArcioActive(w);
            arcioCurrentlyActive = active;
            if (active != lastArcioActive) {
                lastArcioActive = active;
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] ArcIO signal %s",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    active ? "ON - fertilizer enabled" : "OFF - fertilizer paused");
            }
            if (!active) {
                setAnimState(w, false);
            }
        }

        // Check inventory every 20 ticks to reduce overhead.
        inputCheckTimer++;
        if (inputCheckTimer >= 20) {
            inputCheckTimer = 0;
            checkInputItems(w);
            fixSlotAssignments(w);
        }

        boolean arcioPausing = (ARCIO_PRESENT && !arcioCurrentlyActive);
        if (arcioPausing) {
            if (isProcessing) {
                stopProcessing();
            }
        }

        if (isProcessing) {
            processingTimer++;
            durationTimer++;

            int interval = effectiveTickInterval;
            if (interval <= 0) {
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] interval<=0, stopping processing (type=%s)",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z, activeFertilizerType);
                stopProcessing();
                setAnimState(w, false);
            } else if (processingTimer >= interval) {
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] processing tick fired (type=%s treeOnly=%b rot=%d)",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    activeFertilizerType, activeFertilizerType.treeOnly, cachedRotation);
                int advanced = applyGrowthTick(w, activeFertilizerType.treeOnly);
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] applyGrowthTick returned %d",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z, advanced);
                consumeResources();
                processingTimer = 0;
                setAnimState(w, advanced > 0);
                stopProcessing();
            }
        } else {
            timer++;
            if (timer >= 300) {
                timer = 0;
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] 300-tick timer fired (type=%s arcioPausing=%b)",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z,
                    activeFertilizerType, arcioPausing);
                if (activeFertilizerType != FertilizerType.NONE) {
                    int advanced = applyGrowthTick(w, activeFertilizerType.treeOnly);
                    HytaleLogger.getLogger().atInfo().log(
                        "[Fertilizer %d,%d,%d] 300-tick applyGrowthTick returned %d",
                        cachedPosition.x, cachedPosition.y, cachedPosition.z, advanced);
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

        // UI auto-refresh
        if (HYUI_PRESENT && FertilizerUIPage.hasWatcher(cachedBlockPos)) {
            if (uiDirty) {
                uiDirty = false;
                uiTick = 0;
                lastUiProgress = -1;
                FertilizerUIPage.tickRefresh(this, w.getEntityStore().getStore(), cachedBlockPos, true);
            } else if (isProcessing && effectiveTickInterval > 0) {
                int pct = (int)(100.0 * processingTimer / effectiveTickInterval);
                int bucket = pct / 20;
                int lastBucket = lastUiProgress / 20;
                if (lastUiProgress < 0 || bucket != lastBucket) {
                    lastUiProgress = pct;
                    FertilizerUIPage.tickRefresh(this, w.getEntityStore().getStore(), cachedBlockPos, false);
                }
            } else {
                uiTick = 0;
                lastUiProgress = -1;
            }
            periodicUiTimer++;
            if (periodicUiTimer >= 90) {
                periodicUiTimer = 0;
                FertilizerUIPage.periodicRefresh(this, w.getEntityStore().getStore(), cachedBlockPos);
            }
        }
    }

    /** Returns true if the given item ID represents any fertilizer (our own or NoCube). */
    static boolean isFertilizer(String itemId) {
        if (itemId == null) return false;
        String id = itemId.toLowerCase();
        return id.contains("fertil")
            || itemId.equals("Tool_Compost")
            || itemId.equals("Tool_Super_Compost")
            || itemId.equals("Tool_Ultra_Compost");
    }

    /** Returns true if the given item ID is specifically fertilizer water (liquid slot only). */
    static boolean isFertilizerWater(String itemId) {
        if (itemId == null) return false;
        String id = itemId.startsWith("*") ? itemId.substring(1) : itemId;
        return id.equals("Container_Bucket_State_Filled_Fertilizer_Water");
    }

    private void checkInputItems(World w) {
        if (this.itemContainer == null) {
            hasFertilizer = false;
            hasWater = false;
            hasFertilizerWater = false;
            activeFertilizerType = FertilizerType.NONE;
            isProcessing = false;
            return;
        }

        ItemStack fertilizerSlot = this.itemContainer.getItemStack((short) 0);
        ItemStack waterSlot      = this.itemContainer.getItemStack((short) 1);

        String cur0Id  = fertilizerSlot != null ? fertilizerSlot.getItemId() : null;
        int    cur0Qty = fertilizerSlot != null ? fertilizerSlot.getQuantity() : 0;
        String cur1Id  = waterSlot != null ? waterSlot.getItemId() : null;
        int    cur1Qty = waterSlot != null ? waterSlot.getQuantity() : 0;
        boolean slotsChanged = !Objects.equals(cur0Id, lastSlot0Id) || cur0Qty != lastSlot0Qty
                            || !Objects.equals(cur1Id, lastSlot1Id) || cur1Qty != lastSlot1Qty;
        lastSlot0Id = cur0Id; lastSlot0Qty = cur0Qty;
        lastSlot1Id = cur1Id; lastSlot1Qty = cur1Qty;

        hasFertilizer = fertilizerSlot != null && isFertilizer(fertilizerSlot.getItemId());
        hasWater = waterSlot != null && (
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Water")
        );
        hasFertilizerWater = waterSlot != null && (
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
        );

        boolean hasLiquid = hasWater || hasFertilizerWater;
        if (hasFertilizer) {
            String fid = fertilizerSlot.getItemId();
            if (fid.equals("Tool_Compost")) {
                activeFertilizerType = hasLiquid ? FertilizerType.TOOL_COMPOST : FertilizerType.NONE;
            } else if (fid.equals("Tool_Super_Compost")) {
                activeFertilizerType = hasLiquid ? FertilizerType.TOOL_SUPER_COMPOST : FertilizerType.NONE;
            } else if (fid.equals("Tool_Ultra_Compost")) {
                activeFertilizerType = hasLiquid ? FertilizerType.TOOL_ULTRA_COMPOST : FertilizerType.NONE;
            } else if (fid.equals("NoCube_Ingredient_Tree_Fertilizer")) {
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
                activeFertilizerType = FertilizerType.NONE;
            }
        } else {
            activeFertilizerType = FertilizerType.NONE;
        }

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
        if (slotsChanged) uiDirty = true;
    }

    private int applyGrowthTick(World w, boolean treeOnly) {
        int baseX = cachedPosition.x;
        int baseY = cachedPosition.y;
        int baseZ = cachedPosition.z;
        int rotation = cachedRotation;

        Store<ChunkStore> chunkStore = w.getChunkStore().getStore();

        HashMap<Long, WorldChunk> chunkCache = new HashMap<>();
        HashMap<Long, Ref<ChunkStore>> chunkRefCache = new HashMap<>();
        HashMap<Long, BlockComponentChunk> bccCache = new HashMap<>();
        HashMap<Long, Ref<ChunkStore>> sectionRefCache = new HashMap<>();

        HytaleLogger.getLogger().atInfo().log(
            "[Fertilizer %d,%d,%d] applyGrowthTick scanning: base=(%d,%d,%d) rot=%d treeOnly=%b",
            cachedPosition.x, cachedPosition.y, cachedPosition.z,
            baseX, baseY, baseZ, rotation, treeOnly);

        int cropsAdvanced = 0;
        for (int lateral = -2; lateral <= 2; lateral++) {
            for (int forward = 1; forward <= 5; forward++) {
                int x = baseX;
                int z = baseZ;
                switch (rotation) {
                    case 0 -> {
                        x = baseX + lateral;
                        z = baseZ + forward;
                    }
                    case 1 -> {
                        x = baseX + lateral;
                        z = baseZ - forward;
                    }
                    case 2 -> {
                        x = baseX + forward;
                        z = baseZ + lateral;
                    }
                    case 3 -> {
                        x = baseX - forward;
                        z = baseZ + lateral;
                    }
                }

                cropsAdvanced += tryApplyGrowthAt(w, x, baseY,     z, treeOnly, chunkStore, chunkCache, chunkRefCache, bccCache, sectionRefCache);
                cropsAdvanced += tryApplyGrowthAt(w, x, baseY + 1, z, treeOnly, chunkStore, chunkCache, chunkRefCache, bccCache, sectionRefCache);
                cropsAdvanced += tryApplyGrowthAt(w, x, baseY - 1, z, treeOnly, chunkStore, chunkCache, chunkRefCache, bccCache, sectionRefCache);
            }
        }

        HytaleLogger.getLogger().atInfo().log(
            "[Fertilizer %d,%d,%d] applyGrowthTick done: %d crops advanced",
            cachedPosition.x, cachedPosition.y, cachedPosition.z, cropsAdvanced);
        return cropsAdvanced;
    }

    private int tryApplyGrowthAt(World w, int x, int y, int z, boolean treeOnly,
            Store<ChunkStore> chunkStore,
            HashMap<Long, WorldChunk> chunkCache,
            HashMap<Long, Ref<ChunkStore>> chunkRefCache,
            HashMap<Long, BlockComponentChunk> bccCache,
            HashMap<Long, Ref<ChunkStore>> sectionRefCache) {
        try {
            long chunkIdx = ChunkUtil.indexChunkFromBlock(x, z);
            WorldChunk chunk;
            if (!chunkCache.containsKey(chunkIdx)) {
                chunk = w.getChunkIfInMemory(chunkIdx);
                chunkCache.put(chunkIdx, chunk);
            } else {
                chunk = chunkCache.get(chunkIdx);
            }
            if (chunk == null) return 0;

            Vector3i targetPos = new Vector3i(x, y, z);
            BlockType blockType = chunk.getBlockType(targetPos);
            if (blockType == null) return 0;

            String bid = blockType.getId();
            if (bid.equals("Empty") || bid.equals("Air")) return 0;
            boolean isSapling = bid.startsWith("Plant_Sapling_") || bid.contains("_Sapling");
            boolean isCrop = bid.contains("_Crop") || bid.contains("Plant_Crop");

            if (!isSapling && !isCrop) return 0;

            HytaleLogger.getLogger().atInfo().log(
                "[Fertilizer] tryApplyGrowthAt (%d,%d,%d) block=%s sapling=%b crop=%b treeOnly=%b",
                x, y, z, bid, isSapling, isCrop, treeOnly);

            if (treeOnly && !isSapling) return 0;

            // Saplings without farming data: re-arm natural ticking.
            if (blockType.getFarming() == null) {
                if (isSapling) {
                    rearmSaplingTick(chunk, x, y, z);
                    return 1;
                }
                return 0;
            }

            Ref<ChunkStore> chunkRef;
            if (!chunkRefCache.containsKey(chunkIdx)) {
                chunkRef = w.getChunkStore().getChunkReference(chunkIdx);
                chunkRefCache.put(chunkIdx, chunkRef);
            } else {
                chunkRef = chunkRefCache.get(chunkIdx);
            }
            if (chunkRef == null) {
                if (isSapling) {
                    rearmSaplingTick(chunk, x, y, z);
                    return 1;
                }
                return 0;
            }

            BlockComponentChunk blockComponentChunk;
            if (!bccCache.containsKey(chunkIdx)) {
                blockComponentChunk = (BlockComponentChunk) chunkStore.getComponent(
                        chunkRef, BlockComponentChunk.getComponentType());
                bccCache.put(chunkIdx, blockComponentChunk);
            } else {
                blockComponentChunk = bccCache.get(chunkIdx);
            }
            if (blockComponentChunk == null) {
                if (isSapling) {
                    rearmSaplingTick(chunk, x, y, z);
                    return 1;
                }
                return 0;
            }

            int blockIndexColumn = ChunkUtil.indexBlockInColumn(x, y, z);
            Ref<ChunkStore> blockRef = blockComponentChunk.getEntityReference(blockIndexColumn);
            if (blockRef == null) {
                if (isSapling) {
                    rearmSaplingTick(chunk, x, y, z);
                    return 1;
                }
                return 0;
            }

            FarmingBlock farmingBlock = (FarmingBlock) chunkStore.getComponent(blockRef, FarmingBlock.getComponentType());
            if (farmingBlock == null) {
                if (isSapling) {
                    rearmSaplingTick(chunk, x, y, z);
                    return 1;
                }
                return 0;
            }

            float currentProgress = farmingBlock.getGrowthProgress();
            int currentStage = (int) currentProgress;

            FarmingData farmingConfig = blockType.getFarming();
            if (farmingConfig.getStages() == null) return 0;

            String currentStageSet = farmingBlock.getCurrentStageSet();
            if (currentStageSet == null) {
                currentStageSet = farmingConfig.getStartingStageSet();
            }

            FarmingStageData[] stages = (FarmingStageData[]) farmingConfig.getStages().get(currentStageSet);
            if (stages == null || stages.length == 0) return 0;

            int targetStage = isSapling
                    ? (stages.length - 1)
                    : Math.min(currentStage + 1, stages.length - 1);
            if (targetStage <= currentStage) {
                if (isSapling) {
                    rearmSaplingTick(chunk, x, y, z);
                }
                return 0;
            }

            int sx = ChunkUtil.chunkCoordinate(x);
            int sy = ChunkUtil.chunkCoordinate(y);
            int sz = ChunkUtil.chunkCoordinate(z);
            long sectionKey = ((long)(sx & 0xFFFFFF) << 40) | ((long)(sy & 0xFFFF) << 24) | (sz & 0xFFFFFF);
            Ref<ChunkStore> sectionRef;
            if (!sectionRefCache.containsKey(sectionKey)) {
                sectionRef = w.getChunkStore().getChunkSectionReference(sx, sy, sz);
                sectionRefCache.put(sectionKey, sectionRef);
            } else {
                sectionRef = sectionRefCache.get(sectionKey);
            }
            if (sectionRef == null) return 0;

            float lastStableProgress = farmingBlock.getGrowthProgress();
            int lastStableGeneration = farmingBlock.getGeneration();
            boolean advancedAny = false;

            for (int stageToApply = currentStage + 1; stageToApply <= targetStage; stageToApply++) {
                FarmingStageData previousStage = null;
                int previousStageIndex = stageToApply - 1;
                if (previousStageIndex >= 0 && previousStageIndex < stages.length) {
                    previousStage = stages[previousStageIndex];
                }

                try {
                    farmingBlock.setGrowthProgress((float) stageToApply);
                    farmingBlock.setGeneration(lastStableGeneration + 1);
                    stages[stageToApply].apply(chunkStore, sectionRef, blockRef, x, y, z, previousStage);
                    lastStableProgress = farmingBlock.getGrowthProgress();
                    lastStableGeneration = farmingBlock.getGeneration();
                    advancedAny = true;
                } catch (Exception applyEx) {
                    farmingBlock.setGrowthProgress(lastStableProgress);
                    farmingBlock.setGeneration(lastStableGeneration);
                    throw applyEx;
                }
            }

            if (isSapling) {
                rearmSaplingTick(chunk, x, y, z);
            }
            return advancedAny ? 1 : 0;
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] growth tick failed at (%d,%d,%d): %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, x, y, z, e.getMessage());
            return 0;
        }
    }

    private void rearmSaplingTick(WorldChunk chunk, int x, int y, int z) {
        try {
            chunk.setTicking(x, y, z, true);
        } catch (Exception e) {
            HytaleLogger.getLogger().atFine().log(
                "[Fertilizer %d,%d,%d] setTicking failed at (%d,%d,%d): %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, x, y, z, e.getMessage());
        }
    }

    private void consumeResources() {
        if (this.itemContainer == null) return;

        ItemStack fertilizerSlot = this.itemContainer.getItemStack((short) 0);
        if (fertilizerSlot != null && isFertilizer(fertilizerSlot.getItemId())) {
            this.itemContainer.removeItemStackFromSlot((short) 0, 1);
        }

        if (activeFertilizerType.standalone) return;

        ItemStack waterSlot = this.itemContainer.getItemStack((short) 1);
        if (waterSlot != null && (
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Water") ||
            waterSlot.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
            waterSlot.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
        )) {
            this.itemContainer.removeItemStackFromSlot((short) 1, 1);
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
            animHoldTimer = ANIM_HOLD_TICKS;
            spawnFertilizerParticles(w);
            if (!isAnimating) {
                applyBlockState(w, "On");
                isAnimating = true;
            }
        } else {
            if (animHoldTimer > 0) return;
            if (isAnimating) {
                applyBlockState(w, "Off");
                isAnimating = false;
            }
        }
    }

    private void spawnFertilizerParticles(World w) {
        try {
            double cx = cachedPosition.x + 0.5;
            double cy = cachedPosition.y + 0.5;
            double cz = cachedPosition.z + 0.5;
            ComponentAccessor<EntityStore> accessor = w.getEntityStore().getStore();
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx + 1, cy, cz), accessor);
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx - 1, cy, cz), accessor);
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx, cy, cz + 1), accessor);
            ParticleUtil.spawnParticleEffect("Water_Can_Splash", new Vector3d(cx, cy, cz - 1), accessor);
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] spawnFertilizerParticles failed: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
    }

    private void applyBlockState(World world, String stateName) {
        try {
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
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
                cachedPosition.x, cachedPosition.y, cachedPosition.z, stateName, e.getMessage());
        }
    }

    private void fixSlotAssignments(World w) {
        if (this.itemContainer == null) return;

        ItemStack slot0 = this.itemContainer.getItemStack((short)0);
        ItemStack slot1 = this.itemContainer.getItemStack((short)1);

        boolean slot0Wrong = slot0 != null && (!isFertilizer(slot0.getItemId()) || isFertilizerWater(slot0.getItemId()));
        boolean slot1Wrong = slot1 != null && !(
            slot1.getItemId().equals("Container_Bucket_State_Filled_Water") ||
            slot1.getItemId().equals("*Container_Bucket_State_Filled_Water") ||
            slot1.getItemId().equals("Container_Bucket_State_Filled_Fertilizer_Water") ||
            slot1.getItemId().equals("*Container_Bucket_State_Filled_Fertilizer_Water")
        );

        if (slot0Wrong || slot1Wrong) {
            if (slot0Wrong && slot0 != null) {
                dropItem(w, slot0);
                this.itemContainer.removeItemStackFromSlot((short)0, slot0.getQuantity());
            }
            if (slot1Wrong && slot1 != null) {
                dropItem(w, slot1);
                this.itemContainer.removeItemStackFromSlot((short)1, slot1.getQuantity());
            }
        }
    }

    private void dropItem(World w, ItemStack itemStack) {
        if (w == null || itemStack == null) return;
    }

    private void ensureArcioComponents(World world, CommandBuffer<ChunkStore> commandBuffer) {
        if (arcioInitialized) return;
        try {
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
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
                if (commandBuffer != null) {
                    commandBuffer.putComponent(blockRef, BlockUUIDComponent.getComponentType(), uuid);
                } else {
                    cs.putComponent(blockRef, BlockUUIDComponent.getComponentType(), uuid);
                }
                ArcioPlugin.get().putUUID(uuid.getUuid(), blockRef);
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] Registered ArcIO UUID: %s",
                    bx, by, bz, uuid.getUuid());
            }

            ArcioMechanismComponent mech = (ArcioMechanismComponent) cs.getComponent(
                    blockRef, ArcioMechanismComponent.getComponentType());
            if (mech == null) {
                mech = new ArcioMechanismComponent("Fertilizer", 0, 1);
                if (commandBuffer != null) {
                    commandBuffer.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                } else {
                    cs.putComponent(blockRef, ArcioMechanismComponent.getComponentType(), mech);
                }
                HytaleLogger.getLogger().atInfo().log(
                    "[Fertilizer %d,%d,%d] Added ArcIO mechanism component (type=Fertilizer)",
                    bx, by, bz);
            }

            cachedArcioMech = mech;
            arcioInitialized = true;
        } catch (Exception e) {
            HytaleLogger.getLogger().atWarning().log(
                "[Fertilizer %d,%d,%d] Failed to ensure ArcIO components: %s",
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
    }

    private boolean isArcioActive(World world) {
        if (cachedArcioMech != null) {
            try {
                int signal = cachedArcioMech.getStrongestInputSignal(world);
                return signal > 0 && signal >= cachedArcioMech.getRequiredSignal();
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log(
                    "[Fertilizer %d,%d,%d] ArcIO cached signal check failed: %s",
                    cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
                cachedArcioMech = null;
            }
        }
        try {
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
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
                            cachedArcioMech = mech;
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
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
        return hasAdjacentActiveArcioMechanism(world);
    }

    private boolean hasAdjacentActiveArcioMechanism(World world) {
        try {
            Store<ChunkStore> cs = world.getChunkStore().getStore();
            int bx = cachedPosition.x, by = cachedPosition.y, bz = cachedPosition.z;
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
                cachedPosition.x, cachedPosition.y, cachedPosition.z, e.getMessage());
        }
        return false;
    }

    public static class Data extends StateData {
        @Nonnull
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                .append(new KeyedCodec<>("Size", Codec.INTEGER), (o, v) -> o.square = v, o ->o.square).add().build();

        private int square;
    }
}
