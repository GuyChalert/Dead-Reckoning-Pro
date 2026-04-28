package nisargpatel.deadreckoning.sensor;

import android.location.Location;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.bias.GyroscopeBias;
import nisargpatel.deadreckoning.model.Trip;
import nisargpatel.deadreckoning.model.TurnEvent;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.slam.GraphSlamEngine;
import nisargpatel.deadreckoning.stepcounting.StaticStepCounter;

/**
 * Central dead-reckoning engine that fuses step detection, heading estimation,
 * GPS calibration, and Graph-SLAM into a continuously updated position estimate.
 *
 * <p>Coordinate system: local Cartesian ENU (East-North-Up) origin at the first
 * GPS fix. X = East (m), Y = North (m). A GeoPoint is derived from X/Y for map
 * display. Barometric altitude is tracked separately relative to session start.
 *
 * <p>Lifecycle: call {@link #start()} before feeding sensor data; call {@link #stop()}
 * when the session ends to finalise the trip record.
 */
public class DeadReckoningEngine {

    private final EnhancedStepCounter stepCounter;
    private final PreciseHeadingEstimator headingEstimator;
    private final GPSCalibrator gpsCalibrator;
    private final GraphSlamEngine slamEngine;

    private double currentX = 0;
    private double currentY = 0;
    private double currentHeading = 0;
    private double previousHeading = 0;
    private double totalDistance = 0;

    // Barometric elevation (metres relative to session start)
    private float  pressureAtStart  = Float.NaN;
    private double currentElevation = 0;
    
    private boolean isRunning = false;
    private Location startLocation = null;
    private Location lastGPSLocation = null;
    
    private Trip currentTrip = null;
    private final List<TurnEvent> turnEvents = new ArrayList<>();
    
    private static final double TURN_THRESHOLD = 25.0;
    private static final int MIN_STEPS_BETWEEN_TURNS = 5;
    private int stepsSinceLastTurn = 0;
    private double accumulatedHeadingChange = 0;
    private boolean isTurning = false;
    
    private int androidStepCount = 0;
    private int staticStepCount = 0;
    private StepCounterPreferences.StepMode stepMode = StepCounterPreferences.StepMode.DYNAMIC;

    private StaticStepCounter staticStepCounter;
    // Online gyro bias tracker updated at ZUPT (stationary) windows
    private final GyroscopeBias gyroBias;
    private float[] lastRawGyro = null;

    public DeadReckoningEngine() {
        stepCounter = new EnhancedStepCounter();
        headingEstimator = new PreciseHeadingEstimator();
        gpsCalibrator = new GPSCalibrator();
        gyroBias = new GyroscopeBias(200);
        slamEngine = new GraphSlamEngine();
        staticStepCounter = new StaticStepCounter(2, 1.9);
    }
    
    /**
     * Selects which step-detection source drives position updates.
     *
     * @param mode {@link StepCounterPreferences.StepMode#DYNAMIC} (Weinberg+hardware gate),
     *             {@code STATIC} (fixed thresholds), or {@code ANDROID} (hardware pedometer).
     */
    public void setStepMode(StepCounterPreferences.StepMode mode) {
        this.stepMode = mode;
    }

    /**
     * Starts a new tracking session. Resets all state and creates a new
     * {@link Trip} record. Must be called before any {@link #updateSensors} calls.
     */
    public void start() {
        isRunning = true;
        stepCounter.reset();
        headingEstimator.reset();
        currentTrip = new Trip();
        turnEvents.clear();
        useManualHeading = false;

        currentX = 0;
        currentY = 0;
        currentHeading = 0;
        previousHeading = 0;
        totalDistance = 0;
        accumulatedHeadingChange = 0;
        stepsSinceLastTurn = 0;
        pressureAtStart = Float.NaN;
        currentElevation = 0;
    }
    
    /**
     * Stops the session, finalises the trip (sets end time, start/end coordinates,
     * totals), and marks the engine as not running.
     */
    public void stop() {
        isRunning = false;
        
        if (currentTrip != null) {
            currentTrip.setTotalDistance(totalDistance);
            currentTrip.setTotalSteps(stepCounter.getStepCount());
            currentTrip.setTurnEvents(new ArrayList<>(turnEvents));
            currentTrip.finish();
        }
    }

    /** Clears GPS calibration data (scale factor and heading bias). */
    public void resetCalibration() {
        gpsCalibrator.resetCalibration();
    }

