package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import org.osmdroid.tileprovider.modules.IArchiveFile;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.util.MapTileIndex;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Set;

/**
 * Reads map tiles from an MBTiles SQLite database (Mapbox spec).
 * Converts osmdroid XYZ tile coordinates to TMS (Y-flipped) for the SQL query.
 */
public class MBTilesArchive implements IArchiveFile {

    private final File dbFile;
    private SQLiteDatabase db;
    private boolean ignoreTileSource = false;

    /**
     * Opens the MBTiles SQLite database in read-only mode.
     *
     * @param dbFile Local SQLite file following the MBTiles 1.3 spec.
     */
    public MBTilesArchive(File dbFile) {
        this.dbFile = dbFile;
        db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY);
    }

    /**
     * Copies a SAF URI into the app cache directory so SQLite can open it by file path.
     * Must be called off the UI thread (copies file bytes).
     *
     * @param name Target filename within the cache directory (e.g. {@code "mbtiles_1.mbtiles"}).
     * @return The copied {@link File} in the app's cache directory.
     * @throws IOException if the URI cannot be opened or the copy fails.
     */
    public static File copyToCache(Context context, Uri uri, String name) throws IOException {
        File out = new File(context.getCacheDir(), name);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Cannot open URI");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    @Override
    public void init(File pFile) throws Exception {
        // Already opened in constructor
    }

    /**
     * Queries the {@code tiles} table for a single tile by zoom/x/y.
     * Converts osmdroid XYZ Y coordinate to TMS Y (Y-flip: {@code tmsY = 2^z - 1 - osmY}).
     *
     * @return {@link ByteArrayInputStream} of the tile blob, or null if not found.
     */
    @Override
    public InputStream getInputStream(ITileSource pTileSource, long pMapTileIndex) {
        if (db == null || !db.isOpen()) return null;
        int zoom = MapTileIndex.getZoom(pMapTileIndex);
        int x    = MapTileIndex.getX(pMapTileIndex);
        int y    = (1 << zoom) - 1 - MapTileIndex.getY(pMapTileIndex);

        try (Cursor c = db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                new String[]{String.valueOf(zoom), String.valueOf(x), String.valueOf(y)})) {
            if (c != null && c.moveToFirst()) {
                byte[] blob = c.getBlob(0);
                if (blob != null) return new ByteArrayInputStream(blob);
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override
    public void close() {
        if (db != null && db.isOpen()) {
            db.close();
            db = null;
        }
    }

    /** MBTiles archives are single-source; returns an empty set as required by the interface. */
    @Override
    public Set<String> getTileSources() {
        return Collections.emptySet();
    }

    @Override
    public void setIgnoreTileSource(boolean pIgnoreTileSource) {
        ignoreTileSource = pIgnoreTileSource;
    }
}
