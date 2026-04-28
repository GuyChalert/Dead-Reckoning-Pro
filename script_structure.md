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
│   ├── UserActivity.java
│   └── UserListActivity.java
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
├── export/
│   └── TunnelMapExporter.java         ← Graph-SLAM path → GeoJSON / KML / CSV
├── extra/
│   └── ExtraFunctions.java
├── filewriting/
│   └── DataFileWriter.java
├── gis/
│   ├── BoundingBoxOverlay.java
│   ├── CapabilitiesParser.java        ← WMS/WMTS GetCapabilities XML parser
│   ├── GeoKeyReader.java              ← GeoTIFF geo-key directory reader
│   ├── GeoTiffImporter.java           ← GeoTIFF → RasterOverlay
│   ├── KmlOverlay.java                ← KML/KMZ import + NetworkLink support
│   ├── LayerAdapter.java              ← RecyclerView adapter for layer list
│   ├── LayerControlSheet.java         ← bottom sheet: add/remove/reorder layers
│   ├── LayerInfo.java
│   ├── LayerManager.java              ← owns all overlay layers on MapView
│   ├── LayerType.java                 ← enum: WMS, WMTS, KML, MBTILES, …
│   ├── MBTilesArchive.java            ← SQLite MBTiles IArchiveFile adapter
│   ├── MapLayer.java                  ← layer descriptor (id, url, name, style)
│   ├── NorthArrowOverlay.java         ← rotating north arrow on map
│   ├── OfflineTileDownloader.java     ← downloads tiles for offline use
│   ├── PrjParser.java                 ← parses .prj projection files
│   ├── RasterOverlay.java             ← draws geo-referenced raster on map canvas
│   ├── ShapefileOverlay.java          ← renders Shapefile geometries as overlays
│   ├── ShapefileReader.java           ← parses .shp / .dbf files
│   ├── WebServiceConnector.java       ← WMS/WMTS connection + capabilities fetch
│   ├── WmsTileSource.java             ← WMS 1.1.1 GetMap tile source
│   └── WmtsTileSource.java            ← WMTS REST/KVP tile source
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
├── power/
│   └── PowerDutyManager.java          ← POCKET_WALKING ↔ HANDHELD_MAPPING duty cycle
├── preferences/
│   ├── StepCounterPreferences.java
│   └── TurnModePreferences.java
├── sensor/
│   ├── BarometerManager.java          ← pressure (hPa) → altitude (m) + calibration
│   ├── DeadReckoningEngine.java       ← core PDR
│   ├── EnhancedStepCounter.java       ← step detection + ZUPT + Weinberg
│   ├── GPSCalibrator.java
│   ├── KalmanFilter.java
│   ├── PocketStateDetector.java       ← classifies POCKET_WALKING vs HANDHELD_MAPPING
│   └── PreciseHeadingEstimator.java
├── service/
│   └── TrackingService.java           ← foreground service
├── slam/
│   ├── ConstraintEdge.java            ← factor graph edge (odometry / GPS / landmark)
│   ├── FactorGraph.java               ← pose graph + LM optimizer
│   ├── GraphSlamEngine.java           ← high-level Graph-SLAM API
│   └── PoseNode.java                  ← (x, y, θ) keyframe node
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

### `BarometerManager.java`
Converts raw `TYPE_PRESSURE` (hPa) to altitude (m) using the ISA barometric formula.

- `calibrateTo(knownAltitudeM)` — computes offset from current pressure reading
- Falls back to a manual elevation when disabled or before first reading

---

### `PocketStateDetector.java`
Classifies phone carry state using raw `TYPE_ACCELEROMETER` (gravity retained).

- **POCKET_WALKING** — phone vertical + high accel variance from gait bounce; heading unreliable from GAME_ROTATION_VECTOR
- **HANDHELD_MAPPING** — phone held out, lower variance, user-controlled orientation
- 50-sample window (~250 ms at 200 Hz); 20-sample hysteresis to suppress per-step flicker
- Variance threshold `3.5 m²/s⁴`, pitch threshold `55°`

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

## Graph-SLAM — `slam/`

Pose-graph optimisation for underground tunnel mapping where GPS is intermittent.

### `GraphSlamEngine.java`
High-level Graph-SLAM API. Coordinate system: local ENZ Cartesian, origin = first GPS fix.

| Method | Description |
|---|---|
| `addGpsAnchor(Location)` | First call sets ENZ origin; subsequent calls add GPS_ANCHOR constraint |
| `addOdometryStep(dx, dy, dTheta)` | Feed one PDR step (body-frame incremental motion) |
| `addLandmarkDistance(metres)` | Register a painted tunnel distance marker |
| `addLoopClosure(from, to, dx, dy, dTheta)` | Add loop-closure constraint between two nodes |
| `optimize()` | Explicit Levenberg-Marquardt optimisation pass |
| `getCorrectedPath()` | Optimised path as `List<GeoPoint>` |

Keyframes created every 10 odometry steps; auto-optimises every 50 new nodes.
Information weights: PDR pos `10 m⁻²`, PDR heading `5 rad⁻²`, GPS `500`, landmark `200`.

