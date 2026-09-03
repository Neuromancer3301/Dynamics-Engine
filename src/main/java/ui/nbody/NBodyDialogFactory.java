package ui.nbody;

import physics.nbody.NBodyConfig;
import physics.nbody.NBodyState;
import theme.ThemeManager;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/**
 * Builds the three structural-edit dialogs the n-body canvas opens: a
 * double-click on a body (parameters), a clean click on empty space while
 * the Add tool is active (add a body), or a right-click on a body (delete)
 * — the n-body analogue of {@code ui.pendulum.PendulumDialogFactory}. See
 * the n-body implementation spec §6.4.
 *
 * <p>Every dialog commits through {@link Host#applyStructuralEdit}, the
 * same one-arg structural-edit path every other edit uses.
 *
 * <p>Simpler than the pendulum's three dialogs throughout, because a flat
 * body set has no ordering the way a chain does: no "insert after k," no
 * relative/global angle or pose-preservation checkboxes — a body has no
 * parent/child relationship for one to preserve. Also no name field on
 * either the parameter or Add dialog — {@link NBodyConfig}'s own
 * constructor already defaults an unset name to "Body N", so Add simply
 * doesn't set one.
 */
public final class NBodyDialogFactory {

    /** What this factory needs from its host screen — implemented by {@code controller.NBodySimulationController}. */
    public interface Host {
        NBodyConfig currentConfig();

        /**
         * The physics thread's latest published state — used for every
         * body's live position/velocity (which move continuously, unlike
         * mass/radius) so editing one body never snaps every other body
         * back to its original scene-construction position. Falls back to
         * {@code currentConfig}'s own values if no state has been
         * published yet.
         */
        NBodyState liveState();

        /**
         * The gravitational constant actually in effect on the live engine
         * right now — not {@code currentConfig().getGravitationalConstant()},
         * which never changes after construction: the Motion tab's G slider
         * mutates the engine directly ({@code
         * simLoop.submit(e -> e.setGravitationalConstant(...))}), the same
         * way the pendulum's gravity slider does, so {@code currentConfig}'s
         * own copy goes stale the moment that slider moves. Every dialog
         * here that rebuilds a config must carry this value forward, or a
         * live G edit would be silently discarded on the next Add/Edit/
         * Delete — same reasoning as {@link #liveState()} for position/
         * velocity, just for the one runtime-mutable scalar instead of a
         * per-body array.
         */
        double liveGravitationalConstant();

        /** Commits a validated config through the same structural-edit path every other edit uses. Never moves the Reset baseline unless the caller says so. */
        void applyStructuralEdit(NBodyConfig edited);

        /** Selects the given body on the canvas after a structural edit — {@code -1} clears selection. */
        void selectBody(int body);

        /** The window to own each dialog, or {@code null} if the scene isn't attached yet. */
        Window ownerWindow();
    }

    private final Host host;

    public NBodyDialogFactory(Host host) {
        this.host = host;
    }

    /**
     * Opens the double-click parameter dialog: mass/radius/x/y/vx/vy for one
     * body, validated then committed through {@link Host#applyStructuralEdit}.
     * Mass/radius prefill from {@link Host#currentConfig()} (structural,
     * stable for the engine's lifetime); position/velocity prefill from
     * {@link Host#liveState()} (dynamic — showing the config's original,
     * possibly long-stale value here would be actively misleading for a
     * body that has been orbiting, or was dragged, since the scene was
     * built).
     */
    public void showBodyParameterDialog(int body) {
        NBodyConfig currentConfig = host.currentConfig();
        if (currentConfig == null || body < 0 || body >= currentConfig.getN()) return;

        // The config update (this thread) and the physics thread's next
        // state-buffer publish are asynchronous — right after an Add/Delete,
        // currentConfig can already reflect the new N for a frame or more
        // before a same-sized state is published. Trust `live` only when
        // its own body count actually matches; otherwise fall back to
        // currentConfig, which is always internally consistent with `body`
        // (already bounds-checked above). See liveOrConfigState's javadoc.
        double[] prefill = { currentConfig.getPositionX(body), currentConfig.getPositionY(body),
                              currentConfig.getVelocityX(body), currentConfig.getVelocityY(body) };
        NBodyState liveAtOpen = liveOrNullIfMismatched(host.liveState(), currentConfig);
        if (liveAtOpen != null) {
            prefill[0] = liveAtOpen.positionX[body];
            prefill[1] = liveAtOpen.positionY[body];
            prefill[2] = liveAtOpen.velocityX[body];
            prefill[3] = liveAtOpen.velocityY[body];
        }
        double liveX = prefill[0], liveY = prefill[1], liveVx = prefill[2], liveVy = prefill[3];

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit " + currentConfig.getName(body));
        themeDialog(dialog.getDialogPane());

        ButtonType applyButtonType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, ButtonType.CANCEL);

