package nisargpatel.deadreckoning.gis;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.MapTileIndex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads WMS/WMTS tiles into an MBTiles SQLite file.
 *
 * TMS Y-flip: MBTiles stores tile_row = (2^z - 1) - osmY, matching
 * the convention used by {@link MBTilesArchive} on read-back.
 */
public class OfflineTileDownloader {

    public static final int MAX_TILES = 5000;

    public interface Callback {
        /** Called after each tile attempt; {@code done} ≤ {@code total}. */
        void onProgress(int done, int total);
        /** Called when all tiles have been written; {@code out} is the MBTiles file. */
        void onDone(File out);
        /** Called if the download is aborted (too many tiles, network error, DB failure). */
        void onError(Exception e);
    }

    /** Estimate total tile count across [minZoom, maxZoom] for the given bounding box. */
    public static long tileCount(BoundingBox box, int minZoom, int maxZoom) {
        long total = 0;
        for (int z = minZoom; z <= maxZoom; z++) {
            int[] r = tileRange(box, z);
            long w = r[2] - r[0] + 1;
            long h = r[3] - r[1] + 1;
            if (w > 0 && h > 0) total += w * h;
        }
        return total;
    }

    /**
     * Download tiles from the given tile source into an MBTiles SQLite file.
     * Must be called off the UI thread.
     */
    public static void download(OnlineTileSourceBase source, BoundingBox box,
                                int minZoom, int maxZoom, File output, Callback cb) {
        long total = tileCount(box, minZoom, maxZoom);
        if (total > MAX_TILES) {
            cb.onError(new IOException("Too many tiles: " + total +
                " (max " + MAX_TILES + "). Reduce zoom or region."));
            return;
        }

        SQLiteDatabase db = null;
        try {
            if (output.exists()) output.delete();
            db = SQLiteDatabase.openOrCreateDatabase(output, null);
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)");
            db.execSQL("CREATE TABLE tiles " +
                "(zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)");
            db.execSQL("CREATE UNIQUE INDEX tiles_idx ON tiles " +
                "(zoom_level, tile_column, tile_row)");

            String rel = source.getTileRelativeFilenameString(
                    org.osmdroid.util.MapTileIndex.getTileIndex(0, 0, 0));
            String fmt = (rel != null && rel.contains("jpg")) ? "jpg" : "png";
            insertMeta(db, "name",    source.name());
            insertMeta(db, "format",  fmt);
            insertMeta(db, "minzoom", String.valueOf(minZoom));
            insertMeta(db, "maxzoom", String.valueOf(maxZoom));
            insertMeta(db, "bounds",
                box.getLonWest() + "," + box.getLatSouth() + "," +
                box.getLonEast() + "," + box.getLatNorth());

            SQLiteStatement stmt = db.compileStatement(
                "INSERT OR REPLACE INTO tiles " +
                "(zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)");

            int done = 0;
            db.beginTransaction();
            try {
                for (int z = minZoom; z <= maxZoom; z++) {
                    int[] range = tileRange(box, z);
                    for (int x = range[0]; x <= range[2]; x++) {
                        for (int y = range[1]; y <= range[3]; y++) {
                            String tileUrl = source.getTileURLString(
                                MapTileIndex.getTileIndex(z, x, y));
                            byte[] data = fetchBytes(tileUrl);
                            if (data != null && data.length > 0) {
                                int tmsY = (1 << z) - 1 - y;
                                stmt.bindLong(1, z);
                                stmt.bindLong(2, x);
                                stmt.bindLong(3, tmsY);
                                stmt.bindBlob(4, data);
                                stmt.executeInsert();
                                stmt.clearBindings();
                            }
                            done++;
                            cb.onProgress(done, (int) total);
                        }
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            db.close();
            cb.onDone(output);
        } catch (Exception e) {
            if (db != null && db.isOpen()) db.close();
            cb.onError(e);
        }
    }

    /** Inserts a key/value row into the MBTiles {@code metadata} table. */
    private static void insertMeta(SQLiteDatabase db, String key, String value) {
        db.execSQL("INSERT INTO metadata (name, value) VALUES (?, ?)",
            new Object[]{key, value});
    }

    /** Returns [xMin, yMin, xMax, yMax] in XYZ tile coordinates for the given zoom. */
    private static int[] tileRange(BoundingBox box, int z) {
        int xMin = lonToTileX(box.getLonWest(),  z);
        int xMax = lonToTileX(box.getLonEast(),  z);
        int yMin = latToTileY(box.getLatNorth(), z); // north → smaller y in XYZ
        int yMax = latToTileY(box.getLatSouth(), z); // south → larger y
        if (xMin > xMax) { int t = xMin; xMin = xMax; xMax = t; }
        if (yMin > yMax) { int t = yMin; yMin = yMax; yMax = t; }
        int cap = (1 << z) - 1;
        return new int[]{
            Math.max(0, xMin), Math.max(0, yMin),
            Math.min(cap, xMax), Math.min(cap, yMax)
        };
    }

    /** Converts longitude (°) to XYZ tile column at zoom {@code z}. */
    private static int lonToTileX(double lon, int z) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
    }

    /** Converts latitude (°) to XYZ tile row at zoom {@code z} (Web-Mercator projection). */
    private static int latToTileY(double lat, int z) {
        double rad = Math.toRadians(lat);
        double y = (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0;
        return (int) Math.floor(y * (1 << z));
    }

    /**
     * Fetches raw bytes from an HTTP/HTTPS URL with a 10 s connect / 20 s read timeout.
     *
     * @return Response body bytes, or null on non-200 response or network error.
     */
    private static byte[] fetchBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", "DeadReckoningPro/1.0");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
