package physics;

import physics.integrator.Integrator;
import physics.integrator.Rk4Integrator;

/**
 * N-pendulum Lagrangian dynamics, integrated by a swappable {@link
 * Integrator} strategy (defaulting to RK4).
 *
 * <p><b>Performance notes</b> (see the project's benchmark harness for the
 * before/after numbers this was measured against):
 * <ul>
 *   <li><b>Zero heap allocation per {@code step}.</b> Earlier versions
 *       allocated ~22 short-lived arrays per call. Every one of those is
 *       now a preallocated buffer, either here or inside the chosen {@link
 *       Integrator} (which owns its own RK4/Verlet/Euler-specific scratch),
 *       reused in place.</li>
 *   <li><b>Cholesky, not Gaussian elimination, for the common case.</b> The
 *       mass matrix {@code M} is symmetric by construction and physically
 *       positive-definite whenever every mass is meaningfully positive
 *       (kinetic energy is {@code 0.5 * omega^T * M * omega}, which can't
 *       be zero or negative for a real mass distribution) — Cholesky is
 *       the textbook-correct algorithm for exactly this matrix, and does
 *       roughly half the arithmetic of general Gaussian elimination. The
 *       original pivoted eliminator is kept as a fallback for the one case
 *       Cholesky can't handle: a near-zero mass link, which shows up as a
 *       failed positive-definiteness check rather than a crash.</li>
 *   <li><b>Symmetry exploited when building {@code M} and {@code f}.</b>
 *       {@code M[i][j] == M[j][i]} and {@code sin(theta[j]-theta[i]) ==
 *       -sin(theta[i]-theta[j])}, so the coupling loop in {@link
 *       #derivative} computes each pairwise trig value once and mirrors
 *       it, instead of recomputing it from both sides.</li>
 * </ul>
 */
public final class PhysicsEngine {

    private final int      n;
    private final double[] L;
    private final double[] m;
    private final double[] cumMass;
    private volatile double   g;
    private final double[]    initAngles;
    private final double[]    state;
    private double time;

    private final double[][] M;
    private final double[]   f;

    // Gaussian-elimination fallback scratch — only exercised when
    // choleskyDecompose() reports a near-singular matrix. See the class javadoc.
    private final double[][] aug;

    // Cholesky scratch — the fast path for the overwhelmingly common
    // well-conditioned case.
    private final double[][] cholL;
    private final double[]   cholY;
    private final double[]   thetaDdot;

    private final double[] derivTheta, derivOmega;

    // Not final: setIntegrator() swaps this out (a SimCommand, applied only
    // on the physics thread — see simulation.SimulationLoop). Read from
    // step(), also only ever called on the physics thread, so no
    // cross-thread visibility concern the way the engine-swap reference is.
    private Integrator integrator;

    public PhysicsEngine(PendulumConfig cfg) {
        this.n          = cfg.getN();
        this.L          = cfg.getLengths();
        this.m          = cfg.getMasses();
        this.g          = cfg.getGravity();
        this.initAngles = cfg.getInitAngles();

        cumMass = new double[n];
        cumMass[n - 1] = m[n - 1];
        for (int i = n - 2; i >= 0; i--) cumMass[i] = cumMass[i + 1] + m[i];

        M   = new double[n][n];
        f   = new double[n];
        aug = new double[n][n + 1];

        cholL     = new double[n][n];
        cholY     = new double[n];
        thetaDdot = new double[n];

        derivTheta = new double[n];
        derivOmega = new double[n];

        integrator = new Rk4Integrator(2 * n);

        state = new double[2 * n];
        resetState();
    }

    public void setGravity(double g) { this.g = Math.max(0.01, g); }
    public double getGravity()       { return g; }

    /** Swaps the integration strategy. See {@link Integrator} for why this is safe mid-simulation (each owns its own scratch). */
    public void setIntegrator(Integrator integrator) { this.integrator = integrator; }
    public Integrator getIntegrator()                { return integrator; }

    public void reset() { resetState(); time = 0.0; }

    /**
     * Directly overwrites one link's angle and angular velocity, bypassing
     * integration entirely. This is the mechanism behind interactive
     * dragging: on every pointer-move event a {@code SimCommand} calls this
     * with the mouse-derived angle (and zero velocity while held, or an
     * estimated velocity on release for a "fling"). Between UI events the
     * loop keeps integrating normally — because the mass matrix couples
     * every link's derivative to every other link's angle (see {@link
     * #derivative}), the un-grabbed links visibly react to the forced one
     * rather than needing any special-case physics.
     *
     * <p>Silently ignores a non-finite angle or velocity (defensive against
     * a pointer event producing a stray NaN) rather than corrupting the
     * state vector for every other link sharing it.
     */
    public void setLinkState(int index, double angle, double angularVelocity) {
        if (index < 0 || index >= n) return;
        if (!Double.isFinite(angle) || !Double.isFinite(angularVelocity)) return;
        state[index]     = wrapAngle(angle);
        state[n + index] = angularVelocity;
    }

    public void step(double dt) {
        dt = Math.min(dt, 0.05);
        integrator.step(state, dt, this::derivative, n);
        for (int i = 0; i < n; i++) state[i] = wrapAngle(state[i]);
        time += dt;
    }

