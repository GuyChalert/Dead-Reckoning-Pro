package nisargpatel.deadreckoning.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.model.Trip;
import nisargpatel.deadreckoning.sensor.DeadReckoningEngine;
import nisargpatel.deadreckoning.sensor.PreciseHeadingEstimator;
import nisargpatel.deadreckoning.storage.TripStorage;

/**
 * Main navigation activity showing a live osmdroid map with the dead-reckoning path.
 * Supports two modes set by the {@code view_mode} intent extra:
 * <ul>
 *   <li><b>Live mode</b> (default): streams IMU sensors through {@link DeadReckoningEngine} +
 *       {@link PreciseHeadingEstimator}, plots DR and GPS traces in different colours, and
 *       saves the trip to {@link TripStorage} on stop.</li>
 *   <li><b>View mode</b> ({@code view_mode=true}): loads a historical {@link Trip} by
 *       {@code trip_id} extra and replays its path on the map without activating sensors.</li>
 * </ul>
 * GPS fixes from FusedLocationProviderClient are used both for the GPS trace and to calibrate
 * the DR engine when accuracy &lt; 15 m. Forced no-GPS mode is also available for underground use.
 */
public class MainNavigationActivity extends AppCompatActivity implements SensorEventListener, LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST = 1001;

    private MapView mapView;
    private MyLocationNewOverlay locationOverlay;
    private Polyline pathOverlay;
    
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private SensorManager sensorManager;
    private Sensor sensorGravity;
    private Sensor sensorMagnetic;
    private Sensor sensorGyroscope;
    private Sensor sensorLinearAcceleration;
    private Sensor sensorGameRotationVector;

    private DeadReckoningEngine deadReckoningEngine;
    private PreciseHeadingEstimator headingEstimator;

    private TextView textSteps;
    private TextView textDistance;
    private TextView textHeading;
    private TextView textGPSAccuracy;
    private TextView textCalibrationStatus;
    private TextView textCoordinates;
    private Button buttonStartStop;
    private Button buttonPause;
    private Button buttonCalibrate;
    private Button buttonReset;
    private Button buttonCenterMap;
    private ImageView imageDirectionArrow;
    private MaterialCardView cardDirection;

    private List<GeoPoint> pathPoints = new ArrayList<>();
    private List<GeoPoint> noGPSPathPoints = new ArrayList<>();
    private GeoPoint currentPosition = null;
    private GeoPoint startPosition = null;
    private GeoPoint lastGPSPoint = null;
    private GeoPoint lastDeadReckoningPoint = null;

    private boolean isTracking = false;
    private boolean isPaused = false;
    private boolean useGPSForTrace = false;
    private boolean forceNoGPSMode = false;
    private long lastGPSMoveTime = 0;
    private double lastDeadReckoningDistance = 0;
    private double noGPSFixedHeading = 0;
    private Polyline noGPSPathOverlay;
    
    private boolean isViewMode = false;
    private Trip viewingTrip = null;
    private TripStorage tripStorage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_navigation);

        tripStorage = new TripStorage(this);
        
        isViewMode = getIntent().getBooleanExtra("view_mode", false);
        
        initializeViews();
        initializeSensors();
        initializeLocation();
        initializeMap();
        initializeEngine();
        checkPermissions();
        
        if (isViewMode) {
            loadTripForView();
        }
    }
    
    /** Loads the trip identified by the {@code trip_id} intent extra and replays its path; hides tracking controls. */
    private void loadTripForView() {
        try {
            String tripId = getIntent().getStringExtra("trip_id");
            if (tripId != null) {
                viewingTrip = tripStorage.getTrip(tripId);
                if (viewingTrip != null && viewingTrip.getPathPoints() != null) {
                    pathPoints.clear();
                    pathPoints.addAll(viewingTrip.getPathPoints());
                    
                    if (!pathPoints.isEmpty()) {
                        startPosition = pathPoints.get(0);
                        currentPosition = pathPoints.get(pathPoints.size() - 1);
                        
                        if (pathOverlay != null) {
                            pathOverlay.setPoints(pathPoints);
                        }
                        
                        if (textSteps != null) {
                            textSteps.setText(String.format(Locale.getDefault(), "Steps: %d", viewingTrip.getTotalSteps()));
                        }
                        if (textDistance != null) {
                            textDistance.setText(String.format(Locale.getDefault(), "Distance: %.2f m", viewingTrip.getTotalDistance()));
                        }
                        if (textCalibrationStatus != null) {
                            textCalibrationStatus.setText(String.format(Locale.getDefault(), "Viewing: %s", 
                                viewingTrip.getName() != null ? viewingTrip.getName() : "Trip"));
                        }
                        
                        centerOnCurrentPosition();
                    }
                }
            }
            
            if (buttonStartStop != null) {
                buttonStartStop.setVisibility(View.GONE);
            }
            if (buttonPause != null) {
                buttonPause.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading trip: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeViews() {
        textSteps = findViewById(R.id.textSteps);
        textDistance = findViewById(R.id.textDistance);
        textHeading = findViewById(R.id.textHeading);
        textGPSAccuracy = findViewById(R.id.textGPSAccuracy);
        textCalibrationStatus = findViewById(R.id.textCalibrationStatus);
        textCoordinates = findViewById(R.id.textCoordinates);

        buttonStartStop = findViewById(R.id.buttonStartStop);
        buttonPause = findViewById(R.id.buttonPause);
        buttonCalibrate = findViewById(R.id.buttonCalibrate);
        buttonReset = findViewById(R.id.buttonReset);
        buttonCenterMap = (Button) findViewById(R.id.buttonCenterMap);
        imageDirectionArrow = findViewById(R.id.imageDirectionArrow);
        cardDirection = findViewById(R.id.cardDirection);

        buttonStartStop.setOnClickListener(v -> toggleTracking());
        buttonCalibrate.setOnClickListener(v -> showCalibrationDialog());
        buttonReset.setOnClickListener(v -> resetTracking());
        buttonCenterMap.setOnClickListener(v -> centerOnCurrentPosition());
        
        if (buttonPause != null) {
            buttonPause.setOnClickListener(v -> togglePause());
        }
    }
    
    /** Toggles the {@code isPaused} flag; sensor events are silently dropped while paused. */
    private void togglePause() {
        if (!isTracking) return;
        
        isPaused = !isPaused;
        
        if (isPaused) {
            buttonPause.setText(R.string.resume);
            Toast.makeText(this, "Tracking paused", Toast.LENGTH_SHORT).show();
        } else {
            buttonPause.setText(R.string.pause);
            Toast.makeText(this, "Tracking resumed", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorGameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);

        registerSensors();
    }

    /** Registers gravity, magnetic, gyroscope, linear acceleration, and game-rotation-vector at FASTEST rate. */
    private void registerSensors() {
        if (sensorGravity != null) {
            sensorManager.registerListener(this, sensorGravity, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorMagnetic != null) {
            sensorManager.registerListener(this, sensorMagnetic, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorGyroscope != null) {
            sensorManager.registerListener(this, sensorGyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorLinearAcceleration != null) {
            sensorManager.registerListener(this, sensorLinearAcceleration, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorGameRotationVector != null) {
            sensorManager.registerListener(this, sensorGameRotationVector, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    private void initializeLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    handleGPSUpdate(location);
                }
            }
        };
    }

    /**
     * Sets up the osmdroid MapView at zoom 18: adds a blue DR path polyline, a red no-GPS path
     * polyline, and (if permission granted) a MyLocationNewOverlay for the GPS dot.
     */
    private void initializeMap() {
        mapView = findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        IMapController mapController = mapView.getController();
        mapController.setZoom(18.0);
        
        pathOverlay = new Polyline();
        pathOverlay.getOutlinePaint().setColor(Color.BLUE);
        pathOverlay.getOutlinePaint().setStrokeWidth(10.0f);
        pathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(pathOverlay);
        
        noGPSPathOverlay = new Polyline();
        noGPSPathOverlay.getOutlinePaint().setColor(Color.parseColor("#F44336"));
        noGPSPathOverlay.getOutlinePaint().setStrokeWidth(10.0f);
        noGPSPathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(noGPSPathOverlay);
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mapView);
            locationOverlay.enableMyLocation();
            mapView.getOverlays().add(locationOverlay);
        }
    }

    /** Pushes the latest GPS and DR point lists to their respective overlays and redraws the map. */
    private void updatePathOnMap() {
        if (!pathPoints.isEmpty()) {
            pathOverlay.setPoints(pathPoints);
        }
        if (!noGPSPathPoints.isEmpty()) {
            noGPSPathOverlay.setPoints(noGPSPathPoints);
        }
        mapView.invalidate();
    }

    private void initializeEngine() {
        deadReckoningEngine = new DeadReckoningEngine();
        headingEstimator = new PreciseHeadingEstimator();
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        } else {
            startLocationUpdates();
        }
    }

    /** Requests high-accuracy GPS at 1 s / 0.5 s min interval; also fetches last known location immediately. */
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                    .setMinUpdateIntervalMillis(500)
                    .build();
            fusedLocationClient.requestLocationUpdates(request, locationCallback, getMainLooper());
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) handleGPSUpdate(location);
            });
        }
    }

    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    /**
     * Processes a GPS fix: updates accuracy and coordinate labels, decides whether to use GPS
     * for the trace (accuracy &lt;15 m) or switch to DR-only, calibrates the engine, and appends
     * the fix to {@code pathPoints} when it moved &gt;1 m.
     */
    private void handleGPSUpdate(Location location) {
        if (isPaused) return;
        
        GeoPoint gpsPoint = new GeoPoint(location.getLatitude(), location.getLongitude());

        double accuracy = location.getAccuracy();
        textGPSAccuracy.setText(String.format(Locale.getDefault(), "%.1f m", accuracy));
        textCoordinates.setText(String.format(Locale.getDefault(), "%.6f, %.6f", location.getLatitude(), location.getLongitude()));

        if (forceNoGPSMode) {
            useGPSForTrace = false;
            textCalibrationStatus.setText("No GPS Mode");
        } else if (accuracy < 15) {
            useGPSForTrace = true;
            lastGPSMoveTime = System.currentTimeMillis();
        } else if (accuracy < 25) {
            useGPSForTrace = accuracy < 20;
        } else {
            useGPSForTrace = false;
        }

        if (isTracking && !forceNoGPSMode) {
            deadReckoningEngine.calibrateWithGPS(location);

            if (useGPSForTrace && accuracy < 15) {
                if (lastGPSPoint == null) {
                    pathPoints.add(gpsPoint);
                } else {
                    double distance = distanceBetween(lastGPSPoint, gpsPoint);
                    if (distance > 1.0) {
                        pathPoints.add(gpsPoint);
                    }
                }
                lastGPSPoint = gpsPoint;
                currentPosition = gpsPoint;
                
                if (lastDeadReckoningPoint == null) {
                    lastDeadReckoningPoint = gpsPoint;
                    lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                }
                
                updatePathOnMap();
            }

            if (startPosition == null && accuracy < 20) {
                startPosition = gpsPoint;
                mapView.getController().animateTo(startPosition);
            }
        }

        if (!isTracking && accuracy < 20) {
            currentPosition = gpsPoint;
        }
    }

    /** @return Great-circle distance (m) between two WGS-84 points. */
    private double distanceBetween(GeoPoint p1, GeoPoint p2) {
        return p1.distanceToAsDouble(p2);
    }

    private void toggleTracking() {
        if (isTracking) {
            stopTracking();
        } else {
            startTracking();
        }
    }

    /** Starts the DR engine, resets all path points, and updates UI to show Stop/Pause buttons. */
    private void startTracking() {
        isTracking = true;
        isPaused = false;
        deadReckoningEngine.start();
        pathPoints.clear();
        noGPSPathPoints.clear();
        startPosition = null;
        lastGPSPoint = null;
        lastDeadReckoningPoint = null;
        lastDeadReckoningDistance = 0;
        noGPSFixedHeading = 0;
        
        buttonStartStop.setText(R.string.stop);
        if (buttonPause != null) {
            buttonPause.setVisibility(View.VISIBLE);
            buttonPause.setText(R.string.pause);
        }
        
        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show();
    }

    /** Stops the DR engine, hides the Pause button, and persists the trip via {@link #saveTrip()}. */
    private void stopTracking() {
        isTracking = false;
        deadReckoningEngine.stop();
        
        buttonStartStop.setText(R.string.start);
        if (buttonPause != null) {
            buttonPause.setVisibility(View.GONE);
        }
        
        saveTrip();
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show();
    }
    
    /** Creates a {@link Trip} snapshot with the current path, distance, and step count, then persists it. */
    private void saveTrip() {
        Trip trip = new Trip("Trip " + System.currentTimeMillis());
        trip.setPathPoints(new ArrayList<>(pathPoints));
        trip.setTotalDistance(deadReckoningEngine.getDistance());
        trip.setTotalSteps(deadReckoningEngine.getStepCount());
        trip.finish();
        
        tripStorage.saveTrip(trip);
    }

    /** Resets the DR engine and clears the on-map path without stopping an active session. */
    private void resetTracking() {
        deadReckoningEngine.reset();
        pathPoints.clear();
        updatePathOnMap();
        Toast.makeText(this, "Tracking reset", Toast.LENGTH_SHORT).show();
    }

    /** Animates the map camera to the current DR/GPS position, or to the MyLocation overlay as fallback. */
    private void centerOnCurrentPosition() {
        if (currentPosition != null) {
            mapView.getController().animateTo(currentPosition);
        } else if (locationOverlay != null && locationOverlay.getMyLocation() != null) {
            mapView.getController().animateTo(locationOverlay.getMyLocation());
        }
    }

    private void showCalibrationDialog() {
        // Implement calibration dialog if needed
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isPaused) return;

        float[] values = event.values.clone();
        long timestamp = event.timestamp;

        float[] gravity = null;
        float[] magnetic = null;
        float[] gyro = null;
        float[] linearAccel = null;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                gravity = values;
                headingEstimator.updateGravity(values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                magnetic = values;
                headingEstimator.updateMagneticField(values);
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyro = values;
                headingEstimator.updateGyroscope(values, timestamp);
                break;
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                headingEstimator.updateRotationVector(values);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                linearAccel = values;
                break;
        }

        deadReckoningEngine.updateSensors(gravity, magnetic, gyro, linearAccel, timestamp);

        float heading = (float) deadReckoningEngine.getHeading();
        textHeading.setText(String.format(Locale.getDefault(), "Heading: %.1f°", heading));
        imageDirectionArrow.setRotation(heading);

        if (isTracking) {
            textSteps.setText(String.format(Locale.getDefault(), "Steps: %d", deadReckoningEngine.getStepCount()));
            textDistance.setText(String.format(Locale.getDefault(), "Distance: %.2f m", deadReckoningEngine.getDistance()));
            
            if (deadReckoningEngine.isCalibrated()) {
                textCalibrationStatus.setText(String.format(Locale.getDefault(), "Calibrated (%d pts)", deadReckoningEngine.getCalibrationPoints()));
            } else {
                textCalibrationStatus.setText("Calibrating...");
            }
            
            if (deadReckoningEngine.isTurning() && forceNoGPSMode) {
                noGPSFixedHeading = heading;
                deadReckoningEngine.setFixedHeading(heading);
            }

            // Dead reckoning point update for No GPS mode
            if (isTracking && forceNoGPSMode && lastDeadReckoningPoint != null) {
                double totalDistance = deadReckoningEngine.getDistance();
                double distanceDelta = totalDistance - lastDeadReckoningDistance;
                if (distanceDelta > 0.1 && distanceDelta < 3.0) {
                    GeoPoint newPoint = calculateNextPoint(lastDeadReckoningPoint, (float) noGPSFixedHeading, distanceDelta);
                    if (newPoint != null) {
                        noGPSPathPoints.add(newPoint);
                        lastDeadReckoningPoint = newPoint;
                        currentPosition = newPoint;
                        lastDeadReckoningDistance = totalDistance;
                        updatePathOnMap();
                    }
                }
            }
        }
    }

    /**
     * Computes the next WGS-84 point using the haversine forward formula.
     *
     * @param start    Origin point (WGS-84).
     * @param heading  Bearing (°) clockwise from north.
     * @param distance Distance to advance (m).
     * @return New GeoPoint, or {@code null} if calculation fails.
     */
    private GeoPoint calculateNextPoint(GeoPoint start, float heading, double distance) {
        // Simple dead reckoning calculation
        double radiusOfEarth = 6371000; // meters
        double lat1 = Math.toRadians(start.getLatitude());
        double lon1 = Math.toRadians(start.getLongitude());
        double brng = Math.toRadians(heading);
        
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(distance / radiusOfEarth) +
                Math.cos(lat1) * Math.sin(distance / radiusOfEarth) * Math.cos(brng));
        double lon2 = lon1 + Math.atan2(Math.sin(brng) * Math.sin(distance / radiusOfEarth) * Math.cos(lat1),
                Math.cos(distance / radiusOfEarth) - Math.sin(lat1) * Math.sin(lat2));
        
        return new GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        handleGPSUpdate(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        registerSensors();
        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        sensorManager.unregisterListener(this);
        stopLocationUpdates();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            }
        }
    }
}
