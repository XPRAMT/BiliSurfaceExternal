package dev.xpramt.bilisurfacebridge;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes Bilibili's external renderer draw a video frame as soon as it arrives instead of on
 * the next Choreographer VSync.
 *
 * Every decoded frame makes {@code tv.danmaku.ijk.media.player.render.IjkExternalRenderThread}
 * post a {@link Choreographer.FrameCallback} to the {@code ijk_ext_render} thread's
 * Choreographer, and the GL draw then waits for the following VSync.  That ties the GL output
 * to the app VSync regardless of when the frame actually arrived, which costs:
 * <ul>
 *   <li>{@code Surface external}: the GL output lands on the same VSync as the app's own UI
 *   work and its timing is a coin flip against the decoder's arrival phase.</li>
 *   <li>{@code Texture}: the GL draw (VSync + ~1 ms) and the HWUI RenderThread draw that
 *   samples it run back to back on the GPU, and SurfaceFlinger latches ~3.6 ms after the
 *   RenderThread queues; the serial GPU work regularly misses that and the frame slips a slot
 *   (73 gaps / 30 s with the VSync-driven renderer).</li>
 * </ul>
 * The hook recognises the render thread's Choreographer by its Looper and runs the callback at
 * the front of that thread's queue instead, so the GL output becomes "decoder arrival + draw
 * time", a phase the native release scheduling already controls
 * ({@code Media3FrameSynchronizer.NATIVE_CALL_LEAD_*}).  A rate guard falls back to the normal
 * VSync post if a renderer ever re-posts continuously.
 */
final class ExternalRenderImmediate {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String RENDER_THREAD_NAME = "ijk_ext_render";
    private static final long RATE_WINDOW_NS = 1_000_000_000L;
    private static final int MAX_IMMEDIATE_PER_WINDOW = 400;   // 120 fps plus margin

    private static final Map<Choreographer, Handler> RENDER_HANDLERS =
            Collections.synchronizedMap(new WeakHashMap<Choreographer, Handler>());
    private static final AtomicLong IMMEDIATE_FRAMES = new AtomicLong();
    private static final AtomicLong GUARDED_FRAMES = new AtomicLong();
    private static long windowStartNs;
    private static int windowCount;
    private static boolean installed;

    private ExternalRenderImmediate() {
    }

