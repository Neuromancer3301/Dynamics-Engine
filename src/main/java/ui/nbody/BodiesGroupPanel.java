package ui.nbody;

import physics.nbody.NBodyConfig;
import physics.nbody.Presets;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The sidebar's "Bodies" tab: a preset picker plus a compact, name-only body
 * list — deliberately <em>not</em> {@code ui.pendulum.LinkEditorPanel}'s
 * always-visible per-row-of-fields shape. See the n-body implementation
 * spec §8: that pattern works for a pendulum chain (typically 1-60 links ×
 * 3 fields each), but the default n-body roster is 34 bodies × 6 fields —
 * an always-visible ~200-field table doesn't scale the same way. The
 * primary editing path is click a body → its parameter dialog ({@link
 * NBodyDialogFactory#showBodyParameterDialog}); this list is a compact
 * navigation aid, not a second editing surface.
 *
 * <p>Re-selecting the <em>already-selected</em> preset is handled the same
 * way {@code LinkEditorPanel} handles it: a plain {@code ComboBox} fires
 * {@code ON_ACTION} from its value property's invalidation, which is a
 * no-op when the new value equals the old one — a real case here, since
 * Phase 1 ships exactly one preset (there is nothing else to "re-select
 * away from"). Intercepting the popup cell's own click/key gesture instead
 * fires regardless of whether it re-picks the same item.
 */
public final class BodiesGroupPanel extends VBox {

    private final ComboBox<Presets.Preset> presetBox =
            new ComboBox<>(FXCollections.observableArrayList(Presets.all()));
    private final ObservableList<String> bodyNames = FXCollections.observableArrayList();
    private final ListView<String> bodyList = new ListView<>(bodyNames);

    private Consumer<Presets.Preset> onPresetApply;
    private IntConsumer onBodyInfo;
    private IntConsumer onBodyOpen;

    public BodiesGroupPanel(NBodyConfig initialConfig) {
        super(10);

        Label presetHeader = sectionLabel("Preset");
        presetBox.setPromptText("Choose a preset…");
        presetBox.setMaxWidth(Double.MAX_VALUE);
        presetBox.setCellFactory(lv -> {
            ListCell<Presets.Preset> cell = new ListCell<>() {
                @Override protected void updateItem(Presets.Preset item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.toString());
                }
            };
            cell.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
                if (cell.isEmpty()) return;
                applyPreset(cell.getItem());
                presetBox.hide();
                e.consume();
            });
            cell.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                if (cell.isEmpty()) return;
                if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                    applyPreset(cell.getItem());
                    presetBox.hide();
                    e.consume();
                }
            });
            return cell;
        });

        Label listHeader = sectionLabel("Bodies");
        bodyList.setPrefHeight(360);
        bodyList.getStyleClass().add("sidebar-body-list");
        // No ".sidebar-body-list" rule exists in theme.css (that file isn't
        // part of this phase's edit list — see the n-body implementation
        // spec §12) — without this inline fallback the list would render in
        // JavaFX's default light Modena styling, jarring against the rest
        // of this dark-themed sidebar. "-bg-surface"/"-ink"/"-line" are
        // theme.css's own custom properties, defined once at the scene
        // root and resolved by name through any descendant's style —
        // inline or stylesheet-declared makes no difference to that lookup
        // — so this blends in exactly like a real stylesheet rule would.
        bodyList.setStyle(
                "-fx-control-inner-background: -bg-surface;"
              + "-fx-background-color: -bg-surface;"
              + "-fx-background-insets: 0;"
              + "-fx-text-fill: -ink;"
              + "-fx-border-color: -line;"
              + "-fx-border-width: 1;");
        // Round 1.1: single click only, no dialog; double-click opens it.
        // Round 1.2: a single click no longer "selects" at all (that
        // paused the sim, which fighting the whole point of a live
        // readout) — it pins a live-updating info HUD instead, via
        // onBodyInfo. A double click still fires ITS first click as an
        // ordinary single click first (JavaFX delivers clickCount 1 then 2
        // as two separate events), so onBodyInfo always runs before
        // onBodyOpen — same click-then-maybe-open shape the canvas itself
        // uses, just aimed at a different callback now.
        bodyList.setOnMouseClicked(e -> {
            int index = bodyList.getSelectionModel().getSelectedIndex();
            if (index < 0) return;
            if (e.getClickCount() == 2) {
                if (onBodyOpen != null) onBodyOpen.accept(index);
            } else {
                if (onBodyInfo != null) onBodyInfo.accept(index);
            }
        });
        Label listHint = hintLabel("Click a body to watch its live values; double-click to select it and open its parameter dialog.");

        refreshBodies(initialConfig);

        getChildren().setAll(presetHeader, presetBox, sep(), listHeader, bodyList, listHint);
    }

    private void applyPreset(Presets.Preset selected) {
        if (selected == null) return;
        if (onPresetApply != null) onPresetApply.accept(selected);
        presetBox.setValue(selected); // keeps the closed box's own label in sync — never the trigger itself
    }

    /** Repopulates the compact list from the current config — call after every structural edit (Add/Delete/preset load changes N and/or names). */
    public void refreshBodies(NBodyConfig config) {
        bodyNames.clear();
        for (int i = 0; i < config.getN(); i++) {
            bodyNames.add((i + 1) + "   " + config.getName(i));
        }
    }

    /** Registers the callback fired when a preset is chosen (by any means — see the class javadoc). */
    public void setOnPresetApply(Consumer<Presets.Preset> callback) { this.onPresetApply = callback; }

    /** Registers the callback fired with a body's index on a single click (round 1.2: pins a live info HUD — does not select, pause, or open anything). */
    public void setOnBodyInfo(IntConsumer callback) { this.onBodyInfo = callback; }

    /** Registers the callback fired with a body's index on a double-click (opens its parameter dialog). */
    public void setOnBodyOpen(IntConsumer callback) { this.onBodyOpen = callback; }

    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-section-label");
        l.setPadding(new Insets(4, 0, 0, 0));
        return l;
    }

    private static Label hintLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sidebar-hint");
        l.setWrapText(true);
        return l;
    }

    private static Separator sep() {
        Separator s = new Separator();
        s.getStyleClass().add("sidebar-separator");
        return s;
    }
}
