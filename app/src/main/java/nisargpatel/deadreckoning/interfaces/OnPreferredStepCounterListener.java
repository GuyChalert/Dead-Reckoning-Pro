package nisargpatel.deadreckoning.interfaces;

/**
 * Callback fired when the user selects a preferred step-counter algorithm
 * in the calibration or settings UI.
 */
public interface OnPreferredStepCounterListener {
    /**
     * @param preferredStepCounterIndex Zero-based index identifying the chosen
     *                                  step-counter algorithm (see
     *                                  {@code StepCounterPreferences} for mapping).
     */
    void onPreferredStepCounter(int preferredStepCounterIndex);
}
