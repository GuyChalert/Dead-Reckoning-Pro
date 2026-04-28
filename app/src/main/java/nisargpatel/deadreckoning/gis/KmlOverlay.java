package nisargpatel.deadreckoning.gis;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses a KML or KMZ file from a SAF URI and returns the resulting overlays.
 * Caller is responsible for adding them to the MapView.
 * Supports Point, LineString, Polygon, GroundOverlay, and NetworkLink
 * (HTTP hrefs fetched one level deep, max 20 links).
 */
public class KmlOverlay {

    private static final String TAG = "KmlOverlay";
    private static final int MAX_NETWORK_LINK_DEPTH = 6;
    private static final int MAX_NETWORK_LINKS = 60;
    private static final int NET_CONNECT_TIMEOUT_MS = 8_000;
    private static final int NET_READ_TIMEOUT_MS    = 15_000;

    /** Describes a KML NetworkLink that may need to be fetched based on viewport. */
    public static class NetworkLinkDesc {
        public final String href;
        public final String viewRefreshMode;
        public final String httpBase;
        public final double north, south, east, west;
        public final double minLodPixels;
        public final int depth;

        NetworkLinkDesc(String href, String viewRefreshMode, String httpBase,
                        double north, double south, double east, double west,
                        double minLodPixels, int depth) {
            this.href = href;
            this.viewRefreshMode = viewRefreshMode;
            this.httpBase = httpBase;
            this.north = north; this.south = south;
            this.east = east;   this.west = west;
            this.minLodPixels = minLodPixels;
            this.depth = depth;
        }

        public boolean hasRegion() { return !Double.isNaN(north); }

        public boolean intersects(BoundingBox vp) {
            if (!hasRegion()) return true;
            return south <= vp.getLatNorth() && north >= vp.getLatSouth()
                && west  <= vp.getLonEast()  && east  >= vp.getLonWest();
        }

        public boolean meetsLod(BoundingBox vp, int viewWidthPx) {
            if (minLodPixels <= 0 || !hasRegion()) return true;
            double lonSpanPerPx = Math.abs(vp.getLonEast() - vp.getLonWest()) / viewWidthPx;
            if (lonSpanPerPx == 0) return true;
            double tilePxWidth = Math.abs(east - west) / lonSpanPerPx;
            return tilePxWidth >= minLodPixels;
        }
    }

    /** Result from {@link #loadFull}: static overlays + dynamic links for future viewport refresh. */
    public static class LoadResult {
        public final java.util.List<org.osmdroid.views.overlay.Overlay> overlays;
        public final java.util.List<NetworkLinkDesc> pendingLinks;

        LoadResult(java.util.List<org.osmdroid.views.overlay.Overlay> overlays,
                   java.util.List<NetworkLinkDesc> pendingLinks) {
            this.overlays = overlays;
            this.pendingLinks = pendingLinks;
        }
    }

    /**
     * Like {@link #load} but also returns pending dynamic NetworkLinks (onRegion / onStop)
     * that should be re-fetched when the viewport changes. Caller should use
     * {@link #fetchPendingLinks} to refresh them.
     */
    public static LoadResult loadFull(Context context, Uri uri, MapView mapView) throws Exception {
        List<NetworkLinkDesc> pending = new ArrayList<>();
        List<Overlay> overlays = loadImpl(context, uri, mapView, pending);
        return new LoadResult(overlays, pending);
    }

