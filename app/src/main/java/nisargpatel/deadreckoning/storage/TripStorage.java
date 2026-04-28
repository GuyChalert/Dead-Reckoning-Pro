package nisargpatel.deadreckoning.storage;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import nisargpatel.deadreckoning.model.Trip;
import org.osmdroid.util.GeoPoint;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persists {@link nisargpatel.deadreckoning.model.Trip} records as a JSON array in SharedPreferences
 * and exports individual trips as CSV or GPX files to {@code getExternalFilesDir/DeadReckoning/Exports}.
 */
public class TripStorage {
    
    private static final String PREFS_NAME = "TripStorage";
    private static final String TRIPS_KEY = "trips";
    private static final String EXPORT_FOLDER = "DeadReckoning/Exports";
    
    private final SharedPreferences prefs;
    private final Context context;
    private final Gson gson;
    
    public TripStorage(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
    }
    
    /** Upserts a trip: removes any existing entry with the same ID, then appends the new one. */
    public void saveTrip(Trip trip) {
        List<Trip> trips = getAllTrips();
        
        // Remove existing trip with same ID if exists
        trips.removeIf(t -> t.getId().equals(trip.getId()));
        
        trips.add(trip);
        
        String json = gson.toJson(trips);
        prefs.edit().putString(TRIPS_KEY, json).apply();
    }
    
    /** @return Mutable list of all persisted trips, or an empty list if none exist. */
    public List<Trip> getAllTrips() {
        String json = prefs.getString(TRIPS_KEY, "[]");
        Type type = new TypeToken<ArrayList<Trip>>(){}.getType();
        List<Trip> trips = gson.fromJson(json, type);
        return trips != null ? trips : new ArrayList<>();
    }
    
    /** @return The trip with the given {@code id}, or null if not found. */
    public Trip getTrip(String id) {
        List<Trip> trips = getAllTrips();
        for (Trip trip : trips) {
            if (trip.getId().equals(id)) {
                return trip;
            }
        }
        return null;
    }
    
    /** Removes the trip with the given {@code id} from the persisted list. */
    public void deleteTrip(String id) {
        List<Trip> trips = getAllTrips();
        trips.removeIf(t -> t.getId().equals(id));
        
        String json = gson.toJson(trips);
        prefs.edit().putString(TRIPS_KEY, json).apply();
    }
    
    /** Deletes all trips from SharedPreferences. */
    public void clearAllTrips() {
        prefs.edit().remove(TRIPS_KEY).apply();
    }
    
    /**
     * Writes the trip as a CSV file to the exports folder.
     *
     * @return Absolute path of the created CSV file.
     * @throws IOException if the directory cannot be created or the file cannot be written.
     */
    public String exportToCSV(Trip trip) throws IOException {
        File exportDir = new File(context.getExternalFilesDir(null), EXPORT_FOLDER);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String fileName = "trip_" + sdf.format(new Date(trip.getStartTime())) + ".csv";
        File file = new File(exportDir, fileName);
        
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(trip.toCSV());
        }
        
        return file.getAbsolutePath();
    }
    
    /**
     * Writes the trip as a GPX file to the exports folder.
     *
     * @return Absolute path of the created GPX file.
     * @throws IOException if the directory cannot be created or the file cannot be written.
     */
    public String exportToGPX(Trip trip) throws IOException {
        File exportDir = new File(context.getExternalFilesDir(null), EXPORT_FOLDER);
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String fileName = "trip_" + sdf.format(new Date(trip.getStartTime())) + ".gpx";
        File file = new File(exportDir, fileName);
        
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(trip.toGPX());
        }
        
        return file.getAbsolutePath();
    }
    
    /**
     * Returns the {@code count} most recent trips sorted by start time, newest first.
     *
     * @param count Maximum number of trips to return.
     */
    public List<Trip> getRecentTrips(int count) {
        List<Trip> trips = getAllTrips();
        trips.sort((t1, t2) -> Long.compare(t2.getStartTime(), t1.getStartTime()));
        
        return trips.subList(0, Math.min(count, trips.size()));
    }
    
    /** @return Total number of persisted trips. */
    public int getTotalTrips() {
        return getAllTrips().size();
    }
    
    /** @return Sum of {@link nisargpatel.deadreckoning.model.Trip#getTotalDistance()} across all trips (m). */
    public double getTotalDistance() {
        double total = 0;
        for (Trip trip : getAllTrips()) {
            total += trip.getTotalDistance();
        }
        return total;
    }
    
    /** @return Sum of step counts across all stored trips. */
    public int getTotalSteps() {
        int total = 0;
        for (Trip trip : getAllTrips()) {
            total += trip.getTotalSteps();
        }
        return total;
    }
}
