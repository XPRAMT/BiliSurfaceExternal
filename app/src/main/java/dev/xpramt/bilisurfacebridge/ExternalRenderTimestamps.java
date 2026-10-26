package dev.xpramt.bilisurfacebridge;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;

import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Carries the Media3 release timestamp through Bilibili's external renderer (Surface external).
 *
 * The IJK service releases each codec buffer with the timestamp Media3 would use (closest
 * VSync - 80 % of a period, 45 ms ahead; see docs/media3-reference.md).  With the external
 * renderer that buffer lands in a SurfaceTexture in this process and the {@code ijk_ext_render}
 * GL thread redraws it into the SurfaceView, so SurfaceFlinger only sees the GL output.  This
 * reads the timestamp back with {@link SurfaceTexture#getTimestamp()} after the GL thread's
 * {@code updateTexImage()} and sets it on the next swap with
 * {@code eglPresentationTimeANDROID}: on {@link EGL14#eglSwapBuffers} directly, and through the
 * native GOT hook when the swap is made from IJK's native code.  SurfaceFlinger then holds the
 * buffer until its slot, exactly as for a Media3 player.
 */
final class ExternalRenderTimestamps {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String RENDER_THREAD_NAME = "ijk_ext_render";
    // Accept only timestamps that look like Media3 release times: not stale, at most ~100 ms
    // ahead.  A plain release(true) leaves the media PTS here, which must not be applied.
    private static final long MAX_PAST_NS = 50_000_000L;
    private static final long MAX_AHEAD_NS = 100_000_000L;

    private static final ThreadLocal<long[]> PENDING = new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
            return new long[1];
        }
    };
    private static final AtomicLong ARMED = new AtomicLong();
    private static final AtomicLong JAVA_SWAPS = new AtomicLong();
    private static boolean installed;

    private ExternalRenderTimestamps() {
    }

    static synchronized String install() {
        if (installed) {
            return "external render timestamps already installed";
        }
        StringBuilder result = new StringBuilder("external render timestamps:");
        try {
            XposedHelpers.findAndHookMethod(SurfaceTexture.class, "updateTexImage",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onUpdateTexImage((SurfaceTexture) param.thisObject);
                        }
                    });
            result.append(" updateTexImage");
        } catch (Throwable throwable) {
            result.append(" updateTexImage-unavailable=").append(throwable.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(EGL14.class, "eglSwapBuffers", EGLDisplay.class,
                    EGLSurface.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            onJavaSwap((EGLDisplay) param.args[0], (EGLSurface) param.args[1]);
                        }
                    });
            result.append(" EGL14.eglSwapBuffers");
        } catch (Throwable throwable) {
            result.append(" EGL14-unavailable=").append(throwable.getClass().getSimpleName());
        }
        // Bilibili's IjkEglCore drives EGL through javax.microedition.khronos.egl.EGL10, whose
        // implementation is com.google.android.gles_jni.EGLImpl.  EGL10 has no presentation-
        // time call, but the surface being swapped is current, so EGL14's current display and
        // draw surface name the same native objects.
        try {
            Class<?> eglImpl = XposedHelpers.findClass("com.google.android.gles_jni.EGLImpl", null);
            int hooked = XposedBridge.hookAllMethods(eglImpl, "eglSwapBuffers", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    onEgl10Swap();
                }
            }).size();
            result.append(" EGL10.eglSwapBuffers=").append(hooked);
        } catch (Throwable throwable) {
            result.append(" EGL10-unavailable=").append(throwable.getClass().getSimpleName());
        }
        installed = true;
        return result.toString();
    }

    private static long lastStampNs;
    private static long plainSwaps;

    private static void traceEvent(String name, long value) {
        if (!ModulePreferences.loadPlaybackDebugOverlayForHook()) {
            return;
        }
        try {
            android.os.Trace.setCounter(name, value);
        } catch (Throwable ignored) {
            // Measurement only.
        }
    }

    /**
     * Per-swap trace counters (only while the debug overlay is on, i.e. during measurement):
     * how far ahead the presentation time is at the swap, and the step from the previous one.
     */
    private static void traceSwap(long timestampNs) {
        if (!ModulePreferences.loadPlaybackDebugOverlayForHook()) {
            return;
        }
        try {
            long nowNs = System.nanoTime();
            android.os.Trace.setCounter("bsb_ext_lead_us", (timestampNs - nowNs) / 1000L);
            if (lastStampNs != 0L) {
                android.os.Trace.setCounter("bsb_ext_step_us", (timestampNs - lastStampNs) / 1000L);
            }
            lastStampNs = timestampNs;
        } catch (Throwable ignored) {
            // Measurement only.
        }
    }

    private static boolean active() {
        return RENDER_THREAD_NAME.equals(Thread.currentThread().getName())
                && ModulePreferences.loadMedia3FrameSyncForHook()
                && ModulePreferences.loadVideoDetailRendererModeForHook()
                        == ModulePreferences.RendererMode.SURFACE_EXTERNAL;
    }

    private static void onUpdateTexImage(SurfaceTexture texture) {
        if (!active()) {
            return;
        }
        long timestampNs = texture.getTimestamp();
        long nowNs = System.nanoTime();
        long[] pending = PENDING.get();
        if (timestampNs < nowNs - MAX_PAST_NS || timestampNs > nowNs + MAX_AHEAD_NS) {
            // Do not clear what another texture armed.  IJK's external renderer updates two
            // SurfaceTextures per draw (hardware and software decoder outputs); the idle one
            // carries a stale timestamp and was usually updated first, but now and then after
            // the video one, which wiped the frame's timestamp: that swap went out untimed and
            // SurfaceFlinger showed it a VSync early (4-6 extra holds / 30 s at 4K120).  A
            // consumed timestamp is cleared at the swap anyway.
            traceEvent("bsb_ext_reject_us", (timestampNs - nowNs) / 1000L);
            return;
        }
        pending[0] = timestampNs;
        // Native swaps (IJK's own GL code) consume the same value through the GOT hook.
        long armed = ARMED.getAndIncrement();
        if (armed == 0L) {
            XposedBridge.log(TAG + ": external render timestamps armed; "
                    + NativeRefreshCap.installSwapTimestampHook());
        } else if (armed % 3600L == 0L) {
            XposedBridge.log(TAG + ": external render timestamps armed=" + armed + " javaSwaps="
                    + JAVA_SWAPS.get() + " native " + NativeRefreshCap.swapTimestampStatus());
        }
        NativeRefreshCap.armSwapTimestamp(timestampNs);
    }

    private static void onEgl10Swap() {
        if (!RENDER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            return;
        }
        if (PENDING.get()[0] == 0L) {
            if (active()) {
                traceEvent("bsb_ext_plain_swap", ++plainSwaps);
            }
            return;
        }
        onJavaSwap(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
    }

    private static void onJavaSwap(EGLDisplay display, EGLSurface surface) {
        if (!RENDER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            return;
        }
        long[] pending = PENDING.get();
        long timestampNs = pending[0];
        if (timestampNs == 0L) {
            return;
        }
        pending[0] = 0L;
        NativeRefreshCap.armSwapTimestamp(0L);   // applied here; the native hook must not repeat it
        if (display == null || surface == null || surface.equals(EGL14.EGL_NO_SURFACE)) {
            return;
        }
        try {
            EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs);
            traceSwap(timestampNs);
            long swaps = JAVA_SWAPS.incrementAndGet();
            if (swaps == 1L || swaps % 3600L == 0L) {
                XposedBridge.log(TAG + ": external render swap timestamps=" + swaps);
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": eglPresentationTimeANDROID failed: "
                    + throwable.getClass().getSimpleName());
        }
    }
}
