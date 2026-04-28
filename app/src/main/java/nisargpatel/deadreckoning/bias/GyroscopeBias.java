package nisargpatel.deadreckoning.bias;

/**
 * Gyroscope bias estimator.
 *
 * Static phase: average over N trials at startup while device is still.
 * Online phase: during ZUPT-detected stationary windows, use slow EMA to track
 * thermal drift — essential for a long underground walk where phone temperature rises.
 */
public class GyroscopeBias {

    // Slow EMA so a brief mis-classified motion event can't corrupt the bias estimate
    private static final float ONLINE_ALPHA = 0.005f;

    private int runCount;
    private int trials;
    private float[] gyroBias;

    /** Package-private default constructor; use {@link #GyroscopeBias(int)} in production code. */
    GyroscopeBias() {
        runCount = 0;
        trials = 0;
        gyroBias = new float[3];
    }

    /**
     * @param trials Number of gyroscope samples to average during static calibration.
     *               Larger values give a more stable bias estimate at the cost of
     *               a longer startup hold-still period.
     */
    public GyroscopeBias(int trials) {
        this();
        this.trials = trials;
    }

    /**
     * Accumulates one raw gyroscope reading for static bias estimation.
     * Call repeatedly while the device is perfectly still.
     * Uses a running mean to avoid storing all samples.
     *
     * @param rawGyroValues Raw sensor values [x, y, z] in rad/s.
     * @return {@code true} when the required number of {@code trials} has been
     *         reached and the bias estimate is ready; {@code false} otherwise.
     */
    public boolean calcBias(float[] rawGyroValues) {
        runCount++;

        if (runCount >= trials)
            return true;

        if (runCount == 1) {
            gyroBias[0] = rawGyroValues[0];
            gyroBias[1] = rawGyroValues[1];
            gyroBias[2] = rawGyroValues[2];
            return false;
        }

        float n = (float) runCount;
        gyroBias[0] = gyroBias[0] * ((n - 1) / n) + rawGyroValues[0] * (1 / n);
        gyroBias[1] = gyroBias[1] * ((n - 1) / n) + rawGyroValues[1] * (1 / n);
        gyroBias[2] = gyroBias[2] * ((n - 1) / n) + rawGyroValues[2] * (1 / n);

        return false;
    }

    /**
     * Online bias update using a slow exponential moving average (α = 0.005).
     * Call only when ZUPT confirms the device is stationary; any non-zero
     * gyro reading during a stationary window is purely thermal/drift bias.
     *
     * @param rawGyroValues Raw sensor values [x, y, z] in rad/s measured
     *                      during a confirmed zero-velocity phase.
     */
    public void updateFromZupt(float[] rawGyroValues) {
        gyroBias[0] = (1 - ONLINE_ALPHA) * gyroBias[0] + ONLINE_ALPHA * rawGyroValues[0];
        gyroBias[1] = (1 - ONLINE_ALPHA) * gyroBias[1] + ONLINE_ALPHA * rawGyroValues[1];
        gyroBias[2] = (1 - ONLINE_ALPHA) * gyroBias[2] + ONLINE_ALPHA * rawGyroValues[2];
    }

    /** Returns a copy of the current bias estimate [x, y, z] in rad/s. */
    public float[] getBias() {
        return gyroBias.clone();
    }
}
