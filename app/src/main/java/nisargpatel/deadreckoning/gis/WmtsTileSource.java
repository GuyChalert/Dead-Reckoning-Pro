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

    public WmtsTileSource(String id, String baseUrl, String layerName,
                          String style, String format, String matrixSet) {
        super(id, 0, 19, 256, tileExtension(format), new String[]{baseUrl});
        this.baseUrl    = baseUrl;
        this.layerName  = layerName;
        this.style      = style;
        this.format     = format;
        this.matrixSet  = matrixSet;
    }

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

    private static String tileExtension(String format) {
        if (format.contains("jpeg") || format.contains("jpg")) return ".jpg";
        return ".png";
    }
}
