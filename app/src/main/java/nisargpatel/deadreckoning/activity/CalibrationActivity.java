package nisargpatel.deadreckoning.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;
import android.widget.LinearLayout;
import android.location.Location;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import nisargpatel.deadreckoning.R;
import nisargpatel.deadreckoning.extra.ExtraFunctions;
import nisargpatel.deadreckoning.preferences.StepCounterPreferences;
import nisargpatel.deadreckoning.sensor.BarometerManager;
import nisargpatel.deadreckoning.sensor.EnhancedStepCounter;

/**
 * Calibration screen with three sections:
 * <ol>
 *   <li><b>Stride calibration</b>: walks a GPS-measured distance to derive stride length (m),
 *       or enters known distance + steps manually. Saves to {@code CalibrationPrefs}.</li>
 *   <li><b>Step-mode picker</b>: selects DYNAMIC, ANDROID, or STATIC algorithm via radio buttons,
 *       persisted in {@link StepCounterPreferences}.</li>
 *   <li><b>Barometer</b>: toggles {@link BarometerManager}, calibrates to a known altitude (m),
 *       or sets a manual fallback elevation. Settings saved in {@code barometer_prefs}.</li>
 * </ol>
 */
public class CalibrationActivity extends AppCompatActivity implements SensorEventListener {

    private static final String PREFS_NAME = "CalibrationPrefs";
    private static final String PREF_STRIDE_LENGTH = "stride_length";
    private static final String PREF_CALIBRATED = "is_calibrated";

    private SharedPreferences prefs;
    private StepCounterPreferences stepPrefs;
    private SensorManager sensorManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    private RadioGroup radioGroupStepMode;
    private MaterialRadioButton radioDynamic, radioAndroid, radioStatic;
    private Button buttonSaveStepMode;

    private Sensor sensorLinearAcceleration;
    private Sensor sensorMagneticField;
    private Sensor sensorGravity;

    private TextView textCurrentSteps;
    private TextView textMeasuredDistance;
    private TextView textCalculatedStride;
    private TextView textGPSStatus;
    private TextView textHeadingValue;
    private TextView textAccuracyValue;
    private TextView textCalibrationStatus;
    private CircularProgressIndicator progressIndicator;

    private TextInputEditText inputKnownDistance;
    private TextInputEditText inputKnownSteps;

    private Button buttonStartCalibration;
    private Button buttonSaveCalibration;
    private Button buttonResetCalibration;

    private int calibrationStepCount = 0;
    private double measuredDistance = 0;
    private double calculatedStrideLength = 0.75;
    private double startLatitude = 0;
    private double startLongitude = 0;
    private double lastLatitude = 0;
    private double lastLongitude = 0;
    private boolean isCalibrating = false;
    private final AtomicBoolean isStepDetected = new AtomicBoolean(false);
    private double lastAccMagnitude = 0;
    
    private EnhancedStepCounter stepCounter;
    private boolean useAndroidSteps = false;
    private int androidStepCount = 0;

    // Barometer
    private static final String BARO_PREFS = "barometer_prefs";
    private BarometerManager barometerManager;
    private Sensor sensorPressure;
    private com.google.android.material.switchmaterial.SwitchMaterial switchBarometer;
    private TextView textBarometerReading;
    private LinearLayout layoutBarometerCalibrate;
    private LinearLayout layoutManualElevation;
    private com.google.android.material.textfield.TextInputEditText inputKnownAltitude;
    private com.google.android.material.textfield.TextInputEditText inputManualElevation;

    private float[] gravityValues = null;
    private float[] magValues = null;
    private double currentHeading = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        stepPrefs = new StepCounterPreferences(this);

