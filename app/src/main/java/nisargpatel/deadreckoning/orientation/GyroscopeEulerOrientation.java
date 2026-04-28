
package nisargpatel.deadreckoning.orientation;

import nisargpatel.deadreckoning.extra.ExtraFunctions;

/**
 * Tracks device orientation by integrating gyroscope angular velocities into a
 * Direction Cosine Matrix (DCM) using Taylor-series Rodrigues rotation.
 *
 * <p>Predates the EJML import, so matrix arithmetic is done with
 * {@link nisargpatel.deadreckoning.extra.ExtraFunctions} helpers.
 * Sensor axes are remapped from Android convention (x→Y, y→X, -z→Z) to
 * the North-East-Up INS frame before accumulation.
 */
public class GyroscopeEulerOrientation {

    /** 3×3 Direction Cosine Matrix representing current orientation. */
    private float[][] C;

    //NEU rotation
//    private float[][] rotationNEU= {{0,1,0},
//                                    {1,0,0},
//                                    {0,0,-1}};
//    Android Sensor X --> INS Y
//    Android Sensor Y --> INS X
//    Android Sensor -Z --> INS Z


    /** Initialises the DCM to the identity matrix (no rotation). */
    public GyroscopeEulerOrientation() {
        C = ExtraFunctions.IDENTITY_MATRIX.clone();
    }

    /**
     * @param initialOrientation 3×3 rotation matrix to use as the starting orientation,
     *                           e.g. obtained from a magnetometer-based initialisation.
     */
    public GyroscopeEulerOrientation(float[][] initialOrientation) {
        this();
        C = initialOrientation.clone();
    }

    /**
     * Integrates one gyroscope sample into the DCM and returns the updated matrix.
     * Applies the Android→INS axis remap (sensor x→INS Y, sensor y→INS X,
     * sensor -z→INS Z) before computing the Rodrigues update.
     *
     * @param gyroValues Integrated delta-orientation [x, y, z] in radians (rad)
     *                   as produced by {@link GyroscopeDeltaOrientation}.
     * @return Updated 3×3 DCM (clone — caller may modify freely).
     */
    public float[][] getOrientationMatrix(float[] gyroValues) {
//        float wX = gyroValues[0];
//        float wY = gyroValues[1];
//        float wZ = gyroValues[2];

        float wX = gyroValues[1];
        float wY = gyroValues[0];
        float wZ = -gyroValues[2];

        float[][] A = calcMatrixA(wX, wY, wZ);

        calcMatrixC(A);

        return C.clone();
    }

    /**
     * Updates the DCM and extracts the yaw (heading) angle.
     *
     * @param gyroValue Integrated delta-orientation [x, y, z] in radians (rad).
     * @return Heading (yaw) angle extracted from DCM entry atan2(C[1][0], C[0][0]),
     *         in radians (rad), range (−π, π].
     */
    public float getHeading(float[] gyroValue) {
        getOrientationMatrix(gyroValue);
        return (float) (Math.atan2(C[1][0], C[0][0]));
    }

    /**
     * Computes the Rodrigues rotation matrix A = I + B·sin(σ)/σ + B²·(1−cos σ)/σ²
     * using Taylor-series approximations for numerical stability near σ = 0.
     *
     * @param wX Angular velocity component X (remapped INS frame) in rad/s.
     * @param wY Angular velocity component Y (remapped INS frame) in rad/s.
     * @param wZ Angular velocity component Z (remapped INS frame) in rad/s.
     * @return 3×3 incremental rotation matrix A.
     */
    private float[][] calcMatrixA(float wX, float wY, float wZ) {

        float[][] A;

        //skew symmetric matrix
        float[][] B = calcMatrixB(wX, wY, wZ);
        float[][] B_sq = ExtraFunctions.multiplyMatrices(B, B);

        float norm = ExtraFunctions.calcNorm(wX, wY, wZ);
        float B_scaleFactor = calcBScaleFactor(norm);
        float B_sq_scaleFactor = calcBSqScaleFactor(norm);

        B = ExtraFunctions.scaleMatrix(B, B_scaleFactor);
        B_sq = ExtraFunctions.scaleMatrix(B_sq, B_sq_scaleFactor);

        A = ExtraFunctions.addMatrices(B, B_sq);
        A = ExtraFunctions.addMatrices(A, ExtraFunctions.IDENTITY_MATRIX);

        return A;
    }

