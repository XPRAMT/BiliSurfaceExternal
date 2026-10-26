package dev.xpramt.bilisurfacebridge;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Narrow, idempotent route rewrite; never changes a video's identity or unrelated parameters. */
final class StoryRouteRewriter {
    private static final String STORY_PREFIX = "bilibili://story";
    private static final String VIDEO_PREFIX = "bilibili://video";
    private static final String[] STORY_CONTEXT_PARAMETERS = {
            "story_item",
            "player_preload",
            "player_rotate",
            "player_width",
            "player_height",
            "video_aspect",
            "bundle_key_player_shared_id",
            "bundle_key_player_shared_type",
            "player_share",
            "report_flow_data",
            "preload_info",
            "bili_only",
            "jumpFrom",
            "support_miniplayer",
            "force_horizon_ratio",
            "like_num",
            "comment_num",
            "coin_num",
            "favorite_num"
    };
    private StoryRouteRewriter() {
    }

    static String rewrite(String url) {
        if (url == null) {
            return null;
        }
        boolean story = hasAuthority(url, STORY_PREFIX);
        if (story && hasQueryKey(url, "bundle_key_player_shared_id")) {
            return url;
        }
        boolean video = hasAuthority(url, VIDEO_PREFIX);
        boolean webVideo = url.startsWith("https://www.bilibili.com/video/")
                || url.startsWith("http://www.bilibili.com/video/");
        if (!story && !video && !webVideo) {
            return url;
        }
        String result = story ? VIDEO_PREFIX + url.substring(STORY_PREFIX.length()) : url;
        int queryStart = result.indexOf('?');
        if (queryStart < 0) {
            return result;
        }
        int fragmentStart = result.indexOf('#', queryStart);
        int queryEnd = fragmentStart < 0 ? result.length() : fragmentStart;
        String[] fields = result.substring(queryStart + 1, queryEnd).split("&", -1);
        StringBuilder query = new StringBuilder();
        boolean removed = false;
        for (String field : fields) {
            int valueStart = field.indexOf('=');
            String key = valueStart < 0 ? field : field.substring(0, valueStart);
            if (isStoryOnlyParameter(key, story)) {
                removed = true;
                continue;
            }
            if (query.length() != 0) {
                query.append('&');
            }
            query.append(field);
        }
        if (!removed) {
            return result;
        }
        return result.substring(0, queryStart) + (query.length() == 0 ? "" : "?" + query)
                + result.substring(queryEnd);
    }

    private static boolean isStoryOnlyParameter(String rawKey, boolean story) {
        String key;
        try {
            key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            key = rawKey;
        }
        if ("-Arouter".equals(key) || "-Atype".equals(key)) {
            return true;
        }
        if (!story) {
            return false;
        }
        for (String storyContextParameter : STORY_CONTEXT_PARAMETERS) {
            if (storyContextParameter.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasQueryKey(String url, String expectedKey) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        int fragmentStart = url.indexOf('#', queryStart);
        int queryEnd = fragmentStart < 0 ? url.length() : fragmentStart;
        for (String field : url.substring(queryStart + 1, queryEnd).split("&", -1)) {
            int valueStart = field.indexOf('=');
            String rawKey = valueStart < 0 ? field : field.substring(0, valueStart);
            try {
                if (expectedKey.equals(URLDecoder.decode(rawKey,
                        StandardCharsets.UTF_8.name()))) {
                    return true;
                }
            } catch (Exception ignored) {
                if (expectedKey.equals(rawKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAuthority(String url, String prefix) {
        if (!url.startsWith(prefix)) {
            return false;
        }
        if (url.length() == prefix.length()) {
            return true;
        }
        char next = url.charAt(prefix.length());
        return next == '/' || next == '?' || next == '#';
    }
}
