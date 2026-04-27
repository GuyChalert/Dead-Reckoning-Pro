package nisargpatel.deadreckoning.slam;

import android.location.Location;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level Graph-SLAM interface for underground tunnel mapping.
 *
 * Coordinate system: local ENZ (East-North-Up) Cartesian, origin = first GPS fix.
 *   x = East  (metres)
 *   y = North (metres)
 *   theta = heading in radians, 0 = North, CW positive (matches compass azimuth)
 *
 * Usage:
 *   1. Call addGpsAnchor() at tunnel entrance → sets origin, fixes node 0.
 *   2. For every PDR step call addOdometryStep(dx_body, dy_body, dTheta).
 *   3. At distance markings call addLandmarkDistance(measured_metres).
 *   4. At intermediate GPS call addGpsAnchor() again.
 *   5. Call optimize() to run LM and retrieve getCorrectedPath().
 */
public class GraphSlamEngine {

    // Information weights — tune based on sensor quality
    private static final double W_ODO_POS   = 10.0;   // PDR position (m^-2)
    private static final double W_ODO_THETA = 5.0;    // PDR heading  (rad^-2)
    private static final double W_GPS       = 500.0;  // GPS anchor  (high trust)
    private static final double W_LANDMARK  = 200.0;  // Painted distance mark

    // Create new keyframe node every N odometry steps
    private static final int    STEPS_PER_NODE      = 10;
    // Run optimization every M new nodes
    private static final int    OPTIMIZE_EVERY_NODES = 50;

    private final FactorGraph graph = new FactorGraph();
    private int nodeIdCounter = 0;

    // Accumulated body-frame displacement between keyframes
    private double accumDx = 0, accumDy = 0, accumDTheta = 0;
    private int    stepsAccum = 0;

    // Origin GPS for ENZ conversion
    private double originLat = 0, originLon = 0;
    private boolean hasOrigin = false;

    // Total path length in metres (used for landmark scaling)
    private double totalPathMetres = 0;

    // ------------------------------------------------------------------
    // Primary API
    // ------------------------------------------------------------------

    /**
     * Add a GPS anchor.  First call sets the ENZ origin and fixes node 0.
     * Subsequent calls add a GPS_ANCHOR constraint on the most recent keyframe.
     *
     * @param location Android Location with lat/lon (accuracy < 20 m recommended)
     */
    public synchronized void addGpsAnchor(Location location) {
        if (location == null) return;
        double lat = location.getLatitude();
        double lon = location.getLongitude();

        if (!hasOrigin) {
            originLat = lat;
            originLon = lon;
            hasOrigin = true;
            PoseNode origin = new PoseNode(nodeIdCounter++, 0, 0, 0, System.nanoTime());
            origin.isFixed = true;
            graph.addNode(origin);
            return;
        }

        // Flush pending odometry into graph before anchoring
        flushAccumulated();

        double[] enz = gpsToEnz(lat, lon);
        int lastId = graph.nodeCount() - 1;
        graph.addEdge(ConstraintEdge.gpsAnchor(lastId, enz[0], enz[1], W_GPS));
        graph.optimize();
    }

    /**
     * Feed one PDR step (body-frame incremental motion).
     *
     * @param dxBody  displacement east  in body frame (metres)
     * @param dyBody  displacement north in body frame (metres)
     * @param dTheta  heading change (radians)
     */
    public synchronized void addOdometryStep(double dxBody, double dyBody, double dTheta) {
        accumDx     += dxBody;
        accumDy     += dyBody;
        accumDTheta += dTheta;
        stepsAccum++;
        totalPathMetres += Math.sqrt(dxBody*dxBody + dyBody*dyBody);

        if (stepsAccum >= STEPS_PER_NODE) {
            flushAccumulated();
        }
    }

    /**
     * Register a painted tunnel distance marker.
     * Adds a landmark constraint that pins cumulative path length to the marking.
     *
     * @param measuredMetres value on the wall (e.g. "KM 12" → 12000.0)
     */
    public synchronized void addLandmarkDistance(double measuredMetres) {
        flushAccumulated();
        int lastId = graph.nodeCount() - 1;
        if (lastId < 0) return;
        graph.addEdge(ConstraintEdge.landmarkDistance(lastId, measuredMetres, W_LANDMARK));
        graph.optimize();
    }

