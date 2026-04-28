package nisargpatel.deadreckoning.stepcounting;

/**
 * Fixed-threshold peak-detection step counter.
 *
 * <p>Detects a step when the acceleration magnitude rises above
 * {@code upperThreshold}, then resets the detector once it falls below
 * {@code lowerThreshold} (hysteresis). An optional gyroscope magnitude gate
 * suppresses counts while the device is rotating to avoid false positives.
 *
 * <p>Input is expected to be the total acceleration magnitude including gravity
 * (TYPE_ACCELEROMETER), in m/s².
 */
public class StaticStepCounter {

	private boolean peakFound;
	/** Upper detection threshold (m/s²). A peak above this triggers a step. */
	private double upperThreshold;
	/** Lower reset threshold (m/s²). Signal must fall below this before next step. */
	private double lowerThreshold;
    private int stepCount;
    /** Most recent gyroscope magnitude in rad/s; used to suppress rotation-induced false steps. */
    private float gyroMagnitude = 0f;
    /** Steps are suppressed when gyro magnitude exceeds this value (rad/s). */
    private static final float ROTATION_SUPPRESSION_RADS = 0.4f;

    /**
     * Creates a counter with default thresholds tuned for walking:
     * upper = 10.8 m/s², lower = 8.8 m/s².
     */
    public StaticStepCounter() {
        upperThreshold = 10.8;
        lowerThreshold = 8.8;
        stepCount = 0;
        peakFound = false;
    }

    /**
     * @param upper Upper detection threshold in m/s².
     * @param lower Lower reset threshold in m/s².
     */
	public StaticStepCounter(double upper, double lower) {
        this();
        upperThreshold = upper;
        lowerThreshold = lower;
	}

    /**
     * Updates detection thresholds at runtime.
     *
     * @param upper New upper detection threshold in m/s².
     * @param lower New lower reset threshold in m/s².
     */
	public void setThresholds(double upper, double lower) {
		upperThreshold = upper;
		lowerThreshold = lower;
	}

    /**
     * Updates the gyroscope magnitude used to gate step detection.
     * Must be called before each {@link #findStep} call for suppression to work.
     *
     * @param mag Current gyroscope magnitude in rad/s.
     */
    public void setGyroMagnitude(float mag) { gyroMagnitude = mag; }

    /**
     * Processes one acceleration sample and returns whether a step was detected.
     * Steps are suppressed when {@code gyroMagnitude ≥ ROTATION_SUPPRESSION_RADS}.
     *
     * @param acc Total acceleration magnitude in m/s² (gravity-inclusive).
     * @return {@code true} if a step peak was detected on this sample.
     */
	public boolean findStep(double acc) {

		//if no new peak is found, then the program will look for a peak which is above the upperThreshold
		if (!peakFound) {
			if (acc > upperThreshold && gyroMagnitude < ROTATION_SUPPRESSION_RADS) {
				peakFound = true;
                stepCount++;
				return true;
			}
		}

		//after a new peak is found, program will find no more peaks until graph passes under lowerThreshold
		if (peakFound) {
			if (acc < lowerThreshold) {
				peakFound = false;
			}
		}

		return false;
	}

    /** @return Upper detection threshold in m/s². */
    public double getUpperThreshold() {
        return upperThreshold;
    }

    /** @return Lower reset threshold in m/s². */
    public double getLowerThreshold() {
        return lowerThreshold;
    }

    /** @return Cumulative step count since construction or last {@link #clearStepCount()}. */
    public int getStepCount() {
        return stepCount;
    }

    /** Resets the cumulative step count to zero. */
    public void clearStepCount() {
        stepCount = 0;
    }
}