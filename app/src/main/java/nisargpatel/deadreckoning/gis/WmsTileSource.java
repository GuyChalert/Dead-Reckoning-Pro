package nisargpatel.deadreckoning.gis;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

import java.util.Locale;

/**
 * Osmdroid tile source that issues WMS 1.1.1 GetMap requests.
 * Computes the Web-Mercator bounding box for each tile and requests 256×256 images.
 */
public class WmsTileSource extends OnlineTileSourceBase {

    /** Half-width of the Web Mercator projection in metres. */
    private static final double MERCATOR_LIMIT = 20037508.342789244;

    private final String baseUrl;
    private final String layerName;
    private final String format;

    /**
     * @param id        Unique tile-source identifier for osmdroid's cache key.
     * @param baseUrl   WMS service endpoint URL.
     * @param layerName Layer name for the {@code LAYERS} parameter.
     * @param format    MIME tile format (e.g. {@code "image/png"}).
     */
    public WmsTileSource(String id, String baseUrl, String layerName, String format) {
        super(id, 0, 19, 256, ".png", new String[]{baseUrl});
        this.baseUrl    = baseUrl;
        this.layerName  = layerName;
        this.format     = format;
    }

    /**
     * Builds a WMS 1.1.1 GetMap URL for a tile, computing the Web-Mercator
     * bounding box (EPSG:3857) from the tile's zoom/x/y coordinates.
     *
     * @param pMapTileIndex Packed osmdroid tile index (zoom/x/y).
     * @return Complete GetMap URL for the 256×256 px tile.
     */
    @Override
    public String getTileURLString(long pMapTileIndex) {
        int z = MapTileIndex.getZoom(pMapTileIndex);
        int x = MapTileIndex.getX(pMapTileIndex);
        int y = MapTileIndex.getY(pMapTileIndex);

        double n    = Math.pow(2, z);
        double minX = (x       / n) * 2 * MERCATOR_LIMIT - MERCATOR_LIMIT;
        double maxX = ((x + 1) / n) * 2 * MERCATOR_LIMIT - MERCATOR_LIMIT;
        double maxY = MERCATOR_LIMIT - (y       / n) * 2 * MERCATOR_LIMIT;
        double minY = MERCATOR_LIMIT - ((y + 1) / n) * 2 * MERCATOR_LIMIT;

        String bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", minX, minY, maxX, maxY);

        return baseUrl
            + "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
            + "&LAYERS="     + layerName
            + "&STYLES="
            + "&FORMAT="     + format.replace("/", "%2F")
            + "&SRS=EPSG:3857"
            + "&BBOX="       + bbox
            + "&WIDTH=256&HEIGHT=256&TRANSPARENT=TRUE";
    }
}
