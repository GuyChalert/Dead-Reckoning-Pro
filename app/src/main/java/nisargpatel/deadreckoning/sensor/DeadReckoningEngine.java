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
    
    public void setStepMode(StepCounterPreferences.StepMode mode) {
        this.stepMode = mode;
    }
    
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
    
    public void stop() {
        isRunning = false;
        
        if (currentTrip != null) {
            currentTrip.setTotalDistance(totalDistance);
            currentTrip.setTotalSteps(stepCounter.getStepCount());
            currentTrip.setTurnEvents(new ArrayList<>(turnEvents));
            currentTrip.finish();
        }
    }

    public void resetCalibration() {
        gpsCalibrator.resetCalibration();
    }
    
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
    
    public void addAndroidStep() {
        if (!isRunning) return;
        
        androidStepCount++;
        
        if (stepMode == StepCounterPreferences.StepMode.ANDROID) {
            updatePosition();
            stepsSinceLastTurn++;
        }
    }
    
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
    
    public double getX() {
        return currentX;
    }
    
    public double getY() {
        return currentY;
    }
    
    public double getHeading() {
        return gpsCalibrator.correctHeading(currentHeading);
    }
    
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
    
    public int getDynamicStepCount() {
        return stepCounter.getStepCount();
    }
    
    public int getStaticStepCount() {
        return staticStepCounter.getStepCount();
    }
    
    public int getAndroidStepCount() {
        return androidStepCount;
    }
    
    public double getDistance() {
        return totalDistance;
    }
    
    public double getDistanceFromGPS(Location gpsLocation) {
        if (startLocation == null || gpsLocation == null) {
            return -1;
        }
        return startLocation.distanceTo(gpsLocation);
    }
    
    public boolean isCalibrated() {
        return gpsCalibrator.isCalibrated();
    }
    
    public int getCalibrationPoints() {
        return gpsCalibrator.getCalibrationPointCount();
    }
    
    public void notifyHardwareStep(long timestampNs) {
        stepCounter.notifyHardwareStep(timestampNs);
    }

    public void setHardwareStepDetectorAvailable(boolean available) {
        stepCounter.setHardwareStepDetectorAvailable(available);
    }

    public void setStrideLength(double meters) {
        stepCounter.setStrideLength(meters);
    }
    
    public double getStrideLength() {
        return stepCounter.getStrideLength();
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public Location getStartLocation() {
        return startLocation;
    }
    
    public Location getLastGPSLocation() {
        return lastGPSLocation;
    }
    
    public double getScaleFactor() {
        return gpsCalibrator.getScaleFactor();
    }
    
    public Trip getCurrentTrip() {
        return currentTrip;
    }
    
    public List<TurnEvent> getTurnEvents() {
        return new ArrayList<>(turnEvents);
    }
    
    public boolean isTurning() {
        return isTurning;
    }
    
    public double getAccumulatedHeadingChange() {
        return accumulatedHeadingChange;
    }

    private static final double MANUAL_TURN_ANGLE = 90.0;
    private boolean useManualHeading = false;
    private double manualHeadingOffset = 0;

    public void turnLeft() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading -= MANUAL_TURN_ANGLE;
        
        while (currentHeading < 0) {
            currentHeading += 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.LEFT, -MANUAL_TURN_ANGLE);
    }

    public void turnRight() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading += MANUAL_TURN_ANGLE;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.RIGHT, MANUAL_TURN_ANGLE);
    }

    public void turnAround() {
        if (!isRunning) return;
        
        useManualHeading = true;
        currentHeading += 180;
        
        while (currentHeading >= 360) {
            currentHeading -= 360;
        }
        
        addManualTurnEvent(TurnEvent.TurnType.UTURN, 180);
    }

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
    
    public void setFixedHeading(double heading) {
        currentHeading = heading;
        useManualHeading = true;
    }

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
