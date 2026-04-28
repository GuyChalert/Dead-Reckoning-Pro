package nisargpatel.deadreckoning.sensor;

/**
 * Scalar 1-D Kalman filter for smoothing a single noisy signal.
 *
 * <p>Implements the standard predict-correct cycle:
 * <ul>
 *   <li><b>Predict:</b> P ← P + Q (error grows by process noise)</li>
 *   <li><b>Correct:</b> K = P/(P+R); x ← x + K·(z−x); P ← (1−K)·P</li>
 * </ul>
 * Used here to smooth the linear-acceleration magnitude before step detection.
 */
public class KalmanFilter {

    /** Process noise covariance Q — controls how quickly the filter tracks changes. */
    private double q;
    /** Measurement noise covariance R — reflects sensor noise level. */
    private double r;
    /** Current state estimate x̂. */
    private double x;
    /** Estimate error covariance P. */
    private double p;
    /** Kalman gain K (computed each update cycle). */
    private double k;

    /**
     * @param processNoise     Process noise covariance Q; larger = faster tracking,
     *                         less smoothing. Typical: 0.001–0.1.
     * @param measurementNoise Measurement noise covariance R; reflects sensor noise.
     *                         Typical: 0.01–1.0.
     * @param initialEstimate  Initial state estimate x̂₀.
     * @param initialError     Initial estimate error covariance P₀.
     */
    public KalmanFilter(double processNoise, double measurementNoise, double initialEstimate, double initialError) {
        this.q = processNoise;
        this.r = measurementNoise;
        this.x = initialEstimate;
        this.p = initialError;
        this.k = 0;
    }

    /**
     * Runs one predict-correct cycle and returns the updated state estimate.
     *
     * @param measurement New raw sensor reading (same units as the state).
     * @return Filtered estimate x̂ after incorporating the measurement.
     */
    public double update(double measurement) {
        prediction();
        correction(measurement);
        return x;
    }

    /** Prediction step: grow error covariance by process noise Q. */
    private void prediction() {
        p = p + q;
    }

    /**
     * Correction step: compute Kalman gain, update estimate, shrink covariance.
     *
     * @param measurement New raw sensor reading.
     */
    private void correction(double measurement) {
        k = p / (p + r);
        x = x + k * (measurement - x);
        p = (1 - k) * p;
    }

    /**
     * Resets the filter state (e.g. after a long gap or a tracking start/stop).
     *
     * @param initialEstimate New initial state estimate x̂₀.
     * @param initialError    New initial error covariance P₀.
     */
    public void reset(double initialEstimate, double initialError) {
        this.x = initialEstimate;
        this.p = initialError;
        this.k = 0;
    }

    /** @return Current filtered state estimate x̂. */
    public double getEstimate() {
        return x;
    }

    /** @return Current estimate error covariance P. */
    public double getError() {
        return p;
    }
}
