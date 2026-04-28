package nisargpatel.deadreckoning.sensor;

import android.location.Location;
import java.util.ArrayList;
import java.util.List;

/**
 * Calibrates dead-reckoning position and heading estimates against GPS fixes.
 *
 * <p>Accumulates pairs of (GPS location, DR position) and derives:
 * <ul>
 *   <li>A scale factor (GPS distance / DR distance) applied to X and Y.</li>
 *   <li>A heading bias (GPS bearing − DR bearing) applied as a correction offset.</li>
 * </ul>
 * Scale and bias are updated incrementally with EMA smoothing to reduce noise from
 * individual low-accuracy fixes. Calibration is considered valid after
 * {@link #MIN_CALIBRATION_POINTS} points.
 */
public class GPSCalibrator {

    private List<Location> calibrationPoints = new ArrayList<>();
    /** Dead-reckoning East positions (m) parallel to {@link #calibrationPoints}. */
    private List<Double> estimatedXs = new ArrayList<>();
    /** Dead-reckoning North positions (m) parallel to {@link #calibrationPoints}. */
    private List<Double> estimatedYs = new ArrayList<>();

    private boolean isCalibrated = false;
    private int calibrationCount = 0;

    /** Scale factor for X (East) axis; updated incrementally. */
    private double scaleFactorX = 1.0;
    /** Scale factor for Y (North) axis; kept equal to scaleFactorX (isotropic assumption). */
    private double scaleFactorY = 1.0;
    /** Heading correction offset in degrees (°); GPS bearing − DR bearing, EMA-smoothed. */
    private double headingBias = 0.0;

    /** Minimum GPS fixes needed before calibration is considered valid. */
    public static final int MIN_CALIBRATION_POINTS = 3;
    /** Maximum stored calibration history; oldest entries discarded beyond this. */
    public static final int MAX_CALIBRATION_POINTS = 20;

    /**
     * Adds a GPS–DR position pair to the calibration history and triggers
     * recalibration once enough points are available.
     *
     * @param gpsLocation  GPS fix for this position; ignored if {@code null}.
     * @param estimatedX   Dead-reckoning East displacement at this fix (m).
     * @param estimatedY   Dead-reckoning North displacement at this fix (m).
     */
    public void addCalibrationPoint(Location gpsLocation, double estimatedX, double estimatedY) {
        if (gpsLocation == null) return;
        
        calibrationPoints.add(gpsLocation);
        estimatedXs.add(estimatedX);
        estimatedYs.add(estimatedY);
        
        if (calibrationPoints.size() > MAX_CALIBRATION_POINTS) {
            calibrationPoints.remove(0);
            estimatedXs.remove(0);
            estimatedYs.remove(0);
        }
        
        if (calibrationPoints.size() >= MIN_CALIBRATION_POINTS) {
            recalculateCalibration();
        }
    }
    
    /**
     * Recalculates scale factor and heading bias from the two most recent
     * calibration points. Scale uses EMA (α = 0.2); bias uses EMA (α = 0.1).
     * Requires at least 2 points and a minimum DR displacement of 0.5 m to
     * avoid division-by-zero on nearly stationary fixes.
     */
    private void recalculateCalibration() {
        int size = calibrationPoints.size();
        if (size < 2) return;
        
        Location lastGps = calibrationPoints.get(size - 1);
        Location prevGps = calibrationPoints.get(size - 2);
        
        double lastX = estimatedXs.get(size - 1);
        double lastY = estimatedYs.get(size - 1);
        double prevX = estimatedXs.get(size - 2);
        double prevY = estimatedYs.get(size - 2);
        
        double gpsDistance = lastGps.distanceTo(prevGps);
        double estimatedDistance = Math.sqrt(
            Math.pow(lastX - prevX, 2) + 
            Math.pow(lastY - prevY, 2)
        );
        
        if (estimatedDistance > 0.5) {
            double currentScale = gpsDistance / estimatedDistance;
            // Simple moving average to smooth scale factor
            scaleFactorX = (scaleFactorX * 0.8) + (currentScale * 0.2);
            scaleFactorY = scaleFactorX;
        }
        
        headingBias = calculateHeadingBias();
        
        calibrationCount++;
        isCalibrated = calibrationCount >= MIN_CALIBRATION_POINTS;
    }
    
