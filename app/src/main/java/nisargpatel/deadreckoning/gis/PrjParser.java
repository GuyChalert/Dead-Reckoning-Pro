package nisargpatel.deadreckoning.gis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the EPSG authority code from a .prj WKT string.
 * Looks for AUTHORITY["EPSG","<code>"] near the root of the WKT.
 * Falls back to 4326 (WGS 84) if nothing matches.
 */
class PrjParser {

    private static final Pattern AUTHORITY_PATTERN =
            Pattern.compile("AUTHORITY\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\s*\\]",
                    Pattern.CASE_INSENSITIVE);

    /** Returns the EPSG integer code, or 4326 as fallback. */
    static int parseEpsg(String wkt) {
        if (wkt == null || wkt.isEmpty()) return 4326;
        Matcher m = AUTHORITY_PATTERN.matcher(wkt);
        int last = 4326;
        while (m.find()) {
            // The last AUTHORITY block in the WKT tends to be the outermost CRS code.
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) { }
        }
        return last;
    }
}
