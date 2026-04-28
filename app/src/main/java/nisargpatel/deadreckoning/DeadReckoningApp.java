package nisargpatel.deadreckoning;

import android.app.Application;
import android.content.Context;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.io.File;

/**
 * Application subclass. Initialises osmdroid tile configuration once at process start.
 * Tile cache is capped at 100 MB (trim at 80 MB) with a 7-day expiry override.
 */
public class DeadReckoningApp extends Application {

    private static DeadReckoningApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        initOSMDroid();
    }

    /** Configures osmdroid: user-agent, tile cache path/limits, expiry, and animation speed. */
    private void initOSMDroid() {
        IConfigurationProvider config = Configuration.getInstance();

        config.setUserAgentValue(getPackageName());

        File basePath = new File(getCacheDir(), "osmdroid");
        config.setOsmdroidBasePath(basePath);

        File tileCache = new File(basePath, "tiles");
        config.setOsmdroidTileCache(tileCache);

        config.setTileFileSystemCacheMaxBytes(100L * 1024 * 1024);
        config.setTileFileSystemCacheTrimBytes(80L * 1024 * 1024);

        config.setExpirationOverrideDuration(7 * 24 * 60 * 60 * 1000L);

        config.setAnimationSpeedDefault(500);
        config.setAnimationSpeedShort(250);

        config.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
    }

    /** @return Application context accessible from any class without an Activity reference. */
    public static Context getAppContext() {
        return instance.getApplicationContext();
    }

    /** @return A new {@link CacheManager} bound to the given MapView's tile provider. */
    public static CacheManager getCacheManager(MapView mapView) {
        return new CacheManager(mapView);
    }

    /**
     * Asynchronously caches tiles around {@code center} at zoom levels [{@code zoomLevel−2}, {@code zoomLevel+1}].
     *
     * @param center      Centre of the region to cache (WGS-84).
     * @param zoomLevel   Target zoom level (clipped to [1, 19]).
     * @param radiusTiles Approximate bounding-box half-width in tile units (used as 0.001°/tile margin).
     */
    public static void preloadMapRegion(MapView mapView, GeoPoint center, int zoomLevel, int radiusTiles) {
        CacheManager cacheManager = getCacheManager(mapView);

        int minZoom = Math.max(1, zoomLevel - 2);
        int maxZoom = Math.min(19, zoomLevel + 1);

        // Simple bounding box creation
        double latMargin = radiusTiles * 0.001; 
        double lonMargin = radiusTiles * 0.001;
        
        org.osmdroid.util.BoundingBox boundingBox = new org.osmdroid.util.BoundingBox(
                center.getLatitude() + latMargin,
                center.getLongitude() + lonMargin,
                center.getLatitude() - latMargin,
                center.getLongitude() - lonMargin
        );

        cacheManager.downloadAreaAsync(instance, boundingBox, minZoom, maxZoom);
    }
}
