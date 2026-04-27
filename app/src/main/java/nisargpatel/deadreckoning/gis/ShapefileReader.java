package nisargpatel.deadreckoning.gis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal .shp parser supporting Point (1), PolyLine (3), and Polygon (5).
 * Z/M variants and MultiPatch are skipped gracefully.
 * Caller is responsible for reprojection from native CRS to WGS84.
 *
 * All coordinates are returned as [longitude, latitude] pairs
 * in whatever CRS the file was produced in (see companion .prj file).
 *
 * File layout (ESRI SHP spec):
 *   - 100-byte file header (big-endian code/length, little-endian version/type/bbox)
 *   - Records: 8-byte header (big-endian record# + content-length in 16-bit words)
 *              followed by content (little-endian shape type + geometry data)
 */
class ShapefileReader {

    static final int MAX_FEATURES = 50_000;

    static final int TYPE_NULL     = 0;
    static final int TYPE_POINT    = 1;
    static final int TYPE_POLYLINE = 3;
    static final int TYPE_POLYGON  = 5;

    /** A parsed geometry feature with its shape type and coordinate rings. */
    static class Feature {
        final int shapeType;
        /** For Point: one ring of one [x,y] pair.
         *  For PolyLine/Polygon: one ring per part. */
        final List<double[][]> rings; // rings[i] = {{x0,y0},{x1,y1},...}

        Feature(int shapeType, List<double[][]> rings) {
            this.shapeType = shapeType;
            this.rings     = rings;
        }
    }

    /** Parse the entire .shp input stream and return up to MAX_FEATURES features. */
    static List<Feature> read(InputStream shp) throws IOException {
        List<Feature> features = new ArrayList<>();

        byte[] headerBytes = new byte[100];
        readFully(shp, headerBytes);
        ByteBuffer header = ByteBuffer.wrap(headerBytes);

        // File code must be 9994 (big-endian at offset 0)
        header.order(ByteOrder.BIG_ENDIAN);
        int fileCode = header.getInt(0);
        if (fileCode != 9994) throw new IOException("Not a valid .shp file (bad magic)");

        header.order(ByteOrder.LITTLE_ENDIAN);
        int fileShapeType = header.getInt(32);

        // Read records until EOF
        byte[] recHeader = new byte[8];
        while (features.size() < MAX_FEATURES) {
            int read = shp.read(recHeader);
            if (read < 0) break;
            if (read < 8) throw new IOException("Truncated record header");

            ByteBuffer rh = ByteBuffer.wrap(recHeader).order(ByteOrder.BIG_ENDIAN);
            int contentLength = rh.getInt(4); // in 16-bit words
            int byteCount     = contentLength * 2;

            byte[] content = new byte[byteCount];
            readFully(shp, content);
            ByteBuffer buf = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);

            int shapeType = buf.getInt(0);

            switch (shapeType) {
                case TYPE_NULL:
                    break;
                case TYPE_POINT:
                    features.add(readPoint(buf));
                    break;
                case TYPE_POLYLINE:
                case TYPE_POLYGON:
                    features.add(readPolyOrPolygon(buf, shapeType));
                    break;
                default:
                    // Unknown/unsupported shape type — skip gracefully
                    break;
            }
        }
        return features;
    }

    private static Feature readPoint(ByteBuffer buf) {
        double x = buf.getDouble(4);
        double y = buf.getDouble(12);
        List<double[][]> rings = new ArrayList<>(1);
        rings.add(new double[][]{{x, y}});
        return new Feature(TYPE_POINT, rings);
    }

    private static Feature readPolyOrPolygon(ByteBuffer buf, int shapeType) {
        // Offset 4: bounding box (4 doubles = 32 bytes), skip
        int numParts  = buf.getInt(36);
        int numPoints = buf.getInt(40);

        int[] partStarts = new int[numParts];
        for (int i = 0; i < numParts; i++) {
            partStarts[i] = buf.getInt(44 + i * 4);
        }

        int pointsOffset = 44 + numParts * 4;
        double[][] allPoints = new double[numPoints][2];
        for (int i = 0; i < numPoints; i++) {
            allPoints[i][0] = buf.getDouble(pointsOffset + i * 16);
            allPoints[i][1] = buf.getDouble(pointsOffset + i * 16 + 8);
        }

        List<double[][]> rings = new ArrayList<>(numParts);
        for (int p = 0; p < numParts; p++) {
            int start = partStarts[p];
            int end   = (p + 1 < numParts) ? partStarts[p + 1] : numPoints;
            double[][] ring = new double[end - start][2];
            for (int i = start; i < end; i++) {
                ring[i - start] = allPoints[i];
            }
            rings.add(ring);
        }
        return new Feature(shapeType, rings);
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) throw new IOException("Unexpected EOF");
            total += n;
        }
    }
}