    /**
     * Computes the angular difference between the GPS bearing and the DR bearing
     * for the two most recent calibration points, and blends it into the running
     * heading bias estimate.
     *
     * @return Updated heading bias in degrees (°).
     */
    private double calculateHeadingBias() {
        int size = calibrationPoints.size();
        if (size < 2) return headingBias;
        
        Location last = calibrationPoints.get(size - 1);
        Location prev = calibrationPoints.get(size - 2);
        
        double lastX = estimatedXs.get(size - 1);
        double lastY = estimatedYs.get(size - 1);
        double prevX = estimatedXs.get(size - 2);
        double prevY = estimatedYs.get(size - 2);
        
        double gpsBearing = prev.bearingTo(last); // bearing from prev to last
        double estimatedBearing = Math.toDegrees(Math.atan2(lastX - prevX, lastY - prevY));
        
        double diff = gpsBearing - estimatedBearing;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        
        // Blend with existing bias
        return (headingBias * 0.9) + (diff * 0.1);
    }
    
    /**
     * Applies the calibrated scale factor to a DR position estimate.
     * Returns the uncorrected position if not yet calibrated.
     *
     * @param estimatedX  Dead-reckoning East displacement in meters (m).
     * @param estimatedY  Dead-reckoning North displacement in meters (m).
     * @param heading     Current heading in degrees (°); not currently used but
     *                    reserved for future rotation correction.
     * @return {@code [correctedX, correctedY]} in meters (m).
     */
    public double[] correctPosition(double estimatedX, double estimatedY, double heading) {
        if (!isCalibrated) {
            return new double[]{estimatedX, estimatedY};
        }
        
        double correctedX = estimatedX * scaleFactorX;
        double correctedY = estimatedY * scaleFactorY;
        
        return new double[]{correctedX, correctedY};
    }
    
    /**
     * Applies the calibrated heading bias and normalises to [−180, 180].
     * Returns the raw heading if not yet calibrated.
     *
     * @param heading Raw DR heading in degrees (°).
     * @return Corrected heading in degrees (°), range [−180, 180].
     */
    public double correctHeading(double heading) {
        if (!isCalibrated) return heading;
        
        double corrected = heading + headingBias;
        while (corrected > 180) corrected -= 360;
        while (corrected < -180) corrected += 360;
        
        return corrected;
    }
    
    /** Clears all calibration data and resets scale factor and heading bias to defaults. */
    public void resetCalibration() {
        calibrationPoints.clear();
        estimatedXs.clear();
        estimatedYs.clear();
        isCalibrated = false;
        calibrationCount = 0;
        scaleFactorX = 1.0;
        scaleFactorY = 1.0;
        headingBias = 0.0;
    }
    
    /** @return {@code true} once {@link #MIN_CALIBRATION_POINTS} GPS fixes have been processed. */
    public boolean isCalibrated() {
        return isCalibrated;
    }

    /** @return Number of GPS calibration fixes currently in the history window. */
    public int getCalibrationPointCount() {
        return calibrationPoints.size();
    }

    /** @return The most recently added GPS fix, or {@code null} if none. */
    public Location getLastKnownLocation() {
        if (calibrationPoints.isEmpty()) return null;
        return calibrationPoints.get(calibrationPoints.size() - 1);
    }
    
    /**
     * @return Average of X and Y scale factors; 1.0 = perfect DR accuracy.
     *         Values > 1 mean DR underestimates distance; < 1 means it overestimates.
     */
    public double getScaleFactor() {
        return (scaleFactorX + scaleFactorY) / 2.0;
    }
}
