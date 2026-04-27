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
    private static final double STEP_THRESHOLD_HIGH = 2.5;      // m/s²
    private static final double STEP_THRESHOLD_LOW  = 0.4;      // m/s²
    private static final long   STEP_MIN_TIME_NS    = 250_000_000L; // 250 ms in nanoseconds

    // Weinberg stride model: stride_m = K * (aMax - aMin)^0.25
    private static final double WEINBERG_K_DEFAULT = 0.42;

    // ZUPT: variance of linear-accel magnitude over window < threshold → stationary
    private static final int    ZUPT_WINDOW  = 30;
    private static final double ZUPT_VAR_MAX = 0.04; // m²/s⁴

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

    private void updateStepWindow(double mag) {
        if (mag > stepWindowMax) stepWindowMax = mag;
        if (mag < stepWindowMin) stepWindowMin = mag;
    }

    private boolean analyzeForStep(double acceleration, long timestamp) {
        if (!isPeakFound) {
            if (acceleration > STEP_THRESHOLD_HIGH) {
                isPeakFound = true;
                return false;
            }
        } else {
            if (acceleration < STEP_THRESHOLD_LOW) {
                if (timestamp - lastStepTime > STEP_MIN_TIME_NS) {
                    lastStepTime = timestamp;
                    stepCount++;
                    isPeakFound = false;
                    updateStrideFromWeinberg();
                    stepWindowMax = 0;
                    stepWindowMin = Double.MAX_VALUE;
                    return true;
                }
            }
        }

        if (acceleration < STEP_THRESHOLD_LOW) {
            isPeakFound = false;
        }

        return false;
    }

    private void updateStrideFromWeinberg() {
        if (!useWeinberg) return;
        double range = stepWindowMax - stepWindowMin;
        if (range > 0.1) {
            double proposed = weinbergK * Math.pow(range, 0.25);
            // Clamp to realistic walking stride
            currentStrideLength = Math.max(0.40, Math.min(1.10, proposed));
        }
    }

    /** Call after GPS calibration to tune K for this user's gait. */
    public void calibrateWeinbergK(double knownDistanceMeters, int steps) {
        if (steps <= 0 || knownDistanceMeters <= 0) return;
        double measuredStride = knownDistanceMeters / steps;
        double range = stepWindowMax - stepWindowMin;
        if (range > 0.1) {
            weinbergK = measuredStride / Math.pow(range, 0.25);
            weinbergK = Math.max(0.25, Math.min(0.65, weinbergK));
        }
    }

    public void setStrideLength(double strideLengthMeters) {
        currentStrideLength = strideLengthMeters;
        useWeinberg = false;
    }

    public void enableWeinberg(boolean enable) {
        useWeinberg = enable;
        if (enable) weinbergK = WEINBERG_K_DEFAULT;
    }

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
        kalmanFilter.reset(0, 1);
    }

    public double getDistanceTraveled() {
        return stepCount * currentStrideLength;
    }

    public double getFilteredAcceleration() {
        return kalmanFilter.getEstimate();
    }
}
