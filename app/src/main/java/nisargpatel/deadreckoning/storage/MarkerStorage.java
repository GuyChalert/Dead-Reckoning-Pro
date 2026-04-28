package nisargpatel.deadreckoning.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import nisargpatel.deadreckoning.model.Marker;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists user-placed {@link nisargpatel.deadreckoning.model.Marker} objects as a JSON array
 * in SharedPreferences. Provides basic CRUD operations backed by Gson serialisation.
 */
public class MarkerStorage {
    
    private static final String PREFS_NAME = "MarkerStorage";
    private static final String MARKERS_KEY = "markers";
    
    private final SharedPreferences prefs;
    private final Gson gson;
    
    public MarkerStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
    }
    
    /** Appends {@code marker} to the persisted list. Duplicate IDs are not checked. */
    public void saveMarker(Marker marker) {
        List<Marker> markers = getAllMarkers();
        markers.add(marker);
        saveAllMarkers(markers);
    }
    
    /** Replaces the first marker whose {@code id} matches {@code marker.getId()}. No-op if not found. */
    public void updateMarker(Marker marker) {
        List<Marker> markers = getAllMarkers();
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).getId().equals(marker.getId())) {
                markers.set(i, marker);
                break;
            }
        }
        saveAllMarkers(markers);
    }
    
    /** Removes the marker with the given {@code id} from the persisted list. */
    public void deleteMarker(String id) {
        List<Marker> markers = getAllMarkers();
        markers.removeIf(m -> m.getId().equals(id));
        saveAllMarkers(markers);
    }
    
    /** @return Mutable list of all persisted markers, or an empty list if none exist. */
    public List<Marker> getAllMarkers() {
        String json = prefs.getString(MARKERS_KEY, "[]");
        Type type = new TypeToken<ArrayList<Marker>>(){}.getType();
        List<Marker> markers = gson.fromJson(json, type);
        return markers != null ? markers : new ArrayList<>();
    }
    
    /** @return The marker with the given {@code id}, or null if not found. */
    public Marker getMarker(String id) {
        List<Marker> markers = getAllMarkers();
        for (Marker marker : markers) {
            if (marker.getId().equals(id)) {
                return marker;
            }
        }
        return null;
    }
    
    /** Deletes all persisted markers from SharedPreferences. */
    public void clearAllMarkers() {
        prefs.edit().remove(MARKERS_KEY).apply();
    }
    
    /** Serialises the full marker list to JSON and writes it to SharedPreferences. */
    private void saveAllMarkers(List<Marker> markers) {
        String json = gson.toJson(markers);
        prefs.edit().putString(MARKERS_KEY, json).apply();
    }
    
    /** @return Total number of persisted markers. */
    public int getMarkerCount() {
        return getAllMarkers().size();
    }
}
