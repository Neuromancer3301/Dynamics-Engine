package ui.pendulum;

import physics.SimState;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * The sidebar's always-visible "Live Status" block: the N= header, the
 * instability banner, and the drift/Lyapunov readout. Moved out of {@code
 * ControlPanel} — see round 1 §10 of the UI restructuring plan.
 */
public final class StatusPanel extends VBox {

    private static final double INSTABILITY_DRIFT_THRESHOLD_PERCENT = 5.0;

    private final Label header = new Label();
    private final Label lblDrift    = SidebarControlFactory.styledLabel("Drift = ---");
    private final Label lblLyapunov = SidebarControlFactory.styledLabel("λ  = ---");
    private final Label lblInstabilityWarning = new Label(
            "⚠ Simulation is numerically unstable — try Reset or a smaller speed multiplier.");

    public StatusPanel(int n) {
        super(8);

        header.setText("N-Pendulum  [N=" + n + "]");
        header.getStyleClass().add("sidebar-header");
        header.setPadding(new Insets(0, 0, 6, 0));

        lblInstabilityWarning.getStyleClass().add("sidebar-warning-banner");
        lblInstabilityWarning.setWrapText(true);
        lblInstabilityWarning.setVisible(false);
        lblInstabilityWarning.setManaged(false);

        Label lStatus = SidebarControlFactory.sectionLabel("Live Status");
        VBox statusBox = new VBox(4, lblDrift, lblLyapunov);
        statusBox.getStyleClass().add("sidebar-status-box");
        statusBox.setPadding(new Insets(6, 8, 6, 8));

        Label hint = SidebarControlFactory.hintLabel("dt = 2 ms · Space: pause · R: reset · →: step");

        getChildren().setAll(header, lblInstabilityWarning, lStatus, statusBox, hint);
    }

    /**
     * Refreshes the live status block and the instability banner.
     *
     * <p>Energy drift is expressed as a percentage of the STARTING energy,
     * not an absolute figure, so the number is comparable across scenarios
     * of wildly different scale. Guarded by {@code |initialEnergy| > 1e-6}
     * because a configuration that happens to start at nearly zero total
     * energy would otherwise divide by ~0 and report a meaningless
     * astronomical drift.
     */
    public void updateStatus(SimState state, Double initialEnergy) {
        if (state == null) return;

        boolean unstable = !Double.isFinite(state.totalEnergy);
        for (double v : state.angularVelocities) unstable |= !Double.isFinite(v);

        if (initialEnergy != null && Math.abs(initialEnergy) > 1e-6) {
            double drift = Math.abs(state.totalEnergy - initialEnergy)
                         / Math.abs(initialEnergy) * 100.0;
            String driftStr = drift < 0.001
                ? "Drift = < 0.001 %"
                : String.format("Drift = %.4f %%", drift);
            lblDrift.setText(driftStr);
            lblDrift.setTextFill(drift < 0.1 ? Color.web("#3DD68C")
                               : drift < 1.0 ? Color.web("#E8A33D")
                                             : Color.web("#E5484D"));
            unstable |= drift >= INSTABILITY_DRIFT_THRESHOLD_PERCENT;
        }

        lblInstabilityWarning.setVisible(unstable);
        lblInstabilityWarning.setManaged(unstable);
    }

    /** Updates the live Lyapunov readout. A {@code null} lambda means "not measurable yet" and shows dashes rather than a misleading number. */
    public void updateLyapunov(Double lambda) {
        lblLyapunov.setText(lambda == null ? "λ  = ---" : String.format("λ  ≈ %+.3f /s", lambda));
    }

    /** Refreshes the "N=" header after a structural rebuild changes the link count. */
    public void updateLinkCount(int n) {
        header.setText("N-Pendulum  [N=" + n + "]");
    }
}
