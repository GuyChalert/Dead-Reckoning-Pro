package nisargpatel.deadreckoning.gis;

/**
 * Describes one map overlay layer (WMTS or WMS) as used by {@link LayerManager}.
 * Carries all parameters needed to construct tile URLs, plus UI state (alpha, visibility).
 */
public class MapLayer {
    /** Unique layer ID within the session (UUID string). */
    private final String id;
    /** Human-readable display name shown in the layer list. */
    private String name;
    /** Protocol type (WMTS or WMS). */
    private final LayerType type;
    /** Base service endpoint URL (GetCapabilities root). */
    private final String endpointUrl;
    /** Layer identifier used in tile/GetMap requests. */
    private final String layerName;
    /** Style name used in requests (may be empty). */
    private final String style;
    /** MIME tile format (e.g. {@code "image/png"}). */
    private final String format;
    /** WMTS tile-matrix set (e.g. {@code "PM"}); empty string for WMS. */
    private final String matrixSet;
    /** Overlay opacity in [0, 1]; 1.0 = fully opaque. */
    private float alpha   = 1.0f;
    /** Whether this layer is currently shown on the map. */
    private boolean visible = true;

    /**
     * @param id          Unique layer identifier (UUID string).
     * @param name        Human-readable display name.
     * @param type        Service protocol ({@link LayerType#WMTS} or {@link LayerType#WMS}).
     * @param endpointUrl Base service endpoint URL.
     * @param layerName   Layer identifier for tile requests.
     * @param style       Style name for requests; may be empty.
     * @param format      MIME tile format (e.g. {@code "image/png"}).
     * @param matrixSet   WMTS tile-matrix set; empty for WMS.
     */
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

    /** @return Unique layer ID (UUID string). */
    public String getId()          { return id; }
    /** @return Human-readable display name. */
    public String getName()        { return name; }
    /** @return Service protocol type. */
    public LayerType getType()     { return type; }
    /** @return Base service endpoint URL. */
    public String getEndpointUrl() { return endpointUrl; }
    /** @return Layer identifier used in tile requests. */
    public String getLayerName()   { return layerName; }
    /** @return Style name used in requests (may be empty). */
    public String getStyle()       { return style; }
    /** @return MIME tile format (e.g. {@code "image/png"}). */
    public String getFormat()      { return format; }
    /** @return WMTS tile-matrix set; empty string for WMS. */
    public String getMatrixSet()   { return matrixSet; }
    /** @return Overlay opacity in [0, 1]. */
    public float  getAlpha()       { return alpha; }
    /** @return {@code true} if this layer is currently visible on the map. */
    public boolean isVisible()     { return visible; }

    /**
     * Sets the overlay opacity, clamped to [0, 1].
     *
     * @param alpha Opacity where 0 = transparent, 1 = fully opaque.
     */
    public void setAlpha(float alpha)      { this.alpha   = Math.max(0f, Math.min(1f, alpha)); }

    /**
     * @param visible {@code true} to show the layer; {@code false} to hide it.
     */
    public void setVisible(boolean visible){ this.visible = visible; }
}