    /**
     * Fully resets the engine without starting a new session.
     * Clears position, heading, step counts, SLAM state, and GPS calibration.
     */
    public void reset() {
        currentX = 0;
        currentY = 0;
        currentHeading = 0;
        previousHeading = 0;
        totalDistance = 0;
        stepCounter.reset();
        headingEstimator.reset();
        gpsCalibrator.resetCalibration();
        
        currentTrip = null;
        turnEvents.clear();
        
        accumulatedHeadingChange = 0;
        stepsSinceLastTurn = 0;
        isTurning = false;
        
        startLocation = null;
        lastGPSLocation = null;
    }
    
    /**
     * Processes one set of sensor readings. Call from the sensor-event thread.
     * Any parameter may be {@code null} if the sensor did not fire this cycle.
     *
     * @param gravity      TYPE_ACCELEROMETER (gravity + linear accel) [x, y, z] in m/s².
     * @param magnetic     TYPE_MAGNETIC_FIELD (uncalibrated) [x, y, z] in μT.
     * @param gyro         TYPE_GYROSCOPE (bias-corrected by engine) [x, y, z] in rad/s.
     * @param linearAccel  TYPE_LINEAR_ACCELERATION (gravity removed) [x, y, z] in m/s².
     * @param timestamp    Sensor event timestamp in nanoseconds (ns).
     */
    public void updateSensors(float[] gravity, float[] magnetic, float[] gyro, float[] linearAccel, long timestamp) {
        if (!isRunning) return;

        if (gravity != null) {
            headingEstimator.updateGravity(gravity);
        }
        if (magnetic != null) {
            headingEstimator.updateMagneticField(magnetic);
        }
        if (gyro != null) {
            lastRawGyro = gyro;
            float[] correctedGyro = new float[]{
                gyro[0] - gyroBias.getBias()[0],
                gyro[1] - gyroBias.getBias()[1],
                gyro[2] - gyroBias.getBias()[2]
            };
            headingEstimator.updateGyroscope(correctedGyro, timestamp);
            float gyroMag = (float) Math.sqrt(
                correctedGyro[0] * correctedGyro[0] +
                correctedGyro[1] * correctedGyro[1] +
                correctedGyro[2] * correctedGyro[2]);
            stepCounter.setGyroMagnitude(gyroMag);
            staticStepCounter.setGyroMagnitude(gyroMag);
        }

        // Update heading whenever we have any valid orientation source
        if (headingEstimator.hasValidData()) {
            previousHeading = currentHeading;
            if (!useManualHeading) {
                currentHeading = headingEstimator.getHeadingDegrees();
            }
            detectTurn();
        }

        if (linearAccel != null) {
            double magnitude = Math.sqrt(
                linearAccel[0] * linearAccel[0] +
                linearAccel[1] * linearAccel[1] +
                linearAccel[2] * linearAccel[2]
            );

            boolean dynamicDetected = stepCounter.detectStep(linearAccel, timestamp);
            boolean staticDetected = staticStepCounter.findStep(magnitude);
            boolean detected = false;

            switch (stepMode) {
                case DYNAMIC:
                    detected = dynamicDetected;
                    break;
                case STATIC:
                    detected = staticDetected;
                    break;
                case ANDROID:
                    detected = false;
                    break;
            }

            if (detected && !stepCounter.isStationary()) {
                updatePosition();
                stepsSinceLastTurn++;
            }

            // ZUPT: while stationary, update gyro bias online to track thermal drift
            if (stepCounter.isStationary() && lastRawGyro != null) {
                gyroBias.updateFromZupt(lastRawGyro);
            }
        }
    }
    
    /**
     * Registers one hardware step event from Android's TYPE_STEP_DETECTOR.
     * Advances position only when {@link StepCounterPreferences.StepMode#ANDROID} is active.
     */
    public void addAndroidStep() {
        if (!isRunning) return;
        
        androidStepCount++;
        
        if (stepMode == StepCounterPreferences.StepMode.ANDROID) {
            updatePosition();
            stepsSinceLastTurn++;
        }
    }
    
