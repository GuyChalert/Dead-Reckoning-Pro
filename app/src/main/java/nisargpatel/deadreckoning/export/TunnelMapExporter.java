package nisargpatel.deadreckoning.export;

import android.content.Context;

import org.osmdroid.util.GeoPoint;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import nisargpatel.deadreckoning.slam.FactorGraph;
import nisargpatel.deadreckoning.slam.GraphSlamEngine;
import nisargpatel.deadreckoning.slam.PoseNode;

/**
 * Exports the Graph-SLAM optimized tunnel path to GeoJSON, KML, or CSV.
 *
 * Files are written to getExternalFilesDir("exports").
 * Share via intent or access with a file manager / ADB pull.
 *
 * GeoJSON: standard FeatureCollection with LineString geometry.
 *          Compatible with QGIS, JOSM, Leaflet, MapLibre.
 * KML:     Google Earth / Maps compatible Placemark LineString.
 * CSV:     node_id, lat, lon, heading_deg, east_m, north_m per keyframe.
 */
public class TunnelMapExporter {

    public enum Format { GEOJSON, KML, CSV }

    private static final SimpleDateFormat ISO_FMT =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);

    /** Exports the path and returns the output File. Throws IOException on failure. */
    public static File export(Context ctx, GraphSlamEngine slam, Format format) throws IOException {
        List<GeoPoint> pts = slam.getCorrectedPath();
        String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String ext = format == Format.GEOJSON ? ".geojson"
                   : format == Format.KML     ? ".kml"
                   :                            ".csv";
        File dir  = getExportDir(ctx);
        File file = new File(dir, "tunnel_" + ts + ext);

        String content;
        switch (format) {
            case GEOJSON: content = buildGeoJson(pts, slam); break;
            case KML:     content = buildKml(pts, ts);       break;
            default:      content = buildCsv(slam);          break;
        }

        try (FileWriter w = new FileWriter(file)) {
            w.write(content);
        }
        return file;
    }

    // ------------------------------------------------------------------

    /** Builds a GeoJSON FeatureCollection with a LineString from the corrected path points. */
    private static String buildGeoJson(List<GeoPoint> pts, GraphSlamEngine slam) {
        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            GeoPoint p = pts.get(i);
            if (i > 0) coords.append(',');
            coords.append(String.format(Locale.US, "[%.8f,%.8f]",
                    p.getLongitude(), p.getLatitude()));
        }
        return "{\n"
            + "  \"type\": \"FeatureCollection\",\n"
            + "  \"features\": [{\n"
            + "    \"type\": \"Feature\",\n"
            + "    \"properties\": {\n"
            + "      \"name\": \"Tunnel Survey\",\n"
            + "      \"generated\": \"" + ISO_FMT.format(new Date()) + "\",\n"
            + "      \"node_count\": " + pts.size() + ",\n"
            + "      \"total_distance_m\": "
            + String.format(Locale.US, "%.2f", slam.getTotalPathMetres()) + "\n"
            + "    },\n"
            + "    \"geometry\": {\n"
            + "      \"type\": \"LineString\",\n"
            + "      \"coordinates\": [" + coords + "]\n"
            + "    }\n"
            + "  }]\n"
            + "}\n";
    }

    /** Builds a KML document with a single red {@code LineString} placemark ({@code #line} style). */
    private static String buildKml(List<GeoPoint> pts, String name) {
        StringBuilder coords = new StringBuilder();
        for (GeoPoint p : pts) {
            coords.append(String.format(Locale.US, "%.8f,%.8f,0 ",
                    p.getLongitude(), p.getLatitude()));
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n"
            + "  <Document>\n"
            + "    <name>Tunnel Survey " + name + "</name>\n"
            + "    <Style id=\"line\">\n"
            + "      <LineStyle><color>ff0000ff</color><width>3</width></LineStyle>\n"
            + "    </Style>\n"
            + "    <Placemark>\n"
            + "      <name>Path</name>\n"
            + "      <styleUrl>#line</styleUrl>\n"
            + "      <LineString>\n"
            + "        <altitudeMode>clampToGround</altitudeMode>\n"
            + "        <coordinates>" + coords.toString().trim() + "</coordinates>\n"
            + "      </LineString>\n"
            + "    </Placemark>\n"
            + "  </Document>\n"
            + "</kml>\n";
    }

    /**
     * Builds a CSV with columns: node_id, latitude (°), longitude (°), east_m (m), north_m (m),
     * heading_deg (°). One row per SLAM keyframe node.
     */
    private static String buildCsv(GraphSlamEngine slam) {
        StringBuilder sb = new StringBuilder();
        sb.append("node_id,latitude,longitude,east_m,north_m,heading_deg\n");
        // Access nodes via FactorGraph (package-visible list)
        for (PoseNode n : slam.getNodes()) {
            double[] ll = slam.enzToGpsPublic(n.x, n.y);
            sb.append(String.format(Locale.US, "%d,%.8f,%.8f,%.3f,%.3f,%.2f\n",
                    n.id, ll[0], ll[1], n.x, n.y,
                    Math.toDegrees(n.theta)));
        }
        return sb.toString();
    }

    /** Returns the exports directory, creating it if necessary; falls back to internal files dir. */
    private static File getExportDir(Context ctx) {
        File dir = ctx.getExternalFilesDir("exports");
        if (dir != null && !dir.exists()) dir.mkdirs();
        return dir != null ? dir : ctx.getFilesDir();
    }
}
