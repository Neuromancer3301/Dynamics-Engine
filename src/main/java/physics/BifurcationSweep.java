package physics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Classic bifurcation-diagram computation: sweeps the first link's initial
 * angle across a range and, for each value, lets transients settle and then
 * records a column of long-run samples of the last link's angle — the
 * standard technique for visualizing a chaotic system's route from regular
 * to chaotic motion as one parameter varies.
 *
 * <p>Sampling reuses this project's own Poincaré-section convention (a
 * rising zero-crossing of {@code theta1} — see {@code
 * ui.GraphPanel#addDataPoint}) for any N&ge;2 chain: a period-1 orbit
 * produces one tight cluster of samples per column, a period-doubled orbit
 * two, and a chaotic one a smeared band — the visual signature a
 * bifurcation diagram is for. An N=1 pendulum has no second link to define
 * that crossing against and is never chaotic regardless, so that case
 * instead samples {@code theta1} itself at even time intervals — still a
 * valid (if visually uninteresting — a closed loop, not a bifurcation)
 * fallback rather than a special case that produces no data.
 *
 * <p>Pure computation, no JavaFX dependency: intended to run inside a
 * {@code javafx.concurrent.Task} on a background thread (a full sweep is
 * tens of thousands to a few million {@link PhysicsEngine#step} calls,
 * depending on the settings below — long enough to visibly stall the
 * render thread if run there directly).
 */
public final class BifurcationSweep {

    private static final double DT = 0.002;

    private BifurcationSweep() {}

    /**
     * One completed sweep. Parallel collections: {@code paramValues[c]} is
     * the swept initial angle for column {@code c}, and {@code samples.get(c)}
     * holds every sample collected at that value — the vertical smear of
     * dots the diagram draws in that column.
     */
    public record Result(double[] paramValues, List<double[]> samples) {}

    /**
     * @param base            supplies N, lengths, masses, gravity, speed, and every
     *                        initial angle except link 0's, which this sweeps
     * @param paramMin        smallest link-0 initial angle to try (radians)
     * @param paramMax        largest link-0 initial angle to try (radians)
     * @param columns         number of distinct parameter values across [paramMin, paramMax]
     * @param settleSeconds   simulated time to run before sampling, letting transients decay
     * @param sampleSeconds   simulated time over which samples are actually collected
     * @param onProgress      invoked with a 0..1 fraction after each column; may be {@code null}
     * @param cancelled       polled once per column; a {@code true} stops the sweep early with whatever columns are done so far
     */
    public static Result sweep(PendulumConfig base, double paramMin, double paramMax, int columns,
                                double settleSeconds, double sampleSeconds,
                                DoubleConsumer onProgress, BooleanSupplier cancelled) {
        List<Double> paramList = new ArrayList<>(columns);
        List<double[]> samplesPerColumn = new ArrayList<>(columns);

        for (int c = 0; c < columns; c++) {
            if (cancelled != null && cancelled.getAsBoolean()) break;

            double frac  = (columns <= 1) ? 0.0 : (double) c / (columns - 1);
            double theta1 = paramMin + frac * (paramMax - paramMin);

            double[] initAngles = base.getInitAngles();
            initAngles[0] = theta1;
            PendulumConfig columnConfig = new PendulumConfig(
                    base.getN(), base.getLengths(), base.getMasses(), initAngles,
                    base.getGravity(), base.getSpeedMultiplier());
            PhysicsEngine engine = new PhysicsEngine(columnConfig);

            int settleSteps = (int) (settleSeconds / DT);
            for (int i = 0; i < settleSteps; i++) engine.step(DT);

            int sampleSteps = (int) (sampleSeconds / DT);
            List<Double> ySamples = new ArrayList<>();

            if (base.getN() >= 2) {
                double prevTheta1 = Double.NaN;
                for (int i = 0; i < sampleSteps; i++) {
                    engine.step(DT);
                    SimState s = engine.getState();
                    double t1 = s.angles[0];
                    if (!Double.isNaN(prevTheta1) && prevTheta1 < 0 && t1 >= 0) {
                        ySamples.add(s.angles[s.getN() - 1]);
                    }
                    prevTheta1 = t1;
                }
            } else {
                int strideSteps = Math.max(1, sampleSteps / 60);
                for (int i = 0; i < sampleSteps; i++) {
                    engine.step(DT);
                    if (i % strideSteps == 0) ySamples.add(engine.getState().angles[0]);
                }
            }

            double[] arr = new double[ySamples.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = ySamples.get(i);

            paramList.add(theta1);
            samplesPerColumn.add(arr);

            if (onProgress != null) onProgress.accept((double) (c + 1) / columns);
        }

        double[] paramValues = new double[paramList.size()];
        for (int i = 0; i < paramValues.length; i++) paramValues[i] = paramList.get(i);
        return new Result(paramValues, samplesPerColumn);
    }
}