    /**
     * Builds the 3×3 skew-symmetric cross-product matrix B from the angular
     * velocity vector [wX, wY, wZ].
     *
     * @param wX Angular velocity X in rad/s.
     * @param wY Angular velocity Y in rad/s.
     * @param wZ Angular velocity Z in rad/s.
     * @return Skew-symmetric matrix B.
     */
    private float[][] calcMatrixB(float wX, float wY, float wZ) {
//        return (new float[][]{{0, -wZ, wY},
//                              {wZ, 0, -wX},
//                              {-wY, wX, 0}});
          return (new float[][]{{0, wZ, -wY},
                                {-wZ, 0, wX},
                                {wY, -wX, 0}});
    }

    /**
     * Taylor-series approximation of sin(σ)/σ used as the scale factor for B.
     * Approximation: 1 − σ²/3! + σ⁴/5!
     *
     * @param sigma Angular velocity magnitude ‖ω‖ in rad/s.
     * @return Scale factor for B in the Rodrigues formula.
     */
    private float calcBScaleFactor(float sigma) {
        //return (float) ((1 - Math.cos(sigma)) / Math.pow(sigma, 2));
        float sigmaSqOverThreeFactorial = (float) Math.pow(sigma, 2) / ExtraFunctions.factorial(3);
        float sigmaToForthOverFiveFactorial = (float) Math.pow(sigma, 4) / ExtraFunctions.factorial(5);
        return (float) (1.0 - sigmaSqOverThreeFactorial + sigmaToForthOverFiveFactorial);
    }

    /**
     * Taylor-series approximation of (1 − cos σ)/σ² used as the scale factor for B².
     * Approximation: 1/2 − σ²/4! + σ⁴/6!
     *
     * @param sigma Angular velocity magnitude ‖ω‖ in rad/s.
     * @return Scale factor for B² in the Rodrigues formula.
     */
    private float calcBSqScaleFactor(float sigma) {
        //return (float) (Math.sin(sigma) / sigma);
        float sigmaSqOverFourFactorial = (float) Math.pow(sigma, 2) / ExtraFunctions.factorial(4);
        float sigmaToForthOverSixFactorial = (float) Math.pow(sigma, 4) / ExtraFunctions.factorial(6);
        return (float) (0.5 - sigmaSqOverFourFactorial + sigmaToForthOverSixFactorial);
    }

    /**
     * Post-multiplies the current DCM by the incremental rotation A,
     * accumulating the total orientation: C ← C × A.
     *
     * @param A 3×3 incremental rotation matrix from {@link #calcMatrixA}.
     */
    private void calcMatrixC(float[][] A) {
        C = ExtraFunctions.multiplyMatrices(C, A);
    }

    //rotate DCM to be compatible with the rotation matrix generated by the Magnetic Field data
//    private void rotateMatrixC() {
//
//        float r = (float) Math.atan2(C[2][1], C[2][2]);
//        float p = (float) -Math.asin(C[2][0]);
//        float y = (float) Math.atan2(C[1][0], C[0][0]);
//
//        double[][] C_double = new double[][] {{Math.cos(p)*Math.cos(y), -Math.cos(r)*Math.sin(y) + Math.sin(r)*Math.sin(p)*Math.cos(y), Math.sin(r)*Math.sin(y) + Math.cos(r)*Math.sin(p)*Math.cos(y)},
//                                              {Math.cos(p)*Math.sin(y), Math.cos(r)*Math.cos(y) + Math.sin(r)*Math.sin(p)*Math.sin(y), -Math.sin(r)*Math.cos(y) + Math.cos(r)*Math.sin(p)*Math.sin(y)},
//                                              {-Math.sin(p),            Math.sin(r)*Math.cos(p),                                        Math.cos(r)*Math.cos(p)}};
//
//        //converts double[][] to float[][]
//        for (int row = 0; row < C.length; row++)
//            for (int col = 0; col < C[row].length; col++)
//                C[row][col] = (float)C_double[row][col];
//
//    }

//    public void clearMatrix() {
//        C = ExtraFunctions.IDENTITY_MATRIX;
//    }

}
