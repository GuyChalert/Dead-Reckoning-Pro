package nisargpatel.deadreckoning.slam;

import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.List;

/**
 * 2-D pose-graph factor graph with Levenberg-Marquardt optimizer.
 *
 * State vector: x = [x0,y0,θ0, x1,y1,θ1, ..., xN,yN,θN]  (3·N doubles)
 *
 * Node 0 is always held fixed to eliminate gauge freedom.
 * Fixed nodes (isFixed=true) are never moved by the optimizer.
 *
 * Memory note: uses dense SimpleMatrix — adequate for ≤ 500 keyframe segments
 * (the typical GPS-anchor-to-GPS-anchor stretch at 5-10 m/keyframe).
 * Replace with EJML DMatrixSparseCSC + LinearSolverSparse for full-session graphs.
 */
public class FactorGraph {

    private static final int    MAX_ITER      = 25;
    private static final double LAMBDA_INIT   = 1e-3;
    private static final double LAMBDA_UP     = 8.0;
    private static final double LAMBDA_DOWN   = 0.3;
    private static final double CONVERGE_EPS  = 1e-7;
    private static final double PIN_WEIGHT    = 1e10; // large diagonal to fix node 0

    final List<PoseNode>       nodes = new ArrayList<>();
    final List<ConstraintEdge> edges = new ArrayList<>();

    /** Appends a pose node to the graph. Node ID must be unique and ascending. */
    public void addNode(PoseNode n)       { nodes.add(n); }

    /** Appends a constraint edge (factor) to the graph. */
    public void addEdge(ConstraintEdge e) { edges.add(e); }

    /** @return Number of pose nodes currently in the graph. */
    public int  nodeCount()               { return nodes.size(); }

    // ------------------------------------------------------------------
    // Levenberg-Marquardt pose-graph optimization (in-place on nodes).
    // ------------------------------------------------------------------

    /**
     * Runs Levenberg-Marquardt pose-graph optimisation in-place on all non-fixed nodes.
     * Iterates up to {@value #MAX_ITER} times; stops early when the update norm falls
     * below {@value #CONVERGE_EPS}. λ is adaptively increased on cost increase and
     * decreased on cost decrease.
     */
    public void optimize() {
        if (nodes.size() < 2) return;
        final int dim = 3 * nodes.size();
        double lambda = LAMBDA_INIT;
        double prevCost = totalCost();

        for (int iter = 0; iter < MAX_ITER; iter++) {
            SimpleMatrix H = new SimpleMatrix(dim, dim);
            SimpleMatrix b = new SimpleMatrix(dim, 1);

            for (ConstraintEdge e : edges) {
                switch (e.type) {
                    case ODOMETRY:
                    case LOOP_CLOSURE:
                        accumulateOdometry(e, H, b);
                        break;
                    case GPS_ANCHOR:
                        accumulateGpsAnchor(e, H, b);
                        break;
                    case LANDMARK_DISTANCE:
                        accumulateLandmark(e, H, b);
                        break;
                }
            }

            // Pin node 0 (or first non-fixed node) to remove gauge freedom
            for (int k = 0; k < 3; k++) {
                H.set(k, k, H.get(k, k) + PIN_WEIGHT);
            }

            // Levenberg damping
            for (int i = 0; i < dim; i++) {
                H.set(i, i, H.get(i, i) + lambda);
            }

            SimpleMatrix delta;
            try {
                delta = H.solve(b.scale(-1.0));
            } catch (Exception ex) {
                lambda *= LAMBDA_UP;
                continue;
            }

            double[] backup = backupState();
            applyDelta(delta);
            double newCost = totalCost();

            if (newCost < prevCost) {
                if (delta.normF() < CONVERGE_EPS) break;
                prevCost = newCost;
                lambda *= LAMBDA_DOWN;
            } else {
                restoreState(backup);
                lambda *= LAMBDA_UP;
            }
        }
    }

    // ------------------------------------------------------------------
    // Jacobian / information accumulation helpers
    // ------------------------------------------------------------------

