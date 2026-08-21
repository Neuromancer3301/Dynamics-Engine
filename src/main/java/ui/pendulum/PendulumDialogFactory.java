package ui.pendulum;

import physics.PendulumConfig;
import physics.SimState;
import theme.ThemeManager;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the three structural-edit dialogs the pendulum canvas opens on a
 * double-click (parameters), a double-click while the Add tool is active
 * (add a link), or a right-click-release while the Add tool is active
 * (delete a link). Moved verbatim from {@code controller.SimulationController}
 * — see round 1 §7 of the UI restructuring plan.
 *
 * <p>Every dialog commits through {@link Host#applyStructuralEdit}, the same
 * one-arg (never-moves-the-baseline) path every other action-bar edit uses —
 * no dialog here moves where Reset returns to.
 */
public final class PendulumDialogFactory {

    private static final Logger LOG = Logger.getLogger(PendulumDialogFactory.class.getName());

    /** What this factory needs from its host screen — implemented by {@code controller.SimulationController}. */
    public interface Host {
        PendulumConfig currentConfig();

        /**
         * The live angle of every link right now, read from the physics
         * thread's latest published state — not {@code currentConfig}'s
         * possibly-stale {@code initAngles}. Falls back to {@code
         * currentConfig}'s angles if no state has been published yet.
         */
        double[] liveAngles();

        /** The physics thread's latest published state, or {@code null} if none yet. */
        SimState liveState();

        /** Commits a validated config through the same structural-edit path every other action-bar edit uses. Never moves the Reset baseline. */
        void applyStructuralEdit(PendulumConfig edited);

        /** Selects the given link on the canvas after a structural edit — {@code -1} clears selection. */
        void selectLink(int link);

        /** The window to own each dialog, or {@code null} if the scene isn't attached yet. */
        Window ownerWindow();
    }

    private final Host host;

    public PendulumDialogFactory(Host host) {
        this.host = host;
    }

    /**
     * Opens the double-click parameter dialog (§7.4): angle/length/mass for
     * one link, validated the same way {@code ui.LinkEditorPanel} validates
     * its own rows, committing through {@link Host#applyStructuralEdit} on
     * "Apply." Selection/pause is already handled by the press that
     * preceded this double-click (§4-a) — nothing to do here for that.
     */
    public void showLinkParameterDialog(int link) {
        PendulumConfig currentConfig = host.currentConfig();
        if (currentConfig == null || link < 0 || link >= currentConfig.getN()) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit Link #" + (link + 1));
        themeDialog(dialog.getDialogPane());

        ButtonType applyButtonType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, ButtonType.CANCEL);

        // Round 4 §1b: degrees, matching LinkEditorPanel's default — internal
        // state stays radians; this dialog is display/input only.
        TextField angleField  = dialogField(String.format("%.4f", Math.toDegrees(currentConfig.getInitAngle(link))));
        TextField lengthField = dialogField(String.format("%.4f", currentConfig.getLength(link)));
        TextField massField   = dialogField(String.format("%.4f", currentConfig.getMass(link)));

        Label error = new Label();
        error.getStyleClass().add("sidebar-error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("dialog-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Angle (°)"), angleField);
        grid.addRow(1, new Label("Length (m)"), lengthField);
        grid.addRow(2, new Label("Mass (kg)"), massField);
        grid.add(error, 0, 3, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Node applyButtonNode = dialog.getDialogPane().lookupButton(applyButtonType);
        applyButtonNode.addEventFilter(ActionEvent.ACTION, evt -> {
            Double angleDegrees = parseFinite(angleField.getText());
            Double length       = parsePositive(lengthField.getText());
            Double mass         = parsePositive(massField.getText());
            if (angleDegrees == null) { showDialogError(error, "Angle must be a finite number."); evt.consume(); return; }
            if (length == null)       { showDialogError(error, "Length must be a positive, finite number."); evt.consume(); return; }
            if (mass == null)         { showDialogError(error, "Mass must be a positive, finite number."); evt.consume(); return; }

            double[] lengths = currentConfig.getLengths();
            double[] masses  = currentConfig.getMasses();
            double[] angles  = host.liveAngles(); // every other link's live pose, not currentConfig's stale one (§4-f)
            lengths[link] = length;
            masses[link]  = mass;
            angles[link]  = Math.toRadians(angleDegrees);
            try {
                PendulumConfig edited = new PendulumConfig(currentConfig.getN(), lengths, masses, angles,
                        currentConfig.getGravity(), currentConfig.getSpeedMultiplier());
                host.applyStructuralEdit(edited);
            } catch (IllegalArgumentException ex) {
                showDialogError(error, ex.getMessage());
                evt.consume();
            }
        });

        Window owner = host.ownerWindow();
        if (owner != null) dialog.initOwner(owner);
        dialog.showAndWait();
    }

    /**
     * Opens the Add-link dialog (§4-e): double-clicking link {@code k} while
     * the Add tool is active. Splices a new link in as index {@code k+1};
     * every surviving link keeps its live pose. The new link's angle is
     * either the entered value taken as-is (global-frame, matching every
     * other link's convention) or, if the "relative" checkbox is checked,
     * {@code k}'s current live angle plus the entered offset. Selects the
     * newly-added link on confirm, and leaves the Add tool active so more
     * links can be added in a row.
     */
    public void showAddLinkDialog(int k) {
        PendulumConfig currentConfig = host.currentConfig();
        if (currentConfig == null || k < 0 || k >= currentConfig.getN()) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Link After #" + (k + 1));
        themeDialog(dialog.getDialogPane());

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        TextField lengthField = dialogField(String.format("%.4f", currentConfig.getLength(k)));
        TextField massField   = dialogField(String.format("%.4f", currentConfig.getMass(k)));
        TextField angleField  = dialogField("0.0000"); // zero is zero in either unit — no conversion needed here
        CheckBox relativeCheck = new CheckBox("Relative angle (offset from Link #" + (k + 1) + ")");
        relativeCheck.getStyleClass().add("sidebar-checkbox");

        Label error = new Label();
        error.getStyleClass().add("sidebar-error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("dialog-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Length (m)"), lengthField);
        grid.addRow(1, new Label("Mass (kg)"), massField);
        grid.addRow(2, new Label("Angle (°)"), angleField);
        grid.add(relativeCheck, 0, 3, 2, 1);
        grid.add(error, 0, 4, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Node addButtonNode = dialog.getDialogPane().lookupButton(addButtonType);
        addButtonNode.addEventFilter(ActionEvent.ACTION, evt -> {
            Double length     = parsePositive(lengthField.getText());
            Double mass       = parsePositive(massField.getText());
            Double angleInput = parseFinite(angleField.getText());
            if (length == null)     { showDialogError(error, "Length must be a positive, finite number."); evt.consume(); return; }
            if (mass == null)       { showDialogError(error, "Mass must be a positive, finite number."); evt.consume(); return; }
            if (angleInput == null) { showDialogError(error, "Angle must be a finite number."); evt.consume(); return; }

            double[] liveAngles = host.liveAngles();
            // §1b: angleInput is degrees (display unit) — convert before
            // combining with liveAngles[k], which is radians either way.
            double angleOffsetOrAbsoluteRadians = Math.toRadians(angleInput);
            double newAngle = relativeCheck.isSelected()
                    ? liveAngles[k] + angleOffsetOrAbsoluteRadians
                    : angleOffsetOrAbsoluteRadians;

            int n = currentConfig.getN();
            int newN = n + 1;
            double[] oldLengths = currentConfig.getLengths();
            double[] oldMasses  = currentConfig.getMasses();

            double[] lengths = new double[newN];
            double[] masses  = new double[newN];
            double[] angles  = new double[newN];

            for (int i = 0; i <= k; i++) {
                lengths[i] = oldLengths[i];
                masses[i]  = oldMasses[i];
                angles[i]  = liveAngles[i];
            }
            lengths[k + 1] = length;
            masses[k + 1]  = mass;
            angles[k + 1]  = newAngle;
            for (int i = k + 1; i < n; i++) {
                lengths[i + 1] = oldLengths[i];
                masses[i + 1]  = oldMasses[i];
                angles[i + 1]  = liveAngles[i];
            }

            try {
                PendulumConfig edited = new PendulumConfig(newN, lengths, masses, angles,
                        currentConfig.getGravity(), currentConfig.getSpeedMultiplier());
                host.applyStructuralEdit(edited);
                host.selectLink(k + 1); // override applyStructuralEdit's default (re-selects k)
            } catch (IllegalArgumentException ex) {
                showDialogError(error, ex.getMessage());
                evt.consume();
            }
        });

        Window owner = host.ownerWindow();
        if (owner != null) dialog.initOwner(owner);
        dialog.showAndWait();
    }

    /**
     * Opens the delete-confirmation dialog (round 4 §2): right-clicking link
     * {@code link} while the Add tool is active. Refuses on the sole
     * remaining link with a plain info alert rather than silently doing
     * nothing. When a next link survives the deletion, offers to keep its
     * current swing direction (recomputed from its new parent) instead of
     * leaving its raw stored angle as-is; this only ever touches that one
     * surviving link's angle, never its length, so "relative" means
     * "preserve pose," not "preserve exact world position."
     */
    public void showDeleteLinkDialog(int link) {
        PendulumConfig currentConfig = host.currentConfig();
        if (currentConfig == null || link < 0 || link >= currentConfig.getN()) return;
        int n = currentConfig.getN();
        if (n <= 1) {
            Alert info = new Alert(Alert.AlertType.INFORMATION, "Can't delete the only remaining link.");
            themeDialog(info.getDialogPane());
            info.showAndWait();
            return;
        }

        boolean hasNext = link < n - 1;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Link #" + (link + 1));
        confirm.setHeaderText("Delete Link #" + (link + 1) + "?");
        themeDialog(confirm.getDialogPane());

        CheckBox relativeCheck = new CheckBox(
                "Keep Link #" + (link + 2) + "'s current pose (relative to its new parent)");
        relativeCheck.setSelected(true); // avoids a visual snap by default — judgment call
        if (hasNext) confirm.getDialogPane().setContent(relativeCheck);

        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            double[] liveAngles = host.liveAngles();
            double[] oldLengths = currentConfig.getLengths();
            double[] oldMasses  = currentConfig.getMasses();
            SimState state = host.liveState();

            int newN = n - 1;
            double[] lengths = new double[newN];
            double[] masses  = new double[newN];
            double[] angles  = new double[newN];

            for (int i = 0; i < link; i++) {
                lengths[i] = oldLengths[i]; masses[i] = oldMasses[i]; angles[i] = liveAngles[i];
            }
            for (int i = link + 1; i < n; i++) {
                int dst = i - 1;
                lengths[dst] = oldLengths[i];
                masses[dst]  = oldMasses[i];
                angles[dst]  = liveAngles[i]; // "global" default — the raw stored angle, unchanged
            }

            if (hasNext && relativeCheck.isSelected() && state != null) {
                // Recompute the surviving next link's angle so it points the
                // same *direction* it did a moment ago, now measured from its
                // new parent (whatever the deleted link's own parent was) —
                // its length is left exactly as stored, so this preserves
                // pose, not necessarily the exact same (x,y).
                double oldBx = state.bobX[link + 1], oldBy = state.bobY[link + 1];
                double parentX = (link == 0) ? 0.0 : state.bobX[link - 1];
                double parentY = (link == 0) ? 0.0 : state.bobY[link - 1];
                double dx = oldBx - parentX, dy = oldBy - parentY;
                // Same convention as PhysicsEngine.getState(): cx += L*sin(theta), cy -= L*cos(theta)
                angles[link] = Math.atan2(dx, -dy);
            }

            try {
                PendulumConfig edited = new PendulumConfig(newN, lengths, masses, angles,
                        currentConfig.getGravity(), currentConfig.getSpeedMultiplier());
                host.applyStructuralEdit(edited);
                host.selectLink(Math.min(link, newN - 1));
            } catch (IllegalArgumentException ex) {
                LOG.log(Level.WARNING, "Delete-link commit rejected", ex);
            }
        });
    }

    /** Themes a Dialog/Alert's pane the same way every themed dialog in this class does. */
    private static void themeDialog(DialogPane pane) {
        pane.getStylesheets().add(PendulumDialogFactory.class.getResource("/css/theme.css").toExternalForm());
        pane.getStyleClass().addAll("themed-dialog", ThemeManager.getInstance().getCurrent().styleClass());
    }

    private static TextField dialogField(String initial) {
        TextField f = new TextField(initial);
        f.getStyleClass().add("sidebar-numeric-field");
        f.setPrefWidth(120);
        return f;
    }

    private static Double parseFinite(String text) {
        if (text == null) return null;
        try {
            double v = Double.parseDouble(text.trim());
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parsePositive(String text) {
        Double v = parseFinite(text);
        return (v != null && v > 0) ? v : null;
    }

    private static void showDialogError(Label error, String message) {
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
    }
}
