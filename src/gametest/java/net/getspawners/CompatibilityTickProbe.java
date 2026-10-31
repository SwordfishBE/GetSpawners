package net.getspawners;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CompatibilityTickProbe {
    public static final String TAG = "getspawners.compat";
    public static final Set<String> THREADS = ConcurrentHashMap.newKeySet();
    public static final AtomicInteger WORKER_TICKS = new AtomicInteger();
    public static volatile Thread serverThread;

    private CompatibilityTickProbe() {
    }

    public static void record() {
        Thread current = Thread.currentThread();
        THREADS.add(current.getName());
        if (current != serverThread) {
            WORKER_TICKS.incrementAndGet();
        }
    }
}
