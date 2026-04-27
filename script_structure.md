# Script Structure — Dead Reckoning Pro

Full class reference for the `app/src/main/java/nisargpatel/deadreckoning/` package.

---

## Package Tree

```
nisargpatel.deadreckoning/
├── DeadReckoningApp.java
├── activity/
│   ├── CalibrationActivity.java
│   ├── DataCollectActivity.java
│   ├── DebugToolsActivity.java
│   ├── GraphActivity.java
│   ├── GuideActivity.java
│   ├── HeadingActivity.java
│   ├── HistoryActivity.java
│   ├── MainContainerActivity.java
│   ├── MainNavigationActivity.java
│   ├── MapFragment.java
│   ├── SplashActivity.java
│   ├── StepCalibrationActivity.java
│   ├── StepCountActivity.java
│   ├── StepsFragment.java
│   └── UserActivity.java / UserListActivity.java
├── adapter/
│   ├── GuideAdapter.java
│   └── TripAdapter.java
├── bias/
│   ├── GyroscopeBias.java
│   └── MagneticFieldBias.java
├── dialog/
│   ├── AccessUserDialogFragment.java
│   ├── GyroCalibrationDialogFragment.java
│   ├── MagCalibrationDialogFragment.java
│   ├── SensorCalibrationDialogFragment.java
│   ├── StepCalibrationDialogFragment.java
│   ├── StepInfoDialogFragment.java
│   └── UserDetailsDialogFragment.java
├── extra/
│   └── ExtraFunctions.java
├── filewriting/
│   └── DataFileWriter.java
├── graph/
│   └── ScatterPlot.java
├── interfaces/
│   ├── OnPreferredStepCounterListener.java
│   └── OnUserUpdateListener.java
├── model/
│   ├── GuideItem.java
│   ├── Marker.java
│   ├── Trip.java
│   └── TurnEvent.java
├── orientation/
│   ├── GyroscopeDeltaOrientation.java
│   ├── GyroscopeEulerOrientation.java
│   └── MagneticFieldOrientation.java
├── permission/
│   └── PermissionHelper.java
├── preferences/
│   ├── StepCounterPreferences.java
│   └── TurnModePreferences.java
├── sensor/
│   ├── DeadReckoningEngine.java   ← core PDR
│   ├── EnhancedStepCounter.java   ← step detection + ZUPT + Weinberg
│   ├── GPSCalibrator.java
│   ├── KalmanFilter.java
│   └── PreciseHeadingEstimator.java
├── service/
│   └── TrackingService.java       ← foreground service
├── stepcounting/
│   ├── DynamicStepCounter.java
│   └── StaticStepCounter.java
├── storage/
│   ├── MarkerStorage.java
│   └── TripStorage.java
└── view/
    └── DegreeDialView.java
```

---

## Core Engine — `sensor/`

### `DeadReckoningEngine.java`
Central PDR state machine. Owns position, heading, step count, and trip recording.

| Method | Description |
|---|---|
| `start()` | Reset state and begin new trip |
| `stop()` | Finalise trip, compute totals |
| `updateSensors(gravity, magnetic, gyro, linearAccel, timestamp)` | Main sensor ingestion loop — called on every `TYPE_LINEAR_ACCELERATION` event |
| `addAndroidStep()` | Increment position from hardware step counter |
| `calibrateWithGPS(location)` | Feed a GPS fix to `GPSCalibrator`; updates scale + heading bias |
| `turnLeft/Right/Around()` | Manual heading override (90° increments) |
| `setFixedHeading(deg)` | Lock heading to explicit value |
| `resetToCompassHeading()` | Release manual heading lock |

**Drift control wiring** (v2.1):
- Subtracts `GyroscopeBias.getBias()` from raw gyro before heading update
- Calls `GyroscopeBias.updateFromZupt()` when `EnhancedStepCounter.isStationary()` is true
- Heading update guard changed from `gravity != null && magnetic != null` to `headingEstimator.hasValidData()` so GAME_ROTATION_VECTOR alone is sufficient

---

### `PreciseHeadingEstimator.java`
Two-mode heading estimator.

**Primary mode** — `TYPE_GAME_ROTATION_VECTOR`:
- Hardware-fused on Snapdragon 8 Elite sensor hub (OnePlus 13)
- No magnetometer → immune to tunnel magnetic interference
- Activated automatically when `updateRotationVector()` is called

**Fallback mode** — Mahony complementary filter:
- Gyro propagates heading each `dt`: `heading -= gyro[2] * dt`
- Magnetometer applies correction: `heading += GAIN × wrap(magHeading − heading)`
- Replaces original broken `ALPHA * gyroIntegral + (1-ALPHA) * magAbsolute` blend