    static synchronized String install() {
        if (installed) {
            return "external render immediate already installed";
        }
        try {
            XposedHelpers.findAndHookMethod(Choreographer.class, "postFrameCallback",
                    Choreographer.FrameCallback.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            runNow(param);
                        }
                    });
            XposedHelpers.findAndHookMethod(Choreographer.class, "postFrameCallbackDelayed",
                    Choreographer.FrameCallback.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (((Long) param.args[1]).longValue() <= 0L) {
                                runNow(param);
                            }
                        }
                    });
            installCompositorSurfaceCapture();
            installed = true;
            return "external render immediate installed";
        } catch (Throwable throwable) {
            return "external render immediate unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    /**
     * The GL thread's EGL window surface is the SurfaceView Surface that SurfaceFlinger
     * composes (Surface external); its compositor timing is relayed to the scheduler.
     */
    private static void installCompositorSurfaceCapture() {
        XC_MethodHook capture = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!RENDER_THREAD_NAME.equals(Thread.currentThread().getName())) {
                    return;
                }
                android.view.Surface surface = surfaceOf(param.args[2]);
                if (surface == null) {
                    return;
                }
                NativeRefreshCap.load();
                XposedBridge.log(TAG + ": compositor surface captured from "
                        + param.args[2].getClass().getName() + ": "
                        + NativeRefreshCap.setCompositorWindow(surface));
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.opengl.EGL14.class, "eglCreateWindowSurface",
                    android.opengl.EGLDisplay.class, android.opengl.EGLConfig.class, Object.class,
                    int[].class, int.class, capture);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": EGL14 window hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        try {
            Class<?> eglImpl = XposedHelpers.findClass("com.google.android.gles_jni.EGLImpl", null);
            XposedBridge.hookAllMethods(eglImpl, "eglCreateWindowSurface", capture);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": EGL10 window hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private static android.view.Surface surfaceOf(Object window) {
        if (window instanceof android.view.Surface) {
            return (android.view.Surface) window;
        }
        if (window instanceof android.view.SurfaceView) {
            return ((android.view.SurfaceView) window).getHolder().getSurface();
        }
        if (window instanceof android.view.SurfaceHolder) {
            return ((android.view.SurfaceHolder) window).getSurface();
        }
        return null;   // SurfaceTexture (TextureView): not a SurfaceFlinger surface
    }

    /** The render thread's Choreographer, identified through its Looper; null otherwise. */
    private static Handler renderHandler(Choreographer choreographer) {
        Handler handler = RENDER_HANDLERS.get(choreographer);
        if (handler != null) {
            return handler;
        }
        try {
            Looper looper = (Looper) XposedHelpers.getObjectField(choreographer, "mLooper");
            if (looper == null || looper.getThread() == null
                    || !RENDER_THREAD_NAME.equals(looper.getThread().getName())) {
                return null;
            }
            handler = new Handler(looper);
            RENDER_HANDLERS.put(choreographer, handler);
            XposedBridge.log(TAG + ": external render immediate bound to "
                    + looper.getThread().getName() + " from "
                    + Thread.currentThread().getName());
            return handler;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static synchronized boolean withinRate(long nowNs) {
        if (nowNs - windowStartNs > RATE_WINDOW_NS) {
            windowStartNs = nowNs;
            windowCount = 0;
        }
        return ++windowCount <= MAX_IMMEDIATE_PER_WINDOW;
    }

    private static void runNow(XC_MethodHook.MethodHookParam param) {
        // Both external renderers draw on arrival: the GL output phase is then set by the
        // native release scheduling instead of by whichever app VSync follows the arrival
        // (which breaks down when the app VSync rate and the display rate differ, e.g. 60 fps
        // content on a 120 Hz-capable panel).
        // Surface external as well (2026-10-26): its swap carries the Media3 release timestamp
        // (ExternalRenderTimestamps), so drawing on arrival costs nothing, while drawing on the
        // next app VSync made the result depend on the arrival phase.  The codec frame arrived
        // 1-2 ms before an app VSync, the callback request often missed it, two frames met in
        // the following VSync and the SurfaceTexture drain skipped one (98 fps, 1,1,1,1,2).
        // The ijkservice VSync grid cannot predict the main process's VSync phase reliably.
        ModulePreferences.RendererMode mode = ModulePreferences.loadVideoDetailRendererModeForHook();
        if (!(param.args[0] instanceof Choreographer.FrameCallback)
                || !ModulePreferences.loadMedia3FrameSyncForHook()
                || (mode != ModulePreferences.RendererMode.TEXTURE_EXTERNAL
                        && mode != ModulePreferences.RendererMode.SURFACE_EXTERNAL)) {
            return;
        }
        final Handler handler = renderHandler((Choreographer) param.thisObject);
        if (handler == null) {
            return;
        }
        if (!withinRate(System.nanoTime())) {
            GUARDED_FRAMES.incrementAndGet();
            return;
        }
        final Choreographer.FrameCallback callback = (Choreographer.FrameCallback) param.args[0];
        handler.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.doFrame(System.nanoTime());
                } catch (Throwable throwable) {
                    XposedBridge.log(TAG + ": immediate external render failed: "
                            + throwable.getClass().getSimpleName() + ": "
                            + throwable.getMessage());
                }
            }
        });
        long frames = IMMEDIATE_FRAMES.incrementAndGet();
        if (frames == 1L || frames % 3600L == 0L) {
            XposedBridge.log(TAG + ": external render immediate frames=" + frames
                    + " rateGuarded=" + GUARDED_FRAMES.get());
        }
        param.setResult(null);
    }
}
