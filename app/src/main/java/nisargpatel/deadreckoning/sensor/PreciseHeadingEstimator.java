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

    public void updateGravity(float[] values) {
        gravityValues = values.clone();
    }

    public void updateMagneticField(float[] values) {
        magValues = values.clone();
    }

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

    public double getHeading() {
        if (hasRotationVector && rotationVectorValues != null) {
            return headingFromRotationVector();
        }
        return headingFromFallback();
    }

    private double headingFromRotationVector() {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorValues);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        return Math.toDegrees(orientationAngles[0]);
    }

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

    private static double wrapRadians(double r) {
        while (r >  Math.PI) r -= 2 * Math.PI;
        while (r < -Math.PI) r += 2 * Math.PI;
        return r;
    }

    public double getHeadingDegrees() {
        return getHeading();
    }

    public void reset() {
        heading = 0;
        lastTimestamp = 0;
        hasRotationVector = false;
        rotationVectorValues = null;
        gravityValues = null;
        magValues = null;
        gyroValues = null;
    }

    public boolean hasValidData() {
        return hasRotationVector || (gravityValues != null && magValues != null);
    }

    public boolean usingRotationVector() {
        return hasRotationVector;
    }

    public float[] getGravity()       { return gravityValues; }
    public float[] getMagneticField() { return magValues; }
    public float[] getGyroscope()     { return gyroValues; }
}
