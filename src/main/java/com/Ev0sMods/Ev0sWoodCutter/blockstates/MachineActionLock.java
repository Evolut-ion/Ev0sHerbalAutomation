package com.Ev0sMods.Ev0sWoodCutter.blockstates;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight cross-machine position lock to prevent placer/cutter conflicts.
 */
final class MachineActionLock {
    private MachineActionLock() {}

    private record LockEntry(String owner, long expiresAtMs) {}

    private static final ConcurrentHashMap<String, LockEntry> LOCKS = new ConcurrentHashMap<>();

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    static boolean reserve(String owner, int x, int y, int z, long ttlMs) {
        long now = System.currentTimeMillis();
        String key = key(x, y, z);
        LockEntry updated = LOCKS.compute(key, (k, current) -> {
            if (current == null || current.expiresAtMs < now || current.owner.equals(owner)) {
                return new LockEntry(owner, now + Math.max(1L, ttlMs));
            }
            return current;
        });
        return updated != null && owner.equals(updated.owner);
    }

    static boolean reservedByOther(String owner, int x, int y, int z) {
        long now = System.currentTimeMillis();
        String key = key(x, y, z);
        LockEntry current = LOCKS.get(key);
        if (current == null) return false;
        if (current.expiresAtMs < now) {
            LOCKS.remove(key, current);
            return false;
        }
        return !owner.equals(current.owner);
    }
}
