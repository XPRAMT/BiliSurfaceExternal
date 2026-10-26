package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class StoryRouteRewriterTest {
    @Test
    public void storyBecomesVideoAndKeepsIdentity() {
        assertEquals("bilibili://video/350713944?foo=1&page=2#part",
                StoryRouteRewriter.rewrite("bilibili://story/350713944?foo=1&-Arouter=story"
                        + "&-Atype=story&page=2#part"));
    }

    @Test
    public void storyWithSharedPlayerRemainsInStoryPlayer() {
        String url = "bilibili://story/350713944?cid=42&bundle_key_player_shared_id=991"
                + "&-Arouter=story";
        assertEquals(url, StoryRouteRewriter.rewrite(url));
    }

    @Test
    public void storyWithoutSharedPlayerStillRoutesToVideoDetail() {
        assertEquals("bilibili://video/350713944?cid=42&page=2",
                StoryRouteRewriter.rewrite("bilibili://story/350713944?cid=42&page=2"
                        + "&-Arouter=story"));
    }

    @Test
    public void rewrittenStoryDropsStoryPayloadAndPreservesVideoIdentity() {
        assertEquals("bilibili://video/117318592040583?aid=117318592040583&cid=42128312524"
                        + "&from_spmid=main.recommend.0.0",
                StoryRouteRewriter.rewrite("bilibili://story/117318592040583"
                        + "?aid=117318592040583&cid=42128312524&story_item=%7B%7D"
                        + "&player_preload=123&player_width=720&player_height=1280"
                        + "&player_rotate=0&report_flow_data=%7B%7D&jumpFrom=7"
                        + "&from_spmid=main.recommend.0.0"));
    }

    @Test
    public void storyPayloadKeysAreRemovedWhenPercentEncoded() {
        assertEquals("bilibili://video/350713944?cid=42",
                StoryRouteRewriter.rewrite("bilibili://story/350713944?cid=42"
                        + "&%73tory_item=%7B%7D&%6AumpFrom=7"));
    }

    @Test
    public void sharedPlayerStateIsPreservedOnNonStoryVideoRoute() {
        String url = "bilibili://video/350713944?bundle_key_player_shared_id=991"
                + "&player_share=1";
        assertEquals(url, StoryRouteRewriter.rewrite(url));
    }

    @Test
    public void markersAreRemovedFromWebVideoOnly() {
        assertEquals("https://www.bilibili.com/video/BV1PR4y1Y75W/?p=3",
                StoryRouteRewriter.rewrite("https://www.bilibili.com/video/BV1PR4y1Y75W/"
                        + "?p=3&-Arouter=story"));
        assertEquals("https://other.example/video/1?-Arouter=story",
                StoryRouteRewriter.rewrite("https://other.example/video/1?-Arouter=story"));
    }

    @Test
    public void nonStoryAndSecondPassAreUnchanged() {
        String url = "bilibili://story/350713944?x=1";
        assertEquals("bilibili://video/350713944?x=1", StoryRouteRewriter.rewrite(url));
        assertEquals(StoryRouteRewriter.rewrite(url),
                StoryRouteRewriter.rewrite(StoryRouteRewriter.rewrite(url)));
        assertEquals("bilibili://united_video/350713944?x=1",
                StoryRouteRewriter.rewrite("bilibili://united_video/350713944?x=1"));
        assertEquals("bilibili://video?aid=350713944",
                StoryRouteRewriter.rewrite("bilibili://story?aid=350713944"));
        assertEquals("bilibili://storyboard/1",
                StoryRouteRewriter.rewrite("bilibili://storyboard/1"));
    }
}
