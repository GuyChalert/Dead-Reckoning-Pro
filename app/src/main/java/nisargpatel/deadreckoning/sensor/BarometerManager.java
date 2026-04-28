package nisargpatel.deadreckoning.sensor;

import android.hardware.SensorManager;

/**
 * Converts raw pressure (hPa) to altitude (m) using the ISA barometric formula.
 * Supports an optional calibration offset so the user can anchor readings to a
 * known elevation.
 */
public class BarometerManager {

    private static final float SEA_LEVEL_PRESSURE_HPA = SensorManager.PRESSURE_STANDARD_ATMOSPHERE;

    private volatile float lastPressureHpa = Float.NaN;
    private volatile float calibrationOffsetM = 0f; // added to raw altitude
    private volatile boolean enabled = true;
    private volatile float manualElevationM = 0f;   // used when barometer is disabled

    public void onPressureReading(float hPa) {
        lastPressureHpa = hPa;
    }

    /** Altitude derived from last pressure reading + calibration offset. */
    public float getAltitudeM() {
        if (!enabled) return manualElevationM;
        if (Float.isNaN(lastPressureHpa)) return manualElevationM;
        float raw = SensorManager.getAltitude(SEA_LEVEL_PRESSURE_HPA, lastPressureHpa);
        return raw + calibrationOffsetM;
    }

    /**
     * Calibrate: tell the barometer what the current altitude should be.
     * Computes and stores the offset to apply to future readings.
     */
    public void calibrateTo(float knownAltitudeM) {
        if (Float.isNaN(lastPressureHpa)) return;
        float raw = SensorManager.getAltitude(SEA_LEVEL_PRESSURE_HPA, lastPressureHpa);
        calibrationOffsetM = knownAltitudeM - raw;
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    public void setManualElevation(float altM) { manualElevationM = altM; }
    public float getManualElevation() { return manualElevationM; }

    public float getCalibrationOffsetM() { return calibrationOffsetM; }
    public void setCalibrationOffset(float offsetM) { calibrationOffsetM = offsetM; }

    public boolean hasPressureReading() { return !Float.isNaN(lastPressureHpa); }
    public float getLastPressureHpa() { return lastPressureHpa; }
}
