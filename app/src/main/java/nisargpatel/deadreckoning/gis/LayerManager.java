package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.graphics.Canvas;

import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.TilesOverlay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

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

    private static class LayerEntry {
        final MapLayer layer;
        final AlphaTilesOverlay overlay;
        LayerEntry(MapLayer layer, AlphaTilesOverlay overlay) {
            this.layer   = layer;
            this.overlay = overlay;
        }
    }

    public LayerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Must be called after the MapView overlays are populated (i.e. after initMap). */
    public void attach(MapView mapView) {
        this.mapView = mapView;
        // Re-apply any layers that were added before attach
        for (LayerEntry e : entries.values()) {
            insertOverlay(e.overlay);
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
        LayerEntry e = entries.remove(id);
        if (e == null || mapView == null) return;
        mapView.getOverlays().remove(e.overlay);
        mapView.invalidate();
    }

    public void setVisible(String id, boolean visible) {
        LayerEntry e = entries.get(id);
        if (e == null) return;
        e.layer.setVisible(visible);
        e.overlay.setEnabled(visible);
        if (mapView != null) mapView.invalidate();
    }

    public void setAlpha(String id, float alpha) {
        LayerEntry e = entries.get(id);
        if (e == null) return;
        e.layer.setAlpha(alpha);
        e.overlay.setAlpha(alpha);
        if (mapView != null) mapView.invalidate();
    }

    public List<MapLayer> getLayers() {
        List<MapLayer> out = new ArrayList<>();
        for (LayerEntry e : entries.values()) out.add(e.layer);
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
            "ign_topo", "IGN SCAN25 Topo",
            LayerType.WMTS,
            "https://data.geopf.fr/wmts",
            "GEOGRAPHICALGRIDSYSTEMS.MAPS", "normal", "image/png", "PM"));
        presets.add(new MapLayer(
            "brgm_geo", "BRGM Géologie 1:50k",
            LayerType.WMS,
            "https://geoservices.brgm.fr/geologie",
            "BDCharm50", "", "image/png", ""));
        return presets;
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

        AlphaTilesOverlay(MapTileProviderBasic provider, Context ctx) {
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
