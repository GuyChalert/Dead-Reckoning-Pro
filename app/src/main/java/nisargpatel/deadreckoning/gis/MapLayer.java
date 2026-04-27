package nisargpatel.deadreckoning.gis;

/** Describes one map overlay layer (WMTS or WMS). */
public class MapLayer {
    private final String id;
    private String name;
    private final LayerType type;
    private final String endpointUrl;
    private final String layerName;
    private final String style;
    private final String format;
    private final String matrixSet; // WMTS: "PM"; WMS: ""
    private float alpha   = 1.0f;
    private boolean visible = true;

    public MapLayer(String id, String name, LayerType type, String endpointUrl,
                    String layerName, String style, String format, String matrixSet) {
        this.id          = id;
        this.name        = name;
        this.type        = type;
        this.endpointUrl = endpointUrl;
        this.layerName   = layerName;
        this.style       = style;
        this.format      = format;
        this.matrixSet   = matrixSet;
    }

    public String getId()          { return id; }
    public String getName()        { return name; }
    public LayerType getType()     { return type; }
    public String getEndpointUrl() { return endpointUrl; }
    public String getLayerName()   { return layerName; }
    public String getStyle()       { return style; }
    public String getFormat()      { return format; }
    public String getMatrixSet()   { return matrixSet; }
    public float  getAlpha()       { return alpha; }
    public boolean isVisible()     { return visible; }

    public void setAlpha(float alpha)      { this.alpha   = Math.max(0f, Math.min(1f, alpha)); }
    public void setVisible(boolean visible){ this.visible = visible; }
}
