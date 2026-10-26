package dev.xpramt.bilisurfacebridge;

import android.os.Process;
import android.view.Choreographer;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import de.robv.android.xposed.XposedBridge;

/**
 * Publishes the main process's app VSync timeline to the IJK service process.
 *
 * The external renderers consume frames on the <em>main</em> process's Choreographer VSync
 * (the {@code ijk_ext_render} GL thread lives there), but the release scheduling runs in
 * {@code tv.danmaku.bili:ijkservice}, whose own Choreographer can sit on a different phase:
 * at 60 Hz the service's VSync ran ~5 ms ahead of the main process's, so "mid-way between two
 * app VSyncs" computed from the service's timeline landed 3 ms after the real one.  Both
 * processes share a uid, so a 32-byte memory-mapped file under the app's files directory
 * carries (sequence, vsyncNs, periodNs) from a main-thread frame callback; the native
 * scheduler maps the same file and prefers it while it is fresh.
 */
final class AppVsyncRelay {
    private static final String TAG = "BiliSurfaceBridge";
    static final String FILE_NAME = "app_vsync_relay.bin";
    private static final int SIZE = 64;
    private static MappedByteBuffer buffer;
    private static long sequence;
    private static boolean running;

    private AppVsyncRelay() {
    }

    static String path() {
        int userId = Process.myUid() / 100000;
        return "/data/user/" + userId + "/tv.danmaku.bili/files/bilisurfacebridge/" + FILE_NAME;
    }

    static synchronized void start() {
        if (running) {
            return;
        }
        try {
            File file = new File(path());
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory()) {
                directory.mkdirs();
            }
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.setLength(SIZE);
            buffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            raf.close();
            running = true;
            Choreographer.getInstance().postFrameCallback(LOOP);
            XposedBridge.log(TAG + ": app VSync relay started at " + file.getPath());
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": app VSync relay unavailable: "
                    + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static final Choreographer.FrameCallback LOOP = new Choreographer.FrameCallback() {
        private long previousFrameTimeNs;

        @Override
        public void doFrame(long frameTimeNanos) {
            MappedByteBuffer out = buffer;
            if (out == null) {
                return;
            }
            long periodNs = previousFrameTimeNs == 0 ? 0 : frameTimeNanos - previousFrameTimeNs;
            previousFrameTimeNs = frameTimeNanos;
            // Seqlock: odd sequence while writing, even when consistent.
            long seq = ++sequence;
            out.putLong(0, seq * 2 + 1);
            out.putLong(8, frameTimeNanos);
            out.putLong(16, periodNs);
            long nowNs = System.nanoTime();
            out.putLong(24, nowNs);
            out.putLong(0, seq * 2);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
}
