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

    public MBTilesArchive(File dbFile) {
        this.dbFile = dbFile;
        db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY);
    }

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

    @Override
    public Set<String> getTileSources() {
        return Collections.emptySet();
    }

    @Override
    public void setIgnoreTileSource(boolean pIgnoreTileSource) {
        ignoreTileSource = pIgnoreTileSource;
    }
}
