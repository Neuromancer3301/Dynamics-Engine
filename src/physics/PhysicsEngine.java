package physics;

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
    private final double[][] aug;

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

        state = new double[2 * n];
        resetState();
    }

    public void setGravity(double g) { this.g = Math.max(0.01, g); }

    public void reset() { resetState(); time = 0.0; }

    public void step(double dt) {
        dt = Math.min(dt, 0.05);
        double[] k1 = derivative(state);
        double[] k2 = derivative(add(state, scale(k1, dt * 0.5)));
        double[] k3 = derivative(add(state, scale(k2, dt * 0.5)));
        double[] k4 = derivative(add(state, scale(k3, dt)));
        for (int i = 0; i < 2 * n; i++)
            state[i] += (dt / 6.0) * (k1[i] + 2.0*k2[i] + 2.0*k3[i] + k4[i]);
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
        return new SimState(time, theta, omega, bobX, bobY, Math.max(0, ke), pe);
    }

    public double getTime() { return time; }

    private double[] derivative(double[] s) {
        double[] theta = new double[n], omega = new double[n];
        for (int i = 0; i < n; i++) { theta[i] = s[i]; omega[i] = s[n + i]; }
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                double c = cumMass[Math.max(i,j)] * L[i] * L[j];
                M[i][j] = c * Math.cos(theta[i] - theta[j]);
            }
        for (int i = 0; i < n; i++) {
            f[i] = -cumMass[i] * g * L[i] * Math.sin(theta[i]);
            for (int j = 0; j < n; j++) {
                double c = cumMass[Math.max(i,j)] * L[i] * L[j];
                f[i] -= c * Math.sin(theta[i]-theta[j]) * omega[j] * omega[j];
            }
        }
        double[] thetaDdot = solveLinearSystem();
        for (int i = 0; i < n; i++)
            if (!Double.isFinite(thetaDdot[i])) thetaDdot[i] = 0.0;
        double[] deriv = new double[2 * n];
        for (int i = 0; i < n; i++) { deriv[i] = omega[i]; deriv[n+i] = thetaDdot[i]; }
        return deriv;
    }

    private double[] solveLinearSystem() {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) aug[i][j] = M[i][j];
            aug[i][n] = f[i];
        }
        for (int col = 0; col < n; col++) {
            int pivotRow = col; double pivotVal = Math.abs(aug[col][col]);
            for (int row = col+1; row < n; row++) {
                double v = Math.abs(aug[row][col]);
                if (v > pivotVal) { pivotVal = v; pivotRow = row; }
            }
            double[] tmp = aug[col]; aug[col] = aug[pivotRow]; aug[pivotRow] = tmp;
            if (pivotVal < 1e-12) continue;
            for (int row = col+1; row < n; row++) {
                double factor = aug[row][col] / aug[col][col];
                for (int k = col; k <= n; k++) aug[row][k] -= factor * aug[col][k];
            }
        }
        double[] x = new double[n];
        for (int i = n-1; i >= 0; i--) {
            if (Math.abs(aug[i][i]) < 1e-12) { x[i] = 0; continue; }
            double sum = aug[i][n];
            for (int j = i+1; j < n; j++) sum -= aug[i][j] * x[j];
            x[i] = sum / aug[i][i];
        }
        return x;
    }

    private static double[] add(double[] a, double[] b) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] + b[i];
        return r;
    }
    private static double[] scale(double[] a, double s) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] * s;
        return r;
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