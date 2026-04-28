package nisargpatel.deadreckoning.model;

/**
 * Records a single turn detected during dead-reckoning navigation.
 * Stores the turn classification, magnitude, position, and the cumulative
 * step count at the moment of detection.
 */
public class TurnEvent {

    /** Classification of the detected turn based on heading change magnitude. */
    public enum TurnType {
        LEFT,
        RIGHT,
        SLIGHT_LEFT,
        SLIGHT_RIGHT,
        UTURN
    }

    private TurnType type;
    /** Absolute heading change that triggered this event, in degrees (°). */
    private double headingChange;
    /** Cumulative step count at the moment this turn was detected. */
    private int stepCount;
    /** Wall-clock time of detection as Unix epoch milliseconds (ms). */
    private long timestamp;
    /** Latitude of the turn location in decimal degrees (WGS-84). */
    private double latitude;
    /** Longitude of the turn location in decimal degrees (WGS-84). */
    private double longitude;

    /**
     * @param type         Classified turn type.
     * @param headingChange Heading change that caused this turn in degrees (°);
     *                     stored as absolute value.
     * @param stepCount    Cumulative step count at detection time.
     * @param lat          Latitude of the turn location in decimal degrees (WGS-84).
     * @param lon          Longitude of the turn location in decimal degrees (WGS-84).
     */
    public TurnEvent(TurnType type, double headingChange, int stepCount, double lat, double lon) {
        this.type = type;
        this.headingChange = Math.abs(headingChange);
        this.stepCount = stepCount;
        this.timestamp = System.currentTimeMillis();
        this.latitude = lat;
        this.longitude = lon;
    }

    /**
     * Maps a signed heading delta to the appropriate {@link TurnType}.
     * Thresholds (in degrees °):
     * <ul>
     *   <li>|Δ| in (150, 210) → U-turn</li>
     *   <li>|Δ| in (45, 135)  → left / right</li>
     *   <li>|Δ| in (15, 45)   → slight left / right</li>
     *   <li>otherwise         → {@code null} (no significant turn)</li>
     * </ul>
     * Positive {@code headingDelta} is treated as left; negative as right,
     * matching the convention where heading increases counter-clockwise.
     *
     * @param headingDelta Signed heading change in degrees (°).
     * @return Corresponding {@link TurnType}, or {@code null} if below threshold.
     */
    public static TurnType determineTurnType(double headingDelta) {
        double absDelta = Math.abs(headingDelta);

        if (absDelta > 150 && absDelta < 210) {
            return TurnType.UTURN;
        } else if (absDelta > 45 && absDelta < 135) {
            return headingDelta > 0 ? TurnType.LEFT : TurnType.RIGHT;
        } else if (absDelta > 15 && absDelta < 45) {
            return headingDelta > 0 ? TurnType.SLIGHT_LEFT : TurnType.SLIGHT_RIGHT;
        } else {
            return null;
        }
    }

    /** @return Localised French label for the turn type (e.g. "Gauche", "Droite"). */
    public String getType() {
        switch (type) {
            case LEFT: return "Gauche";
            case RIGHT: return "Droite";
            case SLIGHT_LEFT: return "Leger gauche";
            case SLIGHT_RIGHT: return "Leger droite";
            case UTURN: return "Demi-tour";
            default: return "Inconnu";
        }
    }
    
    /** @return Unicode arrow emoji representing the turn direction. */
    public String getTurnSymbol() {
        switch (type) {
            case LEFT: return "⬅";
            case RIGHT: return "➡";
            case SLIGHT_LEFT: return "↰";
            case SLIGHT_RIGHT: return "↱";
            case UTURN: return "🔄";
            default: return "";
        }
    }
    
    // Getters

    /** @return Turn classification enum value. */
    public TurnType getTypeEnum() { return type; }

    /** @return Absolute heading change that triggered this event, in degrees (°). */
    public double getHeadingChange() { return headingChange; }

    /** @return Cumulative step count at the moment this turn was detected. */
    public int getStepCount() { return stepCount; }

    /** @return Detection time as Unix epoch milliseconds (ms). */
    public long getTimestamp() { return timestamp; }

    /** @return Latitude of the turn location in decimal degrees (WGS-84). */
    public double getLatitude() { return latitude; }

    /** @return Longitude of the turn location in decimal degrees (WGS-84). */
    public double getLongitude() { return longitude; }
}
