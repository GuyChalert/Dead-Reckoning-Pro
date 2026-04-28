package nisargpatel.deadreckoning.model;

import org.osmdroid.util.GeoPoint;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Represents a single dead-reckoning tracking session (trip).
 * Stores the path as an ordered list of {@link GeoPoint}s with per-point
 * wall-clock timestamps, aggregate statistics, and detected {@link TurnEvent}s.
 * Supports export to CSV and GPX formats.
 */
public class Trip {

    private String id;
    private String name;
    /** Trip start time as Unix epoch milliseconds (ms). */
    private long startTime;
    /** Trip end time as Unix epoch milliseconds (ms). Set by {@link #finish()}. */
    private long endTime;
    /** Cumulative path length in meters (m). */
    private double totalDistance;
    /** Total step count for the trip. */
    private int totalSteps;
    /** Latitude of the first path point in decimal degrees (WGS-84). */
    private double startLatitude;
    /** Longitude of the first path point in decimal degrees (WGS-84). */
    private double startLongitude;
    /** Latitude of the last path point in decimal degrees (WGS-84). */
    private double endLatitude;
    /** Longitude of the last path point in decimal degrees (WGS-84). */
    private double endLongitude;
    private List<GeoPoint> pathPoints;
    /** Wall-clock timestamps (ms) parallel to {@link #pathPoints}. */
    private List<Long> pathTimestamps;
    private List<TurnEvent> turnEvents;
    private boolean isExported;

    /**
     * Creates a new trip with a random UUID, empty path lists, and
     * {@code startTime} set to the current wall-clock time.
     */
    public Trip() {
        this.id = UUID.randomUUID().toString();
        this.pathPoints = new ArrayList<>();
        this.pathTimestamps = new ArrayList<>();
        this.turnEvents = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
    }

    /**
     * @param name Human-readable trip name shown in history.
     */
    public Trip(String name) {
        this();
        this.name = name;
    }

    /**
     * Lazily initialises list fields that may be null after deserialization
     * from older JSON schemas that lacked these fields.
     */
    private void ensureListsInitialized() {
        if (pathPoints == null) pathPoints = new ArrayList<>();
        if (pathTimestamps == null) pathTimestamps = new ArrayList<>();
        if (turnEvents == null) turnEvents = new ArrayList<>();
    }

    /**
     * Appends a position fix to the path, recording the current wall-clock
     * time alongside it.
     *
     * @param point WGS-84 position to append; ignored if {@code null}.
     */
    public void addPoint(GeoPoint point) {
        ensureListsInitialized();
        if (point != null) {
            pathPoints.add(point);
            pathTimestamps.add(System.currentTimeMillis());
        }
    }

    /**
     * Records a detected turn event.
     *
     * @param event Turn event to record; ignored if {@code null}.
     */
    public void addTurnEvent(TurnEvent event) {
        ensureListsInitialized();
        if (event != null) {
            turnEvents.add(event);
        }
    }

    /**
     * Finalises the trip: sets {@code endTime} to now and copies the first/last
     * path-point coordinates into {@code startLatitude/Longitude} and
     * {@code endLatitude/Longitude}.
     */
    public void finish() {
        ensureListsInitialized();
        this.endTime = System.currentTimeMillis();
        if (!pathPoints.isEmpty()) {
            GeoPoint first = pathPoints.get(0);
            GeoPoint last = pathPoints.get(pathPoints.size() - 1);
            this.startLatitude = first.getLatitude();
            this.startLongitude = first.getLongitude();
            this.endLatitude = last.getLatitude();
            this.endLongitude = last.getLongitude();
        }
    }

    /**
     * Returns the elapsed trip duration.
     * If the trip is still ongoing, returns the time since {@code startTime}.
     *
     * @return Duration in milliseconds (ms), or 0 if no start time is recorded.
     */
    public long getDuration() {
        if (endTime > startTime) {
            return endTime - startTime;
        } else if (startTime > 0) {
            return System.currentTimeMillis() - startTime;
        }
        return 0;
    }

