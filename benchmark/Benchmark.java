import java.util.Arrays;

public class Benchmark {

    // ── Bug fix: matrixRotate now actually applies rotation each iteration ──
    // Original bug: mat[] was identity and never updated, so result was just
    // sum of 2*cos(angle*i) — trig bench, not matrix rotation.
    static double matrixRotate(int iters) {
        double m00 = 1, m01 = 0;
        double m10 = 0, m11 = 1;
        double angle = 0.01;
        double result = 0;
        for (int i = 0; i < iters; i++) {
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            double n00 = m00 * c - m01 * s;
            double n01 = m00 * s + m01 * c;
            double n10 = m10 * c - m11 * s;
            double n11 = m10 * s + m11 * c;
            m00 = n00; m01 = n01;
            m10 = n10; m11 = n11;
            result += m00 + m11; // trace (checksum, prevents DCE)
        }
        return result;
    }

    static double stepIntegration(int steps) {
        double x = 0, y = 0, heading = 0;
        double stepLen = 0.75;
        for (int i = 0; i < steps; i++) {
            heading += Math.toRadians(1.5);
            x += stepLen * Math.cos(heading);
            y += stepLen * Math.sin(heading);
        }
        return Math.sqrt(x * x + y * y);
    }

    // ── Bug fix: return non-trivial checksum (prevents DCE); was returning iters ──
    static double sortBench(int iters) {
        double[] arr = new double[1000];
        double checksum = 0;
        for (int i = 0; i < iters; i++) {
            for (int j = 0; j < arr.length; j++)
                arr[j] = Math.sin(j * 0.01 + i);
            Arrays.sort(arr);
            checksum += arr[arr.length / 4]; // 25th-percentile, non-zero
        }
        return checksum;
    }

    // ── No-GPS / Steel Environment Simulation ──
    //
    // Scenario: 4-leg rectangle walk (N→E→S→W), should close at origin.
    // Steel env = no magnetometer usable → gyro-only heading.
    // Gyro bias drifts thermally (warm enclosed space, phone temperature rise).
    // ZUPT = Zero-velocity Update: at each corner the person pauses ~0.5 s;
    //   gyro reads only bias → cheap online bias correction.
    //
    // RNG-divergence fix: both runs use a deterministic noise function keyed on
    // absolute sample index so ZUPT/no-ZUPT see identical noise sequences.
    // trueBias: the actual hardware bias, unknown to the algorithm, drifts thermally.
    // estBias:  algorithm's estimate (initialized at GYRO_BIAS_0).
    //   No-ZUPT: estBias never updates → error = (trueBias - estBias) grows with drift.
    //   ZUPT:    at each corner the gyro reads ~trueBias; EMA pulls estBias toward it.
    // The gyro reading fed to heading integration is corrected by estBias, not trueBias.
    static double[] noGpsSteelSim(int stepsPerLeg, boolean useZupt) {
        final double GYRO_BIAS_0     = 0.015;  // rad/s initial bias (realistic cheap IMU)
        final double GYRO_BIAS_DRIFT = 1.5e-5; // rad/s per sample (thermal ~1.5°/min @ 50 Hz)
        final double WEINBERG_K      = 0.42;
        final int    SAMPLES         = 30;      // sensor samples per step (50 Hz, ~1.7 Hz cadence)
        final double DT              = 1.0 / 50.0;
        final double ZUPT_ALPHA      = 0.005;  // matches app's GyroscopeBias.ONLINE_ALPHA

        double heading  = 0;
        double x = 0, y = 0;
        double trueBias = GYRO_BIAS_0; // drifts as phone warms up — unknown to algorithm
        double estBias  = GYRO_BIAS_0; // algorithm's estimate, corrected only by ZUPT
        double totalDist = 0;

        // Kalman filter (matches KalmanFilter.java: q=0.005, r=0.1)
        double kQ = 0.005, kR = 0.1, kX = 0, kP = 1;

        double wMax = 0, wMin = Double.MAX_VALUE, stride = 0.75;

        int sampleIdx = 0;

        for (int leg = 0; leg < 4; leg++) {

            if (leg > 0) {
                // Right 90° turn. heading -= gyroZ*dt; clockwise → gyroZ negative.
                double turnRate = -(Math.PI / 2) / (10 * DT);
                for (int s = 0; s < 10; s++) {
                    double measured = turnRate + trueBias + noise(sampleIdx++, 0.003);
                    heading -= (measured - estBias) * DT; // algorithm subtracts its estimate
                    heading   = wrapRad(heading);
                    trueBias += GYRO_BIAS_DRIFT;
                }

                // Stationary pause at corner: gyro reads trueBias + tiny noise.
                // Both branches consume same sampleIdx slots → noise stays in sync.
                for (int s = 0; s < 25; s++) {
                    double observed = trueBias + noise(sampleIdx++, 0.001);
                    if (useZupt) {
                        // EMA toward observation (observed ≈ trueBias) — tracks drift
                        estBias = (1 - ZUPT_ALPHA) * estBias + ZUPT_ALPHA * observed;
                    }
                    trueBias += GYRO_BIAS_DRIFT;
                }
            }

            for (int step = 0; step < stepsPerLeg; step++) {
                wMax = 0; wMin = Double.MAX_VALUE;

                for (int s = 0; s < SAMPLES; s++) {
                    double phase = (s / (double) SAMPLES) * 2 * Math.PI;
                    double accel = Math.abs(2.5 * Math.sin(phase) + 0.8 * Math.sin(2 * phase)
                                           + noise(sampleIdx++, 0.15));
                    kP += kQ;
                    double gain = kP / (kP + kR);
                    kX += gain * (accel - kX);
                    kP *= (1 - gain);
                    double filtered = kX;

                    if (filtered > wMax) wMax = filtered;
                    if (filtered < wMin) wMin = filtered;

                    double measured = noise(sampleIdx++, 0.005) + trueBias;
                    heading -= (measured - estBias) * DT; // residual = trueBias - estBias
                    heading  = wrapRad(heading);
                    trueBias += GYRO_BIAS_DRIFT;
                }

                double range = wMax - wMin;
                if (range > 0.1)
                    stride = Math.max(0.40, Math.min(1.10, WEINBERG_K * Math.pow(range, 0.25)));

                x += stride * Math.sin(heading);
                y += stride * Math.cos(heading);
                totalDist += stride;
            }
        }

        double posErr = Math.sqrt(x * x + y * y);
        return new double[]{posErr, x, y, Math.toDegrees(heading), totalDist};
    }

