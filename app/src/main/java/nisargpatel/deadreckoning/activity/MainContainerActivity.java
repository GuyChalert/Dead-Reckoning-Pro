package nisargpatel.deadreckoning.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import nisargpatel.deadreckoning.R;

/**
 * Shell activity that hosts {@link MapFragment} and {@link StepsFragment} via a bottom navigation bar.
 * A FAB toggles tracking start/stop, delegating to whichever fragment is currently visible.
 * History, Calibration, and Guide items navigate to standalone activities.
 */
public class MainContainerActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigation;
    private FloatingActionButton fabStartStop;

    private MapFragment mapFragment;
    private StepsFragment stepsFragment;
    private Fragment currentFragment;

    private boolean isTracking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_container);

        initViews();
        initFragments();
        setupBottomNavigation();
    }

    /** Binds the bottom navigation view and FAB; FAB is hidden initially (shown only on Steps tab). */
    private void initViews() {
        bottomNavigation = findViewById(R.id.bottomNavigation);
        fabStartStop = findViewById(R.id.fabStartStop);

        fabStartStop.setOnClickListener(v -> toggleTracking());
        
        fabStartStop.hide();
    }

    /** Adds MapFragment and StepsFragment to the container; map starts visible, steps hidden. */
    private void initFragments() {
        mapFragment = new MapFragment();
        stepsFragment = new StepsFragment();

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragmentContainer, stepsFragment, "steps")
                .hide(stepsFragment)
                .add(R.id.fragmentContainer, mapFragment, "map")
                .commit();

        currentFragment = mapFragment;
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            
            if (itemId == R.id.nav_map) {
                showFragment(mapFragment);
                return true;
            } else if (itemId == R.id.nav_steps) {
                showFragment(stepsFragment);
                return true;
            } else if (itemId == R.id.nav_history) {
                startActivity(new Intent(this, HistoryActivity.class));
                return false;
            } else if (itemId == R.id.nav_calibrate) {
                startActivity(new Intent(this, CalibrationActivity.class));
                return false;
            } else if (itemId == R.id.nav_guide) {
                startActivity(new Intent(this, GuideActivity.class));
                return false;
            }
            
            return false;
        });
    }

    /**
     * Switches the visible fragment with a fade transition; no-op if already current.
     * FAB is hidden on the Map tab and shown on all others.
     */
    private void showFragment(Fragment fragment) {
        if (fragment == currentFragment) return;

        getSupportFragmentManager().beginTransaction()
                .hide(currentFragment)
                .show(fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .commit();

        currentFragment = fragment;
        
        if (fragment instanceof MapFragment) {
            fabStartStop.hide();
        } else {
            fabStartStop.show();
        }
    }

    /** Toggles the tracking state and updates the FAB icon/tint; delegates to the active fragment. */
    private void toggleTracking() {
        isTracking = !isTracking;

        if (isTracking) {
            fabStartStop.setImageResource(R.drawable.ic_pause);
            fabStartStop.setBackgroundTintList(getColorStateList(R.color.colorError));
            if (currentFragment instanceof MapFragment) {
                ((MapFragment) currentFragment).startTracking();
            }
        } else {
            fabStartStop.setImageResource(R.drawable.ic_play);
            fabStartStop.setBackgroundTintList(getColorStateList(R.color.colorPrimary));
            if (currentFragment instanceof MapFragment) {
                ((MapFragment) currentFragment).stopTracking();
            }
        }
    }

    /** @return {@code true} while a tracking session is active. */
    public boolean isTracking() {
        return isTracking;
    }

    @Override
    protected void onResume() {
        super.onResume();
        bottomNavigation.setSelectedItemId(R.id.nav_map);
    }
}
