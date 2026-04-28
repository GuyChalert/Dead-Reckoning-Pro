package nisargpatel.deadreckoning.orientation;

import nisargpatel.deadreckoning.extra.ExtraFunctions;

/**
 * Estimates incremental orientation changes (Δroll, Δpitch, Δyaw) by
 * integrating bias-corrected gyroscope angular velocities over time.
 *
 * <p>A simple threshold-based high-pass filter suppresses near-zero drift
 * before integration. Use {@link #calcDeltaOrientation} at each gyroscope
 * event and accumulate the deltas in a rotation matrix or quaternion upstream.
 */
public class GyroscopeDeltaOrientation {

    private boolean isFirstRun;
    /** Angular-velocity threshold below which readings are zeroed (rad/s). */
    private float sensitivity;
    /** Timestamp of the previous sample, in seconds (s). */
    private float lastTimestamp;
    /** Per-axis gyroscope bias [x, y, z] in rad/s. */
    private float[] gyroBias;

    /** Creates an instance with default sensitivity (0.0025 rad/s) and zero bias. */
    public GyroscopeDeltaOrientation() {
        this.gyroBias = new float[3];
        this.sensitivity = 0.0025f;
        this.isFirstRun = true;
    }

    /**
     * @param sensitivity Angular-velocity threshold below which readings are
     *                    treated as zero to suppress drift (rad/s).
     * @param gyroBias    Per-axis bias [x, y, z] in rad/s, e.g. from
     *                    {@link nisargpatel.deadreckoning.bias.GyroscopeBias#getBias()}.
     */
    public GyroscopeDeltaOrientation(float sensitivity, float[] gyroBias) {
        this();
        this.sensitivity = sensitivity;
        this.gyroBias = gyroBias;
    }

    /**
     * Computes the incremental orientation change since the last call by
     * removing bias, applying the sensitivity threshold, and integrating
     * angular velocity over the elapsed time interval.
     *
     * @param timestamp      Sensor event timestamp in nanoseconds (ns).
     * @param rawGyroValues  Raw gyroscope reading [x, y, z] in rad/s.
     * @return Delta orientation [Δroll, Δpitch, Δyaw] in radians (rad).
     *         Returns a zero vector on the first call (no Δt available yet).
     */
    public float[] calcDeltaOrientation(long timestamp, float[] rawGyroValues) {
        //get the first timestamp
        if (isFirstRun) {
            isFirstRun = false;
            lastTimestamp = ExtraFunctions.nsToSec(timestamp);
            return new float[3];
        }

        float[] unbiasedGyroValues = removeBias(rawGyroValues);

        //return deltaOrientation[]
        return integrateValues(timestamp, unbiasedGyroValues);
    }

    /**
     * Updates the per-axis gyroscope bias used for correction.
     *
     * @param gyroBias Per-axis bias [x, y, z] in rad/s.
     */
    public void setBias(float[] gyroBias) {
        this.gyroBias = gyroBias;
    }

    /**
     * Subtracts the stored bias from raw values and zeroes components below
     * the sensitivity threshold.
     *
     * @param rawGyroValues Raw gyroscope reading [x, y, z] in rad/s.
     * @return Corrected angular velocity [x, y, z] in rad/s.
     */
    private float[] removeBias(float[] rawGyroValues) {
        //ignoring the last 3 values of TYPE_UNCALIBRATED_GYROSCOPE, since the are only the Android-calculated biases
        float[] unbiasedGyroValues = new float[3];

        for (int i = 0; i < 3; i++)
            unbiasedGyroValues[i] = rawGyroValues[i] - gyroBias[i];

        //TODO: check how big of a difference this makes
        //applying a quick high pass filter
        for (int i = 0; i < 3; i++)
            if (Math.abs(unbiasedGyroValues[i]) > sensitivity)
                unbiasedGyroValues[i] = unbiasedGyroValues[i];
            else
                unbiasedGyroValues[i] = 0;

        return unbiasedGyroValues;
    }

    /**
     * Multiplies corrected angular velocities by the elapsed time to produce
     * incremental rotation angles.
     *
     * @param timestamp  Current sensor event timestamp in nanoseconds (ns).
     * @param gyroValues Bias-corrected angular velocity [x, y, z] in rad/s.
     * @return Delta orientation [Δx, Δy, Δz] in radians (rad).
     */
    private float[] integrateValues(long timestamp, float[] gyroValues) {
        double currentTime = ExtraFunctions.nsToSec(timestamp);
        double deltaTime = currentTime - lastTimestamp;

        float[] deltaOrientation = new float[3];

        //integrating angular velocity with respect to time
        for (int i = 0; i < 3; i++)
            deltaOrientation[i] = gyroValues[i] * (float)deltaTime;

        lastTimestamp = (float) currentTime;

        return deltaOrientation;
    }

}
