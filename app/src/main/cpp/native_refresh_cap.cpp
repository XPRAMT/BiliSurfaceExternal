#include <android/log.h>
#include <android/trace.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <cerrno>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <time.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <cerrno>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <climits>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr char kTag[] = "BiliSurfaceBridge";
constexpr size_t kRefreshLoopInstructionCount = 4;
constexpr uint32_t kRefreshLoopRegister = 23;
constexpr std::array<uintptr_t, kRefreshLoopInstructionCount> kRefreshLoopInstructionOffsets = {
        0x3edc0, 0x3edd4, 0x3ede8, 0x3edf8};
constexpr std::array<uint32_t, kRefreshLoopInstructionCount> kExpected10msInstructions = {
        0xd2828f77, 0xf2a8f5d7, 0xf2cf5c37, 0xf2e7f097};
constexpr uintptr_t kVpDurationOffset = 0x42168;
constexpr uintptr_t kQueuePictureOffset = 0x4bd28;
constexpr uintptr_t kGetMasterClockOffset = 0x47964;
constexpr size_t kVpDurationPrologueBytes = 16;
constexpr std::array<uint32_t, kRefreshLoopInstructionCount> kExpectedVpDurationPrologue = {
        0xfc1e0fe8, 0xf90007fe, 0xa9014ff4, 0x2f00e408};
constexpr std::array<uint32_t, kRefreshLoopInstructionCount> kExpectedQueuePicturePrologue = {
        0x6db923e9, 0xa9017bfd, 0xa9026ffc, 0xa90367fa};
// Bilibili 9.13.0 arm64 libijkplayer.so, GNU build-id d64b356c9dfa1ce7235aa13186d71c50.
// This profile deliberately installs GOT-only hooks; none of the 9.5.0 structure offsets
// or inline trampolines may be used with it.
constexpr std::array<uint8_t, 16> kIjk913BuildId = {
        0xd6, 0x4b, 0x35, 0x6c, 0x9d, 0xfa, 0x1c, 0xe7,
        0x23, 0x5a, 0xa1, 0x31, 0x86, 0xd7, 0x1c, 0x50};
constexpr std::array<uint32_t, 4> kIjk913ComputePrologue = {
        0xfc1b0fec, 0x6d012beb, 0x6d0223e9, 0xa90357fe};
constexpr std::array<uint32_t, 4> kIjk913WaitPrologue = {
        0xf81e0ffe, 0xa9014ff4, 0x340000e2, 0xb9470808};
constexpr std::array<uint32_t, 4> kIjk913DropPrologue = {
        0xfc1b0fea, 0x6d00a3e9, 0xf9000ffe, 0xa9025ff8};
constexpr uint32_t kArm64LdrX17Literal8 = 0x58000051;
constexpr uint32_t kArm64BranchX17 = 0xd61f0220;
constexpr size_t kFrameSerialOffset = 0x28;
constexpr size_t kFramePtsOffset = 0x30;
constexpr size_t kFrameDurationOffset = 0x38;
constexpr size_t kMaxFrameDurationOffset = 0x102650;
constexpr size_t kVideoStreamOffset = 0x1023b8;
constexpr size_t kFrameLastFilterDelayOffset = 0x1023a8;
constexpr size_t kAvStreamTimeBaseNumeratorOffset = 0x20;
constexpr size_t kAvStreamTimeBaseDenominatorOffset = 0x24;
constexpr double kEarlyDropGraceSeconds = 1.0 / 120.0;
constexpr uint64_t kLongFrameIntervalNanos = 16'667'000ULL;
constexpr uint64_t kVeryLongFrameIntervalNanos = 33'334'000ULL;
constexpr uint64_t kNominal120FrameIntervalNanos = 8'333'333ULL;
constexpr uint64_t kSmallStutter10Nanos = 10'000'000ULL;
constexpr uint64_t kSmallStutter85Nanos = 11'765'000ULL;
constexpr uint64_t kSmallStutter80Nanos = 12'500'000ULL;
constexpr uint64_t kAbnormalDisplayIntervalNanos = 100'000'000ULL;
constexpr uint64_t kAnomalyReportIntervalNanos = 1'000'000'000ULL;
constexpr char kTelemetryBuild[] = "0.9.12-native-codec-release-probe";
constexpr size_t kRawSampleCapacity = 32'768;
constexpr size_t kIjkEarlyUsSampleCapacity = 4'096;
constexpr int64_t kIjkEarlyUsPtsToleranceUs = 2'000;

enum class RawSampleKind : uint32_t {
    kComputeTarget = 1,
    kFrameQueueWait = 2,
    kDisplayInterval = 3,
    kDisplayCall = 4,
    kMasterClock = 5,
    kIjkEarlyUs = 6,
    kQueuePicture = 7,
    kDisplayPicture = 8,
    kVpDuration = 9,
    kVpDurationContext = 10,
    kSdlDelay = 11,
    kSdlCondWait = 12,
    kMediaCodecRelease = 13,
    kNativeSchedule = 14,
    kNativeDisplayNote = 15,
};

struct RawSample {
    uint64_t timestampNanos;
    RawSampleKind kind;
    uint64_t valueNanos;
    uint64_t auxiliaryNanos;
};

struct IjkEarlyUsSample {
    uint64_t timestampNanos;
    int64_t ptsUs;
    int64_t earlyUsNanos;
    uint64_t filterDelayNanos;
};

std::mutex gPatchMutex;
std::mutex gProducerHookMutex;
std::mutex gRawSampleMutex;
std::mutex gIjkEarlyUsMutex;
std::array<RawSample, kRawSampleCapacity> gRawSampleRing{};
std::array<IjkEarlyUsSample, kIjkEarlyUsSampleCapacity> gIjkEarlyUsRing{};
size_t gRawSampleStart = 0;
size_t gRawSampleCount = 0;
size_t gIjkEarlyUsStart = 0;
size_t gIjkEarlyUsCount = 0;
std::atomic<bool> gRawSampleWriterStarted = false;
std::atomic<bool> gIjk913GotOnlyProfile = false;
std::atomic<uint64_t> gSuccessfulEglSwaps = 0;
std::atomic<bool> gProducerSamplerStarted = false;
std::atomic<int64_t> gLatestQueuePicturePtsNanos = -1;
std::atomic<int> gLatestQueuePictureSerial = -1;
std::atomic<uint64_t> gLatestQueuePictureTimestampNanos = 0;
std::atomic<uint64_t> gLastDisplayedQueuePictureTimestampNanos = 0;
std::atomic<uint64_t> gNativeDisplayTraceToken = 0;

// This telemetry is deliberately observational.  The wrappers forward every argument and
// return the original result unchanged; their purpose is to distinguish IJK's scheduling
// decision from decoder and SurfaceFlinger timing already available in Perfetto.
std::mutex gRefreshTelemetryHookMutex;
std::atomic<bool> gRefreshTelemetryStarted = false;
std::atomic<uint64_t> gTargetDelayCalls = 0;
std::atomic<uint64_t> gInputTargetDelayNanos = 0;
std::atomic<uint64_t> gTargetDelayNanos = 0;
std::atomic<uint64_t> gAdjustedTargetDelays = 0;
std::atomic<uint64_t> gDoubledTargetDelays = 0;
std::atomic<uint64_t> gLongTargetDelays = 0;
std::atomic<uint64_t> gVeryLongTargetDelays = 0;
std::atomic<uint64_t> gTargetDelays10ms = 0;
std::atomic<uint64_t> gTargetDelays85fps = 0;
std::atomic<uint64_t> gTargetDelaysOverNominal = 0;
std::atomic<uint64_t> gMaxTargetDelayNanos = 0;
std::atomic<uint64_t> gDisplayCalls = 0;
std::atomic<uint64_t> gWaitCalls = 0;
std::atomic<uint64_t> gRequestedWaitNanos = 0;
std::atomic<uint64_t> gActualWaitNanos = 0;
std::atomic<uint64_t> gWaitOverrunNanos = 0;
std::atomic<uint64_t> gLongWaits = 0;
std::atomic<uint64_t> gWaits10ms = 0;
std::atomic<uint64_t> gWaitsOverNominal = 0;
std::atomic<uint64_t> gMaxWaitNanos = 0;
std::atomic<uint64_t> gLongDisplayIntervals = 0;
std::atomic<uint64_t> gMaxDisplayIntervalNanos = 0;
std::atomic<uint64_t> gDisplayIntervals10ms = 0;
std::atomic<uint64_t> gDisplayIntervals85fps = 0;
std::atomic<uint64_t> gDisplayIntervals80fps = 0;
std::atomic<uint64_t> gDisplayIntervalsOverNominal = 0;
std::atomic<uint64_t> gDisplayOverNominalNanos = 0;
std::atomic<uint64_t> gMaxSmallDisplayIntervalNanos = 0;
std::atomic<uint64_t> gDisplayCallNanos = 0;
std::atomic<uint64_t> gDisplayCallLongIntervals = 0;
std::atomic<uint64_t> gMaxDisplayCallNanos = 0;
std::atomic<uint64_t> gLastDisplayNanos = 0;
std::atomic<uint64_t> gLastTelemetryReportNanos = 0;
std::atomic<uint64_t> gLastAnomalyReportNanos = 0;
std::atomic<uint64_t> gPreviousReportTargetDelayCalls = 0;
std::atomic<uint64_t> gPreviousReportInputTargetDelayNanos = 0;
std::atomic<uint64_t> gPreviousReportTargetDelayNanos = 0;
std::atomic<uint64_t> gPreviousReportAdjustedTargetDelays = 0;
std::atomic<uint64_t> gPreviousReportDoubledTargetDelays = 0;
std::atomic<uint64_t> gPreviousReportLongTargetDelays = 0;
std::atomic<uint64_t> gPreviousReportVeryLongTargetDelays = 0;
std::atomic<uint64_t> gPreviousReportTargetDelays10ms = 0;
std::atomic<uint64_t> gPreviousReportTargetDelays85fps = 0;
std::atomic<uint64_t> gPreviousReportTargetDelaysOverNominal = 0;
std::atomic<uint64_t> gVpDurationCalls = 0;
std::atomic<uint64_t> gVpDurationNanos = 0;
std::atomic<uint64_t> gVpDurationPtsPath = 0;
std::atomic<uint64_t> gVpDurationSerialFallback = 0;
std::atomic<uint64_t> gVpDurationInvalidPtsFallback = 0;
std::atomic<uint64_t> gVpDurationTooLargeFallback = 0;
std::atomic<uint64_t> gVpDurationLongIntervals = 0;
std::atomic<uint64_t> gVpDurationMaxNanos = 0;
std::atomic<uint64_t> gPreviousReportVpDurationCalls = 0;
std::atomic<uint64_t> gPreviousReportVpDurationNanos = 0;
std::atomic<uint64_t> gPreviousReportVpDurationPtsPath = 0;
std::atomic<uint64_t> gPreviousReportVpDurationSerialFallback = 0;
std::atomic<uint64_t> gPreviousReportVpDurationInvalidPtsFallback = 0;
std::atomic<uint64_t> gPreviousReportVpDurationTooLargeFallback = 0;
std::atomic<uint64_t> gPreviousReportVpDurationLongIntervals = 0;
std::atomic<uint64_t> gQueuePictureCalls = 0;
std::atomic<uint64_t> gQueuePicturePtsIntervals = 0;
std::atomic<uint64_t> gQueuePicturePtsNanos = 0;
std::atomic<uint64_t> gQueuePictureLongPtsIntervals = 0;
std::atomic<uint64_t> gQueuePictureDurationNanos = 0;
std::atomic<uint64_t> gQueuePictureLongDurations = 0;
std::atomic<uint64_t> gQueuePictureMaxPtsIntervalNanos = 0;
std::atomic<int64_t> gPreviousQueuePicturePtsNanos = -1;
std::atomic<int> gPreviousQueuePictureSerial = -1;
std::atomic<uint64_t> gPreviousReportQueuePictureCalls = 0;
std::atomic<uint64_t> gPreviousReportQueuePicturePtsIntervals = 0;
std::atomic<uint64_t> gPreviousReportQueuePicturePtsNanos = 0;
std::atomic<uint64_t> gPreviousReportQueuePictureLongPtsIntervals = 0;
std::atomic<uint64_t> gPreviousReportQueuePictureDurationNanos = 0;
std::atomic<uint64_t> gPreviousReportQueuePictureLongDurations = 0;
std::atomic<uint64_t> gDecoderDecodeCalls = 0;
std::atomic<uint64_t> gDecoderDecodedFrames = 0;
std::atomic<uint64_t> gDecoderNoFrame = 0;
std::atomic<uint64_t> gDecoderErrors = 0;
std::atomic<uint64_t> gVideoDropChecks = 0;
std::atomic<uint64_t> gVideoDropRequested = 0;
std::atomic<uint64_t> gVideoDropBypassedCalls = 0;
std::atomic<uint64_t> gIjkEarlyUsReadCalls = 0;
std::atomic<uint64_t> gIjkEarlyUsSamples = 0;
std::atomic<int64_t> gIjkEarlyUsSumNanos = 0;
std::atomic<int64_t> gIjkEarlyUsMostLateNanos = 0;
std::atomic<uint64_t> gMasterClockSampleCalls = 0;
std::atomic<bool> gBlockIjkFrameDrop = false;
std::atomic<bool> gBlockComputeTargetDelay = true;

// Release scheduling inside IJK's refresh loop.  compute_target_delay() is answered with a
// delay that makes IJK's own `frame_timer + delay` land on a chosen SurfaceFlinger slot, so
// IJK wakes up (its waits are accurate to ~0.1 ms) and calls MediaCodec.releaseOutputBuffer
// exactly when the buffer must be queued; nothing is held back and no extra decoder output
// buffer is consumed.  frame_timer itself is never read: it is reproduced from the delays we
// returned and IJK's two reset rules (clamp to "now", and resync when >100 ms behind), with
// the Java release hook (same thread) and is_need_drop_frame() reporting every advance.
constexpr uint64_t kScheduleCallLeadPercent = 220;      // call phase before the target tick (1.8p collided: 118 replacements)
// Direct SurfaceView: 2.2p lands the Queue mid-way in SurfaceFlinger's latch window.  The
// external renderers consume the frame on the app's Choreographer VSync instead (GL redraw
// on their own thread); with 2.2p the frame reached the SurfaceTexture a median 0.2 ms after
// that callback, so every jitter flipped it between two app VSyncs (179 gaps/30 s).  The
// Java side chooses the percentage per renderer mode; 270 puts the arrival half a period
// ahead of the app VSync.
std::atomic<uint64_t> gScheduleCallLeadPercent = kScheduleCallLeadPercent;
constexpr uint64_t kScheduleSlotSlackPercent = 50;      // a call up to 0.5p late still lands
constexpr uint64_t kScheduleSlackMarginNanos = 1'000'000ULL;
constexpr uint64_t kScheduleGridMaxAgeNanos = 2'000'000'000ULL;
constexpr uint64_t kScheduleResyncNanos = 100'000'000ULL;  // IJK AV_SYNC_THRESHOLD_MAX
constexpr uint64_t kScheduleMinPeriodNanos = 4'000'000ULL;
constexpr uint64_t kScheduleMaxPeriodNanos = 34'000'000ULL;
std::atomic<bool> gReleaseScheduling = false;
// Playback rate != 1.0: IJK then paces video off the (sped-up) audio clock by shortening
// compute_target_delay() and dropping frames.  The VSync scheduling replaces that delay with
// its own 1x PTS cadence and the drop suppression keeps every frame, so speed changes had no
// effect on the picture.  Scheduling and drop suppression are bypassed unless the rate is 1.0.
std::atomic<bool> gPlaybackRateUnity = true;
// Media3 schedules at any speed: the release time is (pts - position) / speed.  Off 1x the PTS
// step is divided by the rate and the frame still goes through the VSync scheduler; only
// content faster than the panel (fps bypass) is left to IJK's own pacing and drops.
std::atomic<double> gPlaybackRate = 1.0;
std::atomic<bool> gRateScheduling = false;   // last off-1x frame was scheduled
std::atomic<uint64_t> gScheduleRateScaled = 0;
// Content faster than the panel (120 fps at 2x = 240 frames/s): Media3 renders a frame only if
// its release time falls on a new VSync and skips the rest (releaseOutputBuffer(false)), which
// keeps exactly one frame per VSync at an even content step.  Handing that case to IJK left
// 240 untimed frames/s for SurfaceFlinger to pick "the newest" from, an uneven content step.
// Only for Surface external: the direct Surface path redraws IJK's last frame on every display
// call, so a skipped codec buffer would still produce an (untimed) duplicate swap there.
std::atomic<bool> gMedia3SkipSameVsync = false;
std::atomic<uint64_t> gMedia3Skipped = 0;
std::atomic<uint64_t> gScheduleRateBypassed = 0;
std::atomic<uint64_t> gGridAnchorNanos = 0;
std::atomic<uint64_t> gGridPeriodNanos = 0;
std::atomic<uint64_t> gGridUpdatedNanos = 0;
// The app-phase VSync timestamp (Choreographer frameTimeNanos) matching gGridAnchorNanos.
// The external renderers consume frames relative to this VSync (GL redraw on the app VSync,
// or HWUI's updateTexImage ~0.7 ms after it), and the distance between app VSync and present
// is a fixed number of milliseconds on this device, not of periods: at 60 Hz the 120 Hz
// "2.45 periods before present" landed the frame 4 ms after the app VSync instead of 8 and
// the early tail crossed it.  With the arrival grid the target is defined directly as
// "mid-way between two app VSyncs", independent of the refresh rate.
std::atomic<uint64_t> gGridVsyncNanos = 0;
// Choreographer FrameTimeline deadline for the same tick: the time by which a buffer must be
// queued for SurfaceFlinger to present it at the expected presentation time (= SF's commit
// point; NX809J reports expected - 10.33 ms at 120 Hz, matching the traced 10.2 ms).  For the
// direct SurfaceView path this replaces the hand-tuned "2.2 periods before present": the
// queue window for a present is (previous deadline, deadline - settle], so the call goes out
// at deadline - period/2 - settle/2 - callToArrival, whatever the device's offsets are.
std::atomic<uint64_t> gGridDeadlineNanos = 0;
// Main-process app VSync relay (AppVsyncRelay.java): seqlock (0), vsyncNs (8), periodNs (16),
// writtenNs (24).  The service's own Choreographer can run on a different phase from the main
// process's at 60 Hz, and the external renderers consume on the main process's VSync.
struct AppVsyncRelay {
    volatile uint64_t sequence;
    volatile uint64_t vsyncNanos;
    volatile uint64_t periodNanos;
    volatile uint64_t writtenNanos;
    // SurfaceFlinger compositor timing of the main process's video Surface (GL output), from
    // ANativeWindow GET_COMPOSITOR_TIMING: next composite deadline, composite interval and
    // composite-to-present latency.  Zero when unavailable.
    volatile int64_t compositeDeadlineNanos;
    volatile int64_t compositeIntervalNanos;
    volatile int64_t compositeToPresentNanos;
    volatile uint64_t compositeWrittenNanos;
};
std::atomic<AppVsyncRelay*> gAppVsyncRelay = nullptr;
std::atomic<uint64_t> gRelayVsyncUsed = 0;
constexpr uint64_t kRelayMaxAgeNanos = 500'000'000;

// ---- Compositor timing straight from the BufferQueue producer -----------------------------
// SurfaceFlinger tells every producer when it will next composite (deadline), how often
// (interval) and how long composite-to-present takes, through the frame-timestamp query of
// the window (Surface::getCompositorTiming).  That is the latch grid of the actual surface,
// whatever the refresh rate, render rate or process the Surface lives in; the Choreographer
// timelines only approximate it (the 60 Hz render-rate latch sat 4.8 ms after our queue where
// the Choreographer-derived target promised 9).  ANativeWindow's perform() entry is a stable
// ABI (vendor HALs call it); the codes are from system/window.h.
constexpr int kNativeWindowEnableFrameTimestamps = 25;
constexpr int kNativeWindowGetCompositorTiming = 26;
constexpr int kNativeWindowMagic = 0x5f776e64;   // '_wnd'

struct NativeBaseMirror {
    int magic;
    int version;
    void* reserved[4];
    void (*incRef)(void*);
    void (*decRef)(void*);
};
struct NativeWindowMirror {
    NativeBaseMirror common;
    uint32_t flags;
    int minSwapInterval;
    int maxSwapInterval;
    float xdpi;
    float ydpi;
    intptr_t oem[4];
    int (*setSwapInterval)(ANativeWindow*, int);
    int (*dequeueBuffer_DEPRECATED)(ANativeWindow*, void**);
    int (*lockBuffer_DEPRECATED)(ANativeWindow*, void*);
    int (*queueBuffer_DEPRECATED)(ANativeWindow*, void*);
    int (*query)(const ANativeWindow*, int, int*);
    int (*perform)(ANativeWindow*, int, ...);
};

std::mutex gCompositorMutex;
ANativeWindow* gCompositorWindow = nullptr;
std::atomic<int64_t> gCompositorDeadline = 0;
std::atomic<int64_t> gCompositorInterval = 0;
std::atomic<int64_t> gCompositorLatency = 0;
std::atomic<uint64_t> gCompositorQueriedNanos = 0;
std::atomic<uint64_t> gCompositorQueries = 0;
std::atomic<uint64_t> gCompositorFailures = 0;
std::atomic<uint64_t> gCompositorGridUsed = 0;
std::atomic<int64_t> gCompositorLatencyUsed = 0;
constexpr uint64_t kCompositorQueryIntervalNanos = 50'000'000;

bool queryCompositorTiming(ANativeWindow* window, int64_t* deadline, int64_t* interval,
                           int64_t* latency) {
    if (window == nullptr) {
        return false;
    }
    auto* mirror = reinterpret_cast<NativeWindowMirror*>(window);
    if (mirror->common.magic != kNativeWindowMagic || mirror->perform == nullptr) {
        return false;
    }
    int64_t d = 0, i = 0, l = 0;
    if (mirror->perform(window, kNativeWindowGetCompositorTiming, &d, &i, &l) != 0) {
        return false;
    }
    if (i < 4'000'000 || i > 34'000'000 || d <= 0 || l < 0 || l > 100'000'000) {
        return false;
    }
    *deadline = d; *interval = i; *latency = l;
    return true;
}

void setCompositorWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(gCompositorMutex);
    if (gCompositorWindow != nullptr) {
        ANativeWindow_release(gCompositorWindow);
    }
    gCompositorWindow = window;
    gCompositorDeadline.store(0, std::memory_order_relaxed);
    if (window != nullptr) {
        auto* mirror = reinterpret_cast<NativeWindowMirror*>(window);
        if (mirror->common.magic == kNativeWindowMagic && mirror->perform != nullptr) {
            mirror->perform(window, kNativeWindowEnableFrameTimestamps, 1);
        }
    }
}

// Refreshes the cached compositor timing of our own window at most every 50 ms.
bool ownCompositorTiming(uint64_t now, int64_t* deadline, int64_t* interval, int64_t* latency) {
    const uint64_t queried = gCompositorQueriedNanos.load(std::memory_order_acquire);
    if (queried == 0 || now - queried > kCompositorQueryIntervalNanos) {
        std::lock_guard<std::mutex> lock(gCompositorMutex);
        int64_t d, i, l;
        gCompositorQueries.fetch_add(1, std::memory_order_relaxed);
        if (queryCompositorTiming(gCompositorWindow, &d, &i, &l)) {
            gCompositorDeadline.store(d, std::memory_order_relaxed);
            gCompositorInterval.store(i, std::memory_order_relaxed);
            gCompositorLatency.store(l, std::memory_order_relaxed);
        } else {
            gCompositorFailures.fetch_add(1, std::memory_order_relaxed);
        }
        gCompositorQueriedNanos.store(now, std::memory_order_release);
    }
    const int64_t d = gCompositorDeadline.load(std::memory_order_relaxed);
    if (d <= 0) {
        return false;
    }
    *deadline = d;
    *interval = gCompositorInterval.load(std::memory_order_relaxed);
    *latency = gCompositorLatency.load(std::memory_order_relaxed);
    return true;
}

// The main process's compositor timing (GL output surface) via the relay.
bool relayedCompositorTiming(uint64_t now, int64_t* deadline, int64_t* interval, int64_t* latency) {
    AppVsyncRelay* relay = gAppVsyncRelay.load(std::memory_order_acquire);
    if (relay == nullptr) {
        return false;
    }
    for (int attempt = 0; attempt < 3; ++attempt) {
        const uint64_t seqBefore = relay->sequence;
        if (seqBefore & 1) continue;
        const int64_t d = relay->compositeDeadlineNanos;
        const int64_t i = relay->compositeIntervalNanos;
        const int64_t l = relay->compositeToPresentNanos;
        const uint64_t written = relay->compositeWrittenNanos;
        if (relay->sequence != seqBefore) continue;
        if (d <= 0 || i < 4'000'000 || i > 34'000'000 || written == 0 || now < written
                || now - written > kRelayMaxAgeNanos) {
            return false;
        }
        *deadline = d; *interval = i; *latency = l;
        return true;
    }
    return false;
}


void mapAppVsyncRelay() {
    if (gAppVsyncRelay.load(std::memory_order_acquire) != nullptr) {
        return;
    }
    const std::string path = "/data/user/" + std::to_string(getuid() / 100000)
            + "/tv.danmaku.bili/files/bilisurfacebridge/app_vsync_relay.bin";
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return;
    }
    void* mapped = mmap(nullptr, sizeof(AppVsyncRelay), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        return;
    }
    AppVsyncRelay* expected = nullptr;
    if (!gAppVsyncRelay.compare_exchange_strong(expected, static_cast<AppVsyncRelay*>(mapped))) {
        munmap(mapped, sizeof(AppVsyncRelay));
    }
}

// Returns the main process's latest app VSync (and its period) when fresh, else 0.
uint64_t relayedAppVsync(uint64_t now, uint64_t* periodOut) {
    AppVsyncRelay* relay = gAppVsyncRelay.load(std::memory_order_acquire);
    if (relay == nullptr) {
        return 0;
    }
    for (int attempt = 0; attempt < 3; ++attempt) {
        const uint64_t seqBefore = relay->sequence;
        if (seqBefore & 1) {
            continue;
        }
        const uint64_t vsync = relay->vsyncNanos;
        const uint64_t period = relay->periodNanos;
        const uint64_t written = relay->writtenNanos;
        if (relay->sequence != seqBefore) {
            continue;
        }
        if (vsync == 0 || written == 0 || now < written || now - written > kRelayMaxAgeNanos) {
            return 0;
        }
        if (periodOut != nullptr) {
            *periodOut = period;
        }
        return vsync;
    }
    return 0;
}
// 0 = legacy lead in periods, 1 = arrival mid-way between app VSyncs (external renderer that
// draws on the app VSync), 2 = queue mid-way in SurfaceFlinger's latch window (direct
// SurfaceView), 3 = the same latch-window target for an external renderer that draws
// immediately on arrival (SurfaceView + external render with ExternalRenderImmediate): the GL
// output, not the decoder buffer, is what SurfaceFlinger latches, so the GL draw time is
// added to the lead.  Mode 1 only works where the app VSync and the display period coincide;
// at 60 Hz the app still received 120 Hz VSyncs and the GL output landed on SurfaceFlinger's
// cutoff (bimodal queue->latch, 12-15 gaps / 30 s), which mode 3 avoids by construction.
std::atomic<int> gScheduleGridMode = 0;
std::atomic<uint64_t> gScheduleFpsBypassed = 0;
constexpr uint64_t kCallToArrivalNanos = 1'500'000;   // releaseOutputBuffer -> frame available
// SurfaceFlinger stops accepting a buffer for a present ~4 ms before the FrameTimeline
// deadline it reports (queued at deadline-4.1 ms still latched, at deadline-3.2 ms it was
// replaced; with a deadline-5.4 ms target 33 of 3514 frames were still replaced).  Queue at
// deadline - cutoff - margin, the margin capped at half a period so the previous commit never
// takes the buffer at 60 Hz.
constexpr uint64_t kLatchCutoffNanos = 4'000'000;
constexpr uint64_t kLatchMarginNanos = 4'000'000;
// SurfaceFlinger latches ~9.7 ms before the present at 120 Hz and ~9.1 ms at 60 Hz on this
// device (frame_slice Latch -> PresentFenceSignaled).  The latch window is anchored on the
// expected present (the grid anchor itself) rather than on the FrameTimeline deadline: the
// deadline-to-present distance the service's Choreographer reports changes with the render
// rate (10.33 ms at 120 Hz) and is not the latch.  The feedback controller corrects the rest.
constexpr uint64_t kLatchToPresentNanos = 9'700'000;
constexpr uint64_t kExternalGlDrawNanos = 1'500'000;  // frame available -> GL queueBuffer (immediate render)

