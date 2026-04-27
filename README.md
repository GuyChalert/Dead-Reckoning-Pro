# Dead Reckoning Pro

*GPS-free navigation app for Android using Pedestrian Dead Reckoning (PDR). Tracks position via sensor fusion when GPS is unavailable. Built with Android, OSMDroid, and custom PDR algorithms.*

*Created with AI assistance*

---

## Screenshots

<p align="center">
  <img src="https://github.com/user-attachments/assets/c7cc0cca-6442-4a0f-8756-489710d3d809" width="250" />
  <img src="https://github.com/user-attachments/assets/820db7ab-47c6-4e31-b60d-fb4459e32c40" width="250" />
  <img src="https://github.com/user-attachments/assets/4151b176-e890-4369-a4de-6925e7cf746d" width="250" />
</p>

---

## What's New (v2.1 — OnePlus 13 / Underground Optimisation)

### Sensor Fixes & Drift Reduction

**Critical bug fixes** that were silently breaking the original tracking:

- **Gyroscope was never processed** (`TYPE_GYROSCOPE` case missing from all sensor switches). Heading fusion was gyro-blind — now fixed in `TrackingService`, `MapFragment`, and `MainNavigationActivity`.
- **Step counter thresholds wrong for linear acceleration** — original used 9.5–11.5 m/s² against `TYPE_LINEAR_ACCELERATION` (gravity removed), so steps near 0 g were never detected. Fixed to 0.4–2.5 m/s².
- **Step minimum-time check was nanoseconds vs 250** — compared sensor timestamp (ns) to constant 250, effectively 250 ns ≈ 0 ms debounce. Fixed to 250 ms = 250 000 000 ns.
- **DynamicStepCounter moving-average froze** — unbounded `runCount` denominator caused thresholds to stop adapting after warmup. Replaced with fixed-α EMA (α = 0.02).
- **Complementary filter shape was wrong** — mixed accumulated gyro integral with absolute magnetometer heading using simple blend. Replaced with Mahony-style correction (gyro propagates, mag corrects error).

**New features for underground / long-distance use:**

- **`TYPE_GAME_ROTATION_VECTOR`** (hardware-fused, magnetometer-free) used as primary heading source. No magnetic interference from tunnel steel, pipes, or cables. Falls back to corrected gravity+mag+gyro filter when unavailable.
- **Weinberg stride model** — `stride = K × (a_max - a_min)^0.25` per step. Stride adapts to walking pace instead of using a fixed 0.75 m constant.
- **ZUPT (Zero-Velocity Update)** — detects stationary windows (accelerometer variance < 0.04 m²/s⁴). During ZUPT, gyro bias is updated online via slow EMA, tracking thermal drift across a long walk.
- **Online gyro bias correction** — `GyroscopeBias.updateFromZupt()` runs continuously during stops, compensating temperature-induced drift (relevant for 60+ min underground walks).

### Map & UI Features (v2.0)

- **Custom markers** with 18 emoji options — persistent across sessions
- **Two-finger 360° map rotation**
- **Smart clear** — tracks only / markers only / everything
- **GPX & CSV export** with custom folder selection

---

## How It Works

### Pedestrian Dead Reckoning (PDR)

```
x_new = x_old + stride_length × sin(heading)
y_new = y_old + stride_length × cos(heading)
```

1. **Step detection** — peak detection on `TYPE_LINEAR_ACCELERATION` magnitude
2. **Stride length** — Weinberg model: `K × (a_max − a_min)^0.25` per step window; K calibrated from GPS when available
3. **Heading** — `TYPE_GAME_ROTATION_VECTOR` preferred (hardware sensor fusion, no magnetometer); Mahony complementary filter as fallback
4. **Drift control** — ZUPT detects stops → online gyro bias update; GPS corrects scale + heading bias above ground

### GPS Calibration (above ground)

When GPS accuracy < 15 m, `GPSCalibrator` computes:
- **Scale factor** — ratio of GPS distance to estimated PDR distance (EMA-smoothed)
- **Heading bias** — angular difference between GPS bearing and estimated bearing

Both corrections persist and are reapplied when GPS is lost.

---

## Performance: 10 km Underground Walk

**Scenario**: ~13 300 steps, ~100 min, no GPS, no beacons.

| Source of error | Magnitude | Mitigated by |
|---|---|---|
| Gyro drift (Snapdragon 8 Elite) | ~1–2°/min | GAME_ROTATION_VECTOR + ZUPT bias update |
| Stride variation | ±5–10% per step | Weinberg adaptive stride |
| Accumulated heading error (100 min) | ~100–200° raw | Reduces to ~30–60° with ZUPT |
| End-point position error | — | — |
| Without any corrections | ~1 000–2 000 m | — |
| With ZUPT + Weinberg | ~150–400 m | Depends on stop frequency |

**Hard limits** — cannot be fixed without map-matching or beacons:
- Drift is monotonically increasing — no absolute reference underground
- Magnetic anomalies (steel reinforcement, power cables, ore deposits) corrupt fallback magnetometer heading
- Stairs, stooping, and sideways movement break the stride model
- Temperature rise during walk shifts gyro bias faster than ZUPT can track

**Realistic expectation**: 2–4% of distance as accumulated error. For 10 km: 200–400 m endpoint error with regular stops; 500–1 000 m if walking continuously.

---

## Installation

### Prerequisites
- Android Studio (Hedgehog or later)
- JDK 17+
- Android SDK API 26+ (min), API 35 (target)

### Prerequisites
- Android Studio (Arctic Fox or later recommended)
- Java JDK 11 or later
- Android SDK with API 22+ (minimum), API 34 (target)