        initViews();
        setupToolbar();
        initSensors();
        loadSavedCalibration();
        loadStepModeSelection();
        initBarometerUI();
    }

    private void initViews() {
        textCurrentSteps = findViewById(R.id.textCurrentSteps);
        textMeasuredDistance = findViewById(R.id.textMeasuredDistance);
        textCalculatedStride = findViewById(R.id.textCalculatedStride);
        textGPSStatus = findViewById(R.id.textGPSStatus);
        textHeadingValue = findViewById(R.id.textHeadingValue);
        textAccuracyValue = findViewById(R.id.textAccuracyValue);
        textCalibrationStatus = findViewById(R.id.textCalibrationStatus);
        progressIndicator = findViewById(R.id.progressIndicator);

        inputKnownDistance = findViewById(R.id.inputKnownDistance);
        inputKnownSteps = findViewById(R.id.inputKnownSteps);

        buttonStartCalibration = findViewById(R.id.buttonStartCalibration);
        buttonSaveCalibration = findViewById(R.id.buttonSaveCalibration);
        buttonResetCalibration = findViewById(R.id.buttonResetCalibration);

        radioGroupStepMode = findViewById(R.id.radioGroupStepMode);
        radioDynamic = findViewById(R.id.radioDynamic);
        radioAndroid = findViewById(R.id.radioAndroid);
        radioStatic = findViewById(R.id.radioStatic);
        buttonSaveStepMode = findViewById(R.id.buttonSaveStepMode);

        buttonStartCalibration.setOnClickListener(v -> toggleCalibration());
        buttonSaveCalibration.setOnClickListener(v -> saveCalibration());
        buttonResetCalibration.setOnClickListener(v -> resetCalibration());
        buttonSaveStepMode.setOnClickListener(v -> saveStepMode());
        
        radioGroupStepMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radioDynamic) {
                showStepModeInfo("Dynamic: Uses acceleration + moving average. Best for walking.");
            } else if (checkedId == R.id.radioAndroid) {
                showStepModeInfo("Android: Uses built-in step detector. Most accurate but needs hardware support.");
            } else if (checkedId == R.id.radioStatic) {
                showStepModeInfo("Static: Uses simple thresholds. Fast but less accurate.");
            }
        });
    }
    
    /** Displays a step-mode description in the status text view (primary color). */
    private void showStepModeInfo(String message) {
        textCalibrationStatus.setText(message);
        textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary));
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorLinearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        sensorMagneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorGravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        sensorPressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        stepCounter = new EnhancedStepCounter();
    }

    /**
     * Wires the barometer section: reads saved state from {@code barometer_prefs},
     * sets up the toggle switch, "Calibrate Altitude" button (adjusts offset to match known m),
     * and "Save Manual Elevation" button (no-barometer fallback altitude in m).
     */
    private void initBarometerUI() {
        switchBarometer = findViewById(R.id.switchBarometer);
        textBarometerReading = findViewById(R.id.textBarometerReading);
        layoutBarometerCalibrate = findViewById(R.id.layoutBarometerCalibrate);
        layoutManualElevation = findViewById(R.id.layoutManualElevation);
        inputKnownAltitude = findViewById(R.id.inputKnownAltitude);
        inputManualElevation = findViewById(R.id.inputManualElevation);

        barometerManager = new BarometerManager();
        SharedPreferences baroPrefs = getSharedPreferences(BARO_PREFS, MODE_PRIVATE);
        barometerManager.setEnabled(baroPrefs.getBoolean("barometer_enabled", true));
        barometerManager.setManualElevation(baroPrefs.getFloat("manual_elevation_m", 0f));
        barometerManager.setCalibrationOffset(baroPrefs.getFloat("calibration_offset_m", 0f));

        switchBarometer.setChecked(barometerManager.isEnabled());
        updateBarometerVisibility(barometerManager.isEnabled());

        switchBarometer.setOnCheckedChangeListener((btn, checked) -> {
            barometerManager.setEnabled(checked);
            getSharedPreferences(BARO_PREFS, MODE_PRIVATE).edit()
                    .putBoolean("barometer_enabled", checked).apply();
            updateBarometerVisibility(checked);
            if (checked && sensorPressure != null) {
                sensorManager.registerListener(this, sensorPressure, SensorManager.SENSOR_DELAY_NORMAL);
            } else {
                sensorManager.unregisterListener(this, sensorPressure);
            }
        });

        Button buttonCalibrateAltitude = findViewById(R.id.buttonCalibrateAltitude);
        buttonCalibrateAltitude.setOnClickListener(v -> {
            if (inputKnownAltitude.getText() == null) return;
            String s = inputKnownAltitude.getText().toString().trim();
            try {
                float known = Float.parseFloat(s);
                barometerManager.calibrateTo(known);
                getSharedPreferences(BARO_PREFS, MODE_PRIVATE).edit()
                        .putFloat("calibration_offset_m", barometerManager.getCalibrationOffsetM()).apply();
                Toast.makeText(this, getString(R.string.barometer_calibrated, known), Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid altitude value", Toast.LENGTH_SHORT).show();
            }
        });

        Button buttonSaveManualElevation = findViewById(R.id.buttonSaveManualElevation);
        buttonSaveManualElevation.setOnClickListener(v -> {
            if (inputManualElevation.getText() == null) return;
            String s = inputManualElevation.getText().toString().trim();
            try {
                float alt = Float.parseFloat(s);
                barometerManager.setManualElevation(alt);
                getSharedPreferences(BARO_PREFS, MODE_PRIVATE).edit()
                        .putFloat("manual_elevation_m", alt).apply();
                Toast.makeText(this, getString(R.string.manual_elevation_saved, alt), Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid elevation value", Toast.LENGTH_SHORT).show();
            }
        });

        if (barometerManager.isEnabled() && sensorPressure != null) {
            sensorManager.registerListener(this, sensorPressure, SensorManager.SENSOR_DELAY_NORMAL);
        }
        inputManualElevation.setText(String.valueOf((int) barometerManager.getManualElevation()));
    }

    /** Shows altitude calibration layout when barometer is on; shows manual elevation input when off. */
    private void updateBarometerVisibility(boolean barometerOn) {
        layoutBarometerCalibrate.setVisibility(barometerOn ? View.VISIBLE : View.GONE);
        layoutManualElevation.setVisibility(barometerOn ? View.GONE : View.VISIBLE);
    }

    /** Restores persisted stride length and calibration status from SharedPreferences. */
    private void loadSavedCalibration() {
        double savedStride = prefs.getFloat(PREF_STRIDE_LENGTH, 0.75f);
        boolean wasCalibrated = prefs.getBoolean(PREF_CALIBRATED, false);

        calculatedStrideLength = savedStride;
        textCalculatedStride.setText(String.format(Locale.US, "%.2f m", savedStride));

        if (wasCalibrated) {
            textCalibrationStatus.setText("Calibrated");
            textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
            buttonSaveCalibration.setEnabled(false);
        }
    }

    /** Pre-selects the radio button corresponding to the saved {@link StepCounterPreferences.StepMode}. */
    private void loadStepModeSelection() {
        StepCounterPreferences.StepMode mode = stepPrefs.getStepMode();
        switch (mode) {
            case DYNAMIC: radioDynamic.setChecked(true); break;
            case ANDROID: radioAndroid.setChecked(true); break;
            case STATIC: radioStatic.setChecked(true); break;
        }
    }

    /** Reads the selected radio button, persists the mode via {@link StepCounterPreferences}, and shows a confirmation dialog. */
    private void saveStepMode() {
        StepCounterPreferences.StepMode selectedMode;
        String modeName;
        if (radioDynamic.isChecked()) {
            selectedMode = StepCounterPreferences.StepMode.DYNAMIC;
            modeName = "Dynamic";
        } else if (radioAndroid.isChecked()) {
            selectedMode = StepCounterPreferences.StepMode.ANDROID;
            modeName = "Android";
        } else {
            selectedMode = StepCounterPreferences.StepMode.STATIC;
            modeName = "Static";
        }
        
        stepPrefs.setStepMode(selectedMode);
        Toast.makeText(this, "Step mode: " + modeName + " saved!", Toast.LENGTH_SHORT).show();
        
        new AlertDialog.Builder(this)
            .setTitle("Step Mode Changed")
            .setMessage("The step counting mode has been changed to " + modeName + ".\n\n" +
                (selectedMode == StepCounterPreferences.StepMode.DYNAMIC ? 
                    "Uses acceleration + moving average. Best for walking." :
                    selectedMode == StepCounterPreferences.StepMode.ANDROID ?
                    "Uses built-in step detector. Most accurate." :
                    "Uses simple thresholds. Fast but less accurate."))
            .setPositiveButton("OK", null)
            .show();
    }

    /** Starts calibration if idle, stops it if already running. */
    private void toggleCalibration() {
        if (isCalibrating) {
            stopCalibration();
        } else {
            startCalibration();
        }
    }

    /**
     * Resets counters, registers sensors and GPS (1 s/0.5 s interval, high accuracy),
     * and begins accumulating steps and GPS distance. Uses hardware step detector when available,
     * falls back to {@link EnhancedStepCounter} on linear acceleration.
     */
    @SuppressLint("MissingPermission")
    private void startCalibration() {
        calibrationStepCount = 0;
        measuredDistance = 0;
        startLatitude = 0;
        startLongitude = 0;
        lastLatitude = 0;
        lastLongitude = 0;
        isCalibrating = true;

        buttonStartCalibration.setText("Stop");
        buttonStartCalibration.setBackgroundColor(ContextCompat.getColor(this, R.color.colorError));
        progressIndicator.setVisibility(CircularProgressIndicator.VISIBLE);

        textCurrentSteps.setText("0");
        textMeasuredDistance.setText("0.00 m");
        textCalculatedStride.setText("0.75 m");
        textGPSStatus.setText("Getting GPS...");
        
        if (stepCounter != null) {
            stepCounter.reset();
        }
        
        sensorManager.registerListener(this, sensorLinearAcceleration, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, sensorMagneticField, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, sensorGravity, SensorManager.SENSOR_DELAY_FASTEST);
        
        Sensor stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        if (stepDetector != null) {
            sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_FASTEST);
            useAndroidSteps = true;
        } else {
            useAndroidSteps = false;
        }
        
        stepCounter.reset();

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
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

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
        
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null && isCalibrating) {
                handleGPSUpdate(location);
            }
        });
        
        Toast.makeText(this, "Walk to calibrate - GPS acquiring...", Toast.LENGTH_SHORT).show();
    }

    /** Unregisters sensors and GPS, computes final stride length = distance / steps, enables Save. */
    private void stopCalibration() {
        isCalibrating = false;
        sensorManager.unregisterListener(this);
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        buttonStartCalibration.setText("Start");
        buttonStartCalibration.setBackgroundColor(ContextCompat.getColor(this, R.color.colorPrimary));
        progressIndicator.setVisibility(CircularProgressIndicator.GONE);

        if (calibrationStepCount > 0 && measuredDistance > 0) {
            calculatedStrideLength = measuredDistance / calibrationStepCount;
            textCalculatedStride.setText(String.format(Locale.US, "%.2f m", calculatedStrideLength));
            buttonSaveCalibration.setEnabled(true);
        }
    }

    /**
     * Updates GPS status label by accuracy tier (&lt;10/&lt;20/&lt;30/≥30 m).
     * Accumulates path distance from consecutive fixes with accuracy &lt;30 m,
     * filtering segments outside the range [0.3, 15] m to reject noise and jumps.
     */
    private void handleGPSUpdate(Location location) {
        if (!isCalibrating) return;

        double accuracy = location.getAccuracy();
        textAccuracyValue.setText(String.format(Locale.US, "%.1f m", accuracy));
        
        if (accuracy < 10) {
            textGPSStatus.setText("GPS: Excellent");
            textGPSStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
        } else if (accuracy < 20) {
            textGPSStatus.setText("GPS: Good");
            textGPSStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
        } else if (accuracy < 30) {
            textGPSStatus.setText("GPS: Fair");
            textGPSStatus.setTextColor(ContextCompat.getColor(this, R.color.colorWarning));
        } else {
            textGPSStatus.setText("GPS: Poor - Move to open area");
            textGPSStatus.setTextColor(ContextCompat.getColor(this, R.color.colorError));
        }

        if (accuracy < 30) {
            if (startLatitude == 0 && startLongitude == 0) {
                startLatitude = location.getLatitude();
                startLongitude = location.getLongitude();
            }

            if (lastLatitude != 0 && lastLongitude != 0) {
                float[] results = new float[1];
                Location.distanceBetween(lastLatitude, lastLongitude, location.getLatitude(), location.getLongitude(), results);
                double segmentDistance = results[0];
                
                if (segmentDistance > 0.3 && segmentDistance < 15) {
                    measuredDistance += segmentDistance;
                    textMeasuredDistance.setText(String.format(Locale.US, "%.2f m", measuredDistance));
                    
                    if (calibrationStepCount > 0) {
                        double currentStride = measuredDistance / calibrationStepCount;
                        textCalculatedStride.setText(String.format(Locale.US, "%.2f m", currentStride));
                    }
                }
            }

            lastLatitude = location.getLatitude();
            lastLongitude = location.getLongitude();
        }
    }

    /**
     * Saves stride length to SharedPreferences. If manual distance + steps are provided, they
     * override the GPS-derived value (distance ÷ steps). Marks calibration status as "Calibrated".
     */
    private void saveCalibration() {
        String knownDistanceStr = inputKnownDistance.getText().toString();
        String knownStepsStr = inputKnownSteps.getText().toString();

        if (!knownDistanceStr.isEmpty() && !knownStepsStr.isEmpty()) {
            try {
                double knownDistance = Double.parseDouble(knownDistanceStr);
                int knownSteps = Integer.parseInt(knownStepsStr);
                if (knownSteps > 0) {
                    calculatedStrideLength = knownDistance / knownSteps;
                }
            } catch (NumberFormatException ignored) {}
        }

        prefs.edit()
                .putFloat(PREF_STRIDE_LENGTH, (float) calculatedStrideLength)
                .putBoolean(PREF_CALIBRATED, true)
                .apply();

        textCalibrationStatus.setText("Calibrated");
        textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccess));
        textCalculatedStride.setText(String.format(Locale.US, "%.2f m", calculatedStrideLength));
        buttonSaveCalibration.setEnabled(false);

        Toast.makeText(this, "Calibration saved!", Toast.LENGTH_SHORT).show();
    }

    private void resetCalibration() {
        new AlertDialog.Builder(this)
                .setTitle("Reset")
                .setMessage("Do you want to reset the calibration?")
                .setPositiveButton("Yes", (d, w) -> {
                    prefs.edit()
                            .putFloat(PREF_STRIDE_LENGTH, 0.75f)
                            .putBoolean(PREF_CALIBRATED, false)
                            .apply();

                    calculatedStrideLength = 0.75;
                    textCalculatedStride.setText("0.75 m");
                    textCalibrationStatus.setText("Not calibrated");
                    textCalibrationStatus.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                    buttonSaveCalibration.setEnabled(false);
                    Toast.makeText(this, "Calibration reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_LINEAR_ACCELERATION:
                if (!useAndroidSteps) {
                    processStepDetection(event.values, event.timestamp);
                }
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                calibrationStepCount++;
                textCurrentSteps.setText(String.valueOf(calibrationStepCount));
                if (measuredDistance > 0 && calibrationStepCount > 0) {
                    double currentStride = measuredDistance / calibrationStepCount;
                    textCalculatedStride.setText(String.format(Locale.US, "%.2f m", currentStride));
                }
                break;
            case Sensor.TYPE_GRAVITY:
                gravityValues = event.values.clone();
                updateHeading();
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                magValues = event.values.clone();
                updateHeading();
                break;
            case Sensor.TYPE_PRESSURE:
                if (barometerManager != null && barometerManager.isEnabled()) {
                    barometerManager.onPressureReading(event.values[0]);
                    float alt = barometerManager.getAltitudeM();
                    if (textBarometerReading != null) {
                        textBarometerReading.setText(
                                getString(R.string.barometer_reading, event.values[0], alt));
                    }
                }
                break;
        }
    }

    /**
     * Fallback step detection via {@link EnhancedStepCounter} when TYPE_STEP_DETECTOR is unavailable.
     *
     * @param values    Linear acceleration [x, y, z] (m/s²).
     * @param timestamp Event timestamp (ns).
     */
    private void processStepDetection(float[] values, long timestamp) {
        if (stepCounter.detectStep(values, timestamp)) {
            calibrationStepCount++;
            textCurrentSteps.setText(String.valueOf(calibrationStepCount));
            
            if (measuredDistance > 0 && calibrationStepCount > 0) {
                double currentStride = measuredDistance / calibrationStepCount;
                textCalculatedStride.setText(String.format(Locale.US, "%.2f m", currentStride));
            }
        }
    }

    /** Recomputes magnetic heading (°) from gravity + mag via rotation matrix whenever either sensor updates. */
    private void updateHeading() {
        if (gravityValues != null && magValues != null) {
            float[] rotationMatrix = new float[9];
            float[] orientationAngles = new float[3];
            if (SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, magValues)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                currentHeading = Math.toDegrees(orientationAngles[0]);
                if (currentHeading < 0) currentHeading += 360;
                textHeadingValue.setText(String.format(Locale.US, "%.1f°", currentHeading));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onPause() {
        super.onPause();
        if (isCalibrating) {
            stopCalibration();
        }
        sensorManager.unregisterListener(this);
    }
}