    /**
     * Body-frame 2-D odometry edge.
     *
     * Measurement z = (dx_b, dy_b, dθ) in body frame of node i.
     * Predicted h = (R_i^T · (p_j − p_i), θ_j − θ_i)
     *
     * Jacobian derivation (e = z − h):
     *   ∂e_pos / ∂p_i   =  R_i^T         (2×2)
     *   ∂e_pos / ∂θ_i   =  dR_i^T/dθ · (p_j − p_i)   (2×1)
     *   ∂e_pos / ∂p_j   = −R_i^T        (2×2)
     *   ∂e_θ   / ∂θ_i   = −1
     *   ∂e_θ   / ∂θ_j   =  1
     */
    private void accumulateOdometry(ConstraintEdge e, SimpleMatrix H, SimpleMatrix b) {
        PoseNode ni = nodes.get(e.fromId);
        PoseNode nj = nodes.get(e.toId);

        double ci  = Math.cos(ni.theta), si = Math.sin(ni.theta);
        double dxi = nj.x - ni.x,       dyi = nj.y - ni.y;

        // Residuals in body frame
        double ex  = e.dx     - ( ci*dxi + si*dyi);
        double ey  = e.dy     - (-si*dxi + ci*dyi);
        double eth = normalizeAngle(e.dTheta - normalizeAngle(nj.theta - ni.theta));

        // Jacobian rows for node i  (Ji, 3×3)
        //   [ci,   si,   si·dxi − ci·dyi]
        //   [−si,  ci,   ci·dxi + si·dyi]
        //   [0,    0,    −1             ]
        double[][] Ji = {
            { ci,  si,  si*dxi - ci*dyi },
            { -si, ci,  ci*dxi + si*dyi },
            {  0,   0,  -1              }
        };

        // Jacobian rows for node j  (Jj, 3×3)
        //   [−ci,  −si,  0]
        //   [ si,  −ci,  0]
        //   [  0,    0,  1]
        double[][] Jj = {
            { -ci, -si,  0 },
            {  si, -ci,  0 },
            {   0,   0,  1 }
        };

        double[] omega = { e.wPos, e.wPos, e.wTheta };
        double[] err   = { ex, ey, eth };

        int ri = 3 * e.fromId;
        int rj = 3 * e.toId;

        for (int r = 0; r < 3; r++) {
            // gradient b
            double bi = 0, bj = 0;
            for (int k = 0; k < 3; k++) {
                bi += Ji[k][r] * omega[k] * err[k];
                bj += Jj[k][r] * omega[k] * err[k];
            }
            b.set(ri + r, 0, b.get(ri + r, 0) + bi);
            b.set(rj + r, 0, b.get(rj + r, 0) + bj);

            // Hessian blocks H_ii, H_ij, H_ji, H_jj
            for (int c = 0; c < 3; c++) {
                double hii = 0, hij = 0, hji = 0, hjj = 0;
                for (int k = 0; k < 3; k++) {
                    hii += Ji[k][r] * omega[k] * Ji[k][c];
                    hij += Ji[k][r] * omega[k] * Jj[k][c];
                    hji += Jj[k][r] * omega[k] * Ji[k][c];
                    hjj += Jj[k][r] * omega[k] * Jj[k][c];
                }
                H.set(ri+r, ri+c, H.get(ri+r, ri+c) + hii);
                H.set(ri+r, rj+c, H.get(ri+r, rj+c) + hij);
                H.set(rj+r, ri+c, H.get(rj+r, ri+c) + hji);
                H.set(rj+r, rj+c, H.get(rj+r, rj+c) + hjj);
            }
        }
    }

    /**
     * GPS anchor: unary position constraint.
     * e = [x_i − gps_x, y_i − gps_y],  J = I_{2×2}
     */
    private void accumulateGpsAnchor(ConstraintEdge e, SimpleMatrix H, SimpleMatrix b) {
        PoseNode n = nodes.get(e.fromId);
        double ex = n.x - e.dx;
        double ey = n.y - e.dy;
        int ri = 3 * e.fromId;
        H.set(ri,   ri,   H.get(ri,   ri)   + e.wPos);
        H.set(ri+1, ri+1, H.get(ri+1, ri+1) + e.wPos);
        b.set(ri,   0,    b.get(ri,   0)    + e.wPos * ex);
        b.set(ri+1, 0,    b.get(ri+1, 0)    + e.wPos * ey);
    }

    /**
     * Landmark (distance marking): soft 1-D scale constraint.
     * Models the painted distance marker as a constraint on cumulative path length.
     * Approximation: constrains the node's x-coordinate to the measured distance.
     * Sufficient for nearly-straight tunnels; upgrade to full path-length
     * Jacobian (sum over preceding edges) for strongly curved routes.
     */
    private void accumulateLandmark(ConstraintEdge e, SimpleMatrix H, SimpleMatrix b) {
        PoseNode n = nodes.get(e.fromId);
        // Compute actual path length from node 0 to this node
        double pathLength = computePathLength(e.fromId);
        double ex = pathLength - e.dx;  // e.dx holds measured cumulative distance
        int ri = 3 * e.fromId;
        // Treat as a constraint on the Euclidean x of the node for scale
        H.set(ri, ri, H.get(ri, ri) + e.wPos);
        b.set(ri, 0,  b.get(ri, 0)  + e.wPos * ex);
    }