    /**
     * Detects a turn by accumulating heading delta since the last step.
     * A turn event is emitted when the accumulated change exceeds
     * {@link #TURN_THRESHOLD} (°) and at least {@link #MIN_STEPS_BETWEEN_TURNS}
     * steps have elapsed since the previous turn.
     */
    private void detectTurn() {
        double headingDelta = currentHeading - previousHeading;
        
        if (Math.abs(headingDelta) > 180) {
            if (headingDelta > 0) {
                headingDelta -= 360;
            } else {
                headingDelta += 360;
            }
        }
        
        if (Math.abs(headingDelta) > 5 && !isTurning) {
            isTurning = true;
            accumulatedHeadingChange = 0;
        }
        
        if (isTurning) {
            accumulatedHeadingChange += headingDelta;
            
            if (Math.abs(accumulatedHeadingChange) >= TURN_THRESHOLD && stepsSinceLastTurn >= MIN_STEPS_BETWEEN_TURNS) {
                TurnEvent.TurnType turnType = TurnEvent.determineTurnType(accumulatedHeadingChange);
                
                if (turnType != null && Math.abs(accumulatedHeadingChange) < 270) {
                    addTurnEvent(turnType, accumulatedHeadingChange);
                }
                
                isTurning = false;
                accumulatedHeadingChange = 0;
                stepsSinceLastTurn = 0;
            } else if (Math.abs(accumulatedHeadingChange) < 5) {
                isTurning = false;
                accumulatedHeadingChange = 0;
            }
        }
    }
    
    /**
     * Creates and records an auto-detected turn event at the current path position.
     *
     * @param type          Classified turn type.
     * @param headingChange Accumulated signed heading change that triggered the turn (°).
     */
    private void addTurnEvent(TurnEvent.TurnType type, double headingChange) {
        double lat = 0, lon = 0;
        
        if (currentTrip != null && !currentTrip.getPathPoints().isEmpty()) {
            GeoPoint lastPoint = currentTrip.getPathPoints().get(currentTrip.getPathPoints().size() - 1);
            lat = lastPoint.getLatitude();
            lon = lastPoint.getLongitude();
        }
        
        TurnEvent event = new TurnEvent(type, headingChange, stepCounter.getStepCount(), lat, lon);
        turnEvents.add(event);
        
        if (currentTrip != null) {
            currentTrip.addTurnEvent(event);
        }
    }
    
    /**
     * Advances the ENU position by one stride along the current heading.
     * Uses GPS-corrected heading and the Weinberg/fixed stride length.
     * Also feeds the incremental odometry into the Graph-SLAM engine and
     * appends the new GeoPoint to the current trip.
     */
    private void updatePosition() {
        double strideLength = stepCounter.getStrideLength();
        double correctedHeading = gpsCalibrator.correctHeading(currentHeading);
        double headingRadians = Math.toRadians(correctedHeading);

        // Body-frame step: stride along heading direction
        double dxBody = strideLength * Math.sin(headingRadians);
        double dyBody = strideLength * Math.cos(headingRadians);

        currentX += dxBody;
        currentY += dyBody;
        totalDistance += strideLength;

        // Feed incremental odometry into Graph-SLAM
        slamEngine.addOdometryStep(dxBody, dyBody, Math.toRadians(correctedHeading - previousHeading));

        if (currentTrip != null) {
            GeoPoint point = calculateGeoPoint(currentX, currentY);
            if (point != null) {
                currentTrip.addPoint(point);
            }
        }
    }
    
