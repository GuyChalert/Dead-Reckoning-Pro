package nisargpatel.deadreckoning.slam;

/**
 * A node in the 2-D pose graph.
 *
 * Coordinate frame: ENZ (East-North-Up) local Cartesian.
 *   x = East  (metres from origin GPS)
 *   y = North (metres from origin GPS)
 *   theta = heading in radians, 0 = North, clockwise positive
 *
 * Fixed nodes (GPS anchors, first node) are excluded from LM updates.
 */
public class PoseNode {

    public final int  id;
    public double     x;
    public double     y;
    public double     theta;
    public final long timestamp;
    public boolean    isFixed;

    /**
     * @param id        Unique node index (0-based, assigned by {@link GraphSlamEngine}).
     * @param x         East displacement from ENZ origin in meters (m).
     * @param y         North displacement from ENZ origin in meters (m).
     * @param theta     Heading in radians (rad); 0 = North, clockwise positive.
     * @param timestamp Wall-clock creation time in nanoseconds (ns).
     */
    public PoseNode(int id, double x, double y, double theta, long timestamp) {
        this.id        = id;
        this.x         = x;
        this.y         = y;
        this.theta     = theta;
        this.timestamp = timestamp;
        this.isFixed   = false;
    }
}
