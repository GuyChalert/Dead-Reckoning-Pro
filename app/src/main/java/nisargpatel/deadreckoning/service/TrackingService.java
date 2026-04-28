package nisargpatel.deadreckoning.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.activity.MainContainerActivity;
import nisargpatel.deadreckoning.model.Trip;
import nisargpatel.deadreckoning.model.TurnEvent;
import nisargpatel.deadreckoning.preferences.TurnModePreferences;
import nisargpatel.deadreckoning.sensor.DeadReckoningEngine;
import nisargpatel.deadreckoning.sensor.PreciseHeadingEstimator;
import nisargpatel.deadreckoning.storage.TripStorage;

/**
 * Foreground service that runs the dead-reckoning pipeline while the screen is off or the app
 * is in the background. Acquires a {@link PowerManager#PARTIAL_WAKE_LOCK} for the sensor hub.
 *
 * <p>Lifecycle: send {@link #ACTION_START} / {@link #ACTION_STOP} intents, or bind with
 * {@link LocalBinder} to call methods directly from the UI.
 *
 * <p>Sensor routing:
 * <ul>
 *   <li>GRAVITY + MAGNETIC_FIELD + GYROSCOPE + GAME_ROTATION_VECTOR → {@link PreciseHeadingEstimator}</li>
 *   <li>LINEAR_ACCELERATION → {@link DeadReckoningEngine#updateSensors} (includes latest heading state)</li>
 *   <li>ACCELEROMETER → {@link nisargpatel.deadreckoning.sensor.PocketStateDetector}</li>
 *   <li>PRESSURE → {@link DeadReckoningEngine#updateBarometer} (hPa)</li>
 * </ul>
 * GPS fixes with accuracy &lt; 15 m are forwarded to {@link DeadReckoningEngine#calibrateWithGPS}.
 */
public class TrackingService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "tracking_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START = "nisargpatel.deadreckoning.action.START";
    public static final String ACTION_STOP = "nisargpatel.deadreckoning.action.STOP";

    private final IBinder binder = new LocalBinder();

    private SensorManager sensorManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private PowerManager.WakeLock wakeLock;

    private Sensor sensorGravity;
    private Sensor sensorMagnetic;
    private Sensor sensorGyroscope;
    private Sensor sensorLinearAcceleration;
    private Sensor sensorAccelerometer;      // raw accel for pocket detection
    private Sensor sensorPressure;           // barometer for elevation
    // Preferred: hardware-fused, magnetometer-free — ideal underground
    private Sensor sensorGameRotationVector;

    private final nisargpatel.deadreckoning.sensor.PocketStateDetector pocketDetector =
            new nisargpatel.deadreckoning.sensor.PocketStateDetector();

    private DeadReckoningEngine deadReckoningEngine;
    private PreciseHeadingEstimator headingEstimator;
    private TripStorage tripStorage;
    private TurnModePreferences turnModePrefs;

    private boolean isTracking = false;
    private boolean isManualMode = false;
    private boolean isScreenOn = true;

    private List<GeoPoint> pathPoints = new ArrayList<>();
    private Trip currentTrip = null;

    private int gpsCalibrationPoints = 0;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
            }
        }
    };

    /** Binder that lets bound activities retrieve this service instance directly. */
    public class LocalBinder extends Binder {
        /** @return The running {@link TrackingService} instance. */
        public TrackingService getService() {
            return TrackingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initSensors();
        initLocation();
        initEngine();
        initWakeLock();
        registerScreenReceiver();
    }

    /** Acquires a {@code PARTIAL_WAKE_LOCK} so the CPU stays awake while sensors stream data. */
    private void initWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, 
                "DeadReckoning:TrackingWakeLock");
        }
    }

    /** Registers a receiver for {@code ACTION_SCREEN_ON/OFF} to track display state. */
    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startTracking();
            } else if (ACTION_STOP.equals(action)) {
                stopTracking();
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** Creates the low-importance notification channel required for Android 8+ foreground services. */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tracking_in_progress),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.tracking_notification_desc));
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /** Obtains sensor handles for gravity, magnetic field, gyroscope, linear accel, accelerometer, pressure, and game rotation vector. */
    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorGravity             = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorMagnetic            = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGyroscope           = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorLinearAcceleration  = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorAccelerometer       = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorPressure            = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        sensorGameRotationVector  = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
    }

    /** Sets up the FusedLocationProviderClient and its callback; GPS updates start only after {@link #startTracking()}. */
    private void initLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    handleGPSUpdate(location);
                }
            }
        };
    }

    /** Instantiates the dead-reckoning engine, heading estimator, trip storage, and turn-mode prefs. */
    private void initEngine() {
        deadReckoningEngine = new DeadReckoningEngine();
        headingEstimator = new PreciseHeadingEstimator();
        tripStorage = new TripStorage(this);
        turnModePrefs = new TurnModePreferences(this);
        isManualMode = turnModePrefs.getTurnMode() == TurnModePreferences.TurnMode.MANUAL;
    }

/** Starts the tracking session: acquires wake lock, registers sensors at SENSOR_DELAY_FASTEST, requests GPS, and promotes to foreground. No-op if already tracking. */
    public void startTracking() {
        if (isTracking) return;

        isTracking = true;
        deadReckoningEngine.start();
        pathPoints.clear();
        gpsCalibrationPoints = 0;

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 60 * 1000L);
        }

        registerSensors();
        startLocationUpdates();
        startForeground();

        updateNotification(getString(R.string.tracking_in_progress), getString(R.string.tracking_status_format, 0, 0.00));
    }