### `FactorGraph.java`
Pose graph + Levenberg-Marquardt solver. Holds `List<PoseNode>` and `List<ConstraintEdge>`.

### `PoseNode.java`
Keyframe node: `(id, x, y, theta, timestampNs)`. `isFixed = true` pins the origin.

### `ConstraintEdge.java`
Factory for edge types: `odometry()`, `gpsAnchor()`, `landmarkDistance()`, `loopClosure()`.

---

## Export — `export/`

### `TunnelMapExporter.java`
Exports Graph-SLAM optimised tunnel path to GeoJSON, KML, or CSV.

- Output directory: `getExternalFilesDir("exports")`
- **GeoJSON** — FeatureCollection with LineString; compatible with QGIS, JOSM, Leaflet
- **KML** — Google Earth compatible red LineString Placemark
- **CSV** — `node_id, lat, lon, heading_deg, east_m, north_m` per keyframe

---

## GIS Layer System — `gis/`

### `LayerManager.java`
Manages the ordered set of tile overlay layers on the osmdroid MapView.

Supports: WMS, WMTS, MBTiles, GeoTIFF, KML/KMZ, Shapefile.

| Method | Description |
|---|---|
| `attach(MapView)` | Bind to MapView; wraps OSM base overlay |
| `addLayer(MapLayer)` | Add a WMS/WMTS tile overlay |
| `removeLayer(id)` | Remove layer + overlay |
| `setVisible(id, bool)` | Show/hide without removing |
| `setAlpha(id, float)` | Set opacity [0, 1] |
| `importMBTiles(Uri, name)` | Import MBTiles file from SAF URI |
| `importGeoTiff(Uri)` | Import GeoTIFF raster |
| `importKml(Uri, name)` | Import KML/KMZ (returns overlay count) |
| `importShapefile(Uri, name)` | Import Shapefile (ZIP or raw .shp) |
| `getPresets()` | IGN Orthophoto, Plan Topo, Routes; BRGM Géologie |

Pre-configured presets: `ign_ortho`, `ign_topo`, `ign_routes` (data.geopf.fr WMTS), `brgm_geo` (WMS).

---

### `KmlOverlay.java`
KML/KMZ import with full NetworkLink support.

- Parses Placemarks (Point, LineString, Polygon), GroundOverlay, NetworkLink
- `loadFull(ctx, uri, mapView)` → `LoadResult` (overlays + pending dynamic links)
- `fetchPendingLinks(…)` — fetches viewport-dependent NetworkLinks on scroll/zoom
- Relative hrefs resolved against `httpBase` URL from parent NetworkLink

### `RasterOverlay.java`
Draws a geo-referenced raster image on the map canvas.

- Projects lat/lon bounding box to screen coordinates each draw call
- `draw(Canvas, MapView, boolean)` — clips to map bounds, applies alpha

### `GeoTiffImporter.java`
Reads a GeoTIFF from SAF URI, decodes geo-keys and pixel data, returns a `RasterOverlay`.

### `ShapefileReader.java` / `ShapefileOverlay.java`
Parses .shp + .dbf (from ZIP or raw), renders Point/Polyline/Polygon geometries as osmdroid overlays.

### `MBTilesArchive.java`
SQLite-backed `IArchiveFile` adapter for osmdroid's `MapTileFileArchiveProvider`.

### `WmsTileSource.java` / `WmtsTileSource.java`
`OnlineTileSourceBase` implementations for WMS 1.1.1 GetMap and WMTS REST/KVP tile URLs.

### `CapabilitiesParser.java`
Parses WMS/WMTS GetCapabilities XML to enumerate available layers and tile matrix sets.

### `NorthArrowOverlay.java`
Draws a north arrow overlay that counter-rotates with map rotation.

### `OfflineTileDownloader.java`
Downloads tiles for a bounding box + zoom range into an MBTiles file for offline use.

---

## Power Management — `power/`

### `PowerDutyManager.java`
POCKET_WALKING ↔ HANDHELD_MAPPING duty-cycle state machine.

- **POCKET_WALKING**: screen dims to ~0 brightness, torch off, minimal UI
- **HANDHELD_MAPPING**: screen normal, torch on while tracking (VIO/ARCore ready)
- Driven by `PocketStateDetector.State` fed via `onPocketStateChanged()`
- Torch control via `CameraManager.setTorchMode()` (FLASHLIGHT normal permission)
- All window mutations dispatched on main thread via `Handler`

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

PDR step → GraphSlamEngine.addOdometryStep(dx, dy, dTheta)
  └─ accumulates until STEPS_PER_NODE=10 → flushAccumulated()
       └─ new PoseNode + odometry ConstraintEdge
            └─ every 50 nodes: FactorGraph.optimize() (Levenberg-Marquardt)

GPS fix (accuracy < 20 m)
  └─ GraphSlamEngine.addGpsAnchor(location)
       └─ GPS_ANCHOR ConstraintEdge → optimize()
```
