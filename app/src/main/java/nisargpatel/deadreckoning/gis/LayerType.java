package nisargpatel.deadreckoning.gis;

/** Identifies the protocol used by a map layer. */
public enum LayerType {
    /** OGC Web Map Tile Service — pre-rendered tiles by zoom/x/y. */
    WMTS,
    /** OGC Web Map Service — rendered on demand for a bounding box. */
    WMS,
    /** Keyhole Markup Language — vector/raster overlay file or network link. */
    KML
}
