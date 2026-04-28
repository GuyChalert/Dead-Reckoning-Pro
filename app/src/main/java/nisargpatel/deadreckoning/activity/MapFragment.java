package nisargpatel.deadreckoning.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import nisargpatel.deadreckoning.gis.LayerControlSheet;
import nisargpatel.deadreckoning.gis.LayerManager;
import nisargpatel.deadreckoning.gis.NorthArrowOverlay;
import nisargpatel.deadreckoning.power.PowerDutyManager;
import nisargpatel.deadreckoning.sensor.PocketStateDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.osmdroid.api.IMapController;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.export.TunnelMapExporter;
import nisargpatel.deadreckoning.model.Marker;
import nisargpatel.deadreckoning.preferences.TurnModePreferences;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.sensor.BarometerManager;
import nisargpatel.deadreckoning.sensor.DeadReckoningEngine;
import nisargpatel.deadreckoning.sensor.PreciseHeadingEstimator;
import nisargpatel.deadreckoning.storage.MarkerStorage;
import nisargpatel.deadreckoning.storage.TripStorage;
import nisargpatel.deadreckoning.view.DegreeDialView;

/**
 * Primary navigation fragment embedding an osmdroid {@link MapView} with live dead-reckoning.
 * Uses {@link DeadReckoningEngine} for step/distance/heading estimation and
 * {@link PreciseHeadingEstimator} for sensor fusion (gravity + mag + gyro + game-rotation-vector).
 *
 * <p>Key features:
 * <ul>
 *   <li>GPS trace (blue) + DR-only trace (red) when in No-GPS mode</li>
 *   <li>Toggleable No-GPS mode for underground / indoor use (fixed heading on turn events)</li>
 *   <li>Persistent markers via {@link MarkerStorage} (emoji + optional label)</li>
 *   <li>Collapsible stats panel with step count, distance, heading, GPS accuracy</li>
 *   <li>Turn indicator that mirrors {@link TurnModePreferences} (auto or manual)</li>
 *   <li>GIS layer control via {@link nisargpatel.deadreckoning.gis.LayerManager}</li>
 *   <li>Trip saved to {@link TripStorage} on stop</li>
 * </ul>
 */
public class MapFragment extends Fragment implements SensorEventListener {

    private MapView mapView;
    private MyLocationNewOverlay locationOverlay;
    private Polyline pathOverlay;
    
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;

    private SensorManager sensorManager;
    private Sensor sensorGravity;
    private Sensor sensorMagnetic;
    private Sensor sensorGyroscope;
    private Sensor sensorLinearAcceleration;
    private Sensor sensorGameRotationVector;

    private DeadReckoningEngine deadReckoningEngine;
    private PreciseHeadingEstimator headingEstimator;
    private TripStorage tripStorage;
    private MarkerStorage markerStorage;
    private TurnModePreferences turnModePrefs;
    private StepCounterPreferences stepPrefs;

    private TextView textSteps;
    private TextView textDistance;
    private TextView textHeading;
    private TextView textGPSAccuracy;
    private TextView textGPSStatus;
    private TextView textTurnIndicator;
    private MaterialButton buttonModeToggle;
    private MaterialButton buttonGPSCalibrate;
    private MaterialCardView cardTurn;
    private LinearLayout manualTurnControls;

    private FloatingActionButton buttonTurnLeft;
    private FloatingActionButton buttonTurnRight;
    private FloatingActionButton buttonTurnAround;
    private FloatingActionButton buttonOpenDial;
    private FloatingActionButton fabCenterGPS;
    private FloatingActionButton fabAddMarker;
    private FloatingActionButton fabClearAll;
    private FloatingActionButton fabLogLandmark;
    private FloatingActionButton fabExport;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton buttonStartStop;
    private MaterialButton buttonPause;
    private MaterialButton buttonNoGPS;
    private MaterialButton buttonConfirmDial;
    private ImageView imageDirectionArrow;
    private MaterialCardView cardDirection;
    private FrameLayout dialOverlayContainer;
    private DegreeDialView degreeDialView;
    private android.widget.ImageButton buttonMinimizeStats;
    private LinearLayout layoutStatsContent;
    private boolean isStatsMinimized = false;

    private LayerManager layerManager;
    private FloatingActionButton fabLayers;

    private PowerDutyManager dutyManager;
    private PocketStateDetector pocketDetector;
    private Sensor sensorAccelerometer;
    private Sensor sensorStepDetector;
    private Sensor sensorPressure;
    private BarometerManager barometerManager;
    private FrameLayout pocketModeOverlay;
    private TextView pocketTextDistance;
    private TextView pocketTextSteps;
    private TextView pocketTextElevation;

    private List<GeoPoint> pathPoints = new ArrayList<>();
    private List<GeoPoint> noGPSPathPoints = new ArrayList<>();
    private List<GeoPoint> recentGPSPoints = new ArrayList<>();
    private List<Marker> mapMarkers = new ArrayList<>();
    private List<org.osmdroid.views.overlay.Marker> markerOverlays = new ArrayList<>();
    private GeoPoint currentPosition = null;
    private GeoPoint startPosition = null;
    private GeoPoint lastGPSPoint = null;

    private boolean isTracking = false;
    private boolean isPaused = false;
    private boolean isManualMode = false;
    private Location lastKnownGPS = null;
    private boolean useGPSForTrace = false;
    private boolean forceNoGPSMode = false;
    private long lastGPSMoveTime = 0;
    private GeoPoint lastDeadReckoningPoint = null;
    private double lastDeadReckoningDistance = 0;
    private double noGPSFixedHeading = 0;
    private Polyline noGPSPathOverlay;
    private boolean isAddingMarker = false;
    private String selectedEmoji = "📍";
    private GeoPoint pendingMarkerPosition = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        turnModePrefs = new TurnModePreferences(requireContext());
        stepPrefs = new StepCounterPreferences(requireContext());
        markerStorage = new MarkerStorage(requireContext());
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;

