package nisargpatel.deadreckoning.stepcounting;

/**
 * Peak-detection step counter with dynamically adapting thresholds.
 *
 * <p>Tracks a running exponential moving average of the acceleration magnitude
 * and sets upper/lower thresholds relative to it. A step is detected when
 * the signal crosses above the upper threshold (peak) and subsequently drops
 * below the lower threshold (valley), implementing a hysteresis detector.
 *
 * <p>Input is expected to be the total acceleration magnitude (gravity included),
 * in m/s² (from TYPE_ACCELEROMETER, not TYPE_LINEAR_ACCELERATION). For
 * gravity-removed input see {@link nisargpatel.deadreckoning.sensor.EnhancedStepCounter}.
 */
public class DynamicStepCounter {

    /** Number of samples used in the discrete threshold recalculation window. */
    public static final int REQUIRED_HZ = 500;

    private int stepCount;
    /** Half-band sensitivity added/subtracted around the moving average (m/s²). */
    private double sensitivity;
    /** Current upper detection threshold (m/s²). */
    private double upperThreshold;
    /** Current lower reset threshold (m/s²). */
    private double lowerThreshold;

    private boolean firstRun;
    /** True after a peak has been found; prevents double-counting until valley. */
    private boolean peakFound;

    private int upperCount, lowerCount;
    private double sumUpperAcc, sumLowerAcc;

    private double sumAcc, avgAcc;
    private int runCount;

    /**
     * Creates a counter with default sensitivity (1.0 m/s²) and
     * initial thresholds tuned for walking (upper: 10.8, lower: 8.8 m/s²).
     */
    public DynamicStepCounter() {

        stepCount = 0;
        sensitivity = 1.0;
        upperThreshold = 10.8;
        lowerThreshold = 8.8;

        firstRun = true;
        peakFound = false;

        upperCount = lowerCount = 0;
        sumUpperAcc = sumLowerAcc = 0;

        sumAcc = avgAcc = 0;
        runCount = 0;

    }

    /**
     * @param sensitivity Half-band around the moving average used to set
     *                    detection thresholds, in m/s².
     */
    public DynamicStepCounter(double sensitivity) {
        this();
        this.sensitivity = sensitivity;
    }

    /**
     * Processes one acceleration sample and returns whether a step was detected.
     * Updates thresholds continuously via EMA before checking for a peak.
     *
     * @param acc Total acceleration magnitude in m/s²
     *            (gravity-inclusive, from TYPE_ACCELEROMETER).
     * @return {@code true} if a step peak was detected on this sample.
     */
    public boolean findStep(double acc) {

        //set the thresholds that are used to find the peaks
//        setThresholdsDiscreet(acc);
        setThresholdsContinuous(acc);

        //finds a point (peak) above the upperThreshold
        if (acc > upperThreshold) {
            if (!peakFound) {
                stepCount++;
                peakFound = true;
                return true;
            }
        }

        //after a new peak is found, program will find no more peaks until graph passes under lowerThreshold
        else if (acc < lowerThreshold) {
            if (peakFound) {
                peakFound = false;
            }
        }

        return false;
    }

    /**
     * Discrete threshold update: recalculates upper/lower thresholds once every
     * {@link #REQUIRED_HZ} samples based on the mean of samples above/below the
     * running average. Less responsive than {@link #setThresholdsContinuous}.
     *
     * @param acc Acceleration magnitude in m/s² for this sample.
     */
    public void setThresholdsDiscreet(double acc) {

        runCount++;

        if (firstRun) {
            upperThreshold = acc + sensitivity;
            lowerThreshold = acc - sensitivity;
            avgAcc = acc;
            firstRun = false;
        }

        sumAcc += acc;

        if (acc > avgAcc) {
            sumUpperAcc += acc;
            upperCount++;
        }
        else if (acc < avgAcc) {
            sumLowerAcc += acc;
            lowerCount++;
        }

        if (runCount == REQUIRED_HZ) {
            avgAcc = sumAcc / REQUIRED_HZ;

            upperThreshold = (sumUpperAcc / upperCount) + sensitivity;
            lowerThreshold = (sumLowerAcc / lowerCount) - sensitivity;

            sumAcc = 0;

            sumUpperAcc = 0;
            upperCount = 0;

            sumLowerAcc = 0;
            lowerCount = 0;

            runCount = 0;
        }

    }

    /**
     * Continuous EMA threshold update: adjusts thresholds on every sample
     * (α = 0.02, τ ≈ 50 samples). Preferred over the discrete variant as it
     * adapts in real time to changes in walking pace.
     *
     * @param acc Acceleration magnitude in m/s² for this sample.
     */
    public void setThresholdsContinuous(double acc) {

        runCount++;

        if (firstRun) {
            upperThreshold = acc + sensitivity;
            lowerThreshold = acc - sensitivity;

            avgAcc = acc;

            firstRun = false;
            return;
        }

        // EMA — avoids freezing threshold as runCount grows unbounded
        avgAcc = 0.98 * avgAcc + 0.02 * acc;

        upperThreshold = avgAcc + sensitivity;
        lowerThreshold = avgAcc - sensitivity;

    }

    /** @return Current threshold half-band sensitivity in m/s². */
    public double getSensitivity() {
        return sensitivity;
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
