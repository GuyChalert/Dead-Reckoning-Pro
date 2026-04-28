package nisargpatel.deadreckoning.sensor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Step counter for TYPE_LINEAR_ACCELERATION (gravity already removed by Android).
 *
 * Bug fixes vs original:
 *  - Thresholds corrected: linear-accel step peaks are 2–6 m/s², not 9.5–11.5
 *  - STEP_MIN_TIME compared in nanoseconds (original compared ns to 250 = 250 ns ≈ 0)
 *
 * New for OnePlus 13 / underground use:
 *  - Weinberg stride model: stride = K * (aMax - aMin)^0.25 per step
 *  - ZUPT: stationary detection so caller can reset gyro bias online
 */
public class EnhancedStepCounter {

    // Tuned for linear acceleration (Snapdragon 8 Elite sensor hub, OnePlus 13)
    private static final double STEP_THRESHOLD_HIGH = 4.5;      // m/s²
    private static final double STEP_THRESHOLD_LOW  = 0.4;      // m/s²
    private static final long   STEP_MIN_TIME_NS    = 250_000_000L; // 250 ms in nanoseconds

    // Weinberg stride model: stride_m = K * (aMax - aMin)^0.25
    private static final double WEINBERG_K_DEFAULT = 0.42;

    // ZUPT: variance of linear-accel magnitude over window < threshold → stationary
    private static final int    ZUPT_WINDOW  = 50;
    private static final double ZUPT_VAR_MAX = 0.06; // m²/s⁴

    // Gait continuity: max interval between consecutive steps before resetting cadence
    // 1.0 s tighter than before — filters manipulation with irregular timing
    private static final long STEP_MAX_INTERVAL_NS = 1_000_000_000L; // 1.0 s

    // Require N consecutive in-cadence events before counting (warmup prevents manipulation false-starts)
    private static final int WARMUP_STEPS = 7;
    private int warmupCount = 0;

    // Hardware step detector gate: software step only counts if hardware TYPE_STEP_DETECTOR
    // fired within this window. Eliminates false positives from device manipulation.
    private static final long HARDWARE_GATE_NS = 1_200_000_000L; // 1.2 s
    private volatile long lastHardwareStepNs = 0;
    private volatile boolean hardwareStepAvailable = false;

    // Suppress steps when device is rotating — not walking.
    // Track max gyro over the entire peak→valley window: if the device rotated at any
    // point during that window, cancel the candidate step even if gyro has since dropped.
    private static final float ROTATION_SUPPRESSION_RADS = 0.4f; // rad/s
    private float lastGyroMag = 0f;
    private float peakWindowMaxGyro = 0f;

    private double weinbergK = WEINBERG_K_DEFAULT;
    private boolean useWeinberg = true;

    private final Deque<Double> zuptBuf = new ArrayDeque<>();
    private boolean isStationary = false;

    // Per-step extrema for Weinberg
    private double stepWindowMax = 0;
    private double stepWindowMin = Double.MAX_VALUE;

    private int stepCount = 0;
    private long lastStepTime = 0;
    private boolean isPeakFound = false;
    private double currentStrideLength = 0.75;

    private final KalmanFilter kalmanFilter;

    public EnhancedStepCounter() {
        kalmanFilter = new KalmanFilter(0.005, 0.1, 0, 1);
    }

    /**
     * Processes one TYPE_LINEAR_ACCELERATION event and returns whether a
     * confirmed step was detected.
     *
     * @param linearAcceleration Gravity-removed acceleration vector [x, y, z] in m/s².
     * @param timestamp          Sensor event timestamp in nanoseconds (ns).
     * @return {@code true} if a valid step was confirmed (passed warmup, hardware
     *         gate, cadence, and rotation checks).
     */
    public boolean detectStep(float[] linearAcceleration, long timestamp) {
        double magnitude = Math.sqrt(
            linearAcceleration[0] * linearAcceleration[0] +
            linearAcceleration[1] * linearAcceleration[1] +
            linearAcceleration[2] * linearAcceleration[2]
        );

        double filtered = kalmanFilter.update(magnitude);

        updateZupt(filtered);
        updateStepWindow(filtered);

        return analyzeForStep(filtered, timestamp);
    }

