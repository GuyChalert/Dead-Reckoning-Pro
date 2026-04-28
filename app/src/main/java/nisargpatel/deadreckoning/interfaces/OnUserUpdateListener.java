package nisargpatel.deadreckoning.interfaces;

import android.os.Bundle;

/**
 * Callback fired when user profile data (height, step length, etc.) is
 * updated and needs to be propagated to dependent components.
 */
public interface OnUserUpdateListener {
    /**
     * @param bundle Key-value bundle containing the updated user fields.
     *               Keys are defined in {@code UserActivity}.
     */
    void onUserUpdateListener(Bundle bundle);
}
