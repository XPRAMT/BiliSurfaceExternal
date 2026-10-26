[English](README.md) | [繁體中文](README_TW.md)

# Bili Surface Bridge

給官方 Bilibili Android App（`tv.danmaku.bili`）用的 LSPosed 模組。它修正 Bilibili 內建 IJK 播放器在高幀率點播時的微卡頓（在 Nubia NX809J 與小米平板 6 Pro 上已壓到物理下限），讓你選擇一般影片詳情頁播放器的渲染器、在畫面上顯示實際的影音格式，並可自動挑選最佳音軌。不修改官方 APK，全部在執行期 Hook。

這份 README 的後半是完整的根因分析與修復指引，也是寫給 Bilibili 播放器工程師（以及協助他們的 AI）看的：每個結論都來自 Bilibili Android 9.13.0 上的執行期實驗與 Perfetto trace，所有修正都可以一對一搬回 IJK 與播放器原始碼。

模組支援 Android 7.0／API 24 以上，透過 LSPosed 的舊式 `assets/xposed_init` 註冊 `dev.xpramt.bilisurfacebridge.HookEntry`。

## 設定

| 設定 | 作用 |
|---|---|
| **直式 Story 影片改用一般影片頁** | 把 `bilibili://story` 連結改道到一般影片路由，並移除 Story 專用的導向參數；不封鎖播放器內的滑動手勢。 |
| **影片詳情播放器 renderer**（`Texture`／`Surface external`／`Surface`） | 選擇一般影片詳情頁播放器使用 Bilibili 自己的哪一個渲染器。`Texture` 與 `Surface external` 保留 Bilibili 的 external render／transform 路徑（縮放、旋轉、截圖）；直接 `Surface` 跳過它。卡片、廣告、預載與內嵌播放器不受影響。**建議使用 `Surface external`**（理由見下文 2.2）。 |
| **播放 Debug 浮層** | 僅供診斷：顯示目前渲染器、IJK video-display callback 速率與其滾動 1% low，並開啟量測工具用的遙測與 trace counter。日常使用請關閉。 |
| **播放影音流浮層** | 在一般播放與直播間頂端顯示一行解碼器確認的視訊格式與所選音訊格式、位元率、取樣率（例如 `HEVC / Hi-Res 96KHz`）。 |
| **自動挑選最佳音質** | 初始播放時，在播放器提供獨立 DASH 音軌的情況下挑頻寬最高者；單一混流的來源維持 Bilibili 原本的選擇。 |
| **120 fps 幀同步**（預設開啟） | 照 Media3 的方式排程並帶時間戳釋放影片幀，涵蓋 0.5x／1x／2x 倍速，見下文。 |
| **高通解碼器輸出 fence**（預設開啟） | 設定高通影片解碼器時加入 QTI 軟體輸出 fence 的 vendor key，讓解碼完成的幀更早交出；只在解碼器宣告支援這些 key 時套用（Bilibili 9.13 的解碼器目前沒有宣告，因此實際不會套用）。 |

所有變更都要完整關閉再重新開啟 Bilibili 才生效。

## 安裝與驗證