/** Stops tracking: unregisters sensors, removes GPS updates, persists the current trip, releases wake lock, and stops the foreground service. No-op if not tracking. */
    public void stopTracking() {
        if (!isTracking) return;

        isTracking = false;
        deadReckoningEngine.stop();

        sensorManager.unregisterListener(this);
        fusedLocationClient.removeLocationUpdates(locationCallback);

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if (deadReckoningEngine.getCurrentTrip() != null) {
            tripStorage.saveTrip(deadReckoningEngine.getCurrentTrip());
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

/** Resets the GPS calibration point counter, forcing the engine to re-anchor on the next valid fix. */
    public void calibrateGPS() {
        gpsCalibrationPoints = 0;
    }

    /**
     * Registers all available IMU sensors at {@code SENSOR_DELAY_FASTEST} (~200 Hz).
     * Barometer is registered at {@code SENSOR_DELAY_NORMAL} (~5 Hz) — sufficient for altitude.
     * Null sensors (missing hardware) are silently skipped.
     */
    private void registerSensors() {
        // SENSOR_DELAY_FASTEST → ~200 Hz on Snapdragon 8 Elite sensor hub.
        // Required by HIGH_SAMPLING_RATE_SENSORS permission (already in manifest).
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
        if (sensorAccelerometer != null) {
            sensorManager.registerListener(this, sensorAccelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (sensorGameRotationVector != null) {
            sensorManager.registerListener(this, sensorGameRotationVector, SensorManager.SENSOR_DELAY_FASTEST);
        }
        // Barometer needs only ~1 Hz — changes slowly relative to IMU
        if (sensorPressure != null) {
            sensorManager.registerListener(this, sensorPressure, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    /** Requests high-accuracy GPS fixes every 2 s (min interval 1 s). No-op if fine-location permission is missing. */
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    /** Builds and posts the persistent tracking notification; uses FOREGROUND_SERVICE_TYPE_LOCATION on Android 10+. */
    private void startForeground() {
        Intent notificationIntent = new Intent(this, MainContainerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.tracking_in_progress) + "...")
                .setSmallIcon(R.drawable.ic_directions_walk)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /**
     * Updates the foreground notification text in-place without re-calling startForeground.
     *
     * @param title   Notification title line.
     * @param content Notification body (e.g. step count + distance).
     */
    private void updateNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainContainerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_directions_walk)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    /**
     * Processes a GPS fix: passes it to the DR engine if accuracy &lt; 15 m and updates the
     * foreground notification with the latest step count and distance (m).
     */
    private void handleGPSUpdate(Location location) {
        if (!isTracking) return;

        if (location.getAccuracy() < 15) {
            deadReckoningEngine.calibrateWithGPS(location);
            gpsCalibrationPoints++;
        }

        int steps = deadReckoningEngine.getStepCount();
        double distance = deadReckoningEngine.getDistance();
        updateNotification(getString(R.string.app_name), getString(R.string.tracking_status_format, steps, distance));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isTracking) return;

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
            case Sensor.TYPE_ACCELEROMETER:
                pocketDetector.update(values);
                break;
            case Sensor.TYPE_PRESSURE:
                deadReckoningEngine.updateBarometer(values[0]);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                deadReckoningEngine.updateSensors(
                        headingEstimator.getGravity(),
                        headingEstimator.getMagneticField(),
                        headingEstimator.getGyroscope(),
                        values,
                        event.timestamp
                );
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** @return {@code true} while a tracking session is active. */
    public boolean isTracking() {
        return isTracking;
    }

    /** @return Total step count for the current session. */
    public int getStepCount() {
        return deadReckoningEngine.getStepCount();
    }

    /** @return Cumulative distance travelled in the current session (m). */
    public double getDistance() {
        return deadReckoningEngine.getDistance();
    }

    /** @return Current heading (°) in [0, 360). */
    public double getHeading() {
        return deadReckoningEngine.getHeading();
    }

    /** @return Number of GPS fixes that met the &lt;15 m accuracy threshold since last {@link #calibrateGPS()} call. */
    public int getCalibrationPoints() {
        return gpsCalibrationPoints;
    }

    /** Records a manual 90° left turn in the dead-reckoning engine. No-op if not tracking. */
    public void turnLeft() {
        if (isTracking) {
            deadReckoningEngine.turnLeft();
        }
    }

    /** Records a manual 90° right turn in the dead-reckoning engine. No-op if not tracking. */
    public void turnRight() {
        if (isTracking) {
            deadReckoningEngine.turnRight();
        }
    }

    /** Records a manual 180° about-face in the dead-reckoning engine. No-op if not tracking. */
    public void turnAround() {
        if (isTracking) {
            deadReckoningEngine.turnAround();
        }
    }

    /** @return Current pocket/screen-facing state from the accelerometer-based detector. */
    public nisargpatel.deadreckoning.sensor.PocketStateDetector.State getPocketState() {
        return pocketDetector.getState();
    }

    /** @return Current barometric elevation estimate (m). */
    public double getElevation() {
        return deadReckoningEngine.getElevation();
    }

    /** Register a painted distance marker from the UI. */
    public void addLandmarkDistance(double measuredMetres) {
        if (isTracking) {
            deadReckoningEngine.addLandmarkDistance(measuredMetres);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        stopTracking();
    }
}
