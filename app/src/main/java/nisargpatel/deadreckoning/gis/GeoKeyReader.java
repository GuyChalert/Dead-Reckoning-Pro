package nisargpatel.deadreckoning.gis;

import java.util.List;

import mil.nga.tiff.FieldTagType;
import mil.nga.tiff.FileDirectory;

/**
 * Extracts the EPSG code from a GeoTIFF GeoKey directory (TIFF tag 34735).
 * Falls back to 4326 if not found.
 */
class GeoKeyReader {

    static int readEpsg(FileDirectory dir) {
        Number[] tag = getShortTag(dir, 34735);
        if (tag == null || tag.length < 4) return 4326;

        int numKeys = tag[3].intValue();
        int projected  = -1;
        int geographic = -1;

        for (int i = 0; i < numKeys; i++) {
            int base = 4 + i * 4;
            if (base + 3 >= tag.length) break;
            int keyId    = tag[base].intValue();
            int location = tag[base + 1].intValue();
            if (location != 0) continue;
            int value = tag[base + 3].intValue();
            if (keyId == 3072) projected  = value;
            if (keyId == 2048) geographic = value;
        }

        if (projected  > 0 && projected  != 32767) return projected;
        if (geographic > 0 && geographic != 32767) return geographic;
        return 4326;
    }

    private static Number[] getShortTag(FileDirectory dir, int tagId) {
        try {
            FieldTagType tag = FieldTagType.getById(tagId);
            if (tag == null) return null;
            Object val = dir.get(tag);
            if (val instanceof Number[]) return (Number[]) val;
            if (val instanceof List) {
                List<?> list = (List<?>) val;
                Number[] n = new Number[list.size()];
                for (int i = 0; i < list.size(); i++) n[i] = (Number) list.get(i);
                return n;
            }
            if (val instanceof short[]) {
                short[] s = (short[]) val;
                Number[] n = new Number[s.length];
                for (int i = 0; i < s.length; i++) n[i] = s[i] & 0xFFFF;
                return n;
            }
        } catch (Exception ignored) { }
        return null;
    }
}
