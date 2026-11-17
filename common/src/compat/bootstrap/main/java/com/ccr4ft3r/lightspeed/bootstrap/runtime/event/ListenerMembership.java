package com.ccr4ft3r.lightspeed.bootstrap.runtime.event;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/** Reverse sharded weak listener-to-owner index so unregisterAll touches only owning ListenerListInst instances. */
public final class ListenerMembership {
    private static final int SHARD_COUNT = 64;
    private static final int SHARD_MASK = SHARD_COUNT - 1;
    private static final Shard[] LISTENER_SHARDS = new Shard[SHARD_COUNT];
    private static final Shard[] OWNER_SHARDS = new Shard[SHARD_COUNT];
    private static final ThreadLocal<Object> ACTIVE_DISPATCH_OWNER = new ThreadLocal<>();

    static {
        for (int i = 0; i < SHARD_COUNT; i++) {
            LISTENER_SHARDS[i] = new Shard();
            OWNER_SHARDS[i] = new Shard();
        }
    }

    private ListenerMembership() { }

    public static void record(Object owner, Object listener) {
        if (owner == null || listener == null) {
            return;
        }
        OwnerRef ref = canonicalOwner(owner);
        Shard shard = listenerShard(listener);
        synchronized (shard) {
            Object existing = shard.entries.get(listener);
            if (existing == null) {
                shard.entries.put(listener, ref);
                return;
            }
            if (existing instanceof OwnerRef single) {
                if (single == ref || single.get() == owner) {
                    return;
                }
                shard.entries.put(listener, new OwnerRef[] { single, ref });
                return;
            }
            OwnerRef[] array = (OwnerRef[]) existing;
            int live = 0;
            for (OwnerRef candidate : array) {
                if (candidate == ref || (!candidate.disposed && candidate.get() == owner)) {
                    return;
                }
                if (!candidate.disposed && candidate.get() != null) {
                    live++;
                }
            }
            OwnerRef[] updated = new OwnerRef[live + 1];
            int write = 0;
            for (OwnerRef candidate : array) {
                if (!candidate.disposed && candidate.get() != null) {
                    updated[write++] = candidate;
                }
            }
            updated[write] = ref;
            shard.entries.put(listener, updated);
        }
    }

    public static void record(Object owner, Collection<? extends Collection<?>> priorities, Object listener) {
        record(owner, listener);
    }

    public static void reconcileAfterUnregister(Object owner, List<? extends Collection<?>> priorities, Object listener) {
        if (owner == null || listener == null) {
            return;
        }
        if (priorities != null) {
            for (int i = 0, size = priorities.size(); i < size; i++) {
                if (priorities.get(i).contains(listener)) {
                    return;
                }
            }
        }
        removeOwner(owner, listener);
    }

