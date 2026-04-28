package nisargpatel.deadreckoning.preferences;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the user's turn-detection mode (AUTO = gyroscope-based, MANUAL = button press)
 * and a manual-turn event counter to SharedPreferences.
 */
public class TurnModePreferences {

    private static final String PREFS_NAME = "TurnModePrefs";
    private static final String KEY_TURN_MODE = "turn_mode";
    private static final String KEY_MANUAL_TURN_COUNT = "manual_turn_count";

    public enum TurnMode {
        AUTO("auto"),
        MANUAL("manual");

        private final String value;

        TurnMode(String value) {
            this.value = value;
        }

        /** @return Serialised string key stored in SharedPreferences. */
        public String getValue() {
            return value;
        }

        /**
         * @param value Stored string key.
         * @return Matching {@link TurnMode}, or {@link #AUTO} if unrecognised.
         */
        public static TurnMode fromValue(String value) {
            for (TurnMode mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            return AUTO;
        }
    }

    private final SharedPreferences prefs;

    public TurnModePreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** @return Currently persisted {@link TurnMode}; defaults to {@link TurnMode#AUTO} if unset. */
    public TurnMode getTurnMode() {
        String value = prefs.getString(KEY_TURN_MODE, TurnMode.AUTO.getValue());
        return TurnMode.fromValue(value);
    }

    /** @param mode New turn mode to persist. */
    public void setTurnMode(TurnMode mode) {
        prefs.edit().putString(KEY_TURN_MODE, mode.getValue()).apply();
    }

    /** Toggles between {@link TurnMode#AUTO} and {@link TurnMode#MANUAL} and persists the result. */
    public void toggleTurnMode() {
        TurnMode current = getTurnMode();
        TurnMode newMode = (current == TurnMode.AUTO) ? TurnMode.MANUAL : TurnMode.AUTO;
        setTurnMode(newMode);
    }

    /** @return Total manual-turn events recorded since the last reset. */
    public int getManualTurnCount() {
        return prefs.getInt(KEY_MANUAL_TURN_COUNT, 0);
    }

    /** Increments the manual turn event counter by one and persists it. */
    public void incrementManualTurnCount() {
        int count = getManualTurnCount();
        prefs.edit().putInt(KEY_MANUAL_TURN_COUNT, count + 1).apply();
    }

    /** Resets the manual turn event counter to zero. */
    public void resetManualTurnCount() {
        prefs.edit().putInt(KEY_MANUAL_TURN_COUNT, 0).apply();
    }
}