    /**
     * Updates the ZUPT (Zero-velocity Update) sliding window and sets
     * {@link #isStationary} based on whether the variance of the last
     * {@link #ZUPT_WINDOW} samples falls below {@link #ZUPT_VAR_MAX}.
     *
     * @param mag Filtered linear-acceleration magnitude in m/s².
     */
    private void updateZupt(double mag) {
        zuptBuf.addLast(mag);
        if (zuptBuf.size() > ZUPT_WINDOW) zuptBuf.pollFirst();

        if (zuptBuf.size() == ZUPT_WINDOW) {
            double mean = 0;
            for (double v : zuptBuf) mean += v;
            mean /= ZUPT_WINDOW;
            double variance = 0;
            for (double v : zuptBuf) variance += (v - mean) * (v - mean);
            variance /= ZUPT_WINDOW;
            isStationary = variance < ZUPT_VAR_MAX;
        }
    }

    /**
     * Tracks the per-step acceleration extrema used by the Weinberg stride model.
     * Resets on each confirmed step in {@link #analyzeForStep}.
     *
     * @param mag Filtered linear-acceleration magnitude in m/s².
     */
    private void updateStepWindow(double mag) {
        if (mag > stepWindowMax) stepWindowMax = mag;
        if (mag < stepWindowMin) stepWindowMin = mag;
    }

    /**
     * Core step-detection state machine. Applies all gates in order:
     * ZUPT → hardware gate → peak detection → valley confirmation →
     * rotation suppression → cadence check → warmup counter.
     *
     * @param acceleration Filtered linear-acceleration magnitude in m/s².
     * @param timestamp    Sensor event timestamp in nanoseconds (ns).
     * @return {@code true} if all gates passed and a step is confirmed.
     */
    private boolean analyzeForStep(double acceleration, long timestamp) {
        if (isStationary) return false;
        // Hardware gate: when sensor is available, require a recent hardware step event.
        // Without this guard, lastHardwareStepNs==0 bypasses the gate on startup.
        if (hardwareStepAvailable
                && (lastHardwareStepNs <= 0 || (timestamp - lastHardwareStepNs) > HARDWARE_GATE_NS)) {
            return false;
        }
        if (!isPeakFound) {
            if (acceleration > STEP_THRESHOLD_HIGH) {
                isPeakFound = true;
                peakWindowMaxGyro = lastGyroMag; // start tracking gyro for this candidate step
                return false;
            }
        } else {
            // Keep track of the highest gyro seen since peak was detected
            if (lastGyroMag > peakWindowMaxGyro) peakWindowMaxGyro = lastGyroMag;
            if (acceleration < STEP_THRESHOLD_LOW) {
                if (timestamp - lastStepTime > STEP_MIN_TIME_NS) {
                    isPeakFound = false;
                    stepWindowMax = 0;
                    stepWindowMin = Double.MAX_VALUE;
                    float maxGyro = peakWindowMaxGyro;
                    peakWindowMaxGyro = 0f;
                    // Suppress if device rotated at ANY point during this step candidate window
                    if (maxGyro < ROTATION_SUPPRESSION_RADS) {
                        boolean inWalkingCadence = (lastStepTime > 0)
                                && (timestamp - lastStepTime < STEP_MAX_INTERVAL_NS);
                        lastStepTime = timestamp;
                        if (inWalkingCadence) {
                            warmupCount++;
                            if (warmupCount >= WARMUP_STEPS) {
                                stepCount++;
                                updateStrideFromWeinberg();
                                return true;
                            }
                        } else {
                            warmupCount = 0;
                        }
                    }
                    return false;
                }
            }
        }

        if (acceleration < STEP_THRESHOLD_LOW) {
            isPeakFound = false;
        }

        return false;
    }

