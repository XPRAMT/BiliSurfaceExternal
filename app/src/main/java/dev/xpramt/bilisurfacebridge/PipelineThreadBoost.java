package dev.xpramt.bilisurfacebridge;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * Raises the priority of Bilibili's external-render GL thread in the main process.
 *
 * With {@code TypeSurfaceViewWithExternalRender} every video frame is redrawn by the
 * {@code ijk_ext_render} thread on the app's Choreographer VSync.  That thread runs at nice 0
 * and was measured waiting 2-6 ms for a CPU behind the app's own dispatcher threads, which is
 * most of a 120 Hz slot.  The thread is created by Bilibili's renderer, so it is found by name
 * through /proc/self/task after a render layer attaches; the scan is retried for a few seconds
 * because the thread may not exist yet at attach time.
 */
final class PipelineThreadBoost {
    private static final String TAG = "BiliSurfaceBridge";
    static final String RENDER_THREAD_NAME = "ijk_ext_render";
    // Between THREAD_PRIORITY_DISPLAY (-4) and THREAD_PRIORITY_AUDIO (-16): ahead of every
    // nice-0 app thread without competing with the audio path.
    static final int PIPELINE_NICE = -10;
    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final int RETRY_COUNT = 15;

    private static final Set<Integer> BOOSTED_TIDS = new HashSet<>();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static int pendingRetries;

    private PipelineThreadBoost() {
    }

    /** Starts (or restarts) the named-thread scan; safe to call on every render attach. */
    static synchronized void scheduleRenderThreadBoost() {
        pendingRetries = RETRY_COUNT;
        HANDLER.removeCallbacks(SCAN);
        HANDLER.post(SCAN);
    }

    private static final Runnable SCAN = new Runnable() {
        @Override
        public void run() {
            boolean found = boostNamedThreads(RENDER_THREAD_NAME);
            synchronized (PipelineThreadBoost.class) {
                if (!found && --pendingRetries > 0) {
                    HANDLER.postDelayed(this, RETRY_INTERVAL_MS);
                }
            }
        }
    };

    /** Returns true when at least one live thread with that name exists (boosted or already). */
    static boolean boostNamedThreads(String name) {
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) {
            return false;
        }
        boolean found = false;
        for (File task : tasks) {
            int tid;
            try {
                tid = Integer.parseInt(task.getName());
            } catch (NumberFormatException ignored) {
                continue;
            }
            String comm;
            try {
                comm = new String(Files.readAllBytes(new File(task, "comm").toPath()),
                        StandardCharsets.UTF_8).trim();
            } catch (Throwable ignored) {
                continue;
            }
            if (!name.equals(comm)) {
                continue;
            }
            found = true;
            synchronized (BOOSTED_TIDS) {
                if (!BOOSTED_TIDS.add(tid)) {
                    continue;
                }
            }
            try {
                Process.setThreadPriority(tid, PIPELINE_NICE);
                XposedBridge.log(TAG + ": boosted thread " + name + " tid=" + tid
                        + " nice=" + Process.getThreadPriority(tid));
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": thread boost failed " + name + " tid=" + tid + " "
                        + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            }
        }
        return found;
    }
}
