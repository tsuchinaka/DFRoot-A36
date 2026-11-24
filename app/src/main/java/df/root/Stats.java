package df.root;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local counters for the optimization baseline. */
final class Stats {

    private static final AtomicLong SU_PROBES = new AtomicLong();
    private static final AtomicLong GETPROP = new AtomicLong();
    private static final AtomicLong CHAIN_PASSES = new AtomicLong();
    private static final AtomicLong EXPLOIT_RUNS = new AtomicLong();
    private static final AtomicLong UI_REFRESHES = new AtomicLong();

    private Stats() {
    }

    static void suProbe() {
        SU_PROBES.incrementAndGet();
    }

    static void getprop() {
        GETPROP.incrementAndGet();
    }

    static void chainPass() {
        CHAIN_PASSES.incrementAndGet();
    }

    static void exploitRun() {
        EXPLOIT_RUNS.incrementAndGet();
    }

    static void uiRefresh() {
        UI_REFRESHES.incrementAndGet();
    }

    static String snapshot() {
        return "su_probes=" + SU_PROBES.get()
                + " getprop=" + GETPROP.get()
                + " chain_passes=" + CHAIN_PASSES.get()
                + " exploit_runs=" + EXPLOIT_RUNS.get()
                + " ui_refreshes=" + UI_REFRESHES.get();
    }
}