模組 APK 在 [GitHub Releases](https://github.com/XPRAMT/BiliSurfaceExternal/releases)。**只支援 Bilibili 9.13.0**：原生 Hook 綁定這個版本的 IJK 函式庫（會先核對 build-id 與函式入口，不符就不掛鉤），其他版本只有部分 Java 功能可用。

### LSPosed（root）

1. 安裝模組 APK，並在 LSPosed 啟用它。
2. 作用域只勾選 Bilibili。
3. 開啟模組，選擇渲染器與要開的開關。
4. 強制停止後重新開啟 Bilibili，再播放一般影片。
5. 在 LSPosed log 搜尋 `BiliSurfaceBridge`；成功掛載時會出現選定的影片詳情 renderer，幀同步開啟時 IJK service 會記錄 `native release scheduling active` 與 `video-pictq-size default=min=12`。

### NPatch（免 root）

[NPatch](https://github.com/7723mod/NPatch) 把 Xposed 框架打包進 Bilibili 的 APK。請用**管理器模式**，模組設定才能在模組 App 裡調整（嵌入模式下模組只能用預設值）。以下以 NPatch 1.0.9 實測：

1. 取得 Bilibili **9.13.0** 的 APK。
2. 在電腦上修補（需要 Java 17 以上）：
   ```
   java -jar jar-v1.0.9-833-release.jar bilibili-9.13.0.apk --manager -l 2 -o out
   ```
   `-l 2` 是簽名繞過等級，Bilibili 需要它才能正常登入與播放。
3. 在裝置上安裝 NPatch 管理器（與修補用的版本相同）、本模組 APK，以及 `out/` 裡修補後的 Bilibili。修補版的簽名與官方版不同，必須先解除安裝官方版，登入狀態與離線快取會被清除。
4. 在 NPatch 管理器的「管理」頁點 Bilibili，勾選 **BiliSurfaceBridge**。
5. 完整關閉再開啟 Bilibili。在 `adb logcat` 搜尋 `BiliSurfaceBridge`：看到 `module preferences read from provider` 表示設定已讀到，播放時會出現 `native release scheduling active`。

注意事項：

- **小米／HyperOS 平板要關閉 Bilibili 的「平行視窗」**。開啟時 NPatch 會在啟動時出錯，模組不會載入。
- NPatch 下模組透過模組 App 提供的唯讀 ContentProvider 讀設定；Bilibili 剛啟動的一兩秒讀不到，那段時間使用預設值。大多數設定在下一支影片就會生效。
- 實測 NPatch 1.0.9 在 Sony G8142（Android 9）上會讓 Bilibili 啟動卡死，與本模組無關（不含模組也一樣）。

模組只會寫入 renderer 類別與非敏感的螢幕資訊到 Xposed log，不讀取帳號資料、Cookie、影片網址或播放授權值。

# 高幀率點播卡頓：根因分析與修復指引

一句話結論：**照 Media3（ExoPlayer）的方式釋放影片幀——提前約 30–45 ms、帶上目標上屏時間戳——並且把這個時間戳帶到真正送給 SurfaceFlinger 的那一個 buffer 上。** IJK 現在既不提前、也不帶時間戳，而且 Bilibili 的三種渲染模式在 MediaCodec 之後都還有一次 GL 重繪。照抄 Media3 時還有幾個 IJK 特有的坑（第 3 節），每一個都會讓結果在「完美」與「每秒好幾次停頓」之間隨機跳動。

實驗程式碼在本專案的 `app/src/main/cpp/native_refresh_cap.cpp` 與 `app/src/main/java/dev/xpramt/bilisurfacebridge/`，量測工具在 `tools/`。

- 測試機：Nubia NX809J（Snapdragon 8 Elite Gen 5，Android 16，120.285 Hz 面板，實測週期 8.3135 ms）
- 解碼器：`c2.qti.avc.decoder`（Bilibili 用的名字 `OMX.qcom.video.decoder.avc` 只是 `/vendor/etc/media_codecs.xml` 裡的別名，實際執行的是 Codec2）
- 內容：4K 120 fps AVC（畫質 120；Bilibili 只在 4K 提供 120 fps）與同系列 60 fps 版本；0.5x／1x／2x
  - 測試影片：**《屏幕測試 (UFO,4K)》**第 3 段「UFO Test 120」https://www.bilibili.com/video/BV1PR4y1Y75W/?p=3 （avid 350713944／cid 37386456210），第 4 段「UFO Test 60fps」?p=4。四排 UFO 分別以 120／60／30／15 fps 移動，重複幀一眼可見。
- 對照組：同一台手機上 Media3 1.8.1 播放器（CX 檔案總管內建，`AndroidXMedia3/1.8.1`）播同一支片的本地檔
- 量測：root Perfetto（SurfaceFlinger frame timeline + sched + gfx/view/video），固定 30 秒窗，以影片層的 latch／PresentFence 間隔計「缺幀」（> 1.5 格；一個缺幀＝畫面重複一幀）

## 1. 物理下限與結果

### 1.1 物理下限（拍頻）

內容幀率乘倍率，與面板刷新率不是整數比時，必然每隔一段時間多出或少一個 VSync。這是任何播放器都無法消除的下限：

| 內容 × 倍率 | 每秒內容幀 | 每幀應停格數 | 必然多停一格的頻率 | 每 30 秒 |
|---|---|---|---|---|
| 120 fps × 1 | 120 | 1 | 120.285 − 120 = 0.285 次/秒，每 3.5 s | 8.5 |
| 60 fps × 1 | 60 | 2 | 120.285 − 60 × 2 = 0.285 次/秒 | 8.5 |
| 60 fps × 0.5 | 30 | 4 | 120.285 − 30 × 4 = 0.285 次/秒（一幀停 5 格） | 8.5 |
| 120 fps × 0.5 | 60 | 2 | 120.285 − 60 × 2 = 0.285 次/秒 | 8.5 |
| 60 fps × 1.99（Bilibili 的「2.0X」實際送 1.99） | 119.4 | 1 | 120.285 − 119.4 ≈ 0.885 次/秒，每 1.13 s | 26.6 |
| 120 fps × 1.99 | 238.8 | 1（隔幀顯示） | 每個 VSync 都有新幀 | 0 |

判斷一個停格是不是物理的：它的間隔應該是 `1 / (刷新率 − 內容幀率 × 倍率)` 秒，而且非常規律。不規則、成對（相隔 < 0.1 s）的停格都不是物理的。

### 1.2 結果總表

**指標**：每 30 秒「畫面多停一格」的次數。某一幀停留的格數比它應有的多 n 格，就計 n 次（120 fps 每幀應停 1 格，60 fps 是 2 格，60 fps × 0.5 是 4 格）。這和物理下限用同一個單位，可以直接比較。測試片循環點的停頓（> 100 ms）及其後 3 秒不計。

**條件**：NX809J（120.285 Hz）、離線 4K、Surface external 模式（建議預設，見 2.2）。倍速測試的視窗不足 30 秒時，按比例換算成每 30 秒。

| 影片 | 倍速 | 每幀應停格數 | 物理下限 | 原樣 Bilibili | 修正後 | 量測次數（原樣／修正後） |
|---|---|---|---|---|---|---|
| 120 fps | 1x | 1 | 8.5 | 308、282 | **9、12、13** | 2／3 |
| 60 fps | 1x | 2 | 8.5 | 31 | **13** | 1／1 |
| 60 fps | 0.5x | 4 | 8.5 | 10 | **8.6、8.6** | 1／2 |
| 120 fps | 0.5x | 2 | 8.5 | 73.5 | **8.0** | 1／1 |
| 60 fps | 2x（實際 1.99） | 1 | 26.6 | 532 | **26.4** | 1／1 |
| 120 fps | 2x（實際 1.99） | 1（隔幀顯示） | 0 | 61 | **0、21** | 1／2 |

- 「原樣 Bilibili」是關閉模組幀同步、其餘相同的條件。
- 120 fps × 2 的 21 次集中在 0.1 秒內，疑為補償調整的暫態，另一次為 0。
- 60 fps × 0.5 原樣已接近下限（10）：每幀停 4 格時，IJK 自己的計時誤差很少跨過一格，所以這項修正的改善最小。差距最大的是 60 fps × 2（532 → 26.4）和 120 fps × 1（約 300 → 約 11）。

其他模式（1x、離線 4K120，以舊指標「間隔超過 1.5 格的次數」計，30 秒）：原樣 Texture 376、原樣直連 Surface 143；Media3 1.8.1 對照 8（60 fps 12）。直連 Surface 修正後曾量到 6（60 fps 2），但未依第 3 節的後續修正重測。

時間戳是否生效看 SurfaceFlinger trace：`latchBuffer <layer>` 之下的 `desiredPresentTime` → `presentIn X ms`。修正後為 +3–4 ms（Media3 為 +4.3 ms）；原樣 Bilibili 是 `presentIn -1042580352 ms`——時間戳是媒體 PTS（幾秒），等於「立刻顯示」。

### 1.3 小米平板 6 Pro（HyperOS）

- 裝置：小米平板 6 Pro（Snapdragon 8+ Gen 1，HyperOS／Android 15，面板 120.000 Hz），Bilibili 9.13.0 以 NPatch 1.0.9 管理器模式修補，離線 4K，Surface external。
- 量測：非 root Perfetto（`adb shell perfetto`），指標同 1.2。**HyperOS 播放影片時會依解碼器回報的影片幀率鎖定合成刷新率**（SurfaceFlinger counter `VTFps`），所以每格的長度以當下的合成刷新率計算。
- 面板正好 120 Hz，與 120 fps 內容沒有拍頻，物理下限為 0。

| 影片 | 倍速 | 合成刷新率 | 每 30 秒多停格數 | 每秒呈現 | 結果 |
|---|---|---|---|---|---|
| 60 fps | 1x | 60 Hz | **2** | 59.9 | 正常 |
| 60 fps | 0.5x | 60 Hz | **0** | 30.1 | 正常 |
| 60 fps | 2x（實際 1.99） | 被系統鎖在 60 Hz | — | 56–64 | 系統限制 |
| 120 fps | 1x | 120 Hz | **2** | 120.0 | 正常 |
| 120 fps | 0.5x | 60 Hz | **2** | 60.0 | 正常 |
| 120 fps | 2x（實際 1.99） | 120／60 Hz 切換 | — | 84.9 | 硬體限制 |

- **60 fps × 2**：HyperOS 只看影片本身的幀率，不看倍速，所以 119.4 fps 的內容仍只合成到 60 Hz。不跳過同格幀、改用同一時間戳交給 SurfaceFlinger，或以 `MediaCodec.setParameters` 告訴解碼器 `frame-rate`／`operating-rate` = 幀率 × 倍速，都沒有讓它改變。
- **120 fps × 2**：4K 在 2x 時每秒要解 238 幀，8+ Gen 1 的硬體解碼器只有約 186 幀。

## 2. 根因

### 2.1 IJK 不提前釋放、也不帶有效時間戳（最大宗）

Media3 1.8.1 的做法（原始碼對照見 [docs/media3-reference.md](docs/media3-reference.md)）：

1. 播放迴圈每 10 ms 跑一次；某一幀距離該上屏不到 **50 ms** 就交出（`VideoFrameReleaseControl.MAX_EARLY_US_THRESHOLD`），實際提前 40–50 ms。
2. 時間戳：`FixedFrameRateEstimator` 在**內容時間**（PTS）上估出固定幀長（容許 1 ms 誤差），調整時才除以倍率；平滑後偏差 ≤ 20 ms 才採用；對齊**最近的** VSync，再減 **80% 週期**（`VideoFrameReleaseHelper.adjustReleaseTime`）。
3. `releaseOutputBuffer(index, releaseTimeNs)`；SurfaceFlinger 依時間戳扣住 buffer 到該上屏的那一格。
4. 釋放時間落在前一幀已佔用的 VSync 上（內容比螢幕快）就跳過這一幀（`releaseOutputBuffer(index, false)`）。
5. 晚超過 30 ms 才丟幀；晚 30 ms 且 100 ms 沒畫面就強制顯示；晚 500 ms 丟到關鍵幀。

IJK 的 `video_refresh` 依 `frame_timer + compute_target_delay()` 醒來，**在「現在就該顯示」的那一刻**呼叫 `releaseOutputBuffer(idx, true)`。buffer 進 SurfaceFlinger 的相位因此由 IJK 的醒來時間決定；120.000 fps 對 120.285 Hz 時，這個相位每 ~3.5 s 掃過整個週期，接近 SF latch 邊界時 ±1 ms 抖動讓連續幀在「趕上／沒趕上」間反覆，形成成叢的兩槽缺幀：原樣直連 Surface 30 秒 143 個缺幀裡 136 個是「buffer 已 Queue、但在 latch 前被下一幀取代」。

### 2.2 三種模式在 MediaCodec 之後都還有一次 GL 重繪

```
Surface（「直連」）   MediaCodec ─▶ IJK 服務內的 SurfaceTexture ─GL(ijk_ff_vout, eglSwapBuffers)─▶ SurfaceView ─▶ SF
Surface external      MediaCodec ─▶ 主程序 SurfaceTexture ─GL(ijk_ext_render, EGL10 eglSwapBuffers)─▶ SurfaceView ─▶ SF
Texture（預設）       MediaCodec ─▶ 主程序 SurfaceTexture ─GL(ijk_ext_render)─▶ TextureView ─HWUI RenderThread─▶ App 視窗 ─▶ SF
```

`releaseOutputBuffer(idx, ts)` 的時間戳只掛在 MediaCodec 輸出的那個 buffer 上，SurfaceFlinger 看到的卻是後面 GL 重繪的輸出，所以時間戳要用 `eglPresentationTimeANDROID(display, surface, ts)` 掛在那次 `eglSwapBuffers` 上。Texture 模式最後送給 SF 的是 HWUI 合成的整個 App 視窗，沒有逐幀時間戳可掛（見 4.4）。**這是建議預設改用 Surface external 的主要理由**：保留 transform 與截圖，少一次 GPU 合成、少一格延遲，而且能完整套用 Media3 的時間戳模型。

### 2.3 圖片佇列只有三格（`video-pictq-size=3`）

提前釋放代表 IJK 手上要有好幾幀已解碼的畫面；硬解碼器每 5 s 也會停 16–30 ms（`queue_bak avpackets is full 350`）。三格佇列兩者都撐不住。MediaCodec 給了 15 個輸出 slot，IJK 只持有 3 個。改為 `video-pictq-size = 12`（AVOption 範圍 [3, 16]）。9.13 在 Java 設完之後 native 又會設回 3，而且**線上詳情頁根本不呼叫 `IjkMediaPlayer.setOption`**，要在 `ffp_create()` 的預設值處改，或依 fps 決定 `max(3, fps / 10)`。

### 2.4 管線執行緒全是 nice 0

`ijk_ff_vout`、`ijk_ff_videodec`、`ijk_ext_render` 都是預設優先權，常被 App 自己的 `DefaultDispatch`、GC、系統程序搶走 CPU 2–6 ms。三條執行緒 `setpriority(-10)`（介於 DISPLAY 與 AUDIO 之間，不跟音訊搶）。

## 3. 把 Media3 套到 IJK 時的陷阱（每一條都實測踩過）

Media3 本身沒有問題；下列問題全部來自「IJK 的結構和 Media3 不同」。修正前同一個版本、同一台手機，30 秒缺幀會在 11 到 921 之間隨機跳動，修正後穩定在拍頻下限。

### 3.1 提前量必須鎖在 IJK 的實際送出時間上

Media3 每一幀都在「距離上屏 40–50 ms」那一刻才送出，所以時間戳永遠領先實際送出約 45 ms。直接移植時，時間戳來自一條只在開播／重新對齊時錨定一次的 PTS 時間線，而 IJK 照它自己的刷新節奏送幀（我們排的「呼叫時間」在 IJK 醒來時通常已經過去）。結果「時間戳 − 實際送出」是每次開播隨機決定的常數：

- −25 ms：時間戳已過期，等於不帶時間戳；
- +11–17 ms：時間戳生效，但留給 GL 重繪的時間不夠，SF latch 時 buffer 的 acquire fence 還沒 signal（trace：`fence unsignaled <SurfaceView>`），同一格收到兩幀、丟一幀，最高每秒 28 次。
- +33–43 ms：只剩拍頻。

**修法**：每一幀量 `stamp − now`（在 release 時），連續 8 幀偏離目標（33 ms，取整到 VSync、至少 2 格）超過 0.75 格，就把交給 SurfaceFlinger 的時間戳整格挪動，前後兩個方向都可以（每次挪動付一次停格或丟幀）。**只挪時間戳，不挪 IJK 的呼叫時間**：挪呼叫時間會讓 IJK 跟著晚送，提前量追不上自己（單向版本推到上限仍然壞，就是這個原因）。

### 3.2 外部渲染器必須「幀一到就畫」

`IjkExternalRenderThread` 在 `onFrameAvailable` 才 `postFrameCallback`，等下一個 App VSync 才畫，而且 `updateTexImage()` 迴圈只保留最新一幀。codec 幀抵達主程序的相位 =（目標 VSync − 0.8 格 − 提前量）mod 週期；在 120 Hz、45 ms 提前時落在 App VSync 前 1–2 ms，callback 常趕不上那次 VSync，兩幀擠進下一次、其中一幀被丟。實測鎖在固定的 1,1,1,1,2 節奏（98 fps）。從 ijkservice 預測主程序 VSync 相位不可靠（試過，更差）。**修法**：`postFrameCallback` 改為立刻在 render 執行緒執行（`postAtFrontOfQueue`）；每一幀已帶時間戳，SF 自己會對齊。

### 3.3 `IjkExternalRenderThread` 有兩個 SurfaceTexture

`updateAllTexImage()` 每次會更新硬解（`mAmcSurfaceTexture`）與軟解（`mAvSurfaceTexture`）兩個 SurfaceTexture；閒置的那個帶著很舊的時間戳。取時間戳時必須只看承載影片的那一個：我們一開始對「超出合理範圍就清除待用時間戳」，閒置的那個偶爾在影片那個之後更新，那次 swap 就不帶時間戳、早一格顯示（每 30 秒 4–6 次停格）。

### 3.4 compute_target_delay 回傳 0 會讓 IJK 卡死

IJK 只在 `delay > 0 && time − frame_timer > AV_SYNC_THRESHOLD_MAX` 時把 `frame_timer` 重設到現在。排程回傳 0 時 IJK 不前進也不重設，之後每一幀的時間戳都落在過去、再回傳 0，播放變成解碼器速度自由跑（實測 ~100 fps，持續不恢復，由 0.5x 切回 1x 觸發）。**修法**：已經落後 100 ms 以上卻算出 0 時，回傳 1 µs。

### 3.5 時間線落後要重新對齊

PTS 時間線原本只在偏離現在 60 格（~500 ms）以上才重新對齊；某次開播卡在落後 200–370 ms，所有時間戳都過期，30 秒 921 個缺幀。**修法**：連續 8 幀落後實際刷新 50 ms 以上就重新對齊，並以現在為下限（frame_timer 的估計也可能一起落後）。Media3 不會遇到，因為它每一幀都從當下時鐘推算。

### 3.6 倍速

- **倍率要在播放器所在的程序取得**：Bilibili 的 IJK 跑在 `:ijkservice`，倍速經 AIDL 到 `services.IjkMediaPlayerClient.setPropertyFloat(10003 = FFP_PROP_FLOAT_PLAYBACK_RATE, rate)`；主程序的 `IjkMediaPlayer.setSpeed` 在那裡不會執行。「2.0X」實際送的是 1.99。
- **IJK 的倍速只靠音畫同步實現**：PTS 步長（`compute_target_delay` 的輸入）不會縮放，是 `compute_target_delay` 對倍速後音訊時鐘的修正讓影片變速。任何把延遲換成 PTS 步長的改動（包括「倍速時旁路但回傳 PTS 步長」）都會讓影片停在 1x、只有音訊變速。
- **倍速也要排程**：照 Media3 把 PTS 步長除以倍率照常排程；全交回 IJK 時，IJK 的延遲抖動且無時間戳，60 fps × 2 只剩 109 fps、8 秒跳 92 格。
- **內容比螢幕快時跳過同格的幀**（Media3 第 4 點）：120 fps × 2 每秒 238 幀，若全部渲染，SF 每格拿最新那幀，格數雖滿但內容步長忽大忽小。改成釋放時間落在已佔用 VSync 的幀 `releaseOutputBuffer(index, false)`。只適用 Surface external：直連 Surface 的 IJK GL 重繪在沒有新幀時會把舊幀不帶時間戳再送一次。
- **非 1x 的提前量補償要整條時間線一起挪**：4K120 × 2 時解碼器只能剛好趕上，送出貼著死線，需要把時間線往後挪（最多 4 格 ≈ 33 ms）。這裡和 3.1 相反，偏移必須同時作用在呼叫時間、同格判斷與時間戳上；只挪其中一部分會產生相同的時間戳與一長串沒跳過的幀（每秒渲染 172 幀、8 秒 112 次同時間戳）。

### 3.7 幀率估計要在內容時間上做，並取整到微秒

Bilibili 的 PTS 是毫秒精度：120 fps 的間隔是 8/9 ms 交替、60 fps 是 16/17 ms 交替，剛好落在 Media3 估計器 1 ms 容差的邊上。把除以倍率後的間隔餵給估計器，0.5x 時變成 32/34 ms、永遠不同步，只能用原始時間線對齊 → 每秒 1–2 次「3 格接 5 格」的抖動。即使在內容時間上估計，經過 `/1.99 × 1.99` 的換算也會多出幾 ns 超過容差，所以要取整到微秒。

### 3.8 對齊 VSync 要有遲滯

`closestVsync()` 用最新取樣的 VSync 時間當基準，它每格更新、帶微小抖動。拍頻跨越格線邊界時，平滑後的時間剛好在兩格中點附近，連續幾幀會在兩格之間來回翻：往後翻多一格（停格），往前翻被「同格往後推」規則推到下一格，結果一次跨越產生好幾次相隔 20–60 ms 的成對停格（60 fps × 2：8 秒 16 次，拍頻只有 7 次）。**修法**：要比「每幀正常格數」多跳一格時（120 fps 1 格、60 fps × 1 為 2 格、× 0.5 為 4 格），平滑後時間必須超過兩格中點 1.5 ms。注意要相對每幀正常格數，否則 60 fps × 1 的正常 2 格步長會被當成多跳。

### 3.9 丟幀規則

9.13 的 `is_need_drop_frame()` 幾乎每一幀都判定「晚了」而請求丟幀（它以音訊時鐘判斷，對幾 ms 的遲到就丟，曾連鎖丟掉 40%）。只在幀的時間戳已過去 30 ms 以上才放行（Media3 `MIN_EARLY_US_LATE_THRESHOLD`）；實測這個規則下 1x 實際丟幀為 0。不需要另外全面封鎖丟幀。非 1x 且內容比螢幕快、交回 IJK 時，恢復它原本的丟幀。

## 4. 修法

### 4.1 IJK 的釋放排程

在 `video_refresh`／`compute_target_delay()` 這一層做（我們用 GOT hook 改它的回傳值；搬回原始碼時直接改 refresh 迴圈）：

```c
// 每個 ffp 一份狀態。IJK 會為同一幀多次呼叫 compute_target_delay()：
// 只算「待定」值，等這一幀真的顯示（release）時才 commit。
speed      = (rate == 1.0) ? 1.0 : rate;
step       = vp_duration / speed;                         // 牆鐘上的幀長
unadjusted = committed_ideal + step;
if (!valid || far_off_now(unadjusted) || behind_now_by(50ms) for 8 frames)
    unadjusted = max(frame_timer + step, now);            // 重新對齊（3.5）

dur_content = estimator.synced ? estimator.duration : 0;  // 內容時間（3.7）
dur         = dur_content / speed;
n           = round(step / dur);
candidate   = last_adjusted + dur * n;
adjusted    = (dur > 0 && |unadjusted - candidate| <= 20ms) ? candidate : unadjusted;

slot  = closest_vsync(adjusted, vsync_anchor, vsync_period);
if (slot == last_slot + (frame_slots + 1) * period            // 多跳一格
    && adjusted < last_slot + frame_slots * period + period/2 + 1.5ms)
    slot = last_slot + frame_slots * period;                  // 遲滯（3.8）
release_ts = slot - 0.8 * period;
if (rate != 1.0) release_ts += offset_off1x;                  // 非 1x：整條時間線一起挪（3.6）

skip = (release_ts <= last_release_ts && content_faster_than_display);   // 3.6
if (!skip && release_ts <= last_release_ts) release_ts = last_release_ts + period;

call_time = release_ts - 45ms;
delay     = clamp(call_time - frame_timer, 0, step + period);
if (delay == 0 && now - frame_timer > 100ms) delay = 1us;     // 3.4

stamp = (rate == 1.0) ? release_ts + offset_1x : release_ts; // 1x：只挪時間戳（3.1）

// release 時：
if (skip) releaseOutputBuffer(idx, false);
else      releaseOutputBuffer(idx, stamp);       // 並把 stamp 交給該模式的 GL 重繪（4.2）

// commit（顯示後）：
estimator.on_frame(content_time += round_us(step * speed));  // 3.7
lead = stamp - now;
if (rate == 1.0) lock offset_1x toward 33ms, both ways, whole VSyncs (3.1)
else if (lead < period for 8 frames) offset_off1x += k * period, up to 4 VSyncs (3.6)
```

- `vsync_anchor`／`vsync_period`：Choreographer 的 frame time 與週期（我們每 VSync 取樣；Media3 是每 500 ms）。
- 9.13 的 `compute_target_delay()` A/V 判定不要拿來修正時間線：它曾連續 60 s 對 100% 的幀回傳雙倍延遲，與影片實際落後與否無關。
- 內容幀率高於刷新率、又不是 Surface external 時：旁路，交給 IJK 丟幀。

### 4.2 時間戳掛到 GL 輸出

- **Surface**：在 `ijk_ff_vout` 的 `eglSwapBuffers` 前 `eglPresentationTimeANDROID(dpy, surf, stamp)`（同一執行緒，release 後約 2.5 ms）。
- **Surface external**：MediaCodec 帶著時間戳釋放，時間戳隨 buffer 進到主程序的 SurfaceTexture。`IjkExternalRenderThread` 在 `updateTexImage()` 後以 `mAmcSurfaceTexture.getTimestamp()` 讀回（只看硬解那一個，3.3），在 `ijkWindowSurface.swapBuffers()` 前設上。`IjkEglCore` 用的是 **EGL10**（`javax.microedition.khronos.egl`），沒有 presentation time API；swap 當下該 surface 是 current，可用 `EGL14.eglGetCurrentDisplay()`／`eglGetCurrentSurface(EGL_DRAW)` 取得同一個原生物件呼叫 `EGLExt.eglPresentationTimeANDROID`。外部渲染器必須幀一到就畫（3.2）。

### 4.3 建議的修復順序

| 順序 | 改動 | 位置 |
|---|---|---|
| 1 | 預設渲染模式改 `TypeSurfaceViewWithExternalRender` | `VideoRenderLayerFactory` |
| 2 | `video-pictq-size` 依 fps 設（120 fps → 12） | `ff_ffplay_options.h` 預設值 |
| 3 | refresh 迴圈照 Media3 排程，含 3.1、3.4、3.5、3.7、3.8 | `ff_ffplay.c video_refresh` |
| 4 | 時間戳掛到 GL 輸出；外部渲染器幀一到就畫、只讀硬解 SurfaceTexture 的時間戳 | `ijk_ff_vout` 的 GL 重繪、`IjkExternalRenderThread` |
| 5 | 倍速：在 IJK 所在程序讀倍率、倍速也排程、同格跳過、非 1x 整條時間線補償（3.6） | `video_refresh`、release 路徑 |
| 6 | 丟幀門檻改為晚 30 ms | `is_need_drop_frame` |
| 7 | 三條管線執行緒 nice -10 | 執行緒建立處 |

### 4.4 Texture（目前的預設模式）的限制

HWUI 合成的視窗 buffer 沒有逐幀時間戳，SurfaceFlinger 無法替它對齊，只能靠呼叫時機：外部渲染器改為幀一到就畫、每個 UI VSync `invalidate()` TextureView（frame-available 訊息會輸給帶 VSync 時間戳的 Choreographer 訊息），再把 GL 輸出相位排在兩次 RenderThread `updateTexImage` 中間。120 fps 可到拍頻下限，但 60 fps 時 App 仍收 120 Hz VSync，相位對不準（30 秒 ~46 次 1/3 格交錯），目前沒有可靠的解法。

## 5. 走過的錯路（避免重蹈）

- 「SurfaceFlinger 不遵守 `desiredPresentTime`」是錯的：當時搜尋了這個 SF 版本不會輸出的字串，而且時間戳掛在 MediaCodec 的 buffer 上，根本沒到 SurfaceFlinger。
- 在此錯誤前提下發展的「精準控制呼叫時間」（提前 2.2／2.45 格、依 FrameTimeline deadline 推算、查詢 compositor timing、上屏回饋控制）在 120 fps 可以接近下限，但 60 fps 做不到，也無法跨裝置。`ANativeWindow` 的 `GET_COMPOSITOR_TIMING` 在這台回傳的值錯誤。
- Java 層扣住 `releaseOutputBuffer` 到正確時間再放：解碼器少一個 buffer、更卡。
- 縮小圖片佇列（2 格）無效；問題是太淺不是太深。
- 對 SurfaceView 的 SurfaceControl 投 120 Hz（`CHANGE_FRAME_RATE_ALWAYS`）：這台 SF 對 MRR 面板每幀重設投票、每秒切換顯示模式 ~88 次，實際只剩 87 Hz。
- 外部渲染器「每次重繪只取一幀、有積壓就補畫」：和壞結果高度相關（250 缺幀），撤回。問題在 3.1 的提前量，不在 drain。
- 把外部渲染的送出時間對齊到主程序 App VSync 中間：實際相位散成好幾群，更差。
- 用 `EGL_ANDROID_get_frame_timestamps` 的 RENDERING_COMPLETE 做回饋：它會跟著要求的 present time 一起移動，不能當「是否趕上」的訊號。
- 修正 frame_timer 的估計值：IJK 晚醒不是因為估計偏了，修了只會每次補一格。
- 把呼叫時間跟著時間戳一起往後挪（1x）：IJK 跟著晚送，提前量追不上（3.1）。
- 把高通解碼器輸出 fence 當成停頓原因：在 Bilibili 裡它從未套用（解碼器沒列出所需的參數），與停頓無關。
- 全面封鎖 IJK 丟幀：在 3.9 的規則下實際丟幀已是 0，封鎖沒有作用，只會讓量測和實際設定不一致。

## 6. 順手看到、建議一起修的問題

- 線上切畫質時偶發硬解退回軟解：1080p30 → 4K120 切換時新的 `c2.qti.avc.decoder` 解了 180 幀都 `releaseOutputBuffer(idx, false)` 追進度，隨後 `func_run_sync: fail ret:0 … hw_decode_fallback_enable:1`，整段改成軟解，30 秒 456 個缺幀。
- 建 MediaCodec 時 `frame-rate = 1`，應填實際 fps。
- 每次建解碼器都印 `SDL_AMediaFormatJava_getInt32: failed, name=stride / slice-height` 的 NPE。
- 主程序 `IjkMediaPlayer.setOption` 在線上詳情頁從不被呼叫，只有離線快取頁會。
- 服務程序對 SurfaceView 的 `Surface.setFrameRate()` 回 `-38 Function not implemented`（跨程序 producer），內容幀率提示到不了 SurfaceFlinger。
- 播放清單頁偶發 `BufferQueue has been abandoned` → 硬解啟動失敗退回軟解、黑屏。
- seek／影片循環後的恢復：循環點會有 0.5–0.7 s 的解碼停頓，1x 之後約 6 次停格即恢復；2x 要 2–4 秒（提前量掉到負值、出現同時間戳）。屬於「開始／結束」情況，本文件的量測排除在外，但 seek 會經過同一條路徑。

## 7. 量測方法

1. `adb root`，用 `tools/bili-frame-timeline.pbtxt` 錄 Perfetto（SF frame timeline + `sched` + `gfx`/`view`/`video`）。全螢幕、穩定 20–40 s 後取第一個完整影片 present 起 30 秒。
2. 影片層：Surface 模式找 `SurfaceView[tv.danmaku.bili/...]`；latch／`PresentFenceSignaled` 間隔 > 1.5 格計一個缺幀。Texture 模式改看 GL 執行緒每個 UI VSync 區間的 `queueBuffer` 數（`tools/TextureGlIntervals.py`）。
3. 時間戳是否生效：`latchBuffer <layer>` 之下的 `presentIn`。正值數毫秒＝有效；數十億毫秒的負值＝沒有時間戳；持續 −4 到 −5 ms＝時間戳已過期（3.1）。
4. **逐幀 counter**：在外部渲染器的 swap 上用 `android.os.Trace.setCounter` 寫出「時間戳 − 現在」與「與上一幀時間戳的差（格）」，再加上「被拒收的時間戳」與「沒帶時間戳的 swap」。停格對應到哪一種：時間戳差兩格且 IJK 送出間隔正常＝排程造成；沒帶時間戳＝時間戳遺失；同時間戳＝同格跳過失效。
5. 分辨物理與非物理停格：停格間隔是否為 `1 / (刷新率 − 內容幀率 × 倍率)` 且規律（1.1）。成對、相隔 < 0.1 s 的停格不是物理的。2x 時 IJK 不一定跟著排程的拍頻晚送一幀，用「IJK 送出間隔」判斷拍頻在 2x 不可靠。
6. 循環點：測試片 60 秒循環一次（2x 下每 30 秒），時間戳一次跳幾十格，之後幾秒的停格屬「開始／結束」，要排除或避開。
7. 波動很大：修正前同一版本可在 11 到 921 之間跳，修正後仍要每個情境至少跑兩到三次。倍速測試要從 log 確認倍率真的變了（Bilibili 會記住上次的倍速，點擊選單也可能沒生效）。


## 量測工具

`tools/RunBilibiliJankLoop.ps1` 是上述所有數字使用的無人值守量測迴圈：對模組套用量測設定、啟動 Bilibili（深連結或離線快取）、用 `tools/bili-frame-timeline.pbtxt` 錄 root Perfetto trace，再以 `tools/FrameTimelineAnalysis.py` 分析固定 30 秒窗（呈現幀數、缺幀、SurfaceFlinger buffer 取代、渲染器節奏）。需要 `adb` root 與裝有 `perfetto` 套件的 `python`。只連一台裝置時會自動選用它，否則以 `-Serial` 指定；測修正版要加 `-Media3FrameSyncEnabled $true`。

`tools/ExtraSlots.py` 計算 1.2 表的指標「每 30 秒多停格數」：`python tools/ExtraSlots.py <trace> <每幀應停格數>`（120 fps 1x 為 1，60 fps 1x 與 120 fps 0.5x 為 2，60 fps 0.5x 為 4；面板刷新率不是 120.285 Hz 時加 `--refresh-hz`）。

`tools/CaptureSurfaceLatency.ps1` 是較舊、較簡單的做法：記錄一個指定 Bilibili 影片 Surface 的 SurfaceFlinger 呈現時間戳。Android 15 系統若對舊式 `--latency` 介面只回傳 refresh period，請改用 Perfetto 路徑。

所有量測只包含時間與 layer 識別資訊，不包含影片網址、帳號資料、Cookie、授權值或影片像素。

## 授權

本專案以 [GPL-3.0](LICENSE) 授權。
