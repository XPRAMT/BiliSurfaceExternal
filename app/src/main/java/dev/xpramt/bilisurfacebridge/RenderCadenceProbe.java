package dev.xpramt.bilisurfacebridge;

import android.os.Trace;
import android.os.Handler;
import android.os.Message;
import android.os.MessageQueue;
import android.graphics.SurfaceTexture;
import android.view.Choreographer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Trace-only P2 instrumentation for the external-render callback path.
 *
 * <p>The probe must not choose frames, delay callbacks, or otherwise change playback behavior.
 * It only wraps callbacks posted by the {@code ijk_ext_render} and
 * {@code Bili-Media3-VSync} threads so Perfetto can distinguish a missing post from a posted
 * callback that did not execute before the next VSync.</p>
 */
final class RenderCadenceProbe {
    private static final String RENDER_THREAD_NAME = "ijk_ext_render";
    private static final String JAVA_PLAYER_THREAD_NAME = "ijk_java_player";
    private static final String TRACE_PREFIX = "BiliP2_";
    private static final AtomicLong NEXT_TOKEN = new AtomicLong();
    private static final Map<SurfaceTexture, Long> SURFACE_TEXTURE_TOKENS =
            Collections.synchronizedMap(new WeakHashMap<SurfaceTexture, Long>());
    private static final ThreadLocal<Long> CURRENT_DISPLAY_TOKEN =
            new ThreadLocal<Long>();
    private static boolean installed;

    private RenderCadenceProbe() {
    }

