package nisargpatel.deadreckoning.permission;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import nisargpatel.deadreckoning.R;

/**
 * Utility class for runtime permission management.
 * Provides version-aware permission arrays (Android 10 / 13+),
 * a first-launch flag, and a unified handler for permission results.
 */
public class PermissionHelper {

    public static final int PERMISSION_REQUEST_CODE = 100;
    public static final int SETTINGS_REQUEST_CODE = 101;

    private static final String PREFS_NAME = "PermissionPrefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch";

    public static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    public static final String[] ANDROID_13_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION
    };

    public static final String[] ANDROID_10_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION
    };

    /**
     * Returns {@code true} the very first time this method is called (uses SharedPreferences flag).
     * Subsequent calls always return {@code false}.
     */
    public static boolean isFirstLaunch(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean first = prefs.getBoolean(KEY_FIRST_LAUNCH, true);
        if (first) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
        }
        return first;
    }

    /** @return {@code true} if every permission in {@link #getRequiredPermissions} is granted. */
    public static boolean hasAllPermissions(Context context) {
        for (String permission : getRequiredPermissions(context)) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the version-appropriate permission array:
     * Android 13+ includes {@code POST_NOTIFICATIONS} and {@code FOREGROUND_SERVICE_LOCATION};
     * Android 10–12 omits the notification permission.
     */
    public static String[] getRequiredPermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ANDROID_13_PERMISSIONS;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ANDROID_10_PERMISSIONS;
        } else {
            return REQUIRED_PERMISSIONS;
        }
    }

    /** Requests the version-appropriate permissions with {@link #PERMISSION_REQUEST_CODE}. */
    public static void requestPermissions(Activity activity) {
        String[] permissions = getRequiredPermissions(activity);
        ActivityCompat.requestPermissions(activity, permissions, PERMISSION_REQUEST_CODE);
    }

    /** @return {@code true} if the system recommends showing a permission rationale for any required permission. */
    public static boolean shouldShowRationale(Activity activity) {
        for (String permission : getRequiredPermissions(activity)) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                return true;
            }
        }
        return false;
    }

    /** Opens the app's system settings page so the user can manually grant permissions. */
    public static void openAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", context.getPackageName(), null);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * Handles the result from {@link Activity#onRequestPermissionsResult}.
     * Shows a toast: all granted, partially granted, or fully denied.
     *
     * @param requestCode  Must be {@link #PERMISSION_REQUEST_CODE} to be handled.
     * @param grantResults Array of grant-result codes from the system.
     */
    public static void handlePermissionResult(Activity activity, int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            boolean someGranted = false;

            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    someGranted = true;
                } else {
                    allGranted = false;
                }
            }

            if (allGranted) {
                Toast.makeText(activity, R.string.location_granted, Toast.LENGTH_SHORT).show();
            } else if (someGranted) {
                Toast.makeText(activity, "Some permissions missing - limited features", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(activity, R.string.permission_denied_message, Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * @return {@code true} if writing to external storage is allowed.
     * Always {@code true} on Android 10+ (scoped storage; app-specific dirs don't need the permission).
     */
    public static boolean canWriteExternalStorage(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                == PackageManager.PERMISSION_GRANTED;
    }
}
