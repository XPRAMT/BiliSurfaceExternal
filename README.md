[English](README.md) | [繁體中文](README_TW.md)

# Bili Surface Bridge

An LSPosed module for the official Bilibili Android app (`tv.danmaku.bili`). It fixes the micro-stutter of Bilibili's built-in IJK player during high-frame-rate on-demand playback (down to the physical floor on a Nubia NX809J and a Xiaomi Pad 6 Pro), lets you pick the renderer of the normal video-detail player, shows the real video/audio format on screen, and can pick the best audio track automatically. Nothing in the official APK is modified; everything is hooked at runtime.

The second half of this README is the full root-cause analysis and fix guide, written for Bilibili's player engineers (and the AI helping them): every conclusion comes from runtime experiments and Perfetto traces on Bilibili Android 9.13.0, and every fix maps one-to-one back to IJK and the player source.

The module supports Android 7.0 / API 24 and later and registers `dev.xpramt.bilisurfacebridge.HookEntry` through LSPosed's legacy `assets/xposed_init` entry.

## Settings

| Setting | What it does |
|---|---|
| **Open Story videos in the normal video page** | Redirects `bilibili://story` links to the ordinary video route and removes the Story-only route flags. Swipe gestures inside a player are not blocked. |
| **Video-detail renderer** (`Texture` / `Surface external` / `Surface`) | Chooses which of Bilibili's own renderers the normal video-detail player uses. `Texture` and `Surface external` keep Bilibili's external-render/transform path (scale, rotate, capture); direct `Surface` skips it. Cards, ads, preloads and embedded players are unchanged. **`Surface external` is recommended** (see 2.2 below). |
| **Playback debug overlay** | Diagnostic only: shows the active renderer, the IJK video-display callback rate and its rolling 1 % low, and enables the telemetry and trace counters used by the measurement tools. Leave it off for normal use. |
| **Playback stream overlay** | Shows one line with the decoder-confirmed video format and the selected audio format, bitrate and sample rate (for example `HEVC / Hi-Res 96KHz`) in normal playback and live rooms. |
| **Automatically select best audio** | On first playback picks the highest-bandwidth separate DASH audio track when the player exposes one; muxed single streams stay under Bilibili's own selection. |
| **120 fps frame sync** (default on) | Schedules and timestamps video frames the way Media3 does, at 0.5x / 1x / 2x speed. Described below. |
| **Qualcomm decoder output fence** (default on) | Adds the QTI software output-fence vendor keys when configuring a Qualcomm video decoder, so decoded frames are handed out earlier. Only applied when the decoder declares those keys (Bilibili 9.13's decoder currently does not, so it is not applied there). |

Every change takes effect after Bilibili is fully closed and reopened.

## Install and verify

The module APK is on [GitHub Releases](https://github.com/XPRAMT/BiliSurfaceExternal/releases). **Only Bilibili 9.13.0 is supported**: the native hooks are bound to that version's IJK library (they check its build-id and entry instructions first and do not hook on a mismatch); on other versions only some Java features work.

### LSPosed (root)

1. Install the module APK and enable it in LSPosed.
2. Set its scope exclusively to Bilibili.
3. Open the module, choose the renderer and switches you want.
4. Force-stop and reopen Bilibili, then start a normal video.
5. Search the LSPosed log for `BiliSurfaceBridge`. Successful attachment reports the selected video-detail renderer; with frame sync on, the IJK service logs `native release scheduling active` and `video-pictq-size default=min=12`.

### NPatch (no root)

[NPatch](https://github.com/7723mod/NPatch) packs the Xposed framework into Bilibili's APK. Use **manager mode** so the settings can be changed in the module app (in embedded mode the module only uses its defaults). Tested with NPatch 1.0.9:

1. Get the Bilibili **9.13.0** APK.
2. Patch it on a computer (Java 17 or later):
   ```
   java -jar jar-v1.0.9-833-release.jar bilibili-9.13.0.apk --manager -l 2 -o out
   ```
   `-l 2` is the signature-bypass level Bilibili needs to sign in and play normally.
3. On the device install the NPatch manager (same version as the patcher), this module's APK and the patched Bilibili from `out/`. The patched app is signed differently from the official one, so the official app must be uninstalled first; its sign-in and offline cache are lost.
4. In the NPatch manager's Manage page, tap Bilibili and enable **BiliSurfaceBridge**.
5. Fully close and reopen Bilibili. Search `adb logcat` for `BiliSurfaceBridge`: `module preferences read from provider` means the settings were read, and playback logs `native release scheduling active`.

Notes:

- **On Xiaomi / HyperOS tablets, turn off parallel windows for Bilibili.** With it on, NPatch fails during startup and the module is not loaded.
- Under NPatch the module reads its settings through a read-only ContentProvider in the module app; during the first second or two after Bilibili starts it cannot, and the defaults apply. Most settings take effect from the next video.
- NPatch 1.0.9 hangs Bilibili at startup on a Sony G8142 (Android 9), with or without this module.

The module writes only renderer class and non-sensitive display facts to the Xposed log. It never reads account data, cookies, media URLs, or playback authorization values.

# High-frame-rate playback stutter: root cause and fix guide

In one sentence: **release video frames the way Media3 (ExoPlayer) does — about 30–45 ms early, carrying the target presentation timestamp — and carry that timestamp to the buffer that actually reaches SurfaceFlinger.** IJK today neither releases early nor sets a timestamp, and all three of Bilibili's renderers redraw the frame with GL after MediaCodec. Copying Media3 onto IJK has several IJK-specific traps (section 3); each of them makes results jump at random between "perfect" and "several stalls per second".

The experiment code is in `app/src/main/cpp/native_refresh_cap.cpp` and `app/src/main/java/dev/xpramt/bilisurfacebridge/`; the measurement tools are in `tools/`.

- Test device: Nubia NX809J (Snapdragon 8 Elite Gen 5, Android 16, 120.285 Hz panel, measured period 8.3135 ms)
- Decoder: `c2.qti.avc.decoder` (the name Bilibili uses, `OMX.qcom.video.decoder.avc`, is only an alias in `/vendor/etc/media_codecs.xml`; Codec2 is what runs)
- Content: 4K 120 fps AVC (quality 120; Bilibili only offers 120 fps at 4K) and the 60 fps part of the same series; 0.5x / 1x / 2x
  - Test video: **《屏幕测试 (UFO,4K)》** part 3 "UFO Test 120" https://www.bilibili.com/video/BV1PR4y1Y75W/?p=3 (avid 350713944 / cid 37386456210), part 4 "UFO Test 60fps" ?p=4. Four rows of UFOs move at 120 / 60 / 30 / 15 fps, so repeated frames are visible at a glance.
- Reference: the Media3 1.8.1 player on the same phone (built into CX File Explorer, `AndroidXMedia3/1.8.1`) playing a local copy of the same clip
- Measurement: rooted Perfetto (SurfaceFlinger frame timeline + sched + gfx/view/video), fixed 30-second window; a "missed frame" is a latch / PresentFence interval of the video layer longer than 1.5 periods (one missed frame = one repeated frame on screen)

## 1. Physical floor and results

### 1.1 Physical floor (beat)

When content frame rate times speed is not an integer ratio of the panel refresh rate, a VSync is necessarily gained or lost every so often. No player can remove this floor:

| Content × speed | Content frames/s | Slots per frame | Unavoidable extra slot | per 30 s |
|---|---|---|---|---|
| 120 fps × 1 | 120 | 1 | 120.285 − 120 = 0.285 per s, every 3.5 s | 8.5 |
| 60 fps × 1 | 60 | 2 | 120.285 − 60 × 2 = 0.285 per s | 8.5 |
| 60 fps × 0.5 | 30 | 4 | 120.285 − 30 × 4 = 0.285 per s (one frame stays 5 slots) | 8.5 |
| 120 fps × 0.5 | 60 | 2 | 120.285 − 60 × 2 = 0.285 per s | 8.5 |
| 60 fps × 1.99 (Bilibili's "2.0X" actually sends 1.99) | 119.4 | 1 | 120.285 − 119.4 ≈ 0.885 per s, every 1.13 s | 26.6 |
| 120 fps × 1.99 | 238.8 | 1 (every other frame shown) | a new frame on every VSync | 0 |

To tell whether a stall is physical: its spacing should be `1 / (refresh rate − content fps × speed)` seconds and very regular. Irregular stalls, or pairs less than 0.1 s apart, are not physical.

### 1.2 Results

**Metric**: "extra slots" per 30 s — every time a frame stays on screen n slots longer than it should, count n (a 120 fps frame should stay 1 slot, a 60 fps frame 2, a 60 fps frame at 0.5x 4). This is the same unit as the physical floor, so the two compare directly. The clip's loop-point stall (> 100 ms) and the 3 s after it are excluded.

**Conditions**: NX809J (120.285 Hz), offline 4K, Surface external renderer (recommended default, see 2.2). Speed tests with windows shorter than 30 s are scaled to 30 s.

| Video | Speed | Slots per frame | Physical floor | Stock Bilibili | Fixed | Runs (stock / fixed) |
|---|---|---|---|---|---|---|
| 120 fps | 1x | 1 | 8.5 | 308, 282 | **9, 12, 13** | 2 / 3 |
| 60 fps | 1x | 2 | 8.5 | 31 | **13** | 1 / 1 |
| 60 fps | 0.5x | 4 | 8.5 | 10 | **8.6, 8.6** | 1 / 2 |
| 120 fps | 0.5x | 2 | 8.5 | 73.5 | **8.0** | 1 / 1 |
| 60 fps | 2x (actually 1.99) | 1 | 26.6 | 532 | **26.4** | 1 / 1 |
| 120 fps | 2x (actually 1.99) | 1 (every other frame shown) | 0 | 61 | **0, 21** | 1 / 2 |

- "Stock Bilibili" is the same setup with the module's frame sync turned off.
- The 21 at 120 fps × 2 fall within 0.1 s, likely a compensation transient; the other run was 0.
- Stock 60 fps × 0.5 is already close to the floor (10): with 4 slots per frame IJK's own timing error rarely crosses a slot, so the fix gains least there. The largest gains are 60 fps × 2 (532 → 26.4) and 120 fps × 1 (~300 → ~11).

Other renderers (1x, offline 4K120, counted with the older metric "intervals longer than 1.5 slots", 30 s): stock Texture 376, stock direct Surface 143; Media3 1.8.1 reference 8 (60 fps 12). Direct Surface measured 6 (60 fps 2) after the fix, but has not been re-measured with the later fixes in section 3.

Whether a timestamp takes effect is visible in the SurfaceFlinger trace: `latchBuffer <layer>` → `desiredPresentTime` → `presentIn X ms`. After the fix it reads +3–4 ms (Media3: +4.3 ms); stock Bilibili reads `presentIn -1042580352 ms` — the timestamp is the media PTS (seconds), which means "show now".

### 1.3 Xiaomi Pad 6 Pro (HyperOS)

- Device: Xiaomi Pad 6 Pro (Snapdragon 8+ Gen 1, HyperOS / Android 15, 120.000 Hz panel); Bilibili 9.13.0 patched with NPatch 1.0.9 in manager mode; offline 4K; Surface external.
- Measurement: non-root Perfetto (`adb shell perfetto`), same metric as 1.2. **During video playback HyperOS locks the composition rate to the frame rate the decoder reports** (SurfaceFlinger counter `VTFps`), so a slot is measured at the composition rate in effect.
- The panel is exactly 120 Hz: no beat against 120 fps content, so the physical floor is 0.

| Video | Speed | Composition rate | Extra slots per 30 s | Shown per s | Result |
|---|---|---|---|---|---|
| 60 fps | 1x | 60 Hz | **2** | 59.9 | OK |
| 60 fps | 0.5x | 60 Hz | **0** | 30.1 | OK |
| 60 fps | 2x (actually 1.99) | locked at 60 Hz by the system | — | 56–64 | System limit |
| 120 fps | 1x | 120 Hz | **2** | 120.0 | OK |
| 120 fps | 0.5x | 60 Hz | **2** | 60.0 | OK |
| 120 fps | 2x (actually 1.99) | switching 120 / 60 Hz | — | 84.9 | Hardware limit |

- **60 fps × 2**: HyperOS looks only at the video's own frame rate, not at the speed, so 119.4 fps of content is still composed at 60 Hz. Neither releasing same-VSync frames with a shared timestamp instead of skipping them, nor telling the decoder `frame-rate` / `operating-rate` = fps × speed through `MediaCodec.setParameters`, changed it.
- **120 fps × 2**: 4K at 2x needs 238 decoded frames per second; the 8+ Gen 1 hardware decoder delivers about 186.

## 2. Root causes

### 2.1 IJK neither releases early nor sets a valid timestamp (the main cause)

What Media3 1.8.1 does (source walk-through in [docs/media3-reference.md](docs/media3-reference.md)):

1. The playback loop runs every 10 ms; a frame is released once it is less than **50 ms** from its presentation time (`VideoFrameReleaseControl.MAX_EARLY_US_THRESHOLD`), so in practice 40–50 ms early.
2. Timestamp: `FixedFrameRateEstimator` estimates a fixed frame duration on **content time** (PTS, 1 ms tolerance) and divides by the playback speed only when adjusting; the smoothed time is used only if it is within 20 ms; it is snapped to the **closest** VSync minus **80 % of a period** (`VideoFrameReleaseHelper.adjustReleaseTime`).
3. `releaseOutputBuffer(index, releaseTimeNs)`; SurfaceFlinger holds the buffer until its slot.
4. A frame whose release time falls on a VSync already taken by the previous frame (content faster than the display) is skipped (`releaseOutputBuffer(index, false)`).
5. Frames are dropped only when more than 30 ms late; forced out when 30 ms late and nothing was shown for 100 ms; dropped to the next keyframe when 500 ms late.

IJK's `video_refresh` wakes at `frame_timer + compute_target_delay()` and calls `releaseOutputBuffer(idx, true)` **at the moment the frame should be shown**. The phase at which the buffer reaches SurfaceFlinger is therefore set by IJK's wake-up; at 120.000 fps on 120.285 Hz that phase sweeps through a whole period every ~3.5 s, and near the latch boundary ±1 ms of jitter flips consecutive frames between "made it" and "missed it", producing bursts of two-slot gaps: 136 of the 143 missed frames of stock direct Surface in 30 s were "buffer queued but replaced by the next one before latch".

### 2.2 All three renderers redraw with GL after MediaCodec

```
Surface ("direct")    MediaCodec ─▶ SurfaceTexture in the IJK service ─GL(ijk_ff_vout, eglSwapBuffers)─▶ SurfaceView ─▶ SF
Surface external      MediaCodec ─▶ main-process SurfaceTexture ─GL(ijk_ext_render, EGL10 eglSwapBuffers)─▶ SurfaceView ─▶ SF
Texture (default)     MediaCodec ─▶ main-process SurfaceTexture ─GL(ijk_ext_render)─▶ TextureView ─HWUI RenderThread─▶ app window ─▶ SF
```

The timestamp of `releaseOutputBuffer(idx, ts)` only sits on MediaCodec's output buffer, while SurfaceFlinger sees the output of the GL redraw that follows, so the timestamp has to be set on that `eglSwapBuffers` with `eglPresentationTimeANDROID(display, surface, ts)`. In Texture mode what SurfaceFlinger receives is the whole app window composed by HWUI, which has no per-frame timestamp (see 4.4). **This is the main reason to make Surface external the default**: it keeps transform and capture, saves one GPU composition and one frame of latency, and can use Media3's timestamp model completely.

### 2.3 A three-frame picture queue (`video-pictq-size=3`)

Releasing early means IJK must already hold several decoded frames, and the hardware decoder also stalls for 16–30 ms every 5 s (`queue_bak avpackets is full 350`). Three slots absorb neither. MediaCodec offers 15 output slots; IJK holds only 3. Set `video-pictq-size = 12` (AVOption range [3, 16]). 9.13 sets it back to 3 in native code after Java sets it, and **the online detail page never calls `IjkMediaPlayer.setOption`**, so change the default in `ffp_create()`, or use `max(3, fps / 10)`.

### 2.4 The pipeline threads run at nice 0

`ijk_ff_vout`, `ijk_ff_videodec` and `ijk_ext_render` run at default priority and regularly lose the CPU for 2–6 ms to the app's own `DefaultDispatch`, GC and system processes. Set all three to `setpriority(-10)` (between DISPLAY and AUDIO, so they do not compete with audio).

## 3. Traps when applying Media3 to IJK (each one hit in measurement)

Media3 itself is fine; every item below comes from "IJK is structured differently from Media3". Before these fixes the same build on the same phone jumped at random between 11 and 921 missed frames per 30 s; after them it stays at the beat floor.

### 3.1 The release lead must be locked to IJK's actual release

Media3 releases every frame at the moment it is 40–50 ms from presentation, so the timestamp always leads the actual release by about 45 ms. In a direct port the timestamps come from a PTS timeline anchored only once at start / resync, while IJK releases on its own refresh cadence (the "call time" we schedule is usually already past when IJK wakes). The result is that "timestamp − actual release" is a constant picked at random at each start:

- −25 ms: the timestamp has already expired, the same as no timestamp;
- +11–17 ms: the timestamp works but leaves too little time for the GL redraw; at latch the buffer's acquire fence has not signalled yet (trace: `fence unsignaled <SurfaceView>`), so one VSync receives two frames and drops one, up to 28 times per second;
- +33–43 ms: only the beat remains.

**Fix**: measure `stamp − now` for every frame at release; when it stays more than 0.75 period away from the target (33 ms, rounded to VSyncs, at least 2) for 8 frames, move the timestamp handed to SurfaceFlinger by whole VSyncs, in either direction (each move costs one held or dropped frame). **Move only the timestamp, not IJK's call time**: moving the call time makes IJK release later with it, and the lead chases itself (that is why a one-way version stayed bad at its cap).

### 3.2 The external renderer must draw as soon as a frame arrives

`IjkExternalRenderThread` calls `postFrameCallback` from `onFrameAvailable` and draws on the next app VSync, and its `updateTexImage()` loop keeps only the newest frame. The phase at which a codec frame reaches the main process is (target VSync − 0.8 period − lead) mod period; at 120 Hz with a 45 ms lead it falls 1–2 ms before an app VSync, the callback often misses that VSync, and two frames meet in the next one, so one is dropped. Measured: locked to a fixed 1,1,1,1,2 latch pattern (98 fps). Predicting the main process's VSync phase from the IJK service is not reliable (tried, worse). **Fix**: run the frame callback immediately on the render thread (`postAtFrontOfQueue`); every frame already carries its timestamp and SurfaceFlinger aligns it.

### 3.3 `IjkExternalRenderThread` has two SurfaceTextures

`updateAllTexImage()` updates both the hardware-decoder (`mAmcSurfaceTexture`) and software-decoder (`mAvSurfaceTexture`) SurfaceTextures on every draw; the idle one carries a very old timestamp. Take the timestamp only from the one that carries the video: at first we cleared the pending timestamp whenever a value was out of range, and when the idle texture was updated after the video one, that swap went out without a timestamp and was shown one VSync early (4–6 stalls per 30 s).

### 3.4 A zero delay from compute_target_delay locks IJK up

IJK resets `frame_timer` to the current time only when `delay > 0 && time − frame_timer > AV_SYNC_THRESHOLD_MAX`. When the scheduler returns 0, IJK neither advances nor resets, every following timestamp lands in the past, 0 is returned again, and playback runs free at decoder speed (measured ~100 fps without recovering, triggered by switching from 0.5x back to 1x). **Fix**: return 1 µs instead of 0 when the frame timer is already more than 100 ms behind.

### 3.5 Resync a timeline that falls behind

The PTS timeline used to resync only when it was more than 60 periods (~500 ms) from now; one start left it 200–370 ms behind for good, every timestamp expired, and 30 s had 921 missed frames. **Fix**: resync after 8 consecutive frames more than 50 ms behind the actual refresh, with the current time as the lower bound (the frame-timer estimate may lag too). Media3 cannot get there because it derives every release time from the current clock.

### 3.6 Playback speed

- **Read the rate in the process that runs the player**: Bilibili's IJK runs in `:ijkservice`; speed arrives over AIDL at `services.IjkMediaPlayerClient.setPropertyFloat(10003 = FFP_PROP_FLOAT_PLAYBACK_RATE, rate)`. The main process's `IjkMediaPlayer.setSpeed` never runs there. "2.0X" actually sends 1.99.
- **IJK implements speed only through A/V sync**: the PTS step (the input of `compute_target_delay`) is not scaled; it is `compute_target_delay`'s correction against the rate-scaled audio clock that changes the video speed. Any change that replaces the delay with the PTS step (including "bypass at other speeds but return the PTS step") keeps the video at 1x while only audio changes speed.
- **Schedule at other speeds too**: divide the PTS step by the rate and schedule as usual, as Media3 does; handing everything back to IJK leaves jittery, untimed delays — 60 fps × 2 dropped to 109 fps with 92 skipped slots in 8 s.
- **Skip frames that land on a taken VSync when content is faster than the display** (Media3 item 4): at 120 fps × 2 there are 238 frames per second; rendering all of them lets SurfaceFlinger take the newest at each VSync, so every slot is filled but the content step alternates unevenly. Release frames whose release time falls on an already-taken VSync with `releaseOutputBuffer(index, false)`. Only for Surface external: when there is no new frame, the direct-Surface GL redraw in IJK sends the old frame again without a timestamp.
- **Off 1x, move the whole timeline for lead compensation**: at 4K120 × 2 the decoder keeps up just in time and releases run against the deadline, so the timeline has to move later (up to 4 VSyncs ≈ 33 ms). Opposite to 3.1, the offset here must apply to the call time, the same-VSync check and the timestamp together; moving only part of it produced equal timestamps and long runs of unskipped frames (172 renders/s, 112 equal timestamps in 8 s).

### 3.7 Estimate the frame rate on content time, rounded to microseconds

Bilibili's PTS have millisecond precision: 120 fps alternates 8/9 ms and 60 fps 16/17 ms, right at the edge of the Media3 estimator's 1 ms tolerance. Feeding it rate-divided durations turns them into 32/34 ms at 0.5x, so it never syncs and frames are snapped from the raw timeline → one or two "3 slots then 5 slots" jitters per second. Even on content time, the `/1.99 × 1.99` round trip comes back a few ns over the tolerance, so round to whole microseconds.

### 3.8 Add hysteresis to the VSync snap

`closestVsync()` snaps against the latest sampled VSync, which updates every period with a little jitter. When the beat crosses a slot boundary, the smoothed time sits right at the midpoint of two slots and consecutive frames flip between them: a flip later adds a slot (a stall), a flip back is pushed to the next slot by the "same VSync → next slot" rule, so one crossing produces several stalls 20–60 ms apart (60 fps × 2: 16 in 8 s against a beat of 7). **Fix**: to take one slot more than a frame normally takes (1 at 120 fps, 2 for 60 fps × 1, 4 for × 0.5), the smoothed time must be 1.5 ms past the midpoint. It must be relative to the slots per frame, otherwise the normal 2-slot step of 60 fps × 1 is treated as an extra slot.

### 3.9 Drop rule

9.13's `is_need_drop_frame()` judges almost every frame "late" and asks to drop it (it judges against the audio clock and drops on a few ms of lateness; it once dropped 40 % in a chain reaction). Let a drop through only when the frame's timestamp is more than 30 ms in the past (Media3 `MIN_EARLY_US_LATE_THRESHOLD`); under this rule no frames were actually dropped at 1x. Blocking drops entirely is not needed. Off 1x, when content is faster than the display and handed back to IJK, restore its own drops.

## 4. Fixes

### 4.1 IJK release scheduling

Done at the `video_refresh` / `compute_target_delay()` layer (we change its return value through a GOT hook; in the source, change the refresh loop directly):

```c
// One state per ffp. IJK calls compute_target_delay() several times for the same frame:
// compute "pending" values only, and commit when the frame is actually released.
speed      = (rate == 1.0) ? 1.0 : rate;
step       = vp_duration / speed;                         // frame duration on the wall clock
unadjusted = committed_ideal + step;
if (!valid || far_off_now(unadjusted) || behind_now_by(50ms) for 8 frames)
    unadjusted = max(frame_timer + step, now);            // resync (3.5)

dur_content = estimator.synced ? estimator.duration : 0;  // content time (3.7)
dur         = dur_content / speed;
n           = round(step / dur);
candidate   = last_adjusted + dur * n;
adjusted    = (dur > 0 && |unadjusted - candidate| <= 20ms) ? candidate : unadjusted;

slot  = closest_vsync(adjusted, vsync_anchor, vsync_period);
if (slot == last_slot + (frame_slots + 1) * period            // one slot too many
    && adjusted < last_slot + frame_slots * period + period/2 + 1.5ms)
    slot = last_slot + frame_slots * period;                  // hysteresis (3.8)
release_ts = slot - 0.8 * period;
if (rate != 1.0) release_ts += offset_off1x;                  // off 1x: whole timeline moves (3.6)

skip = (release_ts <= last_release_ts && content_faster_than_display);   // 3.6
if (!skip && release_ts <= last_release_ts) release_ts = last_release_ts + period;

call_time = release_ts - 45ms;
delay     = clamp(call_time - frame_timer, 0, step + period);
if (delay == 0 && now - frame_timer > 100ms) delay = 1us;     // 3.4

stamp = (rate == 1.0) ? release_ts + offset_1x : release_ts; // 1x: only the stamp moves (3.1)

// at release:
if (skip) releaseOutputBuffer(idx, false);
else      releaseOutputBuffer(idx, stamp);       // and hand stamp to the renderer's GL redraw (4.2)

// commit (after release):
estimator.on_frame(content_time += round_us(step * speed));  // 3.7
lead = stamp - now;
if (rate == 1.0) lock offset_1x toward 33ms, both ways, whole VSyncs (3.1)
else if (lead < period for 8 frames) offset_off1x += k * period, up to 4 VSyncs (3.6)
```

- `vsync_anchor` / `vsync_period`: Choreographer frame time and period (we sample every VSync; Media3 every 500 ms).
- Do not use 9.13's `compute_target_delay()` A/V verdict to correct the timeline: it once returned a doubled delay for 100 % of frames for 60 s straight, unrelated to whether the video was actually behind.
- Content faster than the display on a renderer other than Surface external: bypass and let IJK drop frames.

### 4.2 Put the timestamp on the GL output

- **Surface**: `eglPresentationTimeANDROID(dpy, surf, stamp)` before `ijk_ff_vout`'s `eglSwapBuffers` (same thread, about 2.5 ms after the release).
- **Surface external**: MediaCodec releases with the timestamp, which travels with the buffer into the main process's SurfaceTexture. `IjkExternalRenderThread` reads it back with `mAmcSurfaceTexture.getTimestamp()` after `updateTexImage()` (from the hardware-decoder texture only, 3.3) and sets it before `ijkWindowSurface.swapBuffers()`. `IjkEglCore` uses **EGL10** (`javax.microedition.khronos.egl`), which has no presentation-time API; the surface is current at the swap, so `EGL14.eglGetCurrentDisplay()` / `eglGetCurrentSurface(EGL_DRAW)` give the same native objects for `EGLExt.eglPresentationTimeANDROID`. The external renderer must draw on arrival (3.2).

### 4.3 Suggested order

| Order | Change | Where |
|---|---|---|
| 1 | Make `TypeSurfaceViewWithExternalRender` the default renderer | `VideoRenderLayerFactory` |
| 2 | Set `video-pictq-size` by fps (120 fps → 12) | default in `ff_ffplay_options.h` |
| 3 | Media3-style scheduling in the refresh loop, including 3.1, 3.4, 3.5, 3.7, 3.8 | `ff_ffplay.c video_refresh` |
| 4 | Timestamp on the GL output; external renderer draws on arrival and reads the timestamp from the hardware-decoder SurfaceTexture only | `ijk_ff_vout` GL redraw, `IjkExternalRenderThread` |
| 5 | Speed: read the rate in the player's process, schedule at other speeds, skip same-VSync frames, move the whole timeline off 1x (3.6) | `video_refresh`, release path |
| 6 | Drop only when 30 ms late | `is_need_drop_frame` |
| 7 | nice -10 for the three pipeline threads | where the threads are created |

### 4.4 Limits of Texture (the current default)

The HWUI-composed window buffer has no per-frame timestamp, so SurfaceFlinger cannot align it and only call timing is left: the external renderer draws on arrival, the TextureView is invalidated on every UI VSync (the frame-available message loses to Choreographer messages that carry a VSync timestamp), and the GL output phase is placed between two RenderThread `updateTexImage` calls. 120 fps reaches the beat floor, but at 60 fps the app still receives 120 Hz VSync and the phase cannot be aligned (~46 one-third-slot alternations per 30 s); there is no reliable fix yet.

## 5. Dead ends (do not repeat)

- "SurfaceFlinger ignores `desiredPresentTime`" was wrong: we searched for a string this SF version never prints, and the timestamp sat on the MediaCodec buffer, which never reached SurfaceFlinger.
- "Precise call timing" built on that wrong premise (calling 2.2 / 2.45 periods early, deriving from the FrameTimeline deadline, querying compositor timing, present-feedback control) came close at 120 fps but could not do 60 fps or work across devices. `ANativeWindow`'s `GET_COMPOSITOR_TIMING` returned wrong values on this device.
- Holding `releaseOutputBuffer` in Java until the right time: the decoder loses a buffer and stutters more.
- A smaller picture queue (2): no effect; the queue is too shallow, not too deep.
- Voting 120 Hz on the SurfaceView's SurfaceControl (`CHANGE_FRAME_RATE_ALWAYS`): this SF resets the vote every frame on the MRR panel and switches display modes ~88 times per second; it ends up at 87 Hz.
- External renderer "acquire one frame per draw, draw again when backlogged": strongly correlated with bad runs (250 missed frames), reverted. The problem was the lead in 3.1, not the drain.
- Aligning the external release with the middle of two main-process app VSyncs: the actual phase scattered into several clusters, worse.
- Using RENDERING_COMPLETE from `EGL_ANDROID_get_frame_timestamps` as feedback: it moves together with the requested present time, so it cannot tell whether a frame made it.
- Correcting the frame-timer estimate: IJK did not wake late because the estimate was off; correcting it only added one slot each time.
- Moving the call time together with the timestamp at 1x: IJK releases later with it and the lead never catches up (3.1).
- Blaming the Qualcomm decoder output fence: in Bilibili it is never applied (the decoder does not declare the required keys), so it is unrelated.
- Blocking IJK frame drops entirely: under the rule in 3.9 actual drops are already 0, so blocking does nothing except make measurement differ from real settings.

## 6. Other issues seen along the way

- Occasional fallback from hardware to software decoding when switching quality online: on 1080p30 → 4K120 the new `c2.qti.avc.decoder` decoded 180 frames with `releaseOutputBuffer(idx, false)` to catch up, then `func_run_sync: fail ret:0 … hw_decode_fallback_enable:1`, and the whole segment fell back to software decoding (456 missed frames in 30 s).
- MediaCodec is created with `frame-rate = 1`; it should be the real fps.
- Every decoder creation prints an NPE `SDL_AMediaFormatJava_getInt32: failed, name=stride / slice-height`.
- The main process's `IjkMediaPlayer.setOption` is never called on the online detail page, only on the offline-cache page.
- `Surface.setFrameRate()` from the service process on the SurfaceView returns `-38 Function not implemented` (cross-process producer), so the content frame-rate hint never reaches SurfaceFlinger.
- The playlist page occasionally gets `BufferQueue has been abandoned` → hardware decoder fails to start, falls back to software, black screen.
- Recovery after seek / clip loop: the loop point has a 0.5–0.7 s decoder stall; at 1x it recovers after about 6 stalls, at 2x it takes 2–4 s (the lead drops below zero and equal timestamps appear). This is a "start / end" case and excluded from the measurements here, but seeks take the same path.

## 7. Measurement method

1. `adb root`; record Perfetto with `tools/bili-frame-timeline.pbtxt` (SF frame timeline + `sched` + `gfx`/`view`/`video`). Go fullscreen, let it settle for 20–40 s, then take 30 s from the first complete video present.
2. Video layer: in Surface modes find `SurfaceView[tv.danmaku.bili/...]`; a latch / `PresentFenceSignaled` interval over 1.5 periods counts as a missed frame. In Texture mode count `queueBuffer` per UI VSync interval on the GL thread instead (`tools/TextureGlIntervals.py`).
3. Whether the timestamp works: `presentIn` under `latchBuffer <layer>`. A few positive ms = working; a negative value of billions of ms = no timestamp; a steady −4 to −5 ms = the timestamp has expired (3.1).
4. **Per-frame counters**: on the external renderer's swap, write "timestamp − now" and "step from the previous timestamp (slots)" with `android.os.Trace.setCounter`, plus "rejected timestamp" and "swap without timestamp". Map each stall: a two-slot timestamp step with a normal IJK release interval = caused by the scheduler; no timestamp = timestamp lost; equal timestamps = same-VSync skip failed.
5. Physical versus non-physical stalls: is the spacing `1 / (refresh rate − content fps × speed)` and regular (1.1)? Paired stalls less than 0.1 s apart are not physical. At 2x IJK does not always release one frame later on a scheduler beat, so "IJK release interval" is not a reliable beat test at 2x.
6. Loop point: the test clip loops every 60 s (every 30 s at 2x); the timestamp jumps by dozens of slots and the next few seconds of stalls are a "start / end" case to exclude or avoid.
7. Variance is large: before the fixes the same build jumped between 11 and 921; after them, still run every case two or three times. For speed tests, confirm from the log that the rate actually changed (Bilibili remembers the last speed, and a tap on the menu may not register).

## Measurement tools

`tools/RunBilibiliJankLoop.ps1` is the unattended measurement loop used for every number above: it applies a measurement profile to the module, launches Bilibili (deep link or offline cache), records a rooted Perfetto trace with `tools/bili-frame-timeline.pbtxt`, and analyses a fixed 30-second window with `tools/FrameTimelineAnalysis.py` (presented frames, missed frames, SurfaceFlinger buffer replacement, renderer cadence). It needs `adb` root access and a `python` with the `perfetto` package. With one device connected it is picked automatically; otherwise pass `-Serial`. Add `-Media3FrameSyncEnabled $true` to measure the fixed path.

`tools/ExtraSlots.py` computes the metric of table 1.2, extra slots per 30 s: `python tools/ExtraSlots.py <trace> <slots per frame>` (1 for 120 fps at 1x, 2 for 60 fps at 1x and 120 fps at 0.5x, 4 for 60 fps at 0.5x; add `--refresh-hz` when the panel is not 120.285 Hz).

`tools/CaptureSurfaceLatency.ps1` is the older, simpler capture of SurfaceFlinger presentation timestamps for one explicitly selected Bilibili video Surface. On Android 15 builds that return only the refresh period from the legacy `--latency` interface, use the Perfetto path instead.

All captures contain timing and layer identifiers only, not media URLs, account data, cookies, authorization values, or video pixels.

## License

This project is licensed under [GPL-3.0](LICENSE).
