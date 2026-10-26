package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Guards the activity scopes whose playback controls synchronize the stream overlay. */
public final class PlaybackActivityScopeTest {
    @Test
    public void includesEverySupportedPlaybackPage() {
        assertTrue(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity"));
        assertTrue(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.ship.theseus.playlist.UnitedPlaylistActivity"));
        assertTrue(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.video.story.StoryVideoActivity"));
        assertTrue(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.bililive.room.ui.roomv3.LiveRoomActivityV3"));
        assertFalse(PlaybackActivityScope.isVideoPlaybackActivity(null));
    }

    @Test
    public void recognisesKnownLiveRoomActivityVariants() {
        assertTrue(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.bililive.videoliveplayer.LiveRoomActivity"));
        assertTrue(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.bililive.videoliveplayer.LiveRoomActivityV2"));
    }

    @Test
    public void keepsUnrelatedActivitiesOutOfPlaybackScope() {
        assertFalse(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.bililive.videoliveplayer.LiveRoomSettingsActivity"));
        assertFalse(PlaybackActivityScope.isVideoPlaybackActivity(
                "com.bilibili.app.MainActivity"));
        assertFalse(PlaybackActivityScope.isVideoPlaybackActivity(null));
    }
}
