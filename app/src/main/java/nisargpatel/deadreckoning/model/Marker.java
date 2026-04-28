package nisargpatel.deadreckoning.model;

import java.util.UUID;

/**
 * A user-placed geographic point of interest on the map.
 * Each marker carries a WGS-84 position, an optional text label,
 * an emoji icon, and a creation timestamp.
 */
public class Marker {
    private String id;
    /** Latitude in decimal degrees (WGS-84). */
    private double latitude;
    /** Longitude in decimal degrees (WGS-84). */
    private double longitude;
    private String label;
    private String emoji;
    /** Creation time as Unix epoch milliseconds (ms). */
    private long createdAt;

    /**
     * Creates a marker with a new random UUID and the current wall-clock time.
     * Position, label, and emoji must be set separately.
     */
    public Marker() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * @param latitude  Latitude in decimal degrees (WGS-84).
     * @param longitude Longitude in decimal degrees (WGS-84).
     * @param emoji     Unicode emoji string used as the map pin icon.
     */
    public Marker(double latitude, double longitude, String emoji) {
        this();
        this.latitude = latitude;
        this.longitude = longitude;
        this.emoji = emoji;
    }

    /**
     * @param latitude  Latitude in decimal degrees (WGS-84).
     * @param longitude Longitude in decimal degrees (WGS-84).
     * @param emoji     Unicode emoji string used as the map pin icon.
     * @param label     Optional text label shown beneath the pin.
     */
    public Marker(double latitude, double longitude, String emoji, String label) {
        this(latitude, longitude, emoji);
        this.label = label;
    }

    /** @return Unique marker identifier (UUID string). */
    public String getId() {
        return id;
    }

    /** @param id Unique marker identifier (UUID string). */
    public void setId(String id) {
        this.id = id;
    }

    /** @return Latitude in decimal degrees (WGS-84). */
    public double getLatitude() {
        return latitude;
    }

    /** @param latitude Latitude in decimal degrees (WGS-84). */
    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    /** @return Longitude in decimal degrees (WGS-84). */
    public double getLongitude() {
        return longitude;
    }

    /** @param longitude Longitude in decimal degrees (WGS-84). */
    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    /** @return Optional text label shown beneath the pin, or {@code null} if unset. */
    public String getLabel() {
        return label;
    }

    /** @param label Text label shown beneath the pin. Pass {@code null} to clear. */
    public void setLabel(String label) {
        this.label = label;
    }

    /** @return Unicode emoji string used as the map pin icon. */
    public String getEmoji() {
        return emoji;
    }

    /** @param emoji Unicode emoji string used as the map pin icon. */
    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    /** @return Creation time as Unix epoch milliseconds (ms). */
    public long getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt Creation time as Unix epoch milliseconds (ms). */
    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
