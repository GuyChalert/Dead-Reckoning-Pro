package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
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
 * Imports a zipped Shapefile (.zip containing .shp + optional .prj) and
 * renders the geometries as osmdroid overlays on the given MapView.
 *
 * All work is done on the calling thread; callers must invoke off the UI thread.
 * After {@link #load(Context, Uri, MapView)} returns, overlays have been posted to
 * the main thread via {@link MapView#post}.
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
     * Load a .zip containing a shapefile and add overlays to the MapView.
     *
     * @param zipUri URI of a .zip file (via SAF / ACTION_OPEN_DOCUMENT)
     * @throws IOException on parse or read errors
     */
    public static void load(Context context, Uri zipUri, MapView mapView) throws IOException {
        byte[] shpBytes = null;
        String prjWkt   = null;

        // Extract .shp and .prj from the ZIP
        try (InputStream raw = context.getContentResolver().openInputStream(zipUri);
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

        // Determine source CRS from .prj (fallback: WGS84 = no reprojection needed)
        int epsg = PrjParser.parseEpsg(prjWkt);
        CoordinateTransform transform = null;
        if (epsg != 4326) {
            CoordinateReferenceSystem srcCrs = crsFactory.createFromName("epsg:" + epsg);
            transform = xfFactory.createTransform(srcCrs, WGS84);
        }

        // Parse geometries
        List<ShapefileReader.Feature> features;
        try (InputStream shpStream = new java.io.ByteArrayInputStream(shpBytes)) {
            features = ShapefileReader.read(shpStream);
        }

        // Convert to osmdroid overlays
        final List<org.osmdroid.views.overlay.Overlay> overlays = new ArrayList<>();
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

        // Add overlays on the main thread
        mapView.post(() -> {
            mapView.getOverlays().addAll(overlays);
            mapView.invalidate();
        });
    }

    private static GeoPoint reproject(double[] xy, CoordinateTransform xf) {
        if (xf == null) return new GeoPoint(xy[1], xy[0]);
        ProjCoordinate src = new ProjCoordinate(xy[0], xy[1]);
        ProjCoordinate dst = new ProjCoordinate();
        xf.transform(src, dst);
        return new GeoPoint(dst.y, dst.x);
    }

    private static List<GeoPoint> toGeoPoints(double[][] ring, CoordinateTransform xf) {
        List<GeoPoint> pts = new ArrayList<>(ring.length);
        for (double[] xy : ring) pts.add(reproject(xy, xf));
        return pts;
    }
}
