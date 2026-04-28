package nisargpatel.deadreckoning.dialog;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import nisargpatel.deadreckoning.R;

/** Generic one-button info dialog; set the body text with {@link #setDialogMessage(String)}. */
public class StepInfoDialogFragment extends DialogFragment{

    private String message;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        dialogBuilder
                .setMessage(message)
                .setNeutralButton(R.string.okay,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                            }
                        });
        return dialogBuilder.create();
    }

    /** @param message Body text to display; must be set before {@link #show}. */
    public void setDialogMessage(String message) {
        this.message = message;
    }

}