    /**
     * Returns a human-readable duration string (e.g. "1h 23m 45s").
     *
     * @return Formatted duration derived from {@link #getDuration()}.
     */
    public String getFormattedDuration() {
        long duration = getDuration();
        long seconds = duration / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format(Locale.US, "%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format(Locale.US, "%dm %ds", minutes, seconds % 60);
        } else {
            return String.format(Locale.US, "%ds", seconds);
        }
    }

    /**
     * Returns the wall-clock timestamp for a path point by index.
     * Falls back to {@code startTime} for legacy data that predates per-point timestamps.
     *
     * @param index Zero-based index into {@link #pathPoints}.
     * @return Timestamp as Unix epoch milliseconds (ms).
     */
    private long getTimestampForPoint(int index) {
        if (pathTimestamps != null && index < pathTimestamps.size()) {
            return pathTimestamps.get(index);
        }
        // Fallback for old data: estimate based on start time
        return startTime;
    }

    /**
     * Serialises the trip to CSV format with two sections: trip summary and
     * per-point path data (latitude °, longitude °, altitude m, UTC time),
     * followed by turn events.
     *
     * @return Multi-line CSV string.
     */
    public String toCSV() {
        ensureListsInitialized();
        StringBuilder sb = new StringBuilder();
        sb.append("Trip ID,Name,Start Time,End Time,Duration,Distance (m),Steps,Start Lat,Start Lon,End Lat,End Lon\n");
        sb.append(String.format(Locale.US, "%s,%s,%d,%d,%s,%.2f,%d,%.6f,%.6f,%.6f,%.6f\n",
            id, name != null ? name : "", startTime, endTime, getFormattedDuration(), totalDistance, totalSteps,
            startLatitude, startLongitude, endLatitude, endLongitude));
        
        sb.append("\nPath Points\n");
        sb.append("Latitude,Longitude,Altitude,Time\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        for (int i = 0; i < pathPoints.size(); i++) {
            GeoPoint point = pathPoints.get(i);
            long time = getTimestampForPoint(i);
            double altitude = !Double.isNaN(point.getAltitude()) ? point.getAltitude() : 0;
            sb.append(String.format(Locale.US, "%.6f,%.6f,%.2f,%s\n", 
                point.getLatitude(), point.getLongitude(), altitude, sdf.format(new Date(time))));
        }
        
        sb.append("\nTurn Events\n");
        sb.append("Turn Type,Heading Change,Step Count\n");
        for (TurnEvent event : turnEvents) {
            sb.append(String.format(Locale.US, "%s,%.1f,%d\n", 
                event.getType(), event.getHeadingChange(), event.getStepCount()));
        }
        
        return sb.toString();
    }
    
