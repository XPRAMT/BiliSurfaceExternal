package dev.xpramt.bilisurfacebridge;

/** Identifies Bilibili activities whose video stream information may be shown by the module. */
final class PlaybackActivityScope {
    private static final String VIDEO_DETAIL_ACTIVITY =
            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity";
    private static final String VIDEO_PLAYLIST_ACTIVITY =
            "com.bilibili.ship.theseus.playlist.UnitedPlaylistActivity";
    private static final String VIDEO_STORY_ACTIVITY =
            "com.bilibili.video.story.StoryVideoActivity";
    private static final String LIVE_ROOM_ACTIVITY_PREFIX = "com.bilibili.bililive.";

    private PlaybackActivityScope() {
    }

    static boolean isVideoDetailActivity(String className) {
        return VIDEO_DETAIL_ACTIVITY.equals(className);
    }

    static boolean isVideoPlaylistActivity(String className) {
        return VIDEO_PLAYLIST_ACTIVITY.equals(className);
    }

    static boolean isVideoPlaybackActivity(String className) {
        return VIDEO_DETAIL_ACTIVITY.equals(className)
                || VIDEO_PLAYLIST_ACTIVITY.equals(className)
                || VIDEO_STORY_ACTIVITY.equals(className)
                || isLiveRoomActivity(className);
    }

    static boolean isLiveRoomActivity(String className) {
        return className != null
                && className.startsWith(LIVE_ROOM_ACTIVITY_PREFIX)
                && className.contains("LiveRoomActivity");
    }
}
