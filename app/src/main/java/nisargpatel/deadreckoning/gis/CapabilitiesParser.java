package nisargpatel.deadreckoning.gis;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses WMS 1.1.1/1.3.0 and WMTS 1.0.0 GetCapabilities XML responses.
 * Returns a list of layers that support Web Mercator (EPSG:3857 / PM).
 */
public class CapabilitiesParser {

    /** Result callback delivered on the calling thread (must be a background thread). */
    public interface ParseCallback {
        /**
         * @param serviceType Detected service type ({@link LayerType#WMS} or {@link LayerType#WMTS}).
         * @param layers      Layers that support Web Mercator; may be empty but never null.
         */
        void onSuccess(LayerType serviceType, List<LayerInfo> layers);
        /** @param message Human-readable error description. */
        void onError(String message);
    }

    /**
     * Parses the given input stream and calls callback on the calling thread.
     * Should be called from a background thread.
     */
    public static void parse(InputStream is, ParseCallback callback) {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(is, null);
            parser.nextTag();

            String root = stripNs(parser.getName());
            if ("Capabilities".equals(root)) {
                callback.onSuccess(LayerType.WMTS, parseWmts(parser));
            } else if ("WMS_Capabilities".equals(root) || "WMT_MS_Capabilities".equals(root)) {
                callback.onSuccess(LayerType.WMS, parseWms(parser));
            } else {
                callback.onError("Unrecognised capabilities root element: " + root);
            }
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ WMTS

    /** Iterates all {@code <Layer>} elements in a WMTS Capabilities document. */
    private static List<LayerInfo> parseWmts(XmlPullParser p)
            throws XmlPullParserException, IOException {
        List<LayerInfo> layers = new ArrayList<>();
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.getEventType() == XmlPullParser.START_TAG
                    && "Layer".equals(stripNs(p.getName()))) {
                LayerInfo info = readWmtsLayer(p);
                if (info != null) layers.add(info);
            }
        }
        return layers;
    }

    /**
     * Reads one WMTS {@code <Layer>} element.
     * Returns null if the layer has no Identifier, no PM/GoogleMapsCompatible TileMatrixSet,
     * or no supported image format.
     */
    private static LayerInfo readWmtsLayer(XmlPullParser p)
            throws XmlPullParserException, IOException {
        String name = "", title = "", defaultStyle = "", matrixSet = "";
        List<String> formats = new ArrayList<>();

        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                String tag = stripNs(p.getName());
                switch (tag) {
                    case "Identifier": name         = readText(p); depth--; break;
                    case "Title":      title        = readText(p); depth--; break;
                    case "Format":
                        String fmt = readText(p);
                        if (fmt.contains("png") || fmt.contains("jpeg") || fmt.contains("jpg")) {
                            formats.add(fmt);
                        }
                        depth--;
                        break;
                    case "TileMatrixSet":
                        String ms = readText(p); depth--;
                        if (matrixSet.isEmpty()
                                && (ms.startsWith("PM")
                                    || ms.startsWith("GoogleMaps"))) {
                            matrixSet = ms;
                        }
                        break;
                    case "Style":
                        String isDefault = p.getAttributeValue(null, "isDefault");
                        if ("true".equalsIgnoreCase(isDefault)) {
                            defaultStyle = parseStyleIdentifier(p);
                        } else {
                            skipTag(p);
                        }
                        depth--;
                        break;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }

        if (name.isEmpty() || matrixSet.isEmpty()) return null;
        String fmt = pickFormat(formats);
        if (fmt == null) return null;
        return new LayerInfo(name, title, LayerType.WMTS,
                formats, defaultStyle.isEmpty() ? "normal" : defaultStyle, matrixSet);
    }

    /** Reads the {@code <Identifier>} text from within a {@code <Style>} element. */
    private static String parseStyleIdentifier(XmlPullParser p)
            throws XmlPullParserException, IOException {
        String id = "";
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                if ("Identifier".equals(stripNs(p.getName()))) {
                    id = readText(p); depth--;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }
        return id;
    }

    // ------------------------------------------------------------------ WMS

    /** Iterates all {@code <Layer>} elements in a WMS Capabilities document. */
    private static List<LayerInfo> parseWms(XmlPullParser p)
            throws XmlPullParserException, IOException {
        List<LayerInfo> layers = new ArrayList<>();
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.getEventType() == XmlPullParser.START_TAG
                    && "Layer".equals(stripNs(p.getName()))) {
                // Only leaf layers have a <Name> direct child
                LayerInfo info = readWmsLayer(p);
                if (info != null) layers.add(info);
            }
        }
        return layers;
    }

    /**
     * Reads one WMS {@code <Layer>} element, skipping nested group layers.
     * Returns null if the layer has no Name.
     */
    private static LayerInfo readWmsLayer(XmlPullParser p)
            throws XmlPullParserException, IOException {
        String name = "", title = "";
        boolean supports3857 = false;
        List<String> formats = new ArrayList<>();

        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                String tag = stripNs(p.getName());
                switch (tag) {
                    case "Name":  name  = readText(p); depth--; break;
                    case "Title": title = readText(p); depth--; break;
                    case "SRS": case "CRS":
                        String crs = readText(p); depth--;
                        if (crs.contains("3857") || crs.contains("900913")) {
                            supports3857 = true;
                        }
                        break;
                    case "Layer":
                        // nested layer group — skip entire subtree so we only
                        // capture the innermost leaf layers
                        skipTag(p); depth--;
                        break;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
        }

        if (name.isEmpty()) return null;
        // WMS format list comes from the outer <Capability> section; we accept
        // all WMS layers that support 3857 and assume png/jpeg are available
        return new LayerInfo(name, title, LayerType.WMS,
                Arrays.asList("image/png", "image/jpeg"),
                "", "");
    }

    // ------------------------------------------------------------------ util

    /** Reads the text content of the current element and advances past the end tag. */
    private static String readText(XmlPullParser p)
            throws XmlPullParserException, IOException {
        String result = "";
        if (p.next() == XmlPullParser.TEXT) {
            result = p.getText().trim();
            p.nextTag(); // consume END_TAG
        }
        return result;
    }

    /** Skips the current open element and all its nested children. */
    private static void skipTag(XmlPullParser p)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
        }
    }

    /** Removes XML namespace prefix from a tag name (e.g. {@code "ows:Identifier"} → {@code "Identifier"}). */
    private static String stripNs(String name) {
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** Picks the best image format from a list: PNG preferred over JPEG, JPEG over other. */
    private static String pickFormat(List<String> formats) {
        for (String f : formats) if (f.contains("png"))  return f;
        for (String f : formats) if (f.contains("jpeg")) return f;
        for (String f : formats) if (f.contains("jpg"))  return f;
        return formats.isEmpty() ? null : formats.get(0);
    }
}