    /**
     * Serialises the trip to GPX 1.1 format with a single track segment.
     * Each track point includes latitude (°), longitude (°), elevation (m),
     * and a UTC ISO-8601 timestamp.
     *
     * @return GPX XML string.
     */
    public String toGPX() {
        ensureListsInitialized();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"DeadReckoningPro\" \n");
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\" \n");
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" \n");
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        
        sb.append("  <metadata>\n");
        if (name != null && !name.isEmpty()) {
            sb.append(String.format("    <name>%s</name>\n", escapeXml(name)));
        }
        sb.append(String.format("    <time>%s</time>\n", sdf.format(new Date(startTime))));
        sb.append("  </metadata>\n");
        
        sb.append("  <trk>\n");
        String trkName = name != null && !name.isEmpty() ? name : "Trip " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(startTime));
        sb.append(String.format("    <name>%s</name>\n", escapeXml(trkName)));
        sb.append("    <trkseg>\n");
        
        for (int i = 0; i < pathPoints.size(); i++) {
            GeoPoint point = pathPoints.get(i);
            long time = getTimestampForPoint(i);
            double lat = point.getLatitude();
            double lon = point.getLongitude();
            double ele = !Double.isNaN(point.getAltitude()) ? point.getAltitude() : 0.0;
            
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">\n", lat, lon));
            sb.append(String.format(Locale.US, "        <ele>%.2f</ele>\n", ele));
            sb.append(String.format("        <time>%s</time>\n", sdf.format(new Date(time))));
            sb.append("      </trkpt>\n");
        }
        
        sb.append("    </trkseg>\n");
        sb.append("  </trk>\n");
        sb.append("</gpx>");
        
        return sb.toString();
    }
    
    /**
     * Escapes the five XML special characters in {@code text}.
     *
     * @param text Input string; returns empty string if {@code null}.
     * @return XML-safe string.
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
    
    // Getters and Setters

    /** @return Unique trip identifier (UUID string). */
    public String getId() { return id; }
    /** @param id Unique trip identifier (UUID string). */
    public void setId(String id) { this.id = id; }

    /** @return Human-readable trip name, or {@code null} if unnamed. */
    public String getName() { return name; }
    /** @param name Human-readable trip name shown in history. */
    public void setName(String name) { this.name = name; }

    /** @return Trip start time as Unix epoch milliseconds (ms). */
    public long getStartTime() { return startTime; }
    /** @param startTime Trip start time as Unix epoch milliseconds (ms). */
    public void setStartTime(long startTime) { this.startTime = startTime; }

    /** @return Trip end time as Unix epoch milliseconds (ms); 0 if not yet finished. */
    public long getEndTime() { return endTime; }
    /** @param endTime Trip end time as Unix epoch milliseconds (ms). */
    public void setEndTime(long endTime) { this.endTime = endTime; }

    /** @return Cumulative path length in meters (m). */
    public double getTotalDistance() { return totalDistance; }
    /** @param totalDistance Cumulative path length in meters (m). */
    public void setTotalDistance(double totalDistance) { this.totalDistance = totalDistance; }

    /** @return Total step count recorded during this trip. */
    public int getTotalSteps() { return totalSteps; }
    /** @param totalSteps Total step count recorded during this trip. */
    public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }

    /** @return Latitude of the first path point in decimal degrees (WGS-84). */
    public double getStartLatitude() { return startLatitude; }
    /** @param lat Latitude of the first path point in decimal degrees (WGS-84). */
    public void setStartLatitude(double lat) { this.startLatitude = lat; }

    /** @return Longitude of the first path point in decimal degrees (WGS-84). */
    public double getStartLongitude() { return startLongitude; }
    /** @param lon Longitude of the first path point in decimal degrees (WGS-84). */
    public void setStartLongitude(double lon) { this.startLongitude = lon; }

    /** @return Latitude of the last path point in decimal degrees (WGS-84). */
    public double getEndLatitude() { return endLatitude; }
    /** @param lat Latitude of the last path point in decimal degrees (WGS-84). */
    public void setEndLatitude(double lat) { this.endLatitude = lat; }

    /** @return Longitude of the last path point in decimal degrees (WGS-84). */
    public double getEndLongitude() { return endLongitude; }
    /** @param lon Longitude of the last path point in decimal degrees (WGS-84). */
    public void setEndLongitude(double lon) { this.endLongitude = lon; }

    /**
     * Returns the ordered list of WGS-84 path points. Initialises the list
     * lazily if it was null (e.g. after JSON deserialization of legacy data).
     *
     * @return Mutable list of {@link GeoPoint}s; never {@code null}.
     */
    public List<GeoPoint> getPathPoints() {
        ensureListsInitialized();
        return pathPoints;
    }
    /** @param pathPoints Ordered list of WGS-84 path points. */
    public void setPathPoints(List<GeoPoint> pathPoints) { this.pathPoints = pathPoints; }

    /**
     * Returns the list of turn events detected during this trip. Initialises
     * the list lazily if it was null (legacy data).
     *
     * @return Mutable list of {@link TurnEvent}s; never {@code null}.
     */
    public List<TurnEvent> getTurnEvents() {
        ensureListsInitialized();
        return turnEvents;
    }
    /** @param turnEvents List of turn events for this trip. */
    public void setTurnEvents(List<TurnEvent> turnEvents) { this.turnEvents = turnEvents; }

    /** @return {@code true} if this trip has already been exported to a file. */
    public boolean isExported() { return isExported; }
    /** @param exported {@code true} to mark the trip as exported. */
    public void setExported(boolean exported) { isExported = exported; }
}
