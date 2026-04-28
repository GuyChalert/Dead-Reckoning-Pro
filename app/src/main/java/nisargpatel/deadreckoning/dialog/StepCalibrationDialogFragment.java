package nisargpatel.deadreckoning.dialog;

import android.app.AlertDialog;
import android.app.Dialog;
import androidx.fragment.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

import nisargpatel.deadreckoning.interfaces.OnPreferredStepCounterListener;

/**
 * Simple list-picker dialog for choosing step-counter sensitivity.
 * Call {@link #setStepList(String[])} before showing; selected index is forwarded to
 * {@link OnPreferredStepCounterListener#onPreferredStepCounter(int)}.
 */
public class StepCalibrationDialogFragment extends DialogFragment {

    private OnPreferredStepCounterListener onPreferredStepCounterListener;
    private String[] stepList;

    public StepCalibrationDialogFragment() {}

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("Pick the sensitivity that best matches your step count:")
                .setItems(stepList, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (onPreferredStepCounterListener != null) {
                            onPreferredStepCounterListener.onPreferredStepCounter(which);
                        }
                    }
                });

        return builder.create();
    }

    /** @param onPreferredStepCounterListener Receives the user's selection index. */
    public void setOnPreferredStepCounterListener(OnPreferredStepCounterListener onPreferredStepCounterListener) {
        this.onPreferredStepCounterListener = onPreferredStepCounterListener;
    }

    /** @param stepList Sensitivity option labels shown in the list; must be set before {@link #show}. */
    public void setStepList(String[] stepList) {
        this.stepList = stepList;
    }

}