    /**
     * Add a loop-closure constraint between two previously visited positions.
     *
     * @param fromNodeId  earlier pose node id
     * @param toNodeId    current pose node id (usually last node)
     * @param dxBody      body-frame dx from from→to  (metres)
     * @param dyBody      body-frame dy from from→to  (metres)
     * @param dTheta      heading change from→to      (radians)
     */
    public synchronized void addLoopClosure(int fromNodeId, int toNodeId,
                                            double dxBody, double dyBody, double dTheta) {
        graph.addEdge(ConstraintEdge.loopClosure(
                fromNodeId, toNodeId, dxBody, dyBody, dTheta,
                W_GPS, W_ODO_THETA));
        graph.optimize();
    }

    /**
     * Explicit optimization call (caller may trigger at session end or at GPS fixes).
     */
    public synchronized void optimize() {
        flushAccumulated();
        graph.optimize();
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** Optimized path as GeoPoints (requires hasOrigin). */
    public synchronized List<GeoPoint> getCorrectedPath() {
        List<GeoPoint> pts = new ArrayList<>();
        if (!hasOrigin) return pts;
        for (PoseNode n : graph.nodes) {
            double[] ll = enzToGps(n.x, n.y);
            pts.add(new GeoPoint(ll[0], ll[1]));
        }
        return pts;
    }

    public synchronized double getCurrentX() {
        return graph.nodeCount() > 0 ? graph.nodes.get(graph.nodeCount()-1).x : 0;
    }

    public synchronized double getCurrentY() {
        return graph.nodeCount() > 0 ? graph.nodes.get(graph.nodeCount()-1).y : 0;
    }

    public synchronized double getCurrentHeading() {
        return graph.nodeCount() > 0 ? graph.nodes.get(graph.nodeCount()-1).theta : 0;
    }

    public synchronized double getTotalPathMetres() { return totalPathMetres; }

    public synchronized int getNodeCount() { return graph.nodeCount(); }

    public boolean hasOrigin() { return hasOrigin; }

    /** Unmodifiable view of pose nodes (for export / loop-closure detection). */
    public synchronized java.util.List<PoseNode> getNodes() {
        return java.util.Collections.unmodifiableList(graph.nodes);
    }

    /** Public ENZ→GPS conversion (needed by exporter). */
    public double[] enzToGpsPublic(double x, double y) {
        return enzToGps(x, y);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private void flushAccumulated() {
        if (stepsAccum == 0 || graph.nodeCount() == 0) return;

        PoseNode last = graph.nodes.get(graph.nodeCount() - 1);
        double ci = Math.cos(last.theta), si = Math.sin(last.theta);
        // Body frame → world frame
        double worldDx = ci*accumDx - si*accumDy;
        double worldDy = si*accumDx + ci*accumDy;

        PoseNode newNode = new PoseNode(
                nodeIdCounter++,
                last.x + worldDx,
                last.y + worldDy,
                FactorGraph.normalizeAngle(last.theta + accumDTheta),
                System.nanoTime()
        );
        graph.addNode(newNode);
        graph.addEdge(ConstraintEdge.odometry(
                last.id, newNode.id,
                accumDx, accumDy, accumDTheta,
                W_ODO_POS, W_ODO_THETA
        ));

        accumDx = accumDy = accumDTheta = 0;
        stepsAccum = 0;

        if (nodeIdCounter % OPTIMIZE_EVERY_NODES == 0) {
            graph.optimize();
        }
    }

    /** GPS (lat, lon) → local ENZ (x=east, y=north) in metres. */
    private double[] gpsToEnz(double lat, double lon) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(lat - originLat);
        double dLon = Math.toRadians(lon - originLon);
        double cosLat = Math.cos(Math.toRadians(originLat));
        double x = R * dLon * cosLat;
        double y = R * dLat;
        return new double[]{x, y};
    }

    /** Local ENZ → GPS (lat, lon). */
    private double[] enzToGps(double x, double y) {
        final double R = 6_371_000.0;
        double cosLat = Math.cos(Math.toRadians(originLat));
        double lat = originLat + Math.toDegrees(y / R);
        double lon = originLon + Math.toDegrees(x / (R * cosLat));
        return new double[]{lat, lon};
    }
}
