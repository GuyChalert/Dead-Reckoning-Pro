package nisargpatel.deadreckoning.power;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import nisargpatel.deadreckoning.sensor.PocketStateDetector;

/**
 * POCKET_WALKING ↔ HANDHELD_MAPPING duty-cycle state machine.
 *
 * In POCKET_WALKING:
 *   Screen brightness → ~0 (OLED: near-zero power on black pixels).
 *   Torch → off.
 *   UI overlay → shows minimal metrics (distance, steps, elevation).
 *   IMU keeps running at full rate (sensor hub, low power).
 *
 * In HANDHELD_MAPPING:
 *   Screen brightness → system default.
 *   Torch → on while tracking (high-contrast features for future VIO).
 *   Full map UI restored.
 *
 * Torch control uses CameraManager.setTorchMode — no CAMERA permission
 * required on API 23+, only android.permission.FLASHLIGHT (normal permission).
 *
 * All Window mutations are dispatched on the main thread via Handler.
 */
public class PowerDutyManager {

    public enum State { POCKET_WALKING, HANDHELD_MAPPING }

    public interface StateListener {
        void onStateChanged(State newState);
    }

    private static final float SCREEN_DIM    = 0.01f;   // near-off; keeps touch alive
    private static final float SCREEN_NORMAL = -1.0f;   // restore system brightness

    private final Window   window;
    private final CameraManager cameraManager;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    private final java.util.List<StateListener> listeners = new java.util.ArrayList<>();

    private State   currentState   = State.HANDHELD_MAPPING;
    private boolean trackingActive = false;
    private boolean torchEnabled   = false; // off by default; enable via settings
    private String  torchCameraId  = null;

    /**
     * @param window        Activity window used for brightness control.
     * @param cameraManager System {@link CameraManager} for torch control; may be null.
     */
    public PowerDutyManager(Window window, CameraManager cameraManager) {
        this.window        = window;
        this.cameraManager = cameraManager;
        cacheTorchId();
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    /**
     * Notifies that the tracking session started or stopped.
     * Turns off the torch when tracking stops; turns it on in HANDHELD_MAPPING mode when it starts.
     */
    public void setTrackingActive(boolean active) {
        trackingActive = active;
        if (!active) setTorchSafe(false);
        else if (currentState == State.HANDHELD_MAPPING) setTorchSafe(true);
    }

    /**
     * Enables or disables the torch feature (user preference toggle).
     * When disabled, immediately turns off the torch regardless of state.
     */
    public void setTorchEnabled(boolean enabled) {
        torchEnabled = enabled;
        if (!enabled) setTorchSafe(false);
        else if (trackingActive && currentState == State.HANDHELD_MAPPING) setTorchSafe(true);
    }

    /**
     * Feed the latest PocketStateDetector result.
     * Call from any thread (usually the sensor dispatch thread).
     */
    public void onPocketStateChanged(PocketStateDetector.State detected) {
        State next = (detected == PocketStateDetector.State.POCKET_WALKING)
                ? State.POCKET_WALKING : State.HANDHELD_MAPPING;
        if (next == currentState) return;
        currentState = next;
        applyState();
        for (StateListener l : listeners) l.onStateChanged(next);
    }

    /** Registers a listener for duty-cycle state changes. */
    public void addListener(StateListener l) { listeners.add(l); }
    /** Unregisters a previously added state-change listener. */
    public void removeListener(StateListener l) { listeners.remove(l); }

    /** @return Current duty-cycle state. */
    public State getState() { return currentState; }
    /** @return {@code true} if currently in {@link State#POCKET_WALKING} mode. */
    public boolean isPocketMode() { return currentState == State.POCKET_WALKING; }

    /** Call from Fragment.onDestroyView() to restore brightness and kill torch. */
    public void release() {
        setTorchSafe(false);
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /** Applies torch and brightness changes for the new {@link #currentState}. */
    private void applyState() {
        if (currentState == State.POCKET_WALKING) {
            setTorchSafe(false);
        } else {
            if (trackingActive) setTorchSafe(true);
        }
    }

    /**
     * Sets window screen brightness.
     *
     * @param b {@link WindowManager.LayoutParams#BRIGHTNESS_OVERRIDE_NONE} (−1) for system default,
     *          or [0.0, 1.0] for explicit brightness.
     */
    private void setBrightness(float b) {
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.screenBrightness = b;
        window.setAttributes(lp);
    }

    /** Calls {@link CameraManager#setTorchMode}; no-op if torch camera not found or feature disabled. */
    private void setTorchSafe(boolean on) {
        if (torchCameraId == null) return;
        if (on && !torchEnabled) return;
        try {
            cameraManager.setTorchMode(torchCameraId, on);
        } catch (Exception ignored) {}
    }

    /** Finds and caches the first camera ID that has a hardware flashlight. */
    private void cacheTorchId() {
        if (cameraManager == null) return;
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    torchCameraId = id;
                    return;
                }
            }
        } catch (Exception ignored) {}
    }
}
