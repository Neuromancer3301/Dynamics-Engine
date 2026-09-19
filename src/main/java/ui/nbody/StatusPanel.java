package ui.nbody;

import physics.nbody.NBodyState;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * The sidebar's always-visible "Live Status" block: N=, t, E, and three
 * drift readouts (energy, momentum, angular momentum) — direct parallel to
 * {@code ui.pendulum.StatusPanel}'s role (an always-visible block pinned
 * above the tab content), per the n-body implementation spec §8.
 *
 * <p>Momentum/angular-momentum drift have no pendulum precedent — see
 * {@code physics.nbody.NBodyState}'s javadoc for why they're genuinely
 * conserved quantities here. Both are expressed as a percentage of a
 * <em>characteristic scale</em> for the current scene rather than of their
 * own baseline value: the zero-momentum-initialized presets start with a
 * momentum baseline of ~0, and "percent deviation from a ~0 baseline" is
 * degenerate (any nonzero drift reads as an enormous or meaningless
 * percentage) — the same near-zero-denominator trap {@code
 * ui.pendulum.StatusPanel} already guards against for energy, just
 * guaranteed to occur here instead of being an edge case. Comparing against
 * what a normal body's momentum/angular momentum looks like in this scene
 * is the well-defined version of the same idea. Energy's baseline is never
 * near zero for a real gravitational scene, so its drift keeps the
 * pendulum's exact "percent of its own baseline" formula.
 */
public final class StatusPanel extends VBox {

    private static final double INSTABILITY_DRIFT_THRESHOLD_PERCENT = 5.0;

    private final Label header = new Label();
    private final Label lblTime   = styledLabel("t = ---");
    private final Label lblEnergy = styledLabel("E = ---");
    private final Label lblEnergyDrift   = styledLabel("E Drift = ---");
    private final Label lblMomentumDrift = styledLabel("p Drift = ---");
    private final Label lblAngMomDrift   = styledLabel("L Drift = ---");
    private final Label lblInstabilityWarning = new Label(
            "⚠ Simulation is numerically unstable — try Reset or a smaller speed multiplier.");

    public StatusPanel(int n) {
        super(8);

        header.setText("N-Body  [N=" + n + "]");
        header.getStyleClass().add("sidebar-header");
        header.setPadding(new Insets(0, 0, 6, 0));

        lblInstabilityWarning.getStyleClass().add("sidebar-warning-banner");
        lblInstabilityWarning.setWrapText(true);
        lblInstabilityWarning.setVisible(false);
        lblInstabilityWarning.setManaged(false);

        Label lStatus = sectionLabel("Live Status");
        VBox statusBox = new VBox(4, lblTime, lblEnergy, lblEnergyDrift, lblMomentumDrift, lblAngMomDrift);
        statusBox.getStyleClass().add("sidebar-status-box");
        statusBox.setPadding(new Insets(6, 8, 6, 8));

        Label hint = hintLabel("Drift % measures how far a conserved quantity has wandered from its expected value.");

        getChildren().setAll(header, lblInstabilityWarning, lStatus, statusBox, hint);
    }

    /**
     * Refreshes the live status block and the instability banner. Every
     * {@code initialXxx} is {@code null} until the controller has captured
     * a post-reset baseline — see the class javadoc for why
     * momentum/angular-momentum drift compare against a characteristic
     * scale rather than that baseline directly.
     */
    public void updateStatus(NBodyState state, Double initialEnergy, Double initialMomentumX,
                              Double initialMomentumY, Double initialAngularMomentum) {
        if (state == null) return;

        boolean unstable = !Double.isFinite(state.totalEnergy);
        for (double v : state.velocityX) unstable |= !Double.isFinite(v);
        for (double v : state.velocityY) unstable |= !Double.isFinite(v);

        lblTime.setText(String.format("t = %.3e s", state.time));
        lblEnergy.setText(String.format("E = %.3e J", state.totalEnergy));

        double characteristicMomentum = characteristicMomentumScale(state);
        double characteristicAngularMomentum = characteristicMomentum * representativeRadius(state);

        if (initialEnergy != null && Math.abs(initialEnergy) > 1e-300) {
            double drift = 100.0 * Math.abs(state.totalEnergy - initialEnergy) / Math.abs(initialEnergy);
            setDriftLabel(lblEnergyDrift, "E Drift", drift);
            unstable |= drift >= INSTABILITY_DRIFT_THRESHOLD_PERCENT;
        }
        if (initialMomentumX != null && initialMomentumY != null) {
            double dpx = state.totalMomentumX - initialMomentumX;
            double dpy = state.totalMomentumY - initialMomentumY;
            double drift = 100.0 * Math.hypot(dpx, dpy) / Math.max(characteristicMomentum, 1e-300);
            setDriftLabel(lblMomentumDrift, "p Drift", drift);
        }
        if (initialAngularMomentum != null) {
            double drift = 100.0 * Math.abs(state.totalAngularMomentum - initialAngularMomentum)
                    / Math.max(characteristicAngularMomentum, 1e-300);
            setDriftLabel(lblAngMomDrift, "L Drift", drift);
        }

        lblInstabilityWarning.setVisible(unstable);
        lblInstabilityWarning.setManaged(unstable);
    }

    private static void setDriftLabel(Label label, String prefix, double driftPercent) {
        String text = driftPercent < 0.001 ? prefix + " = < 0.001 %" : String.format("%s = %.4f %%", prefix, driftPercent);
        label.setText(text);
        label.setTextFill(driftPercent < 0.1 ? Color.web("#3DD68C")
                         : driftPercent < 1.0 ? Color.web("#E8A33D")
                                              : Color.web("#E5484D"));
    }

    /** The largest body's mass times the fastest body's speed — see {@code physics.nbody.NBodyMomentumConservationTest} for the same scale used as a test tolerance. */
    private static double characteristicMomentumScale(NBodyState state) {
        double maxMass = 0, maxSpeed = 0;
        for (int i = 0; i < state.getN(); i++) {
            maxMass = Math.max(maxMass, state.mass[i]);
            maxSpeed = Math.max(maxSpeed, Math.hypot(state.velocityX[i], state.velocityY[i]));
        }
        return maxMass * maxSpeed;
    }

    private static double representativeRadius(NBodyState state) {
        double max = 1.0;
        for (int i = 0; i < state.getN(); i++) max = Math.max(max, Math.hypot(state.positionX[i], state.positionY[i]));
        return max;
    }

    /** Refreshes the "N=" header after a structural rebuild changes the body count. */
    public void updateBodyCount(int n) {
        header.setText("N-Body  [N=" + n + "]");
    }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        l.setPadding(new Insets(4, 0, 0, 0));
        return l;
    }

    private static Label styledLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-status-label");
        return l;
    }

    private static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-hint");
        l.setWrapText(true);
        return l;
    }
}