| Method | Description |
|---|---|
| `updateRotationVector(float[])` | Feed `TYPE_GAME_ROTATION_VECTOR` data |
| `updateGravity(float[])` | Feed `TYPE_GRAVITY` |
| `updateMagneticField(float[])` | Feed `TYPE_MAGNETIC_FIELD` |
| `updateGyroscope(float[], long)` | Feed bias-corrected gyro + timestamp |
| `getHeadingDegrees()` | Returns current heading in degrees [−180, 180] |
| `hasValidData()` | True if any valid orientation source present |
| `usingRotationVector()` | True if GAME_ROTATION_VECTOR is active |

---

### `EnhancedStepCounter.java`
Step detector for `TYPE_LINEAR_ACCELERATION` (gravity removed).

**Bug fixes vs original:**
- Thresholds: `HIGH = 2.5 m/s²`, `LOW = 0.4 m/s²` (was 11.5 / 9.5 — never triggered on linear accel)
- Min-time debounce: `250_000_000 ns` (was `250`, comparing nanoseconds to 250 ≈ 0 ms)

**Weinberg stride model:**
```
stride = K × (a_max − a_min)^0.25
```
- `K = 0.42` default; clamped to [0.40, 1.10] m
- `calibrateWeinbergK(knownDistance, steps)` — tune K after GPS-calibrated walk
- Replaces fixed 0.75 m constant

**ZUPT (Zero-Velocity Update):**
- Variance of last 30 accel magnitude samples < 0.04 m²/s⁴ → `isStationary() = true`
- Caller (`DeadReckoningEngine`) uses this to trigger online gyro bias update

| Method | Description |
|---|---|
| `detectStep(float[], long)` | Main entry — returns true on confirmed step |
| `isStationary()` | True during detected zero-velocity window |
| `setStrideLength(double)` | Manual override; disables Weinberg |
| `enableWeinberg(boolean)` | Re-enable adaptive stride |
| `calibrateWeinbergK(double, int)` | Calibrate K from known distance + steps |

---

### `GPSCalibrator.java`
Corrects accumulated PDR error using GPS fixes.

- Requires `MIN_CALIBRATION_POINTS = 3` fixes before applying corrections
- **Scale factor**: `gpsDistance / estimatedDistance` (EMA α = 0.2)
- **Heading bias**: `gpsBearing − estimatedBearing` (EMA α = 0.1)
- Stores up to `MAX_CALIBRATION_POINTS = 20` recent fixes (sliding window)

---

### `KalmanFilter.java`
Scalar 1D Kalman filter. Used by `EnhancedStepCounter` to smooth accelerometer magnitude.

Parameters: process noise `q`, measurement noise `r`, initial estimate, initial error.

---

## Bias Estimation — `bias/`

### `GyroscopeBias.java`
Two-phase bias estimator:

1. **Static phase** (`calcBias`) — running average over first N samples while device is still at startup
2. **Online phase** (`updateFromZupt`) — slow EMA (α = 0.005) during ZUPT windows to track thermal drift across long walks

`getBias()` returns `float[3]` in rad/s — subtracted from raw gyro before heading update.

### `MagneticFieldBias.java`
Hard-iron + soft-iron magnetometer calibration (Freescale AN4246 algorithm).
Builds `X^T X` and `X^T Y` matrices incrementally; `getBias()` solves via matrix inversion.
Used only during manual calibration flow — not in the live tracking path.

---

## Foreground Service — `service/`

### `TrackingService.java`
Runs tracking in background as an Android foreground service.

**Sensors registered** (v2.1):
| Sensor | Rate |
|---|---|
| `TYPE_GRAVITY` | SENSOR_DELAY_GAME |
| `TYPE_MAGNETIC_FIELD` | SENSOR_DELAY_GAME |
| `TYPE_GYROSCOPE` | SENSOR_DELAY_GAME |
| `TYPE_LINEAR_ACCELERATION` | SENSOR_DELAY_GAME |
| `TYPE_GAME_ROTATION_VECTOR` | SENSOR_DELAY_GAME |

**`onSensorChanged` routing:**
```
GRAVITY              → headingEstimator.updateGravity()
MAGNETIC_FIELD       → headingEstimator.updateMagneticField()
GYROSCOPE            → headingEstimator.updateGyroscope()          ← bug fix: was missing
GAME_ROTATION_VECTOR → headingEstimator.updateRotationVector()     ← new
LINEAR_ACCELERATION  → deadReckoningEngine.updateSensors(...)
```

GPS updates filtered to accuracy < 15 m; fed to `deadReckoningEngine.calibrateWithGPS()`.

Wake lock held for up to 10 hours; released on `stopTracking()`.

---

## Step Counting — `stepcounting/`

### `DynamicStepCounter.java`
Adaptive threshold step counter. Used directly by calibration and step-count activities (not by `DeadReckoningEngine`).

- Thresholds track `avgAcc ± sensitivity`
- **Fix (v2.1)**: continuous moving-average replaced with EMA (α = 0.02) — avoids threshold freezing as `runCount → ∞`

