package nisargpatel.deadreckoning.gis;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * Osmdroid tile source that builds WMTS GetTile KVP requests.
 * Supports any endpoint with a GoogleMapsCompatible / PM TileMatrixSet (EPSG:3857).
 */
public class WmtsTileSource extends OnlineTileSourceBase {

    private final String baseUrl;
    private final String layerName;
    private final String style;
    private final String format;
    private final String matrixSet;

    /**
     * @param id         Unique tile-source identifier (used by osmdroid's cache key).
     * @param baseUrl    WMTS service endpoint URL (GetCapabilities root).
     * @param layerName  Layer identifier for the {@code LAYER} parameter.
     * @param style      Style name for the {@code STYLE} parameter; may be empty.
     * @param format     MIME tile format (e.g. {@code "image/png"}).
     * @param matrixSet  Tile-matrix set identifier (e.g. {@code "PM"} for pseudo-Mercator).
     */
    public WmtsTileSource(String id, String baseUrl, String layerName,
                          String style, String format, String matrixSet) {
        super(id, 0, 19, 256, tileExtension(format), new String[]{baseUrl});
        this.baseUrl    = baseUrl;
        this.layerName  = layerName;
        this.style      = style;
        this.format     = format;
        this.matrixSet  = matrixSet;
    }

    /**
     * Builds the full WMTS KVP GetTile URL for a given tile index.
     *
     * @param pMapTileIndex Packed osmdroid tile index (zoom/x/y encoded by {@link MapTileIndex}).
     * @return Complete URL string for the requested tile.
     */
    @Override
    public String getTileURLString(long pMapTileIndex) {
        int z = MapTileIndex.getZoom(pMapTileIndex);
        int x = MapTileIndex.getX(pMapTileIndex);
        int y = MapTileIndex.getY(pMapTileIndex);
        return baseUrl
            + "?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
            + "&LAYER="          + layerName
            + "&STYLE="          + style
            + "&FORMAT="         + format.replace("/", "%2F")
            + "&TILEMATRIXSET="  + matrixSet
            + "&TILEMATRIX="     + z
            + "&TILEROW="        + y
            + "&TILECOL="        + x;
    }

    /**
     * Derives the local file extension used by osmdroid's tile cache from the MIME type.
     *
     * @param format MIME tile format (e.g. {@code "image/jpeg"}).
     * @return {@code ".jpg"} for JPEG formats; {@code ".png"} otherwise.
     */
    private static String tileExtension(String format) {
        if (format.contains("jpeg") || format.contains("jpg")) return ".jpg";
        return ".png";
    }
}
