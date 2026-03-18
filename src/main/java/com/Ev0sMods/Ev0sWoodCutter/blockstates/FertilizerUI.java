package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.math.vector.Vector3i;

import java.lang.reflect.Method;
import com.hypixel.hytale.logger.HytaleLogger;

public final class FertilizerUI {
    private FertilizerUI() {}

    private static final boolean HYUI_PRESENT;
    private static Method mOpenForcedNoHint;
    private static Method mOpenForcedWithHint;
    private static Method mHasWatcher;
    private static Method mTickRefresh;
    private static Method mPeriodicRefresh;

    static {
        boolean hyui = false;
        try {
            Class.forName("au.ellie.hyui.builders.PageBuilder");
            hyui = true;
        } catch (ClassNotFoundException ignored) {}
        HYUI_PRESENT = hyui;

        if (HYUI_PRESENT) {
            try {
                Class<?> pageClass = Class.forName("com.Ev0sMods.Ev0sWoodCutter.blockstates.FertilizerUIPage");
                mOpenForcedNoHint = pageClass.getMethod("openForced", PlayerRef.class, Ref.class, Store.class, Vector3i.class);
                mOpenForcedWithHint = pageClass.getMethod("openForced", PlayerRef.class, Ref.class, Store.class, Vector3i.class, FertilizerState.class);
                mHasWatcher = pageClass.getMethod("hasWatcher", Vector3i.class);
                mTickRefresh = pageClass.getMethod("tickRefresh", FertilizerState.class, Store.class, Vector3i.class, boolean.class);
                mPeriodicRefresh = pageClass.getMethod("periodicRefresh", FertilizerState.class, Store.class, Vector3i.class);
            } catch (Exception e) {
                HytaleLogger.getLogger().atWarning().log("[FertilizerUI] Reflection init failed: " + e.getMessage());
                mOpenForcedNoHint = null;
                mOpenForcedWithHint = null;
                mHasWatcher = null;
                mTickRefresh = null;
                mPeriodicRefresh = null;
            }
        } else {
            HytaleLogger.getLogger().atInfo().log("[FertilizerUI] HyUI not found at classload time; UI calls will be no-ops.");
        }
    }

    public static boolean isHyuiAvailable() {
        return HYUI_PRESENT && mOpenForcedNoHint != null;
    }

    public static void openForced(PlayerRef playerRef, Ref<?> entityRef, Store<?> store, Vector3i pos) {
        if (!isHyuiAvailable()) {
            HytaleLogger.getLogger().atWarning().log("[FertilizerUI] openForced called but HyUI not available or reflection failed");
            return;
        }
        try { mOpenForcedNoHint.invoke(null, playerRef, entityRef, store, pos); } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log("[FertilizerUI] openForced failed: " + t.getMessage());
        }
    }

    public static void openForced(PlayerRef playerRef, Ref<?> entityRef, Store<?> store, Vector3i pos, FertilizerState fsHint) {
        if (!isHyuiAvailable()) {
            HytaleLogger.getLogger().atWarning().log("[FertilizerUI] openForced(withHint) called but HyUI not available or reflection failed");
            return;
        }
        try { mOpenForcedWithHint.invoke(null, playerRef, entityRef, store, pos, fsHint); } catch (Throwable t) {
            HytaleLogger.getLogger().atWarning().log("[FertilizerUI] openForced(withHint) failed: " + t.getMessage());
        }
    }

    public static boolean hasWatcher(Vector3i pos) {
        if (!isHyuiAvailable()) return false;
        try { return Boolean.TRUE.equals(mHasWatcher.invoke(null, pos)); } catch (Throwable t) { HytaleLogger.getLogger().atWarning().log("[FertilizerUI] hasWatcher failed: " + t.getMessage()); return false; }
    }

    public static void tickRefresh(FertilizerState fs, Store<?> store, Vector3i pos, boolean forceFullRender) {
        if (!isHyuiAvailable()) return;
        try { mTickRefresh.invoke(null, fs, store, pos, forceFullRender); } catch (Throwable t) { HytaleLogger.getLogger().atWarning().log("[FertilizerUI] tickRefresh failed: " + t.getMessage()); }
    }

    public static void periodicRefresh(FertilizerState fs, Store<?> store, Vector3i pos) {
        if (!isHyuiAvailable()) return;
        try { mPeriodicRefresh.invoke(null, fs, store, pos); } catch (Throwable t) { HytaleLogger.getLogger().atWarning().log("[FertilizerUI] periodicRefresh failed: " + t.getMessage()); }
    }
}