// Present feedback (MediaCodec.OnFrameRenderedListener via Java): every scheduled release
// remembers the present it was aimed at; the actual present time then says whether the
// buffer made its latch (on time), missed it (late by whole display periods) or was taken by
// the previous commit (early).  A small controller moves the call earlier while late presents
// occur and creeps it back when none do, so SurfaceFlinger's real cutoff on this device, at
// this refresh rate and render-rate parity, is measured instead of assumed.
constexpr size_t kPendingPresents = 64;
struct PendingPresent {
    uint64_t callNanos;
    uint64_t expectedPresentNanos;
};
std::mutex gPresentMutex;
PendingPresent gPendingPresents[kPendingPresents];
size_t gPendingHead = 0;   // next write
size_t gPendingTail = 0;   // next read
std::atomic<int64_t> gExpectedMinusDeadlineNanos = 0;   // from the grid sample (10.33 ms on NX809J@120)
std::atomic<uint64_t> gLastAnchorLeadNanos = 0;          // deadline - anchor for the active mode
std::atomic<int64_t> gAdaptiveLeadNanos = 0;             // added to the lead (earlier call)
std::atomic<uint64_t> gPresentOnTime = 0;
std::atomic<uint64_t> gPresentLate = 0;
std::atomic<uint64_t> gPresentEarly = 0;
std::atomic<uint64_t> gPresentUnmatched = 0;
std::atomic<uint64_t> gAdaptiveSteps = 0;
uint32_t gWindowLate = 0, gWindowEarly = 0, gWindowCount = 0, gQuietWindows = 0;
// Diagnostics: the last few (present - expected) distances in ms, matched or not.
std::atomic<int32_t> gPresentDiffSamples[8] = {};
std::atomic<uint32_t> gPresentDiffIndex = 0;
std::atomic<uint64_t> gPendingRemembered = 0;
constexpr uint32_t kPresentWindow = 120;
constexpr int64_t kAdaptiveMaxLeadNanos = 10'000'000;
constexpr int64_t kAdaptiveStepUpNanos = 1'000'000;
constexpr int64_t kAdaptiveStepDownNanos = 250'000;

void rememberPendingPresent(uint64_t callNanos, uint64_t expectedPresentNanos) {
    std::lock_guard<std::mutex> lock(gPresentMutex);
    gPendingRemembered.fetch_add(1, std::memory_order_relaxed);
    gPendingPresents[gPendingHead] = {callNanos, expectedPresentNanos};
    gPendingHead = (gPendingHead + 1) % kPendingPresents;
    if (gPendingHead == gPendingTail) {
        gPendingTail = (gPendingTail + 1) % kPendingPresents;   // overwrite oldest
    }
}

void notePresent(uint64_t presentNanos) {
    const uint64_t period = gGridPeriodNanos.load(std::memory_order_acquire);
    if (period == 0) {
        return;
    }
    int64_t diff = 0;
    bool matched = false;
    {
        std::lock_guard<std::mutex> lock(gPresentMutex);
        if (gPendingTail != gPendingHead) {
            const int64_t raw = static_cast<int64_t>(presentNanos)
                    - static_cast<int64_t>(gPendingPresents[gPendingTail].expectedPresentNanos);
            const uint32_t slot = gPresentDiffIndex.fetch_add(1, std::memory_order_relaxed) % 8;
            gPresentDiffSamples[slot].store(static_cast<int32_t>(raw / 1000 / 100),   // 0.1 ms
                    std::memory_order_relaxed);
        }
        // Drop entries whose present is unreasonably old (never reported: replaced frames).
        while (gPendingTail != gPendingHead) {
            const PendingPresent& entry = gPendingPresents[gPendingTail];
            const int64_t d = static_cast<int64_t>(presentNanos)
                    - static_cast<int64_t>(entry.expectedPresentNanos);
            if (d > static_cast<int64_t>(period) * 4) {
                gPendingTail = (gPendingTail + 1) % kPendingPresents;   // stale, skip
                continue;
            }
            if (d < -static_cast<int64_t>(period) * 4) {
                break;   // this present precedes every pending expectation: unmatched
            }
            diff = d;
            matched = true;
            gPendingTail = (gPendingTail + 1) % kPendingPresents;
            break;
        }
    }
    if (!matched) {
        gPresentUnmatched.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    const int64_t half = static_cast<int64_t>(period) / 2;
    const bool late = diff > half;
    const bool early = diff < -half;
    if (late) gPresentLate.fetch_add(1, std::memory_order_relaxed);
    else if (early) gPresentEarly.fetch_add(1, std::memory_order_relaxed);
    else gPresentOnTime.fetch_add(1, std::memory_order_relaxed);
    if (gScheduleGridMode.load(std::memory_order_acquire) != 2) {
        // External renderers: the render callback is the GL thread's acquire, not the
        // display present; the counters stay diagnostic until the GL output's own present
        // timestamps are relayed from the main process.
        return;
    }
    std::lock_guard<std::mutex> lock(gPresentMutex);
    gWindowCount++;
    if (late) gWindowLate++;
    if (early) gWindowEarly++;
    if (gWindowCount < kPresentWindow) {
        return;
    }
    int64_t lead = gAdaptiveLeadNanos.load(std::memory_order_relaxed);
    if (gWindowLate * 50 > gWindowCount) {           // > 2 % late: call earlier
        lead = std::min(kAdaptiveMaxLeadNanos, lead + kAdaptiveStepUpNanos);
        gQuietWindows = 0;
        gAdaptiveSteps.fetch_add(1, std::memory_order_relaxed);
    } else if (gWindowLate == 0) {
        gQuietWindows++;
        if (gQuietWindows >= 5 && lead > 0) {       // 600 quiet presents: creep back
            lead = std::max<int64_t>(0, lead - kAdaptiveStepDownNanos);
            gQuietWindows = 0;
            gAdaptiveSteps.fetch_add(1, std::memory_order_relaxed);
        }
    } else {
        gQuietWindows = 0;
    }
    gAdaptiveLeadNanos.store(lead, std::memory_order_relaxed);
    gWindowCount = 0; gWindowLate = 0; gWindowEarly = 0;
}
std::atomic<uint64_t> gScheduledFrames = 0;
std::atomic<uint64_t> gScheduleSlotShifts = 0;
std::atomic<uint64_t> gScheduleReplacements = 0;
std::atomic<uint64_t> gScheduleLateFrames = 0;
std::atomic<uint64_t> gScheduleResyncs = 0;
std::atomic<uint64_t> gScheduleClamps = 0;
// Bilibili's compute_target_delay() corrects A/V drift at a threshold of only a few
// milliseconds (it doubled the delay for 24% of frames when video led by <8 ms), which is
// below the +-half-period placement a VSync grid needs.  While scheduling, the video runs on
// its PTS cadence and the original verdict (delay 0 = behind, 2x = ahead) only drives a
// whole-slot correction once it has been persistent.
// Disabled: on 9.13.0 the original returned a doubled delay for 100% of frames for a full
// minute regardless of a +166 ms shift we applied, then 0% — its verdict does not track our
// display timing, so slot corrections driven by it only add drift (+2.6 ms/s measured).
// Pacing on the PTS cadence leaves only clock-rate drift (ppm) until an audio clock can be
// read directly.  Verdicts are still counted for diagnostics.
constexpr bool kScheduleVerdictCorrections = false;
constexpr int64_t kScheduleHeadroomPeriods = 1;
constexpr uint32_t kScheduleVerdictFrames = 48;
// Slots are whole periods, so a correction overshoots a sub-period threshold and the verdict
// flips; without a rate limit that hunted twice a second.  Real clock drift needs a slot
// only every few minutes.
constexpr uint64_t kScheduleCorrectionIntervalNanos = 3'000'000'000ULL;
constexpr uint64_t kScheduleDropLatenessPercent = 50;   // frames later than half a slot may be dropped
// Disabled: returning a drop from is_need_drop_frame() when the schedule lags two slots
// dropped thousands of frames per minute and twice left the video frozen (27 presents in a
// 30 s window).  The call site's drop semantics on 9.13.0 are not understood well enough;
// lag is bounded by the ideal-timeline resync instead.
constexpr bool kScheduleLagDrops = false;
std::atomic<uint64_t> gScheduleCorrections = 0;
std::atomic<uint64_t> gScheduleDropsSuppressed = 0;
// Ideal (PTS-cadence) display timeline.  The reproduced frame_timer follows the grid once we
// steer it, so the source cadence has to be carried separately: it advances by IJK's own
// inputDelay (vp_duration) per displayed frame and re-syncs to frame_timer when IJK resets.
std::atomic<uint64_t> gScheduleIdealResyncs = 0;
constexpr uint64_t kScheduleIdealResyncPeriods = 60;  // only a stall or seek, not a late decode
std::atomic<uint64_t> gScheduleGridStale = 0;
std::atomic<uint64_t> gScheduleDisplayNotes = 0;
// Scheduling is only meaningful while the Java release hook reports every display; a codec
// it does not track (or a paused loop) would otherwise leave the frame_timer mirror stale
// and the returned delay would grow by a whole frame (66 ms waits were observed on 30 fps
// content).  Without a recent note IJK gets its own delay back, and the delay we return is
// never more than one grid period beyond IJK's own.
constexpr uint64_t kScheduleNoteMaxAgeNanos = 500'000'000ULL;
std::atomic<uint64_t> gScheduleNoteStale = 0;
std::atomic<uint64_t> gScheduleNotesUnmatched = 0;
// Per-player scheduler state.  Every IJK player runs its own video refresh thread, and that
// thread performs compute_target_delay(), the display call (our Java hook + JNI note) and
// is_need_drop_frame(), so the thread id identifies the player without any struct access.
// Preview players on list pages run concurrently with the main player.
uint64_t monotonicNanos();


// ---- Media3 1.8.1 port (docs/media3-reference.md) -----------------------------------------
// FixedFrameRateEstimator (exoplayer/video/FixedFrameRateEstimator.java): a matcher syncs once
// more than 15 consecutive frame durations lie within 1 ms of the first one, and its frame
// duration is the mean of the matched durations (8/9 ms millisecond-rounded PTS -> 8.333 ms).
constexpr int kMedia3ConsecutiveMatchingFrames = 15;
constexpr int64_t kMedia3MaxMatchingFrameDifferenceNanos = 1'000'000;

struct Media3Matcher {
    int64_t firstFramePresentationTimeNs = 0;
    int64_t firstFrameDurationNs = 0;
    int64_t lastFramePresentationTimeNs = 0;
    int64_t frameCount = 0;
    int64_t matchingFrameCount = 0;
    int64_t matchingFrameDurationSumNs = 0;
    bool recentFrameOutlierFlags[kMedia3ConsecutiveMatchingFrames] = {};
    int recentFrameOutlierCount = 0;

    void reset() {
        frameCount = 0;
        matchingFrameCount = 0;
        matchingFrameDurationSumNs = 0;
        recentFrameOutlierCount = 0;
        for (bool& flag : recentFrameOutlierFlags) flag = false;
    }
    bool isSynced() const {
        return frameCount > kMedia3ConsecutiveMatchingFrames && recentFrameOutlierCount == 0;
    }
    bool isLastFrameOutlier() const {
        if (frameCount == 0) return false;
        return recentFrameOutlierFlags[(frameCount - 1) % kMedia3ConsecutiveMatchingFrames];
    }
    int64_t frameDurationNs() const {
        return matchingFrameCount == 0 ? 0 : matchingFrameDurationSumNs / matchingFrameCount;
    }
    void onNextFrame(int64_t framePresentationTimeNs) {
        if (frameCount == 0) {
            firstFramePresentationTimeNs = framePresentationTimeNs;
        } else if (frameCount == 1) {
            firstFrameDurationNs = framePresentationTimeNs - firstFramePresentationTimeNs;
            matchingFrameDurationSumNs = firstFrameDurationNs;
            matchingFrameCount = 1;
        } else {
            const int64_t lastFrameDurationNs = framePresentationTimeNs - lastFramePresentationTimeNs;
            const int index = static_cast<int>(frameCount % kMedia3ConsecutiveMatchingFrames);
            if (std::llabs(lastFrameDurationNs - firstFrameDurationNs)
                    <= kMedia3MaxMatchingFrameDifferenceNanos) {
                matchingFrameCount++;
                matchingFrameDurationSumNs += lastFrameDurationNs;
                if (recentFrameOutlierFlags[index]) {
                    recentFrameOutlierFlags[index] = false;
                    recentFrameOutlierCount--;
                }
            } else if (!recentFrameOutlierFlags[index]) {
                recentFrameOutlierFlags[index] = true;
                recentFrameOutlierCount++;
            }
        }
        frameCount++;
        lastFramePresentationTimeNs = framePresentationTimeNs;
    }
};

struct Media3FrameRateEstimator {
    Media3Matcher matchers[2];
    int current = 0;
    bool candidateMatcherActive = false;
    bool switchToCandidateMatcherWhenSynced = false;
    bool haveLastFrame = false;
    int64_t lastFramePresentationTimeNs = 0;

    void reset() {
        matchers[0].reset();
        matchers[1].reset();
        current = 0;
        candidateMatcherActive = false;
        switchToCandidateMatcherWhenSynced = false;
        haveLastFrame = false;
    }
    void onNextFrame(int64_t framePresentationTimeNs) {
        Media3Matcher& currentMatcher = matchers[current];
        Media3Matcher& candidateMatcher = matchers[1 - current];
        currentMatcher.onNextFrame(framePresentationTimeNs);
        if (currentMatcher.isSynced() && !switchToCandidateMatcherWhenSynced) {
            candidateMatcherActive = false;
        } else if (haveLastFrame) {
            if (!candidateMatcherActive || candidateMatcher.isLastFrameOutlier()) {
                candidateMatcher.reset();
                candidateMatcher.onNextFrame(lastFramePresentationTimeNs);
            }
            candidateMatcherActive = true;
            candidateMatcher.onNextFrame(framePresentationTimeNs);
        }
        if (candidateMatcherActive && matchers[1 - current].isSynced()) {
            current = 1 - current;
            candidateMatcherActive = false;
            switchToCandidateMatcherWhenSynced = false;
        }
        lastFramePresentationTimeNs = framePresentationTimeNs;
        haveLastFrame = true;
    }
    bool isSynced() const { return matchers[current].isSynced(); }
    int64_t frameDurationNs() const { return isSynced() ? matchers[current].frameDurationNs() : 0; }
};

// VideoFrameReleaseHelper / VideoFrameReleaseControl / MediaCodecVideoRenderer constants.
constexpr int64_t kMedia3MaxAllowedAdjustmentNanos = 20'000'000;   // adjustReleaseTime
constexpr uint64_t kMedia3VsyncOffsetPercent = 80;                  // releaseTime = vsync - 80 %
constexpr int64_t kMedia3MaxEarlyNanos = 50'000'000;                // release at most 50 ms early
// Media3 polls every 10 ms and releases the first time earlyUs <= 50 ms, so a frame leaves
// 40-50 ms before its release timestamp; IJK wakes exactly when told, so aim at the middle.
constexpr int64_t kMedia3ReleaseLeadNanos = 45'000'000;
constexpr int64_t kMedia3LateDropNanos = 30'000'000;                // MIN_EARLY_US_LATE_THRESHOLD
constexpr uint64_t kMedia3MinDelayNanos = 1'000;                    // see scheduleMedia3()
std::atomic<uint64_t> gMedia3StuckReanchors = 0;
std::atomic<uint64_t> gMedia3SkipDecisions = 0;     // committed frames scheduled as skip
std::atomic<uint64_t> gMedia3TakeStateMismatch = 0; // take found another state than scheduled
std::atomic<void*> gMedia3LastScheduledState = nullptr;
std::atomic<uint64_t> gMedia3ResetsUnsynced = 0;
std::atomic<uint64_t> gMedia3ResetsOverLimit = 0;
std::atomic<int64_t> gMedia3ResetLastDiffUs = 0;
std::atomic<int64_t> gMedia3ResetLastBaseUs = 0;
constexpr int kMedia3LateStreakFrames = 8;
constexpr int kMedia3LatencySettleFrames = 60;
constexpr int64_t kMedia3MaxLatencyPeriods = 12;
constexpr int64_t kMedia3TargetReleaseLeadNanos = 33'000'000;
constexpr int64_t kMedia3SnapHysteresisNanos = 1'500'000;
std::atomic<uint64_t> gMedia3SnapHysteresis = 0;
constexpr uint64_t kMedia3BehindResyncNanos = 50'000'000;
constexpr int kMedia3BehindResyncFrames = 8;
std::atomic<uint64_t> gMedia3LatencySteps = 0;
std::atomic<uint64_t> gMedia3LatencyOffsetNs = 1ULL << 40;   // biased: signed offset

std::atomic<uint64_t> gMedia3Scheduled = 0;
std::atomic<uint64_t> gMedia3Adjusted = 0;
std::atomic<uint64_t> gMedia3AdjustmentResets = 0;
std::atomic<uint64_t> gMedia3SameVsync = 0;
std::atomic<uint64_t> gMedia3LateReleases = 0;
std::atomic<uint64_t> gMedia3Timestamped = 0;
std::atomic<uint64_t> gMedia3FrameDurationNs = 0;

struct SchedulerState {
    std::atomic<void*> player = nullptr;   // ffp pointer seen by both IJK hooks
    std::atomic<pid_t> tid = 0;            // the player's video refresh thread
    std::atomic<uint64_t> lastUsedNanos = 0;
    std::atomic<bool> frameTimerValid = false;
    std::atomic<uint64_t> frameTimerEstNanos = 0;
    std::atomic<uint64_t> lastReturnedDelayNanos = 0;
    std::atomic<uint64_t> lastTargetTickNanos = 0;
    std::atomic<uint64_t> pendingTargetTickNanos = 0;
    // Media3 mode (grid mode 4).  Touched only on the refresh thread; reset() from other
    // threads only raises media3ResetRequested.
    // IJK's refresh loop may call compute_target_delay() several times for one frame while
    // it waits, so scheduling only computes "pending" values from the committed state and
    // the commit happens once per displayed frame (noteDisplay, same thread).
    Media3FrameRateEstimator media3Estimator;
    int64_t media3PresentationNs = 0;          // cumulative PTS fed to the estimator
    bool media3IdealValid = false;
    uint64_t media3CommittedIdealNs = 0;       // unadjusted release time of the last frame
    int64_t media3LastAdjustedReleaseNs = 0;   // VideoFrameReleaseHelper.lastAdjustedReleaseTimeNs
    bool media3HaveLastAdjusted = false;
    uint64_t media3LastReleaseTsNs = 0;        // previous release timestamp (same-vsync check)
    bool media3PendingValid = false;
    bool media3PendingResync = false;
    uint64_t media3PendingIdealNs = 0;
    int64_t media3PendingAdjustedNs = 0;
    uint64_t media3PendingTsNs = 0;
    bool media3PendingIsSkip = false;
    uint64_t media3PendingStampNs = 0;   // releaseTs + lead-lock offset (what SF receives)
    // Whole periods added to every release timestamp when frames cannot be released ahead of
    // them (decoder-bound, e.g. 4K120 at 2x = 240 decodes/s): see commitMedia3Frame().
    int64_t media3LatencyOffsetNs = 0;   // signed: the lock moves both ways
    int media3LateStreak = 0;
    int media3FramesSinceResync = 0;   // catch-up after a (re)sync is late by design
    int media3BehindStreak = 0;
    uint64_t media3PendingBaseDelayNs = 0;
    std::atomic<bool> media3ResetRequested = true;
    std::atomic<uint64_t> media3PendingReleaseTsNs = 0;   // taken by the Java release hook
    std::atomic<bool> media3PendingSkip = false;          // release without rendering
    std::atomic<int64_t> offsetNanos = 0;
    std::atomic<uint32_t> behindVerdicts = 0;
    std::atomic<uint32_t> aheadVerdicts = 0;
    std::atomic<uint64_t> lastCorrectionNanos = 0;
    std::atomic<uint64_t> lastIntendedNanos = 0;
    std::atomic<bool> idealValid = false;
    std::atomic<uint64_t> idealPrevNanos = 0;
    std::atomic<uint64_t> idealNextNanos = 0;
    std::atomic<uint64_t> lastNoteNanos = 0;
    std::atomic<uint64_t> lagSlots = 0;
    std::atomic<uint64_t> notes = 0;
    std::atomic<uint64_t> scheduled = 0;
    std::atomic<uint64_t> lateFrames = 0;
    std::atomic<uint64_t> staleCalls = 0;

    void reset() {
        media3ResetRequested.store(true, std::memory_order_release);
        media3PendingReleaseTsNs.store(0, std::memory_order_release);
        frameTimerValid.store(false, std::memory_order_release);
        idealValid.store(false, std::memory_order_release);
        lastTargetTickNanos.store(0, std::memory_order_release);
        offsetNanos.store(0, std::memory_order_release);
        lastNoteNanos.store(0, std::memory_order_release);
    }
};
constexpr size_t kSchedulerSlots = 4;
std::array<SchedulerState, kSchedulerSlots> gSchedulers{};

// compute_target_delay() and is_need_drop_frame() both receive the player; the Java release
// hook only knows its thread, which is the refresh thread that compute_target_delay() runs
// on, so each state remembers that thread id.
SchedulerState& schedForPlayer(void* player) {
    const uint64_t now = monotonicNanos();
    size_t oldest = 0;
    for (size_t i = 0; i < kSchedulerSlots; ++i) {
        if (gSchedulers[i].player.load(std::memory_order_acquire) == player) {
            gSchedulers[i].lastUsedNanos.store(now, std::memory_order_relaxed);
            return gSchedulers[i];
        }
        if (gSchedulers[i].lastUsedNanos.load(std::memory_order_relaxed)
                < gSchedulers[oldest].lastUsedNanos.load(std::memory_order_relaxed)) {
            oldest = i;
        }
    }
    SchedulerState& state = gSchedulers[oldest];
    state.reset();
    state.player.store(player, std::memory_order_release);
    state.tid.store(0, std::memory_order_release);
    state.lastUsedNanos.store(now, std::memory_order_relaxed);
    return state;
}

SchedulerState* schedForCurrentThread() {
    const pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
    for (SchedulerState& state : gSchedulers) {
        if (state.tid.load(std::memory_order_acquire) == tid
                && state.player.load(std::memory_order_acquire) != nullptr) {
            return &state;
        }
    }
    return nullptr;
}

void resetAllSchedulers() {
    for (SchedulerState& state : gSchedulers) {
        state.reset();
    }
}

std::atomic<uint64_t> gScheduleDelayCapped = 0;
std::atomic<uint64_t> gScheduleDropAdvances = 0;
std::atomic<bool> gFrameQueueWaitRoundUp = false;
std::atomic<uintptr_t> gIjkPlayerBase = 0;
std::atomic<uintptr_t> gIjkSdlBase = 0;
std::atomic<uint64_t> gPreviousReportDecoderDecodeCalls = 0;
std::atomic<uint64_t> gPreviousReportDecoderDecodedFrames = 0;
std::atomic<uint64_t> gPreviousReportDecoderNoFrame = 0;
std::atomic<uint64_t> gPreviousReportDecoderErrors = 0;
std::atomic<uint64_t> gPreviousReportVideoDropChecks = 0;
std::atomic<uint64_t> gPreviousReportVideoDropRequested = 0;
std::atomic<uint64_t> gPreviousReportVideoDropBypassedCalls = 0;
std::atomic<uint64_t> gPreviousReportIjkEarlyUsReadCalls = 0;
std::atomic<uint64_t> gPreviousReportIjkEarlyUsSamples = 0;
std::atomic<int64_t> gPreviousReportIjkEarlyUsSumNanos = 0;
std::atomic<uint64_t> gVideoDropTimingSamples = 0;
std::atomic<int64_t> gVideoDropLatenessNanos = 0;
std::atomic<uint64_t> gVideoDropFilterDelayNanos = 0;
std::atomic<int64_t> gVideoDropMostLateNanos = 0;
std::atomic<uint64_t> gPreviousReportVideoDropTimingSamples = 0;
std::atomic<int64_t> gPreviousReportVideoDropLatenessNanos = 0;
std::atomic<uint64_t> gPreviousReportVideoDropFilterDelayNanos = 0;
std::atomic<uint64_t> gVideoDropGraceKept = 0;
std::atomic<uint64_t> gPreviousReportVideoDropGraceKept = 0;
std::atomic<uint64_t> gPreviousReportDisplayCalls = 0;
std::atomic<uint64_t> gPreviousReportWaitCalls = 0;
std::atomic<uint64_t> gPreviousReportRequestedWaitNanos = 0;
std::atomic<uint64_t> gPreviousReportActualWaitNanos = 0;
std::atomic<uint64_t> gPreviousReportWaitOverrunNanos = 0;
std::atomic<uint64_t> gPreviousReportLongWaits = 0;
std::atomic<uint64_t> gPreviousReportWaits10ms = 0;
std::atomic<uint64_t> gPreviousReportWaitsOverNominal = 0;
std::atomic<uint64_t> gPreviousReportLongDisplayIntervals = 0;
std::atomic<uint64_t> gPreviousReportDisplayIntervals10ms = 0;
std::atomic<uint64_t> gPreviousReportDisplayIntervals85fps = 0;
std::atomic<uint64_t> gPreviousReportDisplayIntervals80fps = 0;
std::atomic<uint64_t> gPreviousReportDisplayIntervalsOverNominal = 0;
std::atomic<uint64_t> gPreviousReportDisplayOverNominalNanos = 0;
std::atomic<uint64_t> gPreviousReportDisplayCallNanos = 0;
std::atomic<uint64_t> gPreviousReportDisplayCallLongIntervals = 0;

using ComputeTargetDelay = double (*)(void*, void*, double);
// Bilibili 9.5.0's frame_queue_wait_timeout uses x0, w1, w2 and writes w0
// on return. Preserve its int return value even though the current caller
// ignores it; this keeps the observer ABI-correct before any behavior A/B.
using FrameQueueWaitTimeout = int (*)(void*, int, int);
using VoutDisplayYuvOverlay = void (*)(void*, void*);
using VpDuration = double (*)(void*, void*, void*);
using QueuePicture = int (*)(void*, void*, void*, int64_t, int, double, double);
using DecoderDecodeFrame = int (*)(void*, void*, void*, void*);
using IsNeedDropFrame = int (*)(int64_t, void*, void*, int);
using SdlDelay = void (*)(unsigned int);
using SdlCondWaitTimeout = int (*)(void*, void*, unsigned int);
using MediaCodecReleaseOutputBuffer = int (*)(void*, int, int);
using GetMasterClock = double (*)(void*);
std::atomic<ComputeTargetDelay> gOriginalComputeTargetDelay = nullptr;
std::atomic<FrameQueueWaitTimeout> gOriginalFrameQueueWaitTimeout = nullptr;
std::atomic<VoutDisplayYuvOverlay> gOriginalVoutDisplayYuvOverlay = nullptr;
std::atomic<VpDuration> gOriginalVpDuration = nullptr;
std::atomic<QueuePicture> gOriginalQueuePicture = nullptr;
std::atomic<DecoderDecodeFrame> gOriginalDecoderDecodeFrame = nullptr;
std::atomic<IsNeedDropFrame> gOriginalIsNeedDropFrame = nullptr;
std::atomic<SdlDelay> gOriginalSdlDelay = nullptr;
std::atomic<SdlCondWaitTimeout> gOriginalSdlCondWaitTimeout = nullptr;
std::atomic<MediaCodecReleaseOutputBuffer> gOriginalMediaCodecReleaseOutputBuffer = nullptr;

using EglSwapBuffers = EGLBoolean (*)(EGLDisplay, EGLSurface);
std::atomic<EglSwapBuffers> gOriginalEglSwapBuffers = nullptr;

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
}

