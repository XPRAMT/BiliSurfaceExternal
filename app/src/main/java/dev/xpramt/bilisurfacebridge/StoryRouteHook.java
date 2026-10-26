package dev.xpramt.bilisurfacebridge;

import android.net.Uri;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Adapts the BiliRoamingX Story route rewrite to the official 9.13.0 LSPosed runtime. */
final class StoryRouteHook {
    private static final String REWRITTEN_STORY_REQUEST =
            "dev.xpramt.bilisurfacebridge.rewritten_story_request";
    private static final String[] STORY_CONTEXT_KEYS = {
            "story_item",
            "player_preload",
            "player_rotate",
            "report_flow_data",
            "preload_info",
            "bili_only",
            "jumpFrom",
            "support_miniplayer",
            "force_horizon_ratio",
            "like_num",
            "comment_num",
            "coin_num",
            "favorite_num",
            "bundle_key_player_shared_id",
            "bundle_key_player_shared_type",
            "player_share",
            "player_width",
            "player_height",
            "video_aspect"
    };

    private StoryRouteHook() {
    }

    static String install(ClassLoader classLoader) {
        int installed = 0;
        StringBuilder failures = new StringBuilder();
        try {
            Class<?> builder = XposedHelpers.findClass(
                    "com.bilibili.lib.blrouter.RouteRequest$Builder", classLoader);
            XposedHelpers.findAndHookConstructor(builder, Uri.class, new AndroidUriHook());
            XposedHelpers.findAndHookConstructor(builder, String.class, new StringUriHook());
            XposedHelpers.findAndHookMethod(builder, "build", new BuildHook());
            installed += 3;
        } catch (Throwable failure) {
            failures.append(" legacy=").append(failure.getClass().getSimpleName());
        }
        try {
            Class<?> internalUri = XposedHelpers.findClass(
                    "com.bilibili.lib.brouter.uri.Uri", classLoader);
            Class<?> builder = XposedHelpers.findClass(
                    "com.bilibili.lib.brouter.api.internal.DefaultRouteRequest$a", classLoader);
            Object companion = XposedHelpers.getStaticObjectField(internalUri, "Companion");
            XposedHelpers.findAndHookConstructor(builder, internalUri,
                    new InternalUriHook(companion));
            installed++;
        } catch (Throwable failure) {
            failures.append(" internal=").append(failure.getClass().getSimpleName());
        }
        return "Story route hooks=" + installed + failures;
    }

    private static final class AndroidUriHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Uri original = (Uri) param.args[0];
            if (original == null) {
                return;
            }
            String raw = original.toString();
            String rewritten = StoryRouteRewriter.rewrite(raw);
            if (!raw.equals(rewritten)) {
                param.args[0] = Uri.parse(rewritten);
                param.setObjectExtra(REWRITTEN_STORY_REQUEST, Boolean.TRUE);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            markRewrittenRequest(param);
        }
    }

    private static final class StringUriHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String raw = (String) param.args[0];
            String rewritten = StoryRouteRewriter.rewrite(raw);
            if (raw != null && !raw.equals(rewritten)) {
                param.args[0] = rewritten;
                param.setObjectExtra(REWRITTEN_STORY_REQUEST, Boolean.TRUE);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            markRewrittenRequest(param);
        }
    }

    private static final class InternalUriHook extends XC_MethodHook {
        private final Object companion;

        InternalUriHook(Object companion) {
            this.companion = companion;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object original = param.args[0];
            if (original == null) {
                return;
            }
            String raw = original.toString();
            String rewritten = StoryRouteRewriter.rewrite(raw);
            if (!raw.equals(rewritten)) {
                param.args[0] = XposedHelpers.callMethod(companion, "parse", rewritten);
                param.setObjectExtra(REWRITTEN_STORY_REQUEST, Boolean.TRUE);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            markRewrittenRequest(param);
        }
    }

    private static final class BuildHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                    param.thisObject, REWRITTEN_STORY_REQUEST))) {
                return;
            }
            try {
                Object extras = XposedHelpers.callMethod(param.thisObject, "getExtras");
                if (extras == null) {
                    return;
                }
                for (String key : STORY_CONTEXT_KEYS) {
                    XposedHelpers.callMethod(extras, "remove", key);
                }
                Log.i("BiliSurfaceBridge", "story route stripped Story context before build");
            } catch (Throwable failure) {
                Log.e("BiliSurfaceBridge", "story route context cleanup failed: "
                        + failure.getClass().getSimpleName());
            }
        }
    }

    private static void markRewrittenRequest(XC_MethodHook.MethodHookParam param) {
        if (Boolean.TRUE.equals(param.getObjectExtra(REWRITTEN_STORY_REQUEST))
                && param.thisObject != null) {
            XposedHelpers.setAdditionalInstanceField(
                    param.thisObject, REWRITTEN_STORY_REQUEST, Boolean.TRUE);
        }
    }
}
