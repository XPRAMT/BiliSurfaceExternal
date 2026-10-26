# Media3 影片幀釋放機制（原始碼對照）

本文件記錄 Media3 決定「何時、以什麼時間戳」把解碼後的影片幀交給 Surface 的完整邏輯，作為 Bilibili IJK 修復的參考。內容直接來自原始碼，不靠記憶；每一條都附檔案與行號。

- 原始碼：`https://github.com/androidx/media`
- 版本：tag **`1.8.1`**（commit `95ded8583289646a04488f2bf4fc5c8258f33289`）。選這個版本是因為 NX809J 上用來對照的 CX 檔案總管（`com.cxinventor.file.explorer`）內建 `AndroidXMedia3/1.8.1`。
- 路徑前綴：`libraries/exoplayer/src/main/java/androidx/media3/exoplayer/`
- 對照實測：CX 檔案總管播放 UFO Test 120／60，30 s Perfetto（trace 未收錄於儲存庫）

## 1. 整體流程

```
ExoPlayerImplInternal.doSomeWork()            每 10 ms 一次（固定排程）
  └ MediaCodecVideoRenderer.processOutputBuffer()   對「下一個」已解碼的輸出 buffer
       └ VideoFrameReleaseControl.getFrameReleaseAction()
            ├ earlyUs = (PTS − 播放位置) / 倍速 − 本輪迴圈已經過的時間
            ├ releaseTimeNs = VideoFrameReleaseHelper.adjustReleaseTime(now + earlyUs)
            │      ├ 固定幀率平滑：依上一幀的 releaseTime + 幀長 × 幀數差（偏差 ≤ 20 ms 才採用）
            │      └ 對齊最近的 VSync，再減 80% 週期
            ├ earlyUs 重新以 releaseTimeNs − now 計算
            ├ 太晚（< −30 ms）→ 丟幀；太早（> 50 ms）→ 這輪不處理，10 ms 後再問
            └ 其餘 → FRAME_RELEASE_SCHEDULED
       └ codec.releaseOutputBuffer(index, releaseTimeNs)   帶時間戳釋放
```

播放器**不在幀該顯示的那一刻**才釋放，而是在距離目標 VSync 50 ms 內的任一輪迴圈釋放，並把「目標 VSync − 80% 週期」寫進 buffer 時間戳，讓 SurfaceFlinger 扣住到那一格才上屏。

## 2. 決定何時釋放：`video/VideoFrameReleaseControl.java`

| 項目 | 程式 | 值 |
|---|---|---|
| 最早可釋放 | `MAX_EARLY_US_THRESHOLD`（L170），L411 `earlyUs > 50 ms → FRAME_RELEASE_TRY_AGAIN_LATER` | **50 ms** |
| earlyUs 計算 | `calculateEarlyTimeUs()`（L471–483） | `(PTS − positionUs) / playbackSpeed − (now − 本輪起點)` |
| 時間戳 | L397–400：`releaseTimeNs = helper.adjustReleaseTime(nanoTime + earlyUs×1000)`，`earlyUs = (releaseTimeNs − nanoTime)/1000` | 對齊後的 earlyUs 才拿去判斷丟幀／太早 |
| 丟幀 | L407 → `MediaCodecVideoRenderer.shouldDropOutputBuffer()`（L1962）：`earlyUs < MIN_EARLY_US_LATE_THRESHOLD` | **−30 ms**（L147） |
| 嚴重落後 | `shouldDropBuffersToKeyframe()`（L1977）：`earlyUs < −500 ms` → 丟到下一個關鍵幀 | −500 ms（L150） |
| 強制顯示 | `shouldForceRenderOutputBuffer()`（L1999）：晚超過 30 ms 且距上一次顯示 > 100 ms → 立刻以 `nanoTime()` 釋放 | 避免畫面凍住 |
| 第一幀 | `shouldForceRelease()`（L486–509）依 `firstFrameState` 立即釋放 | — |

渲染迴圈：`ExoPlayerImplInternal.scheduleNextWork()`（L1507）。動態排程是實驗功能、預設關閉，所以用 `getStaticSchedulingWakeUpIntervalMs()`＝`Renderer.DEFAULT_DURATION_TO_PROGRESS_US`＝**10 ms**（`Renderer.java` L155）。所以實際釋放時機落在目標前 40–50 ms 之間。

## 3. 決定時間戳：`video/VideoFrameReleaseHelper.java`

- **`adjustReleaseTime()`**（L251–280）
  1. 如果固定幀率估計器已同步（`FixedFrameRateEstimator.isSynced()`），候選時間＝`lastAdjustedReleaseTimeNs + 幀長 × (frameIndex − lastAdjustedFrameIndex) / 倍速`。和未調整值差 ≤ `MAX_ALLOWED_ADJUSTMENT_NS`（20 ms，L86）才採用，否則重設平滑（L260–264）。這讓時間戳照影片節奏等距排列，不受播放時鐘抖動影響。
  2. `closestVsync()`（L399–414）：以 `VSyncSampler.sampledVsyncTimeNs` 為錨、`vsyncDurationNs` 為週期，取**最近**的 VSync（不是 floor）。
  3. 回傳 `snapped − vsyncOffsetNs`，`vsyncOffsetNs = vsyncDurationNs × 80 / 100`（L93、L391）。也就是時間戳落在「上一個 VSync 之後 20%」，SurfaceFlinger 會在目標 VSync 顯示它。