    public static boolean shouldUnregister(Object owner, Object listener, int availablePermits) {
        if (availablePermits == 0 || owner == null || listener == null) {
            return true;
        }
        if (ACTIVE_DISPATCH_OWNER.get() == owner) {
            return true;
        }
        Shard shard = listenerShard(listener);
        synchronized (shard) {
            Object entry = shard.entries.get(listener);
            if (entry == null) {
                return false;
            }
            if (entry instanceof OwnerRef single) {
                return !single.disposed && single.get() == owner;
            }
            for (OwnerRef candidate : (OwnerRef[]) entry) {
                if (!candidate.disposed && candidate.get() == owner) {
                    return true;
                }
            }
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean unregisterKnown(int busId, Object listener) {
        if (listener == null) {
            return true;
        }
        Object snapshot;
        Shard shard = listenerShard(listener);
        synchronized (shard) {
            snapshot = shard.entries.get(listener);
        }
        if (snapshot == null) {
            return true;
        }
        int expectedPlusOne = busId + 1;
        if (snapshot instanceof OwnerRef single) {
            return dispatchSingle(single, expectedPlusOne, listener);
        }
        OwnerRef[] array = (OwnerRef[]) snapshot;
        for (OwnerRef candidate : array) {
            if (!dispatchSingle(candidate, expectedPlusOne, listener)) {
                return false;
            }
        }
        return true;
    }

    public static void dispose(Object owner) {
        if (owner == null) {
            return;
        }
        Shard shard = ownerShard(owner);
        synchronized (shard) {
            Object removed = shard.entries.remove(owner);
            if (removed instanceof OwnerRef ref) {
                ref.disposed = true;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean dispatchSingle(OwnerRef ref, int expectedPlusOne, Object listener) {
        if (ref.disposed) {
            removeOwnerRef(ref, listener);
            return true;
        }
        Object owner = ref.get();
        if (owner == null) {
            removeOwnerRef(ref, listener);
            return true;
        }
        if (!(owner instanceof Consumer<?> consumer) || !(owner instanceof ToIntFunction<?> busIdSource)) {
            return false;
        }
        int ownerBusPlusOne = ((ToIntFunction<Object>) busIdSource).applyAsInt(null);
        if (ownerBusPlusOne != 0 && ownerBusPlusOne != expectedPlusOne) {
            return true;
        }
        Object previous = ACTIVE_DISPATCH_OWNER.get();
        ACTIVE_DISPATCH_OWNER.set(owner);
        try {
            ((Consumer<Object>) consumer).accept(listener);
        } finally {
            if (previous == null) {
                ACTIVE_DISPATCH_OWNER.remove();
            } else {
                ACTIVE_DISPATCH_OWNER.set(previous);
            }
        }
        return true;
    }

    private static void removeOwner(Object owner, Object listener) {
        Shard shard = listenerShard(listener);
        synchronized (shard) {
            Object entry = shard.entries.get(listener);
            if (entry == null) {
                return;
            }
            if (entry instanceof OwnerRef single) {
                if (single.disposed || single.get() == owner || single.get() == null) {
                    shard.entries.remove(listener);
                }
                return;
            }
            compactArray(shard, listener, (OwnerRef[]) entry, owner, null);
        }
    }

    private static void removeOwnerRef(OwnerRef target, Object listener) {
        Shard shard = listenerShard(listener);
        synchronized (shard) {
            Object entry = shard.entries.get(listener);
            if (entry == null) {
                return;
            }
            if (entry instanceof OwnerRef single) {
                if (single == target) {
                    shard.entries.remove(listener);
                }
                return;
            }
            compactArray(shard, listener, (OwnerRef[]) entry, null, target);
        }
    }

    private static void compactArray(Shard shard, Object listener, OwnerRef[] array, Object matchOwner, OwnerRef matchRef) {
        int remaining = 0;
        OwnerRef lastAlive = null;
        for (OwnerRef candidate : array) {
            if (candidate == matchRef || candidate.disposed) {
                continue;
            }
            Object owner = candidate.get();
            if (owner == null || owner == matchOwner) {
                continue;
            }
            remaining++;
            lastAlive = candidate;
        }
        if (remaining == 0) {
            shard.entries.remove(listener);
        } else if (remaining == 1) {
            shard.entries.put(listener, lastAlive);
        } else if (remaining < array.length) {
            OwnerRef[] shrunk = new OwnerRef[remaining];
            int write = 0;
            for (OwnerRef candidate : array) {
                if (candidate == matchRef || candidate.disposed) {
                    continue;
                }
                Object owner = candidate.get();
                if (owner == null || owner == matchOwner) {
                    continue;
                }
                shrunk[write++] = candidate;
            }
            shard.entries.put(listener, shrunk);
        }
    }

    private static OwnerRef canonicalOwner(Object owner) {
        Shard shard = ownerShard(owner);
        synchronized (shard) {
            OwnerRef ref = (OwnerRef) shard.entries.get(owner);
            if (ref == null || ref.disposed) {
                ref = new OwnerRef(owner);
                shard.entries.put(owner, ref);
            }
            return ref;
        }
    }

    private static Shard listenerShard(Object key) {
        int h = key.hashCode();
        return LISTENER_SHARDS[(h ^ (h >>> 16)) & SHARD_MASK];
    }

    private static Shard ownerShard(Object owner) {
        int h = System.identityHashCode(owner);
        return OWNER_SHARDS[(h ^ (h >>> 16)) & SHARD_MASK];
    }

    private static final class Shard {
        final WeakHashMap<Object, Object> entries = new WeakHashMap<>(16);
    }

    private static final class OwnerRef extends WeakReference<Object> {
        volatile boolean disposed;

        OwnerRef(Object owner) {
            super(owner);
        }
    }
}