        TextField massField   = dialogField(String.format("%.6e", currentConfig.getMass(body)));
        TextField radiusField = dialogField(String.format("%.6e", currentConfig.getRadius(body)));
        TextField xField      = dialogField(String.format("%.6e", liveX));
        TextField yField      = dialogField(String.format("%.6e", liveY));
        TextField vxField     = dialogField(String.format("%.6e", liveVx));
        TextField vyField     = dialogField(String.format("%.6e", liveVy));

        Label error = errorLabel();

        GridPane grid = new GridPane();
        grid.getStyleClass().add("dialog-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Mass (kg)"), massField);
        grid.addRow(1, new Label("Radius (m)"), radiusField);
        grid.addRow(2, new Label("X (m)"), xField);
        grid.addRow(3, new Label("Y (m)"), yField);
        grid.addRow(4, new Label("Vx (m/s)"), vxField);
        grid.addRow(5, new Label("Vy (m/s)"), vyField);
        grid.add(error, 0, 6, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Node applyButtonNode = dialog.getDialogPane().lookupButton(applyButtonType);
        applyButtonNode.addEventFilter(ActionEvent.ACTION, evt -> {
            Double mass   = parsePositive(massField.getText());
            Double radius = parsePositive(radiusField.getText());
            Double x      = parseFinite(xField.getText());
            Double y      = parseFinite(yField.getText());
            Double vx     = parseFinite(vxField.getText());
            Double vy     = parseFinite(vyField.getText());
            if (mass == null)   { showDialogError(error, "Mass must be a positive, finite number."); evt.consume(); return; }
            if (radius == null) { showDialogError(error, "Radius must be a positive, finite number."); evt.consume(); return; }
            if (x == null || y == null)   { showDialogError(error, "Position must be finite numbers."); evt.consume(); return; }
            if (vx == null || vy == null) { showDialogError(error, "Velocity must be finite numbers."); evt.consume(); return; }

            // Every OTHER body's position/velocity comes from the live
            // state, not currentConfig's original — otherwise applying this
            // one body's edit would snap every other body back to where the
            // scene started (same reasoning as PendulumDialogFactory's own
            // host.liveAngles() call). Re-fetched HERE, at commit time, not
            // reused from whatever was captured when the dialog opened —
            // the sim may have kept running for the entire time this modal
            // sat open, so an upfront snapshot would otherwise discard
            // every intervening frame of everyone else's motion the moment
            // Apply is pressed.
            NBodyState freshLive = liveOrNullIfMismatched(host.liveState(), currentConfig);
            double[] mass_   = currentConfig.getMasses();
            double[] radius_ = currentConfig.getRadii();
            double[] px = liveArrayOrConfig(freshLive, currentConfig, 'x');
            double[] py = liveArrayOrConfig(freshLive, currentConfig, 'y');
            double[] vxs = liveArrayOrConfig(freshLive, currentConfig, 'X');
            double[] vys = liveArrayOrConfig(freshLive, currentConfig, 'Y');

            mass_[body] = mass; radius_[body] = radius;
            px[body] = x; py[body] = y; vxs[body] = vx; vys[body] = vy;

            try {
                NBodyConfig edited = new NBodyConfig(currentConfig.getN(), mass_, radius_, px, py, vxs, vys,
                        currentConfig.getNames(), currentConfig.getSofteningLength(),
                        host.liveGravitationalConstant(), currentConfig.getSpeedMultiplier());
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
     * Opens the Add-body dialog: click-to-place, position pre-filled from
     * the clicked world coordinates. Simpler than the pendulum's Add
     * dialog — just append; see the class javadoc.
     */
    public void showAddBodyDialog(double prefillX, double prefillY) {
        NBodyConfig currentConfig = host.currentConfig();
        if (currentConfig == null) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add Body");
        themeDialog(dialog.getDialogPane());

        ButtonType addButtonType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        TextField massField   = dialogField(String.format("%.6e", 1.0e22));
        TextField radiusField = dialogField(String.format("%.6e", 1.0e6));
        TextField xField      = dialogField(String.format("%.6e", prefillX));
        TextField yField      = dialogField(String.format("%.6e", prefillY));
        TextField vxField     = dialogField("0.0");
        TextField vyField     = dialogField("0.0");

        Label error = errorLabel();

        GridPane grid = new GridPane();
        grid.getStyleClass().add("dialog-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("Mass (kg)"), massField);
        grid.addRow(1, new Label("Radius (m)"), radiusField);
        grid.addRow(2, new Label("X (m)"), xField);
        grid.addRow(3, new Label("Y (m)"), yField);
        grid.addRow(4, new Label("Vx (m/s)"), vxField);
        grid.addRow(5, new Label("Vy (m/s)"), vyField);
        grid.add(error, 0, 6, 2, 1);
        dialog.getDialogPane().setContent(grid);

        Node addButtonNode = dialog.getDialogPane().lookupButton(addButtonType);
        addButtonNode.addEventFilter(ActionEvent.ACTION, evt -> {
            Double mass   = parsePositive(massField.getText());
            Double radius = parsePositive(radiusField.getText());
            Double x      = parseFinite(xField.getText());
            Double y      = parseFinite(yField.getText());
            Double vx     = parseFinite(vxField.getText());
            Double vy     = parseFinite(vyField.getText());
            if (mass == null)   { showDialogError(error, "Mass must be a positive, finite number."); evt.consume(); return; }
            if (radius == null) { showDialogError(error, "Radius must be a positive, finite number."); evt.consume(); return; }
            if (x == null || y == null)   { showDialogError(error, "Position must be finite numbers."); evt.consume(); return; }
            if (vx == null || vy == null) { showDialogError(error, "Velocity must be finite numbers."); evt.consume(); return; }

            // Fetched fresh, right here at commit time (not before the
            // dialog opened) and size-guarded against currentConfig — see
            // showBodyParameterDialog's identical reasoning.
            NBodyState live = liveOrNullIfMismatched(host.liveState(), currentConfig);
            int n = currentConfig.getN();
            int newN = n + 1;

            double[] mass_ = appendTo(liveArrayOrConfigAll(live, currentConfig, "mass"), mass);
            double[] radius_ = appendTo(liveArrayOrConfigAll(live, currentConfig, "radius"), radius);
            double[] px = appendTo(liveArrayOrConfigAll(live, currentConfig, "x"), x);
            double[] py = appendTo(liveArrayOrConfigAll(live, currentConfig, "y"), y);
            double[] vxs = appendTo(liveArrayOrConfigAll(live, currentConfig, "vx"), vx);
            double[] vys = appendTo(liveArrayOrConfigAll(live, currentConfig, "vy"), vy);
            String[] names = new String[newN];
            System.arraycopy(currentConfig.getNames(), 0, names, 0, n);
            names[n] = null; // NBodyConfig defaults this to "Body " + (n+1)

            try {
                NBodyConfig edited = new NBodyConfig(newN, mass_, radius_, px, py, vxs, vys, names,
                        currentConfig.getSofteningLength(), host.liveGravitationalConstant(),
                        currentConfig.getSpeedMultiplier());
                host.applyStructuralEdit(edited);
                host.selectBody(n); // the newly-added body, overriding applyStructuralEdit's default
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
     * Opens the delete-confirmation dialog: plain confirm, refuses at N=1
     * same as the pendulum's refusal at N=1 and for the same reason (a
     * zero-body scene is meaningless — nothing to render, nothing for a
     * selection index to point at). No pose-preservation checkbox — see
     * the class javadoc.
     */
    public void showDeleteBodyDialog(int body) {
        NBodyConfig currentConfig = host.currentConfig();
        if (currentConfig == null || body < 0 || body >= currentConfig.getN()) return;
        int n = currentConfig.getN();
        if (n <= 1) {
            Alert info = new Alert(Alert.AlertType.INFORMATION, "Can't delete the only remaining body.");
            themeDialog(info.getDialogPane());
            info.showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete " + currentConfig.getName(body));
        confirm.setHeaderText("Delete " + currentConfig.getName(body) + "?");
        themeDialog(confirm.getDialogPane());

        confirm.showAndWait().filter(bt -> bt == ButtonType.OK).ifPresent(bt -> {
            NBodyState live = liveOrNullIfMismatched(host.liveState(), currentConfig);
            int newN = n - 1;
            double[] mass = removeFrom(liveArrayOrConfigAll(live, currentConfig, "mass"), body);
            double[] radius = removeFrom(liveArrayOrConfigAll(live, currentConfig, "radius"), body);
            double[] px = removeFrom(liveArrayOrConfigAll(live, currentConfig, "x"), body);
            double[] py = removeFrom(liveArrayOrConfigAll(live, currentConfig, "y"), body);
            double[] vxs = removeFrom(liveArrayOrConfigAll(live, currentConfig, "vx"), body);
            double[] vys = removeFrom(liveArrayOrConfigAll(live, currentConfig, "vy"), body);
            String[] names = removeFrom(currentConfig.getNames(), body);

            try {
                NBodyConfig edited = new NBodyConfig(newN, mass, radius, px, py, vxs, vys, names,
                        currentConfig.getSofteningLength(), host.liveGravitationalConstant(),
                        currentConfig.getSpeedMultiplier());
                host.applyStructuralEdit(edited);
                host.selectBody(Math.min(body, newN - 1));
            } catch (IllegalArgumentException ignored) {
                // Shouldn't happen given the checks above (n>1, valid index) —
                // fail quietly rather than crash, same convention as the
                // pendulum's delete-link commit.
            }
        });
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code live} unchanged if it's safe to index against {@code
     * cfg} (same body count), else {@code null} — the physics thread's
     * state-buffer publish and this controller's {@code currentConfig}
     * update are asynchronous, so immediately after an Add/Delete there is
     * a real (if narrow) window where one already reflects the new N and
     * the other doesn't. Every {@code live.<array>[i]} read in this class
     * goes through this gate first: {@link #liveArrayOrConfig}/{@link
     * #liveArrayOrConfigAll} already fall back to {@code cfg} whenever
     * {@code live == null}, so filtering a size-mismatched snapshot down to
     * {@code null} here is what keeps every call site safe without
     * duplicating the bounds check at each one.
     */
    private static NBodyState liveOrNullIfMismatched(NBodyState live, NBodyConfig cfg) {
        return (live != null && live.getN() == cfg.getN()) ? live : null;
    }

    /** Live position/velocity for every body if available, else the config's own — used when only ONE body's own field is about to be overwritten by the caller. */
    private static double[] liveArrayOrConfig(NBodyState live, NBodyConfig cfg, char which) {
        if (live == null) {
            return switch (which) {
                case 'x' -> cfg.getPositionsX();
                case 'y' -> cfg.getPositionsY();
                case 'X' -> cfg.getVelocitiesX();
                default  -> cfg.getVelocitiesY();
            };
        }
        return switch (which) {
            case 'x' -> live.positionX.clone();
            case 'y' -> live.positionY.clone();
            case 'X' -> live.velocityX.clone();
            default  -> live.velocityY.clone();
        };
    }

    /** Same idea as {@link #liveArrayOrConfig}, keyed by name so Add/Delete (which need mass/radius too, never overwritten by a live value) can share one lookup. */
    private static double[] liveArrayOrConfigAll(NBodyState live, NBodyConfig cfg, String which) {
        return switch (which) {
            case "mass"   -> cfg.getMasses();
            case "radius" -> cfg.getRadii();
            case "x"      -> live != null ? live.positionX.clone() : cfg.getPositionsX();
            case "y"      -> live != null ? live.positionY.clone() : cfg.getPositionsY();
            case "vx"     -> live != null ? live.velocityX.clone() : cfg.getVelocitiesX();
            default       -> live != null ? live.velocityY.clone() : cfg.getVelocitiesY();
        };
    }

    private static double[] appendTo(double[] array, double value) {
        double[] result = new double[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[array.length] = value;
        return result;
    }

    private static double[] removeFrom(double[] array, int index) {
        double[] result = new double[array.length - 1];
        System.arraycopy(array, 0, result, 0, index);
        System.arraycopy(array, index + 1, result, index, array.length - index - 1);
        return result;
    }

    private static String[] removeFrom(String[] array, int index) {
        String[] result = new String[array.length - 1];
        System.arraycopy(array, 0, result, 0, index);
        System.arraycopy(array, index + 1, result, index, array.length - index - 1);
        return result;
    }

    /** Themes a Dialog/Alert's pane the same way {@code PendulumDialogFactory} does. */
    private static void themeDialog(DialogPane pane) {
        pane.getStylesheets().add(NBodyDialogFactory.class.getResource("/css/theme.css").toExternalForm());
        pane.getStyleClass().addAll("themed-dialog", ThemeManager.getInstance().getCurrent().styleClass());
    }

    private static TextField dialogField(String initial) {
        TextField f = new TextField(initial);
        f.getStyleClass().add("sidebar-numeric-field");
        f.setPrefWidth(160);
        return f;
    }

    private static Label errorLabel() {
        Label error = new Label();
        error.getStyleClass().add("sidebar-error-label");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        return error;
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
