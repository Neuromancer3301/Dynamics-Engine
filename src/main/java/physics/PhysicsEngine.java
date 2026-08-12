package physics;

import physics.integrator.Integrator;
import physics.integrator.Rk4Integrator;

import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(PhysicsEngine.class.getName());

    private final int      n;
    private final double[] L;
    private final double[] m;
    private final double[] cumMass;
    private volatile double   g;

    // 0 = straight down (screen +Y), matching this engine's original,
    // only-ever behavior exactly — see setGravityAngle's javadoc for the
    // convention and where it's actually set.
    private volatile double   gravityAngle = 0.0;

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

    // Logged once per engine instance, not once per step: a persistently
    // near-singular config (e.g. one near-zero mass) would otherwise flood
    // the log at up to ~500Hz. The condition itself doesn't need repeating
    // once known — it's a structural property of this engine's masses.
    private boolean fallbackWarningLogged = false;

    // Not final: setIntegrator() swaps this out (a SimCommand, applied only
    // on the physics thread — see simulation.SimulationLoop). Read from
    // step(), also only ever called on the physics thread, so no
    // cross-thread visibility concern the way the engine-swap reference is.
    private Integrator integrator;

    /**
     * Builds an engine for one specific chain shape. Everything that cannot
     * change without rebuilding (N, the per-link lengths and masses) is
     * copied in here; everything that can change at runtime (gravity, the
     * integrator) has a setter.
     *
     * <p><b>What {@code cumMass} is, and why it exists.</b> In an
     * N-pendulum, link {@code i} does not only carry its own bob — it also
     * has to carry every link hanging below it. So the effective mass
     * pulling on link {@code i} is {@code m[i] + m[i+1] + ... + m[n-1]}.
     * That running total from the tip backwards is {@code cumMass[i]}, and
     * it appears in literally every term of the equations of motion below.
     * Computing it once here (a single backwards pass, O(N)) instead of
     * re-summing inside {@link #derivative} — which runs thousands of times
     * a second — is the difference between O(N) and O(N²) per step.
     *
     * <p>Every array below is allocated exactly once, here, and then reused
     * in place forever. See the class javadoc's "zero heap allocation"
     * note: {@code step()} must not allocate, because it runs ~500 times a
     * second and would otherwise generate constant garbage-collector churn.
     */
    public PhysicsEngine(PendulumConfig cfg) {
        this.n          = cfg.getN();
        this.L          = cfg.getLengths();
        this.m          = cfg.getMasses();
        this.g          = cfg.getGravity();
        this.initAngles = cfg.getInitAngles();

        // cumMass[i] = total mass at or below link i. Built from the tip
        // (n-1) backwards, so each entry is just "the one below me, plus me".
        cumMass = new double[n];
        cumMass[n - 1] = m[n - 1];
        for (int i = n - 2; i >= 0; i--) cumMass[i] = cumMass[i + 1] + m[i];

        M   = new double[n][n];      // mass matrix, rebuilt every derivative call
        f   = new double[n];         // force vector, likewise
        aug = new double[n][n + 1];  // [M | f] augmented matrix, Gaussian fallback only

        cholL     = new double[n][n]; // lower-triangular Cholesky factor
        cholY     = new double[n];    // intermediate vector from forward substitution
        thetaDdot = new double[n];    // the solved angular accelerations

        derivTheta = new double[n];   // scratch: angles unpacked from the state vector
        derivOmega = new double[n];   // scratch: angular velocities, likewise

        integrator = new Rk4Integrator(2 * n); // default; swappable via setIntegrator

        // The state vector packs BOTH angles and angular velocities into one
        // flat array of length 2n: indices [0, n) are the angles (theta),
        // indices [n, 2n) are the angular velocities (omega). Integrators
        // work on this single array without needing to know what the halves
        // mean — that's what makes them interchangeable.
        state = new double[2 * n];
        resetState();
    }

    /** Sets gravitational acceleration in m/s². Clamped above zero — zero or negative gravity would make the mass matrix meaningless. */
    public void setGravity(double g) { this.g = Math.max(0.01, g); }

    /** Current gravitational acceleration in m/s². */
    public double getGravity()       { return g; }

    /**
     * Sets the direction gravity pulls in — "painting" the field, per
     * {@code ui.PendulumCanvas}'s draggable handle. 0 (the default, and the
     * only value this engine ever used before this method existed) means
     * straight down, in the same angle convention {@code theta} itself
     * uses: a link's rest position is wherever {@code theta == gravityAngle}
     * places it, exactly as {@code theta == 0} was the only rest position
     * when gravity could only point down. This is a genuine generalization
     * of the single-scalar-g case, not an approximation of it: with
     * {@code gravityAngle == 0} every formula below reduces to exactly what
     * this engine computed before it existed.
     */
    public void setGravityAngle(double gravityAngle) { this.gravityAngle = gravityAngle; }
    /** Current gravity direction in radians (0 = straight down). */
    public double getGravityAngle()                  { return gravityAngle; }

    /** Swaps the integration strategy. See {@link Integrator} for why this is safe mid-simulation (each owns its own scratch). */
    public void setIntegrator(Integrator integrator) { this.integrator = integrator; }
    /** The integration strategy currently in use. */
    public Integrator getIntegrator()                { return integrator; }

    /** Returns the chain to its configured starting angles at rest and rewinds the clock. Invoked via {@code ResetCommand} so it lands between steps. */
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

    /**
     * Advances (or, given a negative {@code dt}, reverses) the simulation by
     * one step. A negative {@code dt} is a real capability, not an
     * out-of-range input to reject: RK4 (and the other {@link
     * physics.integrator.Integrator} strategies) integrate an ODE the same
     * way regardless of the sign of the step, so calling this with negative
     * steps genuinely runs the same dynamics backward — see {@code
     * simulation.SimulationLoop#setTimeReversed} for where that's exposed.
     * Clamped symmetrically so neither direction can take a single step
     * large enough to blow up the integrator.
     */
    public void step(double dt) {
        dt = Math.max(-0.05, Math.min(dt, 0.05));
        integrator.step(state, dt, this::derivative, n);
        for (int i = 0; i < n; i++) state[i] = wrapAngle(state[i]);
        time += dt;
    }

    /**
     * Produces an immutable snapshot of everything the rest of the app needs
     * to draw or analyse this chain. Called from the physics thread and
     * handed across to the UI thread (see {@code simulation.StateBuffer}),
     * which is why it returns a fresh defensive copy rather than exposing
     * the live internal arrays.
     *
     * <p>Three separate computations happen here:
     *
     * <p><b>1. Forward kinematics</b> — converting angles into positions.
     * The engine only ever stores <em>angles</em>; the (x, y) position of
     * each bob is derived. Because each link hangs off the end of the
     * previous one, positions are <em>cumulative</em>: running totals
     * {@code cx}/{@code cy} accumulate down the chain. For each link,
     * {@code x += L·sin(theta)} and {@code y -= L·cos(theta)}. The minus on
     * y is the convention that theta = 0 means "hanging straight down".
     *
     * <p><b>2. Kinetic energy</b> — {@code KE = ½·ωᵀ·M·ω}, the standard
     * quadratic form for a coupled system. The double loop is exactly that
     * matrix product written out: every pair of links (i, j) contributes,
     * because in a coupled chain link i's motion carries link j along with
     * it. The {@code cos(theta_i − theta_j)} factor is how much of link j's
     * motion is aligned with link i's — at 90° apart they contribute
     * nothing to each other. Clamped at zero on return purely as a guard
     * against floating-point round-off producing a tiny negative.
     *
     * <p><b>3. Potential energy</b> — height × weight, summed. {@code
     * −cumMass[i]·g·L[i]·cos(theta − gravityAngle)} is the vertical drop of
     * link i measured along whichever direction gravity currently points.
     * It is negative because PE is measured downward from the pivot, so
     * hanging straight down is the minimum-energy position.
     *
     * <p>Total energy (KE + PE) should stay constant in an ideal
     * simulation — the app's "Drift %" readout is precisely how far this
     * total has wandered, and is the headline measure of integrator quality.
     */
    public SimState getState() {
        double[] theta = new double[n], omega = new double[n];
        double[] bobX  = new double[n], bobY  = new double[n];

        // Unpack the flat 2n state vector back into separate angle/velocity arrays.
        for (int i = 0; i < n; i++) { theta[i] = state[i]; omega[i] = state[n + i]; }

        // --- 1. Forward kinematics: angles -> cumulative bob positions ---
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            cx += L[i] * Math.sin(theta[i]);
            cy -= L[i] * Math.cos(theta[i]); // negative: theta=0 hangs downward
            bobX[i] = cx; bobY[i] = cy;      // absolute position of bob i
        }

        // --- 2. Kinetic energy: KE = 1/2 * omega^T * M * omega ---
        double ke = 0;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                // Same coupling coefficient the mass matrix uses in derivative().
                double c = cumMass[Math.max(i,j)] * L[i] * L[j];
                ke += 0.5 * c * Math.cos(theta[i]-theta[j]) * omega[i] * omega[j];
            }

        // --- 3. Potential energy: sum of (weight * height) along gravity ---
        double pe = 0;
        for (int i = 0; i < n; i++) pe -= cumMass[i] * g * L[i] * Math.cos(theta[i] - gravityAngle);

        return new SimState(time, theta, omega, bobX, bobY, m, Math.max(0, ke), pe);
    }

    /** Simulation time in seconds since the last reset. Can decrease — see {@link #step} on negative timesteps. */
    public double getTime() { return time; }

    /**
     * <b>The heart of the engine: computes the state derivative.</b> Given a
     * state (all angles + all angular velocities), returns how fast each of
     * those quantities is currently changing. Everything else in this class
     * exists to serve this method, and every integrator does nothing but
     * call it repeatedly.
     *
     * <p><b>The core idea.</b> The state vector is {@code [theta, omega]}.
     * Its derivative is {@code [omega, alpha]} — because the rate of change
     * of an angle <em>is</em> its angular velocity (that half is free), and
     * the rate of change of angular velocity is angular acceleration
     * (that half is all the work). So this method's real job is: <em>find
     * the angular accelerations.</em>
     *
     * <p><b>Why that's hard for a chain.</b> For a single pendulum you can
     * write {@code alpha = −(g/L)·sin(theta)} directly. For N coupled links
     * you cannot: pushing link 1 also accelerates links 2..N, and they push
     * back. Every link's acceleration depends on every other link's
     * acceleration, all at the same instant. Lagrangian mechanics turns
     * that mutual dependency into a matrix equation:
     *
     * <pre>    M · alpha = f</pre>
     *
     * where <b>M</b> is the {@code n×n} mass matrix (how strongly link i's
     * acceleration is coupled to link j's) and <b>f</b> is the force vector
     * (gravity plus centrifugal terms). Solving that linear system for
     * {@code alpha} is the only way to get all N accelerations
     * simultaneously — which is why {@link #solveLinearSystem} exists.
     *
     * <p><b>The three steps below are therefore:</b> build {@code f} from
     * gravity, add the coupling terms to both {@code M} and {@code f}, then
     * solve. Results are written into the caller's {@code out} buffer;
     * nothing is allocated.
     *
     * <p>Passed to the active {@link Integrator} as a method reference —
     * {@code s} and {@code out} are whichever scratch buffers that
     * integrator's own algorithm calls for, which is exactly why
     * integrators are interchangeable: they never see this class's fields.
     */
    private void derivative(double[] s, double[] out) {
        // Unpack the caller's flat state vector into readable halves.
        for (int i = 0; i < n; i++) { derivTheta[i] = s[i]; derivOmega[i] = s[n + i]; }

        // ---- Step 1: gravity torque on each link. ----
        // Torque = -(mass below) * g * (lever arm) * sin(angle from vertical).
        // The minus sign makes it a RESTORING torque: displaced to one side,
        // gravity pulls back the other way, which is what makes a pendulum
        // oscillate at all. Independent of the coupling terms below, so it
        // is computed first in its own clean pass.
        //
        // The (derivTheta[i] - gravityAngle) form is exactly the standard
        // gravity term with theta replaced by "angle from wherever gravity
        // now points" instead of "angle from straight down" — see
        // setGravityAngle's javadoc.
        for (int i = 0; i < n; i++) {
            f[i] = -cumMass[i] * g * L[i] * Math.sin(derivTheta[i] - gravityAngle);
        }

        // ---- Step 2: coupling terms — fill the mass matrix M, and add the
        //      centrifugal contributions to f. ----
        //
        // OFF-DIAGONAL M[i][j]: how strongly link i's acceleration is tied
        // to link j's. Proportional to cos(theta_i - theta_j) — two links
        // pointing the same way drag each other maximally; at right angles
        // they barely couple at all.
        //
        // THE f TERMS: these are the centrifugal (velocity-squared) forces.
        // A link swinging fast flings the links attached to it outward, and
        // that depends on omega², not on angle. This is the term that makes
        // the system genuinely nonlinear — and therefore chaotic.
        //
        // Two symmetries are exploited so each pair is touched once, halving
        // the trig calls (the dominant cost at large N):
        //   * M[i][j] == M[j][i]                     -> computed once, mirrored
        //   * sin(theta_j - theta_i) == -sin(theta_i - theta_j) -> negated, not recomputed
        // The j == i case is skipped entirely: sin(0) == 0 contributes nothing to f.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < i; j++) {
                double c     = cumMass[i] * L[i] * L[j]; // max(i,j) == i since j < i
                double delta = derivTheta[i] - derivTheta[j];
                double cosD  = Math.cos(delta);
                double sinD  = Math.sin(delta);

                M[i][j] = c * cosD;
                M[j][i] = c * cosD; // mirrored, not recomputed

                // Centrifugal: each link is flung by the other's angular speed.
                // Opposite signs are Newton's third law expressed in angular form.
                f[i] -= c * sinD * derivOmega[j] * derivOmega[j];
                f[j] += c * sinD * derivOmega[i] * derivOmega[i];
            }
            // DIAGONAL M[i][i]: link i's own moment of inertia about the pivot,
            // carrying everything below it. Always the largest entry in its row,
            // which is what makes the matrix positive-definite and Cholesky valid.
            M[i][i] = cumMass[i] * L[i] * L[i];
        }

        // ---- Step 3: solve M * alpha = f for the angular accelerations. ----
        solveLinearSystem(); // writes result into thetaDdot

        // Safety net: if the solve produced NaN/Infinity (a pathological,
        // near-singular configuration), substitute zero acceleration rather
        // than letting a non-finite value propagate into the state vector,
        // where it would poison every subsequent frame irrecoverably.
        for (int i = 0; i < n; i++)
            if (!Double.isFinite(thetaDdot[i])) thetaDdot[i] = 0.0;

        // Repack into the derivative the integrator expects:
        //   d(theta)/dt = omega   (the free half)
        //   d(omega)/dt = alpha   (the half we just solved for)
        for (int i = 0; i < n; i++) { out[i] = derivOmega[i]; out[n + i] = thetaDdot[i]; }
    }

    /** Solves {@code M * thetaDdot = f}, writing into {@link #thetaDdot}. See the class javadoc for the algorithm choice. */
    private void solveLinearSystem() {
        if (choleskyDecompose()) {
            choleskySolveInto(thetaDdot);
        } else {
            if (!fallbackWarningLogged) {
                LOG.warning("N=" + n + ": mass matrix failed the Cholesky positive-definiteness "
                        + "check (likely a near-zero mass link) — falling back to pivoted Gaussian "
                        + "elimination for this and all subsequent steps.");
                fallbackWarningLogged = true;
            }
            gaussianFallbackSolveInto(thetaDdot);
        }
    }

    /**
     * <b>Cholesky decomposition:</b> factors the mass matrix into {@code M =
     * L · Lᵀ}, where {@code L} is lower-triangular.
     *
     * <p><b>Why bother factoring at all?</b> Solving {@code M · x = f}
     * directly is expensive. But solving a <em>triangular</em> system is
     * trivial — you just substitute one variable at a time. So the strategy
     * is: split M into two triangular matrices once, then do two cheap
     * triangular solves (see {@link #choleskySolveInto}).
     *
     * <p><b>Why Cholesky specifically, and not general Gaussian
     * elimination?</b> Cholesky only works on symmetric positive-definite
     * matrices — but our mass matrix is guaranteed to be exactly that (see
     * the class javadoc: kinetic energy {@code ½ωᵀMω} is physically
     * impossible to make negative for real masses). In exchange for that
     * restriction it does roughly <em>half</em> the arithmetic of Gaussian
     * elimination, and needs no pivoting. Since this runs once per
     * derivative evaluation — four times per RK4 step, ~2000 times a second
     * — halving the work is the single biggest performance win available.
     *
     * <p><b>The algorithm.</b> Walk the lower triangle. Each entry is the
     * original {@code M[i][j]} minus the dot product of the parts of rows i
     * and j already computed. Diagonal entries take a square root; the rest
     * divide by the diagonal above them.
     *
     * <p><b>The failure case.</b> If a diagonal value drops to (or below)
     * zero, the square root would be imaginary — meaning the matrix is not
     * positive-definite after all, which in practice means a near-zero mass
     * link. Rather than produce NaN, this returns {@code false} immediately
     * and the caller falls back to {@link #gaussianFallbackSolveInto}.
     *
     * @return true if the factorisation succeeded and {@code cholL} is usable
     */
    private boolean choleskyDecompose() {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {          // lower triangle only
                double sum = M[i][j];
                // Subtract the overlap with everything already factored.
                for (int k = 0; k < j; k++) sum -= cholL[i][k] * cholL[j][k];
                if (i == j) {
                    // Diagonal: must be positive or the matrix isn't positive-definite.
                    if (sum < 1.0e-12) return false; // bail out; caller uses the fallback
                    cholL[i][i] = Math.sqrt(sum);
                } else {
                    cholL[i][j] = sum / cholL[j][j];
                }
            }
        }
        return true;
    }

    /**
     * Solves {@code M · x = f} using the factorisation {@code M = L · Lᵀ}
     * already computed by {@link #choleskyDecompose}, writing {@code x} into
     * {@code out}.
     *
     * <p>Substituting the factorisation gives {@code L · (Lᵀ · x) = f}.
     * Treating {@code (Lᵀ · x)} as an unknown vector {@code y}, that splits
     * into two easy triangular systems solved back to back:
     *
     * <ol>
     *   <li><b>Forward substitution</b> — solve {@code L · y = f} for y.
     *       L is lower-triangular, so row 0 involves only y[0]: solve it,
     *       substitute into row 1, and so on downward.</li>
     *   <li><b>Back substitution</b> — solve {@code Lᵀ · x = y} for x.
     *       Lᵀ is upper-triangular, so the same trick runs in reverse, from
     *       the last row upward.</li>
     * </ol>
     *
     * <p>{@code Lᵀ} is never actually built as a separate array — the
     * transpose of element {@code [i][k]} is just {@code [k][i]}, so the
     * second loop simply reads {@code cholL} with the indices swapped.
     */
    private void choleskySolveInto(double[] out) {
        // Forward substitution: L * y = f  (top row down; each row has one new unknown)
        for (int i = 0; i < n; i++) {
            double sum = f[i];
            for (int k = 0; k < i; k++) sum -= cholL[i][k] * cholY[k]; // subtract knowns
            cholY[i] = sum / cholL[i][i];
        }
        // Back substitution: L^T * x = y  (bottom row up; same trick reversed)
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
        // Build the augmented matrix [M | f] — the standard textbook setup,
        // where the right-hand side rides along as an extra column so row
        // operations apply to it automatically.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) aug[i][j] = M[i][j];
            aug[i][n] = f[i];
        }

        // --- Forward elimination, with partial pivoting. ---
        for (int col = 0; col < n; col++) {
            // PIVOTING: find the row with the largest value in this column and
            // swap it up. Dividing by a tiny number massively amplifies
            // rounding error, so always divide by the biggest available.
            int pivotRow = col; double pivotVal = Math.abs(aug[col][col]);
            for (int row = col + 1; row < n; row++) {
                double v = Math.abs(aug[row][col]);
                if (v > pivotVal) { pivotVal = v; pivotRow = row; }
            }
            double[] tmp = aug[col]; aug[col] = aug[pivotRow]; aug[pivotRow] = tmp;

            // Even the best pivot is ~zero: this column is degenerate. Skip it
            // rather than dividing by ~0 and producing Infinity/NaN. This is
            // precisely the graceful degradation Cholesky cannot offer.
            if (pivotVal < 1e-12) continue;

            // Eliminate this column from every row below, so the matrix
            // becomes upper-triangular.
            for (int row = col + 1; row < n; row++) {
                double factor = aug[row][col] / aug[col][col];
                for (int k = col; k <= n; k++) aug[row][k] -= factor * aug[col][k];
            }
        }

        // --- Back substitution, bottom row upward. ---
        for (int i = n - 1; i >= 0; i--) {
            // A degenerate row contributes no information: leave that
            // direction's acceleration at zero instead of emitting NaN.
            if (Math.abs(aug[i][i]) < 1e-12) { out[i] = 0; continue; }
            double sum = aug[i][n];
            for (int j = i + 1; j < n; j++) sum -= aug[i][j] * out[j]; // subtract already-solved unknowns
            out[i] = sum / aug[i][i];
        }
    }

    /**
     * Normalises any angle into the range (−π, π].
     *
     * <p>Without this, a continuously spinning link's angle would grow
     * without bound (100π, 1000π…), and floating-point precision degrades
     * as magnitude grows — a genuine long-run accuracy problem. Wrapping
     * also means the graph's Y axis has a fixed, meaningful range instead
     * of scrolling off forever. Physically nothing changes: θ and θ+2π are
     * the same orientation.
     */
    private static double wrapAngle(double angle) {
        angle = angle % (2 * Math.PI);          // collapse whole revolutions
        if (angle >  Math.PI) angle -= 2 * Math.PI; // fold the top half down
        if (angle < -Math.PI) angle += 2 * Math.PI; // fold the bottom half up
        return angle;
    }

    /** Restores the configured starting angles with every link at rest (zero velocity), and rewinds the clock. */
    private void resetState() {
        for (int i = 0; i < n; i++) {
            state[i]     = initAngles[i]; // angle half
            state[n + i] = 0.0;           // velocity half — released from rest
        }
        time = 0.0;
    }
}