    /**
     * Fetch pending NetworkLinks that now intersect/match the given viewport and add
     * their overlays to {@code targetList}. Already-fetched hrefs in {@code fetchedSet}
     * are skipped. New pending sub-links are added back to {@code pendingLinks}.
     * Must be called off the UI thread.
     */
    public static List<Overlay> fetchPendingLinks(Context context, MapView mapView,
            List<NetworkLinkDesc> pendingLinks, java.util.Set<String> fetchedSet) {
        List<Overlay> result = new ArrayList<>();
        BoundingBox vp = mapView.getBoundingBox();
        int vpW = mapView.getWidth();
        List<NetworkLinkDesc> toFetch = new ArrayList<>();
        synchronized (pendingLinks) {
            java.util.Iterator<NetworkLinkDesc> it = pendingLinks.iterator();
            while (it.hasNext()) {
                NetworkLinkDesc d = it.next();
                if (shouldFetchNow(d, vp, vpW)) {
                    it.remove();
                    toFetch.add(d);
                }
            }
        }
        for (NetworkLinkDesc d : toFetch) {
            boolean isOnStop = "onStop".equals(d.viewRefreshMode);
            String url = isOnStop ? appendBbox(d.href, vp) : d.href;
            // onStop links are re-fetched every viewport change (don't deduplicate by href)
            if (!isOnStop && !fetchedSet.add(url)) continue;
            try {
                List<NetworkLinkDesc> subPending = new ArrayList<>();
                List<Overlay> sub = fetchNetworkLinkUrl(context, url, d.httpBase, d.depth, mapView, subPending);
                result.addAll(sub);
                synchronized (pendingLinks) { pendingLinks.addAll(subPending); }
            } catch (Exception e) {
                Log.w(TAG, "Dynamic fetch failed: " + url + " — " + e.getMessage());
            }
            // onStop links always go back to pending for next viewport change
            if (isOnStop) synchronized (pendingLinks) { pendingLinks.add(d); }
        }
        return result;
    }

    private static boolean shouldFetchNow(NetworkLinkDesc d, BoundingBox vp, int vpW) {
        if ("onStop".equals(d.viewRefreshMode)) return true;
        if ("onRegion".equals(d.viewRefreshMode)) return d.intersects(vp) && d.meetsLod(vp, vpW);
        return false;
    }

    /**
     * Parse a KML or KMZ file and return all created overlays.
     * Must be called off the UI thread (may make HTTP requests for NetworkLinks).
     */
    public static List<Overlay> load(Context context, Uri uri, MapView mapView) throws Exception {
        return loadImpl(context, uri, mapView, null);
    }