    static synchronized String install() {
        if (installed) {
            return "P2 render cadence probe already installed";
        }
        int installedHooks = 0;
        installedHooks += hook("postFrameCallback", new Object[]{
                Choreographer.FrameCallback.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapFrameCallback(param, false);
                    }
                }});
        installedHooks += hook("postFrameCallbackDelayed", new Object[]{
                Choreographer.FrameCallback.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapFrameCallback(param, true);
                    }
                }});
        installedHooks += hook("postVsyncCallback", new Object[]{
                Choreographer.VsyncCallback.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapVsyncCallback(param);
                    }
                }});
        installedHooks += hook("postCallback", new Object[]{
                int.class, Runnable.class, Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapRunnable(param, false);
                    }
                }});
        installedHooks += hook("postCallbackDelayed", new Object[]{
                int.class, Runnable.class, Object.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapRunnable(param, true);
                    }
                }});
        installedHooks += hookHandler("post", new Object[]{
                Runnable.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapHandlerRunnable(param, 0, "handler_post");
                    }
                }});
        installedHooks += hookHandler("postDelayed", new Object[]{
                Runnable.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapHandlerRunnable(param, 0, "handler_post_delayed");
                    }
                }});
        installedHooks += hookHandler("postAtFrontOfQueue", new Object[]{
                Runnable.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapHandlerRunnable(param, 0, "handler_post_front");
                    }
                }});
        installedHooks += hookHandler("postAtTime", new Object[]{
                Runnable.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapHandlerRunnable(param, 0, "handler_post_at_time");
                    }
                }});
        installedHooks += hookHandler("postAtTime", new Object[]{
                Runnable.class, Object.class, long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        wrapHandlerRunnable(param, 0, "handler_post_at_time_token");
                    }
                }});
        installedHooks += hookHandler("sendMessageAtFrontOfQueue", new Object[]{
                Message.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isHandoffThread() || !(param.args[0] instanceof Message)) {
                            return;
                        }
                        Message message = (Message) param.args[0];
                        if (message.what == 1) {
                            tracePoint("choreographer_frame_handler_front",
                                    NEXT_TOKEN.incrementAndGet(), currentDisplayToken());
                        }
                    }
                }});
        try {
            installedHooks += hookDeclaredMethods(MessageQueue.class, "enqueueMessage",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isHandoffThread()
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof Message)) {
                                return;
                            }
                            Message message = (Message) param.args[0];
                            if (message.what == 1) {
                                tracePoint("choreographer_queue_enqueue",
                                        NEXT_TOKEN.incrementAndGet(), currentDisplayToken());
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: MessageQueue.enqueueMessage hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        try {
            installedHooks += hookDeclaredMethods(Choreographer.class,
                    "postCallbackDelayedInternal", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isHandoffThread()) {
                                tracePoint("choreographer_post_callback_internal",
                                        NEXT_TOKEN.incrementAndGet(), currentDisplayToken());
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: Choreographer.postCallbackDelayedInternal hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        try {
            installedHooks += hookDeclaredMethods(Choreographer.class,
                    "scheduleFrameLocked", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isHandoffThread()) {
                                tracePoint("choreographer_schedule_frame_locked",
                                        NEXT_TOKEN.incrementAndGet(), currentDisplayToken());
                            }
                        }
                    });
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: Choreographer.scheduleFrameLocked hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        installedHooks += hookFrameDisplayEventReceiver();
        installedHooks += hookSurfaceTexture();
        installed = installedHooks > 0;
        return installed
                ? "installed P2 render cadence probe hooks=" + installedHooks
                : "P2 render cadence probe unavailable";
    }

    private static int hook(String methodName, Object[] parameterTypesAndCallback) {
        try {
            Object[] arguments = parameterTypesAndCallback.clone();
            XC_MethodHook callback = (XC_MethodHook) arguments[arguments.length - 1];
            Object[] parameterTypes = new Object[arguments.length - 1];
            System.arraycopy(arguments, 0, parameterTypes, 0, parameterTypes.length);
            XposedHelpers.findAndHookMethod(Choreographer.class, methodName,
                    appendCallback(parameterTypes, callback));
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int hookHandler(String methodName, Object[] parameterTypesAndCallback) {
        try {
            Object[] arguments = parameterTypesAndCallback.clone();
            XC_MethodHook callback = (XC_MethodHook) arguments[arguments.length - 1];
            Object[] parameterTypes = new Object[arguments.length - 1];
            System.arraycopy(arguments, 0, parameterTypes, 0, parameterTypes.length);
            XposedHelpers.findAndHookMethod(Handler.class, methodName,
                    appendCallback(parameterTypes, callback));
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Trace the hidden receiver boundary used by Choreographer on the external-render thread.
     * This is deliberately observation-only: it does not change the timestamp, frame number,
     * message, or callback result.
     */
    private static int hookFrameDisplayEventReceiver() {
        Class<?> receiverClass;
        try {
            receiverClass = XposedHelpers.findClass(
                    "android.view.Choreographer$FrameDisplayEventReceiver",
                    ClassLoader.getSystemClassLoader());
        } catch (Throwable systemLoaderFailure) {
            try {
                receiverClass = XposedHelpers.findClass(
                        "android.view.Choreographer$FrameDisplayEventReceiver", null);
            } catch (Throwable bootLoaderFailure) {
                XposedBridge.log("BiliSurfaceBridge: FrameDisplayEventReceiver class unavailable: "
                        + bootLoaderFailure.getClass().getSimpleName());
                return 0;
            }
        }
        int hooks = 0;
        XC_MethodHook onVsyncHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int frame = param.args.length > 2 && param.args[2] instanceof Integer
                        ? ((Integer) param.args[2]).intValue()
                        : -1;
                tracePoint("frame_receiver_on_vsync_" + frame,
                        NEXT_TOKEN.incrementAndGet(), currentDisplayToken());
            }
        };
        XC_MethodHook dispatchVsyncHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                long timestampNanos = param.args.length > 0 && param.args[0] instanceof Long
                        ? ((Long) param.args[0]).longValue()
                        : -1L;
                int frame = param.args.length > 2 && param.args[2] instanceof Integer
                        ? ((Integer) param.args[2]).intValue()
                        : -1;
                tracePoint("frame_receiver_dispatch_vsync_" + frame + "_t" + timestampNanos,
                        NEXT_TOKEN.incrementAndGet(), currentDisplayToken());
            }
        };
        try {
            XposedHelpers.findAndHookMethod(receiverClass, "onVsync",
                    long.class, int.class, int.class,
                    onVsyncHook);
            hooks++;
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: FrameDisplayEventReceiver.onVsync hook unavailable: "
                    + throwable.getClass().getSimpleName());
            try {
                Class<?> displayReceiverClass = XposedHelpers.findClass(
                        "android.view.DisplayEventReceiver", null);
                int fallbackHooks = hookAllOnVsyncMethods(displayReceiverClass, onVsyncHook);
                hooks += fallbackHooks;
                if (fallbackHooks == 0) {
                    XposedBridge.log("BiliSurfaceBridge: DisplayEventReceiver has no reflected onVsync method");
                }
            } catch (Throwable fallbackThrowable) {
                XposedBridge.log("BiliSurfaceBridge: DisplayEventReceiver.onVsync hook unavailable: "
                        + fallbackThrowable.getClass().getSimpleName());
            }
        }
        try {
            Class<?> displayReceiverClass = XposedHelpers.findClass(
                    "android.view.DisplayEventReceiver", null);
            int dispatchHooks = hookDeclaredMethods(
                    displayReceiverClass, "dispatchVsync", dispatchVsyncHook);
            hooks += dispatchHooks;
            if (dispatchHooks == 0) {
                XposedBridge.log("BiliSurfaceBridge: DisplayEventReceiver has no declared dispatchVsync method");
            }
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: DisplayEventReceiver.dispatchVsync hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        try {
            XposedHelpers.findAndHookMethod(receiverClass, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isRenderThread()) {
                        tracePoint("frame_receiver_run",
                                NEXT_TOKEN.incrementAndGet(), currentDisplayToken());
                    }
                }
            });
            hooks++;
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: FrameDisplayEventReceiver.run hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        return hooks;
    }

    /**
     * Trace the SurfaceTexture producer-to-consumer handoff without changing the texture or
     * renderer state. The token is object-local and only joins callbacks to updateTexImage within
     * this process; it is deliberately not presented as a SurfaceFlinger frame identity.
     */
    private static int hookSurfaceTexture() {
        int hooks = 0;
        try {
            XposedHelpers.findAndHookMethod(SurfaceTexture.class, "updateTexImage",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isHandoffThread() || !(param.thisObject instanceof SurfaceTexture)) {
                                return;
                            }
                            long token = surfaceTextureToken((SurfaceTexture) param.thisObject);
                            Trace.beginSection(TRACE_PREFIX + "surface_texture_update_" + token);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (isHandoffThread() && param.thisObject instanceof SurfaceTexture) {
                                try {
                                    SurfaceTexture surfaceTexture = (SurfaceTexture) param.thisObject;
                                    long token = surfaceTextureToken(surfaceTexture);
                                    long timestamp = surfaceTexture.getTimestamp();
                                    String name = TRACE_PREFIX + "surface_texture_update_timestamp_"
                                            + token + "_p" + timestamp;
                                    Trace.beginSection(name);
                                    Trace.endSection();
                                } catch (Throwable ignored) {
                                    // Timestamp telemetry is diagnostic only.
                                }
                                Trace.endSection();
                            }
                        }
                    });
            hooks++;
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: SurfaceTexture.updateTexImage hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        try {
            Class<?> listenerClass = XposedHelpers.findClass(
                    "android.graphics.SurfaceTexture$1", null);
            XposedHelpers.findAndHookMethod(listenerClass, "handleMessage", Message.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isHandoffThread()) {
                                return;
                            }
                            SurfaceTexture surfaceTexture = surfaceTextureFromListenerHandler(
                                    param.thisObject);
                            if (surfaceTexture == null) {
                                return;
                            }
                            long token = surfaceTextureToken(surfaceTexture);
                            tracePoint("surface_texture_frame_ready", token, 0L);
                        }
                    });
            hooks++;
        } catch (Throwable throwable) {
            XposedBridge.log("BiliSurfaceBridge: SurfaceTexture listener hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        return hooks;
    }

    private static SurfaceTexture surfaceTextureFromListenerHandler(Object handler) {
        Class<?> type = handler == null ? null : handler.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(handler);
                    if (value instanceof SurfaceTexture) {
                        return (SurfaceTexture) value;
                    }
                } catch (Throwable ignored) {
                    // The anonymous Handler's synthetic outer field is implementation-specific.
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static long surfaceTextureToken(SurfaceTexture surfaceTexture) {
        synchronized (SURFACE_TEXTURE_TOKENS) {
            Long token = SURFACE_TEXTURE_TOKENS.get(surfaceTexture);
            if (token == null) {
                token = NEXT_TOKEN.incrementAndGet();
                SURFACE_TEXTURE_TOKENS.put(surfaceTexture, token);
            }
            return token.longValue();
        }
    }

    private static int hookAllOnVsyncMethods(Class<?> receiverClass, XC_MethodHook hook)
            throws Throwable {
        return hookDeclaredMethods(receiverClass, "onVsync", hook);
    }

    private static int hookDeclaredMethods(Class<?> receiverClass, String methodName,
                                            XC_MethodHook hook) throws Throwable {
        int hooks = 0;
        java.lang.reflect.Method[] methods = receiverClass.getDeclaredMethods();
        for (java.lang.reflect.Method method : methods) {
            if (!methodName.equals(method.getName())) {
                continue;
            }
            XposedBridge.hookMethod(method, hook);
            hooks++;
        }
        return hooks;
    }

    private static Object[] appendCallback(Object[] parameterTypes, XC_MethodHook callback) {
        Object[] arguments = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, arguments, 0, parameterTypes.length);
        arguments[arguments.length - 1] = callback;
        return arguments;
    }

    private static boolean isRenderThread() {
        return RENDER_THREAD_NAME.equals(Thread.currentThread().getName());
    }

    private static boolean isHandoffThread() {
        String name = Thread.currentThread().getName();
        return isRenderThread()
                || JAVA_PLAYER_THREAD_NAME.equals(name)
                || "Bili-Media3-VSync".equals(name)
                || name.startsWith("binder:");
    }

    private static void wrapFrameCallback(XC_MethodHook.MethodHookParam param, boolean delayed) {
        if (!isHandoffThread()) {
            return;
        }
        Object callback = param.args[0];
        if (!(callback instanceof Choreographer.FrameCallback)
                || callback instanceof FrameCallbackWrapper) {
            return;
        }
        long token = NEXT_TOKEN.incrementAndGet();
        long displayToken = currentDisplayToken();
        FrameCallbackWrapper wrapper = new FrameCallbackWrapper(
                (Choreographer.FrameCallback) callback, token, displayToken);
        param.args[0] = wrapper;
        tracePoint(delayed ? "post_frame_delayed" : "post_frame", token, displayToken);
    }

    private static void wrapVsyncCallback(XC_MethodHook.MethodHookParam param) {
        if (!isHandoffThread()) {
            return;
        }
        Object callback = param.args[0];
        if (!(callback instanceof Choreographer.VsyncCallback)
                || callback instanceof VsyncCallbackWrapper) {
            return;
        }
        long token = NEXT_TOKEN.incrementAndGet();
        long displayToken = currentDisplayToken();
        param.args[0] = new VsyncCallbackWrapper(
                (Choreographer.VsyncCallback) callback, token, displayToken);
        tracePoint("post_vsync", token, displayToken);
    }

    private static void wrapRunnable(XC_MethodHook.MethodHookParam param, boolean delayed) {
        if (!isHandoffThread()) {
            return;
        }
        Object callback = param.args[1];
        if (!(callback instanceof Runnable) || callback instanceof RunnableWrapper) {
            return;
        }
        long token = NEXT_TOKEN.incrementAndGet();
        long displayToken = currentDisplayToken();
        RunnableWrapper wrapper = new RunnableWrapper((Runnable) callback, token, displayToken);
        param.args[1] = wrapper;
        tracePoint(delayed ? "post_runnable_delayed" : "post_runnable", token, displayToken);
    }

    private static void wrapHandlerRunnable(XC_MethodHook.MethodHookParam param,
                                             int argumentIndex, String event) {
        if (!isHandoffThread()) {
            return;
        }
        Object callback = param.args[argumentIndex];
        if (!(callback instanceof Runnable) || callback instanceof RunnableWrapper) {
            return;
        }
        long token = NEXT_TOKEN.incrementAndGet();
        long displayToken = currentDisplayToken();
        param.args[argumentIndex] = new RunnableWrapper(
                (Runnable) callback, token, displayToken);
        tracePoint(event, token, displayToken);
    }

    static DisplayScope beginDisplayCallback() {
        long token = NEXT_TOKEN.incrementAndGet();
        Long previous = CURRENT_DISPLAY_TOKEN.get();
        CURRENT_DISPLAY_TOKEN.set(token);
        tracePoint("display_callback", token, 0L);
        return new DisplayScope(token, previous);
    }

    static void endDisplayCallback(DisplayScope scope) {
        if (scope == null) {
            return;
        }
        tracePoint("display_callback_end", scope.token, 0L);
        if (scope.previous == null) {
            CURRENT_DISPLAY_TOKEN.remove();
        } else {
            CURRENT_DISPLAY_TOKEN.set(scope.previous);
        }
    }

    private static long currentDisplayToken() {
        Long token = CURRENT_DISPLAY_TOKEN.get();
        return token == null ? 0L : token;
    }

    private static void tracePoint(String event, long token, long displayToken) {
        try {
            String name = TRACE_PREFIX + event + "_" + token;
            if (displayToken != 0L) {
                name += "_d" + displayToken;
            }
            Trace.beginSection(name);
            Trace.endSection();
        } catch (Throwable ignored) {
            // Trace is diagnostic only and must never affect the render callback.
        }
    }

    static final class DisplayScope {
        private final long token;
        private final Long previous;

        private DisplayScope(long token, Long previous) {
            this.token = token;
            this.previous = previous;
        }
    }

    private static final class FrameCallbackWrapper implements Choreographer.FrameCallback {
        private final Choreographer.FrameCallback delegate;
        private final long token;
        private final long displayToken;

        private FrameCallbackWrapper(Choreographer.FrameCallback delegate, long token,
                                     long displayToken) {
            this.delegate = delegate;
            this.token = token;
            this.displayToken = displayToken;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            tracePoint("frame_callback", token, displayToken);
            Long previous = CURRENT_DISPLAY_TOKEN.get();
            try {
                setDisplayToken(displayToken);
                delegate.doFrame(frameTimeNanos);
            } finally {
                restoreDisplayToken(previous);
            }
        }
    }

    private static final class VsyncCallbackWrapper implements Choreographer.VsyncCallback {
        private final Choreographer.VsyncCallback delegate;
        private final long token;
        private final long displayToken;

        private VsyncCallbackWrapper(Choreographer.VsyncCallback delegate, long token,
                                     long displayToken) {
            this.delegate = delegate;
            this.token = token;
            this.displayToken = displayToken;
        }

        @Override
        public void onVsync(Choreographer.FrameData frameData) {
            tracePoint("vsync_callback", token, displayToken);
            Long previous = CURRENT_DISPLAY_TOKEN.get();
            try {
                setDisplayToken(displayToken);
                delegate.onVsync(frameData);
            } finally {
                restoreDisplayToken(previous);
            }
        }
    }

    private static final class RunnableWrapper implements Runnable {
        private final Runnable delegate;
        private final long token;
        private final long displayToken;

        private RunnableWrapper(Runnable delegate, long token, long displayToken) {
            this.delegate = delegate;
            this.token = token;
            this.displayToken = displayToken;
        }

        @Override
        public void run() {
            tracePoint("handler_callback", token, displayToken);
            Long previous = CURRENT_DISPLAY_TOKEN.get();
            try {
                setDisplayToken(displayToken);
                delegate.run();
            } finally {
                restoreDisplayToken(previous);
            }
        }
    }

    private static void setDisplayToken(long token) {
        if (token == 0L) {
            CURRENT_DISPLAY_TOKEN.remove();
        } else {
            CURRENT_DISPLAY_TOKEN.set(token);
        }
    }

    private static void restoreDisplayToken(Long token) {
        if (token == null) {
            CURRENT_DISPLAY_TOKEN.remove();
        } else {
            CURRENT_DISPLAY_TOKEN.set(token);
        }
    }
}