        layerManager = new LayerManager(requireContext());
        initViews(view);
        initSensors();
        initPowerDutyManager(view);
        initLocation();
        initMap(view);
        layerManager.attach(mapView);
        mapView.getOverlays().add(new NorthArrowOverlay(requireContext(), mapView));
        initEngine();
        updateTurnModeUI();
        loadMarkers();
    }

    /** Binds all UI widgets and wires click listeners. */
    private void initViews(View view) {
        textSteps = view.findViewById(R.id.textSteps);
        textDistance = view.findViewById(R.id.textDistance);
        textHeading = view.findViewById(R.id.textHeading);
        textGPSAccuracy = view.findViewById(R.id.textGPSAccuracy);
        textGPSStatus = view.findViewById(R.id.textGPSStatus);
        textTurnIndicator = view.findViewById(R.id.textTurnIndicator);
        buttonModeToggle = view.findViewById(R.id.buttonModeToggle);
        buttonGPSCalibrate = view.findViewById(R.id.buttonGPSCalibrate);
        cardTurn = view.findViewById(R.id.cardTurn);
        manualTurnControls = view.findViewById(R.id.manualTurnControls);

        buttonTurnLeft = view.findViewById(R.id.buttonTurnLeft);
        buttonTurnRight = view.findViewById(R.id.buttonTurnRight);
        buttonTurnAround = view.findViewById(R.id.buttonTurnAround);
        fabCenterGPS = view.findViewById(R.id.fabCenterGPS);
        fabAddMarker = view.findViewById(R.id.fabAddMarker);
        fabClearAll  = view.findViewById(R.id.fabClearAll);
        fabLogLandmark = view.findViewById(R.id.fabLogLandmark);
        fabExport    = view.findViewById(R.id.fabExport);
        buttonStartStop = view.findViewById(R.id.buttonStartStop);
        buttonPause = view.findViewById(R.id.buttonPause);
        buttonNoGPS = view.findViewById(R.id.buttonNoGPS);
        imageDirectionArrow = view.findViewById(R.id.imageDirectionArrow);
        cardDirection = view.findViewById(R.id.cardDirection);

        buttonOpenDial = view.findViewById(R.id.buttonOpenDial);
        dialOverlayContainer = view.findViewById(R.id.dialOverlayContainer);
        degreeDialView = view.findViewById(R.id.degreeDialView);
        buttonConfirmDial = view.findViewById(R.id.buttonConfirmDial);

        buttonModeToggle.setOnClickListener(v -> toggleTurnMode());
        
        if (buttonNoGPS != null) {
            buttonNoGPS.setOnClickListener(v -> toggleNoGPSMode());
        }
        
        buttonGPSCalibrate.setOnClickListener(v -> calibrateGPS());

        buttonOpenDial.setOnClickListener(v -> openDialOverlay());
        
        dialOverlayContainer.setOnClickListener(v -> closeDialOverlay());
        
        degreeDialView.setOnDegreeChangedListener(degree -> {
            textHeading.setText(String.format("%.0f°", degree));
        });
        
        buttonConfirmDial.setOnClickListener(v -> confirmDialSelection());
        
        buttonTurnLeft.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnLeft();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("Left ↰");
            }
        });
        
        buttonTurnRight.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnRight();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("Right ↱");
            }
        });
        
        buttonTurnAround.setOnClickListener(v -> {
            if (deadReckoningEngine != null && isTracking && forceNoGPSMode) {
                deadReckoningEngine.turnAround();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                showTurnIndicator("U-Turn 🔄");
            }
        });

        fabLayers = view.findViewById(R.id.fabLayers);
        fabLayers.setOnClickListener(v -> openLayerControl());
        fabLayers.setOnLongClickListener(v -> { showSettingsDialog(); return true; });

        layoutStatsContent = view.findViewById(R.id.layoutStatsContent);
        buttonMinimizeStats = view.findViewById(R.id.buttonMinimizeStats);
        if (buttonMinimizeStats != null) {
            buttonMinimizeStats.setOnClickListener(v -> toggleStatsPanel());
        }

        fabCenterGPS.setOnClickListener(v -> centerOnGPS());
        
        fabAddMarker.setOnClickListener(v -> toggleAddMarkerMode());
        
        fabClearAll.setOnClickListener(v -> showClearAllDialog());

        fabLogLandmark.setOnClickListener(v -> showLandmarkDialog());
        fabExport.setOnClickListener(v -> showExportDialog());

        if (buttonStartStop != null) {
            buttonStartStop.setOnClickListener(v -> {
                if (isTracking) {
                    stopTracking();
                } else {
                    startTracking();
                }
            });
        }
        
        if (buttonPause != null) {
            buttonPause.setOnClickListener(v -> togglePause());
        }
    }
    
    /** Toggles the paused state; updates button label/icon and shows a short Toast. */
    private void togglePause() {
        if (!isTracking) return;
        
        isPaused = !isPaused;
        
        if (isPaused) {
            buttonPause.setText(R.string.resume);
            buttonPause.setIconResource(R.drawable.ic_play);
            Toast.makeText(requireContext(), "Tracking paused", Toast.LENGTH_SHORT).show();
        } else {
            buttonPause.setText(R.string.pause);
            buttonPause.setIconResource(R.drawable.ic_pause);
            Toast.makeText(requireContext(), "Tracking resumed", Toast.LENGTH_SHORT).show();
        }
    }
    
    /** Shows or hides the stats content area; rotates the minimize button arrow 180° when collapsed. */
    private void toggleStatsPanel() {
        isStatsMinimized = !isStatsMinimized;
        if (layoutStatsContent != null) {
            layoutStatsContent.setVisibility(isStatsMinimized ? View.GONE : View.VISIBLE);
        }
        if (buttonMinimizeStats != null) {
            buttonMinimizeStats.setRotation(isStatsMinimized ? 180f : 0f);
        }
    }

    /**
     * Toggles forced No-GPS mode (underground / indoor use).
     * When activating: disables GPS trace, captures the last GPS bearing as the fixed heading,
     * computes a 5 m offset start point from the recent GPS ring, and shows manual turn controls.
     * When deactivating: re-enables compass heading and hides manual turn controls.
     */
    private void toggleNoGPSMode() {
        forceNoGPSMode = !forceNoGPSMode;
        useGPSForTrace = !forceNoGPSMode;
        
        if (forceNoGPSMode) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
            manualTurnControls.setVisibility(View.VISIBLE);
            
            GeoPoint startPoint = calculatePoint5MetersBack();
            
            if (startPoint != null) {
                lastDeadReckoningPoint = startPoint;
            } else if (lastKnownGPS != null && currentPosition != null) {
                lastDeadReckoningPoint = currentPosition;
            }
            
            if (deadReckoningEngine != null) {
                lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                noGPSFixedHeading = deadReckoningEngine.getHeading();
                deadReckoningEngine.setFixedHeading(noGPSFixedHeading);
            }
            
            Toast.makeText(requireContext(), "Mode: No GPS - Straight line, use arrows to turn", Toast.LENGTH_SHORT).show();
        } else {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
            if (!isManualMode) {
                manualTurnControls.setVisibility(View.GONE);
            }
            if (deadReckoningEngine != null) {
                deadReckoningEngine.resetToCompassHeading();
            }
            Toast.makeText(requireContext(), "Mode: GPS (Compass)", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Computes a point 5 m behind the most recent GPS position using the last two GPS track points.
     *
     * @return GeoPoint 5 m before the last GPS fix, or {@code null} if fewer than 2 GPS points
     *         are available.
     */
    private GeoPoint calculatePoint5MetersBack() {
        if (recentGPSPoints.size() < 2) {
            return null;
        }
        
        GeoPoint lastPoint = recentGPSPoints.get(recentGPSPoints.size() - 1);
        GeoPoint secondLastPoint = recentGPSPoints.get(recentGPSPoints.size() - 2);
        
        double bearing = calculateBearing(secondLastPoint.getLatitude(), secondLastPoint.getLongitude(),
                                          lastPoint.getLatitude(), lastPoint.getLongitude());
        
        double backBearing = (bearing + 180) % 360;
        
        return calculateEstimatedPosition(
            lastPoint.getLatitude(),
            lastPoint.getLongitude(),
            backBearing,
            5.0
        );
    }

    /**
     * Computes the forward azimuth (initial bearing) from point 1 to point 2 using the
     * {@code atan2} formula on spherical coordinates.
     *
     * @param lat1 Latitude of the origin (°, WGS-84).
     * @param lon1 Longitude of the origin (°, WGS-84).
     * @param lat2 Latitude of the destination (°, WGS-84).
     * @param lon2 Longitude of the destination (°, WGS-84).
     * @return Bearing in [0, 360) °.
     */
    private double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double lon1Rad = Math.toRadians(lon1);
        double lon2Rad = Math.toRadians(lon2);
        
        double dLon = lon2Rad - lon1Rad;
        
        double y = Math.sin(dLon) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - 
                   Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon);
        
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    /** Animates the map to the last known GPS position at zoom 19, or starts location updates if none yet. */
    private void centerOnGPS() {
        if (lastKnownGPS != null) {
            IMapController mapController = mapView.getController();
            mapController.animateTo(new GeoPoint(lastKnownGPS.getLatitude(), lastKnownGPS.getLongitude()));
            mapController.setZoom(19.0);
        } else {
            Toast.makeText(requireContext(), R.string.searching_gps, Toast.LENGTH_SHORT).show();
            startLocationUpdates();
        }
    }

    /** Cycles {@link TurnModePreferences} between MANUAL and AUTO and refreshes the UI. */
    private void toggleTurnMode() {
        turnModePrefs.toggleTurnMode();
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
        updateTurnModeUI();
        
        String mode = isManualMode ? "Manual" : "Auto";
        Toast.makeText(requireContext(), "Mode: " + mode, Toast.LENGTH_SHORT).show();
    }

    /** Resets the DR engine's GPS calibration, prompting it to re-anchor on the next accurate fix. */
    private void calibrateGPS() {
        if (deadReckoningEngine != null) {
            deadReckoningEngine.resetCalibration();
            Toast.makeText(requireContext(), R.string.gps_reset, Toast.LENGTH_LONG).show();
            textGPSStatus.setText("GPS: Calibrating...");
        }
    }

    /**
     * Reflects the current {@link TurnModePreferences} value in the UI: shows or hides the
     * manual turn controls row and updates the mode toggle button label.
     */
    private void updateTurnModeUI() {
        if (isManualMode) {
            buttonModeToggle.setText("Mode: Manual");
            if (isTracking) {
                manualTurnControls.setVisibility(View.VISIBLE);
            }
        } else {
            buttonModeToggle.setText("Mode: Auto");
        manualTurnControls.setVisibility(View.GONE);

        if (buttonNoGPS != null) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        }
        }
    }

    /**
     * Displays the turn card with the given label for 2 000 ms, then auto-hides it.
     *
     * @param text Human-readable turn description, e.g. {@code "Left ↰"} or {@code "90°"}.
     */
    private void showTurnIndicator(String text) {
        textTurnIndicator.setText(text);
        cardTurn.setVisibility(View.VISIBLE);
        
        cardTurn.removeCallbacks(hideTurnRunnable);
        cardTurn.postDelayed(hideTurnRunnable, 2000);
    }

    private final Runnable hideTurnRunnable = new Runnable() {
        @Override
        public void run() {
            cardTurn.setVisibility(View.GONE);
        }
    };

    /**
     * Obtains sensor handles from {@link SensorManager}: gravity, magnetic field, gyroscope,
     * linear acceleration, game rotation vector, accelerometer, step detector, and pressure.
     */
    private void initSensors() {
        sensorManager = (SensorManager) requireContext().getSystemService(requireContext().SENSOR_SERVICE);

        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorMagnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorGameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorStepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        sensorPressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
    }

    /**
     * Sets up {@link PocketStateDetector} and {@link PowerDutyManager}.
     * Reads the {@code pref_flashlight} boolean from {@code app_prefs} and passes it to the
     * duty manager so the torch is enabled or disabled at startup.
     *
     * @param view Fragment root view (used to find the pocket-mode overlay TextViews).
     */
    private void initPowerDutyManager(View view) {
        pocketDetector = new PocketStateDetector();
        pocketModeOverlay = view.findViewById(R.id.pocketModeOverlay);
        pocketTextDistance = view.findViewById(R.id.pocketTextDistance);
        pocketTextSteps = view.findViewById(R.id.pocketTextSteps);
        pocketTextElevation = view.findViewById(R.id.pocketTextElevation);

        CameraManager cameraManager =
            (CameraManager) requireContext().getSystemService(Context.CAMERA_SERVICE);
        dutyManager = new PowerDutyManager(requireActivity().getWindow(), cameraManager);
        boolean flashlightEnabled = requireContext()
                .getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("pref_flashlight", false);
        dutyManager.setTorchEnabled(flashlightEnabled);
        dutyManager.addListener(state -> {
            // pocketModeOverlay intentionally never shown
        });
    }

    /**
     * Registers all available sensors at {@link SensorManager#SENSOR_DELAY_FASTEST}, except
     * the pressure sensor which uses {@link SensorManager#SENSOR_DELAY_NORMAL} and is only
     * registered when the {@link BarometerManager} is enabled.
     */
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
        if (sensorAccelerometer != null) {
            sensorManager.registerListener(this, sensorAccelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorStepDetector != null) {
            sensorManager.registerListener(this, sensorStepDetector, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorPressure != null && barometerManager != null && barometerManager.isEnabled()) {
            sensorManager.registerListener(this, sensorPressure, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    /**
     * Creates the {@link FusedLocationProviderClient}, a high-accuracy {@link LocationRequest}
     * (1 s / 0.5 s min interval), and the {@link LocationCallback} that routes fixes to
     * {@link #handleGPSUpdate(Location)}.
     */
    private void initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build();

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

    /** Starts continuous GPS updates (requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION}) and fetches the last known location immediately. */
    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (hasLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) handleGPSUpdate(location);
            });
        }
    }

    /** Removes the location update callback from the fused provider. */
    private void stopLocationUpdates() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    /**
     * Configures the osmdroid {@link MapView}: zoom 18, rotation gesture overlay,
     * blue GPS polyline (#1976D2), red No-GPS polyline (#F44336), a
     * {@link MapEventsOverlay} for marker tap handling, and the user-location overlay.
     *
     * @param view Fragment root view containing {@code R.id.mapView}.
     */
    private void initMap(View view) {
        mapView = view.findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(Color.TRANSPARENT);
        
        RotationGestureOverlay rotationGestureOverlay = new RotationGestureOverlay(requireContext(), mapView);
        rotationGestureOverlay.setEnabled(true);
        mapView.getOverlays().add(rotationGestureOverlay);
        
        IMapController mapController = mapView.getController();
        mapController.setZoom(18.0);
        
        pathOverlay = new Polyline();
        pathOverlay.getOutlinePaint().setColor(Color.parseColor("#1976D2"));
        pathOverlay.getOutlinePaint().setStrokeWidth(12.0f);
        pathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(pathOverlay);
        
        noGPSPathOverlay = new Polyline();
        noGPSPathOverlay.getOutlinePaint().setColor(Color.parseColor("#F44336"));
        noGPSPathOverlay.getOutlinePaint().setStrokeWidth(12.0f);
        noGPSPathOverlay.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
        mapView.getOverlays().add(noGPSPathOverlay);

        MapEventsReceiver mReceive = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (isAddingMarker) {
                    showMarkerDialog(p);
                    return true;
                }
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };

        mapView.getOverlays().add(new MapEventsOverlay(mReceive));

        enableLocationComponent();
    }

    /** Adds a {@link MyLocationNewOverlay} with follow-location enabled if the location permission is granted. */
    @SuppressLint("MissingPermission")
    private void enableLocationComponent() {
        if (hasLocationPermission()) {
            locationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(requireContext()), mapView);
            locationOverlay.enableMyLocation();
            locationOverlay.enableFollowLocation();
            mapView.getOverlays().add(locationOverlay);
        }
    }

    /** Pushes the current GPS path and No-GPS path point lists to their polyline overlays and redraws the map. */
    private void updatePathOnMap() {
        if (!pathPoints.isEmpty()) {
            pathOverlay.setPoints(pathPoints);
        }
        if (!noGPSPathPoints.isEmpty()) {
            noGPSPathOverlay.setPoints(noGPSPathPoints);
        }
        mapView.invalidate();
    }

    /**
     * Instantiates {@link DeadReckoningEngine}, informs it whether a hardware step detector is
     * available, creates the {@link PreciseHeadingEstimator} and {@link TripStorage}, and
     * calls {@link #initBarometer()}.
     */
    private void initEngine() {
        deadReckoningEngine = new DeadReckoningEngine();
        deadReckoningEngine.setHardwareStepDetectorAvailable(sensorStepDetector != null);
        headingEstimator = new PreciseHeadingEstimator();
        tripStorage = new TripStorage(requireContext());
        initBarometer();
    }

    /**
     * Reads {@code barometer_prefs} SharedPreferences and configures the {@link BarometerManager}:
     * enabled flag, manual elevation override (m), and calibration offset (m).
     */
    private void initBarometer() {
        barometerManager = new BarometerManager();
        android.content.SharedPreferences prefs =
                requireContext().getSharedPreferences("barometer_prefs", android.content.Context.MODE_PRIVATE);
        barometerManager.setEnabled(prefs.getBoolean("barometer_enabled", true));
        barometerManager.setManualElevation(prefs.getFloat("manual_elevation_m", 0f));
        barometerManager.setCalibrationOffset(prefs.getFloat("calibration_offset_m", 0f));
    }

    /** Expose barometer for CalibrationActivity (accessed via Activity cast). */
    public BarometerManager getBarometerManager() { return barometerManager; }

    /**
     * Begins a new tracking session: applies the current {@link StepCounterPreferences.StepMode},
     * starts the DR engine, resets path lists and DR state, and shows the pause/stop controls.
     * Does nothing if location permission is not granted.
     */
    public void startTracking() {
        if (!hasLocationPermission()) {
            Toast.makeText(requireContext(), "GPS Permission required", Toast.LENGTH_LONG).show();
            return;
        }

        isTracking = true;
        isPaused = false;
        
        StepCounterPreferences.StepMode stepMode = stepPrefs.getStepMode();
        deadReckoningEngine.setStepMode(stepMode);
        
        deadReckoningEngine.start();
        pathPoints.clear();
        noGPSPathPoints.clear();
        startPosition = null;
        lastGPSPoint = null;
        lastDeadReckoningPoint = null;
        lastDeadReckoningDistance = 0;
        noGPSFixedHeading = 0;
        
        forceNoGPSMode = false;
        if (buttonNoGPS != null) {
            buttonNoGPS.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.transparent));
            buttonNoGPS.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
        }

        if (isManualMode || forceNoGPSMode) {
            manualTurnControls.setVisibility(View.VISIBLE);
        }
        
        if (buttonPause != null) {
            buttonPause.setVisibility(View.VISIBLE);
            buttonPause.setText(R.string.pause);
            buttonPause.setIconResource(R.drawable.ic_pause);
        }
        if (buttonStartStop != null) {
            buttonStartStop.setText(R.string.stop);
            buttonStartStop.setIconResource(R.drawable.ic_stop);
        }
        if (cardDirection != null) {
            cardDirection.setVisibility(View.VISIBLE);
        }

        fabLogLandmark.setVisibility(View.VISIBLE);

        if (dutyManager != null) dutyManager.setTrackingActive(true);

        updatePathOnMap();
        Toast.makeText(requireContext(), "Tracking started", Toast.LENGTH_SHORT).show();
    }

    /**
     * Ends the current tracking session: stops the DR engine, saves the trip via
     * {@link TripStorage}, and reveals the export FAB if the SLAM graph has at least one node.
     */
    public void stopTracking() {
        isTracking = false;
        isPaused = false;
        deadReckoningEngine.stop();

        if (dutyManager != null) dutyManager.setTrackingActive(false);

        manualTurnControls.setVisibility(View.GONE);
        fabLogLandmark.setVisibility(View.GONE);

        // Show export FAB after session ends so path can be exported
        if (deadReckoningEngine.getSlamEngine().getNodeCount() > 0) {
            fabExport.setVisibility(View.VISIBLE);
        }

        if (buttonPause != null) {
            buttonPause.setVisibility(View.GONE);
        }
        if (buttonStartStop != null) {
            buttonStartStop.setText(R.string.start);
            buttonStartStop.setIconResource(R.drawable.ic_play);
        }

        if (deadReckoningEngine.getCurrentTrip() != null) {
            tripStorage.saveTrip(deadReckoningEngine.getCurrentTrip());
            Toast.makeText(requireContext(), "Trip saved", Toast.LENGTH_SHORT).show();
        }

        Toast.makeText(requireContext(), "Tracking stopped", Toast.LENGTH_SHORT).show();
    }

    /** @return {@code true} if {@link android.Manifest.permission#ACCESS_FINE_LOCATION} is granted. */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), 
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Processes an incoming GPS fix: updates accuracy labels, decides whether to use the fix
     * for the GPS trace (accuracy tiers: OK &lt;15 m, Fair &lt;25 m, Low ≥25 m), calibrates the
     * DR engine, appends the point to the GPS trace and the 20-point ring buffer, and
     * initialises the DR start point for No-GPS mode.
     *
     * @param location Latest GPS fix from {@link FusedLocationProviderClient}.
     */
    private void handleGPSUpdate(Location location) {
        if (isPaused) return;
        
        lastKnownGPS = location;
        
        double accuracy = location.getAccuracy();
        textGPSAccuracy.setText(String.format("%.1f m", accuracy));
        
        GeoPoint gpsPoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        
        if (forceNoGPSMode) {
            textGPSStatus.setText("No GPS");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorError));
            useGPSForTrace = false;
        } else if (accuracy < 15) {
            textGPSStatus.setText("GPS: OK");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorSuccess));
            useGPSForTrace = true;
            lastGPSMoveTime = System.currentTimeMillis();
        } else if (accuracy < 25) {
            textGPSStatus.setText("GPS: Fair");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
            useGPSForTrace = accuracy < 20;
        } else {
            textGPSStatus.setText("GPS: Low");
            textGPSStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorWarning));
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
                
                recentGPSPoints.add(gpsPoint);
                if (recentGPSPoints.size() > 20) {
                    recentGPSPoints.remove(0);
                }
                
                if (lastDeadReckoningPoint == null) {
                    lastDeadReckoningPoint = gpsPoint;
                    lastDeadReckoningDistance = deadReckoningEngine.getDistance();
                    noGPSFixedHeading = deadReckoningEngine.getHeading();
                    deadReckoningEngine.setFixedHeading(noGPSFixedHeading);
                }
                
                updatePathOnMap();
            }

            if (startPosition == null && accuracy < 20) {
                startPosition = gpsPoint;
                mapView.getController().setCenter(startPosition);
            }
        }

        if (!isTracking && accuracy < 20) {
            currentPosition = gpsPoint;
        }
    }

    /**
     * Returns the great-circle distance between two WGS-84 points (m) via osmdroid's
     * {@link GeoPoint#distanceToAsDouble(GeoPoint)}.
     *
     * @param p1 First point.
     * @param p2 Second point.
     * @return Distance (m).
     */
    private double distanceBetween(GeoPoint p1, GeoPoint p2) {
        return p1.distanceToAsDouble(p2);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values = event.values.clone();

        switch (event.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                headingEstimator.updateGravity(values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                headingEstimator.updateMagneticField(values);
                break;
            case Sensor.TYPE_GYROSCOPE:
                headingEstimator.updateGyroscope(values, event.timestamp);
                break;
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                headingEstimator.updateRotationVector(values);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                if (isTracking && !isPaused) {
                    deadReckoningEngine.updateSensors(
                            headingEstimator.getGravity(),
                            headingEstimator.getMagneticField(),
                            headingEstimator.getGyroscope(),
                            values,
                            event.timestamp
                    );
                    if (event.timestamp - lastUiUpdateNs >= UI_INTERVAL_NS) {
                        lastUiUpdateNs = event.timestamp;
                        updateUI();
                    }
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                if (pocketDetector != null && dutyManager != null) {
                    pocketDetector.update(values);
                    dutyManager.onPocketStateChanged(pocketDetector.getState());
                }
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                if (deadReckoningEngine != null && isTracking && !isPaused) {
                    deadReckoningEngine.notifyHardwareStep(event.timestamp);
                }
                break;
            case Sensor.TYPE_PRESSURE:
                if (barometerManager != null && barometerManager.isEnabled()) {
                    float hPa = event.values[0];
                    barometerManager.onPressureReading(hPa);
                    if (deadReckoningEngine != null) deadReckoningEngine.updateBarometer(hPa);
                }
                break;
        }
    }

    /**
     * Throttled UI refresh called at most ~30 fps (every {@code UI_INTERVAL_NS} = 33.3 ms).
     * Updates step count, distance (m), heading (°), and the direction-arrow rotation.
     * In No-GPS mode, also advances the red DR polyline by the incremental distance walked
     * since the last update, clamped to (0.1, 3.0) m per frame to filter noise.
     */
    private void updateUI() {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            int steps = deadReckoningEngine.getStepCount();
            double dist = deadReckoningEngine.getDistance();
            textSteps.setText(String.valueOf(steps));
            textDistance.setText(String.format("%.2f m", dist));

            if (pocketModeOverlay != null && pocketModeOverlay.getVisibility() == View.VISIBLE) {
                pocketTextDistance.setText(String.format("%.0f m", dist));
                pocketTextSteps.setText(steps + " steps");
                pocketTextElevation.setText(String.format("%.1f m elev",
                    deadReckoningEngine.getElevation()));
            }

            double heading = deadReckoningEngine.getHeading();
            textHeading.setText(String.format("%.1f°", heading));

            if (imageDirectionArrow != null && cardDirection != null && isTracking) {
                float rotation = (float) -heading;
                imageDirectionArrow.setRotation(rotation);
                cardDirection.setVisibility(View.VISIBLE);
            }

            if (!isManualMode && deadReckoningEngine.isTurning()) {
                double turnAmount = deadReckoningEngine.getAccumulatedHeadingChange();
                if (turnAmount > 0) {
                    showTurnIndicator("Left " + String.format("%.0f°", Math.abs(turnAmount)));
                } else {
                    showTurnIndicator("Right " + String.format("%.0f°", Math.abs(turnAmount)));
                }
                
                if (forceNoGPSMode) {
                    noGPSFixedHeading = heading;
                    deadReckoningEngine.setFixedHeading(heading);
                }
            }

            if (isPaused) return;

            if (isTracking && forceNoGPSMode && lastDeadReckoningPoint != null) {
                double totalDistance = deadReckoningEngine.getDistance();
                double incrementalDistance = totalDistance - lastDeadReckoningDistance;
                
                if (incrementalDistance > 0.1 && incrementalDistance < 3.0) {
                    GeoPoint newPoint = calculateEstimatedPosition(
                            lastDeadReckoningPoint.getLatitude(),
                            lastDeadReckoningPoint.getLongitude(),
                            noGPSFixedHeading,
                            incrementalDistance
                    );

                    if (newPoint != null) {
                        noGPSPathPoints.add(newPoint);
                        lastDeadReckoningPoint = newPoint;
                        currentPosition = newPoint;
                        updatePathOnMap();
                    }
                }
                lastDeadReckoningDistance = totalDistance;
            }
        });
    }

    /** Shows the {@link DegreeDialView} overlay pre-set to the current DR heading (°). */
    private void openDialOverlay() {
        if (dialOverlayContainer == null || degreeDialView == null) return;
        
        float currentHeading = (float) noGPSFixedHeading;
        if (deadReckoningEngine != null && isTracking) {
            currentHeading = (float) deadReckoningEngine.getHeading();
        }
        
        degreeDialView.setDegree(currentHeading);
        dialOverlayContainer.setVisibility(View.VISIBLE);
    }

    /** Hides the degree-dial overlay container. */
    private void closeDialOverlay() {
        if (dialOverlayContainer != null) {
            dialOverlayContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Reads the degree selected in the dial and applies it as the fixed heading (°): in
     * No-GPS mode updates {@link #noGPSFixedHeading} and calls
     * {@link DeadReckoningEngine#setFixedHeading(double)}, in manual mode calls
     * {@link DeadReckoningEngine#setFixedHeading(double)} without touching the No-GPS path.
     * Closes the dial overlay afterwards.
     */
    private void confirmDialSelection() {
        if (degreeDialView != null && deadReckoningEngine != null && isTracking) {
            float selectedDegree = degreeDialView.getDegree();
            
            if (forceNoGPSMode) {
                noGPSFixedHeading = selectedDegree;
                deadReckoningEngine.setFixedHeading(selectedDegree);
                showTurnIndicator(String.format("%.0f°", selectedDegree));
            } else if (isManualMode) {
                deadReckoningEngine.setFixedHeading(selectedDegree);
            }
        }
        
        closeDialOverlay();
    }

    /**
     * Estimates a new WGS-84 position using a flat-Earth approximation (sin/cos, no atan2).
     * Suitable for short steps (&lt; a few metres) where spherical distortion is negligible.
     *
     * @param lat           Origin latitude (°, WGS-84).
     * @param lon           Origin longitude (°, WGS-84).
     * @param heading       Travel heading (°, clockwise from north).
     * @param distanceMeters Step distance (m).
     * @return Estimated {@link GeoPoint} after travelling {@code distanceMeters} in {@code heading} direction.
     */
    private GeoPoint calculateEstimatedPosition(double lat, double lon, double heading, double distanceMeters) {
        double earthRadius = 6371000;
        double headingRad = Math.toRadians(heading);

        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);

        double newLatRad = latRad + (distanceMeters * Math.cos(headingRad)) / earthRadius;
        double newLonRad = lonRad + (distanceMeters * Math.sin(headingRad)) / (earthRadius * Math.cos(latRad));

        return new GeoPoint(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (dutyManager != null) dutyManager.release();
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
        registerSensors();
        startLocationUpdates();
        
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
        updateTurnModeUI();
        
        try {
            requireContext().registerReceiver(screenStateReceiver, screenStateFilter);
        } catch (Exception ignored) {}
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
        // Keep sensors registered for background tracking
        // sensorManager.unregisterListener(this);
        
        try {
            requireContext().unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {}
        
        stopLocationUpdates();
    }

    private long lastUiUpdateNs = 0;
    private static final long UI_INTERVAL_NS = 33_333_333L; // ~30 fps

    private boolean isScreenOn = true;
    private final IntentFilter screenStateFilter = new IntentFilter();
    {
        screenStateFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
    }

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
            }
        }
    };

    /** Enables or disables marker-placement mode; tints the FAB green while active. */
    private void toggleAddMarkerMode() {
        isAddingMarker = !isAddingMarker;
        if (isAddingMarker) {
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorSuccess));
            Toast.makeText(requireContext(), R.string.tap_location, Toast.LENGTH_SHORT).show();
        } else {
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            pendingMarkerPosition = null;
        }
    }

    /**
     * Shows the marker-creation dialog at the tapped map position, offering 18 emoji choices
     * and an optional text label. On confirmation, persists the marker via
     * {@link MarkerStorage} and adds it to the map.
     *
     * @param position WGS-84 coordinate where the marker will be placed.
     */
    private void showMarkerDialog(GeoPoint position) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_marker, null);
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
        builder.setView(dialogView);
        
        android.app.AlertDialog dialog = builder.create();
        
        TextView[] emojiViews = {
            dialogView.findViewById(R.id.emoji_pin),
            dialogView.findViewById(R.id.emoji_pushpin),
            dialogView.findViewById(R.id.emoji_home),
            dialogView.findViewById(R.id.emoji_building),
            dialogView.findViewById(R.id.emoji_restaurant),
            dialogView.findViewById(R.id.emoji_pizza),
            dialogView.findViewById(R.id.emoji_coffee),
            dialogView.findViewById(R.id.emoji_shopping),
            dialogView.findViewById(R.id.emoji_hospital),
            dialogView.findViewById(R.id.emoji_fuel),
            dialogView.findViewById(R.id.emoji_hotel),
            dialogView.findViewById(R.id.emoji_park),
            dialogView.findViewById(R.id.emoji_car),
            dialogView.findViewById(R.id.emoji_plane),
            dialogView.findViewById(R.id.emoji_star),
            dialogView.findViewById(R.id.emoji_heart),
            dialogView.findViewById(R.id.emoji_red_dot),
            dialogView.findViewById(R.id.emoji_green_dot)
        };
        
        String[] emojis = {"📍", "📌", "🏠", "🏢", "🍔", "🍕", "☕", "🛒", "🏥", "⛽", "🏨", "🏞", "🚗", "✈️", "⭐", "❤️", "🔴", "🟢"};
        
        for (int i = 0; i < emojiViews.length; i++) {
            final int index = i;
            emojiViews[i].setOnClickListener(v -> {
                selectedEmoji = emojis[index];
                for (TextView tv : emojiViews) {
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                emojiViews[index].setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimaryLight));
            });
        }
        
        emojiViews[0].setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.colorPrimaryLight));
        
        com.google.android.material.textfield.TextInputEditText editLabel = dialogView.findViewById(R.id.editMarkerLabel);
        
        dialogView.findViewById(R.id.buttonCancelMarker).setOnClickListener(v -> {
            dialog.dismiss();
            isAddingMarker = false;
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            pendingMarkerPosition = null;
        });
        
        dialogView.findViewById(R.id.buttonConfirmMarker).setOnClickListener(v -> {
            String label = editLabel.getText() != null ? editLabel.getText().toString().trim() : "";
            
            Marker marker = new Marker(position.getLatitude(), position.getLongitude(), selectedEmoji);
            if (!label.isEmpty()) {
                marker.setLabel(label);
            }
            
            markerStorage.saveMarker(marker);
            addMarkerToMap(marker);
            
            dialog.dismiss();
            isAddingMarker = false;
            fabAddMarker.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(), R.color.colorAccent));
            Toast.makeText(requireContext(), R.string.marker_added, Toast.LENGTH_SHORT).show();
        });
        
        dialog.show();
    }

    /**
     * Creates an osmdroid {@link org.osmdroid.views.overlay.Marker} for the given model
     * {@link Marker}, attaches a drag listener that persists position changes via
     * {@link MarkerStorage#updateMarker(Marker)}, and adds it to the map overlay list.
     *
     * @param marker Marker model containing latitude, longitude, emoji, and optional label.
     */
    private void addMarkerToMap(Marker marker) {
        org.osmdroid.views.overlay.Marker mapMarker = new org.osmdroid.views.overlay.Marker(mapView);
        mapMarker.setPosition(new GeoPoint(marker.getLatitude(), marker.getLongitude()));
        
        String title = marker.getEmoji();
        if (marker.getLabel() != null && !marker.getLabel().isEmpty()) {
            title = marker.getLabel() + " " + marker.getEmoji();
        }
        mapMarker.setTitle(title);
        mapMarker.setSnippet(marker.getEmoji());
        mapMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM);
        
        mapMarker.setOnMarkerClickListener((m, mv) -> {
            m.showInfoWindow();
            return true;
        });

        mapMarker.setOnMarkerDragListener(new org.osmdroid.views.overlay.Marker.OnMarkerDragListener() {
            @Override
            public void onMarkerDrag(org.osmdroid.views.overlay.Marker m) {}

            @Override
            public void onMarkerDragEnd(org.osmdroid.views.overlay.Marker m) {
                GeoPoint pos = m.getPosition();
                for (int i = 0; i < markerOverlays.size(); i++) {
                    if (markerOverlays.get(i) == m) {
                        Marker modelMarker = mapMarkers.get(i);
                        modelMarker.setLatitude(pos.getLatitude());
                        modelMarker.setLongitude(pos.getLongitude());
                        markerStorage.updateMarker(modelMarker);
                        break;
                    }
                }
            }

            @Override
            public void onMarkerDragStart(org.osmdroid.views.overlay.Marker m) {}
        });

        mapView.getOverlays().add(mapMarker);
        markerOverlays.add(mapMarker);
        mapMarkers.add(marker);
        mapView.invalidate();
    }

    /** Loads all persisted markers from {@link MarkerStorage} and places them on the map. */
    private void loadMarkers() {
        List<Marker> markers = markerStorage.getAllMarkers();
        for (Marker marker : markers) {
            addMarkerToMap(marker);
        }
    }

    /** Shows a list dialog offering: clear tracks only, clear markers only, or clear everything. */
    private void showClearAllDialog() {
        String[] options = {getString(R.string.clear_tracks_only), getString(R.string.clear_markers_only), getString(R.string.clear_everything)};
        
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_confirmation)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        clearTracksOnly();
                        Toast.makeText(requireContext(), R.string.trips_cleared, Toast.LENGTH_SHORT).show();
                        break;
                    case 1:
                        clearMarkersOnly();
                        Toast.makeText(requireContext(), R.string.markers_cleared, Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        clearTracksOnly();
                        clearMarkersOnly();
                        Toast.makeText(requireContext(), R.string.everything_cleared, Toast.LENGTH_SHORT).show();
                        break;
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /** Clears the GPS and No-GPS path point lists and redraws the map. */
    private void clearTracksOnly() {
        pathPoints.clear();
        noGPSPathPoints.clear();
        updatePathOnMap();
    }

    /** Removes all marker overlays from the map and deletes them from {@link MarkerStorage}. */
    private void clearMarkersOnly() {
        for (org.osmdroid.views.overlay.Marker m : markerOverlays) {
            mapView.getOverlays().remove(m);
        }
        markerOverlays.clear();
        mapMarkers.clear();
        markerStorage.clearAllMarkers();
        mapView.invalidate();
    }

    // ------------------------------------------------------------------
    // Slice E: GIS layer control
    // ------------------------------------------------------------------

    /** Opens the {@link LayerControlSheet} bottom sheet for managing GIS tile and vector layers. */
    private void openLayerControl() {
        LayerControlSheet.newInstance(layerManager, mapView, this::enterDownloadMode)
            .show(getChildFragmentManager(), "layers");
    }

    /**
     * Shows the long-press settings dialog (triggered by long-pressing the layers FAB).
     * Currently exposes a single toggle for the rear flashlight via {@link PowerDutyManager}.
     */
    private void showSettingsDialog() {
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE);
        boolean flashOn = prefs.getBoolean("pref_flashlight", false);

        android.widget.CheckBox cbFlash = new android.widget.CheckBox(requireContext());
        cbFlash.setText(R.string.setting_flashlight);
        cbFlash.setChecked(flashOn);
        int p = (int)(16 * requireContext().getResources().getDisplayMetrics().density);
        cbFlash.setPadding(p, p, p, p);

        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_title)
            .setView(cbFlash)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                boolean enabled = cbFlash.isChecked();
                prefs.edit().putBoolean("pref_flashlight", enabled).apply();
                if (dutyManager != null) dutyManager.setTorchEnabled(enabled);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ------------------------------------------------------------------
    // Slice H: offline tile download with bounding-box UI
    // ------------------------------------------------------------------

    private nisargpatel.deadreckoning.gis.BoundingBoxOverlay bboxOverlay;
    private android.view.View downloadBar;

    /**
     * Enters offline tile download mode: adds a {@link nisargpatel.deadreckoning.gis.BoundingBoxOverlay}
     * initialised to 80% of the current viewport and shows the download control bar.
     * No-op if already in download mode.
     */
    private void enterDownloadMode() {
        if (mapView == null) return;
        if (bboxOverlay != null) return; // already in download mode

        // Shrink current viewport by 20% for initial bbox
        org.osmdroid.util.BoundingBox vp = mapView.getBoundingBox();
        double latPad = (vp.getLatNorth() - vp.getLatSouth()) * 0.1;
        double lonPad = (vp.getLonEast()  - vp.getLonWest())  * 0.1;
        org.osmdroid.util.BoundingBox initial = new org.osmdroid.util.BoundingBox(
            vp.getLatNorth() - latPad, vp.getLonEast() - lonPad,
            vp.getLatSouth() + latPad, vp.getLonWest() + lonPad);

        float density = requireContext().getResources().getDisplayMetrics().density;
        bboxOverlay = new nisargpatel.deadreckoning.gis.BoundingBoxOverlay(initial, density);
        mapView.getOverlays().add(bboxOverlay);
        mapView.invalidate();

        showDownloadBar();
    }

    /**
     * Programmatically inflates and attaches the bottom control bar shown while in download mode,
     * containing a hint label and Configure / Cancel buttons.
     */
    private void showDownloadBar() {
        android.widget.LinearLayout bar = new android.widget.LinearLayout(requireContext());
        bar.setOrientation(android.widget.LinearLayout.VERTICAL);
        bar.setPadding(24, 16, 24, 16);
        bar.setBackgroundColor(android.graphics.Color.argb(220, 30, 30, 30));

        android.widget.TextView hint = new android.widget.TextView(requireContext());
        hint.setText(R.string.offline_download_hint);
        hint.setTextColor(android.graphics.Color.WHITE);
        hint.setTextSize(13f);
        bar.addView(hint);

        android.widget.LinearLayout btns = new android.widget.LinearLayout(requireContext());
        btns.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btns.setPadding(0, 12, 0, 0);

        com.google.android.material.button.MaterialButton btnConfigure =
            new com.google.android.material.button.MaterialButton(requireContext());
        btnConfigure.setText(R.string.offline_configure_btn);
        btnConfigure.setOnClickListener(v -> showDownloadConfigDialog());

        com.google.android.material.button.MaterialButton btnCancel =
            new com.google.android.material.button.MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnCancel.setText(R.string.offline_cancel_btn);
        btnCancel.setTextColor(android.graphics.Color.WHITE);
        btnCancel.setOnClickListener(v -> exitDownloadMode());

        android.widget.LinearLayout.LayoutParams lp =
            new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd((int)(8 * requireContext().getResources().getDisplayMetrics().density));
        btns.addView(btnConfigure, lp);
        btns.addView(btnCancel,
            new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(btns);

        android.widget.FrameLayout.LayoutParams barLp =
            new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = android.view.Gravity.BOTTOM;

        downloadBar = bar;
        ((android.view.ViewGroup) requireView()).addView(bar, barLp);
    }

    /** Removes the bounding-box overlay and download bar from the view hierarchy. */
    private void exitDownloadMode() {
        if (bboxOverlay != null) {
            mapView.getOverlays().remove(bboxOverlay);
            bboxOverlay = null;
            mapView.invalidate();
        }
        if (downloadBar != null) {
            ((android.view.ViewGroup) requireView()).removeView(downloadBar);
            downloadBar = null;
        }
    }

    /**
     * Shows the offline-download configuration dialog: layer picker (filtered to tile-based
     * and OSM-base layers), max-zoom SeekBar (capped at current zoom + 3, max 18), and a live
     * tile-count estimate. Validates the count against
     * {@link nisargpatel.deadreckoning.gis.OfflineTileDownloader#MAX_TILES} before starting.
     */
    @SuppressWarnings("deprecation")
    private void showDownloadConfigDialog() {
        if (bboxOverlay == null) return;

        java.util.List<nisargpatel.deadreckoning.gis.MapLayer> activeLayers =
            layerManager.getLayers();

        // Filter to tile-based layers (WMS/WMTS with endpoints) or the OSM base
        java.util.List<nisargpatel.deadreckoning.gis.MapLayer> downloadable = new java.util.ArrayList<>();
        for (nisargpatel.deadreckoning.gis.MapLayer l : activeLayers) {
            boolean hasEndpoint = l.getEndpointUrl() != null && !l.getEndpointUrl().isEmpty();
            boolean isOsmBase   = "osm_base".equals(l.getId());
            if (hasEndpoint || isOsmBase) {
                downloadable.add(l);
            }
        }

        if (downloadable.isEmpty()) {
            android.widget.Toast.makeText(requireContext(),
                R.string.offline_no_layers, android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        org.osmdroid.util.BoundingBox bbox = bboxOverlay.getBoundingBox();
        int currentZoom = (int) mapView.getZoomLevelDouble();
        int maxZoomCap  = Math.min(18, currentZoom + 3);

        // Build dialog layout
        android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * requireContext().getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        // Layer picker
        android.widget.TextView layerLabel = new android.widget.TextView(requireContext());
        layerLabel.setText(R.string.offline_pick_layer);
        layout.addView(layerLabel);

        String[] layerNames = new String[downloadable.size()];
        for (int i = 0; i < downloadable.size(); i++) layerNames[i] = downloadable.get(i).getName();
        android.widget.Spinner layerSpinner = new android.widget.Spinner(requireContext());
        android.widget.ArrayAdapter<String> spinnerAdp = new android.widget.ArrayAdapter<>(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, layerNames);
        layerSpinner.setAdapter(spinnerAdp);
        layout.addView(layerSpinner);

        // Max zoom slider
        android.widget.TextView zoomLabel = new android.widget.TextView(requireContext());
        zoomLabel.setText(getString(R.string.offline_zoom_max_label, maxZoomCap));
        layout.addView(zoomLabel);

        android.widget.SeekBar zoomBar = new android.widget.SeekBar(requireContext());
        zoomBar.setMax(maxZoomCap - currentZoom);
        zoomBar.setProgress(zoomBar.getMax());

        // Tile count label
        android.widget.TextView tileCountLabel = new android.widget.TextView(requireContext());
        long initialCount = nisargpatel.deadreckoning.gis.OfflineTileDownloader.tileCount(
            bbox, currentZoom, maxZoomCap);
        tileCountLabel.setText(getString(R.string.offline_tile_count, initialCount));
        tileCountLabel.setPadding(0, 8, 0, 0);

        zoomBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                int maxZ = currentZoom + progress;
                zoomLabel.setText(getString(R.string.offline_zoom_max_label, maxZ));
                long count = nisargpatel.deadreckoning.gis.OfflineTileDownloader.tileCount(
                    bbox, currentZoom, maxZ);
                tileCountLabel.setText(getString(R.string.offline_tile_count, count));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        layout.addView(zoomBar);
        layout.addView(tileCountLabel);

        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.offline_download_btn)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                int chosenMaxZoom = currentZoom + zoomBar.getProgress();
                nisargpatel.deadreckoning.gis.MapLayer chosenLayer =
                    downloadable.get(layerSpinner.getSelectedItemPosition());
                long count = nisargpatel.deadreckoning.gis.OfflineTileDownloader.tileCount(
                    bbox, currentZoom, chosenMaxZoom);
                if (count > nisargpatel.deadreckoning.gis.OfflineTileDownloader.MAX_TILES) {
                    android.widget.Toast.makeText(requireContext(),
                        getString(R.string.offline_too_many, count,
                            nisargpatel.deadreckoning.gis.OfflineTileDownloader.MAX_TILES),
                        android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                startOfflineDownload(chosenLayer, currentZoom, chosenMaxZoom, bbox, (int) count);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /**
     * Starts the offline tile download on a background thread via
     * {@link nisargpatel.deadreckoning.gis.OfflineTileDownloader#download}.
     * Progress toasts fire every 10%. On completion the MBTiles file is loaded into
     * {@link LayerManager} as a new layer named {@code "<layerName> (offline z<maxZoom>)"}.
     *
     * @param layer      Source layer to download tiles from.
     * @param minZoom    Minimum zoom level to download (inclusive).
     * @param maxZoom    Maximum zoom level to download (inclusive).
     * @param bbox       Geographic bounding box of the download area.
     * @param totalTiles Pre-computed tile count shown in start Toast.
     */
    private void startOfflineDownload(nisargpatel.deadreckoning.gis.MapLayer layer,
                                      int minZoom, int maxZoom,
                                      org.osmdroid.util.BoundingBox bbox, int totalTiles) {
        org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase source =
            layerManager.getTileSourceForLayer(layer.getId());
        if (source == null) {
            android.widget.Toast.makeText(requireContext(),
                getString(R.string.offline_download_error, "Source unavailable"),
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        exitDownloadMode();

        android.widget.Toast.makeText(requireContext(),
            getString(R.string.offline_download_start, totalTiles),
            android.widget.Toast.LENGTH_SHORT).show();

        String fileName = "offline_" + layer.getId() + "_z" + minZoom + "-" + maxZoom + ".mbtiles";
        java.io.File outFile = new java.io.File(requireContext().getCacheDir(), fileName);
        String displayName   = layer.getName() + " (offline z" + maxZoom + ")";

        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        exec.execute(() -> nisargpatel.deadreckoning.gis.OfflineTileDownloader.download(
            source, bbox, minZoom, maxZoom, outFile,
            new nisargpatel.deadreckoning.gis.OfflineTileDownloader.Callback() {
                private int lastReportedPct = -1;
                @Override public void onProgress(int done, int total) {
                    int pct = total > 0 ? (done * 100 / total) : 0;
                    int bucket = pct / 10; // report every 10%
                    if (bucket != lastReportedPct) {
                        lastReportedPct = bucket;
                        if (mapView != null) mapView.post(() ->
                            android.widget.Toast.makeText(requireContext(),
                                getString(R.string.offline_download_progress, done, total),
                                android.widget.Toast.LENGTH_SHORT).show());
                    }
                }
                @Override public void onDone(java.io.File out) {
                    try {
                        layerManager.loadMBTilesFromFile(out, displayName);
                        if (mapView != null) mapView.post(() ->
                            android.widget.Toast.makeText(requireContext(),
                                getString(R.string.offline_download_done, displayName),
                                android.widget.Toast.LENGTH_LONG).show());
                    } catch (java.io.IOException e) {
                        if (mapView != null) mapView.post(() ->
                            android.widget.Toast.makeText(requireContext(),
                                getString(R.string.offline_download_error, e.getMessage()),
                                android.widget.Toast.LENGTH_LONG).show());
                    }
                }
                @Override public void onError(Exception e) {
                    if (mapView != null) mapView.post(() ->
                        android.widget.Toast.makeText(requireContext(),
                            getString(R.string.offline_download_error, e.getMessage()),
                            android.widget.Toast.LENGTH_LONG).show());
                }
            }));
        exec.shutdown();
    }

    // ------------------------------------------------------------------
    // Slice C: distance marking dialog
    // ------------------------------------------------------------------

    /**
     * Shows a numeric input dialog for manually logging a known landmark distance (m).
     * On confirmation, calls {@link DeadReckoningEngine#addLandmarkDistance(double)} to
     * insert a SLAM constraint at the current position.
     */
    private void showLandmarkDialog() {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(
            android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(getString(R.string.landmark_hint));
        input.setTextColor(ContextCompat.getColor(requireContext(), R.color.textPrimary));

        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.landmark_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.confirm, (d, w) -> {
                String txt = input.getText() != null ? input.getText().toString().trim() : "";
                if (txt.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.invalid_value), Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    double metres = Double.parseDouble(txt);
                    deadReckoningEngine.addLandmarkDistance(metres);
                    Toast.makeText(requireContext(),
                        getString(R.string.landmark_logged, metres), Toast.LENGTH_SHORT).show();
                } catch (NumberFormatException e) {
                    Toast.makeText(requireContext(), getString(R.string.invalid_value), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    // ------------------------------------------------------------------
    // Slice C: export dialog
    // ------------------------------------------------------------------

    /**
     * Shows an export format picker (GeoJSON / KML / CSV). Requires the SLAM graph to have an
     * origin and at least one node; shows an error Toast otherwise.
     */
    private void showExportDialog() {
        if (!deadReckoningEngine.getSlamEngine().hasOrigin()
                || deadReckoningEngine.getSlamEngine().getNodeCount() == 0) {
            Toast.makeText(requireContext(), R.string.no_slam_path, Toast.LENGTH_LONG).show();
            return;
        }

        String[] formats = {"GeoJSON", "KML", "CSV"};
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.export_format_title)
            .setItems(formats, (d, which) -> {
                TunnelMapExporter.Format fmt;
                switch (which) {
                    case 0:  fmt = TunnelMapExporter.Format.GEOJSON; break;
                    case 1:  fmt = TunnelMapExporter.Format.KML;     break;
                    default: fmt = TunnelMapExporter.Format.CSV;     break;
                }
                doExport(fmt);
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    /**
     * Runs {@link TunnelMapExporter#export} on a background thread for the given format and
     * posts a Toast with the output file path on success, or an error message on failure.
     *
     * @param fmt Desired export format (GeoJSON, KML, or CSV).
     */
    private void doExport(TunnelMapExporter.Format fmt) {
        new Thread(() -> {
            try {
                java.io.File f = TunnelMapExporter.export(
                    requireContext(), deadReckoningEngine.getSlamEngine(), fmt);
                requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(),
                        getString(R.string.exported_path, f.getAbsolutePath()),
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                requireActivity().runOnUiThread(() ->
                    Toast.makeText(requireContext(),
                        getString(R.string.export_error, e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }
}