### Build Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/SaturnXIII/Dead-Reckoning-Pro
   cd Dead-Reckoning-Pro
   ```

2. **Open in Android Studio**
   - File → Open → Select the project folder
   - Wait for Gradle sync to complete

3. **Configure SDK**
   - Go to File → Project Structure → SDK Location
   - Ensure Android SDK is configured

4. **Build Debug APK**
   - Build → Build Bundle(s) / APK(s) → Build APK(s)
   - Or press `Ctrl+F9` (Windows/Linux) / `Cmd+F9` (Mac)

5. **Install on Device**
   - Enable USB debugging on your Android device
   - Connect device and run from Android Studio
   - Or manually install:
     ```bash
     adb install app/build/outputs/apk/debug/app-debug.apk
     ```

### Permissions Required

The app requests these permissions:
- `ACCESS_FINE_LOCATION` — GPS tracking
- `ACCESS_COARSE_LOCATION` — Network-based location
- `ACCESS_BACKGROUND_LOCATION` — Background location
- `FOREGROUND_SERVICE` — Background tracking
- `FOREGROUND_SERVICE_LOCATION` — Location in background
- `WAKE_LOCK` — Prevent device sleep
- `HIGH_SAMPLING_RATE_SENSORS` — High-frequency sensor data
- `ACTIVITY_RECOGNITION` — Step counting
- `POST_NOTIFICATIONS` — Android 13+ notification permission
- `INTERNET` — Map tiles download
- `ACCESS_NETWORK_STATE` — Network status check

---

## Usage Guide

### Starting a Trip

1. Open the app and grant all requested permissions
2. Wait for GPS to lock (status shows "GPS: OK")
3. Tap the **Start** button to begin tracking
4. Walk while the app records your path

### Adding Markers

1. Tap the **+** button (orange) to enter marker mode
2. Tap on the map where you want to place the marker
3. Select an emoji icon from the list
4. Optionally enter a label for the marker
5. Tap **Confirm** to save

### Rotating the Map

1. Place **two fingers** on the map
2. Rotate in a circular motion
3. The map will rotate 360°
4. Double-tap to reset to North-up

### Using Manual Mode

1. Tap **Mode: Auto** to switch to Manual mode
2. Use arrow buttons to turn left/right (90°) or U-turn
3. Or tap the **compass icon** to open the 360° dial
4. Drag the dial to select your exact heading, then confirm

### When GPS is Unavailable (Underground / Indoor)

1. Tap **No GPS** button to switch to dead reckoning mode
2. Keep phone in hand or chest pocket (consistent orientation)
3. The app continues tracking using steps + heading (GAME_ROTATION_VECTOR if available)
4. Use manual controls to indicate turns if auto-heading is unreliable
5. When GPS returns, tap **No GPS** again to re-enable GPS tracking and recalibrate

### Viewing Past Trips

1. Navigate to **History** from the main menu
2. Tap a trip to view details
3. Tap **View on Map** to see the trip path
4. Use export options to save as GPX or CSV

### Exporting Data

From the History screen:
- **Export CSV (choose folder)** — Select custom save location
- **Export GPX (choose folder)** — Select custom save location
- **Export to Downloads** — Save directly to Downloads folder

### Clearing Data

1. Tap the **trash icon** (red) in the bottom right
2. Choose an option:
   - **Tracks only** — Clear current path
   - **Markers only** — Delete all saved markers
   - **Everything** — Clear both tracks and markers
3. Confirm your choice

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Steps not counted | Wrong sensor mode or thresholds | Ensure `DYNAMIC` mode; check `HIGH_SAMPLING_RATE_SENSORS` permission |
| Heading spins rapidly | No gyro data (fixed in v2.1) | Update to v2.1; check Android grants sensor access |
| Heading drifts underground | Magnetic interference | GAME_ROTATION_VECTOR active automatically if sensor available |
| Position jumps on GPS return | Large accumulated error | Use **Reset calibration** before going underground |
| App killed in background | Battery optimisation | Disable battery optimisation for the app in Android settings |

---

## Sensors Used

| Sensor | Purpose | Priority |
|---|---|---|
| `TYPE_GAME_ROTATION_VECTOR` | Heading (hardware-fused, no mag) | Primary |
| `TYPE_GRAVITY` | Device tilt for fallback heading | Fallback |
| `TYPE_MAGNETIC_FIELD` | Fallback compass | Fallback |
| `TYPE_GYROSCOPE` | Heading propagation + ZUPT bias | Both modes |
| `TYPE_LINEAR_ACCELERATION` | Step detection + ZUPT | Both modes |
| `FusedLocationProvider` | GPS anchor & calibration | Above ground |

---

## Technical Details

- **Map**: OSMDroid (OpenStreetMap tiles)
- **Location**: Google Play Services `FusedLocationProvider`
- **Matrix math**: EJML (Efficient Java Matrix Library) — used for magnetometer soft-iron calibration
- **Heading fusion**: `TYPE_GAME_ROTATION_VECTOR` primary; Mahony-style complementary filter fallback
- **Stride model**: Weinberg (Weinberg 2002): `stride = K × (a_max − a_min)^0.25`
- **Drift control**: ZUPT + online EMA gyro bias
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)

---

## Project Structure

See [script_structure.md](script_structure.md) for detailed class and file documentation.

---

## Credits

**Original project**: [nisargnp/DeadReckoning](https://github.com/nisargnp/DeadReckoning)

**Fork**: [SaturnXIII/Dead-Reckoning-Pro](https://github.com/SaturnXIII/Dead-Reckoning-Pro)

**Libraries**: OSMDroid · Google Play Services Location · Material Components · EJML · AChartEngine

**License**: Open Source — contributions welcome.

---

> **Note**: Dead reckoning provides estimates. Errors accumulate without GPS. Use GPS as primary source when available. See performance table above for underground error budgets.