    // Deterministic noise indexed by absolute sample position.
    // No shared mutable state → both sim runs see the same values at every index.
    private static double noise(int idx, double scale) {
        return Math.sin(idx * 2.399963 + idx * (long) idx * 0.000023) * scale;
    }

    private static double wrapRad(double r) {
        while (r >  Math.PI) r -= 2 * Math.PI;
        while (r < -Math.PI) r += 2 * Math.PI;
        return r;
    }

    public static void main(String[] args) {
        int WARMUP = 500;
        int RUNS   = 100_000;
        int SORT_R = 500;

        // ── Bug fix: sortBench was not warmed up ──
        stepIntegration(WARMUP);
        matrixRotate(WARMUP);
        sortBench(50);

        System.out.println("=== JIT Benchmark ===");

        long t0 = System.nanoTime();
        double r1 = stepIntegration(RUNS);
        long t1 = System.nanoTime();
        System.out.printf("Step integration (%,d steps):  %.2f ms  result=%.4f%n",
            RUNS, (t1 - t0) / 1e6, r1);

        long t2 = System.nanoTime();
        double r2 = matrixRotate(RUNS);
        long t3 = System.nanoTime();
        System.out.printf("Matrix rotation  (%,d iters):  %.2f ms  result=%.4f%n",
            RUNS, (t3 - t2) / 1e6, r2);

        long t4 = System.nanoTime();
        double r3 = sortBench(SORT_R);
        long t5 = System.nanoTime();
        System.out.printf("Array sort       (%,d iters):  %.2f ms  checksum=%.4f%n",
            SORT_R, (t5 - t4) / 1e6, r3);

        System.out.printf("Benchmark total: %.2f ms%n", (t5 - t0) / 1e6);

        // ── No-GPS / Steel Environment Simulation ──
        System.out.println("\n=== No-GPS / Steel Environment Simulation ===");
        System.out.println("Path:   4-leg rectangle (20 steps/leg, 90° right turn per corner)");
        System.out.println("Env:    gyro-only heading (steel = no magnetometer), no GPS, thermal bias drift");
        System.out.println("ZUPT:   0.5 s stationary pause at each corner → online bias correction");
        System.out.println("Expect: ZUPT reduces position error vs no-ZUPT (same noise both runs)");
        System.out.println();

        long ts1 = System.nanoTime();
        double[] noZupt = noGpsSteelSim(20, false);
        long ts2 = System.nanoTime();

        long ts3 = System.nanoTime();
        double[] zupt = noGpsSteelSim(20, true);
        long ts4 = System.nanoTime();

        System.out.printf("WITHOUT ZUPT: pos_error=%5.2f m  end=(%6.2f,%6.2f)  hdg_drift=%7.2f°  dist=%.2f m  [%.2f ms]%n",
            noZupt[0], noZupt[1], noZupt[2], noZupt[3], noZupt[4], (ts2 - ts1) / 1e6);
        System.out.printf("WITH    ZUPT: pos_error=%5.2f m  end=(%6.2f,%6.2f)  hdg_drift=%7.2f°  dist=%.2f m  [%.2f ms]%n",
            zupt[0],   zupt[1],   zupt[2],   zupt[3],   zupt[4],   (ts4 - ts3) / 1e6);

        double improvement = noZupt[0] > 0 ? (1.0 - zupt[0] / noZupt[0]) * 100 : 0;
        System.out.printf("%nZUPT position improvement: %.1f%%%n", improvement);
    }
}