    public SimState getState() {
        double[] theta = new double[n], omega = new double[n];
        double[] bobX  = new double[n], bobY  = new double[n];
        for (int i = 0; i < n; i++) { theta[i] = state[i]; omega[i] = state[n + i]; }
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += L[i] * Math.sin(theta[i]);
            cy -= L[i] * Math.cos(theta[i]);
            bobX[i] = cx; bobY[i] = cy;
        }
        double ke = 0;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                double c = cumMass[Math.max(i,j)] * L[i] * L[j];
                ke += 0.5 * c * Math.cos(theta[i]-theta[j]) * omega[i] * omega[j];
            }
        double pe = 0;
        for (int i = 0; i < n; i++) pe -= cumMass[i] * g * L[i] * Math.cos(theta[i]);
        return new SimState(time, theta, omega, bobX, bobY, m, Math.max(0, ke), pe);
    }

    public double getTime() { return time; }

    /**
     * Computes the state derivative at {@code s}, writing the result into
     * {@code out} rather than allocating. Passed to the active {@link
     * Integrator} as a method reference — {@code s} and {@code out} are
     * whichever buffers that integrator's own algorithm calls for.
     */
    private void derivative(double[] s, double[] out) {
        for (int i = 0; i < n; i++) { derivTheta[i] = s[i]; derivOmega[i] = s[n + i]; }

        // Gravity term for each link — independent of the coupling below.
        for (int i = 0; i < n; i++) {
            f[i] = -cumMass[i] * g * L[i] * Math.sin(derivTheta[i]);
        }

        // Coupling terms: each (i, j) pair with j < i is visited once.
        // M[i][j] == M[j][i] (mirrored directly); sin(theta[j]-theta[i]) ==
        // -sin(theta[i]-theta[j]) (negated, not recomputed). The j == i
        // term is skipped entirely — sin(0) == 0, so it never contributed
        // to f regardless.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < i; j++) {
                double c     = cumMass[i] * L[i] * L[j]; // max(i,j) == i since j < i
                double delta = derivTheta[i] - derivTheta[j];
                double cosD  = Math.cos(delta);
                double sinD  = Math.sin(delta);

                M[i][j] = c * cosD;
                M[j][i] = c * cosD;

                f[i] -= c * sinD * derivOmega[j] * derivOmega[j];
                f[j] += c * sinD * derivOmega[i] * derivOmega[i];
            }
            M[i][i] = cumMass[i] * L[i] * L[i];
        }

        solveLinearSystem(); // writes result into thetaDdot

        for (int i = 0; i < n; i++)
            if (!Double.isFinite(thetaDdot[i])) thetaDdot[i] = 0.0;

        for (int i = 0; i < n; i++) { out[i] = derivOmega[i]; out[n + i] = thetaDdot[i]; }
    }

    /** Solves {@code M * thetaDdot = f}, writing into {@link #thetaDdot}. See the class javadoc for the algorithm choice. */
    private void solveLinearSystem() {
        if (choleskyDecompose()) {
            choleskySolveInto(thetaDdot);
        } else {
            gaussianFallbackSolveInto(thetaDdot);
        }
    }

    /**
     * Cholesky decomposition {@code M = cholL * cholL^T}. Returns false the
     * moment a pivot fails the positive-definiteness guarantee — the
     * near-singular case a near-zero mass link produces — signalling the
     * caller to fall back to {@link #gaussianFallbackSolveInto}.
     */
    private boolean choleskyDecompose() {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = M[i][j];
                for (int k = 0; k < j; k++) sum -= cholL[i][k] * cholL[j][k];
                if (i == j) {
                    if (sum < 1.0e-12) return false;
                    cholL[i][i] = Math.sqrt(sum);
                } else {
                    cholL[i][j] = sum / cholL[j][j];
                }
            }
        }
        return true;
    }

    private void choleskySolveInto(double[] out) {
        // Forward substitution: cholL * y = f
        for (int i = 0; i < n; i++) {
            double sum = f[i];
            for (int k = 0; k < i; k++) sum -= cholL[i][k] * cholY[k];
            cholY[i] = sum / cholL[i][i];
        }
        // Back substitution: cholL^T * x = y
        for (int i = n - 1; i >= 0; i--) {
            double sum = cholY[i];
            for (int k = i + 1; k < n; k++) sum -= cholL[k][i] * out[k]; // cholL^T[i][k] == cholL[k][i]
            out[i] = sum / cholL[i][i];
        }
    }

    /**
     * The original pivoted-Gaussian eliminator — kept verbatim as the
     * fallback for the rare near-singular case, where it degrades more
     * gracefully than Cholesky can: a near-zero pivot's row is skipped,
     * leaving that direction's acceleration at zero rather than
     * propagating a NaN through the rest of the state vector.
     */
    private void gaussianFallbackSolveInto(double[] out) {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) aug[i][j] = M[i][j];
            aug[i][n] = f[i];
        }
        for (int col = 0; col < n; col++) {
            int pivotRow = col; double pivotVal = Math.abs(aug[col][col]);
            for (int row = col + 1; row < n; row++) {
                double v = Math.abs(aug[row][col]);
                if (v > pivotVal) { pivotVal = v; pivotRow = row; }
            }
            double[] tmp = aug[col]; aug[col] = aug[pivotRow]; aug[pivotRow] = tmp;
            if (pivotVal < 1e-12) continue;
            for (int row = col + 1; row < n; row++) {
                double factor = aug[row][col] / aug[col][col];
                for (int k = col; k <= n; k++) aug[row][k] -= factor * aug[col][k];
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            if (Math.abs(aug[i][i]) < 1e-12) { out[i] = 0; continue; }
            double sum = aug[i][n];
            for (int j = i + 1; j < n; j++) sum -= aug[i][j] * out[j];
            out[i] = sum / aug[i][i];
        }
    }

    private static double wrapAngle(double angle) {
        angle = angle % (2 * Math.PI);
        if (angle >  Math.PI) angle -= 2 * Math.PI;
        if (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    private void resetState() {
        for (int i = 0; i < n; i++) { state[i] = initAngles[i]; state[n+i] = 0.0; }
        time = 0.0;
    }
}
