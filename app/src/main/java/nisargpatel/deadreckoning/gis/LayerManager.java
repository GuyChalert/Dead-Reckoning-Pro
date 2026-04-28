package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.net.Uri;

import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;

import org.osmdroid.tileprovider.IRegisterReceiver;
import org.osmdroid.tileprovider.MapTileProviderArray;
import org.osmdroid.tileprovider.MapTileProviderBase;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider;
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.TilesOverlay;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the ordered set of WMS/WMTS overlay layers on the osmdroid MapView.
 *
 * Tile overlays are inserted at index 0 so they sit above the base OSM layer
 * but below path, marker, and gesture overlays that are added after init.
 *
 * Pre-configured IGN (data.geopf.fr) and BRGM (geoservices.brgm.fr) layers
 * are available via {@link #getPresets()}.
 */
public class LayerManager {

    // ------------------------------------------------------------------ model

    private final Context context;
    private MapView mapView;

    // Ordered map: layer id → (model, overlay)
    private final LinkedHashMap<String, LayerEntry> entries = new LinkedHashMap<>();
    // KML vector/raster layers tracked separately (no tile overlay)
    private final LinkedHashMap<String, KmlLayerEntry> kmlEntries = new LinkedHashMap<>();
    // Pending dynamic NetworkLinks (onRegion/onStop) across all loaded KML/KMZ files
    private final List<KmlOverlay.NetworkLinkDesc> kmlPendingLinks = new ArrayList<>();
    private final Set<String> kmlFetchedHrefs = new HashSet<>();
    private final ScheduledExecutorService kmlRefreshPool = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> kmlRefreshTask;
    // OSM base layer (built-in MapView base overlay, wrapped for alpha/visibility)
    private AlphaTilesOverlay osmBaseOverlay;
    private final MapLayer osmBaseLayer = new MapLayer(
            "osm_base", "OSM (fond de carte)", LayerType.WMS, "", "", "", "", "");

    private static class LayerEntry {
        final MapLayer layer;
        final AlphaTilesOverlay overlay;
        LayerEntry(MapLayer layer, AlphaTilesOverlay overlay) {
            this.layer   = layer;
            this.overlay = overlay;
        }
    }

    private static class KmlLayerEntry {
        final MapLayer layer;
        final List<org.osmdroid.views.overlay.Overlay> overlays;
        KmlLayerEntry(MapLayer layer, List<org.osmdroid.views.overlay.Overlay> overlays) {
            this.layer   = layer;
            this.overlays = overlays;
        }
    }

    public LayerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Must be called after the MapView overlays are populated (i.e. after initMap). */
    public void attach(MapView mapView) {
        this.mapView = mapView;
        // Wrap the built-in OSM base overlay with our alpha-capable version
        osmBaseOverlay = new AlphaTilesOverlay(mapView.getTileProvider(), context);
        osmBaseOverlay.setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT);
        osmBaseOverlay.setEnabled(osmBaseLayer.isVisible());
        osmBaseOverlay.setAlpha(osmBaseLayer.getAlpha());
        mapView.getOverlayManager().setTilesOverlay(osmBaseOverlay);
        // Re-apply any layers that were added before attach
        for (LayerEntry e : entries.values()) {
            insertOverlay(e.overlay);
        }
        // Register viewport listener to refresh dynamic KML NetworkLinks on scroll/zoom
        mapView.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent event) { scheduleKmlRefresh(); return false; }
            @Override public boolean onZoom(ZoomEvent event)     { scheduleKmlRefresh(); return false; }
        });
    }

    /** Schedule a deferred KML dynamic-link refresh (debounced 1.5 s after last viewport change). */
    private void scheduleKmlRefresh() {
        if (kmlPendingLinks.isEmpty()) return;
        if (kmlRefreshTask != null) kmlRefreshTask.cancel(false);
        kmlRefreshTask = kmlRefreshPool.schedule(this::doKmlRefresh, 1500, TimeUnit.MILLISECONDS);
    }

    private void doKmlRefresh() {
        if (mapView == null || kmlPendingLinks.isEmpty()) return;
        List<org.osmdroid.views.overlay.Overlay> newOverlays =
                KmlOverlay.fetchPendingLinks(context, mapView, kmlPendingLinks, kmlFetchedHrefs);
        if (!newOverlays.isEmpty()) {
            mapView.post(() -> {
                mapView.getOverlays().addAll(newOverlays);
                mapView.invalidate();
            });
        }
    }

    // ------------------------------------------------------------------ CRUD

    public void addLayer(MapLayer layer) {
        if (entries.containsKey(layer.getId())) return;

        OnlineTileSourceBase tileSource = buildTileSource(layer);
        MapTileProviderBasic provider = new MapTileProviderBasic(context);
        provider.setTileSource(tileSource);
        AlphaTilesOverlay overlay = new AlphaTilesOverlay(provider, context);
        overlay.setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT);
        overlay.setEnabled(layer.isVisible());
        overlay.setAlpha(layer.getAlpha());

        entries.put(layer.getId(), new LayerEntry(layer, overlay));

        if (mapView != null) {
            insertOverlay(overlay);
            mapView.invalidate();
        }
    }

    public void removeLayer(String id) {
        if ("osm_base".equals(id)) return; // base layer cannot be removed
        LayerEntry e = entries.remove(id);
        if (e != null) {
            if (mapView != null) {
                mapView.getOverlays().remove(e.overlay);
                mapView.invalidate();
            }
            return;
        }
        KmlLayerEntry ke = kmlEntries.remove(id);
        if (ke != null && mapView != null) {
            mapView.getOverlays().removeAll(ke.overlays);
            mapView.invalidate();
        }
    }

    public void setVisible(String id, boolean visible) {
        if ("osm_base".equals(id)) {
            osmBaseLayer.setVisible(visible);
            if (osmBaseOverlay != null) osmBaseOverlay.setEnabled(visible);
            if (mapView != null) mapView.invalidate();
            return;
        }
        LayerEntry e = entries.get(id);
        if (e != null) {
            e.layer.setVisible(visible);
            e.overlay.setEnabled(visible);
            if (mapView != null) mapView.invalidate();
            return;
        }
        KmlLayerEntry ke = kmlEntries.get(id);
        if (ke != null) {
            ke.layer.setVisible(visible);
            for (org.osmdroid.views.overlay.Overlay o : ke.overlays) o.setEnabled(visible);
            if (mapView != null) mapView.invalidate();
        }
    }

    public void setAlpha(String id, float alpha) {
        if ("osm_base".equals(id)) {
            osmBaseLayer.setAlpha(alpha);
            if (osmBaseOverlay != null) osmBaseOverlay.setAlpha(alpha);
            if (mapView != null) mapView.invalidate();
            return;
        }
        LayerEntry e = entries.get(id);
        if (e == null) return;
        e.layer.setAlpha(alpha);
        e.overlay.setAlpha(alpha);
        if (mapView != null) mapView.invalidate();
    }

    public List<MapLayer> getLayers() {
        List<MapLayer> out = new ArrayList<>();
        out.add(osmBaseLayer); // base layer always first
        for (LayerEntry e : entries.values()) out.add(e.layer);
        for (KmlLayerEntry ke : kmlEntries.values()) out.add(ke.layer);
        return out;
    }

    // ------------------------------------------------------------------ presets

    /** Returns the pre-configured IGN and BRGM layers (not yet added to map). */
    public static List<MapLayer> getPresets() {
        List<MapLayer> presets = new ArrayList<>();
        presets.add(new MapLayer(
            "ign_ortho", "IGN Orthophoto",
            LayerType.WMTS,
            "https://data.geopf.fr/wmts",
            "ORTHOIMAGERY.ORTHOPHOTOS", "normal", "image/jpeg", "PM"));
        presets.add(new MapLayer(
            "ign_topo", "IGN Plan Topo",
            LayerType.WMTS,
            "https://data.geopf.fr/wmts",
            "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", "normal", "image/png", "PM"));
        presets.add(new MapLayer(
            "brgm_geo", "BRGM Géologie 1:50k",
            LayerType.WMS,
            "https://geoservices.brgm.fr/geologie",
            "SCAN_H_GEOL50", "", "image/png", ""));
        presets.add(new MapLayer(
            "ign_routes", "IGN Routes",
            LayerType.WMTS,
            "https://data.geopf.fr/wmts",
            "TRANSPORTNETWORKS.ROADS", "normal", "image/png", "PM"));
        return presets;
    }

    // ------------------------------------------------------------------ MBTiles / GeoTIFF import

    private static final AtomicInteger importCounter = new AtomicInteger();

    /**
     * Import an MBTiles file (ZIP-of-SQLite) from a SAF URI.
     * Must be called off the UI thread (copies the file).
     * Posts the overlay addition to the main thread.
     *
     * @throws IOException if copy or DB open fails
     */
    public void importMBTiles(Uri uri, String displayName) throws IOException {
        int idx = importCounter.incrementAndGet();
        File cached = MBTilesArchive.copyToCache(context, uri,
                "mbtiles_" + idx + ".mbtiles");
        MBTilesArchive archive = new MBTilesArchive(cached);
        String id = "mbtiles_" + idx;

        XYTileSource tileSource = new XYTileSource(
                displayName, 0, 22, 256, ".png", new String[]{});

        IRegisterReceiver noOp = new IRegisterReceiver() {
            @Override public android.content.Intent registerReceiver(
                    android.content.BroadcastReceiver r, IntentFilter f) { return null; }
            @Override public void unregisterReceiver(android.content.BroadcastReceiver r) {}
            @Override public void destroy() {}
        };

        MapTileFileArchiveProvider archiveProvider = new MapTileFileArchiveProvider(
                noOp, tileSource, new IArchiveFile[]{archive});
        MapTileProviderArray provider = new MapTileProviderArray(tileSource, noOp,
                new MapTileModuleProviderBase[]{archiveProvider});
        AlphaTilesOverlay overlay = new AlphaTilesOverlay(provider, context);
        overlay.setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT);

        MapLayer layer = new MapLayer(id, displayName, LayerType.WMS,
                "", "", "", "image/png", "");
        entries.put(id, new LayerEntry(layer, overlay));

        if (mapView != null) {
            mapView.post(() -> {
                insertOverlay(overlay);
                mapView.invalidate();
            });
        }
    }

    /**
     * Import a GeoTIFF from a SAF URI and add as a RasterOverlay.
     * Must be called off the UI thread (reads and decodes the raster).
     * Posts the overlay addition to the main thread.
     *
     * @throws IOException if read or decode fails
     */
    public void importGeoTiff(Uri uri) throws IOException {
        RasterOverlay raster = GeoTiffImporter.load(context, uri);
        if (mapView != null) {
            mapView.post(() -> {
                mapView.getOverlays().add(0, raster);
                mapView.invalidate();
            });
        }
    }

    /**
     * Import a KML or KMZ file from a SAF URI and add its geometries as overlays.
     * Must be called off the UI thread.
     *
     * @throws Exception if parse or IO fails
     */
    /** Returns number of overlays added (0 = file parsed but had no displayable content). */
    public int importKml(Uri uri, String displayName) throws Exception {
        if (mapView == null) return 0;
        KmlOverlay.LoadResult result = KmlOverlay.loadFull(context, uri, mapView);
        List<org.osmdroid.views.overlay.Overlay> overlays = result.overlays;
        if (!result.pendingLinks.isEmpty()) {
            synchronized (kmlPendingLinks) { kmlPendingLinks.addAll(result.pendingLinks); }
        }
        int idx = importCounter.incrementAndGet();
        String id = "kml_" + idx;
        MapLayer layer = new MapLayer(id, displayName, LayerType.KML, "", "", "", "", "");
        kmlEntries.put(id, new KmlLayerEntry(layer, overlays));
        mapView.post(() -> {
            mapView.getOverlays().addAll(overlays);
            mapView.invalidate();
        });
        return overlays.size();
    }

    /**
     * Import a Shapefile (ZIP or raw .shp) from a SAF URI and add its geometries as overlays.
     * Must be called off the UI thread.
     *
     * @throws IOException if parse or IO fails
     */
    public void importShapefile(Uri uri, String displayName) throws IOException {
        if (mapView == null) return;
        List<org.osmdroid.views.overlay.Overlay> overlays =
                ShapefileOverlay.load(context, uri, mapView);
        int idx = importCounter.incrementAndGet();
        String id = "shp_" + idx;
        MapLayer layer = new MapLayer(id, displayName, LayerType.KML, "", "", "", "", "");
        kmlEntries.put(id, new KmlLayerEntry(layer, overlays));
        mapView.post(() -> {
            mapView.getOverlays().addAll(overlays);
            mapView.invalidate();
        });
    }

    // ------------------------------------------------------------------ offline download helpers

    /**
     * Returns the OnlineTileSourceBase for an active layer (for use by the tile downloader).
     * Returns null if the layer id is not found or is not an online source.
     */
    public OnlineTileSourceBase getTileSourceForLayer(String id) {
        if ("osm_base".equals(id)) {
            return (OnlineTileSourceBase) org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK;
        }
        LayerEntry e = entries.get(id);
        if (e == null) return null;
        return buildTileSource(e.layer);
    }

    public MapLayer getOsmBaseLayer() { return osmBaseLayer; }

    /**
     * Load a pre-downloaded MBTiles file directly (no SAF copy needed).
     * Must be called off the UI thread.
     * Posts the overlay addition to the main thread.
     */
    public void loadMBTilesFromFile(File file, String displayName) throws IOException {
        int idx = importCounter.incrementAndGet();
        MBTilesArchive archive = new MBTilesArchive(file);
        String id = "mbtiles_dl_" + idx;

        XYTileSource tileSource = new XYTileSource(
                displayName, 0, 22, 256, ".png", new String[]{});

        IRegisterReceiver noOp = new IRegisterReceiver() {
            @Override public android.content.Intent registerReceiver(
                    android.content.BroadcastReceiver r, IntentFilter f) { return null; }
            @Override public void unregisterReceiver(android.content.BroadcastReceiver r) {}
            @Override public void destroy() {}
        };

        MapTileFileArchiveProvider archiveProvider = new MapTileFileArchiveProvider(
                noOp, tileSource, new IArchiveFile[]{archive});
        MapTileProviderArray provider = new MapTileProviderArray(tileSource, noOp,
                new MapTileModuleProviderBase[]{archiveProvider});
        AlphaTilesOverlay overlay = new AlphaTilesOverlay(provider, context);
        overlay.setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT);

        MapLayer layer = new MapLayer(id, displayName, LayerType.WMS,
                "", "", "", "image/png", "");
        entries.put(id, new LayerEntry(layer, overlay));

        if (mapView != null) {
            mapView.post(() -> {
                insertOverlay(overlay);
                mapView.invalidate();
            });
        }
    }

    // ------------------------------------------------------------------ internal

    private OnlineTileSourceBase buildTileSource(MapLayer layer) {
        if (layer.getType() == LayerType.WMTS) {
            return new WmtsTileSource(
                layer.getId(),
                layer.getEndpointUrl(),
                layer.getLayerName(),
                layer.getStyle(),
                layer.getFormat(),
                layer.getMatrixSet());
        } else {
            return new WmsTileSource(
                layer.getId(),
                layer.getEndpointUrl(),
                layer.getLayerName(),
                layer.getFormat());
        }
    }

    /**
     * Insert overlay at position 0 so it sits above the base OSM tile layer
     * but below all path and marker overlays already in the list.
     */
    private void insertOverlay(AlphaTilesOverlay overlay) {
        mapView.getOverlays().add(0, overlay);
    }

    // ------------------------------------------------------------------ alpha overlay

    static class AlphaTilesOverlay extends TilesOverlay {
        private float alpha = 1.0f;

        AlphaTilesOverlay(MapTileProviderBase provider, Context ctx) {
            super(provider, ctx);
        }

        void setAlpha(float alpha) {
            this.alpha = Math.max(0f, Math.min(1f, alpha));
        }

        @Override
        public void draw(Canvas canvas, MapView mapView, boolean shadow) {
            if (alpha >= 1.0f) {
                super.draw(canvas, mapView, shadow);
                return;
            }
            int saved = canvas.saveLayerAlpha(0, 0, canvas.getWidth(), canvas.getHeight(),
                    (int) (alpha * 255));
            super.draw(canvas, mapView, shadow);
            canvas.restoreToCount(saved);
        }
    }
}
