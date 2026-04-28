package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.osmdroid.util.BoundingBox;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import mil.nga.tiff.FieldTagType;
import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.TIFFImage;
import mil.nga.tiff.TiffReader;
import mil.nga.tiff.Rasters;

/**
 * Imports a GeoTIFF from a SAF URI and produces a {@link RasterOverlay}.
 * Memory cap: downsamples so the longest side is at most MAX_SIDE pixels.
 */
public class GeoTiffImporter {

    private static final int MAX_SIDE = 4096;

    private static final CRSFactory            crsFactory = new CRSFactory();
    private static final CoordinateTransformFactory xfFactory = new CoordinateTransformFactory();
    private static final CoordinateReferenceSystem  WGS84 =
            crsFactory.createFromName("epsg:4326");

    /**
     * Reads and decodes a GeoTIFF from a SAF URI into a georeferenced {@link RasterOverlay}.
     * Extracts the bounding box from ModelTiepoint+ModelPixelScale (tag 33922/33550) or
     * ModelTransformation (tag 34264), then reprojects from the file's EPSG CRS to WGS-84.
     * Downsamples so that neither dimension exceeds {@link #MAX_SIDE} pixels.
     *
     * @throws IOException if the URI cannot be opened, TIFF dimensions are missing,
     *                     or georef tags are absent.
     */
    public static RasterOverlay load(Context context, Uri uri) throws IOException {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open URI");
            bytes = in.readAllBytes();
        }

        TIFFImage tiff = TiffReader.readTiff(bytes);
        FileDirectory dir = tiff.getFileDirectory();

        // --- image dimensions via convenience methods
        Number wObj = dir.getImageWidth();
        Number hObj = dir.getImageHeight();
        if (wObj == null || hObj == null)
            throw new IOException("Cannot read TIFF dimensions");
        int imageWidth  = wObj.intValue();
        int imageHeight = hObj.intValue();

        // --- georef: try ModelTiepoint + ModelPixelScale first
        double west, east, north, south;
        double[] tiepoint   = getDoubleList(dir, 33922);
        double[] pixelScale = getDoubleList(dir, 33550);

        if (tiepoint != null && tiepoint.length >= 6
                && pixelScale != null && pixelScale.length >= 2) {
            double originX = tiepoint[3];
            double originY = tiepoint[4];
            double scaleX  = pixelScale[0];
            double scaleY  = pixelScale[1];
            west  = originX;
            north = originY;
            east  = originX + imageWidth  * scaleX;
            south = originY - imageHeight * scaleY;
        } else {
            double[] xform = getDoubleList(dir, 34264);
            if (xform == null || xform.length < 16)
                throw new IOException("GeoTIFF has no recognisable georef tags");
            double a = xform[0], b = xform[1], tx = xform[3];
            double d = xform[4], e = xform[5], ty = xform[7];
            west  = tx;
            north = ty;
            east  = tx + a * imageWidth  + b * imageHeight;
            south = ty + d * imageWidth  + e * imageHeight;
        }

        // --- reproject corners if not already WGS84
        int epsg = GeoKeyReader.readEpsg(dir);
        if (epsg != 4326) {
            CoordinateReferenceSystem src = crsFactory.createFromName("epsg:" + epsg);
            CoordinateTransform xf = xfFactory.createTransform(src, WGS84);
            ProjCoordinate nw = reproject(xf, west,  north);
            ProjCoordinate se = reproject(xf, east,  south);
            west  = nw.x; north = nw.y;
            east  = se.x; south = se.y;
        }

        // --- decode pixels
        Number sppObj = dir.getSamplesPerPixel();
        int samplesPerPixel = (sppObj != null) ? sppObj.intValue() : 3;
        Rasters rasters = dir.readRasters();

        List<Integer> bpsRaw = dir.getBitsPerSample();
        int[] bitsPerSample = new int[samplesPerPixel];
        for (int i = 0; i < samplesPerPixel; i++) {
            bitsPerSample[i] = (bpsRaw != null && i < bpsRaw.size()) ? bpsRaw.get(i) : 8;
        }
        boolean hasAlpha = (samplesPerPixel >= 4);

        int sampleX = Math.max(1, imageWidth  / MAX_SIDE);
        int sampleY = Math.max(1, imageHeight / MAX_SIDE);
        int bmpW = (imageWidth  + sampleX - 1) / sampleX;
        int bmpH = (imageHeight + sampleY - 1) / sampleY;

        int[] pixels = new int[bmpW * bmpH];
        for (int py = 0; py < bmpH; py++) {
            int srcY = py * sampleY;
            for (int px = 0; px < bmpW; px++) {
                int srcX = px * sampleX;
                int r = 0, g = 0, b = 0, a = 255;
                Number[] sample = rasters.getPixel(srcX, srcY);
                if (samplesPerPixel == 1) {
                    r = g = b = clamp(sample[0].intValue(), bitsPerSample[0]);
                } else {
                    r = clamp(sample[0].intValue(), bitsPerSample[0]);
                    g = clamp(sample[1].intValue(), bitsPerSample[1]);
                    b = clamp(sample[2].intValue(), bitsPerSample[2]);
                    if (hasAlpha) a = clamp(sample[3].intValue(), bitsPerSample[3]);
                }
                pixels[py * bmpW + px] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(pixels, bmpW, bmpH, Bitmap.Config.ARGB_8888);
        BoundingBox bbox = new BoundingBox(north, east, south, west);
        return new RasterOverlay(bitmap, bbox);
    }

    /** Projects a single (x, y) coordinate using the given transform; returns the result. */
    private static ProjCoordinate reproject(CoordinateTransform xf, double x, double y) {
        ProjCoordinate src = new ProjCoordinate(x, y);
        ProjCoordinate dst = new ProjCoordinate();
        xf.transform(src, dst);
        return dst;
    }

    /**
     * Reads a TIFF tag value as a {@code double[]} array, handling List, double[], and Number[] types.
     * Package-private so {@link GeoKeyReader} tests can call it.
     *
     * @param tagId TIFF tag identifier (e.g. 33922 for ModelTiepoint).
     * @return Array of doubles, or null if the tag is absent or its type is unrecognised.
     */
    static double[] getDoubleList(FileDirectory dir, int tagId) {
        try {
            FieldTagType tag = FieldTagType.getById(tagId);
            if (tag == null) return null;
            Object v = dir.get(tag);
            if (v instanceof List) {
                List<?> list = (List<?>) v;
                double[] result = new double[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    result[i] = ((Number) list.get(i)).doubleValue();
                }
                return result;
            }
            if (v instanceof double[]) return (double[]) v;
            if (v instanceof Number[]) {
                Number[] n = (Number[]) v;
                double[] d = new double[n.length];
                for (int i = 0; i < n.length; i++) d[i] = n[i].doubleValue();
                return d;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Normalises a raw TIFF sample to an 8-bit value [0, 255].
     * 16-bit samples are right-shifted by 8; 8-bit values are masked; others are clamped.
     */
    private static int clamp(int val, int bits) {
        if (bits <= 0 || bits == 8) return val & 0xFF;
        if (bits == 16) return (val >>> 8) & 0xFF;
        return Math.min(255, Math.max(0, val));
    }
}