    /**
     * Recalculates stride length using the Weinberg model:
     * stride = K · (a_max − a_min)^0.25, clamped to [0.40, 1.10] m.
     * Only updates if the acceleration range within the step window is
     * large enough to be meaningful (> 0.1 m/s²).
     */
    private void updateStrideFromWeinberg() {
        if (!useWeinberg) return;
        double range = stepWindowMax - stepWindowMin;
        if (range > 0.1) {
            double proposed = weinbergK * Math.pow(range, 0.25);
            // Clamp to realistic walking stride
            currentStrideLength = Math.max(0.40, Math.min(1.10, proposed));
        }
    }

    /**
     * Calibrates the Weinberg K constant from a known GPS distance.
     * Call after walking a measured segment with GPS tracking enabled.
     * K is clamped to [0.25, 0.65] to reject obviously bad calibrations.
     *
     * @param knownDistanceMeters True walked distance measured by GPS in meters (m).
     * @param steps               Step count over that distance.
     */
    public void calibrateWeinbergK(double knownDistanceMeters, int steps) {
        if (steps <= 0 || knownDistanceMeters <= 0) return;
        double measuredStride = knownDistanceMeters / steps;
        double range = stepWindowMax - stepWindowMin;
        if (range > 0.1) {
            weinbergK = measuredStride / Math.pow(range, 0.25);
            weinbergK = Math.max(0.25, Math.min(0.65, weinbergK));
        }
    }

    /**
     * Sets a fixed stride length and disables Weinberg adaptive estimation.
     * Use this when the user has measured their own stride manually.
     *
     * @param strideLengthMeters Fixed stride length in meters (m).
     */
    public void setStrideLength(double strideLengthMeters) {
        currentStrideLength = strideLengthMeters;
        useWeinberg = false;
    }

    /**
     * Enables or disables the Weinberg adaptive stride model.
     * Re-enabling resets K to the default value ({@value #WEINBERG_K_DEFAULT}).
     *
     * @param enable {@code true} to use Weinberg adaptive stride; {@code false}
     *               to use the last fixed stride length set by {@link #setStrideLength}.
     */
    public void enableWeinberg(boolean enable) {
        useWeinberg = enable;
        if (enable) weinbergK = WEINBERG_K_DEFAULT;
    }

    /**
     * Updates the gyroscope magnitude used for rotation suppression.
     * Must be called before each {@link #detectStep} call for the gate to work.
     *
     * @param mag Current gyroscope magnitude in rad/s.
     */
    public void setGyroMagnitude(float mag) { lastGyroMag = mag; }

    /** Must be called once at startup to enable the hardware gate. False = software-only mode. */
    public void setHardwareStepDetectorAvailable(boolean available) {
        hardwareStepAvailable = available;
    }

    /** Called when Android's hardware TYPE_STEP_DETECTOR fires. Gates software detection. */
    public void notifyHardwareStep(long timestampNs) { lastHardwareStepNs = timestampNs; }

    public double getStrideLength()   { return currentStrideLength; }
    public int    getStepCount()      { return stepCount; }
    public boolean isStationary()     { return isStationary; }

    public void reset() {
        stepCount = 0;
        lastStepTime = 0;
        isPeakFound = false;
        stepWindowMax = 0;
        stepWindowMin = Double.MAX_VALUE;
        zuptBuf.clear();
        isStationary = false;
        currentStrideLength = 0.75;
        warmupCount = 0;
        lastHardwareStepNs = 0;
        peakWindowMaxGyro = 0f;
        kalmanFilter.reset(0, 1);
    }

    public double getDistanceTraveled() {
        return stepCount * currentStrideLength;
    }

    public double getFilteredAcceleration() {
        return kalmanFilter.getEstimate();
    }
}
