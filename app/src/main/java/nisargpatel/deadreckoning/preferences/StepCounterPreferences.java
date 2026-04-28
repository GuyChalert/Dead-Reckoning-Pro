package nisargpatel.deadreckoning.preferences;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists the user's preferred step-counting algorithm to SharedPreferences.
 * Choices: DYNAMIC (adaptive threshold), ANDROID (hardware step detector), STATIC (fixed threshold).
 */
public class StepCounterPreferences {

    private static final String PREFS_NAME = "StepCounterPrefs";
    private static final String KEY_STEP_MODE = "step_mode";

    /** Available step-counting algorithm modes. */
    public enum StepMode {
        DYNAMIC("dynamic"),
        ANDROID("android"),
        STATIC("static");

        private final String value;

        StepMode(String value) {
            this.value = value;
        }

        /** @return Serialised string key stored in SharedPreferences. */
        public String getValue() {
            return value;
        }

        /**
         * Parses the serialised key back to an enum constant.
         *
         * @param value Stored string key.
         * @return Matching {@link StepMode}, or {@link #DYNAMIC} if unrecognised.
         */
        public static StepMode fromValue(String value) {
            for (StepMode mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            return DYNAMIC;
        }
    }

    private final SharedPreferences prefs;

    public StepCounterPreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** @return Currently persisted {@link StepMode}; defaults to {@link StepMode#DYNAMIC} if unset. */
    public StepMode getStepMode() {
        String value = prefs.getString(KEY_STEP_MODE, StepMode.DYNAMIC.getValue());
        return StepMode.fromValue(value);
    }

    /**
     * Persists the chosen step-counting mode.
     *
     * @param mode New mode to store.
     */
    public void setStepMode(StepMode mode) {
        prefs.edit().putString(KEY_STEP_MODE, mode.getValue()).apply();
    }

    /** @return {@code true} if the active mode is {@link StepMode#DYNAMIC}. */
    public boolean useDynamic() {
        return getStepMode() == StepMode.DYNAMIC;
    }

    /** @return {@code true} if the active mode is {@link StepMode#ANDROID}. */
    public boolean useAndroid() {
        return getStepMode() == StepMode.ANDROID;
    }

    /** @return {@code true} if the active mode is {@link StepMode#STATIC}. */
    public boolean useStatic() {
        return getStepMode() == StepMode.STATIC;
    }
}
