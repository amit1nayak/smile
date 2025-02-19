/*
 * Copyright (c) 2010-2020 Haifeng Li. All rights reserved.
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 */

package smile.netlib;

import org.netlib.util.doubleW;
import org.netlib.util.intW;
import smile.math.MathEx;
import smile.math.matrix.DenseMatrix;
import smile.math.matrix.Matrix;
import smile.math.matrix.EVD;

/**
 * ARPACK based eigen decomposition. Currently support only symmetric matrix.
 *
 * @author Haifeng Li
 */
public class ARPACK {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ARPACK.class);

    /** Specify which of the Ritz values of OP to compute. */
    public enum Ritz {
        /**
         * compute the NEV largest (algebraic) eigenvalues.
         */
        LA,
        /**
         * compute the NEV smallest (algebraic) eigenvalues.
         */
        SA,
        /**
         * compute the NEV largest (in magnitude) eigenvalues.
         */
        LM,
        /**
         * compute the NEV smallest (in magnitude) eigenvalues.
         */
        SM,
        /**
         * compute NEV eigenvalues, half from each end of the spectrum
         */
        BE
    }

    private static final com.github.fommil.netlib.ARPACK arpack = com.github.fommil.netlib.ARPACK.getInstance();

    /**
     * Find k approximate eigen pairs of a symmetric matrix by the
     * Lanczos algorithm.
     *
     * @param k Number of eigenvalues of OP to be computed. {@code 0 < k < N}.
     * @param ritz Specify which of the Ritz values to compute.
     */
    public static EVD eigen(Matrix A, int k, Ritz ritz) {
        return eigen(A, k, ritz, 1E-8, 10 * A.nrow());
    }

    /**
     * Find k approximate eigen pairs of a symmetric matrix by the
     * Lanczos algorithm.
     *
     * @param k Number of eigenvalues of OP to be computed. {@code 0 < k < N}.
     * @param which Specify which of the Ritz values to compute.
     */
    public static EVD eigen(Matrix A, int k, String which) {
        return eigen(A, k, which, 1E-8, 10 * A.nrow());
    }

    /**
     * Find k approximate eigen pairs of a symmetric matrix by the
     * Lanczos algorithm.
     *
     * @param k Number of eigenvalues of OP to be computed. {@code 0 < k < N}.
     * @param ritz Specify which of the Ritz values to compute.
     * @param kappa Relative accuracy of ritz values acceptable as eigenvalues.
     * @param maxIter Maximum number of iterations.
     */
    public static EVD eigen(Matrix A, int k, Ritz ritz, double kappa, int maxIter) {
        return eigen(A, k, ritz.name(), kappa, maxIter);
    }

    /**
     * Find k approximate eigen pairs of a symmetric matrix by the
     * Lanczos algorithm.
     *
     * @param k Number of eigenvalues of OP to be computed. {@code 0 < NEV < N}.
     * @param which Specify which of the Ritz values to compute.
     * @param kappa Relative accuracy of ritz values acceptable as eigenvalues.
     * @param maxIter Maximum number of iterations.
     */
    public static EVD eigen(Matrix A, int k, String which, double kappa, int maxIter) {
        if (A.nrow() != A.ncol()) {
            throw new IllegalArgumentException(String.format("Matrix is not square: %d x %d", A.nrow(), A.ncol()));
        }

        if (!A.isSymmetric()) {
            throw new UnsupportedOperationException("This matrix is not symmetric.");
        }

        int n = A.nrow();

        if (k <= 0 || k >= n) {
            throw new IllegalArgumentException("Invalid NEV parameter k: " + k);
        }

        if (kappa <= MathEx.EPSILON) {
            throw new IllegalArgumentException("Invalid tolerance: kappa = " + kappa);
        }

        if (maxIter <= 0) {
            maxIter = 10 * A.nrow();
        }

        intW nev = new intW(k);

        int ncv = Math.min(3 * k, n);

        String bmat = "I"; // standard eigenvalue problem
        doubleW tol = new doubleW(kappa);
        intW info = new intW(0);
        int[] iparam = new int[11];
        iparam[0] = 1;
        iparam[2] = 300;
        iparam[6] = 1;
        intW ido = new intW(0);

        // used for initial residual (if info != 0)
        // and eventually the output residual
        double[] resid = new double[n];
        // Lanczos basis vectors
        double[] v = new double[n * ncv];
        // Arnoldi reverse communication
        double[] workd = new double[3 * n];
        // private work array
        double[] workl = new double[ncv * (ncv + 8)];
        int[] ipntr = new int[11];

        int iter = 0;
        for (; iter < maxIter; iter++) {
            arpack.dsaupd(ido, bmat, n, which, nev.val, tol, resid, ncv, v, n, iparam, ipntr, workd, workl, workl.length, info);

            if (ido.val == 99) {
                break;
            }

            if (ido.val != -1 && ido.val != 1) {
                throw new IllegalStateException("ARPACK DSAUPD ido = " + ido.val);
            }

            av(A, workd, ipntr[0] - 1, ipntr[1] - 1);
        }

        logger.info("ARPACK: " + iter + " iterations for Matrix of size " + n);

        if (info.val != 0) {
            if (info.val == 1) {
                logger.info("ARPACK DSAUPD found all possible eigenvalues: {}", iparam[4]);
            } else {
                throw new IllegalStateException("ARPACK DSAUPD error code: " + info.val);
            }
        }

        double[] d = new double[nev.val];
        boolean[] select = new boolean[ncv];
        double[] z = java.util.Arrays.copyOfRange(v, 0, nev.val * n);

        arpack.dseupd(true, "A", select, d, z, n, 0, bmat, n, which, nev, tol.val, resid, ncv, v, n, iparam, ipntr, workd, workl, workl.length, info);

        if (info.val != 0) {
            throw new IllegalStateException("ARPACK DSEUPD error code: " + info.val);
        }

        int computed = iparam[4];
        logger.info("ARPACK computed " + computed + " eigenvalues");

        DenseMatrix V = new NLMatrix(n, nev.val, z);
        NLMatrix.reverse(d, V);
        return new EVD(V, d);
    }

    private static void av(Matrix A, double[] work, int inputOffset, int outputOffset) {
        int n = A.ncol();
        double[] x = new double[A.ncol()];
        System.arraycopy(work, inputOffset, x, 0, n);
        double[] y = new double[A.ncol()];
        A.ax(x, y);
        System.arraycopy(y, 0, work, outputOffset, n);
    }
}