uint32_t encodeMoveWide(bool keep, uint32_t halfword, uint32_t immediate) {
    const uint32_t opcode = keep ? 0xf2800000U : 0xd2800000U;
    return opcode | (halfword << 21U) | (immediate << 5U) | kRefreshLoopRegister;
}

std::array<uint32_t, kRefreshLoopInstructionCount> build4msInstructions() {
    constexpr uint64_t kFourMillisecondsBits = 0x3f70624dd2f1a9fcULL;
    return {
            encodeMoveWide(false, 0, (kFourMillisecondsBits >> 0U) & 0xffffU),
            encodeMoveWide(true, 1, (kFourMillisecondsBits >> 16U) & 0xffffU),
            encodeMoveWide(true, 2, (kFourMillisecondsBits >> 32U) & 0xffffU),
            encodeMoveWide(true, 3, (kFourMillisecondsBits >> 48U) & 0xffffU)};
}

struct LibrarySearch {
    uintptr_t base = 0;
    std::string name;
    bool ijk913BuildId = false;
};

bool hasIjk913BuildId(const dl_phdr_info* info) {
    for (Elf64_Half index = 0; index < info->dlpi_phnum; ++index) {
        const Elf64_Phdr& header = info->dlpi_phdr[index];
        if (header.p_type != PT_NOTE) continue;
        const auto* bytes = reinterpret_cast<const uint8_t*>(info->dlpi_addr + header.p_vaddr);
        size_t position = 0;
        const size_t length = static_cast<size_t>(header.p_memsz);
        while (length - position >= sizeof(Elf64_Nhdr)) {
            Elf64_Nhdr note{};
            std::memcpy(&note, bytes + position, sizeof(note));
            position += sizeof(note);
            const size_t nameBytes = (static_cast<size_t>(note.n_namesz) + 3U) & ~size_t(3U);
            const size_t dataBytes = (static_cast<size_t>(note.n_descsz) + 3U) & ~size_t(3U);
            if (nameBytes > length - position || dataBytes > length - position - nameBytes) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4
                    && note.n_descsz == kIjk913BuildId.size()
                    && std::memcmp(bytes + position, "GNU", 4) == 0
                    && std::memcmp(bytes + position + nameBytes, kIjk913BuildId.data(),
                            kIjk913BuildId.size()) == 0) {
                return true;
            }
            position += nameBytes + dataBytes;
        }
    }
    return false;
}

int locateIjkPlayer(struct dl_phdr_info* info, size_t, void* data) {
    auto* search = static_cast<LibrarySearch*>(data);
    if (info->dlpi_name == nullptr || std::strstr(info->dlpi_name, "libijkplayer.so") == nullptr) {
        return 0;
    }
    search->base = static_cast<uintptr_t>(info->dlpi_addr);
    search->name = info->dlpi_name;
    search->ijk913BuildId = hasIjk913BuildId(info);
    return 1;
}

bool ijk913InstructionsMatch(uintptr_t base) {
    const struct Entry {
        uintptr_t offset;
        const std::array<uint32_t, 4>* expected;
    } entries[] = {
            {0x456cc, &kIjk913ComputePrologue},
            {0x478a8, &kIjk913WaitPrologue},
            {0x4a740, &kIjk913DropPrologue},
    };
    for (const Entry& entry : entries) {
        for (size_t index = 0; index < entry.expected->size(); ++index) {
            uint32_t instruction = 0;
            std::memcpy(&instruction,
                    reinterpret_cast<const void*>(base + entry.offset + index * 4U), 4);
            if (instruction != (*entry.expected)[index]) return false;
        }
    }
    return true;
}

using InstructionPointers =
        std::array<volatile uint32_t*, kRefreshLoopInstructionCount>;

InstructionPointers instructionPointers(uintptr_t libraryBase) {
    InstructionPointers pointers{};
    for (size_t index = 0; index < pointers.size(); ++index) {
        pointers[index] = reinterpret_cast<volatile uint32_t*>(
                libraryBase + kRefreshLoopInstructionOffsets[index]);
    }
    return pointers;
}

InstructionPointers vpDurationInstructionPointers(uintptr_t libraryBase) {
    InstructionPointers pointers{};
    for (size_t index = 0; index < pointers.size(); ++index) {
        pointers[index] = reinterpret_cast<volatile uint32_t*>(
                libraryBase + kVpDurationOffset + index * sizeof(uint32_t));
    }
    return pointers;
}

InstructionPointers queuePictureInstructionPointers(uintptr_t libraryBase) {
    InstructionPointers pointers{};
    for (size_t index = 0; index < pointers.size(); ++index) {
        pointers[index] = reinterpret_cast<volatile uint32_t*>(
                libraryBase + kQueuePictureOffset + index * sizeof(uint32_t));
    }
    return pointers;
}

bool instructionsMatch(const InstructionPointers& instructions,
                       const std::array<uint32_t, kRefreshLoopInstructionCount>& expected) {
    for (size_t index = 0; index < expected.size(); ++index) {
        if (*instructions[index] != expected[index]) {
            return false;
        }
    }
    return true;
}

std::string instructionSignature(const InstructionPointers& instructions) {
    char buffer[96];
    std::snprintf(buffer, sizeof(buffer), "%08x,%08x,%08x,%08x",
            *instructions[0], *instructions[1], *instructions[2], *instructions[3]);
    return buffer;
}

std::string hexWord(uint32_t word) {
    char buffer[9];
    std::snprintf(buffer, sizeof(buffer), "%08x", word);
    return buffer;
}

std::string octalBytes(uint32_t word) {
    std::ostringstream stream;
    for (size_t index = 0; index < sizeof(word); ++index) {
        const uint8_t byte = static_cast<uint8_t>(word >> (index * 8U));
        char buffer[5];
        std::snprintf(buffer, sizeof(buffer), "\\%03o", byte);
        stream << buffer;
    }
    return stream.str();
}

std::string rootPatchCommand(const InstructionPointers& instructions,
                             const std::array<uint32_t, kRefreshLoopInstructionCount>& expected,
                             const std::array<uint32_t, kRefreshLoopInstructionCount>& replacement) {
    const std::string pid = std::to_string(getpid());
    std::ostringstream script;
    script << "trap \"kill -CONT " << pid << " >/dev/null 2>&1\" EXIT; "
           << "kill -STOP " << pid << " || exit 20; ";
    for (size_t index = 0; index < expected.size(); ++index) {
        const uint64_t address = reinterpret_cast<uintptr_t>(instructions[index]);
        script << "dd if=/proc/" << pid << "/mem bs=4 skip=" << (address / 4U)
               << " count=1 2>/dev/null | od -An -tx4 | grep -q \""
               << hexWord(expected[index]) << "\" || exit " << (30 + index)
               << "; ";
    }
    // Write the most-significant double halfword first. While the target is stopped this also
    // avoids a refresh iteration observing a partially reconstructed double.
    for (size_t index = replacement.size(); index-- > 0;) {
        const uint64_t address = reinterpret_cast<uintptr_t>(instructions[index]);
        script << "printf \"" << octalBytes(replacement[index]) << "\" | dd of=/proc/"
               << pid << "/mem bs=1 seek=" << address
               << " conv=notrunc >/dev/null || exit " << (40 + index) << "; ";
    }
    for (size_t index = 0; index < replacement.size(); ++index) {
        const uint64_t address = reinterpret_cast<uintptr_t>(instructions[index]);
        script << "dd if=/proc/" << pid << "/mem bs=4 skip=" << (address / 4U)
               << " count=1 2>/dev/null | od -An -tx4 | grep -q \""
               << hexWord(replacement[index]) << "\" || exit " << (50 + index) << "; ";
    }
    script << "echo BSB_ROOT_PATCH_OK";
    return "/system/bin/su -c '" + script.str() + "' 2>&1";
}

bool patchWithRootHelper(const InstructionPointers& instructions,
                         const std::array<uint32_t, kRefreshLoopInstructionCount>& expected,
                         const std::array<uint32_t, kRefreshLoopInstructionCount>& replacement,
                         std::string* error) {
    const std::string command = rootPatchCommand(instructions, expected, replacement);
    FILE* process = popen(command.c_str(), "r");
    if (process == nullptr) {
        *error = "unable to start root helper";
        return false;
    }

    std::array<char, 256> buffer{};
    std::string output;
    while (fgets(buffer.data(), static_cast<int>(buffer.size()), process) != nullptr) {
        output.append(buffer.data());
    }
    const int status = pclose(process);
    if (status != 0 || output.find("BSB_ROOT_PATCH_OK") == std::string::npos) {
        const int exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : status;
        *error = "root helper failed exit=" + std::to_string(exitCode)
                + " output=" + output;
        return false;
    }
    return true;
}

std::string patchRefreshLoop() {
    std::lock_guard<std::mutex> lock(gPatchMutex);

    LibrarySearch search;
    dl_iterate_phdr(locateIjkPlayer, &search);
    if (search.base == 0) {
        return "libijkplayer.so not loaded";
    }

    const auto replacement = build4msInstructions();
    const InstructionPointers instructions = instructionPointers(search.base);
    if (instructionsMatch(instructions, replacement)) {
        return "IJK 4ms cap already active";
    }
    if (!instructionsMatch(instructions, kExpected10msInstructions)) {
        return "IJK binary signature mismatch; patch skipped name=" + search.name
                + " words=" + instructionSignature(instructions);
    }

    const uintptr_t firstAddress = reinterpret_cast<uintptr_t>(instructions.front());
    const uintptr_t lastAddress = reinterpret_cast<uintptr_t>(instructions.back())
            + sizeof(uint32_t);
    std::string writeError;
    if (!patchWithRootHelper(instructions, kExpected10msInstructions, replacement, &writeError)) {
        return "IJK 4ms patch skipped: " + writeError;
    }
    __builtin___clear_cache(reinterpret_cast<char*>(firstAddress),
            reinterpret_cast<char*>(lastAddress));
    if (!instructionsMatch(instructions, replacement)) {
        return "IJK 4ms patch verification failed words=" + instructionSignature(instructions);
    }

    logInfo("patched exact Bilibili 9.5.0 IJK refresh cap from 10ms to 4ms via verified root helper: " + search.name);
    return "IJK refresh cap patched to 4ms";
}

uintptr_t dynamicPointer(uintptr_t libraryBase, Elf64_Addr value) {
    return value >= libraryBase ? static_cast<uintptr_t>(value) : libraryBase + value;
}

int protectionForAddress(uintptr_t address) {
    FILE* maps = std::fopen("/proc/self/maps", "re");
    if (maps == nullptr) {
        return 0;
    }
    char line[512];
    while (std::fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start = 0;
        unsigned long long end = 0;
        char permissions[5] = {};
        if (std::sscanf(line, "%llx-%llx %4s", &start, &end, permissions) != 3
                || address < start || address >= end) {
            continue;
        }
        int protection = 0;
        if (permissions[0] == 'r') protection |= PROT_READ;
        if (permissions[1] == 'w') protection |= PROT_WRITE;
        if (permissions[2] == 'x') protection |= PROT_EXEC;
        std::fclose(maps);
        return protection;
    }
    std::fclose(maps);
    return 0;
}

bool replaceGotSlot(void** slot, void* replacement, void** originalOut, std::string* error) {
    const uintptr_t address = reinterpret_cast<uintptr_t>(slot);
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        *error = "unable to resolve GOT page size";
        return false;
    }
    const uintptr_t pageMask = static_cast<uintptr_t>(pageSize - 1);
    const uintptr_t pageStart = address & ~pageMask;
    const int originalProtection = protectionForAddress(address);
    if (originalProtection == 0) {
        *error = "unable to resolve GOT page protection";
        return false;
    }
    if (mprotect(reinterpret_cast<void*>(pageStart), static_cast<size_t>(pageSize),
            originalProtection | PROT_WRITE) != 0) {
        *error = "unable to make GOT slot writable";
        return false;
    }
    void* original = *slot;
    if (originalOut != nullptr && *originalOut == nullptr && original != replacement) {
        *originalOut = original;
    }
    if (original != replacement) {
        *slot = replacement;
    }
    if (mprotect(reinterpret_cast<void*>(pageStart), static_cast<size_t>(pageSize),
            originalProtection) != 0) {
        *error = "GOT hook installed but protection restore failed";
        return false;
    }
    return true;
}

// ---- Release timestamp on IJK's own GL output ---------------------------------------------
// Bilibili 9.13's "direct Surface" is not direct: MediaCodec renders into a SurfaceTexture in
// the IJK service and the refresh thread (ijk_ff_vout) redraws it with GL into the
// SurfaceView (acquireBuffer -> dequeueBuffer SurfaceView -> eglSwapBuffers, ~2.5 ms after
// releaseOutputBuffer).  A timestamp passed to releaseOutputBuffer therefore never reaches
// SurfaceFlinger (its buffers show "presentIn -1042580352 ms", an auto timestamp).  The
// Media3 release timestamp is attached where the SurfaceView buffer is produced instead:
// eglPresentationTimeANDROID() on the refresh thread right before IJK's eglSwapBuffers.
// The Java release hook arms it (same thread, thread-local) when it takes the timestamp.
using EglPresentationTimeAndroid = EGLBoolean (*)(EGLDisplay, EGLSurface, int64_t);
std::atomic<EglPresentationTimeAndroid> gEglPresentationTime = nullptr;
std::atomic<bool> gEglPresentationTimeResolved = false;
std::atomic<uint64_t> gSwapTimestamped = 0;
std::atomic<uint64_t> gSwapTimestampFailures = 0;
std::atomic<uint64_t> gSwapsWithoutTimestamp = 0;
thread_local uint64_t tlsSwapTimestampNs = 0;

EglPresentationTimeAndroid resolveEglPresentationTime() {
    if (!gEglPresentationTimeResolved.load(std::memory_order_acquire)) {
        void* egl = dlopen("libEGL.so", RTLD_NOW | RTLD_NOLOAD);
        if (egl == nullptr) {
            egl = dlopen("libEGL.so", RTLD_NOW);
        }
        using GetProc = void* (*)(const char*);
        auto getProc = egl == nullptr ? nullptr
                : reinterpret_cast<GetProc>(dlsym(egl, "eglGetProcAddress"));
        if (getProc != nullptr) {
            gEglPresentationTime.store(reinterpret_cast<EglPresentationTimeAndroid>(
                    getProc("eglPresentationTimeANDROID")), std::memory_order_release);
        }
        gEglPresentationTimeResolved.store(true, std::memory_order_release);
    }
    return gEglPresentationTime.load(std::memory_order_acquire);
}

extern "C" EGLBoolean countedEglSwapBuffers(EGLDisplay display, EGLSurface surface) {
    const EglSwapBuffers original = gOriginalEglSwapBuffers.load();
    if (original == nullptr) {
        return EGL_FALSE;
    }
    const uint64_t presentationNs = tlsSwapTimestampNs;
    if (presentationNs != 0) {
        tlsSwapTimestampNs = 0;
        const EglPresentationTimeAndroid setPresentationTime = resolveEglPresentationTime();
        if (setPresentationTime != nullptr
                && setPresentationTime(display, surface, static_cast<int64_t>(presentationNs))
                        == EGL_TRUE) {
            gSwapTimestamped.fetch_add(1, std::memory_order_relaxed);
        } else {
            gSwapTimestampFailures.fetch_add(1, std::memory_order_relaxed);
        }
    } else {
        gSwapsWithoutTimestamp.fetch_add(1, std::memory_order_relaxed);
    }
    const EGLBoolean result = original(display, surface);
    if (result == EGL_TRUE) {
        gSuccessfulEglSwaps.fetch_add(1, std::memory_order_relaxed);
    }
    return result;
}

struct ProducerHookSearch {
    size_t matchedSlots = 0;
    std::string error;
};

bool hookEglSwapBuffersImport(const dl_phdr_info* info, ProducerHookSearch* search) {
    if (info->dlpi_name == nullptr
            || (std::strstr(info->dlpi_name, "libijk.so") == nullptr
                    && std::strstr(info->dlpi_name, "libijksdl.so") == nullptr)) {
        return true;
    }
    const Elf64_Phdr* dynamicProgramHeader = nullptr;
    for (Elf64_Half index = 0; index < info->dlpi_phnum; ++index) {
        if (info->dlpi_phdr[index].p_type == PT_DYNAMIC) {
            dynamicProgramHeader = &info->dlpi_phdr[index];
            break;
        }
    }
    if (dynamicProgramHeader == nullptr) {
        return true;
    }
    const uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    const auto* dynamic = reinterpret_cast<const Elf64_Dyn*>(
            base + dynamicProgramHeader->p_vaddr);
    const Elf64_Sym* symbols = nullptr;
    const char* strings = nullptr;
    const Elf64_Rela* jumpRelocations = nullptr;
    size_t jumpRelocationBytes = 0;
    for (const Elf64_Dyn* entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        if (entry->d_tag == DT_SYMTAB) {
            symbols = reinterpret_cast<const Elf64_Sym*>(dynamicPointer(base, entry->d_un.d_ptr));
        } else if (entry->d_tag == DT_STRTAB) {
            strings = reinterpret_cast<const char*>(dynamicPointer(base, entry->d_un.d_ptr));
        } else if (entry->d_tag == DT_JMPREL) {
            jumpRelocations = reinterpret_cast<const Elf64_Rela*>(
                    dynamicPointer(base, entry->d_un.d_ptr));
        } else if (entry->d_tag == DT_PLTRELSZ) {
            jumpRelocationBytes = static_cast<size_t>(entry->d_un.d_val);
        } else if (entry->d_tag == DT_PLTREL && entry->d_un.d_val != DT_RELA) {
            search->error = "unsupported non-RELA PLT relocations";
            return false;
        }
    }
    if (symbols == nullptr || strings == nullptr || jumpRelocations == nullptr) {
        return true;
    }
    const size_t relocationCount = jumpRelocationBytes / sizeof(Elf64_Rela);
    for (size_t index = 0; index < relocationCount; ++index) {
        const Elf64_Rela& relocation = jumpRelocations[index];
        const uint32_t type = ELF64_R_TYPE(relocation.r_info);
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) {
            continue;
        }
        const uint32_t symbolIndex = ELF64_R_SYM(relocation.r_info);
        if (std::strcmp(strings + symbols[symbolIndex].st_name, "eglSwapBuffers") != 0) {
            continue;
        }
        auto** slot = reinterpret_cast<void**>(base + relocation.r_offset);
        void* original = nullptr;
        if (!replaceGotSlot(slot, reinterpret_cast<void*>(countedEglSwapBuffers), &original,
                &search->error)) {
            return false;
        }
        if (original != nullptr) {
            EglSwapBuffers expected = nullptr;
            gOriginalEglSwapBuffers.compare_exchange_strong(
                    expected, reinterpret_cast<EglSwapBuffers>(original));
        }
        ++search->matchedSlots;
    }
    return true;
}

int hookProducerImports(dl_phdr_info* info, size_t, void* data) {
    auto* search = static_cast<ProducerHookSearch*>(data);
    return hookEglSwapBuffersImport(info, search) ? 0 : 1;
}

void writeProducerStats(const std::string& statsPath, double fps, double onePercentLow,
                        size_t sampleCount) {
    char text[192];
    const int length = std::snprintf(text, sizeof(text),
            "surface_queue_fps=%.1f\none_percent_low_fps=%.1f\nrolling_samples=%zu\n",
            fps, onePercentLow, sampleCount);
    const int descriptor = open(statsPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (descriptor < 0) {
        return;
    }
    (void) write(descriptor, text, static_cast<size_t>(length));
    close(descriptor);
}

void sampleProducerStats(std::string statsPath) {
    uint64_t previousCount = gSuccessfulEglSwaps.load(std::memory_order_relaxed);
    auto previousTime = std::chrono::steady_clock::now();
    std::vector<double> recentSamples;
    writeProducerStats(statsPath, 0.0, 0.0, 0);
    for (;;) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        const auto now = std::chrono::steady_clock::now();
        const uint64_t currentCount = gSuccessfulEglSwaps.load(std::memory_order_relaxed);
        const double elapsedSeconds = std::chrono::duration<double>(now - previousTime).count();
        const double fps = (currentCount - previousCount) / elapsedSeconds;
        previousCount = currentCount;
        previousTime = now;
        recentSamples.push_back(fps);
        if (recentSamples.size() > 60) {
            recentSamples.erase(recentSamples.begin());
        }
        std::vector<double> sortedSamples = recentSamples;
        std::sort(sortedSamples.begin(), sortedSamples.end());
        const size_t onePercentIndex = static_cast<size_t>(
                (sortedSamples.size() - 1) * 0.01);
        writeProducerStats(statsPath, fps, sortedSamples[onePercentIndex],
                recentSamples.size());
    }
}

std::atomic<bool> gSwapHookInstalled = false;
std::string gSwapHookResult;

std::string installSwapTimestampHook() {
    std::lock_guard<std::mutex> lock(gProducerHookMutex);
    if (gSwapHookInstalled.load()) {
        return gSwapHookResult;
    }
    ProducerHookSearch search;
    dl_iterate_phdr(hookProducerImports, &search);
    if (!search.error.empty()) {
        return "swap timestamp hook failed: " + search.error;
    }
    if (search.matchedSlots == 0 || gOriginalEglSwapBuffers.load() == nullptr) {
        return "swap timestamp hook: IJK EGL import not loaded yet";
    }
    gSwapHookInstalled.store(true);
    gSwapHookResult = "swap timestamp hook on IJK eglSwapBuffers imports="
            + std::to_string(search.matchedSlots)
            + (resolveEglPresentationTime() != nullptr ? " presentationTime=ok"
                    : " presentationTime=unavailable");
    return gSwapHookResult;
}

std::string startProducerCounter(const std::string& statsPath) {
    std::lock_guard<std::mutex> lock(gProducerHookMutex);
    if (gProducerSamplerStarted.load()) {
        return "Surface producer counter already active";
    }
    ProducerHookSearch search;
    dl_iterate_phdr(hookProducerImports, &search);
    if (!search.error.empty()) {
        return "Surface producer counter skipped: " + search.error;
    }
    if (search.matchedSlots == 0 || gOriginalEglSwapBuffers.load() == nullptr) {
        return "Surface producer counter skipped: IJK EGL import not loaded";
    }
    gProducerSamplerStarted.store(true);
    std::thread(sampleProducerStats, statsPath).detach();
    return "Surface producer counter active; hooked EGL imports="
            + std::to_string(search.matchedSlots);
}

uint64_t monotonicNanos() {
    timespec timestamp{};
    clock_gettime(CLOCK_MONOTONIC, &timestamp);
    return static_cast<uint64_t>(timestamp.tv_sec) * 1'000'000'000ULL
            + static_cast<uint64_t>(timestamp.tv_nsec);
}

double readMasterClock(void* state) {
    if (state == nullptr) {
        return NAN;
    }
    LibrarySearch library;
    dl_iterate_phdr(locateIjkPlayer, &library);
    if (library.base == 0) {
        return NAN;
    }
    const auto masterClock = reinterpret_cast<GetMasterClock>(
            library.base + kGetMasterClockOffset);
    if (masterClock == nullptr) {
        return NAN;
    }
    const double value = masterClock(state);
    return std::isfinite(value) ? value : NAN;
}

const char* rawSampleKindName(RawSampleKind kind) {
    switch (kind) {
        case RawSampleKind::kComputeTarget:
            return "compute_target";
        case RawSampleKind::kFrameQueueWait:
            return "frame_queue_wait";
        case RawSampleKind::kDisplayInterval:
            return "display_interval";
        case RawSampleKind::kDisplayCall:
            return "display_call";
        case RawSampleKind::kMasterClock:
            return "master_clock";
        case RawSampleKind::kIjkEarlyUs:
            return "ijk_early_us_ns";
        case RawSampleKind::kQueuePicture:
            return "ijk_queue_picture";
        case RawSampleKind::kDisplayPicture:
            return "ijk_display_picture";
        case RawSampleKind::kVpDuration:
            return "vp_duration";
        case RawSampleKind::kVpDurationContext:
            return "vp_duration_context";
        case RawSampleKind::kSdlDelay:
            return "sdl_delay";
        case RawSampleKind::kSdlCondWait:
            return "sdl_cond_wait";
        case RawSampleKind::kMediaCodecRelease:
            return "mediacodec_release";
        case RawSampleKind::kNativeSchedule:
            return "native_schedule";
        case RawSampleKind::kNativeDisplayNote:
            return "native_display_note";
    }
    return "unknown";
}

bool writeAll(int descriptor, const char* data, size_t length) {
    size_t written = 0;
    while (written < length) {
        const ssize_t result = write(descriptor, data + written, length - written);
        if (result > 0) {
            written += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && errno == EINTR) {
            continue;
        }
        return false;
    }
    return true;
}

void recordRawSample(RawSampleKind kind, uint64_t timestampNanos,
                     uint64_t valueNanos, uint64_t auxiliaryNanos) {
    if (!gRawSampleWriterStarted.load(std::memory_order_relaxed)) {
        return;
    }
    std::lock_guard<std::mutex> lock(gRawSampleMutex);
    const size_t index = (gRawSampleStart + gRawSampleCount) % kRawSampleCapacity;
    gRawSampleRing[index] = {timestampNanos, kind, valueNanos, auxiliaryNanos};
    if (gRawSampleCount < kRawSampleCapacity) {
        ++gRawSampleCount;
    } else {
        gRawSampleStart = (gRawSampleStart + 1) % kRawSampleCapacity;
    }
}

std::vector<RawSample> drainRawSamples() {
    std::vector<RawSample> drained;
    std::lock_guard<std::mutex> lock(gRawSampleMutex);
    drained.reserve(gRawSampleCount);
    for (size_t offset = 0; offset < gRawSampleCount; ++offset) {
        drained.push_back(gRawSampleRing[(gRawSampleStart + offset) % kRawSampleCapacity]);
    }
    gRawSampleStart = 0;
    gRawSampleCount = 0;
    return drained;
}

void rawSampleWriter(int descriptor) {
    for (;;) {
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        const std::vector<RawSample> samples = drainRawSamples();
        if (samples.empty()) {
            continue;
        }
        std::string output;
        output.reserve(samples.size() * 72);
        char line[160];
        for (const RawSample& sample : samples) {
            const int length = sample.kind == RawSampleKind::kIjkEarlyUs
                    ? std::snprintf(line, sizeof(line), "%llu,%s,%lld,%llu\n",
                            static_cast<unsigned long long>(sample.timestampNanos),
                            rawSampleKindName(sample.kind),
                            static_cast<long long>(static_cast<int64_t>(sample.valueNanos)),
                            static_cast<unsigned long long>(sample.auxiliaryNanos))
                    : std::snprintf(line, sizeof(line), "%llu,%s,%llu,%llu\n",
                            static_cast<unsigned long long>(sample.timestampNanos),
                            rawSampleKindName(sample.kind),
                            static_cast<unsigned long long>(sample.valueNanos),
                            static_cast<unsigned long long>(sample.auxiliaryNanos));
            if (length > 0 && static_cast<size_t>(length) < sizeof(line)) {
                output.append(line, static_cast<size_t>(length));
            }
        }
        if (!writeAll(descriptor, output.data(), output.size())) {
            logInfo("native raw sample writer stopped: write failed");
            close(descriptor);
            return;
        }
    }
}

