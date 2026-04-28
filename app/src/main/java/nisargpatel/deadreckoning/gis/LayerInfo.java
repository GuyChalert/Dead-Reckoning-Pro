package nisargpatel.deadreckoning.gis;

import java.util.List;

/**
 * Immutable DTO returned by {@link CapabilitiesParser} — one entry per
 * available layer advertised in a WMS or WMTS GetCapabilities response.
 */
public class LayerInfo {
    /** Layer identifier used in tile/map requests (e.g. {@code "GEOGRAPHICALGRIDSYSTEMS.MAPS"}). */
    public final String name;
    /** Human-readable layer title shown in the layer picker UI. */
    public final String title;
    /** Service protocol (WMTS or WMS). */
    public final LayerType type;
    /** Supported MIME tile formats (e.g. {@code "image/png"}, {@code "image/jpeg"}). */
    public final List<String> formats;
    /** Default style name to use in requests (may be empty string). */
    public final String defaultStyle;
    /** WMTS tile-matrix set identifier (e.g. {@code "PM"} for pseudo-Mercator); empty for WMS. */
    public final String matrixSet;

    /**
     * @param name          Layer identifier for requests.
     * @param title         Human-readable title.
     * @param type          Service protocol ({@link LayerType#WMTS} or {@link LayerType#WMS}).
     * @param formats       List of supported MIME tile formats.
     * @param defaultStyle  Default style name; may be empty.
     * @param matrixSet     WMTS tile-matrix set; empty for WMS.
     */
    public LayerInfo(String name, String title, LayerType type,
                     List<String> formats, String defaultStyle, String matrixSet) {
        this.name = name;
        this.title = title;
        this.type = type;
        this.formats = formats;
        this.defaultStyle = defaultStyle;
        this.matrixSet = matrixSet;
    }

    @Override
    public String toString() {
        return title.isEmpty() ? name : title;
    }
}
