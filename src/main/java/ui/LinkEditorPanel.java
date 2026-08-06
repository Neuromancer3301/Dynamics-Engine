package ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import physics.NaturalLanguageSceneParser;
import physics.PendulumConfig;
import physics.PendulumConfigIO;
import physics.PerformanceCalibrator;
import physics.Presets;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Sidebar section for editing per-link parameters (length, mass, initial
 * angle) and the link count itself — the "N" the app is named after, made
 * editable at runtime instead of fixed at construction.
 *
 * <p>Edits are staged in this panel's own draft rows and only take effect
 * on "Apply Changes" — deliberately, not as a limitation. Length and mass
 * are structural parameters: applying one means rebuilding {@code
 * physics.PhysicsEngine}'s internal arrays outright (see {@code
 * simulation.command.EngineRebuilder}), which this codebase treats as an
 * explicit, visible action rather than something that happens on every
 * keystroke. Applying resets the simulation to the edited initial angles at
 * rest — consistent with "initial angle" being the parameter's own name.
 *
 * <p>Every field is validated here before it ever reaches {@link
 * PendulumConfig}'s constructor: a malformed number shows an inline message
 * and aborts the apply rather than crashing or silently discarding the edit.
 *
 * <p>The panel also owns scenario persistence: a preset picker ({@link
 * Presets}) and Save/Load buttons backed by {@link PendulumConfigIO}'s
 * hand-rolled JSON. Choosing a preset or successfully loading a file both
 * populate the rows <em>and</em> immediately apply — the same path a
 * manual "Apply Changes" click takes — so there's exactly one place
 * ({@link #onApply}) that ever rebuilds the simulation.
 *
 * <p>Angle fields display in degrees or radians per {@link #useDegrees} —
 * a display-layer concern only. Every value that crosses this class's
 * boundary (in via {@link #loadFrom}, out via {@link #onApply}) is always
 * radians; conversion happens exactly at construction ({@link LinkRow}) and
 * at read time ({@link #parseAngleField}).
 */
public final class LinkEditorPanel extends VBox {

    private static final double DEFAULT_NEW_LENGTH = 0.5;
    private static final double DEFAULT_NEW_MASS   = 1.0;
    private static final double DEFAULT_NEW_ANGLE  = Math.PI / 2.0; // radians

    // Self-tuned per machine rather than a hardcoded guess — see
    // PerformanceCalibrator. Triggers a one-time, cached micro-benchmark
    // (typically well under a second) the first time any LinkEditorPanel is
    // constructed.
    private final int maxLinks = PerformanceCalibrator.getMaxLinks();

    private final VBox rowsBox = new VBox(6);
    private final Label errorLabel = new Label();
    private final Label columnHints = new Label();
    private final List<LinkRow> rows = new ArrayList<>();

    private Consumer<PendulumConfig> onApply;
    private boolean useDegrees = false;

    // Pulled fresh at apply-time rather than cached, so editing gravity or
    // speed via ControlPanel's sliders after opening this panel is never
    // silently reverted by an Apply here — there's nothing to keep in sync.
    private Supplier<Double> gravitySupplier = () -> 9.81;
    private Supplier<Double> speedSupplier   = () -> 1.0;

    public LinkEditorPanel() {
        super(8);
        setPadding(new Insets(10, 0, 4, 0));

        // ---- Scenarios: presets + save/load ----
        Label scenarioHeader = sectionLabel("Scenarios");

        ComboBox<Presets.Preset> presetBox = new ComboBox<>(FXCollections.observableArrayList(Presets.all()));
        presetBox.setPromptText("Choose a preset…");
        presetBox.setMaxWidth(Double.MAX_VALUE);
        presetBox.setOnAction(e -> {
            Presets.Preset selected = presetBox.getValue();
            if (selected == null) return;
            loadFrom(selected.config());
            clearError();
            if (onApply != null) onApply.accept(selected.config());
        });

        Button saveButton = smallButton("💾 Save");
        saveButton.setOnAction(e -> handleSave());
        Button loadButton = smallButton("📂 Load");
        loadButton.setOnAction(e -> handleLoad());
        HBox fileRow = new HBox(6, saveButton, loadButton);

        // ---- Natural-language scenario setup ----
        // A local keyword/regex heuristic (NaturalLanguageSceneParser), not
        // an AI/LLM call — see that class's javadoc for why. Labeled as
        // such here too, so it reads as a shortcut for the form above
        // rather than something smarter than it is.
        TextField nlField = new TextField();
        nlField.setPromptText("e.g. \"5 links, heavy first link, low gravity\"");
        nlField.setAccessibleText("Describe a scenario in words to fill in the form below");
        Button nlApplyButton = smallButton("✨ Parse & Apply");
        nlApplyButton.setOnAction(e -> handleNaturalLanguageApply(nlField.getText()));
        HBox nlRow = new HBox(6, nlField, nlApplyButton);
        HBox.setHgrow(nlField, javafx.scene.layout.Priority.ALWAYS);
        Label nlHint = hintLabel("Keyword parser, not AI — recognizes link count, gravity, angle, and heavy/light/long/short first/last link.");

        // ---- Links ----
        Label sectionHeader = sectionLabel("Links");
        Button addButton = smallButton("+ Add");
        addButton.setOnAction(e -> addRow(defaultsFromLastRow()));

        Button unitToggle = smallButton(angleUnitLabel());
        unitToggle.setOnAction(e -> {
            toggleAngleUnit();
            unitToggle.setText(angleUnitLabel());
        });

        HBox headerRow = new HBox(8, sectionHeader, addButton, unitToggle);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        columnHints.getStyleClass().add("sidebar-hint");
        columnHints.setText(columnHintsText());

        errorLabel.getStyleClass().add("sidebar-error-label");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        Button applyButton = smallButton("Apply Changes");
        applyButton.setMaxWidth(Double.MAX_VALUE);
        applyButton.getStyleClass().add("primary-button"); // the one FXML-shared class this panel reuses, for the one "commit" action
        applyButton.setOnAction(e -> applyChanges());

        Label note = hintLabel("Applying resets motion to these initial angles.");

        getChildren().addAll(
            scenarioHeader, presetBox, fileRow, nlRow, nlHint, sep(),
            headerRow, columnHints, rowsBox, errorLabel, applyButton, note
        );
    }

    /**
     * Parses {@code text} against the current form's values (so anything
     * unmentioned carries over rather than resetting) and, on success,
     * populates the rows and applies immediately — the same one-path
     * behavior a preset or a successfully loaded file already takes; see
     * the class javadoc's note about {@link #onApply} being the sole entry
     * point for all three.
     */
    private void handleNaturalLanguageApply(String text) {
        if (text == null || text.isBlank()) {
            showError("Type a description first — e.g. \"3 links, moon gravity, horizontal\".");
            return;
        }
        PendulumConfig current = buildConfigFromRows();
        if (current == null) return; // buildConfigFromRows already showed the error (some existing row is invalid)

        PendulumConfig parsed;
        try {
            parsed = NaturalLanguageSceneParser.parse(text, current, maxLinks);
        } catch (IllegalArgumentException ex) {
            showError("Could not build a scenario from that description: " + ex.getMessage());
            return;
        }
        loadFrom(parsed);
        clearError();
        if (onApply != null) onApply.accept(parsed);
    }

    /** Repopulates every row from {@code config} — called at construction, and again whenever a preset or file is chosen. */
    public void loadFrom(PendulumConfig config) {
        rows.forEach(r -> rowsBox.getChildren().remove(r.container));
        rows.clear();
        for (int i = 0; i < config.getN(); i++) {
            addRow(new double[]{config.getLength(i), config.getMass(i), config.getInitAngle(i)});
        }
    }

    /** Supplies gravity/speed at the moment Apply is pressed — see the field javadoc for why this is pull, not push. */
    public void setLiveParameterSuppliers(Supplier<Double> gravitySupplier, Supplier<Double> speedSupplier) {
        this.gravitySupplier = gravitySupplier;
        this.speedSupplier   = speedSupplier;
    }

    public void setOnApply(Consumer<PendulumConfig> onApply) {
        this.onApply = onApply;
    }

    private double[] defaultsFromLastRow() {
        if (rows.isEmpty()) return new double[]{DEFAULT_NEW_LENGTH, DEFAULT_NEW_MASS, DEFAULT_NEW_ANGLE};
        LinkRow last = rows.get(rows.size() - 1);
        try {
            return new double[]{Double.parseDouble(last.length.getText().trim()), DEFAULT_NEW_MASS, DEFAULT_NEW_ANGLE};
        } catch (NumberFormatException ex) {
            return new double[]{DEFAULT_NEW_LENGTH, DEFAULT_NEW_MASS, DEFAULT_NEW_ANGLE};
        }
    }

    private void addRow(double[] initialRadians) {
        if (rows.size() >= maxLinks) {
            showError("Maximum of " + maxLinks + " links — this machine's own measured real-time limit.");
            return;
        }
        LinkRow row = new LinkRow(rows.size() + 1, initialRadians[0], initialRadians[1],
                radiansToDisplay(initialRadians[2]));
        row.deleteButton.setOnAction(e -> removeRow(row));
        rows.add(row);
        rowsBox.getChildren().add(row.container);
        renumberRows();
        clearError();
    }

    private void removeRow(LinkRow row) {
        if (rows.size() <= 1) {
            showError("At least one link is required.");
            return;
        }
        rows.remove(row);
        rowsBox.getChildren().remove(row.container);
        renumberRows();
        clearError();
    }

    private void renumberRows() {
        for (int i = 0; i < rows.size(); i++) rows.get(i).setIndex(i + 1);
    }

    // ---- Angle units ----

    private void toggleAngleUnit() {
        boolean newUseDegrees = !useDegrees;
        for (LinkRow row : rows) {
            try {
                double oldDisplay = Double.parseDouble(row.angle.getText().trim());
                double radians    = useDegrees ? Math.toRadians(oldDisplay) : oldDisplay;
                double newDisplay = newUseDegrees ? Math.toDegrees(radians) : radians;
                row.angle.setText(formatNumber(newDisplay));
            } catch (NumberFormatException ignored) {
                // Malformed field: leave it as-is: whatever's wrong with it
                // will surface via the usual validation the next time Apply
                // or Save is pressed, not silently here.
            }
        }
        useDegrees = newUseDegrees;
        columnHints.setText(columnHintsText());
    }

    private double radiansToDisplay(double radians) { return useDegrees ? Math.toDegrees(radians) : radians; }
    private double displayToRadians(double display)  { return useDegrees ? Math.toRadians(display) : display; }

    private String angleUnitLabel()  { return useDegrees ? "θ: deg" : "θ: rad"; }
    private String columnHintsText() { return "       L        m        θ (" + (useDegrees ? "deg" : "rad") + ")"; }

    private void applyChanges() {
        PendulumConfig newConfig = buildConfigFromRows();
        if (newConfig == null) return; // buildConfigFromRows already showed the error
        clearError();
        if (onApply != null) onApply.accept(newConfig);
    }

    /**
     * Validates every row and builds the {@link PendulumConfig} they
     * describe, or shows an inline error and returns {@code null}. Shared
     * by "Apply Changes" and "Save" — saving a scenario should fail on the
     * same bad input applying would, not write a file first and only
     * discover the problem on load.
     */
    private PendulumConfig buildConfigFromRows() {
        int n = rows.size();
        double[] lengths = new double[n];
        double[] masses  = new double[n];
        double[] angles  = new double[n];

        for (int i = 0; i < n; i++) {
            LinkRow row = rows.get(i);
            Double length = parseField(row.length, "Link " + (i + 1) + " length");
            if (length == null) return null;
            Double mass = parseField(row.mass, "Link " + (i + 1) + " mass");
            if (mass == null) return null;
            Double angle = parseAngleField(row.angle, "Link " + (i + 1) + " initial angle");
            if (angle == null) return null;
            lengths[i] = length;
            masses[i]  = mass;
            angles[i]  = angle; // already converted to radians by parseAngleField
        }

        try {
            return new PendulumConfig(n, lengths, masses, angles, gravitySupplier.get(), speedSupplier.get());
        } catch (IllegalArgumentException ex) {
            // Second line of defense — e.g. a value that parses but PendulumConfig
            // itself rejects (non-positive length/mass). Should be rare given the
            // per-field checks above, but never assume they're exhaustive.
            showError(ex.getMessage());
            return null;
        }
    }

    private void handleSave() {
        PendulumConfig config = buildConfigFromRows();
        if (config == null) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Scenario");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Pendulum Scenario", "*" + PendulumConfigIO.FILE_EXTENSION));
        chooser.setInitialFileName("scenario" + PendulumConfigIO.FILE_EXTENSION);

        File file = chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (file == null) return; // user cancelled

        try {
            PendulumConfigIO.save(config, file.toPath());
            showStatus("Saved " + file.getName());
        } catch (IOException ex) {
            showError("Failed to save: " + ex.getMessage());
        }
    }

    private void handleLoad() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Scenario");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Pendulum Scenario", "*" + PendulumConfigIO.FILE_EXTENSION));

        File file = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (file == null) return; // user cancelled

        try {
            PendulumConfig loaded = PendulumConfigIO.load(file.toPath());
            loadFrom(loaded);
            showStatus("Loaded " + file.getName());
            if (onApply != null) onApply.accept(loaded);
        } catch (IOException ex) {
            // Malformed/adversarial files land here, not a crash — see
            // PendulumConfigIO's javadoc for the size/N bounds enforced
            // before this point.
            showError("Failed to load: " + ex.getMessage());
        }
    }

    private Double parseField(TextField field, String label) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value)) {
                showError(label + " must be a finite number.");
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            showError(label + " is not a valid number.");
            return null;
        }
    }

    /** Like {@link #parseField}, but interprets the value in the current display unit and converts to radians. */
    private Double parseAngleField(TextField field, String label) {
        Double display = parseField(field, label);
        return (display == null) ? null : displayToRadians(display);
    }

    private void showError(String message) {
        errorLabel.getStyleClass().setAll("sidebar-error-label");
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    /** Reuses the error label's slot for transient save/load success feedback — not worth a second label for two words. */
    private void showStatus(String message) {
        errorLabel.getStyleClass().setAll("sidebar-success-label");
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private static String formatNumber(double value) {
        return (Math.abs(value - Math.rint(value)) < 1e-9)
                ? String.valueOf((long) value)
                : String.format("%.3f", value);
    }

    // -------------------------------------------------------------------------
    // Row
    // -------------------------------------------------------------------------

    private static final class LinkRow {
        final HBox container;
        final Label indexLabel;
        final TextField length;
        final TextField mass;
        final TextField angle;
        final Button deleteButton;

        LinkRow(int index, double length, double mass, double angleDisplay) {
            indexLabel = new Label("#" + index);
            indexLabel.getStyleClass().add("sidebar-hint");
            indexLabel.setMinWidth(20);

            this.length = smallField(length);
            this.mass   = smallField(mass);
            this.angle  = smallField(angleDisplay);

            deleteButton = new Button("✕");
            deleteButton.getStyleClass().add("link-delete-button");
            deleteButton.setAccessibleText("Remove link " + index);

            container = new HBox(4, indexLabel, this.length, this.mass, this.angle, deleteButton);
            container.setAlignment(Pos.CENTER_LEFT);

            updateAccessibleTexts(index);
        }

        void setIndex(int index) {
            indexLabel.setText("#" + index);
            deleteButton.setAccessibleText("Remove link " + index);
            updateAccessibleTexts(index);
        }

        // The three columns are only visually labeled once, by columnHints
        // above the whole rows list — a screen reader stepping field-by-field
        // through a row otherwise has no way to tell "length" from "mass"
        // from "angle" apart from column position, so each field names both
        // its own role and which link it belongs to.
        private void updateAccessibleTexts(int index) {
            length.setAccessibleText("Link " + index + " length");
            mass.setAccessibleText("Link " + index + " mass");
            angle.setAccessibleText("Link " + index + " initial angle");
        }

        private static TextField smallField(double value) {
            TextField field = new TextField(formatNumber(value));
            field.getStyleClass().add("sidebar-numeric-field");
            field.setPrefWidth(50);
            return field;
        }
    }

    // -------------------------------------------------------------------------
    // Small factory helpers
    // -------------------------------------------------------------------------

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        return l;
    }

    private static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-hint");
        l.setWrapText(true);
        return l;
    }

    private static Button smallButton(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("sidebar-button");
        return b;
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
