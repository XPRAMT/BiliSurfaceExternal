package dev.xpramt.bilisurfacebridge;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/** Small configuration screen; LSPosed scope remains the activation authority. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 20, 24);
    private static final int PRIMARY = Color.WHITE;
    private static final int SECONDARY = Color.rgb(125, 190, 220);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String automationRenderer = getIntent().getStringExtra("automation_profile");
        Boolean automationProbe = getIntent().hasExtra("render_cadence_probe")
                ? Boolean.valueOf(getIntent().getBooleanExtra("render_cadence_probe", true))
                : null;
        Boolean automationMedia3FrameSync = getIntent().hasExtra("media3_frame_sync")
                ? Boolean.valueOf(getIntent().getBooleanExtra("media3_frame_sync", false))
                : null;
        Boolean automationFrameQueueWaitRoundUp = getIntent().hasExtra("frame_queue_wait_round_up")
                ? Boolean.valueOf(getIntent().getBooleanExtra("frame_queue_wait_round_up", false))
                : null;
        Boolean automationBlockFrameDrop = getIntent().hasExtra("block_ijk_frame_drop")
                ? Boolean.valueOf(getIntent().getBooleanExtra("block_ijk_frame_drop", true))
                : null;
        Boolean automationBlockComputeTargetDelay =
                getIntent().hasExtra("block_compute_target_delay")
                ? Boolean.valueOf(getIntent().getBooleanExtra("block_compute_target_delay", true))
                : null;
        if (getIntent().hasExtra("replace_story_video")) {
            ModulePreferences.setReplaceStoryVideoEnabled(
                    this, getIntent().getBooleanExtra("replace_story_video", false));
        }
        Window window = getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        window.getDecorView().setSystemUiVisibility(0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(32));
        content.setBackgroundColor(BACKGROUND);
        scroll.addView(content);

        final int horizontalPadding = dp(20);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            content.setPadding(horizontalPadding,
                    insets.getSystemWindowInsetTop() + dp(20),
                    horizontalPadding,
                    insets.getSystemWindowInsetBottom() + dp(28));
            return insets.consumeSystemWindowInsets();
        });

        content.addView(text("Bili Surface Bridge", 25, PRIMARY));
        add(content, text("Bilibili 120 fps 播放修正與播放資訊浮層", 14, SECONDARY), 0, 5);
        add(content, text("作用域只勾選 Bilibili（tv.danmaku.bili）。本模組不讀取帳號、Cookie、影片網址或授權資料。", 15, Color.LTGRAY), 0, 22);

        addSection(content, "影片頁面", "變更頁面導向；與播放器渲染及音訊設定互不依賴。");
        Switch replaceStoryVideo = new Switch(this);
        replaceStoryVideo.setText("直式 Story 影片改用一般影片頁");
        replaceStoryVideo.setTextSize(16);
        replaceStoryVideo.setTextColor(PRIMARY);
        replaceStoryVideo.setChecked(ModulePreferences.isReplaceStoryVideoEnabled(this));
        replaceStoryVideo.setOnCheckedChangeListener((button, checked) ->
                ModulePreferences.setReplaceStoryVideoEnabled(MainActivity.this, checked));
        add(content, replaceStoryVideo, 0, 8);
        addSettingNote(content, "改寫 Story 路由並移除 Story 專用導向參數；不封鎖滑動手勢。切換後需完整關閉再開啟 Bilibili。", Color.LTGRAY);

        add(content, text("影片詳情播放器 renderer", 17, PRIMARY), 0, 18);
        add(content, text("此選擇只套用一般影片詳情頁；卡片、廣告與內嵌播放器保持 Bilibili 原設定。切換後需完整關閉再開啟 Bilibili。", 14, Color.LTGRAY), 0, 5);

        Spinner rendererSpinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ModulePreferences.RendererMode[] modes = ModulePreferences.RendererMode.values();
        String[] labels = new String[modes.length];
        for (int index = 0; index < modes.length; index++) {
            labels[index] = modes[index].label;
        }
        ArrayAdapter<String> rendererAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return rendererRow(super.getView(position, convertView, parent), false);
            }

            @Override
            public View getDropDownView(int position, View convertView,
                                        android.view.ViewGroup parent) {
                return rendererRow(super.getDropDownView(position, convertView, parent), true);
            }
        };
        rendererSpinner.setAdapter(rendererAdapter);
        ModulePreferences.RendererMode currentMode =
                ModulePreferences.getVideoDetailRendererMode(this);
        rendererSpinner.setSelection(currentMode.ordinal(), false);
        final boolean automationProfileActive = automationRenderer != null;
        rendererSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!automationProfileActive) {
                    ModulePreferences.setVideoDetailRendererMode(
                            MainActivity.this, ModulePreferences.RendererMode.values()[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A renderer is always selected.
            }
        });
        add(content, rendererSpinner, 0, 8);
        add(content, text("Texture：TextureView + external render，可保留 transform，但可能增加合成負擔。\nSurface external：SurfaceView + Bilibili external render，可保留 transform。\nSurface：直接 SurfaceView，跳過 external render；縮放、旋轉、平移與截圖等 transform 功能不可用。", 14, Color.LTGRAY), 0, 8);

        // Apply automation after the Spinner has been restored and its listener attached. Android
        // may restore a stale selection during onCreate; doing the final write here prevents that
        // UI restoration from silently changing the profile immediately before the runner reads it.
        if (automationRenderer != null) {
            ModulePreferences.applyAutomationProfile(this,
                    automationRenderer, automationProbe,
                    automationMedia3FrameSync,
                    automationFrameQueueWaitRoundUp, automationBlockFrameDrop,
                    automationBlockComputeTargetDelay);
            ModulePreferences.RendererMode appliedMode =
                    ModulePreferences.getVideoDetailRendererMode(this);
            rendererSpinner.setSelection(appliedMode.ordinal(), false);
        }

        addSection(content, "播放資訊浮層", "只影響一般影片詳情頁；兩個浮層彼此獨立。");

        Switch debugOverlay = new Switch(this);
        debugOverlay.setText("播放 Debug 浮層");
        debugOverlay.setTextSize(16);
        debugOverlay.setTextColor(PRIMARY);
        debugOverlay.setChecked(ModulePreferences.isPlaybackDebugOverlayEnabled(this));
        debugOverlay.setOnCheckedChangeListener((button, checked) ->
                ModulePreferences.setPlaybackDebugOverlayEnabled(MainActivity.this, checked));
        add(content, debugOverlay, 0, 8);
        addSettingNote(content, "顯示 renderer 與 IJK video-display callback 的實際送幀 fps；不是螢幕 VSync。", Color.LTGRAY);

        Switch streamOverlay = new Switch(this);
        streamOverlay.setText("播放影音流浮層");
        streamOverlay.setTextSize(16);
        streamOverlay.setTextColor(PRIMARY);
        streamOverlay.setChecked(ModulePreferences.isPlaybackStreamOverlayEnabled(this));
        streamOverlay.setOnCheckedChangeListener((button, checked) ->
                ModulePreferences.setPlaybackStreamOverlayEnabled(MainActivity.this, checked));
        add(content, streamOverlay, 0, 12);
        addSettingNote(content, "在畫面頂端顯示一行目前影音流，例如「hevc AAC 200k」。關閉只會隱藏浮層。", Color.LTGRAY);

        Switch autoSelectBestAudio = new Switch(this);
        autoSelectBestAudio.setText("自動挑選最佳音質");
        autoSelectBestAudio.setTextSize(16);
        autoSelectBestAudio.setTextColor(PRIMARY);
        autoSelectBestAudio.setChecked(ModulePreferences.isAutoSelectBestAudioEnabled(this));
        autoSelectBestAudio.setOnCheckedChangeListener((button, checked) ->
                ModulePreferences.setAutoSelectBestAudioEnabled(MainActivity.this, checked));
        add(content, autoSelectBestAudio, 0, 12);
        addSettingNote(content, "開啟時，初始播放會在一般 DASH 音軌中挑選頻寬最高者；關閉時完整保留 Bilibili 原始預設音軌。切換後須完整關閉再開啟 Bilibili。", Color.LTGRAY);

        addSection(content, "120 fps 播放", "NX809J 的 120 fps 點播微卡頓修正；切換後須完整關閉再開啟 Bilibili。");

        Switch frameSync = new Switch(this);
        frameSync.setText("120 fps 幀同步");
        frameSync.setTextSize(16);
        frameSync.setTextColor(PRIMARY);
        frameSync.setChecked(ModulePreferences.isMedia3FrameSyncEnabled(this));
        frameSync.setOnCheckedChangeListener((button, checked) ->
                ModulePreferences.setMedia3FrameSyncEnabled(MainActivity.this, checked));
        add(content, frameSync, 0, 8);
        addSettingNote(content, "把 IJK 的每一幀釋放對齊螢幕 VSync 格線（native 排程）、把解碼圖片佇列加深到 12 格吸收解碼器停頓，並提高影片管線執行緒的優先權。開啟後 4K120 只剩面板 120.285 Hz 與 120.000 fps 來源之間每 3.5 秒一次的必然重複幀。三種渲染模式都支援。", Color.LTGRAY);

        Switch qtiOutputFence = new Switch(this);
        qtiOutputFence.setText("高通解碼器輸出 fence");
        qtiOutputFence.setTextSize(16);
        qtiOutputFence.setTextColor(PRIMARY);
        qtiOutputFence.setChecked(ModulePreferences.isQtiOutputFenceEnabled(this));
        qtiOutputFence.setOnCheckedChangeListener((button, checked) ->
                ModulePreferences.setQtiOutputFenceEnabled(MainActivity.this, checked));
        add(content, qtiOutputFence, 0, 12);
        addSettingNote(content, "只對 Bilibili IJK service 的 c2.qti／OMX.qcom 影片解碼器，且僅在解碼器明確宣告支援的 vendor key 存在時，於 configure 加入軟體輸出 fence。Moonlight 等串流 App 的必備設定，可壓低約 5 ms 解碼延遲（超過 120 fps 的半格）。", Color.LTGRAY);

        setContentView(scroll);
    }

    private View rendererRow(View row, boolean dropdown) {
        if (row instanceof TextView) {
            TextView text = (TextView) row;
            text.setTextColor(PRIMARY);
            text.setTextSize(16);
            text.setPadding(dp(12), dp(dropdown ? 12 : 8), dp(12), dp(dropdown ? 12 : 8));
            text.setBackgroundColor(dropdown ? Color.rgb(35, 42, 49) : Color.rgb(27, 33, 39));
        }
        return row;
    }

    private void addSection(LinearLayout parent, String title, String summary) {
        add(parent, text(title, 18, PRIMARY), 0, 24);
        add(parent, text(summary, 14, SECONDARY), 0, 3);
    }

    private void addSettingNote(LinearLayout parent, String note, int color) {
        add(parent, text(note, 14, color), 0, 2);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        view.setLineSpacing(dp(3), 1.0f);
        return view;
    }

    private void add(LinearLayout parent, TextView child, int left, int top) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        parameters.setMargins(dp(left), dp(top), 0, 0);
        parent.addView(child, parameters);
    }

    private void add(LinearLayout parent, Switch child, int left, int top) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        parameters.setMargins(dp(left), dp(top), 0, 0);
        parent.addView(child, parameters);
    }

    private void add(LinearLayout parent, View child, int left, int top) {
        LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        parameters.setMargins(dp(left), dp(top), 0, 0);
        parent.addView(child, parameters);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
