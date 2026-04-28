package nisargpatel.deadreckoning.slam;

/**
 * An edge (factor) in the pose graph.
 *
 * Binary edges (ODOMETRY, LOOP_CLOSURE): connect fromId → toId.
 *   dx, dy, dTheta are the body-frame relative motion measurement.
 *
 * Unary edges (GPS_ANCHOR, LANDMARK_DISTANCE): fromId is the node, toId = -1.
 *   GPS_ANCHOR:         dx = abs ENZ-x, dy = abs ENZ-y (world-frame position).
 *   LANDMARK_DISTANCE:  dx = cumulative path distance in metres from origin.
 *
 * Weights (wPos, wTheta) are information values (1/σ²).
 * Higher weight = stronger constraint = trusted more by the optimizer.
 */
public class ConstraintEdge {

    public enum Type { ODOMETRY, GPS_ANCHOR, LANDMARK_DISTANCE, LOOP_CLOSURE }

    public final int    fromId;
    public final int    toId;
    public final double dx;
    public final double dy;
    public final double dTheta;
    public final double wPos;
    public final double wTheta;
    public final Type   type;

    /**
     * @param fromId  Source node ID (always valid).
     * @param toId    Target node ID; -1 for unary (GPS/landmark) constraints.
     * @param dx      Measurement x-component: body-frame Δx (m) for odometry/loop,
     *                absolute ENZ-x (m) for GPS anchors,
     *                cumulative path distance (m) for landmark constraints.
     * @param dy      Measurement y-component (m); 0 for unary constraints.
     * @param dTheta  Heading change (rad); 0 for unary constraints.
     * @param wPos    Position information weight (1/σ², m⁻²).
     * @param wTheta  Heading information weight (1/σ², rad⁻²); 0 for unary constraints.
     * @param type    Edge type.
     */
    private ConstraintEdge(int fromId, int toId,
                           double dx, double dy, double dTheta,
                           double wPos, double wTheta, Type type) {
        this.fromId = fromId; this.toId   = toId;
        this.dx     = dx;     this.dy     = dy;    this.dTheta = dTheta;
        this.wPos   = wPos;   this.wTheta = wTheta;
        this.type   = type;
    }

    // ---------------------------------------------------------------
    // Factory helpers — use these instead of the raw constructor.
    // ---------------------------------------------------------------

    /**
     * Creates a binary odometry (PDR step) edge.
     *
     * @param fromId  Source pose-node ID.
     * @param toId    Target pose-node ID.
     * @param dx      Body-frame East displacement in meters (m).
     * @param dy      Body-frame North displacement in meters (m).
     * @param dTheta  Heading change in radians (rad).
     * @param wPos    Position information weight (1/σ², m⁻²).
     * @param wTheta  Heading information weight (1/σ², rad⁻²).
     */
    public static ConstraintEdge odometry(int fromId, int toId,
                                          double dx, double dy, double dTheta,
                                          double wPos, double wTheta) {
        return new ConstraintEdge(fromId, toId, dx, dy, dTheta, wPos, wTheta, Type.ODOMETRY);
    }

    /**
     * Creates a loop-closure edge between two previously visited nodes.
     *
     * @param fromId  Earlier pose-node ID.
     * @param toId    Later pose-node ID.
     * @param dx      Body-frame East displacement from fromId to toId (m).
     * @param dy      Body-frame North displacement from fromId to toId (m).
     * @param dTheta  Heading change from fromId to toId (rad).
     * @param wPos    Position information weight (1/σ², m⁻²).
     * @param wTheta  Heading information weight (1/σ², rad⁻²).
     */
    public static ConstraintEdge loopClosure(int fromId, int toId,
                                             double dx, double dy, double dTheta,
                                             double wPos, double wTheta) {
        return new ConstraintEdge(fromId, toId, dx, dy, dTheta, wPos, wTheta, Type.LOOP_CLOSURE);
    }

    /** GPS fix: world-frame absolute position of node {@code nodeId}. */
    public static ConstraintEdge gpsAnchor(int nodeId, double absX, double absY, double wPos) {
        return new ConstraintEdge(nodeId, -1, absX, absY, 0, wPos, 0, Type.GPS_ANCHOR);
    }

    /** Distance marking: measured cumulative path length at node {@code nodeId}. */
    public static ConstraintEdge landmarkDistance(int nodeId,
                                                  double cumulativeDistanceMetres,
                                                  double wPos) {
        return new ConstraintEdge(nodeId, -1, cumulativeDistanceMetres, 0, 0, wPos, 0,
                Type.LANDMARK_DISTANCE);
    }
}
