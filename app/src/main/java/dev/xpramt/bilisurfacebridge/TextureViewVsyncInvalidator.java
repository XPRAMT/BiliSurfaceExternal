package dev.xpramt.bilisurfacebridge;

import android.view.Choreographer;
import android.view.TextureView;
import android.view.View;
import android.view.ViewParent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Redraws Bilibili's video TextureView on every UI VSync while it is attached.
 *
 * With a TextureView the frame only reaches the screen when the UI thread runs a traversal
 * and the RenderThread samples the SurfaceTexture.  HWUI schedules that traversal from the
 * SurfaceTexture's frame-available message, and that message loses against the Choreographer
 * VSync message whenever the GL output lands within ~1.5 ms of the VSync (the message carries
 * the VSync timestamp, so it sorts first).  Each loss is a VSync without a draw: one missing
 * present even though the frame was there (13 of the 22 gaps/30 s at 4K120).  Posting an
 * {@code invalidate()} from a Choreographer frame callback makes the traversal unconditional,
 * so the only remaining question is which GL frame the RenderThread samples, and the native
 * release scheduling keeps that mid-way between two RenderThread draws.
 */
final class TextureViewVsyncInvalidator {
    private static final String TAG = "BiliSurfaceBridge";
    private static final String RENDER_LAYER_MARKER = "RenderLayer";
    private static final Map<TextureView, Choreographer.FrameCallback> LOOPS =
            Collections.synchronizedMap(new WeakHashMap<TextureView, Choreographer.FrameCallback>());
    private static boolean installed;

    private TextureViewVsyncInvalidator() {
    }

    static synchronized String install() {
        if (installed) {
            return "TextureView VSync invalidator already installed";
        }
        try {
            XposedHelpers.findAndHookMethod(TextureView.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            start((TextureView) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(TextureView.class, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            stop((TextureView) param.thisObject);
                        }
                    });
            installed = true;
            return "TextureView VSync invalidator installed";
        } catch (Throwable throwable) {
            return "TextureView VSync invalidator unavailable="
                    + throwable.getClass().getSimpleName();
        }
    }

    private static boolean isVideoRenderTexture(TextureView view) {
        ViewParent parent = view.getParent();
        for (int depth = 0; parent != null && depth < 6; depth++) {
            if (parent.getClass().getName().contains(RENDER_LAYER_MARKER)) {
                return true;
            }
            parent = parent.getParent();
        }
        return view.getClass().getName().contains(RENDER_LAYER_MARKER);
    }

    private static void start(final TextureView view) {
        if (!ModulePreferences.loadMedia3FrameSyncForHook()
                || ModulePreferences.loadVideoDetailRendererModeForHook()
                        != ModulePreferences.RendererMode.TEXTURE_EXTERNAL
                || !isVideoRenderTexture(view) || LOOPS.containsKey(view)) {
            return;
        }
        Choreographer.FrameCallback loop = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (LOOPS.get(view) != this || !view.isAttachedToWindow()) {
                    return;
                }
                view.invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            }
        };
        LOOPS.put(view, loop);
        Choreographer.getInstance().postFrameCallback(loop);
        XposedBridge.log(TAG + ": TextureView VSync invalidator started for "
                + view.getClass().getName() + " in " + parentName(view));
    }

    private static void stop(TextureView view) {
        if (LOOPS.remove(view) != null) {
            XposedBridge.log(TAG + ": TextureView VSync invalidator stopped");
        }
    }

    private static String parentName(View view) {
        ViewParent parent = view.getParent();
        return parent == null ? "null" : parent.getClass().getName();
    }
}