    /**
     * Converts a local ENU displacement to a WGS-84 GeoPoint using a flat-Earth
     * approximation (valid for distances up to ~10 km from the origin).
     *
     * @param x East displacement from session origin in meters (m).
     * @param y North displacement from session origin in meters (m).
     * @return Corresponding WGS-84 GeoPoint, or {@code null} if no start location set.
     */
    private GeoPoint calculateGeoPoint(double x, double y) {
        if (startLocation == null) return null;
        
        double earthRadius = 6371000; // meters
        double latRad = Math.toRadians(startLocation.getLatitude());
        double lonRad = Math.toRadians(startLocation.getLongitude());
        
        double newLatRad = latRad + y / earthRadius;
        double newLonRad = lonRad + x / (earthRadius * Math.cos(latRad));
        
        return new GeoPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad));
    }
    
    /**
     * Register a barometric pressure reading from TYPE_PRESSURE sensor.
     * First reading calibrates the baseline; subsequent readings compute
     * relative altitude change in metres using the hypsometric formula.
     */
    public void updateBarometer(float hPa) {
        if (!isRunning) return;
        if (Float.isNaN(pressureAtStart)) {
            pressureAtStart = hPa;
            currentElevation = 0;
        } else {
            // Hypsometric formula (valid for small ΔP, ~10 cm resolution with BMP5xx)
            currentElevation = 44330.0 * (1.0 - Math.pow(hPa / pressureAtStart, 1.0 / 5.255));
        }
    }

    /**
     * Register a painted distance marker (e.g. "KM 12" → 12000.0 m).
     * Adds a landmark constraint to the Graph-SLAM engine and triggers optimization.
     */
    public void addLandmarkDistance(double measuredMetres) {
        slamEngine.addLandmarkDistance(measuredMetres);
    }

    /**
     * Incorporates a GPS fix into calibration.
     * The first fix (accuracy ≤ 20 m) sets the ENU origin and SLAM anchor.
     * Subsequent fixes refine the scale factor and heading bias via
     * {@link GPSCalibrator} and add SLAM anchor constraints.
     *
     * @param location GPS fix; ignored if {@code null}, missing accuracy, or accuracy > 20 m.
     */
    public void calibrateWithGPS(Location location) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() > 20) {
            return;
        }

        if (startLocation == null) {
            startLocation = location;
            lastGPSLocation = location;

            if (currentTrip != null) {
                currentTrip.addPoint(new GeoPoint(location.getLatitude(), location.getLongitude()));
            }

            // First GPS fix becomes the SLAM ENZ origin
            slamEngine.addGpsAnchor(location);
            return;
        }

        gpsCalibrator.addCalibrationPoint(location, currentX, currentY);

        double[] corrected = gpsCalibrator.correctPosition(currentX, currentY, currentHeading);
        currentX = corrected[0];
        currentY = corrected[1];

        lastGPSLocation = location;

        // Intermediate GPS fix: add as anchor to constrain SLAM graph
        slamEngine.addGpsAnchor(location);
    }
    
    /** @return East displacement from session origin in meters (m). */
    public double getX() {
        return currentX;
    }

    /** @return North displacement from session origin in meters (m). */
    public double getY() {
        return currentY;
    }

    /**
     * @return GPS-corrected heading in degrees (°), range [−180, 180].
     *         0° = North, 90° = East.
     */
    public double getHeading() {
        return gpsCalibrator.correctHeading(currentHeading);
    }
    
    /**
     * Returns the step count for the currently active step mode.
     *
     * @return Step count from the selected source (DYNAMIC, STATIC, or ANDROID).
     */
    public int getStepCount() {
        switch (stepMode) {
            case DYNAMIC:
                return stepCounter.getStepCount();
            case STATIC:
                return staticStepCounter.getStepCount();
            case ANDROID:
                return androidStepCount;
            default:
                return stepCounter.getStepCount();
        }
    }
    
    /** @return Step count from the dynamic (Weinberg+hardware-gate) counter. */
    public int getDynamicStepCount() {
        return stepCounter.getStepCount();
    }

    /** @return Step count from the static (fixed-threshold) counter. */
    public int getStaticStepCount() {
        return staticStepCounter.getStepCount();
    }

    /** @return Step count from Android's hardware pedometer (TYPE_STEP_DETECTOR). */
    public int getAndroidStepCount() {
        return androidStepCount;
    }

    /** @return Cumulative path length in meters (m) since {@link #start()}. */
    public double getDistance() {
        return totalDistance;
    }

    /**
     * Calculates straight-line GPS distance from the session start to a given fix.
     *
     * @param gpsLocation Target GPS location.
     * @return Distance in meters (m), or -1 if no start location is available.
     */
    public double getDistanceFromGPS(Location gpsLocation) {
        if (startLocation == null || gpsLocation == null) {
            return -1;
        }
        return startLocation.distanceTo(gpsLocation);
    }
    
    /** @return {@code true} once enough GPS fixes have been accumulated for calibration. */
    public boolean isCalibrated() {
        return gpsCalibrator.isCalibrated();
    }

    /** @return Number of GPS calibration fixes collected so far. */
    public int getCalibrationPoints() {
        return gpsCalibrator.getCalibrationPointCount();
    }

    /**
     * Forwards a hardware TYPE_STEP_DETECTOR event to the enhanced step counter.
     * Required for the hardware gate to function in DYNAMIC mode.
     *
     * @param timestampNs Hardware step event timestamp in nanoseconds (ns).
     */
    public void notifyHardwareStep(long timestampNs) {
        stepCounter.notifyHardwareStep(timestampNs);
    }

    /**
     * Signals whether the device has a hardware TYPE_STEP_DETECTOR sensor.
     * If {@code false}, the hardware gate in DYNAMIC mode is disabled.
     *
     * @param available {@code true} if hardware step detector is present and registered.
     */
    public void setHardwareStepDetectorAvailable(boolean available) {
        stepCounter.setHardwareStepDetectorAvailable(available);
    }

    /**
     * Sets a fixed stride length and disables Weinberg adaptive estimation.
     *
     * @param meters Fixed stride length in meters (m).
     */
    public void setStrideLength(double meters) {
        stepCounter.setStrideLength(meters);
    }

    /** @return Current stride length in meters (m). */
    public double getStrideLength() {
        return stepCounter.getStrideLength();
    }
    
    /** @return {@code true} if a session is active (between {@link #start()} and {@link #stop()}). */
    public boolean isRunning() {
        return isRunning;
    }

    /** @return First GPS fix used as the ENU origin, or {@code null} if not yet received. */
    public Location getStartLocation() {
        return startLocation;
    }

    /** @return Most recent GPS fix used for calibration, or {@code null} if none received. */
    public Location getLastGPSLocation() {
        return lastGPSLocation;
    }

    /**
     * @return GPS-derived scale factor (GPS distance / dead-reckoning distance).
     *         Returns 1.0 if not yet calibrated.
     */
    public double getScaleFactor() {
        return gpsCalibrator.getScaleFactor();
    }

    /** @return Current trip record (non-null after {@link #start()}). */
    public Trip getCurrentTrip() {
        return currentTrip;
    }

    /** @return Snapshot of all turn events recorded so far this session. */
    public List<TurnEvent> getTurnEvents() {
        return new ArrayList<>(turnEvents);
    }

    /** @return {@code true} if a turn is currently being accumulated. */
    public boolean isTurning() {
        return isTurning;
    }

    /**
     * @return Heading change accumulated so far during an in-progress turn, in degrees (°).
     */
    public double getAccumulatedHeadingChange() {
        return accumulatedHeadingChange;
    }

    private static final double MANUAL_TURN_ANGLE = 90.0;
    private boolean useManualHeading = false;
    private double manualHeadingOffset = 0;

    /**
     * Manually rotates heading 90° counter-clockwise and records a LEFT turn event.
     * Switches the engine to manual heading mode, bypassing compass/gyro.
     */
    public void turnLeft() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading -= MANUAL_TURN_ANGLE;
        
        while (currentHeading < 0) {
            currentHeading += 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.LEFT, -MANUAL_TURN_ANGLE);
    }

    /**
     * Manually rotates heading 90° clockwise and records a RIGHT turn event.
     * Switches the engine to manual heading mode, bypassing compass/gyro.
     */
    public void turnRight() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading += MANUAL_TURN_ANGLE;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.RIGHT, MANUAL_TURN_ANGLE);
    }

    /**
     * Manually rotates heading 180° and records a U-TURN event.
     * Switches the engine to manual heading mode, bypassing compass/gyro.
     */
    public void turnAround() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading += 180;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.UTURN, 180);
    }

    /**
     * Creates and records a manually-triggered turn event at the current path position.
     *
     * @param type  Turn type.
     * @param angle Signed heading change applied, in degrees (°).
     */
    private void addManualTurnEvent(TurnEvent.TurnType type, double angle) {
        double lat = 0, lon = 0;
        
        if (currentTrip != null && !currentTrip.getPathPoints().isEmpty()) {
            GeoPoint lastPoint = currentTrip.getPathPoints().get(currentTrip.getPathPoints().size() - 1);
            lat = lastPoint.getLatitude();
            lon = lastPoint.getLongitude();
        }
        
        TurnEvent event = new TurnEvent(type, angle, stepCounter.getStepCount(), lat, lon);
        turnEvents.add(event);
        
        if (currentTrip != null) {
            currentTrip.addTurnEvent(event);
        }
    }
    
    /**
     * Locks the heading to a fixed value, bypassing all sensor estimates.
     *
     * @param heading Fixed heading in degrees (°); 0 = North, 90 = East.
     */
    public void setFixedHeading(double heading) {
        currentHeading = heading;
        useManualHeading = true;
    }

    /** Switches back from manual heading mode to sensor-derived heading (compass/gyro). */
    public void resetToCompassHeading() {
        useManualHeading = false;
    }

    /** Relative altitude in metres since session start (from barometer). */
    public double getElevation() { return currentElevation; }

    /** Current pressure in hPa (reference at session start). */
    public float getPressureAtStart() { return pressureAtStart; }

    /** Access Graph-SLAM engine (e.g. for loop-closure or path export). */
    public GraphSlamEngine getSlamEngine() { return slamEngine; }
}