bool startRawSampleWriter(const std::string& samplePath) {
    if (samplePath.empty()) {
        return false;
    }
    const int descriptor = open(samplePath.c_str(),
            O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (descriptor < 0) {
        return false;
    }
    const std::string header = "# nativeTelemetryBuild=" + std::string(kTelemetryBuild)
            + "\n# columns=timestamp_ns,event,value_ns,auxiliary_ns\n"
            + "timestamp_ns,event,value_ns,auxiliary_ns\n";
    if (!writeAll(descriptor, header.c_str(), header.size())) {
        close(descriptor);
        return false;
    }
    gRawSampleWriterStarted.store(true, std::memory_order_release);
    std::thread(rawSampleWriter, descriptor).detach();
    return true;
}

void updateMaximum(std::atomic<uint64_t>* destination, uint64_t candidate) {
    uint64_t current = destination->load(std::memory_order_relaxed);
    while (current < candidate
            && !destination->compare_exchange_weak(current, candidate,
                    std::memory_order_relaxed, std::memory_order_relaxed)) {
    }
}

void updateMinimum(std::atomic<int64_t>* destination, int64_t candidate) {
    int64_t current = destination->load(std::memory_order_relaxed);
    while ((current == 0 || candidate < current)
            && !destination->compare_exchange_weak(current, candidate,
                    std::memory_order_relaxed, std::memory_order_relaxed)) {
    }
}

void reportRefreshAnomaly(const char* source, uint64_t valueNanos,
                          uint64_t requestedNanos, uint64_t actualNanos) {
    const uint64_t now = monotonicNanos();
    uint64_t previous = gLastAnomalyReportNanos.load(std::memory_order_relaxed);
    if (previous != 0 && now - previous < kAnomalyReportIntervalNanos) {
        return;
    }
    if (!gLastAnomalyReportNanos.compare_exchange_strong(
            previous, now,
            std::memory_order_relaxed, std::memory_order_relaxed)) {
        return;
    }
    logInfo("[DEBUG-FRAME-LOCATE] anomaly source=" + std::string(source)
            + " valueMs=" + std::to_string(static_cast<double>(valueNanos) / 1'000'000.0)
            + " requestedMs="
            + std::to_string(static_cast<double>(requestedNanos) / 1'000'000.0)
            + " actualMs="
            + std::to_string(static_cast<double>(actualNanos) / 1'000'000.0)
            + " targetCalls=" + std::to_string(
                    gTargetDelayCalls.load(std::memory_order_relaxed))
            + " waitCalls=" + std::to_string(gWaitCalls.load(std::memory_order_relaxed))
            + " displayCalls=" + std::to_string(
                    gDisplayCalls.load(std::memory_order_relaxed))
            + " queuePicture=" + std::to_string(
                    gQueuePictureCalls.load(std::memory_order_relaxed))
            + " decoderFrames=" + std::to_string(
                    gDecoderDecodedFrames.load(std::memory_order_relaxed))
            + " videoDropRequested=" + std::to_string(
                    gVideoDropRequested.load(std::memory_order_relaxed))
            + " videoDropBypassedCalls=" + std::to_string(
                    gVideoDropBypassedCalls.load(std::memory_order_relaxed)));
}

void reportRefreshTelemetry(uint64_t now) {
    uint64_t reportAt = gLastTelemetryReportNanos.load(std::memory_order_relaxed);
    if (reportAt != 0 && now - reportAt < 1'000'000'000ULL) {
        return;
    }
    if (!gLastTelemetryReportNanos.compare_exchange_strong(reportAt, now,
            std::memory_order_relaxed, std::memory_order_relaxed)) {
        return;
    }

    const uint64_t targetCount = gTargetDelayCalls.load(std::memory_order_relaxed);
    const uint64_t inputTargetNanos = gInputTargetDelayNanos.load(std::memory_order_relaxed);
    const uint64_t targetNanos = gTargetDelayNanos.load(std::memory_order_relaxed);
    const uint64_t adjustedTargets = gAdjustedTargetDelays.load(std::memory_order_relaxed);
    const uint64_t doubledTargets = gDoubledTargetDelays.load(std::memory_order_relaxed);
    const uint64_t longTargets = gLongTargetDelays.load(std::memory_order_relaxed);
    const uint64_t veryLongTargets = gVeryLongTargetDelays.load(std::memory_order_relaxed);
    const uint64_t targetDelays10ms = gTargetDelays10ms.load(std::memory_order_relaxed);
    const uint64_t targetDelays85fps = gTargetDelays85fps.load(std::memory_order_relaxed);
    const uint64_t targetDelaysOverNominal = gTargetDelaysOverNominal.load(
            std::memory_order_relaxed);
    const uint64_t maxTargetNanos = gMaxTargetDelayNanos.exchange(0,
            std::memory_order_relaxed);
    const uint64_t vpDurationCalls = gVpDurationCalls.load(std::memory_order_relaxed);
    const uint64_t vpDurationNanos = gVpDurationNanos.load(std::memory_order_relaxed);
    const uint64_t vpDurationPtsPath = gVpDurationPtsPath.load(std::memory_order_relaxed);
    const uint64_t vpDurationSerialFallback = gVpDurationSerialFallback.load(
            std::memory_order_relaxed);
    const uint64_t vpDurationInvalidPtsFallback = gVpDurationInvalidPtsFallback.load(
            std::memory_order_relaxed);
    const uint64_t vpDurationTooLargeFallback = gVpDurationTooLargeFallback.load(
            std::memory_order_relaxed);
    const uint64_t vpDurationLongIntervals = gVpDurationLongIntervals.load(
            std::memory_order_relaxed);
    const uint64_t vpDurationMaxNanos = gVpDurationMaxNanos.exchange(0,
            std::memory_order_relaxed);
    const uint64_t queuePictureCalls = gQueuePictureCalls.load(std::memory_order_relaxed);
    const uint64_t queuePicturePtsIntervals = gQueuePicturePtsIntervals.load(
            std::memory_order_relaxed);
    const uint64_t queuePicturePtsNanos = gQueuePicturePtsNanos.load(
            std::memory_order_relaxed);
    const uint64_t queuePictureLongPtsIntervals = gQueuePictureLongPtsIntervals.load(
            std::memory_order_relaxed);
    const uint64_t queuePictureDurationNanos = gQueuePictureDurationNanos.load(
            std::memory_order_relaxed);
    const uint64_t queuePictureLongDurations = gQueuePictureLongDurations.load(
            std::memory_order_relaxed);
    const uint64_t queuePictureMaxPtsIntervalNanos = gQueuePictureMaxPtsIntervalNanos.exchange(0,
            std::memory_order_relaxed);
    const uint64_t decoderDecodeCalls = gDecoderDecodeCalls.load(std::memory_order_relaxed);
    const uint64_t decoderDecodedFrames = gDecoderDecodedFrames.load(std::memory_order_relaxed);
    const uint64_t decoderNoFrame = gDecoderNoFrame.load(std::memory_order_relaxed);
    const uint64_t decoderErrors = gDecoderErrors.load(std::memory_order_relaxed);
    const uint64_t videoDropChecks = gVideoDropChecks.load(std::memory_order_relaxed);
    const uint64_t videoDropRequested = gVideoDropRequested.load(std::memory_order_relaxed);
    const uint64_t videoDropBypassedCalls = gVideoDropBypassedCalls.load(
            std::memory_order_relaxed);
    const uint64_t ijkEarlyUsReadCalls = gIjkEarlyUsReadCalls.load(
            std::memory_order_relaxed);
    const uint64_t ijkEarlyUsSamples = gIjkEarlyUsSamples.load(
            std::memory_order_relaxed);
    const int64_t ijkEarlyUsSumNanos = gIjkEarlyUsSumNanos.load(
            std::memory_order_relaxed);
    const int64_t ijkEarlyUsMostLateNanos = gIjkEarlyUsMostLateNanos.exchange(0,
            std::memory_order_relaxed);
    const uint64_t videoDropTimingSamples = gVideoDropTimingSamples.load(
            std::memory_order_relaxed);
    const int64_t videoDropLatenessNanos = gVideoDropLatenessNanos.load(
            std::memory_order_relaxed);
    const uint64_t videoDropFilterDelayNanos = gVideoDropFilterDelayNanos.load(
            std::memory_order_relaxed);
    const int64_t videoDropMostLateNanos = gVideoDropMostLateNanos.exchange(0,
            std::memory_order_relaxed);
    const uint64_t videoDropGraceKept = gVideoDropGraceKept.load(
            std::memory_order_relaxed);
    const uint64_t displayCount = gDisplayCalls.load(std::memory_order_relaxed);
    const uint64_t waitCount = gWaitCalls.load(std::memory_order_relaxed);
    const uint64_t requestedWaitNanos = gRequestedWaitNanos.load(std::memory_order_relaxed);
    const uint64_t actualWaitNanos = gActualWaitNanos.load(std::memory_order_relaxed);
    const uint64_t waitOverrunNanos = gWaitOverrunNanos.load(std::memory_order_relaxed);
    const uint64_t longWaits = gLongWaits.load(std::memory_order_relaxed);
    const uint64_t waits10ms = gWaits10ms.load(std::memory_order_relaxed);
    const uint64_t waitsOverNominal = gWaitsOverNominal.load(std::memory_order_relaxed);
    const uint64_t maxWaitNanos = gMaxWaitNanos.exchange(0, std::memory_order_relaxed);
    const uint64_t longIntervals = gLongDisplayIntervals.load(std::memory_order_relaxed);
    const uint64_t maxInterval = gMaxDisplayIntervalNanos.exchange(0, std::memory_order_relaxed);
    const uint64_t displayIntervals10ms = gDisplayIntervals10ms.load(
            std::memory_order_relaxed);
    const uint64_t displayIntervals85fps = gDisplayIntervals85fps.load(
            std::memory_order_relaxed);
    const uint64_t displayIntervals80fps = gDisplayIntervals80fps.load(
            std::memory_order_relaxed);
    const uint64_t displayIntervalsOverNominal = gDisplayIntervalsOverNominal.load(
            std::memory_order_relaxed);
    const uint64_t displayOverNominalNanos = gDisplayOverNominalNanos.load(
            std::memory_order_relaxed);
    const uint64_t maxSmallDisplayIntervalNanos = gMaxSmallDisplayIntervalNanos.exchange(0,
            std::memory_order_relaxed);
    const uint64_t displayCallNanos = gDisplayCallNanos.load(std::memory_order_relaxed);
    const uint64_t displayCallLongIntervals = gDisplayCallLongIntervals.load(
            std::memory_order_relaxed);
    const uint64_t maxDisplayCallNanos = gMaxDisplayCallNanos.exchange(0,
            std::memory_order_relaxed);

    const uint64_t targetDelta = targetCount - gPreviousReportTargetDelayCalls.exchange(
            targetCount, std::memory_order_relaxed);
    const uint64_t inputTargetNanosDelta = inputTargetNanos
            - gPreviousReportInputTargetDelayNanos.exchange(inputTargetNanos,
                    std::memory_order_relaxed);
    const uint64_t targetNanosDelta = targetNanos - gPreviousReportTargetDelayNanos.exchange(
            targetNanos, std::memory_order_relaxed);
    const uint64_t adjustedTargetDelta = adjustedTargets
            - gPreviousReportAdjustedTargetDelays.exchange(adjustedTargets,
                    std::memory_order_relaxed);
    const uint64_t doubledTargetDelta = doubledTargets
            - gPreviousReportDoubledTargetDelays.exchange(doubledTargets,
                    std::memory_order_relaxed);
    const uint64_t longTargetDelta = longTargets
            - gPreviousReportLongTargetDelays.exchange(longTargets,
                    std::memory_order_relaxed);
    const uint64_t veryLongTargetDelta = veryLongTargets
            - gPreviousReportVeryLongTargetDelays.exchange(veryLongTargets,
                    std::memory_order_relaxed);
    const uint64_t targetDelays10msDelta = targetDelays10ms
            - gPreviousReportTargetDelays10ms.exchange(targetDelays10ms,
                    std::memory_order_relaxed);
    const uint64_t targetDelays85fpsDelta = targetDelays85fps
            - gPreviousReportTargetDelays85fps.exchange(targetDelays85fps,
                    std::memory_order_relaxed);
    const uint64_t targetDelaysOverNominalDelta = targetDelaysOverNominal
            - gPreviousReportTargetDelaysOverNominal.exchange(targetDelaysOverNominal,
                    std::memory_order_relaxed);
    const uint64_t vpDurationCallDelta = vpDurationCalls
            - gPreviousReportVpDurationCalls.exchange(vpDurationCalls,
                    std::memory_order_relaxed);
    const uint64_t vpDurationNanosDelta = vpDurationNanos
            - gPreviousReportVpDurationNanos.exchange(vpDurationNanos,
                    std::memory_order_relaxed);
    const uint64_t vpDurationPtsPathDelta = vpDurationPtsPath
            - gPreviousReportVpDurationPtsPath.exchange(vpDurationPtsPath,
                    std::memory_order_relaxed);
    const uint64_t vpDurationSerialFallbackDelta = vpDurationSerialFallback
            - gPreviousReportVpDurationSerialFallback.exchange(vpDurationSerialFallback,
                    std::memory_order_relaxed);
    const uint64_t vpDurationInvalidPtsFallbackDelta = vpDurationInvalidPtsFallback
            - gPreviousReportVpDurationInvalidPtsFallback.exchange(vpDurationInvalidPtsFallback,
                    std::memory_order_relaxed);
    const uint64_t vpDurationTooLargeFallbackDelta = vpDurationTooLargeFallback
            - gPreviousReportVpDurationTooLargeFallback.exchange(vpDurationTooLargeFallback,
                    std::memory_order_relaxed);
    const uint64_t vpDurationLongIntervalDelta = vpDurationLongIntervals
            - gPreviousReportVpDurationLongIntervals.exchange(vpDurationLongIntervals,
                    std::memory_order_relaxed);
    const uint64_t queuePictureCallDelta = queuePictureCalls
            - gPreviousReportQueuePictureCalls.exchange(queuePictureCalls,
                    std::memory_order_relaxed);
    const uint64_t queuePicturePtsIntervalDelta = queuePicturePtsIntervals
            - gPreviousReportQueuePicturePtsIntervals.exchange(queuePicturePtsIntervals,
                    std::memory_order_relaxed);
    const uint64_t queuePicturePtsNanosDelta = queuePicturePtsNanos
            - gPreviousReportQueuePicturePtsNanos.exchange(queuePicturePtsNanos,
                    std::memory_order_relaxed);
    const uint64_t queuePictureLongPtsIntervalDelta = queuePictureLongPtsIntervals
            - gPreviousReportQueuePictureLongPtsIntervals.exchange(queuePictureLongPtsIntervals,
                    std::memory_order_relaxed);
    const uint64_t queuePictureDurationNanosDelta = queuePictureDurationNanos
            - gPreviousReportQueuePictureDurationNanos.exchange(queuePictureDurationNanos,
                    std::memory_order_relaxed);
    const uint64_t queuePictureLongDurationDelta = queuePictureLongDurations
            - gPreviousReportQueuePictureLongDurations.exchange(queuePictureLongDurations,
                    std::memory_order_relaxed);
    const uint64_t decoderDecodeCallDelta = decoderDecodeCalls
            - gPreviousReportDecoderDecodeCalls.exchange(decoderDecodeCalls,
                    std::memory_order_relaxed);
    const uint64_t decoderDecodedFrameDelta = decoderDecodedFrames
            - gPreviousReportDecoderDecodedFrames.exchange(decoderDecodedFrames,
                    std::memory_order_relaxed);
    const uint64_t decoderNoFrameDelta = decoderNoFrame
            - gPreviousReportDecoderNoFrame.exchange(decoderNoFrame,
                    std::memory_order_relaxed);
    const uint64_t decoderErrorDelta = decoderErrors
            - gPreviousReportDecoderErrors.exchange(decoderErrors,
                    std::memory_order_relaxed);
    const uint64_t videoDropCheckDelta = videoDropChecks
            - gPreviousReportVideoDropChecks.exchange(videoDropChecks,
                    std::memory_order_relaxed);
    const uint64_t videoDropRequestedDelta = videoDropRequested
            - gPreviousReportVideoDropRequested.exchange(videoDropRequested,
                    std::memory_order_relaxed);
    const uint64_t videoDropBypassedCallsDelta = videoDropBypassedCalls
            - gPreviousReportVideoDropBypassedCalls.exchange(videoDropBypassedCalls,
                    std::memory_order_relaxed);
    const uint64_t ijkEarlyUsReadCallsDelta = ijkEarlyUsReadCalls
            - gPreviousReportIjkEarlyUsReadCalls.exchange(ijkEarlyUsReadCalls,
                    std::memory_order_relaxed);
    const uint64_t ijkEarlyUsSampleDelta = ijkEarlyUsSamples
            - gPreviousReportIjkEarlyUsSamples.exchange(ijkEarlyUsSamples,
                    std::memory_order_relaxed);
    const int64_t ijkEarlyUsSumNanosDelta = ijkEarlyUsSumNanos
            - gPreviousReportIjkEarlyUsSumNanos.exchange(ijkEarlyUsSumNanos,
                    std::memory_order_relaxed);
    const uint64_t videoDropTimingSampleDelta = videoDropTimingSamples
            - gPreviousReportVideoDropTimingSamples.exchange(videoDropTimingSamples,
                    std::memory_order_relaxed);
    const int64_t videoDropLatenessNanosDelta = videoDropLatenessNanos
            - gPreviousReportVideoDropLatenessNanos.exchange(videoDropLatenessNanos,
                    std::memory_order_relaxed);
    const uint64_t videoDropFilterDelayNanosDelta = videoDropFilterDelayNanos
            - gPreviousReportVideoDropFilterDelayNanos.exchange(videoDropFilterDelayNanos,
                    std::memory_order_relaxed);
    const uint64_t videoDropGraceKeptDelta = videoDropGraceKept
            - gPreviousReportVideoDropGraceKept.exchange(videoDropGraceKept,
                    std::memory_order_relaxed);
    const uint64_t displayDelta = displayCount - gPreviousReportDisplayCalls.exchange(
            displayCount, std::memory_order_relaxed);
    const uint64_t waitDelta = waitCount - gPreviousReportWaitCalls.exchange(
            waitCount, std::memory_order_relaxed);
    const uint64_t requestedWaitDelta = requestedWaitNanos
            - gPreviousReportRequestedWaitNanos.exchange(requestedWaitNanos,
                    std::memory_order_relaxed);
    const uint64_t actualWaitDelta = actualWaitNanos
            - gPreviousReportActualWaitNanos.exchange(actualWaitNanos,
                    std::memory_order_relaxed);
    const uint64_t waitOverrunDelta = waitOverrunNanos
            - gPreviousReportWaitOverrunNanos.exchange(waitOverrunNanos,
                    std::memory_order_relaxed);
    const uint64_t longWaitDelta = longWaits
            - gPreviousReportLongWaits.exchange(longWaits,
                    std::memory_order_relaxed);
    const uint64_t waits10msDelta = waits10ms
            - gPreviousReportWaits10ms.exchange(waits10ms,
                    std::memory_order_relaxed);
    const uint64_t waitsOverNominalDelta = waitsOverNominal
            - gPreviousReportWaitsOverNominal.exchange(waitsOverNominal,
                    std::memory_order_relaxed);
    const uint64_t longIntervalDelta = longIntervals
            - gPreviousReportLongDisplayIntervals.exchange(longIntervals,
                    std::memory_order_relaxed);
    const uint64_t displayIntervals10msDelta = displayIntervals10ms
            - gPreviousReportDisplayIntervals10ms.exchange(displayIntervals10ms,
                    std::memory_order_relaxed);
    const uint64_t displayIntervals85fpsDelta = displayIntervals85fps
            - gPreviousReportDisplayIntervals85fps.exchange(displayIntervals85fps,
                    std::memory_order_relaxed);
    const uint64_t displayIntervals80fpsDelta = displayIntervals80fps
            - gPreviousReportDisplayIntervals80fps.exchange(displayIntervals80fps,
                    std::memory_order_relaxed);
    const uint64_t displayIntervalsOverNominalDelta = displayIntervalsOverNominal
            - gPreviousReportDisplayIntervalsOverNominal.exchange(displayIntervalsOverNominal,
                    std::memory_order_relaxed);
    const uint64_t displayOverNominalNanosDelta = displayOverNominalNanos
            - gPreviousReportDisplayOverNominalNanos.exchange(displayOverNominalNanos,
                    std::memory_order_relaxed);
    const uint64_t displayCallNanosDelta = displayCallNanos
            - gPreviousReportDisplayCallNanos.exchange(displayCallNanos,
                    std::memory_order_relaxed);
    const uint64_t displayCallLongIntervalDelta = displayCallLongIntervals
            - gPreviousReportDisplayCallLongIntervals.exchange(displayCallLongIntervals,
                    std::memory_order_relaxed);
    const double meanInputTargetDelayMs = targetDelta == 0 ? 0.0
            : static_cast<double>(inputTargetNanosDelta) / targetDelta / 1'000'000.0;
    const double meanTargetDelayMs = targetDelta == 0 ? 0.0
            : static_cast<double>(targetNanosDelta) / targetDelta / 1'000'000.0;
    const double meanVpDurationMs = vpDurationCallDelta == 0 ? 0.0
            : static_cast<double>(vpDurationNanosDelta) / vpDurationCallDelta / 1'000'000.0;
    const double meanQueuePicturePtsMs = queuePicturePtsIntervalDelta == 0 ? 0.0
            : static_cast<double>(queuePicturePtsNanosDelta) / queuePicturePtsIntervalDelta
                    / 1'000'000.0;
    const double meanQueuePictureDurationMs = queuePictureCallDelta == 0 ? 0.0
            : static_cast<double>(queuePictureDurationNanosDelta) / queuePictureCallDelta
                    / 1'000'000.0;
    const double meanVideoDropLateMs = videoDropTimingSampleDelta == 0 ? 0.0
            : static_cast<double>(videoDropLatenessNanosDelta) / videoDropTimingSampleDelta
                    / 1'000'000.0;
    const double meanVideoDropFilterDelayMs = videoDropTimingSampleDelta == 0 ? 0.0
            : static_cast<double>(videoDropFilterDelayNanosDelta) / videoDropTimingSampleDelta
                    / 1'000'000.0;
    const double meanIjkEarlyUsMs = ijkEarlyUsSampleDelta == 0 ? 0.0
            : static_cast<double>(ijkEarlyUsSumNanosDelta) / ijkEarlyUsSampleDelta
                    / 1'000'000.0;
    const double meanRequestedWaitMs = waitDelta == 0 ? 0.0
            : static_cast<double>(requestedWaitDelta) / waitDelta / 1'000'000.0;
    const double meanActualWaitMs = waitDelta == 0 ? 0.0
            : static_cast<double>(actualWaitDelta) / waitDelta / 1'000'000.0;
    const double meanWaitOverrunMs = waitDelta == 0 ? 0.0
            : static_cast<double>(waitOverrunDelta) / waitDelta / 1'000'000.0;
    const double maxDisplayIntervalMs = static_cast<double>(maxInterval) / 1'000'000.0;
    const double meanDisplayCallMs = displayDelta == 0 ? 0.0
            : static_cast<double>(displayCallNanosDelta) / displayDelta / 1'000'000.0;
    const double meanDisplayExcessMs = displayIntervalsOverNominalDelta == 0 ? 0.0
            : static_cast<double>(displayOverNominalNanosDelta)
                    / displayIntervalsOverNominalDelta / 1'000'000.0;
    logInfo("refresh telemetry: target=" + std::to_string(targetDelta)
            + " inputTargetMs=" + std::to_string(meanInputTargetDelayMs)
            + " outputTargetMs=" + std::to_string(meanTargetDelayMs)
            + " adjusted=" + std::to_string(adjustedTargetDelta)
            + " doubled=" + std::to_string(doubledTargetDelta)
            + " computeTarget>=16.7ms=" + std::to_string(longTargetDelta)
            + " computeTarget>=33.3ms=" + std::to_string(veryLongTargetDelta)
            + " computeTarget>=10ms=" + std::to_string(targetDelays10msDelta)
            + " computeTarget>=11.765ms=" + std::to_string(targetDelays85fpsDelta)
            + " computeTarget>8.333ms=" + std::to_string(targetDelaysOverNominalDelta)
            + " computeTargetMaxMs=" + std::to_string(
                    static_cast<double>(maxTargetNanos) / 1'000'000.0)
            + " vpDuration=" + std::to_string(vpDurationCallDelta)
            + " vpDurationMs=" + std::to_string(meanVpDurationMs)
            + " vpPtsPath=" + std::to_string(vpDurationPtsPathDelta)
            + " vpSerialFallback=" + std::to_string(vpDurationSerialFallbackDelta)
            + " vpInvalidPtsFallback=" + std::to_string(vpDurationInvalidPtsFallbackDelta)
            + " vpTooLargeFallback=" + std::to_string(vpDurationTooLargeFallbackDelta)
            + " vpDuration>=16.7ms=" + std::to_string(vpDurationLongIntervalDelta)
            + " vpDurationMaxMs=" + std::to_string(
                    static_cast<double>(vpDurationMaxNanos) / 1'000'000.0)
            + " queuePicture=" + std::to_string(queuePictureCallDelta)
            + " queuePtsMs=" + std::to_string(meanQueuePicturePtsMs)
            + " queuePts>=16.7ms=" + std::to_string(queuePictureLongPtsIntervalDelta)
            + " queuePtsMaxMs=" + std::to_string(
                    static_cast<double>(queuePictureMaxPtsIntervalNanos) / 1'000'000.0)
            + " queueDurationMs=" + std::to_string(meanQueuePictureDurationMs)
            + " queueDuration>=16.7ms=" + std::to_string(queuePictureLongDurationDelta)
            + " decoderCalls=" + std::to_string(decoderDecodeCallDelta)
            + " decoderFrames=" + std::to_string(decoderDecodedFrameDelta)
            + " decoderNoFrame=" + std::to_string(decoderNoFrameDelta)
            + " decoderErrors=" + std::to_string(decoderErrorDelta)
            + " videoDropChecks=" + std::to_string(videoDropCheckDelta)
            + " videoDropRequested=" + std::to_string(videoDropRequestedDelta)
            + " videoDropBypassedCalls=" + std::to_string(videoDropBypassedCallsDelta)
            + " ijkEarlyUsReads=" + std::to_string(ijkEarlyUsReadCallsDelta)
            + " ijkEarlyUsSamples=" + std::to_string(ijkEarlyUsSampleDelta)
            + " ijkEarlyUsMeanMs=" + std::to_string(meanIjkEarlyUsMs)
            + " ijkEarlyUsMostLateMs=" + std::to_string(
                    static_cast<double>(ijkEarlyUsMostLateNanos) / 1'000'000.0)
            + " videoDropLateMs=" + std::to_string(meanVideoDropLateMs)
            + " videoDropMostLateMs=" + std::to_string(
                    static_cast<double>(videoDropMostLateNanos) / 1'000'000.0)
            + " videoDropFilterDelayMs=" + std::to_string(meanVideoDropFilterDelayMs)
            + " videoDropGraceKept=" + std::to_string(videoDropGraceKeptDelta)
            + " display=" + std::to_string(displayDelta)
            + " waits=" + std::to_string(waitDelta)
            + " requestedWaitMs=" + std::to_string(meanRequestedWaitMs)
            + " actualWaitMs=" + std::to_string(meanActualWaitMs)
            + " waitOverrunMs=" + std::to_string(meanWaitOverrunMs)
            + " wait>=10ms=" + std::to_string(waits10msDelta)
            + " wait>8.333ms=" + std::to_string(waitsOverNominalDelta)
            + " wait>=16.7ms=" + std::to_string(longWaitDelta)
            + " waitMaxMs=" + std::to_string(
                    static_cast<double>(maxWaitNanos) / 1'000'000.0)
            + " display>=16.7ms=" + std::to_string(longIntervalDelta)
            + " maxDisplayMs=" + std::to_string(maxDisplayIntervalMs)
            + " display>=10ms=" + std::to_string(displayIntervals10msDelta)
            + " display>=11.765ms=" + std::to_string(displayIntervals85fpsDelta)
            + " display>=12.5ms=" + std::to_string(displayIntervals80fpsDelta)
            + " display>8.333ms=" + std::to_string(displayIntervalsOverNominalDelta)
            + " displayExcessMs=" + std::to_string(meanDisplayExcessMs)
            + " displaySmallMaxMs=" + std::to_string(
                    static_cast<double>(maxSmallDisplayIntervalNanos) / 1'000'000.0)
            + " displayCallMs=" + std::to_string(meanDisplayCallMs)
            + " displayCall>=16.7ms=" + std::to_string(displayCallLongIntervalDelta)
            + " displayCallMaxMs=" + std::to_string(
                    static_cast<double>(maxDisplayCallNanos) / 1'000'000.0));
}

// Mirrors `is->frame_timer += delay` (plus IJK's >100 ms resync) after a display or drop.
void commitMedia3Frame(SchedulerState& S, uint64_t now);

void advanceFrameTimer(SchedulerState& S, uint64_t now, bool fromDisplay) {
    if (fromDisplay) {
        // Recorded even before the mirror is valid: scheduling only starts once the Java
        // hook has proven that it sees this codec's displays.
        S.lastNoteNanos.store(now, std::memory_order_release);
    }
    if (!S.frameTimerValid.load(std::memory_order_acquire)) {
        return;
    }
    const uint64_t delay = S.lastReturnedDelayNanos.load(std::memory_order_acquire);
    uint64_t estimate = S.frameTimerEstNanos.load(std::memory_order_acquire) + delay;
    if (fromDisplay && estimate > now) {
        // IJK only displays once `time >= frame_timer + delay`, so the real frame_timer can
        // never be later than the display call.  This clamps away any advance we credited
        // that IJK did not make (e.g. a drop checked before its frame_timer update), which
        // otherwise schedules the following frames a slot early and collides them.
        estimate = now;
        gScheduleClamps.fetch_add(1, std::memory_order_relaxed);
    }
    if (delay > 0 && now > estimate && now - estimate > kScheduleResyncNanos) {
        estimate = now;
        gScheduleResyncs.fetch_add(1, std::memory_order_relaxed);
    }
    S.frameTimerEstNanos.store(estimate, std::memory_order_release);
    if (fromDisplay && gScheduleGridMode.load(std::memory_order_acquire) == 4) {
        commitMedia3Frame(S, now);
    }
    if (fromDisplay) {
        // Only a displayed frame consumes its slot; a dropped frame leaves lastTargetTick
        // alone so the lag behind the ideal timeline actually shrinks (advancing it here
        // kept lag >= 2 and dropped every following frame).
        S.lastTargetTickNanos.store(S.pendingTargetTickNanos.load(std::memory_order_acquire),
                std::memory_order_release);
    }
    S.idealPrevNanos.store(S.idealNextNanos.load(std::memory_order_relaxed),
            std::memory_order_relaxed);
    if (fromDisplay) {
        gScheduleDisplayNotes.fetch_add(1, std::memory_order_relaxed);
        S.notes.fetch_add(1, std::memory_order_relaxed);
        // value: how far the real wake-up is behind the reproduced frame_timer (expect ~0-1 ms
        // for an on-time frame); auxiliary: the slot this frame was aimed at.
        recordRawSample(RawSampleKind::kNativeDisplayNote, now,
                now > estimate ? now - estimate : 0,
                S.lastTargetTickNanos.load(std::memory_order_acquire));
    } else {
        gScheduleDropAdvances.fetch_add(1, std::memory_order_relaxed);
    }
}

// Returns the delay (seconds) to hand back to IJK, or a negative value when scheduling is
// not possible and the caller should return its own delay.

// Media3-mode scheduling (grid mode 4): MediaCodecVideoRenderer + VideoFrameReleaseControl +
// VideoFrameReleaseHelper applied to IJK's refresh loop.
//
//   unadjusted  = PTS timeline (IJK's display time for this frame)
//   adjusted    = lastAdjusted + frameDuration * n   when the estimator is synced and the
//                 result is within 20 ms of unadjusted (adjustReleaseTime)
//   releaseTs   = closestVsync(adjusted) - 80 % of a period
//   release     = 45 ms before releaseTs (Media3: first 10 ms poll with earlyUs <= 50 ms)
//
// The returned delay makes IJK's frame_timer + delay land on that release time; the Java
// hook then calls releaseOutputBuffer(index, releaseTs) and SurfaceFlinger holds the buffer
// until its slot, so the call phase itself no longer decides the slot.
double scheduleMedia3(SchedulerState& S, uint64_t period, uint64_t vsyncAnchor, uint64_t estimate,
                      uint64_t baseDelay, uint64_t now) {
    if (S.media3ResetRequested.exchange(false, std::memory_order_acq_rel)) {
        S.media3Estimator.reset();
        S.media3PresentationNs = 0;
        S.media3IdealValid = false;
        S.media3HaveLastAdjusted = false;
        S.media3LastReleaseTsNs = 0;
        S.media3PendingValid = false;
        S.media3LatencyOffsetNs = 0;
        S.media3LateStreak = 0;
        S.media3FramesSinceResync = 0;
    }
    // Unadjusted release time: the PTS timeline, anchored to IJK's own display time when it
    // (re)starts and advanced by each displayed frame's PTS duration afterwards.
    const uint64_t ijkIntended = estimate + baseDelay;
    uint64_t ideal = S.media3CommittedIdealNs + baseDelay;
    bool resync = false;
    const uint64_t resyncBand = period * kScheduleIdealResyncPeriods;
    // A timeline persistently behind the actual refresh also resyncs, not only one beyond the
    // 60-period band: measured 2026-10-26, a start left it 200-370 ms behind for good (every
    // timestamp expired, the lead lock pinned at its cap, frame_timer re-anchors every frame).
    // Media3 cannot get there because it derives every release time from the current clock.
    if (S.media3IdealValid && ideal + kMedia3BehindResyncNanos < now) {
        S.media3BehindStreak++;
    } else {
        S.media3BehindStreak = 0;
    }
    const bool behindResync = S.media3BehindStreak >= kMedia3BehindResyncFrames;
    if (!S.media3IdealValid || behindResync
            || ideal + resyncBand < now || ideal > now + resyncBand + kMedia3MaxEarlyNanos) {
        S.media3BehindStreak = 0;
        // The frame_timer mirror can be stale by the same amount, so a behind-resync starts
        // from the current time at the earliest.
        ideal = behindResync ? std::max(ijkIntended, now) : ijkIntended;
        resync = true;
    }

    // adjustReleaseTime(): smooth to the fixed frame rate.  Frames IJK dropped show up as a
    // longer PTS step, so the frame count since the last adjusted one is the rounded ratio.
    // Media3 estimates the frame rate on content time (frame presentation timestamps) and
    // divides by the playback speed only when adjusting: candidate = lastAdjusted +
    // frameDuration * n / speed.  Feeding the estimator rate-scaled durations doubled the
    // millisecond PTS rounding at 0.5x (16/17 ms -> 32/34 ms, past its 1 ms matching tolerance),
    // so it never synced and every frame was snapped from the raw timeline: 3/5-VSync pairs
    // instead of a steady 4 for 60 fps content at 0.5x.
    const double speed = gPlaybackRateUnity.load(std::memory_order_acquire)
            ? 1.0 : gPlaybackRate.load(std::memory_order_acquire);
    const int64_t contentDurationNs = S.media3Estimator.frameDurationNs();
    const int64_t frameDurationNs = contentDurationNs > 0 && speed > 0.0
            ? static_cast<int64_t>(std::llround(static_cast<double>(contentDurationNs) / speed))
            : 0;
    gMedia3FrameDurationNs.store(static_cast<uint64_t>(contentDurationNs), std::memory_order_relaxed);
    int64_t adjusted = static_cast<int64_t>(ideal);
    bool adjustedFromCadence = false;
    if (!resync && S.media3HaveLastAdjusted && frameDurationNs > 0) {
        const int64_t frames = std::max<int64_t>(1,
                (static_cast<int64_t>(baseDelay) + frameDurationNs / 2) / frameDurationNs);
        const int64_t candidate = S.media3LastAdjustedReleaseNs + frameDurationNs * frames;
        if (std::llabs(static_cast<int64_t>(ideal) - candidate) <= kMedia3MaxAllowedAdjustmentNanos) {
            adjusted = candidate;
            adjustedFromCadence = true;
        }
    }

    // closestVsync() - vsyncOffset.
    const int64_t p = static_cast<int64_t>(period);
    const int64_t anchorSigned = static_cast<int64_t>(vsyncAnchor);
    const int64_t count = (adjusted - anchorSigned) / p;
    int64_t snapped = anchorSigned + p * count;
    int64_t before, after;
    if (adjusted <= snapped) { before = snapped - p; after = snapped; }
    else { before = snapped; after = snapped + p; }
    snapped = (after - adjusted) < (adjusted - before) ? after : before;
    uint64_t releaseTs = static_cast<uint64_t>(snapped)
            - period * kMedia3VsyncOffsetPercent / 100;
    // Off 1x the offset is part of the timeline itself (as validated on 4K120 at 2x: 120.0
    // renders/s, 2 holds / 8 s): call time, same-VSync skip and stamp all move together.  The
    // stamp-only lock below is for 1x; off 1x, moving only part of it produced duplicate stamps
    // and runs of unskipped frames (172 renders/s, 112 equal stamps / 8 s).
    const bool unityRate = gPlaybackRateUnity.load(std::memory_order_acquire);
    const int64_t timelineOffset = !unityRate && S.media3LatencyOffsetNs > 0
            ? S.media3LatencyOffsetNs : 0;
    releaseTs += static_cast<uint64_t>(timelineOffset);
    // Hysteresis at the beat crossing.  closestVsync() snaps against the latest sampled VSync,
    // whose timestamp jitters a little; while the adjusted time sits near the midpoint between
    // two slots, consecutive frames flipped between them.  A flip later is a two-slot step (a
    // held frame), a flip back is pushed to the next slot below, so one crossing produced
    // several holds (60 fps at 2x: pairs of holds 20-60 ms apart, 16 / 8 s against a ~7 beat).
    // Take the second slot only once the adjusted time is clearly past the midpoint.
    bool hysteresisHeld = false;
    if (!resync && S.media3LastReleaseTsNs != 0) {
        const int64_t last = static_cast<int64_t>(S.media3LastReleaseTsNs);
        const int64_t step = static_cast<int64_t>(releaseTs) - last;
        // Slots one frame normally takes: 1 for 120 fps, 2 for 60 fps at 1x, 4 at 0.5x.
        const int64_t frameNs = frameDurationNs > 0 ? frameDurationNs
                : static_cast<int64_t>(baseDelay);
        const int64_t expected = std::max<int64_t>(1, (frameNs + p / 2) / p);
        if (std::llabs(step - (expected + 1) * p) < p / 4) {
            const int64_t adjustedRelease = adjusted
                    - static_cast<int64_t>(period * kMedia3VsyncOffsetPercent / 100)
                    + timelineOffset;
            const int64_t margin = std::min<int64_t>(kMedia3SnapHysteresisNanos, p / 4);
            if (adjustedRelease < last + expected * p + p / 2 + margin) {
                releaseTs = static_cast<uint64_t>(last + expected * p);
                hysteresisHeld = true;
            }
        }
    }
    // Media3 skips a frame whose release time equals the previous one (content faster than
    // the display).  IJK cannot skip from here, so the frame takes the next VSync instead;
    // the fps bypass keeps genuinely faster content away from this path.
    bool sameVsync = false;
    bool skip = false;
    if (!resync && S.media3LastReleaseTsNs != 0 && releaseTs <= S.media3LastReleaseTsNs) {
        if (gMedia3SkipSameVsync.load(std::memory_order_relaxed)
                && baseDelay * 100 < period * 85) {
            // Media3: skip the frame, the previous one already owns this VSync.
            releaseTs = S.media3LastReleaseTsNs;
            skip = true;
        } else {
            releaseTs = S.media3LastReleaseTsNs + period;
            sameVsync = true;
        }
    }

    // Release 45 ms before the timestamp; a frame already inside that window goes now.
    // Off 1x the lock offset also moves the call time (decoder-bound content such as 4K120 at
    // 2x: frames come just in time, and with the call time already past IJK released them in
    // decoder bursts, 160 renders/s instead of 120 after the same-VSync skip).  At 1x only the
    // stamp moves, otherwise IJK's release would follow the call and the lead chase itself.
    const uint64_t callTime = releaseTs > static_cast<uint64_t>(kMedia3ReleaseLeadNanos)
            ? releaseTs - kMedia3ReleaseLeadNanos : now;
    uint64_t delay = callTime > estimate ? callTime - estimate : 0;
    if (delay > baseDelay + period) {
        delay = baseDelay + period;
        gScheduleDelayCapped.fetch_add(1, std::memory_order_relaxed);
    }
    // A zero delay can lock playback up.  IJK only re-anchors frame_timer to the current time when
    // `delay > 0 && time - frame_timer > AV_SYNC_THRESHOLD_MAX`; with 0 it neither advances nor
    // re-anchors, every following frame is "late" again and gets 0 again, and playback falls
    // into a free-running state paced only by the decoder (seen after 0.5x -> 1.0x: every
    // release timestamp ~330 ms in the past, ~100 fps).  1 us still displays immediately.
    // Only when the frame_timer mirror is already more than AV_SYNC_THRESHOLD_MAX behind:
    // that is the stuck state, and the positive delay lets IJK and the mirror re-anchor.
    if (delay == 0 && now > estimate && now - estimate > kScheduleResyncNanos) {
        delay = kMedia3MinDelayNanos;
        gMedia3StuckReanchors.fetch_add(1, std::memory_order_relaxed);
    }

    // Pending until this frame is displayed (compute_target_delay may run again for it).
    if (!S.media3PendingValid || S.media3PendingTsNs != releaseTs) {
        if (adjustedFromCadence) gMedia3Adjusted.fetch_add(1, std::memory_order_relaxed);
        else if (S.media3HaveLastAdjusted && !resync) {
            gMedia3AdjustmentResets.fetch_add(1, std::memory_order_relaxed);
            if (frameDurationNs <= 0) {
                gMedia3ResetsUnsynced.fetch_add(1, std::memory_order_relaxed);
            } else {
                gMedia3ResetsOverLimit.fetch_add(1, std::memory_order_relaxed);
                const int64_t frames = std::max<int64_t>(1,
                        (static_cast<int64_t>(baseDelay) + frameDurationNs / 2) / frameDurationNs);
                gMedia3ResetLastDiffUs.store((static_cast<int64_t>(ideal)
                        - (S.media3LastAdjustedReleaseNs + frameDurationNs * frames)) / 1000,
                        std::memory_order_relaxed);
                gMedia3ResetLastBaseUs.store(static_cast<int64_t>(baseDelay) / 1000,
                        std::memory_order_relaxed);
            }
        }
        if (sameVsync) gMedia3SameVsync.fetch_add(1, std::memory_order_relaxed);
        if (hysteresisHeld) gMedia3SnapHysteresis.fetch_add(1, std::memory_order_relaxed);
    }
    S.media3PendingValid = true;
    S.media3PendingResync = resync;
    S.media3PendingIdealNs = ideal;
    S.media3PendingAdjustedNs = adjusted;
    S.media3PendingTsNs = releaseTs;
    S.media3PendingIsSkip = skip;
    // The lead lock (commitMedia3Frame) moves only the timestamp handed to SurfaceFlinger, not
    // the call time: moving the call would move IJK's release with it and the lead would chase
    // itself (a one-way version of this reached its cap and stayed bad).
    const uint64_t stampTs = unityRate ? static_cast<uint64_t>(
            static_cast<int64_t>(releaseTs) + S.media3LatencyOffsetNs) : releaseTs;
    S.media3PendingStampNs = stampTs;
    S.media3PendingBaseDelayNs = baseDelay;
    S.lastIntendedNanos.store(releaseTs, std::memory_order_relaxed);
    S.lastReturnedDelayNanos.store(delay, std::memory_order_release);
    S.pendingTargetTickNanos.store(releaseTs, std::memory_order_release);
    S.media3PendingSkip.store(skip, std::memory_order_release);
    gMedia3LastScheduledState.store(&S, std::memory_order_release);
    S.media3PendingReleaseTsNs.store(stampTs, std::memory_order_release);
    S.scheduled.fetch_add(1, std::memory_order_relaxed);
    gScheduledFrames.fetch_add(1, std::memory_order_relaxed);
    gMedia3Scheduled.fetch_add(1, std::memory_order_relaxed);
    recordRawSample(RawSampleKind::kNativeSchedule, now, callTime > now ? callTime - now : 0,
            releaseTs);
    return static_cast<double>(delay) / 1e9;
}

// Called once per displayed frame on the refresh thread: the pending values become the
// state the next frame is adjusted against (VideoFrameReleaseHelper.onNextFrame()).
void commitMedia3Frame(SchedulerState& S, uint64_t now) {
    if (!S.media3PendingValid) {
        return;
    }
    S.media3PendingValid = false;
    if (S.media3PendingIsSkip) {
        gMedia3SkipDecisions.fetch_add(1, std::memory_order_relaxed);
    }
    if (S.media3PendingResync) {
        gScheduleIdealResyncs.fetch_add(1, std::memory_order_relaxed);
        S.media3FramesSinceResync = 0;
        S.media3LateStreak = 0;
    } else if (S.media3FramesSinceResync < 1000000) {
        S.media3FramesSinceResync++;
    }
    S.media3CommittedIdealNs = S.media3PendingIdealNs;
    S.media3IdealValid = true;
    S.media3LastAdjustedReleaseNs = S.media3PendingAdjustedNs;
    S.media3HaveLastAdjusted = true;
    S.media3LastReleaseTsNs = S.media3PendingTsNs;
    {
        // Back to content time for the frame-rate estimator (see scheduleMedia3).
        const double speed = gPlaybackRateUnity.load(std::memory_order_acquire)
                ? 1.0 : gPlaybackRate.load(std::memory_order_acquire);
        // Rounded to whole microseconds (the PTS resolution): 16/17 ms through x/1.99*1.99
        // came back a few ns over the estimator's exact 1 ms matching tolerance at 2x.
        S.media3PresentationNs += 1000 * static_cast<int64_t>(std::llround(
                static_cast<double>(S.media3PendingBaseDelayNs) * speed / 1000.0));
    }
    S.media3Estimator.onNextFrame(S.media3PresentationNs);
    const int64_t earlyNs = static_cast<int64_t>(S.media3PendingStampNs) - static_cast<int64_t>(now);
    if (earlyNs < -kMedia3LateDropNanos) {
        gMedia3LateReleases.fetch_add(1, std::memory_order_relaxed);
    }
    // Lock the release timestamp to IJK's actual release, as Media3 does.
    //
    // Media3 releases each frame when it is 40-50 ms early, so the timestamp always leads the
    // release by about that much.  Here the timestamps come from a PTS timeline anchored once
    // at (re)sync, while IJK releases on its own refresh cadence: measured 2026-10-26, the
    // scheduled call time was always already past and the timestamp-minus-release offset was a
    // constant picked at start, -25 ms in one run (timestamps expired: untimed presentation)
    // and +11..17 ms in others (timestamps active with too little margin for the GL redraw:
    // SurfaceFlinger found the buffer's fence unsignaled and dropped one of two frames per
    // VSync, up to ~28 holds/s).  With ~33-43 ms the same content held only on the beat.  So
    // whenever the lead stays off the target, move the whole timeline by whole VSyncs (one
    // held frame when moving later, one dropped frame when moving earlier).
    const uint64_t period = gGridPeriodNanos.load(std::memory_order_acquire);
    if (!S.media3PendingIsSkip && period >= kScheduleMinPeriodNanos
            && period <= kScheduleMaxPeriodNanos && !S.media3PendingResync
            && S.media3FramesSinceResync >= kMedia3LatencySettleFrames) {
        const int64_t p = static_cast<int64_t>(period);
        const int64_t target = p * std::max<int64_t>(2,
                (kMedia3TargetReleaseLeadNanos + p / 2) / p);
        const int64_t deviation = earlyNs - target;
        if (!gPlaybackRateUnity.load(std::memory_order_acquire)) {
            // Off 1x: the validated one-way rule.  Frames that leave less than a period early
            // (decoder-bound, 4K120 at 2x) move the whole timeline later, up to 4 VSyncs.
            if (earlyNs < p) {
                if (++S.media3LateStreak >= kMedia3LateStreakFrames
                        && S.media3LatencyOffsetNs < p * 4) {
                    const int64_t add = std::max<int64_t>(1, (p - earlyNs + p - 1) / p);
                    S.media3LatencyOffsetNs = std::min<int64_t>(p * 4,
                            std::max<int64_t>(0, S.media3LatencyOffsetNs) + add * p);
                    S.media3LateStreak = 0;
                    gMedia3LatencySteps.fetch_add(1, std::memory_order_relaxed);
                    gMedia3LatencyOffsetNs.store(static_cast<uint64_t>(
                            S.media3LatencyOffsetNs + (1LL << 40)), std::memory_order_relaxed);
                }
            } else {
                S.media3LateStreak = 0;
            }
        } else if (std::llabs(deviation) > p * 3 / 4) {
            if (++S.media3LateStreak >= kMedia3LateStreakFrames) {
                int64_t steps = -(deviation >= 0 ? (deviation + p / 2) / p
                                                 : -((-deviation + p / 2) / p));
                const int64_t limit = p * kMedia3MaxLatencyPeriods;
                int64_t next = std::max(-limit, std::min(limit,
                        S.media3LatencyOffsetNs + steps * p));
                steps = (next - S.media3LatencyOffsetNs) / p;
                if (steps != 0) {
                    S.media3LatencyOffsetNs = next;
                    gMedia3LatencySteps.fetch_add(1, std::memory_order_relaxed);
                    gMedia3LatencyOffsetNs.store(static_cast<uint64_t>(
                            S.media3LatencyOffsetNs + (1LL << 40)), std::memory_order_relaxed);
                }
                S.media3LateStreak = 0;
            }
        } else {
            S.media3LateStreak = 0;
        }
    }
}

double scheduleReleaseDelay(void* player, double baseDelaySeconds,
                            double originalDelaySeconds, uint64_t now) {
    SchedulerState& S = schedForPlayer(player);
    S.tid.store(static_cast<pid_t>(syscall(SYS_gettid)), std::memory_order_release);
    if (!gReleaseScheduling.load(std::memory_order_acquire)) {
        return -1.0;
    }
    uint64_t period = gGridPeriodNanos.load(std::memory_order_acquire);
    uint64_t anchor = gGridAnchorNanos.load(std::memory_order_acquire);
    const uint64_t updated = gGridUpdatedNanos.load(std::memory_order_acquire);
    const int gridMode = gScheduleGridMode.load(std::memory_order_acquire);
    bool arrivalGrid = false;
    // Without FrameTimeline data (API < 33 sampler) both modes fall back to the legacy lead.
    if (gridMode == 1) {
        uint64_t vsync = gGridVsyncNanos.load(std::memory_order_acquire);
        uint64_t relayPeriod = 0;
        const uint64_t relayed = relayedAppVsync(now, &relayPeriod);
        // The main process's VSync timeline is authoritative for the external renderers,
        // period included: the service's Choreographer may keep a different render rate.
        if (relayed != 0 && relayPeriod >= kScheduleMinPeriodNanos
                && relayPeriod <= kScheduleMaxPeriodNanos) {
            vsync = relayed;
            period = relayPeriod;
            gRelayVsyncUsed.fetch_add(1, std::memory_order_relaxed);
        }
        if (vsync != 0) {
            // Ticks become the call times whose frame arrives mid-way between two app VSyncs.
            anchor = vsync + period / 2 - kCallToArrivalNanos;
            arrivalGrid = true;
        }
    } else if (gridMode == 2 || gridMode == 3) {
        int64_t compDeadline = 0, compInterval = 0, compLatency = 0;
        const bool haveCompositor = gridMode == 3
                ? relayedCompositorTiming(now, &compDeadline, &compInterval, &compLatency)
                : ownCompositorTiming(now, &compDeadline, &compInterval, &compLatency);
        if (haveCompositor) {
            // SurfaceFlinger's own latch grid for this surface: queue margin before the next
            // composite; the present follows compositeToPresent later.
            period = static_cast<uint64_t>(compInterval);
            const uint64_t margin = std::min(kLatchMarginNanos, period / 2);
            const uint64_t lead = margin + kCallToArrivalNanos
                    + (gridMode == 3 ? kExternalGlDrawNanos : 0)
                    + static_cast<uint64_t>(gAdaptiveLeadNanos.load(std::memory_order_relaxed));
            gLastAnchorLeadNanos.store(lead + static_cast<uint64_t>(compLatency),
                    std::memory_order_relaxed);
            gCompositorLatencyUsed.store(compLatency, std::memory_order_relaxed);
            if (static_cast<uint64_t>(compDeadline) > lead) {
                anchor = static_cast<uint64_t>(compDeadline) - lead;
                arrivalGrid = true;
                gCompositorGridUsed.fetch_add(1, std::memory_order_relaxed);
            }
        } else {
            // Fallback: the expected-present grid of the Choreographer with the measured
            // latch-to-present distance.
            const uint64_t margin = std::min(kLatchMarginNanos, period / 2);
            const uint64_t lead = kLatchToPresentNanos + kLatchCutoffNanos + margin
                    + kCallToArrivalNanos + (gridMode == 3 ? kExternalGlDrawNanos : 0)
                    + static_cast<uint64_t>(gAdaptiveLeadNanos.load(std::memory_order_relaxed));
            gLastAnchorLeadNanos.store(lead, std::memory_order_relaxed);
            if (anchor > lead) {
                anchor -= lead;
                arrivalGrid = true;
            }
        }
    }
    // Content faster than the panel (120 fps on a 60 Hz mode) has to drop frames; that is
    // IJK's job, and the slot scheduler would only push frames later and later.
    if (baseDelaySeconds > 0.0 && baseDelaySeconds * 1e9 < static_cast<double>(period) * 0.85
            && !(gridMode == 4 && gMedia3SkipSameVsync.load(std::memory_order_relaxed))) {
        gScheduleFpsBypassed.fetch_add(1, std::memory_order_relaxed);
        return -1.0;
    }
    if (period < kScheduleMinPeriodNanos || period > kScheduleMaxPeriodNanos || anchor == 0
            || now < updated || now - updated > kScheduleGridMaxAgeNanos
            || !std::isfinite(baseDelaySeconds) || baseDelaySeconds < 0.0) {
        gScheduleGridStale.fetch_add(1, std::memory_order_relaxed);
        return -1.0;
    }
    const uint64_t lastNote = S.lastNoteNanos.load(std::memory_order_acquire);
    if (lastNote == 0 || now < lastNote || now - lastNote > kScheduleNoteMaxAgeNanos) {
        gScheduleNoteStale.fetch_add(1, std::memory_order_relaxed);
        S.staleCalls.fetch_add(1, std::memory_order_relaxed);
        S.frameTimerValid.store(false, std::memory_order_release);
        S.idealValid.store(false, std::memory_order_release);
        S.lastTargetTickNanos.store(0, std::memory_order_release);
        return -1.0;
    }
    uint64_t estimate = S.frameTimerEstNanos.load(std::memory_order_acquire);
    if (!S.frameTimerValid.load(std::memory_order_acquire) || estimate > now) {
        // IJK: `if (isnan(frame_timer) || time < frame_timer) frame_timer = time;`
        estimate = now;
        S.frameTimerValid.store(true, std::memory_order_release);
        S.frameTimerEstNanos.store(estimate, std::memory_order_release);
    }
    const uint64_t baseDelay = static_cast<uint64_t>(std::llround(baseDelaySeconds * 1e9));
    if (gridMode == 4) {
        const uint64_t vsync = gGridVsyncNanos.load(std::memory_order_acquire);
        if (vsync == 0) {
            gScheduleGridStale.fetch_add(1, std::memory_order_relaxed);
            S.media3PendingReleaseTsNs.store(0, std::memory_order_release);
            return -1.0;
        }
        return scheduleMedia3(S, period, vsync, estimate, baseDelay, now);
    }
    if (std::isfinite(originalDelaySeconds) && baseDelaySeconds > 0.0) {
        if (originalDelaySeconds < baseDelaySeconds * 0.5) {
            S.aheadVerdicts.store(0, std::memory_order_relaxed);
            if (kScheduleVerdictCorrections
                    && S.behindVerdicts.fetch_add(1, std::memory_order_relaxed) + 1
                    >= kScheduleVerdictFrames
                    && now - S.lastCorrectionNanos.load(std::memory_order_relaxed)
                            >= kScheduleCorrectionIntervalNanos) {
                S.behindVerdicts.store(0, std::memory_order_relaxed);
                S.offsetNanos.fetch_sub(static_cast<int64_t>(period),
                        std::memory_order_relaxed);
                gScheduleCorrections.fetch_add(1, std::memory_order_relaxed);
                S.lastCorrectionNanos.store(now, std::memory_order_relaxed);
            }
        } else if (originalDelaySeconds > baseDelaySeconds * 1.5) {
            S.behindVerdicts.store(0, std::memory_order_relaxed);
            if (kScheduleVerdictCorrections
                    && S.aheadVerdicts.fetch_add(1, std::memory_order_relaxed) + 1
                    >= kScheduleVerdictFrames
                    && now - S.lastCorrectionNanos.load(std::memory_order_relaxed)
                            >= kScheduleCorrectionIntervalNanos) {
                S.aheadVerdicts.store(0, std::memory_order_relaxed);
                S.offsetNanos.fetch_add(static_cast<int64_t>(period),
                        std::memory_order_relaxed);
                gScheduleCorrections.fetch_add(1, std::memory_order_relaxed);
                S.lastCorrectionNanos.store(now, std::memory_order_relaxed);
            }
        } else {
            S.behindVerdicts.store(0, std::memory_order_relaxed);
            S.aheadVerdicts.store(0, std::memory_order_relaxed);
        }
    }
    const uint64_t ijkIntended = estimate + baseDelay;
    uint64_t ideal = S.idealPrevNanos.load(std::memory_order_relaxed) + baseDelay;
    // The frame_timer mirror follows the grid once we steer it, so "now" is the only honest
    // reference: re-sync only after a stall or seek has left the ideal timeline far from it.
    const uint64_t resyncBand = period * kScheduleIdealResyncPeriods;
    if (!S.idealValid.load(std::memory_order_relaxed)
            || ideal + resyncBand < now || ideal > now + resyncBand + period) {
        ideal = ijkIntended;
        S.idealPrevNanos.store(estimate, std::memory_order_relaxed);
        S.idealValid.store(true, std::memory_order_relaxed);
        // One slot of headroom: decoder hiccups shorter than a period no longer push the
        // schedule into a lagging regime (every entry cost a repeated frame, and the beat
        // only pulled the lag back after ~3.5 s, so the next hiccup repeated the cycle).
        // Video runs one period behind the audio clock, below perception.
        S.offsetNanos.store(static_cast<int64_t>(period) * kScheduleHeadroomPeriods,
                std::memory_order_relaxed);
        gScheduleIdealResyncs.fetch_add(1, std::memory_order_relaxed);
    }
    S.idealNextNanos.store(ideal, std::memory_order_relaxed);
    const int64_t offset = S.offsetNanos.load(std::memory_order_relaxed);
    const int64_t intendedSigned = static_cast<int64_t>(ideal) + offset;
    const uint64_t intended = intendedSigned > 0 ? static_cast<uint64_t>(intendedSigned) : 0;
    S.lastIntendedNanos.store(intended, std::memory_order_relaxed);
    const uint64_t callLead = arrivalGrid ? 0
            : period * gScheduleCallLeadPercent.load(std::memory_order_relaxed) / 100;
    // Nearest expected-presentation tick to the intended call phase, so the video clock stays
    // within +-half a period of the audio clock.  (Frames placed up to half a period late are
    // kept by our is_need_drop_frame() override; Bilibili's own check would drop them.)
    const uint64_t want = intended + callLead;
    uint64_t tick = anchor;
    if (want >= anchor) {
        tick = anchor + ((want - anchor + period / 2) / period) * period;
    } else {
        tick = anchor - ((anchor - want + period / 2) / period) * period;
    }
    // No monotonic-slot rule: when the previous (late) frame was pushed into this frame's
    // slot, this frame still takes its ideal slot and SurfaceFlinger replaces the late one.
    // Shifting instead would move every following frame a slot later and the schedule would
    // run on the grid cadence (+0.24% vs the source) until the beat caught up.
    // Bounded lag: when the previous frame was pushed into this frame's slot, shift this
    // frame one slot later as long as the schedule lags the ideal timeline by less than a
    // period (small decoder hiccups are absorbed without dropping anything).  Once the lag
    // reaches a full period the frame keeps its ideal slot and SurfaceFlinger replaces the
    // late one, so A/V error never exceeds one slot and the schedule never settles on the
    // grid cadence (+0.24% vs the source).
    // Slots stay strictly increasing (a frame can only be shifted later, never replaced after
    // the previous call has gone out).  The resulting lag behind the ideal timeline is
    // recorded; once it reaches two slots the is_need_drop_frame() hook drops the frame so
    // the schedule catches up instead of settling on the grid cadence.
    const uint64_t lastTick = S.lastTargetTickNanos.load(std::memory_order_acquire);
    uint64_t lagSlots = 0;
    if (lastTick != 0 && tick <= lastTick) {
        lagSlots = (lastTick - tick) / period + 1;
        tick = lastTick + period;
        gScheduleSlotShifts.fetch_add(1, std::memory_order_relaxed);
    }
    S.lagSlots.store(lagSlots, std::memory_order_release);
    // A call later than (tick - callLead + 0.5p) misses the slot and would collide with the
    // next frame; push such frames to the next slot instead.
    const uint64_t slack = period * kScheduleSlotSlackPercent / 100;
    bool late = false;
    while (tick - callLead + slack < now + kScheduleSlackMarginNanos) {
        tick += period;
        late = true;
    }
    if (late) {
        gScheduleLateFrames.fetch_add(1, std::memory_order_relaxed);
        S.lateFrames.fetch_add(1, std::memory_order_relaxed);
    }
    S.scheduled.fetch_add(1, std::memory_order_relaxed);
    const uint64_t callTime = tick - callLead;
    uint64_t delay = callTime > estimate ? callTime - estimate : 0;
    if (delay > baseDelay + period) {
        delay = baseDelay + period;
        gScheduleDelayCapped.fetch_add(1, std::memory_order_relaxed);
    }
    S.lastReturnedDelayNanos.store(delay, std::memory_order_release);
    S.pendingTargetTickNanos.store(tick, std::memory_order_release);
    gScheduledFrames.fetch_add(1, std::memory_order_relaxed);
    // value: planned wait from now; auxiliary: target tick.
    recordRawSample(RawSampleKind::kNativeSchedule, now,
            callTime > now ? callTime - now : 0, tick);
    return static_cast<double>(delay) / 1e9;
}

// The IJK refresh (ijk_ff_vout) and decode (ijk_ff_videodec) threads run at nice 0 and lose
// the CPU for 2-6 ms to the app's own dispatcher threads, GC and system daemons; every such
// delay is a missed VSync slot.  Raise them once, on their first pass through our hooks.
constexpr int kPipelineThreadNice = -10;
std::atomic<int> gPipelineThreadsBoosted = 0;
std::atomic<int> gPipelineBoostErrno = 0;

void boostCurrentThreadOnce() {
    thread_local bool done = false;
    if (done) {
        return;
    }
    done = true;
    const pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
    if (setpriority(PRIO_PROCESS, static_cast<id_t>(tid), kPipelineThreadNice) == 0) {
        gPipelineThreadsBoosted.fetch_add(1, std::memory_order_relaxed);
    } else {
        gPipelineBoostErrno.store(errno, std::memory_order_relaxed);
    }
}

extern "C" double observedComputeTargetDelay(void* player, void* state, double inputDelay) {
    const ComputeTargetDelay original = gOriginalComputeTargetDelay.load(
            std::memory_order_acquire);
    if (original == nullptr) {
        return 0.0;
    }
    boostCurrentThreadOnce();
    const double originalDelay = original(player, state, inputDelay);
    const uint64_t inputDelayNanos = std::isfinite(inputDelay) && inputDelay > 0.0
            ? static_cast<uint64_t>(std::llround(inputDelay * 1e9))
            : 0;
    const bool canBypass = gBlockComputeTargetDelay.load(std::memory_order_acquire)
            && std::isfinite(inputDelay) && inputDelay > 0.0;
    const double effectiveDelay = canBypass ? inputDelay : originalDelay;
    const uint64_t originalDelayNanos = std::isfinite(originalDelay) && originalDelay > 0.0
            ? static_cast<uint64_t>(std::llround(originalDelay * 1e9))
            : 0;
    const uint64_t delayNanos = std::isfinite(effectiveDelay) && effectiveDelay > 0.0
            ? static_cast<uint64_t>(std::llround(effectiveDelay * 1e9))
            : 0;
    // Sample the native IJK master clock sparsely so telemetry can be joined to Java
    // MediaCodec events by CLOCK_MONOTONIC without adding a readMasterClock call to every
    // compute_target_delay invocation.
    const uint64_t masterClockSampleOrdinal = gMasterClockSampleCalls.fetch_add(
            1, std::memory_order_relaxed);
    if (!gIjk913GotOnlyProfile.load(std::memory_order_acquire)
            && (masterClockSampleOrdinal & 7U) == 0U) {
        const double masterClock = readMasterClock(state);
        if (std::isfinite(masterClock) && masterClock >= 0.0) {
            recordRawSample(RawSampleKind::kMasterClock, monotonicNanos(),
                    static_cast<uint64_t>(std::llround(masterClock * 1e9)), delayNanos);
        }
    }
    recordRawSample(RawSampleKind::kComputeTarget, monotonicNanos(), delayNanos,
            inputDelayNanos);
    gTargetDelayCalls.fetch_add(1, std::memory_order_relaxed);
    if (std::isfinite(inputDelay) && inputDelay > 0.0) {
        gInputTargetDelayNanos.fetch_add(inputDelayNanos, std::memory_order_relaxed);
    }
    if (std::isfinite(effectiveDelay) && effectiveDelay > 0.0) {
        gTargetDelayNanos.fetch_add(delayNanos, std::memory_order_relaxed);
        if (delayNanos > kNominal120FrameIntervalNanos) {
            gTargetDelaysOverNominal.fetch_add(1, std::memory_order_relaxed);
        }
        if (delayNanos >= kSmallStutter10Nanos) {
            gTargetDelays10ms.fetch_add(1, std::memory_order_relaxed);
        }
        if (delayNanos >= kSmallStutter85Nanos) {
            gTargetDelays85fps.fetch_add(1, std::memory_order_relaxed);
        }
        if (delayNanos >= kLongFrameIntervalNanos) {
            gLongTargetDelays.fetch_add(1, std::memory_order_relaxed);
        }
        if (delayNanos >= kVeryLongFrameIntervalNanos) {
            gVeryLongTargetDelays.fetch_add(1, std::memory_order_relaxed);
            reportRefreshAnomaly("compute_target_delay", delayNanos,
                    std::isfinite(inputDelay) && inputDelay > 0.0
                            ? static_cast<uint64_t>(std::llround(inputDelay * 1e9))
                            : 0,
                    delayNanos);
        }
        updateMaximum(&gMaxTargetDelayNanos, delayNanos);
    }
    if (std::isfinite(inputDelay) && std::isfinite(originalDelay) && inputDelay > 0.0
            && std::fabs(originalDelay - inputDelay) >= 0.0001) {
        gAdjustedTargetDelays.fetch_add(1, std::memory_order_relaxed);
        const double ratio = originalDelay / inputDelay;
        if (ratio >= 1.9 && ratio <= 2.1) {
            gDoubledTargetDelays.fetch_add(1, std::memory_order_relaxed);
        }
        if (!gIjk913GotOnlyProfile.load(std::memory_order_acquire)
                && (originalDelayNanos >= kSmallStutter10Nanos
                        || (ratio >= 1.9 && ratio <= 2.1))) {
            const double masterClock = readMasterClock(state);
            char message[256];
            if (std::isfinite(masterClock)) {
                std::snprintf(message, sizeof(message),
                        "[DEBUG-COMPUTE-BYPASS] inputMs=%.3f originalMs=%.3f effectiveMs=%.3f ratio=%.3f masterClock=%.6f",
                        inputDelay * 1000.0, originalDelay * 1000.0,
                        effectiveDelay * 1000.0, ratio, masterClock);
            } else {
                std::snprintf(message, sizeof(message),
                        "[DEBUG-COMPUTE-BYPASS] inputMs=%.3f originalMs=%.3f effectiveMs=%.3f ratio=%.3f masterClock=unavailable",
                        inputDelay * 1000.0, originalDelay * 1000.0,
                        effectiveDelay * 1000.0, ratio);
            }
            logInfo(message);
        }
    }
    reportRefreshTelemetry(monotonicNanos());
    if (!gPlaybackRateUnity.load(std::memory_order_acquire)) {
        const double rate = gPlaybackRate.load(std::memory_order_acquire);
        if (std::isfinite(inputDelay) && inputDelay > 0.0 && rate > 0.0) {
            const double scheduledAtRate = scheduleReleaseDelay(player, inputDelay / rate,
                    originalDelay, monotonicNanos());
            if (scheduledAtRate >= 0.0) {
                gRateScheduling.store(true, std::memory_order_release);
                gScheduleRateScaled.fetch_add(1, std::memory_order_relaxed);
                return scheduledAtRate;
            }
        }
        gRateScheduling.store(false, std::memory_order_release);
        // IJK implements playback speed only through this function: the PTS step stays the
        // 1x frame duration and the A/V sync against the rate-scaled audio clock stretches or
        // shrinks the delay.  The PTS-step bypass (block_compute_target_delay) would keep the
        // video at 1x while audio and position run at the new rate, so return IJK's own value.
        gScheduleRateBypassed.fetch_add(1, std::memory_order_relaxed);
        schedForPlayer(player).media3PendingReleaseTsNs.store(0, std::memory_order_release);
        schedForPlayer(player).lastReturnedDelayNanos.store(originalDelayNanos,
                std::memory_order_release);
        return originalDelay;
    }
    // While scheduling, pace on the PTS cadence (inputDelay); the original verdict is only
    // consulted for persistent drift.
    const double scheduled = scheduleReleaseDelay(player,
            std::isfinite(inputDelay) && inputDelay > 0.0 ? inputDelay : effectiveDelay,
            originalDelay, monotonicNanos());
    if (scheduled >= 0.0) {
        return scheduled;
    }
    schedForPlayer(player).media3PendingReleaseTsNs.store(0, std::memory_order_release);
    schedForPlayer(player).lastReturnedDelayNanos.store(delayNanos, std::memory_order_release);
    return effectiveDelay;
}

extern "C" double observedVpDuration(void* player, void* currentFrame, void* nextFrame) {
    const VpDuration original = gOriginalVpDuration.load(std::memory_order_acquire);
    if (original == nullptr) {
        return 0.0;
    }

    // These are exactly the fields read by Bilibili 9.5.0's vp_duration implementation.
    // Recording them before the original call does not alter the frame queue or its timing.
    const int currentSerial = *reinterpret_cast<const int*>(
            static_cast<const uint8_t*>(currentFrame) + kFrameSerialOffset);
    const int nextSerial = *reinterpret_cast<const int*>(
            static_cast<const uint8_t*>(nextFrame) + kFrameSerialOffset);
    const double currentPts = *reinterpret_cast<const double*>(
            static_cast<const uint8_t*>(currentFrame) + kFramePtsOffset);
    const double nextPts = *reinterpret_cast<const double*>(
            static_cast<const uint8_t*>(nextFrame) + kFramePtsOffset);
    const double maxDuration = *reinterpret_cast<const double*>(
            static_cast<const uint8_t*>(player) + kMaxFrameDurationOffset);

    const double duration = original(player, currentFrame, nextFrame);
    const uint64_t observedAt = monotonicNanos();
    gVpDurationCalls.fetch_add(1, std::memory_order_relaxed);
    const double ptsDelta = nextPts - currentPts;
    const bool serialChanged = currentSerial != nextSerial;
    const bool invalidPts = !std::isfinite(ptsDelta) || ptsDelta <= 0.0;
    const bool ptsExceedsMax = !std::isfinite(maxDuration) || ptsDelta > maxDuration;
    const uint64_t returnedDurationNanos = std::isfinite(duration) && duration > 0.0
            ? static_cast<uint64_t>(std::llround(duration * 1e9)) : 0;
    const uint64_t ptsDeltaNanos = std::isfinite(ptsDelta) && ptsDelta > 0.0
            ? static_cast<uint64_t>(std::llround(ptsDelta * 1e9)) : 0;
    const uint64_t maxDurationNanos = std::isfinite(maxDuration) && maxDuration > 0.0
            ? static_cast<uint64_t>(std::llround(maxDuration * 1e9)) : 0;
    const uint64_t fallbackFlags = (serialChanged ? 1ULL : 0ULL)
            | (invalidPts ? 2ULL : 0ULL)
            | (ptsExceedsMax ? 4ULL : 0ULL);
    recordRawSample(RawSampleKind::kVpDuration, observedAt,
            returnedDurationNanos, ptsDeltaNanos);
    recordRawSample(RawSampleKind::kVpDurationContext, observedAt,
            maxDurationNanos, fallbackFlags);
    if (std::isfinite(duration) && duration > 0.0) {
        const uint64_t nanos = static_cast<uint64_t>(std::llround(duration * 1e9));
        gVpDurationNanos.fetch_add(nanos, std::memory_order_relaxed);
        if (nanos >= 16'667'000ULL) {
            gVpDurationLongIntervals.fetch_add(1, std::memory_order_relaxed);
        }
        updateMaximum(&gVpDurationMaxNanos, nanos);
    }

    if (serialChanged) {
        gVpDurationSerialFallback.fetch_add(1, std::memory_order_relaxed);
    } else if (!std::isfinite(ptsDelta) || ptsDelta <= 0.0) {
        gVpDurationInvalidPtsFallback.fetch_add(1, std::memory_order_relaxed);
    } else if (!std::isfinite(maxDuration) || ptsDelta > maxDuration) {
        gVpDurationTooLargeFallback.fetch_add(1, std::memory_order_relaxed);
    } else {
        gVpDurationPtsPath.fetch_add(1, std::memory_order_relaxed);
    }
    reportRefreshTelemetry(monotonicNanos());
    return duration;
}

std::array<uint32_t, kRefreshLoopInstructionCount> vpDurationBranchStub(uintptr_t target) {
    return {kArm64LdrX17Literal8, kArm64BranchX17,
            static_cast<uint32_t>(target), static_cast<uint32_t>(target >> 32U)};
}

std::string installVpDurationProbe() {
    LibrarySearch search;
    dl_iterate_phdr(locateIjkPlayer, &search);
    if (search.base == 0) {
        return "vp_duration probe skipped: libijkplayer.so not loaded";
    }
    const InstructionPointers entry = vpDurationInstructionPointers(search.base);
    if (!instructionsMatch(entry, kExpectedVpDurationPrologue)) {
        return "vp_duration probe skipped: binary signature mismatch words="
                + instructionSignature(entry);
    }

    constexpr size_t kTrampolineBytes = kVpDurationPrologueBytes + 16;
    auto* trampoline = static_cast<uint8_t*>(mmap(nullptr, kTrampolineBytes,
            PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    if (trampoline == MAP_FAILED) {
        return "vp_duration probe skipped: trampoline allocation failed";
    }
    auto* trampolineWords = reinterpret_cast<uint32_t*>(trampoline);
    for (size_t index = 0; index < entry.size(); ++index) {
        trampolineWords[index] = *entry[index];
    }
    const auto returnStub = vpDurationBranchStub(search.base + kVpDurationOffset
            + kVpDurationPrologueBytes);
    std::memcpy(trampoline + kVpDurationPrologueBytes, returnStub.data(),
            returnStub.size() * sizeof(uint32_t));
    __builtin___clear_cache(reinterpret_cast<char*>(trampoline),
            reinterpret_cast<char*>(trampoline + kTrampolineBytes));
    if (mprotect(trampoline, kTrampolineBytes, PROT_READ | PROT_EXEC) != 0) {
        munmap(trampoline, kTrampolineBytes);
        return "vp_duration probe skipped: trampoline execute permission denied";
    }

    const auto entryStub = vpDurationBranchStub(
            reinterpret_cast<uintptr_t>(observedVpDuration));
    std::string writeError;
    // Publish the valid trampoline before the process resumes from the root helper.  The
    // pointer is cleared again if the verified write fails, so the wrapper can never run
    // after a successful branch with a null original target.
    gOriginalVpDuration.store(reinterpret_cast<VpDuration>(trampoline),
            std::memory_order_release);
    if (!patchWithRootHelper(entry, kExpectedVpDurationPrologue, entryStub, &writeError)) {
        gOriginalVpDuration.store(nullptr, std::memory_order_release);
        munmap(trampoline, kTrampolineBytes);
        return "vp_duration probe skipped: " + writeError;
    }
    const uintptr_t entryAddress = reinterpret_cast<uintptr_t>(entry.front());
    __builtin___clear_cache(reinterpret_cast<char*>(entryAddress),
            reinterpret_cast<char*>(entryAddress + kVpDurationPrologueBytes));
    if (!instructionsMatch(entry, entryStub)) {
        gOriginalVpDuration.store(nullptr, std::memory_order_release);
        munmap(trampoline, kTrampolineBytes);
        return "vp_duration probe skipped: post-write verification failed";
    }
    return "vp_duration probe active";
}

extern "C" int observedQueuePicture(void* player, void* frameQueue, void* decodedFrame,
                                     int64_t position, int serial, double pts,
                                     double duration) {
    const QueuePicture original = gOriginalQueuePicture.load(std::memory_order_acquire);
    if (original == nullptr) {
        return -1;
    }

    // ffplay_video_thread computes pts from the decoded AVFrame immediately before this call.
    // The wrapper reads only scalar call arguments, then returns queue_picture's real result.
    // These two native events are observational handoff anchors: they expose the IJK PTS and
    // serial before the external renderer receives the frame, without changing queue behavior.
    const uint64_t now = monotonicNanos();
    gQueuePictureCalls.fetch_add(1, std::memory_order_relaxed);
    if (std::isfinite(duration) && duration > 0.0) {
        const uint64_t durationNanos = static_cast<uint64_t>(std::llround(duration * 1e9));
        gQueuePictureDurationNanos.fetch_add(durationNanos, std::memory_order_relaxed);
        if (durationNanos >= 16'667'000ULL) {
            gQueuePictureLongDurations.fetch_add(1, std::memory_order_relaxed);
        }
    }
    if (std::isfinite(pts)) {
        const int64_t ptsNanos = static_cast<int64_t>(std::llround(pts * 1e9));
        if (ptsNanos >= 0) {
            gLatestQueuePicturePtsNanos.store(ptsNanos, std::memory_order_relaxed);
            gLatestQueuePictureSerial.store(serial, std::memory_order_relaxed);
            gLatestQueuePictureTimestampNanos.store(now, std::memory_order_relaxed);
            recordRawSample(RawSampleKind::kQueuePicture, now,
                    static_cast<uint64_t>(ptsNanos), static_cast<uint64_t>(serial));
        }
        const int previousSerial = gPreviousQueuePictureSerial.exchange(serial,
                std::memory_order_relaxed);
        const int64_t previousPtsNanos = gPreviousQueuePicturePtsNanos.exchange(ptsNanos,
                std::memory_order_relaxed);
        if (previousSerial == serial && previousPtsNanos >= 0 && ptsNanos > previousPtsNanos) {
            const uint64_t intervalNanos = static_cast<uint64_t>(ptsNanos - previousPtsNanos);
            gQueuePicturePtsIntervals.fetch_add(1, std::memory_order_relaxed);
            gQueuePicturePtsNanos.fetch_add(intervalNanos, std::memory_order_relaxed);
            if (intervalNanos >= 16'667'000ULL) {
                gQueuePictureLongPtsIntervals.fetch_add(1, std::memory_order_relaxed);
            }
            updateMaximum(&gQueuePictureMaxPtsIntervalNanos, intervalNanos);
        }
    }
    reportRefreshTelemetry(now);
    return original(player, frameQueue, decodedFrame, position, serial, pts, duration);
}

std::string installQueuePictureProbe() {
    LibrarySearch search;
    dl_iterate_phdr(locateIjkPlayer, &search);
    if (search.base == 0) {
        return "queue_picture probe skipped: libijkplayer.so not loaded";
    }
    const InstructionPointers entry = queuePictureInstructionPointers(search.base);
    if (!instructionsMatch(entry, kExpectedQueuePicturePrologue)) {
        return "queue_picture probe skipped: binary signature mismatch words="
                + instructionSignature(entry);
    }
    constexpr size_t kTrampolineBytes = kVpDurationPrologueBytes + 16;
    auto* trampoline = static_cast<uint8_t*>(mmap(nullptr, kTrampolineBytes,
            PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    if (trampoline == MAP_FAILED) {
        return "queue_picture probe skipped: trampoline allocation failed";
    }
    auto* trampolineWords = reinterpret_cast<uint32_t*>(trampoline);
    for (size_t index = 0; index < entry.size(); ++index) {
        trampolineWords[index] = *entry[index];
    }
    const auto returnStub = vpDurationBranchStub(search.base + kQueuePictureOffset
            + kVpDurationPrologueBytes);
    std::memcpy(trampoline + kVpDurationPrologueBytes, returnStub.data(),
            returnStub.size() * sizeof(uint32_t));
    __builtin___clear_cache(reinterpret_cast<char*>(trampoline),
            reinterpret_cast<char*>(trampoline + kTrampolineBytes));
    if (mprotect(trampoline, kTrampolineBytes, PROT_READ | PROT_EXEC) != 0) {
        munmap(trampoline, kTrampolineBytes);
        return "queue_picture probe skipped: trampoline execute permission denied";
    }
    const auto entryStub = vpDurationBranchStub(
            reinterpret_cast<uintptr_t>(observedQueuePicture));
    gOriginalQueuePicture.store(reinterpret_cast<QueuePicture>(trampoline),
            std::memory_order_release);
    std::string writeError;
    if (!patchWithRootHelper(entry, kExpectedQueuePicturePrologue, entryStub, &writeError)) {
        gOriginalQueuePicture.store(nullptr, std::memory_order_release);
        munmap(trampoline, kTrampolineBytes);
        return "queue_picture probe skipped: " + writeError;
    }
    const uintptr_t entryAddress = reinterpret_cast<uintptr_t>(entry.front());
    __builtin___clear_cache(reinterpret_cast<char*>(entryAddress),
            reinterpret_cast<char*>(entryAddress + kVpDurationPrologueBytes));
    if (!instructionsMatch(entry, entryStub)) {
        gOriginalQueuePicture.store(nullptr, std::memory_order_release);
        munmap(trampoline, kTrampolineBytes);
        return "queue_picture probe skipped: post-write verification failed";
    }
    return "queue_picture probe active";
}

extern "C" void observedSdlDelay(unsigned int milliseconds) {
    const SdlDelay original = gOriginalSdlDelay.load(std::memory_order_acquire);
    if (original == nullptr) {
        return;
    }
    const uintptr_t returnAddress = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    const uintptr_t libraryBase = gIjkPlayerBase.load(std::memory_order_acquire);
    const uint64_t callsiteOffset = libraryBase != 0 && returnAddress >= libraryBase
            ? static_cast<uint64_t>(returnAddress - libraryBase)
            : 0;
    recordRawSample(RawSampleKind::kSdlDelay, monotonicNanos(),
            static_cast<uint64_t>(milliseconds) * 1'000'000ULL, callsiteOffset);
    original(milliseconds);
}

extern "C" int observedSdlCondWaitTimeout(void* condition, void* mutex,
                                           unsigned int milliseconds) {
    const SdlCondWaitTimeout original = gOriginalSdlCondWaitTimeout.load(
            std::memory_order_acquire);
    if (original == nullptr) {
        return 0;
    }
    const uint64_t started = monotonicNanos();
    const int result = original(condition, mutex, milliseconds);
    const uint64_t elapsed = monotonicNanos() - started;
    const uintptr_t returnAddress = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    const uintptr_t libraryBase = gIjkSdlBase.load(std::memory_order_acquire);
    const uint64_t callsiteOffset = libraryBase != 0 && returnAddress >= libraryBase
            ? static_cast<uint64_t>(returnAddress - libraryBase)
            : 0;
    // value_ns is the actual wait; auxiliary_ns is the libijksdl callsite offset. The
    // requested timeout remains recoverable from the exact binary's callsite immediate.
    recordRawSample(RawSampleKind::kSdlCondWait, monotonicNanos(), elapsed, callsiteOffset);
    return result;
}

extern "C" int observedMediaCodecReleaseOutputBuffer(void* codec, int bufferIndex,
                                                       int render) {
    const MediaCodecReleaseOutputBuffer original =
            gOriginalMediaCodecReleaseOutputBuffer.load(std::memory_order_acquire);
    if (original == nullptr) {
        return -1;
    }
    const uintptr_t returnAddress = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    const uintptr_t libraryBase = gIjkSdlBase.load(std::memory_order_acquire);
    const uint64_t callsiteOffset = libraryBase != 0 && returnAddress >= libraryBase
            ? static_cast<uint64_t>(returnAddress - libraryBase)
            : 0;
    // value_ns stores the MediaCodec buffer index. auxiliary_ns stores the libijksdl
    // return-address offset shifted left one bit, with bit 0 equal to render.
    const uint64_t packedCallsiteAndRender = (callsiteOffset << 1U)
            | static_cast<uint64_t>(render & 0x1);
    recordRawSample(RawSampleKind::kMediaCodecRelease, monotonicNanos(),
            static_cast<uint64_t>(bufferIndex), packedCallsiteAndRender);
    return original(codec, bufferIndex, render);
}

extern "C" int observedFrameQueueWaitTimeout(void* queue, int timeout, int abortRequest) {
    const FrameQueueWaitTimeout original = gOriginalFrameQueueWaitTimeout.load(
            std::memory_order_acquire);
    if (original == nullptr) {
        return 0;
    }
    const uint64_t started = monotonicNanos();
    gWaitCalls.fetch_add(1, std::memory_order_relaxed);
    uint64_t requestedNanos = 0;
    if (timeout > 0) {
        // IJK passes microseconds to frame_queue_wait_timeout.
        requestedNanos = static_cast<uint64_t>(timeout) * 1000ULL;
        gRequestedWaitNanos.fetch_add(requestedNanos, std::memory_order_relaxed);
    }
    int effectiveTimeout = timeout;
    if (gFrameQueueWaitRoundUp.load(std::memory_order_acquire) && timeout > 0) {
        const int remainder = timeout % 1000;
        if (remainder != 0 && timeout <= INT_MAX - (1000 - remainder)) {
            effectiveTimeout += 1000 - remainder;
        }
    }
    const int result = original(queue, effectiveTimeout, abortRequest);
    const uint64_t elapsed = monotonicNanos() - started;
    recordRawSample(RawSampleKind::kFrameQueueWait, monotonicNanos(), elapsed, requestedNanos);
    gActualWaitNanos.fetch_add(elapsed, std::memory_order_relaxed);
    if (requestedNanos > 0 && elapsed > requestedNanos) {
        gWaitOverrunNanos.fetch_add(elapsed - requestedNanos, std::memory_order_relaxed);
    }
    if (elapsed >= kLongFrameIntervalNanos) {
        gLongWaits.fetch_add(1, std::memory_order_relaxed);
        reportRefreshAnomaly("frame_queue_wait_timeout", elapsed, requestedNanos, elapsed);
    }
    if (elapsed >= kSmallStutter10Nanos) {
        gWaits10ms.fetch_add(1, std::memory_order_relaxed);
    }
    if (elapsed > kNominal120FrameIntervalNanos) {
        gWaitsOverNominal.fetch_add(1, std::memory_order_relaxed);
    }
    updateMaximum(&gMaxWaitNanos, elapsed);
    reportRefreshTelemetry(monotonicNanos());
    return result;
}

extern "C" void observedVoutDisplayYuvOverlay(void* vout, void* overlay) {
    const VoutDisplayYuvOverlay original = gOriginalVoutDisplayYuvOverlay.load(
            std::memory_order_acquire);
    if (original == nullptr) {
        return;
    }
    const uint64_t traceToken = gNativeDisplayTraceToken.fetch_add(
            1, std::memory_order_relaxed) + 1;
    const int traceSerial = gLatestQueuePictureSerial.load(std::memory_order_relaxed);
    const int64_t tracePtsUs = gLatestQueuePicturePtsNanos.load(
            std::memory_order_relaxed) / 1000;
    char traceName[128];
    std::snprintf(traceName, sizeof(traceName),
            "BiliP2_native_display_%llu_s%d_p%lld",
            static_cast<unsigned long long>(traceToken), traceSerial,
            static_cast<long long>(tracePtsUs));
    const bool traceEnabled = ATrace_isEnabled();
    if (traceEnabled) {
        ATrace_beginSection(traceName);
    }
    char traceCallName[96];
    std::snprintf(traceCallName, sizeof(traceCallName),
            "BiliP2_native_display_call_%llu",
            static_cast<unsigned long long>(traceToken));
    const uint64_t now = monotonicNanos();
    const int64_t latestPtsNanos = gLatestQueuePicturePtsNanos.load(
            std::memory_order_relaxed);
    const uint64_t latestQueueTimestamp = gLatestQueuePictureTimestampNanos.load(
            std::memory_order_relaxed);
    const uint64_t previousDisplayedQueueTimestamp =
            gLastDisplayedQueuePictureTimestampNanos.load(std::memory_order_relaxed);
    if (latestPtsNanos >= 0 && latestQueueTimestamp != 0
            && latestQueueTimestamp > previousDisplayedQueueTimestamp
            && now >= latestQueueTimestamp && now - latestQueueTimestamp <= 100'000'000ULL) {
        recordRawSample(RawSampleKind::kDisplayPicture, now,
                static_cast<uint64_t>(latestPtsNanos), static_cast<uint64_t>(
                        gLatestQueuePictureSerial.load(std::memory_order_relaxed)));
        gLastDisplayedQueuePictureTimestampNanos.store(latestQueueTimestamp,
                std::memory_order_relaxed);
    }
    const uint64_t previous = gLastDisplayNanos.exchange(now, std::memory_order_relaxed);
    if (previous != 0 && now > previous) {
        const uint64_t interval = now - previous;
        if (interval >= kLongFrameIntervalNanos) {
            gLongDisplayIntervals.fetch_add(1, std::memory_order_relaxed);
        }
        recordRawSample(RawSampleKind::kDisplayInterval, now, interval, 0);
        updateMaximum(&gMaxDisplayIntervalNanos, interval);
        if (interval < kAbnormalDisplayIntervalNanos) {
            if (interval > kNominal120FrameIntervalNanos) {
                gDisplayIntervalsOverNominal.fetch_add(1, std::memory_order_relaxed);
                gDisplayOverNominalNanos.fetch_add(
                        interval - kNominal120FrameIntervalNanos,
                        std::memory_order_relaxed);
            }
            if (interval >= kSmallStutter10Nanos) {
                gDisplayIntervals10ms.fetch_add(1, std::memory_order_relaxed);
            }
            if (interval >= kSmallStutter85Nanos) {
                gDisplayIntervals85fps.fetch_add(1, std::memory_order_relaxed);
            }
            if (interval >= kSmallStutter80Nanos) {
                gDisplayIntervals80fps.fetch_add(1, std::memory_order_relaxed);
            }
            updateMaximum(&gMaxSmallDisplayIntervalNanos, interval);
        }
        if (interval >= kAbnormalDisplayIntervalNanos) {
            reportRefreshAnomaly("display_interval", interval, 0, 0);
        }
    }
    gDisplayCalls.fetch_add(1, std::memory_order_relaxed);
    const uint64_t callStarted = monotonicNanos();
    if (traceEnabled) {
        ATrace_beginSection(traceCallName);
    }
    original(vout, overlay);
    if (traceEnabled) {
        ATrace_endSection();
    }
    const uint64_t callElapsed = monotonicNanos() - callStarted;
    recordRawSample(RawSampleKind::kDisplayCall, callStarted, callElapsed, 0);
    gDisplayCallNanos.fetch_add(callElapsed, std::memory_order_relaxed);
    if (callElapsed >= kLongFrameIntervalNanos) {
        gDisplayCallLongIntervals.fetch_add(1, std::memory_order_relaxed);
    }
    updateMaximum(&gMaxDisplayCallNanos, callElapsed);
    if (callElapsed >= kAbnormalDisplayIntervalNanos) {
        reportRefreshAnomaly("SDL_VoutDisplayYUVOverlay", callElapsed, 0, callElapsed);
    }
    if (traceEnabled) {
        ATrace_endSection();
    }
}

extern "C" int observedDecoderDecodeFrame(void* player, void* decoder, void* frame,
                                            void* serialOut) {
    const DecoderDecodeFrame original = gOriginalDecoderDecodeFrame.load(
            std::memory_order_acquire);
    if (original == nullptr) {
        return -1;
    }
    const int result = original(player, decoder, frame, serialOut);
    gDecoderDecodeCalls.fetch_add(1, std::memory_order_relaxed);
    if (result > 0) {
        gDecoderDecodedFrames.fetch_add(1, std::memory_order_relaxed);
    } else if (result == 0) {
        gDecoderNoFrame.fetch_add(1, std::memory_order_relaxed);
    } else {
        gDecoderErrors.fetch_add(1, std::memory_order_relaxed);
    }
    reportRefreshTelemetry(monotonicNanos());
    return result;
}

struct VideoDropTiming {
    bool valid = false;
    int64_t ptsUs = INT64_MIN;
    uint64_t timestampNanos = 0;
    // IJK's earlyUs equivalent: positive means the frame is early, negative means late.
    int64_t earlyUsNanos = 0;
    uint64_t filterDelayNanos = 0;
};

VideoDropTiming readVideoDropTiming(int64_t pts, void* player) {
    VideoDropTiming timing;
    if (player == nullptr || pts == INT64_MIN) {
        return timing;
    }
    auto* state = *reinterpret_cast<void* const*>(
            static_cast<const uint8_t*>(player) + sizeof(void*));
    LibrarySearch library;
    dl_iterate_phdr(locateIjkPlayer, &library);
    auto* stream = state == nullptr ? nullptr : *reinterpret_cast<void* const*>(
            static_cast<const uint8_t*>(state) + kVideoStreamOffset);
    if (library.base == 0 || stream == nullptr) {
        return timing;
    }
    const int timeBaseNumerator = *reinterpret_cast<const int*>(
            static_cast<const uint8_t*>(stream) + kAvStreamTimeBaseNumeratorOffset);
    const int timeBaseDenominator = *reinterpret_cast<const int*>(
            static_cast<const uint8_t*>(stream) + kAvStreamTimeBaseDenominatorOffset);
    const auto masterClock = reinterpret_cast<GetMasterClock>(
            library.base + kGetMasterClockOffset);
    if (timeBaseNumerator <= 0 || timeBaseDenominator <= 0 || masterClock == nullptr) {
        return timing;
    }
    const double frameSeconds = static_cast<double>(pts) * timeBaseNumerator
            / timeBaseDenominator;
    const double latenessSeconds = frameSeconds - masterClock(state);
    const double filterDelaySeconds = *reinterpret_cast<const double*>(
            static_cast<const uint8_t*>(state) + kFrameLastFilterDelayOffset);
    if (!std::isfinite(latenessSeconds) || !std::isfinite(filterDelaySeconds)) {
        return timing;
    }
    timing.valid = true;
    timing.ptsUs = static_cast<int64_t>(std::llround(frameSeconds * 1e6));
    timing.timestampNanos = monotonicNanos();
    timing.earlyUsNanos = static_cast<int64_t>(std::llround(latenessSeconds * 1e9));
    timing.filterDelayNanos = static_cast<uint64_t>(std::llround(
            std::max(0.0, filterDelaySeconds) * 1e9));
    return timing;
}

void storeIjkEarlyUsSample(const VideoDropTiming& timing) {
    std::lock_guard<std::mutex> lock(gIjkEarlyUsMutex);
    const size_t index = (gIjkEarlyUsStart + gIjkEarlyUsCount)
            % kIjkEarlyUsSampleCapacity;
    gIjkEarlyUsRing[index] = {
            timing.timestampNanos, timing.ptsUs, timing.earlyUsNanos, timing.filterDelayNanos};
    if (gIjkEarlyUsCount < kIjkEarlyUsSampleCapacity) {
        ++gIjkEarlyUsCount;
    } else {
        gIjkEarlyUsStart = (gIjkEarlyUsStart + 1) % kIjkEarlyUsSampleCapacity;
    }
}

bool findIjkEarlyUsSample(int64_t ptsUs, IjkEarlyUsSample* result) {
    if (ptsUs == INT64_MIN || result == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> lock(gIjkEarlyUsMutex);
    for (size_t offset = 0; offset < gIjkEarlyUsCount; ++offset) {
        const size_t reverseOffset = gIjkEarlyUsCount - 1 - offset;
        const size_t index = (gIjkEarlyUsStart + reverseOffset)
                % kIjkEarlyUsSampleCapacity;
        const IjkEarlyUsSample& sample = gIjkEarlyUsRing[index];
        const int64_t difference = sample.ptsUs >= ptsUs
                ? sample.ptsUs - ptsUs : ptsUs - sample.ptsUs;
        if (difference <= kIjkEarlyUsPtsToleranceUs) {
            *result = sample;
            return true;
        }
    }
    return false;
}

void recordIjkEarlyUs(const VideoDropTiming& timing) {
    if (!timing.valid) {
        return;
    }
    gIjkEarlyUsSamples.fetch_add(1, std::memory_order_relaxed);
    gIjkEarlyUsSumNanos.fetch_add(timing.earlyUsNanos, std::memory_order_relaxed);
    updateMinimum(&gIjkEarlyUsMostLateNanos, timing.earlyUsNanos);
    storeIjkEarlyUsSample(timing);
    // The event name explicitly carries the unit because RawSample's other events are ns-based.
    recordRawSample(RawSampleKind::kIjkEarlyUs, monotonicNanos(),
            static_cast<uint64_t>(timing.earlyUsNanos), timing.filterDelayNanos);
}

void recordVideoDropTiming(const VideoDropTiming& timing) {
    if (!timing.valid) {
        return;
    }
    gVideoDropTimingSamples.fetch_add(1, std::memory_order_relaxed);
    gVideoDropLatenessNanos.fetch_add(timing.earlyUsNanos, std::memory_order_relaxed);
    gVideoDropFilterDelayNanos.fetch_add(timing.filterDelayNanos,
            std::memory_order_relaxed);
    updateMinimum(&gVideoDropMostLateNanos, timing.earlyUsNanos);
}

extern "C" int observedIsNeedDropFrame(int64_t pts, void* player, void* decoder,
                                        int forceDrop) {
    const IsNeedDropFrame original = gOriginalIsNeedDropFrame.load(std::memory_order_acquire);
    if (original == nullptr) {
        return 0;
    }
    boostCurrentThreadOnce();
    gVideoDropChecks.fetch_add(1, std::memory_order_relaxed);
    if (!gPlaybackRateUnity.load(std::memory_order_acquire)
            && !gRateScheduling.load(std::memory_order_acquire)) {
        // Off 1x and not scheduled (content faster than the panel) IJK keeps up with the rate-scaled audio clock by dropping frames (2x of a
        // 120 fps source is 240 frames/s); blocking drops here would leave the video behind.
        return original(pts, player, decoder, forceDrop);
    }
    if (gIjk913GotOnlyProfile.load(std::memory_order_acquire)) {
        // The 9.5.0 earlyUs fields have not been validated against 9.13.0.
        // Only the ABI-confirmed return-value experiment is safe in this profile.
        if (gBlockIjkFrameDrop.load(std::memory_order_acquire)) {
            gVideoDropBypassedCalls.fetch_add(1, std::memory_order_relaxed);
            return 0;
        }
        SchedulerState& S = schedForPlayer(player);
        if (gReleaseScheduling.load(std::memory_order_acquire)
                && (gPlaybackRateUnity.load(std::memory_order_acquire)
                        || gRateScheduling.load(std::memory_order_acquire))
                && S.frameTimerValid.load(std::memory_order_acquire)) {
            // The scheduler places frames deliberately up to a period from IJK's own
            // timeline; only a frame that is genuinely late (decoder behind) may be dropped.
            if (kScheduleLagDrops && S.lagSlots.load(std::memory_order_acquire) >= 2) {
                // Two or more slots behind the ideal timeline: skip this frame to catch up.
                gScheduleReplacements.fetch_add(1, std::memory_order_relaxed);
                advanceFrameTimer(S, monotonicNanos(), false);
                return 1;
            }
            const uint64_t now = monotonicNanos();
            const uint64_t intended = S.lastIntendedNanos.load(std::memory_order_relaxed);
            const uint64_t period = gGridPeriodNanos.load(std::memory_order_acquire);
            const uint64_t lateness = gScheduleGridMode.load(std::memory_order_acquire) == 4
                    ? static_cast<uint64_t>(kMedia3LateDropNanos)
                    : period * kScheduleDropLatenessPercent / 100;
            if (now <= intended + lateness) {
                gScheduleDropsSuppressed.fetch_add(1, std::memory_order_relaxed);
                return 0;
            }
        }
        const int drop = original(pts, player, decoder, forceDrop);
        if (drop != 0) {
            gVideoDropRequested.fetch_add(1, std::memory_order_relaxed);
            advanceFrameTimer(S, monotonicNanos(), false);
        }
        return drop;
    }
    const VideoDropTiming timing = readVideoDropTiming(pts, player);
    gIjkEarlyUsReadCalls.fetch_add(1, std::memory_order_relaxed);
    recordIjkEarlyUs(timing);
    if (gBlockIjkFrameDrop.load(std::memory_order_acquire)) {
        gVideoDropBypassedCalls.fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    // Preserve a frame that is late by less than one 120 fps interval. This check runs before
    // Bilibili's original helper so it does not increment its internal early-drop counters for
    // a frame that we intentionally keep. Bilibili 9.5.0's fourth argument affects only its
    // post-decision statistics, not whether its function returns a drop.
    if (timing.valid && timing.earlyUsNanos < 0
            && timing.earlyUsNanos >= -static_cast<int64_t>(std::llround(
                    kEarlyDropGraceSeconds * 1e9))) {
        gVideoDropGraceKept.fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    const int drop = original(pts, player, decoder, forceDrop);
    if (drop != 0) {
        gVideoDropRequested.fetch_add(1, std::memory_order_relaxed);
        recordVideoDropTiming(timing);
        advanceFrameTimer(schedForPlayer(player), monotonicNanos(), false);
    }
    return drop;
}

struct RefreshTelemetryHookSearch {
    size_t matchedSlots = 0;
    std::string error;
    bool collectIjk913 = false;
    struct PendingGotHook {
        void** slot;
        void* replacement;
        void* original;
    };
    std::vector<PendingGotHook> pending;
};

bool hookRefreshTelemetryImports(const dl_phdr_info* info, RefreshTelemetryHookSearch* search) {
    if (info->dlpi_name == nullptr) {
        return true;
    }
    const bool isIjkPlayer = std::strstr(info->dlpi_name, "libijkplayer.so") != nullptr;
    const bool isIjkSdl = std::strstr(info->dlpi_name, "libijksdl.so") != nullptr;
    if (!isIjkPlayer && !isIjkSdl) {
        return true;
    }
    const Elf64_Phdr* dynamicProgramHeader = nullptr;
    for (Elf64_Half index = 0; index < info->dlpi_phnum; ++index) {
        if (info->dlpi_phdr[index].p_type == PT_DYNAMIC) {
            dynamicProgramHeader = &info->dlpi_phdr[index];
            break;
        }
    }
    if (dynamicProgramHeader == nullptr) {
        search->error = "IJK library dynamic section unavailable";
        return false;
    }
    const uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    if (isIjkPlayer) {
        gIjkPlayerBase.store(base, std::memory_order_release);
    } else if (isIjkSdl) {
        gIjkSdlBase.store(base, std::memory_order_release);
    }
    const auto* dynamic = reinterpret_cast<const Elf64_Dyn*>(base + dynamicProgramHeader->p_vaddr);
    const Elf64_Sym* symbols = nullptr;
    const char* strings = nullptr;
    const Elf64_Rela* jumpRelocations = nullptr;
    size_t jumpRelocationBytes = 0;
    for (const Elf64_Dyn* entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        if (entry->d_tag == DT_SYMTAB) {
            symbols = reinterpret_cast<const Elf64_Sym*>(dynamicPointer(base, entry->d_un.d_ptr));
        } else if (entry->d_tag == DT_STRTAB) {
            strings = reinterpret_cast<const char*>(dynamicPointer(base, entry->d_un.d_ptr));
        } else if (entry->d_tag == DT_JMPREL) {
            jumpRelocations = reinterpret_cast<const Elf64_Rela*>(
                    dynamicPointer(base, entry->d_un.d_ptr));
        } else if (entry->d_tag == DT_PLTRELSZ) {
            jumpRelocationBytes = static_cast<size_t>(entry->d_un.d_val);
        }
    }
    if (symbols == nullptr || strings == nullptr || jumpRelocations == nullptr) {
        search->error = "IJK library PLT metadata unavailable";
        return false;
    }
    const size_t relocationCount = jumpRelocationBytes / sizeof(Elf64_Rela);
    for (size_t index = 0; index < relocationCount; ++index) {
        const Elf64_Rela& relocation = jumpRelocations[index];
        if (ELF64_R_TYPE(relocation.r_info) != R_AARCH64_JUMP_SLOT) {
            continue;
        }
        const char* symbol = strings + symbols[ELF64_R_SYM(relocation.r_info)].st_name;
        enum class HookKind {
            kComputeTargetDelay,
            kFrameQueueWaitTimeout,
            kVoutDisplay,
            kDecoderDecodeFrame,
            kIsNeedDropFrame,
            kSdlDelay,
            kSdlCondWait,
            kMediaCodecRelease,
        };
        void* replacement = nullptr;
        HookKind hookKind = HookKind::kComputeTargetDelay;
        if (isIjkPlayer && std::strcmp(symbol, "compute_target_delay") == 0) {
            replacement = reinterpret_cast<void*>(observedComputeTargetDelay);
            hookKind = HookKind::kComputeTargetDelay;
        } else if (isIjkPlayer && std::strcmp(symbol, "frame_queue_wait_timeout") == 0) {
            replacement = reinterpret_cast<void*>(observedFrameQueueWaitTimeout);
            hookKind = HookKind::kFrameQueueWaitTimeout;
        } else if (isIjkPlayer && std::strcmp(symbol, "SDL_VoutDisplayYUVOverlay") == 0) {
            replacement = reinterpret_cast<void*>(observedVoutDisplayYuvOverlay);
            hookKind = HookKind::kVoutDisplay;
        } else if (isIjkPlayer && std::strcmp(symbol, "decoder_decode_frame") == 0) {
            replacement = reinterpret_cast<void*>(observedDecoderDecodeFrame);
            hookKind = HookKind::kDecoderDecodeFrame;
        } else if (isIjkPlayer && std::strcmp(symbol, "is_need_drop_frame") == 0) {
            replacement = reinterpret_cast<void*>(observedIsNeedDropFrame);
            hookKind = HookKind::kIsNeedDropFrame;
        } else if (isIjkPlayer && std::strcmp(symbol, "SDL_Delay") == 0) {
            replacement = reinterpret_cast<void*>(observedSdlDelay);
            hookKind = HookKind::kSdlDelay;
        } else if (isIjkSdl && std::strcmp(symbol, "SDL_CondWaitTimeout") == 0) {
            replacement = reinterpret_cast<void*>(observedSdlCondWaitTimeout);
            hookKind = HookKind::kSdlCondWait;
        } else if (isIjkSdl && std::strcmp(symbol, "SDL_AMediaCodec_releaseOutputBuffer") == 0) {
            replacement = reinterpret_cast<void*>(observedMediaCodecReleaseOutputBuffer);
            hookKind = HookKind::kMediaCodecRelease;
        } else {
            continue;
        }
        if (search->collectIjk913 && hookKind != HookKind::kComputeTargetDelay
                && hookKind != HookKind::kFrameQueueWaitTimeout
                && hookKind != HookKind::kIsNeedDropFrame) {
            continue;
        }
        auto** slot = reinterpret_cast<void**>(base + relocation.r_offset);
        if (search->collectIjk913) {
            const uintptr_t expected = base + (hookKind == HookKind::kComputeTargetDelay
                    ? 0x456cc : hookKind == HookKind::kFrameQueueWaitTimeout
                            ? 0x478a8 : 0x4a740);
            if (reinterpret_cast<uintptr_t>(*slot) != expected) {
                search->error = "IJK 9.13.0 GOT target differs from verified function";
                return false;
            }
            search->pending.push_back({slot, replacement, *slot});
            ++search->matchedSlots;
            continue;
        }
        void* original = nullptr;
        if (!replaceGotSlot(slot, replacement, &original, &search->error)) {
            return false;
        }
        if (original != nullptr) {
            if (hookKind == HookKind::kComputeTargetDelay) {
                ComputeTargetDelay expected = nullptr;
                gOriginalComputeTargetDelay.compare_exchange_strong(expected,
                        reinterpret_cast<ComputeTargetDelay>(original));
            } else if (hookKind == HookKind::kFrameQueueWaitTimeout) {
                FrameQueueWaitTimeout expected = nullptr;
                gOriginalFrameQueueWaitTimeout.compare_exchange_strong(expected,
                        reinterpret_cast<FrameQueueWaitTimeout>(original));
            } else if (hookKind == HookKind::kVoutDisplay) {
                VoutDisplayYuvOverlay expected = nullptr;
                gOriginalVoutDisplayYuvOverlay.compare_exchange_strong(expected,
                        reinterpret_cast<VoutDisplayYuvOverlay>(original));
            } else if (hookKind == HookKind::kDecoderDecodeFrame) {
                DecoderDecodeFrame expected = nullptr;
                gOriginalDecoderDecodeFrame.compare_exchange_strong(expected,
                        reinterpret_cast<DecoderDecodeFrame>(original));
            } else if (hookKind == HookKind::kIsNeedDropFrame) {
                IsNeedDropFrame expected = nullptr;
                gOriginalIsNeedDropFrame.compare_exchange_strong(expected,
                        reinterpret_cast<IsNeedDropFrame>(original));
            } else if (hookKind == HookKind::kSdlDelay) {
                SdlDelay expected = nullptr;
                gOriginalSdlDelay.compare_exchange_strong(expected,
                        reinterpret_cast<SdlDelay>(original));
            } else if (hookKind == HookKind::kSdlCondWait) {
                SdlCondWaitTimeout expected = nullptr;
                gOriginalSdlCondWaitTimeout.compare_exchange_strong(expected,
                        reinterpret_cast<SdlCondWaitTimeout>(original));
            } else if (hookKind == HookKind::kMediaCodecRelease) {
                MediaCodecReleaseOutputBuffer expected = nullptr;
                gOriginalMediaCodecReleaseOutputBuffer.compare_exchange_strong(expected,
                        reinterpret_cast<MediaCodecReleaseOutputBuffer>(original));
            }
        }
        ++search->matchedSlots;
    }
    return true;
}

int installRefreshTelemetryHook(dl_phdr_info* info, size_t, void* data) {
    auto* search = static_cast<RefreshTelemetryHookSearch*>(data);
    return hookRefreshTelemetryImports(info, search) ? 0 : 1;
}

std::string startRefreshTelemetry(const std::string& rawSamplePath) {
    std::lock_guard<std::mutex> lock(gRefreshTelemetryHookMutex);
    if (gRefreshTelemetryStarted.load()) {
        return "IJK refresh telemetry already active";
    }
    // Verify every fixed-offset probe before changing any GOT entry. Newer IJK binaries
    // move these functions; an early return after GOT patching would leave wrappers active.
    LibrarySearch binary;
    dl_iterate_phdr(locateIjkPlayer, &binary);
    if (binary.base == 0) {
        return "IJK refresh telemetry skipped: libijkplayer.so not loaded";
    }
    if (binary.ijk913BuildId) {
        if (!ijk913InstructionsMatch(binary.base)) {
            return "IJK refresh telemetry skipped: 9.13.0 instruction signature mismatch";
        }
        RefreshTelemetryHookSearch search;
        search.collectIjk913 = true;
        dl_iterate_phdr(installRefreshTelemetryHook, &search);
        if (!search.error.empty()) return "IJK refresh telemetry skipped: " + search.error;
        if (search.pending.size() != 3) {
            return "IJK refresh telemetry skipped: 9.13.0 expected three verified GOT slots, found="
                    + std::to_string(search.pending.size());
        }
        for (const auto& pending : search.pending) {
            if (pending.replacement == reinterpret_cast<void*>(observedComputeTargetDelay)) {
                gOriginalComputeTargetDelay.store(
                        reinterpret_cast<ComputeTargetDelay>(pending.original));
            } else if (pending.replacement == reinterpret_cast<void*>(observedFrameQueueWaitTimeout)) {
                gOriginalFrameQueueWaitTimeout.store(
                        reinterpret_cast<FrameQueueWaitTimeout>(pending.original));
            } else if (pending.replacement == reinterpret_cast<void*>(observedIsNeedDropFrame)) {
                gOriginalIsNeedDropFrame.store(
                        reinterpret_cast<IsNeedDropFrame>(pending.original));
            }
        }
        gIjk913GotOnlyProfile.store(true, std::memory_order_release);
        for (const auto& pending : search.pending) {
            std::string error;
            if (!replaceGotSlot(pending.slot, pending.replacement, nullptr, &error)) {
                bool rollbackOk = true;
                for (const auto& undo : search.pending) {
                    std::string rollbackError;
                    if (!replaceGotSlot(undo.slot, undo.original, nullptr, &rollbackError)) {
                        rollbackOk = false;
                    }
                }
                return "IJK refresh telemetry skipped: 9.13.0 GOT install failed: " + error
                        + (rollbackOk ? "; rolled back" : "; rollback incomplete");
            }
        }
        const bool rawSamplesActive = startRawSampleWriter(rawSamplePath);
        gRefreshTelemetryStarted.store(true, std::memory_order_release);
        logInfo("IJK 9.13.0 GOT-only telemetry active: compute_target_delay, "
                "frame_queue_wait_timeout, is_need_drop_frame; old struct/inline probes disabled");
        return "IJK refresh telemetry active; profile=9.13.0-got-only rawSamples="
                + std::string(rawSamplesActive ? "active" : "unavailable");
    }
    if (!instructionsMatch(vpDurationInstructionPointers(binary.base),
            kExpectedVpDurationPrologue)
            || !instructionsMatch(queuePictureInstructionPointers(binary.base),
                    kExpectedQueuePicturePrologue)) {
        return "IJK refresh telemetry skipped: unsupported IJK binary probe signature";
    }
    RefreshTelemetryHookSearch search;
    dl_iterate_phdr(installRefreshTelemetryHook, &search);
    if (!search.error.empty()) {
        return "IJK refresh telemetry skipped: " + search.error;
    }
    if (search.matchedSlots != 8 || gOriginalComputeTargetDelay.load() == nullptr
            || gOriginalFrameQueueWaitTimeout.load() == nullptr
            || gOriginalVoutDisplayYuvOverlay.load() == nullptr
            || gOriginalDecoderDecodeFrame.load() == nullptr
            || gOriginalIsNeedDropFrame.load() == nullptr
            || gOriginalSdlDelay.load() == nullptr
            || gOriginalSdlCondWaitTimeout.load() == nullptr
            || gOriginalMediaCodecReleaseOutputBuffer.load() == nullptr) {
        return "IJK refresh telemetry skipped: expected eight imports, found="
                + std::to_string(search.matchedSlots);
    }
    const std::string vpDurationStatus = installVpDurationProbe();
    if (vpDurationStatus != "vp_duration probe active") {
        return "IJK refresh telemetry skipped: " + vpDurationStatus;
    }
    const std::string queuePictureStatus = installQueuePictureProbe();
    if (queuePictureStatus != "queue_picture probe active") {
        return "IJK refresh telemetry skipped: " + queuePictureStatus;
    }
    const bool rawSamplesActive = startRawSampleWriter(rawSamplePath);
    gRefreshTelemetryStarted.store(true);
    logInfo("nativeTelemetryBuild=" + std::string(kTelemetryBuild)
            + " rawSamples=" + (rawSamplesActive ? "active" : "unavailable")
            + " rawSamplePath=" + (rawSamplePath.empty() ? "<none>" : rawSamplePath));
    logInfo(std::string("computeTargetSyncBypass=")
            + (gBlockComputeTargetDelay.load(std::memory_order_acquire)
                    ? "active" : "inactive"));
    logInfo("IJK refresh telemetry active; hooked compute_target_delay, "
            "frame_queue_wait_timeout, SDL_VoutDisplayYUVOverlay, decoder_decode_frame, "
            "is_need_drop_frame, SDL_Delay, libijksdl SDL_CondWaitTimeout, vp_duration, "
            "libijksdl SDL_AMediaCodec_releaseOutputBuffer, queue_picture");
    return "IJK refresh telemetry active; nativeTelemetryBuild="
            + std::string(kTelemetryBuild) + " rawSamples="
            + (rawSamplesActive ? "active" : "unavailable");
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeApply4msPatch(
        JNIEnv* environment, jclass) {
    const std::string result = patchRefreshLoop();
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeStartProducerCounter(
        JNIEnv* environment, jclass, jstring statsPath) {
    const char* chars = environment->GetStringUTFChars(statsPath, nullptr);
    if (chars == nullptr) {
        return environment->NewStringUTF("Surface producer counter skipped: invalid stats path");
    }
    const std::string result = startProducerCounter(chars);
    environment->ReleaseStringUTFChars(statsPath, chars);
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeStartRefreshTelemetry(
        JNIEnv* environment, jclass, jstring rawSamplePath) {
    std::string path;
    const char* chars = rawSamplePath == nullptr
            ? nullptr : environment->GetStringUTFChars(rawSamplePath, nullptr);
    if (chars != nullptr) {
        path = chars;
        environment->ReleaseStringUTFChars(rawSamplePath, chars);
    }
    const std::string result = startRefreshTelemetry(path);
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetIjkFrameDropBlocked(
        JNIEnv* environment, jclass, jboolean blocked) {
    if (!gRefreshTelemetryStarted.load(std::memory_order_acquire)) {
        return environment->NewStringUTF("IJK frame-drop block unavailable: refresh hooks inactive");
    }
    gBlockIjkFrameDrop.store(blocked == JNI_TRUE, std::memory_order_release);
    const char* result = blocked == JNI_TRUE
            ? "IJK is_need_drop_frame blocking active"
            : "IJK is_need_drop_frame blocking disabled";
    return environment->NewStringUTF(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetComputeTargetDelayBlocked(
        JNIEnv* environment, jclass, jboolean blocked) {
    if (!gRefreshTelemetryStarted.load(std::memory_order_acquire)) {
        return environment->NewStringUTF("compute_target_delay block unavailable: refresh hooks inactive");
    }
    gBlockComputeTargetDelay.store(blocked == JNI_TRUE, std::memory_order_release);
    const char* result = blocked == JNI_TRUE
            ? "compute_target_delay blocking active"
            : "compute_target_delay blocking disabled";
    return environment->NewStringUTF(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetFrameQueueWaitRoundUp(
        JNIEnv* environment, jclass, jboolean enabled) {
    if (!gRefreshTelemetryStarted.load(std::memory_order_acquire)) {
        return environment->NewStringUTF("frame queue wait rounding unavailable: refresh hooks inactive");
    }
    gFrameQueueWaitRoundUp.store(enabled == JNI_TRUE, std::memory_order_release);
    const char* result = enabled == JNI_TRUE
            ? "frame queue wait rounding up active"
            : "frame queue wait rounding up disabled";
    return environment->NewStringUTF(result);
}

namespace {

struct OptionTableSearch {
    uintptr_t base = 0;
    std::vector<std::pair<uintptr_t, uintptr_t>> readable;  // PT_LOAD ranges
};

int locateIjkPlayerSegments(dl_phdr_info* info, size_t, void* data) {
    auto* search = static_cast<OptionTableSearch*>(data);
    if (info->dlpi_name == nullptr || std::strstr(info->dlpi_name, "libijkplayer.so") == nullptr) {
        return 0;
    }
    search->base = static_cast<uintptr_t>(info->dlpi_addr);
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const Elf64_Phdr& header = info->dlpi_phdr[i];
        if (header.p_type == PT_LOAD && (header.p_flags & PF_R) != 0) {
            const uintptr_t start = search->base + header.p_vaddr;
            search->readable.emplace_back(start, start + header.p_memsz);
        }
    }
    return 1;
}

template <typename T>
bool writeInPlace(T* target, T value, std::string* error) {
    const uintptr_t address = reinterpret_cast<uintptr_t>(target);
    const long pageSize = sysconf(_SC_PAGESIZE);
    const uintptr_t pageMask = static_cast<uintptr_t>(pageSize - 1);
    const uintptr_t pageStart = address & ~pageMask;
    const int protection = protectionForAddress(address);
    if (protection == 0) {
        *error = "option table page protection unknown";
        return false;
    }
    if (mprotect(reinterpret_cast<void*>(pageStart), static_cast<size_t>(pageSize),
            protection | PROT_WRITE) != 0) {
        *error = "option table page not writable";
        return false;
    }
    *target = value;
    mprotect(reinterpret_cast<void*>(pageStart), static_cast<size_t>(pageSize), protection);
    return true;
}

// FFmpeg AVOption layout on arm64: name(8) help(8) offset(4) type(4) default_val(8)
// min(8) max(8) flags(4) unit(8).  IJK's ffp option table uses the same struct, and its
// "video-pictq-size" entry on Bilibili 9.13.0 is {type INT, default 3, min 3, max 16}.
// Two writes make the deeper queue stick for every player created afterwards: the default
// becomes newMin (ffp_create() copies it through av_opt_set_defaults(), so no Java option
// call is needed — the online detail page never calls IjkMediaPlayer.setOption) and the
// minimum becomes newMin so the app's own later "3" is rejected as out of range.  Find the
// entry through the string pointer and verify type, default and both bounds first.
constexpr int kAvOptTypeInt = 1;
constexpr int kAvOptTypeInt64 = 2;  // the 9.13.0 entry is INT64 (static check of the .so)

std::string patchPictureQueueRange(double newMin) {
    OptionTableSearch search;
    dl_iterate_phdr(locateIjkPlayerSegments, &search);
    if (search.base == 0 || search.readable.empty()) {
        return "libijkplayer.so not loaded";
    }
    static constexpr char kName[] = "video-pictq-size";
    const size_t nameLength = sizeof(kName);  // includes NUL
    std::vector<uintptr_t> nameAddresses;
    for (const auto& range : search.readable) {
        const char* begin = reinterpret_cast<const char*>(range.first);
        const size_t length = range.second - range.first;
        for (size_t offset = 0; offset + nameLength <= length; ++offset) {
            if (begin[offset] == 'v' && std::memcmp(begin + offset, kName, nameLength) == 0) {
                nameAddresses.push_back(range.first + offset);
            }
        }
    }
    if (nameAddresses.empty()) {
        return "video-pictq-size string not found in libijkplayer.so";
    }
    int patched = 0;
    std::string detail;
    const int64_t newDefault = static_cast<int64_t>(newMin);
    for (const auto& range : search.readable) {
        const uintptr_t alignedStart = (range.first + 7) & ~static_cast<uintptr_t>(7);
        for (uintptr_t address = alignedStart; address + 56 <= range.second; address += 8) {
            const uintptr_t namePointer = *reinterpret_cast<const uintptr_t*>(address);
            bool matches = false;
            for (uintptr_t candidate : nameAddresses) {
                if (candidate == namePointer) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            const int32_t type = *reinterpret_cast<const int32_t*>(address + 20);
            int64_t* defaultValue = reinterpret_cast<int64_t*>(address + 24);
            double* minimum = reinterpret_cast<double*>(address + 32);
            double* maximum = reinterpret_cast<double*>(address + 40);
            if ((type != kAvOptTypeInt && type != kAvOptTypeInt64) || *maximum != 16.0
                    || (*minimum != 3.0 && *minimum != newMin)
                    || (*defaultValue != 3 && *defaultValue != newDefault)) {
                detail += " candidate@" + std::to_string(address - search.base) + " type="
                        + std::to_string(type) + " default=" + std::to_string(*defaultValue)
                        + " min=" + std::to_string(*minimum) + " max=" + std::to_string(*maximum);
                continue;
            }
            std::string error;
            if (*minimum != newMin && !writeInPlace(minimum, newMin, &error)) {
                return "video-pictq-size range patch failed: " + error;
            }
            if (*defaultValue != newDefault && !writeInPlace(defaultValue, newDefault, &error)) {
                return "video-pictq-size default patch failed: " + error;
            }
            patched++;
            detail += " patched@" + std::to_string(address - search.base);
        }
    }
    if (patched == 0) {
        return "video-pictq-size option entry not matched" + detail;
    }
    return "video-pictq-size default=min=" + std::to_string(newDefault) + " entries="
            + std::to_string(patched) + detail;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativePatchPictureQueueRange(
        JNIEnv* environment, jclass, jdouble newMin) {
    const std::string result = patchPictureQueueRange(static_cast<double>(newMin));
    return environment->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetReleaseScheduling(
        JNIEnv* environment, jclass, jboolean enabled, jint callLeadPercent,
        jint gridMode) {
    gScheduleGridMode.store(gridMode >= 0 && gridMode <= 4 ? gridMode : 0,
            std::memory_order_release);
    if (gridMode == 1) {
        mapAppVsyncRelay();
    }
    std::string swapHook;
    if (gridMode == 4) {
        swapHook = " " + installSwapTimestampHook();
    }
    if (!gRefreshTelemetryStarted.load(std::memory_order_acquire)
            || gOriginalComputeTargetDelay.load(std::memory_order_acquire) == nullptr) {
        return environment->NewStringUTF(
                "native release scheduling unavailable: compute_target_delay hook inactive");
    }
    const uint64_t lead = callLeadPercent >= 100 && callLeadPercent <= 400
            ? static_cast<uint64_t>(callLeadPercent) : kScheduleCallLeadPercent;
    gScheduleCallLeadPercent.store(lead, std::memory_order_relaxed);
    gReleaseScheduling.store(enabled == JNI_TRUE, std::memory_order_release);
    resetAllSchedulers();
    return environment->NewStringUTF(enabled == JNI_TRUE
            ? ("native release scheduling active " + std::string(gridMode == 1
                    ? "grid=arrival(vsync+p/2)" : gridMode == 2 ? "grid=deadline(latch window)"
                    : gridMode == 3 ? "grid=deadline+gl(latch window, immediate render)"
                    : gridMode == 4 ? "grid=media3(timestamped, 45 ms early)" + swapHook
                    : "callLead=" + std::to_string(lead) + "%")).c_str()
            : "native release scheduling disabled");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetMedia3SkipSameVsync(
        JNIEnv*, jclass, jboolean enabled) {
    gMedia3SkipSameVsync.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetVsyncGrid(
        JNIEnv*, jclass, jlong anchorNanos, jlong periodNanos, jlong vsyncNanos,
        jlong deadlineNanos) {
    if (anchorNanos <= 0 || periodNanos <= 0) {
        return;
    }
    gGridVsyncNanos.store(vsyncNanos > 0 ? static_cast<uint64_t>(vsyncNanos) : 0,
            std::memory_order_release);
    gGridDeadlineNanos.store(deadlineNanos > 0 ? static_cast<uint64_t>(deadlineNanos) : 0,
            std::memory_order_release);
    if (deadlineNanos > 0) {
        gExpectedMinusDeadlineNanos.store(anchorNanos - deadlineNanos, std::memory_order_relaxed);
    }
    gGridAnchorNanos.store(static_cast<uint64_t>(anchorNanos), std::memory_order_release);
    gGridPeriodNanos.store(static_cast<uint64_t>(periodNanos), std::memory_order_release);
    gGridUpdatedNanos.store(monotonicNanos(), std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeNoteDisplay(
        JNIEnv*, jclass, jlong nowNanos) {
    SchedulerState* state = schedForCurrentThread();
    if (state == nullptr) {
        gScheduleNotesUnmatched.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    const uint64_t now = nowNanos > 0 ? static_cast<uint64_t>(nowNanos) : monotonicNanos();
    const int gridMode = gScheduleGridMode.load(std::memory_order_acquire);
    const uint64_t tick = state->pendingTargetTickNanos.load(std::memory_order_acquire);
    if ((gridMode == 2 || gridMode == 3) && tick != 0
            && state->frameTimerValid.load(std::memory_order_acquire)) {
        // tick is the planned call time; its deadline is tick + lead, its present follows the
        // FrameTimeline's expected-minus-deadline distance.
        const int64_t expected = static_cast<int64_t>(tick)
                + static_cast<int64_t>(gLastAnchorLeadNanos.load(std::memory_order_relaxed));
        if (expected > 0) {
            rememberPendingPresent(now, static_cast<uint64_t>(expected));
        }
    }
    advanceFrameTimer(*state, now, true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetCompositorWindow(
        JNIEnv* environment, jclass, jobject surface) {
    ANativeWindow* window = surface == nullptr ? nullptr
            : ANativeWindow_fromSurface(environment, surface);
    setCompositorWindow(window);
    int64_t d = 0, i = 0, l = 0;
    const bool ok = window != nullptr && queryCompositorTiming(window, &d, &i, &l);
    gCompositorQueriedNanos.store(0, std::memory_order_release);
    return environment->NewStringUTF(ok
            ? ("compositor timing available interval=" + std::to_string(i / 1000) + "us latency="
                    + std::to_string(l / 1000) + "us").c_str()
            : (window == nullptr ? "compositor window unavailable"
                    : "compositor timing query failed"));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeQueryCompositorTiming(
        JNIEnv* environment, jclass) {
    int64_t d = 0, i = 0, l = 0;
    {
        std::lock_guard<std::mutex> lock(gCompositorMutex);
        if (!queryCompositorTiming(gCompositorWindow, &d, &i, &l)) {
            return nullptr;
        }
    }
    jlongArray result = environment->NewLongArray(3);
    if (result == nullptr) {
        return nullptr;
    }
    const jlong values[3] = {d, i, l};
    environment->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeArmSwapTimestamp(
        JNIEnv*, jclass, jlong presentationNs) {
    tlsSwapTimestampNs = presentationNs > 0 ? static_cast<uint64_t>(presentationNs) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeInstallSwapTimestampHook(
        JNIEnv* environment, jclass) {
    return environment->NewStringUTF(installSwapTimestampHook().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSwapTimestampStatus(
        JNIEnv* environment, jclass) {
    return environment->NewStringUTF(("swap[timestamped/failed/plain]="
            + std::to_string(gSwapTimestamped.load()) + "/"
            + std::to_string(gSwapTimestampFailures.load()) + "/"
            + std::to_string(gSwapsWithoutTimestamp.load())).c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeTakeReleaseTimestamp(JNIEnv*, jclass) {
    if (gScheduleGridMode.load(std::memory_order_acquire) != 4) {
        return -1;
    }
    SchedulerState* state = schedForCurrentThread();
    if (state == nullptr) {
        return -1;
    }
    if (gMedia3LastScheduledState.load(std::memory_order_acquire) != state) {
        gMedia3TakeStateMismatch.fetch_add(1, std::memory_order_relaxed);
    }
    const uint64_t ts = state->media3PendingReleaseTsNs.exchange(0, std::memory_order_acq_rel);
    if (ts == 0) {
        return -1;
    }
    if (state->media3PendingSkip.exchange(false, std::memory_order_acq_rel)) {
        gMedia3Skipped.fetch_add(1, std::memory_order_relaxed);
        return -2;   // release without rendering
    }
    gMedia3Timestamped.fetch_add(1, std::memory_order_relaxed);
    tlsSwapTimestampNs = ts;   // consumed by IJK's eglSwapBuffers on this thread
    return static_cast<jlong>(ts);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeNotePresent(
        JNIEnv*, jclass, jlong presentNanos) {
    if (presentNanos > 0) {
        notePresent(static_cast<uint64_t>(presentNanos));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeInvalidateFrameTimer(JNIEnv*, jclass) {
    resetAllSchedulers();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeSetPlaybackRate(
        JNIEnv*, jclass, jfloat rate) {
    const bool unity = std::isfinite(rate) && std::fabs(static_cast<double>(rate) - 1.0) < 0.01;
    const double value = unity || !std::isfinite(rate) || rate <= 0.0f
            ? 1.0 : static_cast<double>(rate);
    const double previous = gPlaybackRate.exchange(value, std::memory_order_acq_rel);
    gPlaybackRateUnity.store(unity, std::memory_order_release);
    if (std::fabs(previous - value) >= 0.01) {
        // The frame_timer mirror, ideal timeline and frame-rate estimate are meaningless
        // across any rate change.
        gRateScheduling.store(false, std::memory_order_release);
        resetAllSchedulers();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeReleaseSchedulingStatus(
        JNIEnv* environment, jclass) {
    mapAppVsyncRelay();
    if (gScheduleGridMode.load() == 4 && !gSwapHookInstalled.load()) {
        installSwapTimestampHook();   // IJK's GL library may load after activation
    }
    const std::string status = std::string("swap[timestamped/failed/plain]=")
            + std::to_string(gSwapTimestamped.load()) + "/"
            + std::to_string(gSwapTimestampFailures.load()) + "/"
            + std::to_string(gSwapsWithoutTimestamp.load())
            + " stuckReanchors=" + std::to_string(gMedia3StuckReanchors.load())
            + " resets[unsynced/overLimit lastDiffUs lastBaseUs]="
            + std::to_string(gMedia3ResetsUnsynced.load()) + "/"
            + std::to_string(gMedia3ResetsOverLimit.load()) + " "
            + std::to_string(gMedia3ResetLastDiffUs.load()) + " "
            + std::to_string(gMedia3ResetLastBaseUs.load())
            + " skipped=" + std::to_string(gMedia3Skipped.load())
            + " skipDecisions=" + std::to_string(gMedia3SkipDecisions.load())
            + " snapHysteresis=" + std::to_string(gMedia3SnapHysteresis.load())
            + " takeStateMismatch=" + std::to_string(gMedia3TakeStateMismatch.load())
            + " latencySteps=" + std::to_string(gMedia3LatencySteps.load())
            + " latencyOffsetUs=" + std::to_string((static_cast<int64_t>(
                    gMedia3LatencyOffsetNs.load()) - (1LL << 40)) / 1000)
            + " media3[scheduled/timestamped/adjusted/resets/sameVsync/late]="
            + std::to_string(gMedia3Scheduled.load()) + "/"
            + std::to_string(gMedia3Timestamped.load()) + "/"
            + std::to_string(gMedia3Adjusted.load()) + "/"
            + std::to_string(gMedia3AdjustmentResets.load()) + "/"
            + std::to_string(gMedia3SameVsync.load()) + "/"
            + std::to_string(gMedia3LateReleases.load()) + " frameDurUs="
            + std::to_string(gMedia3FrameDurationNs.load() / 1000) + " compositor[grid/queries/failures]="
            + std::to_string(gCompositorGridUsed.load()) + "/"
            + std::to_string(gCompositorQueries.load()) + "/"
            + std::to_string(gCompositorFailures.load()) + " compInterval="
            + std::to_string(gCompositorInterval.load() / 1000) + "us compLatency="
            + std::to_string(gCompositorLatencyUsed.load() / 1000)
            + "us present[onTime/late/early/unmatched]="
            + std::to_string(gPresentOnTime.load()) + "/" + std::to_string(gPresentLate.load())
            + "/" + std::to_string(gPresentEarly.load()) + "/"
            + std::to_string(gPresentUnmatched.load()) + " remembered="
            + std::to_string(gPendingRemembered.load()) + " diff0.1ms="
            + std::to_string(gPresentDiffSamples[0].load()) + ","
            + std::to_string(gPresentDiffSamples[1].load()) + ","
            + std::to_string(gPresentDiffSamples[2].load()) + ","
            + std::to_string(gPresentDiffSamples[3].load()) + " adaptiveLeadUs="
            + std::to_string(gAdaptiveLeadNanos.load() / 1000) + " adaptiveSteps="
            + std::to_string(gAdaptiveSteps.load()) + " relayVsync="
            + std::to_string(gRelayVsyncUsed.load()) + " gridMode="
            + std::to_string(gScheduleGridMode.load()) + " fpsBypassed="
            + std::to_string(gScheduleFpsBypassed.load()) + " rateUnity="
            + (gPlaybackRateUnity.load() ? "1" : "0") + " rateScaled="
            + std::to_string(gScheduleRateScaled.load()) + " rateBypassed="
            + std::to_string(gScheduleRateBypassed.load()) + " boostedThreads="
            + std::to_string(gPipelineThreadsBoosted.load()) + " boostErrno="
            + std::to_string(gPipelineBoostErrno.load()) + " scheduled="
            + std::to_string(gScheduledFrames.load()) + " displayNotes="
            + std::to_string(gScheduleDisplayNotes.load()) + " dropAdvances="
            + std::to_string(gScheduleDropAdvances.load()) + " slotShifts="
            + std::to_string(gScheduleSlotShifts.load()) + " lagDrops="
            + std::to_string(gScheduleReplacements.load()) + " late="
            + std::to_string(gScheduleLateFrames.load()) + " resyncs="
            + std::to_string(gScheduleResyncs.load()) + " clamps="
            + std::to_string(gScheduleClamps.load()) + " corrections="
            + std::to_string(gScheduleCorrections.load()) + " offsetNs="
            + std::to_string(gSchedulers[0].offsetNanos.load()) + " dropsSuppressed="
            + std::to_string(gScheduleDropsSuppressed.load()) + " idealResyncs="
            + std::to_string(gScheduleIdealResyncs.load()) + " noteStale="
            + std::to_string(gScheduleNoteStale.load()) + " notesUnmatched="
            + std::to_string(gScheduleNotesUnmatched.load()) + " delayCapped="
            + std::to_string(gScheduleDelayCapped.load()) + " gridStale="
            + std::to_string(gScheduleGridStale.load());
    std::string perThread;
    const uint64_t now = monotonicNanos();
    for (const SchedulerState& state : gSchedulers) {
        const pid_t tid = state.tid.load(std::memory_order_acquire);
        if (state.player.load(std::memory_order_acquire) == nullptr
                || now - state.lastUsedNanos.load(std::memory_order_relaxed) > 5'000'000'000ULL) {
            continue;
        }
        perThread += " [tid=" + std::to_string(tid) + " notes=" + std::to_string(state.notes.load())
                + " sched=" + std::to_string(state.scheduled.load()) + " late="
                + std::to_string(state.lateFrames.load()) + " stale="
                + std::to_string(state.staleCalls.load()) + " valid="
                + (state.frameTimerValid.load() ? "1" : "0") + "]";
    }
    return environment->NewStringUTF((status + perThread).c_str());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_xpramt_bilisurfacebridge_NativeRefreshCap_nativeGetIjkEarlyUsForPts(
        JNIEnv* environment, jclass, jlong ptsUs) {
    IjkEarlyUsSample sample{};
    if (!findIjkEarlyUsSample(static_cast<int64_t>(ptsUs), &sample)) {
        return nullptr;
    }
    const jlong values[] = {
            static_cast<jlong>(sample.timestampNanos),
            static_cast<jlong>(sample.ptsUs),
            static_cast<jlong>(sample.earlyUsNanos),
            static_cast<jlong>(sample.filterDelayNanos),
    };
    jlongArray result = environment->NewLongArray(4);
    if (result == nullptr) {
        return nullptr;
    }
    environment->SetLongArrayRegion(result, 0, 4, values);
    return result;
}