### `StaticStepCounter.java`
Fixed-threshold step counter (`upper = 2.0`, `lower = 1.9` in engine; higher defaults in standalone use).
Simpler reference implementation used in STATIC step mode.

---

## Activities — `activity/`

### `MapFragment.java`
Main UI fragment. Owns map rendering (OSMDroid), marker management, GPS overlay, and its own sensor loop for live heading display.

**Sensor fixes (v2.1):** Added `TYPE_GYROSCOPE` and `TYPE_GAME_ROTATION_VECTOR` cases to `onSensorChanged` switch; both were missing.

Key responsibilities:
- Draws path polyline and custom emoji markers on map
- Two-finger rotation gesture recogniser
- Calls `deadReckoningEngine.updateSensors()` on each linear-accel event
- GPS location overlay + accuracy circle

### `MainNavigationActivity.java`
Alternative navigation view. Also fixed in v2.1 to register and route `TYPE_GAME_ROTATION_VECTOR`.

### `StepCalibrationActivity.java` / `StepCountActivity.java` / `StepsFragment.java`
Use `DynamicStepCounter[]` arrays directly (20 counters at varying sensitivities) to let users choose their preferred sensitivity setting.

### `GraphActivity.java`
Visualises raw accelerometer + step-detection data. Uses `DynamicStepCounter` standalone.

### `CalibrationActivity.java`
GPS + step-length calibration workflow. Triggers `calibrateGPS()` on the service.

### `HistoryActivity.java`
Lists saved `Trip` objects; supports GPX/CSV export and trip deletion.

---

## Models — `model/`

| Class | Fields | Notes |
|---|---|---|
| `Trip` | path points, turn events, distance, steps, timestamps | GPX + CSV export built-in |
| `Marker` | lat, lon, emoji, label | JSON-persisted via `MarkerStorage` |
| `TurnEvent` | type (LEFT/RIGHT/UTURN), angle, step number, position | Embedded in `Trip` |
| `GuideItem` | title, body | Help content |

---

## Storage — `storage/`

| Class | Backend | Key operations |
|---|---|---|
| `TripStorage` | JSON files in app private storage | `saveTrip`, `loadTrips`, `deleteTrip` |
| `MarkerStorage` | Single JSON array file | `saveMarkers`, `loadMarkers` |

---

## Orientation Helpers — `orientation/`

Legacy orientation classes predating EJML import. Not used in current live tracking path — superseded by `PreciseHeadingEstimator`.

| Class | Algorithm |
|---|---|
| `GyroscopeEulerOrientation` | Direction cosine matrix from Euler angles |
| `GyroscopeDeltaOrientation` | Delta rotation matrix integration |
| `MagneticFieldOrientation` | Rotation matrix from gravity + magnetic |

---

## View — `view/`

### `DegreeDialView.java`
Custom `View` — 360° compass dial. Drag to select heading in manual mode. Draws tick marks, cardinal labels, and a rotating needle.

---

## Utilities

### `ExtraFunctions.java`
Static helpers: matrix multiply, matrix add, matrix scale, norm calculation, factorial, `denseMatrixToArray` (EJML bridge).

### `DataFileWriter.java`
Writes GPX and CSV files to user-chosen folder or Downloads.

### `PermissionHelper.java`
Centralised runtime permission request and check logic.

### `KalmanFilter.java` *(in sensor/ package)*
Scalar Kalman filter. See `sensor/` section above.

---

## Data Flow: One Step (underground)

```
SensorEvent (LINEAR_ACCELERATION)
  └─ TrackingService.onSensorChanged()
       ├─ headingEstimator.getGravity/getMagneticField/getGyroscope()
       └─ deadReckoningEngine.updateSensors(gravity, mag, gyro, linearAccel, ts)
            ├─ gyroBias.getBias() → correctedGyro
            ├─ headingEstimator.updateGyroscope(correctedGyro, ts)
            ├─ headingEstimator.getHeadingDegrees()          ← GAME_ROTATION_VECTOR if available
            ├─ EnhancedStepCounter.detectStep(linearAccel, ts)
            │    ├─ Kalman filter magnitude
            │    ├─ updateZupt() → isStationary?
            │    ├─ updateStepWindow() → track a_max, a_min
            │    └─ peak detection → step confirmed
            │         └─ updateStrideFromWeinberg() → currentStrideLength
            ├─ if step: updatePosition()
            │    └─ x += stride × sin(heading)
            │       y += stride × cos(heading)
            └─ if isStationary: gyroBias.updateFromZupt(rawGyro)

SensorEvent (GAME_ROTATION_VECTOR)
  └─ TrackingService.onSensorChanged()
       └─ headingEstimator.updateRotationVector(values)
            └─ sets hasRotationVector = true
               next getHeading() → SensorManager.getRotationMatrixFromVector()
```
