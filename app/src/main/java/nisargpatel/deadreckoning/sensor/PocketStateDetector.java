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
    private static final double POCKET_VAR_THRESH = 1.2;  // m²/s⁴
    // Phone is vertical (pocket) when pitch > this
    private static final double POCKET_PITCH_MIN_DEG = 35.0;

    private final Deque<float[]> buf = new ArrayDeque<>();
    private State state = State.HANDHELD_MAPPING;

    /**
     * Call on every TYPE_ACCELEROMETER event (not LINEAR_ACCELERATION — gravity needed).
     * Thread-safe: call from sensor thread; read state from any thread.
     */
    public synchronized void update(float[] accel) {
        buf.addLast(new float[]{accel[0], accel[1], accel[2]});
        if (buf.size() > WINDOW) buf.pollFirst();
        if (buf.size() == WINDOW) classify();
    }

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

        state = (highVar && phoneUpright) ? State.POCKET_WALKING : State.HANDHELD_MAPPING;
    }

    public synchronized State getState() { return state; }
    public synchronized boolean isPocketMode() { return state == State.POCKET_WALKING; }
}
