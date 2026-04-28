package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a Shapefile and returns the resulting overlays.
 * Accepts either a .zip (containing .shp + optional .prj) or a raw .shp file URI.
 * For raw .shp URIs, tries to find a sibling .prj via DocumentsContract; falls back to WGS84.
 */
public class ShapefileOverlay {

    private static final int COLOR_STROKE  = Color.argb(200, 0, 120, 255);
    private static final int COLOR_FILL    = Color.argb(60,  0, 120, 255);
    private static final float STROKE_WIDTH = 3f;

    private static final CRSFactory        crsFactory    = new CRSFactory();
    private static final CoordinateTransformFactory xfFactory = new CoordinateTransformFactory();
    private static final CoordinateReferenceSystem WGS84 =
            crsFactory.createFromName("epsg:4326");

    /**
     * Load a shapefile and return overlays. Accepts:
     *   - .zip URI (containing .shp + optional .prj)
     *   - raw .shp URI (sibling .prj resolved via DocumentsContract when possible)
     *
     * Must be called off the UI thread.
     */
    public static List<Overlay> load(Context context, Uri uri, MapView mapView) throws IOException {
        byte[] shpBytes = null;
        String prjWkt   = null;

        String segment = uri.getLastPathSegment();
        boolean isZip = (segment != null && segment.toLowerCase().endsWith(".zip"))
                || "application/zip".equals(context.getContentResolver().getType(uri));

        if (isZip) {
            try (InputStream raw = context.getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName().toLowerCase();
                    if (name.endsWith(".shp")) {
                        shpBytes = zis.readAllBytes();
                    } else if (name.endsWith(".prj")) {
                        prjWkt = new BufferedReader(
                                new InputStreamReader(zis, StandardCharsets.UTF_8))
                                .readLine();
                    }
                    zis.closeEntry();
                }
            }
            if (shpBytes == null) throw new IOException("No .shp file found in ZIP");
        } else {
            // Raw .shp file
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("Cannot open .shp URI");
                shpBytes = is.readAllBytes();
            }
            // Try to find sibling .prj via DocumentsContract
            prjWkt = readSiblingPrj(context, uri);
        }

        int epsg = PrjParser.parseEpsg(prjWkt);
        CoordinateTransform transform = null;
        if (epsg != 4326) {
            CoordinateReferenceSystem srcCrs = crsFactory.createFromName("epsg:" + epsg);
            transform = xfFactory.createTransform(srcCrs, WGS84);
        }

        List<ShapefileReader.Feature> features;
        try (InputStream shpStream = new java.io.ByteArrayInputStream(shpBytes)) {
            features = ShapefileReader.read(shpStream);
        }

        final List<Overlay> overlays = new ArrayList<>();
        final CoordinateTransform xf = transform;

        for (ShapefileReader.Feature f : features) {
            switch (f.shapeType) {
                case ShapefileReader.TYPE_POINT:
                    if (!f.rings.isEmpty() && f.rings.get(0).length > 0) {
                        GeoPoint gp = reproject(f.rings.get(0)[0], xf);
                        Marker marker = new Marker(mapView);
                        marker.setPosition(gp);
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                        overlays.add(marker);
                    }
                    break;
                case ShapefileReader.TYPE_POLYLINE:
                    for (double[][] ring : f.rings) {
                        Polyline line = new Polyline();
                        line.setColor(COLOR_STROKE);
                        line.setWidth(STROKE_WIDTH);
                        line.setPoints(toGeoPoints(ring, xf));
                        overlays.add(line);
                    }
                    break;
                case ShapefileReader.TYPE_POLYGON:
                    for (double[][] ring : f.rings) {
                        Polygon poly = new Polygon();
                        poly.setStrokeColor(COLOR_STROKE);
                        poly.setFillColor(COLOR_FILL);
                        poly.setStrokeWidth(STROKE_WIDTH);
                        poly.setPoints(toGeoPoints(ring, xf));
                        overlays.add(poly);
                    }
                    break;
            }
        }

        return overlays;
    }

    /**
     * Tries to read the WKT string from the .prj file that is a sibling of the given .shp URI.
     * Uses {@link DocumentsContract#buildDocumentUri} to swap the extension in the document ID.
     *
     * @return WKT projection string, or null if not found or not a SAF content URI.
     */
    private static String readSiblingPrj(Context context, Uri shpUri) {
        if (!"content".equals(shpUri.getScheme())) return null;
        try {
            String authority = shpUri.getAuthority();
            String docId = DocumentsContract.getDocumentId(shpUri);
            if (docId == null || authority == null) return null;
            // Replace .shp extension with .prj
            String prjDocId = docId.replaceAll("(?i)\\.shp$", ".prj");
            if (prjDocId.equals(docId)) return null;
            Uri prjUri = DocumentsContract.buildDocumentUri(authority, prjDocId);
            try (InputStream is = context.getContentResolver().openInputStream(prjUri)) {
                if (is == null) return null;
                return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .readLine();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Converts a shapefile [x, y] coordinate pair to a WGS-84 {@link GeoPoint}.
     * If {@code xf} is null the coordinates are assumed to already be WGS-84 (lon, lat).
     */
    private static GeoPoint reproject(double[] xy, CoordinateTransform xf) {
        if (xf == null) return new GeoPoint(xy[1], xy[0]);
        ProjCoordinate src = new ProjCoordinate(xy[0], xy[1]);
        ProjCoordinate dst = new ProjCoordinate();
        xf.transform(src, dst);
        return new GeoPoint(dst.y, dst.x);
    }

    /** Converts all coordinate pairs in a shapefile ring to a list of WGS-84 GeoPoints. */
    private static List<GeoPoint> toGeoPoints(double[][] ring, CoordinateTransform xf) {
        List<GeoPoint> pts = new ArrayList<>(ring.length);
        for (double[] xy : ring) pts.add(reproject(xy, xf));
        return pts;
    }
}