- **VSync 週期來源**：`Display.getRefreshRate()`（L387–397，`DisplayListener` 更新）。是**顯示模式**的刷新率，不是 SurfaceFlinger 的 render rate。
- **VSync 錨點**：`VSyncSampler`（L487–580），獨立執行緒 `ExoPlayer:FrameReleaseChoreographer` 上的 `Choreographer.postFrameCallbackDelayed(500 ms)`，每 500 ms 取一次 `frameTimeNanos`。**沒有用 FrameTimeline／deadline。**
- **Surface 幀率提示**：`updateSurfacePlaybackFrameRate()`（L348–366）以 `Surface.setFrameRate(內容幀率 × 倍速, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` 告訴系統內容幀率；幀率估計穩定（≥ 5 s）時變動 ≥ 0.02 fps 才更新。
- **同一格兩幀**：`MediaCodecVideoRenderer.releaseFrame()`（L1887–1903），`releaseTimeNs` 與上一幀相同就直接 skip（內容幀率高於刷新率時）。

## 4. 釋放 API：`video/MediaCodecVideoRenderer.java`

- `processOutputBuffer()`（L1781–1861）：上面的決策結果分派。`FRAME_RELEASE_SCHEDULED` → `releaseFrame()` → `renderOutputBufferV21()`（L2158）→ **`codec.releaseOutputBuffer(index, releaseTimeNs)`**（L2161）。
- `FRAME_RELEASE_IMMEDIATELY` 也是帶時間戳，只是時間戳＝`nanoTime()`（L1838–1841）。
- 只有在套用影片特效時才走 `VideoSink`／`PlaybackVideoGraphWrapper`（L855–858）；一般播放走上面的直接路徑。

## 5. 對照 NX809J 實測（CX 檔案總管，Media3 1.8.1）

| | 120 fps | 60 fps |
|---|---|---|
| 30 s 缺口 | 8（拍頻下限） | 12 |
| SF `desiredPresentTime` | 每個 buffer 都有，`presentIn ≈ 4.4 ms` | 同 |
| Queue → Latch 中位數 | 18.7 ms | 39.9 ms |
| Queue → Present 中位數 | 36.0 ms | 49.4 ms |

Queue→Present 落在 36–50 ms，與「目標前 40–50 ms 釋放、時間戳＝目標 − 0.8 格」相符。SurfaceFlinger 依時間戳扣住 buffer，直到它該上屏的那一格。

## 6. 與 BiliSurfaceBridge 先前實作的差異

`Media3FrameSynchronizer.java`（2026-09 移植）只移植了第 3 節（時間戳計算）：VSync 取樣、80% 偏移、固定幀率估計。缺了第 2 節：

1. **沒有提前釋放。** 仍由 IJK 在「現在就該顯示」時呼叫 `releaseOutputBuffer`，再把時間戳換掉。目標 VSync 幾乎就在眼前，SurfaceFlinger 沒有空間扣住，時間戳的作用很小。Media3 是提前 40–50 ms 釋放。
2. 早期 PTS 時鐘從不重新同步，時間戳全部落在過去（等同不帶時間戳）。
3. 2026-10 誤判「SurfaceFlinger 不理時間戳」（搜尋了這版 SF 不會輸出的字串），因此把時間戳路徑關掉（`RELEASE_WITH_TIMESTAMP = false`）。Bilibili 的 buffer 在 SF 裡顯示 `presentIn -1019020992 ms`：時間戳是媒體 PTS（幾秒），等於「立刻顯示」。

## 7. 套用到 IJK 時要對應的點

| Media3 | IJK 對應 |
|---|---|
| 渲染迴圈每 10 ms 問一次「最早可釋放的幀」 | IJK 的 `video_refresh` 依 `frame_timer + compute_target_delay()` 醒來；以 native hook 讓它提前醒 |
| `earlyUs ≤ 50 ms` 就釋放 | 讓 IJK 在目標 VSync 前 3–5 格（≤ 50 ms）處理該幀 |
| 時間戳＝最近 VSync − 0.8 格 | Java hook 把 `releaseOutputBuffer(idx, true)` 改為 `releaseOutputBuffer(idx, ts)`，`ts` 由 PTS 時間線（固定幀率平滑）對齊 VSync 求出 |
| 晚 > 30 ms 丟幀、晚 > 30 ms 且 100 ms 沒顯示則強制顯示 | `is_need_drop_frame()` 的門檻 |
| 同一個時間戳的第二幀 skip | 內容幀率高於刷新率時的處理 |
| `Surface.setFrameRate(內容幀率, FIXED_SOURCE)` | 舊實驗曾因「每幀投票」造成刷新率亂跳；Media3 只在幀率估計變動時呼叫一次 |
| 外部渲染（GL 再畫一次） | Media3 沒有這條路；需把解碼 buffer 的時間戳（`SurfaceTexture.getTimestamp()`）以 `eglPresentationTimeANDROID` 帶到 GL 輸出 |