    private static List<Overlay> loadImpl(Context context, Uri uri, MapView mapView,
                                          List<NetworkLinkDesc> pendingOut) throws Exception {
        String mime = context.getContentResolver().getType(uri);
        String displayName = null;
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) displayName = c.getString(0);
        } catch (Exception ignored) {}

        // Decode the last path segment to handle URL-encoded tree URIs
        String lastSeg = uri.getLastPathSegment();
        if (lastSeg != null) {
            try { lastSeg = java.net.URLDecoder.decode(lastSeg, "UTF-8"); } catch (Exception ignored) {}
        }

        boolean isKmz = "application/vnd.google-earth.kmz".equals(mime)
                || (lastSeg != null && lastSeg.toLowerCase().endsWith(".kmz"))
                || (displayName != null && displayName.toLowerCase().endsWith(".kmz"));

        // Fallback: detect ZIP magic bytes PK\x03\x04 if MIME/name checks were inconclusive
        if (!isKmz) {
            try (InputStream probe = context.getContentResolver().openInputStream(uri)) {
                if (probe != null) {
                    byte[] h = new byte[4];
                    if (probe.read(h) == 4 && h[0] == 0x50 && h[1] == 0x4B
                            && h[2] == 0x03 && h[3] == 0x04) {
                        isKmz = true;
                    }
                }
            } catch (Exception ignored) {}
        }

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (isKmz) {
                Map<String, byte[]> zipData = new HashMap<>();
                // Android 13+ rejects ZIP entries with absolute paths (e.g. /tmp/foo.kml).
                // Patch the raw bytes to replace leading '/' in local file headers before parsing.
                byte[] kmzBytes = readAllBytes(raw);
                patchZipAbsolutePaths(kmzBytes);
                try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(kmzBytes))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        zipData.put(entry.getName(), readAllBytes(zis));
                        zis.closeEntry();
                    }
                }
                // Prefer doc.kml, then any .kml entry (by full path or filename-only)
                String kmlKey = null;
                for (String key : zipData.keySet()) {
                    if ("doc.kml".equalsIgnoreCase(key)) { kmlKey = key; break; }
                }
                if (kmlKey == null) {
                    for (String key : zipData.keySet()) {
                        String fn = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                        if ("doc.kml".equalsIgnoreCase(fn)) { kmlKey = key; break; }
                    }
                }
                if (kmlKey == null) {
                    for (String key : zipData.keySet()) {
                        if (key.toLowerCase().endsWith(".kml")) { kmlKey = key; break; }
                    }
                }
                if (kmlKey != null) {
                    return parseAndCollect(context, uri, null, 0,
                            new ByteArrayInputStream(zipData.get(kmlKey)), mapView, zipData, pendingOut);
                }
                Log.w(TAG, "KMZ contained no .kml entry: " + uri);
                return new ArrayList<>();
            } else {
                return parseAndCollect(context, uri, null, 0, raw, mapView, null, pendingOut);
            }
        }
    }

    private static List<Overlay> parseAndCollect(Context context, Uri baseUri, String httpBase,
                                                  int depth, InputStream in, MapView mapView,
                                                  Map<String, byte[]> zipData,
                                                  List<NetworkLinkDesc> pendingOut) throws Exception {
        List<Overlay> overlays = new ArrayList<>();
        List<NetworkLinkDesc> networkLinks = new ArrayList<>();

        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser xpp = factory.newPullParser();
        xpp.setInput(in, null);

        String currentName = null;
        boolean inPlacemark = false;

        int event = xpp.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = localName(xpp.getName());
                switch (tag) {
                    case "Placemark":
                        inPlacemark = true;
                        currentName = null;
                        break;
                    case "name":
                        if (inPlacemark) currentName = xpp.nextText();
                        break;
                    case "Point":
                        if (inPlacemark) {
                            GeoPoint pt = parsePoint(xpp);
                            if (pt != null) {
                                Marker m = new Marker(mapView);
                                m.setPosition(pt);
                                m.setTitle(currentName != null ? currentName : "");
                                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                                overlays.add(m);
                            }
                        }
                        break;
                    case "LineString":
                        if (inPlacemark) {
                            List<GeoPoint> pts = parseCoordinates(xpp, "LineString");
                            if (!pts.isEmpty()) {
                                Polyline line = new Polyline(mapView);
                                line.setPoints(pts);
                                line.setTitle(currentName != null ? currentName : "");
                                overlays.add(line);
                            }
                        }
                        break;
                    case "Polygon":
                        if (inPlacemark) {
                            List<GeoPoint> pts = parseCoordinates(xpp, "Polygon");
                            if (!pts.isEmpty()) {
                                Polygon poly = new Polygon(mapView);
                                poly.setPoints(pts);
                                poly.setTitle(currentName != null ? currentName : "");
                                overlays.add(poly);
                            }
                        }
                        break;
                    case "GroundOverlay":
                        Overlay go = parseGroundOverlay(context, baseUri, httpBase, xpp, zipData);
                        if (go != null) overlays.add(go);
                        break;
                    case "NetworkLink":
                        if (depth < MAX_NETWORK_LINK_DEPTH
                                && networkLinks.size() < MAX_NETWORK_LINKS) {
                            NetworkLinkDesc nl = parseNetworkLink(xpp, httpBase, depth + 1);
                            if (nl != null) networkLinks.add(nl);
                        } else {
                            skipElement(xpp, "NetworkLink");
                        }
                        break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("Placemark".equals(localName(xpp.getName()))) inPlacemark = false;
            }
            event = xpp.next();
        }

        // Evaluate and dispatch NetworkLinks
        BoundingBox vp = mapView != null ? mapView.getBoundingBox() : null;
        int vpW = mapView != null ? mapView.getWidth() : 256;
        for (NetworkLinkDesc nl : networkLinks) {
            if (zipData != null && !nl.href.startsWith("http")) {
                // KMZ internal link
                byte[] linked = zipData.get(nl.href);
                if (linked == null) {
                    for (Map.Entry<String, byte[]> e : zipData.entrySet())
                        if (e.getKey().equalsIgnoreCase(nl.href)) { linked = e.getValue(); break; }
                }
                if (linked != null) {
                    try {
                        overlays.addAll(parseAndCollect(context, null, null, nl.depth,
                                new ByteArrayInputStream(linked), mapView, zipData, pendingOut));
                    } catch (Exception e) {
                        Log.w(TAG, "KMZ link parse failed: " + nl.href + " — " + e.getMessage());
                    }
                }
            } else if ("onStop".equals(nl.viewRefreshMode)) {
                // onStop WMS: fetch with current viewport bbox, and keep in pending for re-fetch
                if (vp != null) {
                    String bboxUrl = appendBbox(nl.href, vp);
                    try {
                        overlays.addAll(fetchNetworkLinkUrl(context, bboxUrl, nl.httpBase,
                                nl.depth, mapView, pendingOut));
                    } catch (Exception e) {
                        Log.w(TAG, "onStop fetch failed: " + bboxUrl + " — " + e.getMessage());
                    }
                }
                if (pendingOut != null) pendingOut.add(nl); // keep for re-fetch on viewport change
            } else if ("onRegion".equals(nl.viewRefreshMode)) {
                if (vp != null && nl.intersects(vp) && nl.meetsLod(vp, vpW)) {
                    // Region visible — fetch now
                    try {
                        overlays.addAll(fetchNetworkLinkUrl(context, nl.href, nl.httpBase,
                                nl.depth, mapView, pendingOut));
                    } catch (Exception e) {
                        Log.w(TAG, "onRegion fetch failed: " + nl.href + " — " + e.getMessage());
                    }
                } else {
                    // Not yet visible — defer
                    if (pendingOut != null) pendingOut.add(nl);
                }
            } else {
                // "never" or unknown — fetch unconditionally
                try {
                    overlays.addAll(fetchNetworkLinkUrl(context, nl.href, nl.httpBase,
                            nl.depth, mapView, pendingOut));
                } catch (Exception e) {
                    Log.w(TAG, "NetworkLink fetch failed: " + nl.href + " — " + e.getMessage());
                }
            }
        }

        return overlays;
    }

    private static String appendBbox(String href, BoundingBox vp) {
        String bbox = String.format(java.util.Locale.US, "%f,%f,%f,%f",
                vp.getLonWest(), vp.getLatSouth(), vp.getLonEast(), vp.getLatNorth());
        String sep = href.contains("?") ? "&" : "?";
        return href + sep + "BBOX=" + bbox;
    }

    private static List<Overlay> fetchNetworkLinkUrl(Context context, String href, String httpBase,
            int depth, MapView mapView, List<NetworkLinkDesc> pendingOut) throws Exception {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            String nlBase = href.contains("/")
                    ? href.substring(0, href.lastIndexOf('/') + 1) : href + "/";
            HttpURLConnection conn = (HttpURLConnection) new URL(href).openConnection();
            conn.setConnectTimeout(NET_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(NET_READ_TIMEOUT_MS);
            try {
                String contentType = conn.getContentType();
                boolean isKmzResp = (contentType != null && contentType.contains("kmz"))
                        || href.toLowerCase().contains(".kmz");
                try (InputStream nis = conn.getInputStream()) {
                    if (isKmzResp) {
                        Map<String, byte[]> nlZip = new HashMap<>();
                        ZipInputStream nzis = new ZipInputStream(nis);
                        ZipEntry ze;
                        while ((ze = nzis.getNextEntry()) != null)
                            nlZip.put(ze.getName(), readAllBytes(nzis));
                        String kk = null;
                        for (String k : nlZip.keySet())
                            if (k.toLowerCase().endsWith(".kml")) { kk = k; break; }
                        if (kk != null)
                            return parseAndCollect(context, null, nlBase, depth,
                                    new ByteArrayInputStream(nlZip.get(kk)), mapView, nlZip, pendingOut);
                    } else {
                        return parseAndCollect(context, null, nlBase, depth, nis, mapView, null, pendingOut);
                    }
                }
            } finally {
                conn.disconnect();
            }
        }
        return new ArrayList<>();
    }

    // ---------- NetworkLink ----------

    /**
     * Parse a &lt;NetworkLink&gt; element fully, returning a descriptor with region/LOD info.
     * Returns null if there is no href.
     */
    private static NetworkLinkDesc parseNetworkLink(XmlPullParser xpp, String httpBase, int depth) throws Exception {
        String href = null;
        String viewRefreshMode = "never";
        double nlNorth = Double.NaN, nlSouth = Double.NaN, nlEast = Double.NaN, nlWest = Double.NaN;
        double minLod = 0;
        int event = xpp.next();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = localName(xpp.getName());
                switch (tag) {
                    case "href":            href = xpp.nextText().trim(); break;
                    case "viewRefreshMode": viewRefreshMode = xpp.nextText().trim(); break;
                    case "north":           nlNorth = parseDouble(xpp.nextText()); break;
                    case "south":           nlSouth = parseDouble(xpp.nextText()); break;
                    case "east":            nlEast  = parseDouble(xpp.nextText()); break;
                    case "west":            nlWest  = parseDouble(xpp.nextText()); break;
                    case "minLodPixels":    minLod  = parseDouble(xpp.nextText()); break;
                }
            } else if (event == XmlPullParser.END_TAG
                    && "NetworkLink".equals(localName(xpp.getName()))) {
                break;
            }
            event = xpp.next();
        }
        if (href == null || "onRequest".equals(viewRefreshMode)) return null;
        // Derive httpBase for sub-links from this href
        String nlHttpBase = httpBase;
        if (href.startsWith("http")) {
            nlHttpBase = href.contains("/") ? href.substring(0, href.lastIndexOf('/') + 1) : href + "/";
        } else if (httpBase != null) {
            href = httpBase + href; // resolve relative href
            nlHttpBase = href.contains("/") ? href.substring(0, href.lastIndexOf('/') + 1) : href + "/";
        }
        return new NetworkLinkDesc(href, viewRefreshMode, nlHttpBase,
                nlNorth, nlSouth, nlEast, nlWest, minLod, depth);
    }

    private static void skipElement(XmlPullParser xpp, String endTag) throws Exception {
        int event = xpp.next();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && endTag.equals(localName(xpp.getName()))) break;
            event = xpp.next();
        }
    }

    // ---------- GroundOverlay ----------

    private static Overlay parseGroundOverlay(Context context, Uri baseUri, String httpBase,
                                              XmlPullParser xpp,
                                              Map<String, byte[]> zipData) throws Exception {
        String href = null;
        double north = Double.NaN, south = Double.NaN, east = Double.NaN, west = Double.NaN;
        double rotation = 0;

        int event = xpp.next();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = localName(xpp.getName());
                switch (tag) {
                    case "href":     href     = xpp.nextText().trim(); break;
                    case "north":    north    = parseDouble(xpp.nextText()); break;
                    case "south":    south    = parseDouble(xpp.nextText()); break;
                    case "east":     east     = parseDouble(xpp.nextText()); break;
                    case "west":     west     = parseDouble(xpp.nextText()); break;
                    case "rotation": rotation = parseDouble(xpp.nextText()); break;
                }
            } else if (event == XmlPullParser.END_TAG
                    && "GroundOverlay".equals(localName(xpp.getName()))) {
                break;
            }
            event = xpp.next();
        }

        if (Double.isNaN(north) || Double.isNaN(south) || Double.isNaN(east) || Double.isNaN(west)) {
            Log.w(TAG, "GroundOverlay missing LatLonBox, skipped");
            return null;
        }
        if (href == null) {
            Log.w(TAG, "GroundOverlay missing href, skipped");
            return null;
        }
        if (rotation != 0) {
            Log.w(TAG, "GroundOverlay rotation=" + rotation + " ignored");
        }

        Bitmap bitmap = resolveImage(context, baseUri, httpBase, href, zipData);
        if (bitmap == null) {
            Log.w(TAG, "GroundOverlay could not load image: " + href);
            return null;
        }

        final Bitmap bmp = bitmap;
        final double fNorth = north, fSouth = south, fEast = east, fWest = west;

        return new Overlay() {
            @Override
            public void draw(Canvas canvas, MapView mapView, boolean shadow) {
                if (shadow) return;
                Projection proj = mapView.getProjection();
                Point nw = new Point();
                Point se = new Point();
                proj.toPixels(new GeoPoint(fNorth, fWest), nw);
                proj.toPixels(new GeoPoint(fSouth, fEast), se);
                RectF rect = new RectF(nw.x, nw.y, se.x, se.y);
                canvas.drawBitmap(bmp, null, rect, null);
            }
        };
    }

    private static Bitmap resolveImage(Context context, Uri baseUri, String httpBase,
                                       String href, Map<String, byte[]> zipData) {
        // 1. KMZ: exact path match
        if (zipData != null) {
            byte[] bytes = zipData.get(href);
            // 2. KMZ: case-insensitive full path match
            if (bytes == null) {
                for (Map.Entry<String, byte[]> e : zipData.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(href)) { bytes = e.getValue(); break; }
                }
            }
            // 3. KMZ: filename-only match (href may omit directory prefix)
            if (bytes == null) {
                String hrefName = href.contains("/") ? href.substring(href.lastIndexOf('/') + 1) : href;
                for (Map.Entry<String, byte[]> e : zipData.entrySet()) {
                    String entryName = e.getKey().contains("/")
                            ? e.getKey().substring(e.getKey().lastIndexOf('/') + 1) : e.getKey();
                    if (entryName.equalsIgnoreCase(hrefName)) { bytes = e.getValue(); break; }
                }
            }
            if (bytes != null) return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        // 4. HTTP/HTTPS remote image fetch
        if (href.startsWith("http://") || href.startsWith("https://")) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(href);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(NET_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(NET_READ_TIMEOUT_MS);
                try (InputStream is = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            } catch (Exception e) {
                Log.w(TAG, "GroundOverlay remote fetch failed: " + href + " — " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
            return null;
        }
        // 5. Relative href from HTTP NetworkLink — resolve against the KML's own URL base
        if (httpBase != null && !href.startsWith("http://") && !href.startsWith("https://")) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(httpBase + href);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(NET_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(NET_READ_TIMEOUT_MS);
                try (InputStream is = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(is);
                }
            } catch (Exception e) {
                Log.w(TAG, "GroundOverlay http-relative fetch failed: " + httpBase + href
                        + " — " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        // 7. Relative path: try to resolve sibling via DocumentsContract (plain KML from SAF)
        if (context != null && baseUri != null && "content".equals(baseUri.getScheme())) {
            Uri siblingUri = buildSiblingUri(baseUri, href);
            if (siblingUri != null) {
                try (InputStream is = context.getContentResolver().openInputStream(siblingUri)) {
                    if (is != null) return BitmapFactory.decodeStream(is);
                } catch (Exception ignored) {}
            }
        }
        // 8. Direct file path: for external storage SAF URIs decode the real path
        if (context != null && baseUri != null
                && "com.android.externalstorage.documents".equals(baseUri.getAuthority())) {
            try {
                String docId = DocumentsContract.getDocumentId(baseUri);
                int colon = docId.indexOf(':');
                if (colon >= 0) {
                    String volume = docId.substring(0, colon);
                    String filePart = docId.substring(colon + 1);
                    String root = "primary".equals(volume)
                            ? android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                            : "/storage/" + volume;
                    String dir = filePart.contains("/")
                            ? filePart.substring(0, filePart.lastIndexOf('/') + 1) : "";
                    java.io.File imgFile = new java.io.File(root + "/" + dir + href);
                    if (imgFile.exists()) return BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                    // href may already be a relative path with subdir — try as-is under root
                    imgFile = new java.io.File(root + "/" + filePart.substring(0,
                            filePart.contains("/") ? filePart.lastIndexOf('/') + 1 : 0) + href);
                    if (imgFile.exists()) return BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.w(TAG, "GroundOverlay file-path fallback failed: " + e.getMessage());
            }
        }
        return null;
    }

    private static Uri buildSiblingUri(Uri baseUri, String relHref) {
        // 1. SAF document URI (e.g. content://com.android.externalstorage.documents/...)
        try {
            String authority = baseUri.getAuthority();
            String docId = DocumentsContract.getDocumentId(baseUri);
            if (docId != null && authority != null) {
                int lastSep = docId.lastIndexOf('/');
                String parentDocId = lastSep >= 0 ? docId.substring(0, lastSep + 1) : "";
                return DocumentsContract.buildDocumentUri(authority, parentDocId + relHref);
            }
        } catch (Exception ignored) {}
        // 2. FileProvider or other content URIs: path-based sibling
        try {
            String uriStr = baseUri.toString();
            int lastSlash = uriStr.lastIndexOf('/');
            if (lastSlash >= 0) {
                return Uri.parse(uriStr.substring(0, lastSlash + 1) + relHref);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---------- helpers ----------

    /**
     * Scans ZIP local file headers (signature PK\x03\x04) and replaces any leading '/'
     * in entry names with '_'. Android 13+ ZipInputStream rejects absolute paths.
     * Operates in-place on the byte array.
     */
    private static void patchZipAbsolutePaths(byte[] data) {
        for (int i = 0; i + 30 < data.length; i++) {
            if (data[i] == 0x50 && data[i+1] == 0x4B && data[i+2] == 0x03 && data[i+3] == 0x04) {
                int fnLen = (data[i+26] & 0xFF) | ((data[i+27] & 0xFF) << 8);
                if (fnLen > 0 && i + 30 < data.length && data[i+30] == '/') {
                    data[i+30] = '_';
                }
            }
        }
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static GeoPoint parsePoint(XmlPullParser xpp) throws Exception {
        int event = xpp.next();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "coordinates".equals(localName(xpp.getName()))) {
                String text = xpp.nextText().trim();
                String[] parts = text.split("[,\\s]+");
                if (parts.length >= 2) {
                    try {
                        double lon = Double.parseDouble(parts[0]);
                        double lat = Double.parseDouble(parts[1]);
                        return new GeoPoint(lat, lon);
                    } catch (NumberFormatException ignored) {}
                }
            } else if (event == XmlPullParser.END_TAG && "Point".equals(localName(xpp.getName()))) {
                break;
            }
            event = xpp.next();
        }
        return null;
    }

    private static List<GeoPoint> parseCoordinates(XmlPullParser xpp, String stopTag) throws Exception {
        List<GeoPoint> pts = new ArrayList<>();
        int event = xpp.next();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "coordinates".equals(localName(xpp.getName()))) {
                String text = xpp.nextText().trim();
                for (String tuple : text.split("\\s+")) {
                    String[] parts = tuple.split(",");
                    if (parts.length >= 2) {
                        try {
                            double lon = Double.parseDouble(parts[0]);
                            double lat = Double.parseDouble(parts[1]);
                            pts.add(new GeoPoint(lat, lon));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } else if (event == XmlPullParser.END_TAG && stopTag.equals(localName(xpp.getName()))) {
                break;
            }
            event = xpp.next();
        }
        return pts;
    }
}