    /**
     * Computes the Euclidean cumulative path length from node 0 to {@code upToNodeId}
     * by summing consecutive ODOMETRY edge inter-node distances.
     *
     * @param upToNodeId Inclusive upper bound node ID.
     * @return Path length in meters (m).
     */
    private double computePathLength(int upToNodeId) {
        double len = 0;
        for (ConstraintEdge e : edges) {
            if (e.type != ConstraintEdge.Type.ODOMETRY) continue;
            if (e.toId > upToNodeId) break;
            PoseNode ni = nodes.get(e.fromId);
            PoseNode nj = nodes.get(e.toId);
            double dx = nj.x - ni.x, dy = nj.y - ni.y;
            len += Math.sqrt(dx*dx + dy*dy);
        }
        return len;
    }

    // ------------------------------------------------------------------
    // Cost, state backup/restore
    // ------------------------------------------------------------------

    /**
     * Computes the total weighted sum-of-squared residuals over all edges.
     * Used by the LM loop to decide whether to accept or reject an update.
     *
     * @return Scalar total cost (unitless — weighted sum of squared residuals).
     */
    double totalCost() {
        double cost = 0;
        for (ConstraintEdge e : edges) {
            switch (e.type) {
                case ODOMETRY:
                case LOOP_CLOSURE: {
                    PoseNode ni = nodes.get(e.fromId), nj = nodes.get(e.toId);
                    double ci = Math.cos(ni.theta), si = Math.sin(ni.theta);
                    double dxi = nj.x - ni.x, dyi = nj.y - ni.y;
                    double ex  = e.dx - ( ci*dxi + si*dyi);
                    double ey  = e.dy - (-si*dxi + ci*dyi);
                    double eth = normalizeAngle(e.dTheta - normalizeAngle(nj.theta - ni.theta));
                    cost += e.wPos*(ex*ex + ey*ey) + e.wTheta*(eth*eth);
                    break;
                }
                case GPS_ANCHOR: {
                    PoseNode n = nodes.get(e.fromId);
                    double ex = n.x - e.dx, ey = n.y - e.dy;
                    cost += e.wPos * (ex*ex + ey*ey);
                    break;
                }
                case LANDMARK_DISTANCE: {
                    double pathLen = computePathLength(e.fromId);
                    double ex = pathLen - e.dx;
                    cost += e.wPos * ex * ex;
                    break;
                }
            }
        }
        return cost;
    }

    /**
     * Applies the LM update vector to all non-fixed nodes in-place.
     * Heading is normalised to (−π, π] after each update.
     *
     * @param delta 3N×1 update vector in state order [x₀,y₀,θ₀, x₁,y₁,θ₁, ...].
     */
    private void applyDelta(SimpleMatrix delta) {
        for (int i = 0; i < nodes.size(); i++) {
            PoseNode n = nodes.get(i);
            if (n.isFixed) continue;
            n.x     += delta.get(3*i,   0);
            n.y     += delta.get(3*i+1, 0);
            n.theta  = normalizeAngle(n.theta + delta.get(3*i+2, 0));
        }
    }

    /**
     * Snapshots all node poses into a flat array for potential rollback.
     *
     * @return Array of length 3N with state [x₀,y₀,θ₀, x₁,y₁,θ₁, ...].
     */
    private double[] backupState() {
        double[] s = new double[nodes.size() * 3];
        for (int i = 0; i < nodes.size(); i++) {
            s[3*i]   = nodes.get(i).x;
            s[3*i+1] = nodes.get(i).y;
            s[3*i+2] = nodes.get(i).theta;
        }
        return s;
    }

    /**
     * Restores all node poses from a previously captured backup.
     *
     * @param s Flat state array from {@link #backupState()}.
     */
    private void restoreState(double[] s) {
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).x     = s[3*i];
            nodes.get(i).y     = s[3*i+1];
            nodes.get(i).theta = s[3*i+2];
        }
    }

    /**
     * Wraps an angle to (−π, π].
     *
     * @param a Angle in radians (rad).
     * @return Equivalent angle in (−π, π] in radians (rad).
     */
    static double normalizeAngle(double a) {
        while (a >  Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
