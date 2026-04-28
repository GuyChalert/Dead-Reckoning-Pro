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

    /**
     * Updates the latest raw pressure reading.
     *
     * @param hPa Atmospheric pressure in hectopascals (hPa) from TYPE_PRESSURE.
     */
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

    /**
     * Enables or disables barometer-derived altitude.
     * When disabled, {@link #getAltitudeM()} returns the manual elevation.
     *
     * @param enabled {@code true} to use barometer; {@code false} to use manual value.
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** @return {@code true} if barometer-derived altitude is active. */
    public boolean isEnabled() { return enabled; }

    /**
     * Sets the fallback manual elevation returned when the barometer is disabled
     * or no pressure reading has been received yet.
     *
     * @param altM Manual elevation above sea level in meters (m).
     */
    public void setManualElevation(float altM) { manualElevationM = altM; }

    /** @return Manual elevation fallback in meters (m). */
    public float getManualElevation() { return manualElevationM; }

    /** @return Current calibration offset added to raw ISA altitude, in meters (m). */
    public float getCalibrationOffsetM() { return calibrationOffsetM; }

    /**
     * Directly sets the altitude calibration offset.
     *
     * @param offsetM Offset in meters (m) to add to raw ISA altitude.
     */
    public void setCalibrationOffset(float offsetM) { calibrationOffsetM = offsetM; }

    /** @return {@code true} if at least one pressure reading has been received. */
    public boolean hasPressureReading() { return !Float.isNaN(lastPressureHpa); }

    /** @return Most recent raw pressure reading in hectopascals (hPa). */
    public float getLastPressureHpa() { return lastPressureHpa; }
}
