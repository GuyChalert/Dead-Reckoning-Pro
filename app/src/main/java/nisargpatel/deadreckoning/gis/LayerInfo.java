package nisargpatel.deadreckoning.gis;

import java.util.List;

/** DTO returned by CapabilitiesParser — one entry per available layer. */
public class LayerInfo {
    public final String name;     // layer identifier used in requests
    public final String title;    // human-readable title
    public final LayerType type;
    public final List<String> formats;
    public final String defaultStyle;
    public final String matrixSet; // WMTS only (e.g. "PM"); empty for WMS

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
