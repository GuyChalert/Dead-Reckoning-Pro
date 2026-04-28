package nisargpatel.deadreckoning.sensor;

import android.hardware.SensorManager;

/**
 * Heading estimator with two modes:
 *
 *  PRIMARY — TYPE_GAME_ROTATION_VECTOR (hardware-fused, magnetometer-free).
 *    Ideal for underground / magnetically noisy environments (steel, cables, ore).
 *    Snapdragon 8 Elite sensor hub on OnePlus 13 implements this in hardware.
 *    Typical drift: ~1–2°/min; corrected further by ZUPT-based gyro-bias updates.
 *
 *  FALLBACK — Mahony-style complementary filter on gravity + magnetometer + gyroscope.
 *    Bug fix vs original: original did ALPHA*gyroHeading + (1-ALPHA)*magHeading where
 *    gyroHeading was an accumulated integral and magHeading was absolute — wrong shape.
 *    Replaced with: gyro propagates heading each dt, mag applies small error correction.
 */
public class PreciseHeadingEstimator {

    // Correction gain for the fallback complementary filter
    private static final float MAG_CORRECTION_GAIN = 0.02f;

    private float[] gravityValues        = null;
    private float[] magValues            = null;
    private float[] gyroValues           = null;
    private float[] rotationVectorValues = null;
    private boolean hasRotationVector    = false;

    // Fallback state: heading in radians, propagated by gyro
    private double heading       = 0;
    private long   lastTimestamp = 0;

    private final float[] rotationMatrix    = new float[9];
    private final float[] orientationAngles = new float[3];

    /** Feed TYPE_GAME_ROTATION_VECTOR data (preferred, magnetometer-free). */
    public void updateRotationVector(float[] values) {
        rotationVectorValues = values.clone();
        hasRotationVector = true;
    }

    /**
     * Updates the gravity (accelerometer) reading used by the fallback filter.
     *
     * @param values TYPE_ACCELEROMETER reading [x, y, z] in m/s².
     */
    public void updateGravity(float[] values) {
        gravityValues = values.clone();
    }

    /**
     * Updates the magnetic field reading used by the fallback filter.
     *
     * @param values TYPE_MAGNETIC_FIELD reading [x, y, z] in μT.
     */
    public void updateMagneticField(float[] values) {
        magValues = values.clone();
    }

    /**
     * Updates the gyroscope reading and propagates the fallback heading
     * by integrating the Z-axis angular rate.
     * No-op if the rotation vector source is available.
     *
     * @param values    Bias-corrected gyroscope reading [x, y, z] in rad/s.
     * @param timestamp Sensor event timestamp in nanoseconds (ns).
     */
    public void updateGyroscope(float[] values, long timestamp) {
        if (lastTimestamp == 0) {
            lastTimestamp = timestamp;
            gyroValues = values.clone();
            return;
        }

        float dt = (timestamp - lastTimestamp) / 1_000_000_000.0f;
        if (dt <= 0 || dt > 1.0f) {
            lastTimestamp = timestamp;
            return;
        }
        lastTimestamp = timestamp;
        gyroValues = values.clone();

        if (!hasRotationVector) {
            // gyroValues[2] = Z-axis angular rate in rad/s (yaw). Negative = clockwise.
            heading -= gyroValues[2] * dt;
            heading = wrapRadians(heading);
        }
    }

    /**
     * Returns the best available heading estimate.
     * Prefers the rotation-vector source; falls back to the Mahony complementary filter.
     *
     * @return Heading in degrees (°); 0° = North (magnetic), positive clockwise.
     */
    public double getHeading() {
        if (hasRotationVector && rotationVectorValues != null) {
            return headingFromRotationVector();
        }
        return headingFromFallback();
    }

    /**
     * Derives heading from the TYPE_GAME_ROTATION_VECTOR quaternion via
     * {@link android.hardware.SensorManager#getOrientation}.
     *
     * @return Heading (azimuth) in degrees (°).
     */
    private double headingFromRotationVector() {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValues);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        return Math.toDegrees(orientationAngles[0]);
    }

    /**
     * Computes heading using a Mahony-style complementary filter.
     * Gyro propagates heading each Δt; magnetometer applies a small error
     * correction (gain = {@link #MAG_CORRECTION_GAIN}) toward the absolute
     * magnetic heading when gravity + mag data are available.
     *
     * @return Heading in degrees (°).
     */
    private double headingFromFallback() {
        if (gravityValues != null && magValues != null) {
            boolean ok = SensorManager.getRotationMatrix(
                rotationMatrix, null, gravityValues, magValues);
            if (ok) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                double magHeading = orientationAngles[0];
                // Mahony-style error correction toward absolute magnetic heading
                double error = wrapRadians(magHeading - heading);
                heading += MAG_CORRECTION_GAIN * error;
                heading = wrapRadians(heading);
            }
        }
        return Math.toDegrees(heading);
    }

    /**
     * Wraps an angle to (−π, π].
     *
     * @param r Angle in radians (rad).
     * @return Equivalent angle in (−π, π] in radians (rad).
     */
    private static double wrapRadians(double r) {
        while (r >  Math.PI) r -= 2 * Math.PI;
        while (r < -Math.PI) r += 2 * Math.PI;
        return r;
    }

    /** Alias for {@link #getHeading()}; returns heading in degrees (°). */
    public double getHeadingDegrees() {
        return getHeading();
    }

    /** Resets all sensor state; the next call to {@link #getHeading()} will re-initialise. */
    public void reset() {
        heading = 0;
        lastTimestamp = 0;
        hasRotationVector = false;
        rotationVectorValues = null;
        gravityValues = null;
        magValues = null;
        gyroValues = null;
    }

    /**
     * @return {@code true} if at least one valid heading source is available
     *         (rotation vector, or both gravity and magnetometer).
     */
    public boolean hasValidData() {
        return hasRotationVector || (gravityValues != null && magValues != null);
    }

    /** @return {@code true} if the rotation-vector (magnetometer-free) path is active. */
    public boolean usingRotationVector() {
        return hasRotationVector;
    }

    /** @return Most recent gravity reading [x, y, z] in m/s², or {@code null}. */
    public float[] getGravity()       { return gravityValues; }

    /** @return Most recent magnetic field reading [x, y, z] in μT, or {@code null}. */
    public float[] getMagneticField() { return magValues; }

    /** @return Most recent gyroscope reading [x, y, z] in rad/s, or {@code null}. */
    public float[] getGyroscope()     { return gyroValues; }
}
