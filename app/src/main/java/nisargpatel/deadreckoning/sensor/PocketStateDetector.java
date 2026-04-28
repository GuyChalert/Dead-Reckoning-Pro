package nisargpatel.deadreckoning.sensor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Classifies phone carry state using raw TYPE_ACCELEROMETER (gravity retained).
 *
 * POCKET_WALKING  — phone vertical, high variance from gait bounce.
 *   Heading from GAME_ROTATION_VECTOR reflects leg/hip axis, not walking direction.
 *   PDR uses PCA of accel principal axis for heading; gyro bias corrected at each ZUPT.
 *
 * HANDHELD_MAPPING — phone held out, lower variance, user-controlled orientation.
 *   GAME_ROTATION_VECTOR heading is valid for tunnel wall tracking (VIO/ARCore ready).
 */
public class PocketStateDetector {

    public enum State { POCKET_WALKING, HANDHELD_MAPPING }

    // Window of ~250 ms at 200 Hz
    private static final int    WINDOW           = 50;
    // Pocket bounces produce higher total accel variance than handheld
    // Raised to 3.5 to prevent handheld-walking from triggering pocket mode
    private static final double POCKET_VAR_THRESH = 3.5;  // m²/s⁴
    // Phone is vertical (pocket) when pitch > this
    private static final double POCKET_PITCH_MIN_DEG = 55.0;
    // Require N consecutive same-state reads before switching (prevents flicker per step)
    private static final int    HYSTERESIS_COUNT = 20;
    private int hysteresisCounter = 0;

    private final Deque<float[]> buf = new ArrayDeque<>();
    private State state = State.HANDHELD_MAPPING;

    /**
     * Processes one TYPE_ACCELEROMETER event (gravity retained — do not use
     * TYPE_LINEAR_ACCELERATION). Thread-safe: call from sensor thread; read
     * state from any thread.
     *
     * @param accel Raw accelerometer reading [x, y, z] in m/s² (gravity included).
     */
    public synchronized void update(float[] accel) {
        buf.addLast(new float[]{accel[0], accel[1], accel[2]});
        if (buf.size() > WINDOW) buf.pollFirst();
        if (buf.size() == WINDOW) classify();
    }

    /**
     * Classifies the current carry state from the buffered samples.
     * Computes total variance and pitch angle over the {@link #WINDOW} sample window.
     * State changes require {@link #HYSTERESIS_COUNT} consecutive opposite-state samples
     * to prevent flickering with each gait step.
     */
    private void classify() {
        double mx = 0, my = 0, mz = 0;
        for (float[] s : buf) { mx += s[0]; my += s[1]; mz += s[2]; }
        mx /= WINDOW; my /= WINDOW; mz /= WINDOW;

        double vx = 0, vy = 0, vz = 0;
        for (float[] s : buf) {
            vx += (s[0] - mx) * (s[0] - mx);
            vy += (s[1] - my) * (s[1] - my);
            vz += (s[2] - mz) * (s[2] - mz);
        }
        double totalVar = (vx + vy + vz) / WINDOW;

        // Pitch: angle between gravity vector and horizontal plane.
        // Phone vertical (pocket) → gravity mostly along phone Y axis → high pitch.
        double gMag = Math.sqrt(mx*mx + my*my + mz*mz);
        double pitch = (gMag > 0.1)
            ? Math.toDegrees(Math.acos(Math.abs(mz) / gMag))
            : 0;

        boolean highVar    = totalVar > POCKET_VAR_THRESH;
        boolean phoneUpright = pitch > POCKET_PITCH_MIN_DEG;

        State candidate = (highVar && phoneUpright) ? State.POCKET_WALKING : State.HANDHELD_MAPPING;
        if (candidate == state) {
            hysteresisCounter = 0;
        } else if (++hysteresisCounter >= HYSTERESIS_COUNT) {
            state = candidate;
            hysteresisCounter = 0;
        }
    }

    /** @return Current carry-state classification (thread-safe). */
    public synchronized State getState() { return state; }

    /** @return {@code true} if the phone is classified as pocket walking. */
    public synchronized boolean isPocketMode() { return state == State.POCKET_WALKING; }
}
