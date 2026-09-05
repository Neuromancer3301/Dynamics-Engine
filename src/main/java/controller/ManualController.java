package controller;

import javafx.animation.FadeTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import navigation.Navigable;
import navigation.SceneRouter;
import theme.ThemeManager;

import java.net.URL;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * The in-app user manual — a read-only guide written for someone opening the
 * program for the first time with no physics background.
 *
 * <p><b>One tab per simulation.</b> The manual is split three ways, matching
 * the three cards on the main menu: click a tab and you get that simulation's
 * manual and nothing else. This is not just navigation — it is what keeps the
 * document honest as the suite grows. A single scrolling guide would force a
 * reader of simulation 02 to scroll past forty sections about the pendulum;
 * with tabs, each simulation's manual is self-contained, and adding one is
 * adding a {@link Tab} constant and a builder method.
 *
 * <p><b>Why the content is built here rather than declared in FXML.</b> It is
 * roughly forty sections of prose, steps and callouts. As FXML that would be
 * a thousand lines of nested {@code <Label>} tags with a style class repeated
 * on every one — unreadable, and impossible to keep consistent. Building it
 * from the small vocabulary of helpers at the bottom of this class ({@link
 * #part}, {@link #heading}, {@link #text}, {@link #steps}, {@link #expect},
 * {@link #means}, {@link #note}, {@link #row}) means each section reads as
 * what it is, and restyling one kind of block is a one-line change.
 *
 * <p>Everything here documents behaviour that actually exists. Where a
 * feature is deliberately absent — the one remaining reserved simulation
 * slot, or a not-yet-built piece of a real one (e.g. n-body scenario
 * save/load) — the manual says so plainly rather than describing something
 * aspirational.
 */
public final class ManualController implements Initializable, Navigable {

    /**
     * The three manual tabs, in bar order — deliberately mirroring the three
     * {@code NavCard}s on the main menu, down to the same number and title
     * strings {@code MainMenuController} passes them, so the tab a reader
     * picks here is recognisably the card they clicked (or could not click)
     * there.
     *
     * <p>{@code built} marks whether the slot holds a real simulation. All
     * three tabs are clickable regardless — a reserved tab still opens a page,
     * it just opens one that explains the slot is empty.
     */
    private enum Tab {
        PENDULUM("01", "N-Pendulum Chain", true),
        SLOT_TWO("02", "N-Body Gravity",  true),
        SLOT_THREE("03", "Coming Soon",   false);

        final String number;
        final String title;
        final boolean built;

        Tab(String number, String title, boolean built) {
            this.number = number;
            this.title  = title;
            this.built  = built;
        }
    }
    
    @FXML private ScrollPane scroll;
    @FXML private VBox content;
    @FXML private HBox tabBar;

    private final Map<Tab, Node>   pages      = new EnumMap<>(Tab.class);
    private final Map<Tab, Button> tabButtons = new EnumMap<>(Tab.class);
    private Tab active = Tab.PENDULUM;

    private SceneRouter router;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        pages.put(Tab.PENDULUM,   page(Tab.PENDULUM,   pendulumManual()));
        pages.put(Tab.SLOT_TWO,   page(Tab.SLOT_TWO,   slotTwoManual()));
        pages.put(Tab.SLOT_THREE, page(Tab.SLOT_THREE, slotThreeManual()));

        for (Tab t : Tab.values()) {
            Button b = new Button(t.number + "   " + t.title);
            b.getStyleClass().add("manual-tab");
            if (!t.built) b.getStyleClass().add("manual-tab-reserved");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> select(t));
            HBox.setHgrow(b, Priority.ALWAYS);
            tabButtons.put(t, b);
            tabBar.getChildren().add(b);
        }

        select(Tab.PENDULUM);
    }

    /**
     * Shows one simulation's manual. Always scrolls back to the top: the tabs
     * are three separate documents, so carrying the old tab's scroll position
     * into the new one would drop the reader into the middle of a page they
     * have not started.
     */
    private void select(Tab tab) {
        this.active = tab;
        Node page = pages.get(tab);
        content.getChildren().setAll(page);
        scroll.setVvalue(0);

        if (ThemeManager.getInstance().isReducedMotion()) {
            page.setOpacity(1.0);
        } else {
            page.setOpacity(0.0);
            FadeTransition fade = new FadeTransition(Duration.millis(140), page);
            fade.setToValue(1.0);
            fade.play();
        }

        for (Map.Entry<Tab, Button> e : tabButtons.entrySet()) {
            e.getValue().getStyleClass().remove("manual-tab-active");
            if (e.getKey() == tab) e.getValue().getStyleClass().add("manual-tab-active");
        }
    }

    /** Wraps one tab's blocks in a page, headed by its simulation banner. */
    private static Node page(Tab tab, List<Node> blocks) {
        VBox box = new VBox(0);
        box.getChildren().add(simulation(tab));
        box.getChildren().addAll(blocks);
        return box;
    }

    // -------------------------------------------------------------------------
    // Tab 01 — the N-Pendulum Chain. Parts One to Eight.
    // -------------------------------------------------------------------------

    private static List<Node> pendulumManual() {
        return List.of(
            lede("The chaotic pendulum chain: any number of coupled links, editable live, "
               + "with ten experiments that make chaos visible and measurable."),

            // ---------------------------------------------------------------
            part("PART ONE", "What is this program?"),

            heading("A pendulum chain"),
            text("A pendulum is a weight on a string that swings back and forth. A grandfather "
               + "clock has one. It is completely predictable — it swings out, it comes back, "
               + "it repeats."),
            text("Now attach a second pendulum to the bottom of the first, and a third to that. "
               + "You have a pendulum chain, and something strange happens: it stops being "
               + "predictable. It thrashes around in a way that never quite repeats."),
            text("This program simulates that chain. You choose how many links there are, how "
               + "long and heavy each one is, and how strong gravity is — then watch."),

            heading("What \"chaos\" means here"),
            text("Chaos is a technical word, not a synonym for messy. It means one specific thing:"),
            callout("Start two identical chains from almost the same position — a difference far "
                  + "too small to see — and within seconds they will be doing completely "
                  + "different things."),
            text("That is not a flaw in the simulation. It is a real property of the physical "
               + "system, and it is why weather forecasts stop working after about a week. Most "
               + "of this program exists to let you see that happen, and to measure it."),

            heading("What you can do with it"),
            bullets("Play with it — drag the pendulum, change gravity, watch it move",
                    "Demonstrate chaos — tools that make unpredictability visible",
                    "Measure it — graphs that turn what you see into numbers"),

            // ---------------------------------------------------------------
            part("PART TWO", "Starting up"),

            text("Run the program with:"),
            code("mvn javafx:run"),
            note("Two warnings always appear in the terminal, about \"restricted method\" and "
               + "\"sun.misc.Unsafe\". They come from the graphics library, not from this "
               + "program. Ignore them. Only a message containing the word Exception means "
               + "something is actually wrong."),
            text("On the main menu you will see three cards. Card 01, N-Pendulum Chain, is the "
               + "one that works — click it. Cards 02 and 03 are greyed out with dashed borders; "
               + "they are reserved for future simulations and cannot be clicked. See Part Nine."),

            // ---------------------------------------------------------------
            part("PART THREE", "Understanding the screen"),

            text("When the simulation opens, a three-link chain starts swinging immediately. The "
               + "screen is mostly empty on purpose — the pendulum gets the space, and controls "
               + "appear only when you ask for them."),
            row("← Menu", "Goes back. Also stops the simulation — time freezes until you return."),
            row("Tool rail (left)", "Three buttons that change what your mouse does."),
            row("The canvas", "The pendulum itself. Fully interactive — you can grab it."),
            row("[‹] button", "Top-right of the canvas. Opens the control sidebar."),
            callout("If you see almost nothing, that is correct. The sidebar and the graph both "
                  + "start closed so the pendulum has room. Click the [‹] button to open the "
                  + "controls."),

            // ---------------------------------------------------------------
            part("PART FOUR", "Your first two minutes"),

            text("Do these in order. It covers everything you need before the experiments."),
            steps("Just watch for 20 seconds. Notice the motion never repeats.",
                  "Press Space. Everything freezes. Press it again to resume.",
                  "Click and hold any ball, then move the mouse. The other links react — they "
                + "are physically connected.",
                  "Drag quickly and let go mid-motion. It flies off at the speed you were moving.",
                  "Hover over a ball without clicking. A panel shows its angle, speed, weight "
                + "and length.",
                  "Press R. Everything snaps back to the start.",
                  "Click [‹] at the canvas's top-right. The sidebar slides open."),
            expect("You now know how to pause, reset, grab the pendulum and open the controls. "
                 + "Everything else builds on this."),

            // ---------------------------------------------------------------
            part("PART FIVE", "Every control"),

            heading("The tool rail, on the left edge"),
            row("Edit", "Normal mode, on by default. Click to select a link, drag to move it."),
            row("Add", "Changes double-click to insert a new link after the one you click."),
            row("Snap", "Rounds your dragging to tidy values — angles to 15°, lengths to 0.25 m."),

            heading("Mouse actions on the pendulum"),
            row("Hover a ball", "Shows its live details"),
            row("Drag a ball", "Moves it; the rest of the chain reacts"),
            row("Release while moving", "Throws it"),
            row("Double-click a ball", "Opens a box to type exact values (or adds a link, in Add mode)"),
            row("Right-click a ball", "Deletes that link"),
            row("Right-click and drag", "Changes that link's length"),
            row("Drag the small \"g\" dot", "Changes which direction gravity pulls"),

            heading("Keyboard"),
            row("Space", "Pause and resume"),
            row("R", "Reset to the beginning"),
            row("→", "Advance one tiny step; works while paused"),

            heading("The sidebar's six tabs"),
            row("Motion", "Gravity, speed, pause/step/reset, reverse time, calculation method"),
            row("Chaos & Compare", "The four chaos demonstration tools"),
            row("Graphs", "Eight graphs, plus buttons to generate the two slow ones"),
            row("History", "A slider to replay the last ~30 seconds"),
            row("Links", "Each link's length, weight and angle. Presets and save/load."),
            row("Display", "Trails and image export — appearance only, no effect on the physics"),

            heading("Reading Live Status"),
            text("This block stays visible above the tabs, whichever tab you are on."),
            row("t", "How long the simulation has run. Not real-world seconds — speed affects it."),
            row("E", "Total energy in the system."),
            row("Drift", "How much the program is lying to you. Energy should never change, so "
                       + "any change is calculation error. Green is good, red is bad."),
            row("λ", "How fast chaos is happening. Only appears when Butterfly Effect is on. "
                   + "A positive number means genuinely chaotic."),

            // ---------------------------------------------------------------
            part("PART SIX", "The experiments"),

            text("Each one is self-contained: the steps, what you should see, and what it means. "
               + "Do them in order the first time — later ones assume the earlier ones."),

            experiment("EXPERIMENT 1", "Is the simulation trustworthy?"),
            text("Before believing anything else, check the program is calculating correctly. "
               + "Energy in a swinging pendulum should stay constant forever."),
            steps("Press R to reset.",
                  "Open the sidebar and watch the Drift line in Live Status.",
                  "Leave it running for two minutes without touching anything."),
            expect("Drift stays below 0.1% and stays green the whole time."),
            means("The program's arithmetic is sound. If drift had climbed into red, nothing "
                + "else in the program could be trusted — which is why this is the experiment "
                + "to run first, and the one to cite if anyone asks whether the simulation is "
                + "accurate."),

            experiment("EXPERIMENT 2", "The Butterfly Effect"),
            text("The headline demonstration. Fifty near-identical copies of your pendulum run "
               + "alongside it, each started a fraction of a degree different."),
            steps("Links tab → choose the preset \"Butterfly Twins\".",
                  "Chaos & Compare tab → click Butterfly Effect.",
                  "Watch continuously for a full minute. Do not touch anything."),
            expect("At first, one pendulum — the fifty copies are hidden exactly behind it. "
                 + "After a few seconds a faint blur appears. Then they fan out into a spray."),
            means("Nothing changed partway through. The copies differed from the very first "
                + "instant, by about one ten-millionth of a degree. It simply took that long "
                + "for the difference to grow big enough to see. Differences too small to "
                + "measure become differences too large to ignore."),
            note("Watch the λ line in Live Status turn from dashes into a positive number. "
               + "That number is the fanning-out, expressed as a rate."),

            experiment("EXPERIMENT 3", "The nudge you cannot undo"),
            text("Experiment 2 used copies. This one changes the real pendulum."),
            steps("Turn Butterfly Effect off. Press R.",
                  "Watch for 10 seconds to get a feel for the motion.",
                  "Click Perturb once, then keep watching."),
            expect("Absolutely nothing. The pendulum carries on exactly as before."),
            means("The nudge was about one millionth of a degree per second — invisible. But "
                + "the pendulum is now on a completely different future path. There is no undo, "
                + "and pressing Perturb again does not restore anything. That \"nothing "
                + "happened\" feeling is the entire lesson: the change is real but "
                + "unobservable, which is exactly why Experiment 2 needs fifty copies to make "
                + "the same point visible."),

            experiment("EXPERIMENT 4", "Choose your own difference"),
            text("Butterfly Effect uses a fixed, invisible difference. Here you pick how "
               + "different the second pendulum starts."),
            steps("Chaos & Compare tab → set the Δθ₁ slider to about 0.01.",
                  "Click A/B Compare and watch for a minute.",
                  "Turn it off, set the slider to 0.5, turn it back on.",
                  "Repeat once more at 1.0."),
            expect("A second, brightly-coloured chain marked B. At 0.01 it starts almost on top "
                 + "of yours and slowly peels away. At 1.0 it is obviously different immediately."),
            means("You are running the experiment yourself rather than watching a fixed demo. "
                + "It answers something the Butterfly Effect cannot: how big does a difference "
                + "need to be before it matters quickly?"),

            experiment("EXPERIMENT 5", "Running time backwards"),
            text("Two parts, and the contrast between them is the point."),
            steps("Links tab → delete links until only one remains → Apply Changes.",
                  "Let it swing 10 seconds. Note the t value.",
                  "Motion tab → turn on Reverse Time. Watch.",
                  "Now switch back to 3 links, reset, run 20 seconds, and reverse again."),
            expect("The single pendulum retraces its own path almost perfectly and t counts "
                 + "down. The three-link chain does not return to where it started."),
            means("This is not a video being rewound — the program is genuinely calculating "
                + "backwards. A simple pendulum reverses cleanly. A chaotic one cannot, because "
                + "the microscopic rounding errors that accumulated going forward get amplified "
                + "coming back. The failure is the result, not a bug."),

            experiment("EXPERIMENT 6", "Is it repeating? (Poincaré Section)"),
            text("Watching a chain tells you nothing about whether it repeats. This graph "
               + "answers it directly."),
            steps("Make sure you have at least 2 links.",
                  "Graphs tab → click Poincaré Section. The graph panel slides open.",
                  "Let it run for at least a minute. Longer is better."),
            expect("Dots appearing one at a time, slowly building into a scattered cloud."),
            means("The program takes one snapshot per swing — like a strobe light flashing at "
                + "the same moment each cycle. If the motion repeated, every snapshot would "
                + "land in the same spot and you would see a single dot. A scattered cloud "
                + "proves it never repeats."),
            note("With only one link you get an explanatory message instead. A single pendulum "
               + "has no second link to sample, and never behaves chaotically anyway."),

            experiment("EXPERIMENT 7", "Comparing calculation methods"),
            text("The program can do its arithmetic three different ways. This compares them "
               + "on equal terms."),
            steps("Motion tab → click Compare Integrators.",
                  "Read the graph that appears."),
            expect("Three labelled curves. RK4 sits lowest and flattest. Symplectic Euler sits "
                 + "higher but wobbles up and down rather than climbing steadily."),
            means("Each curve shows how far that method's energy has drifted from correct — "
                + "lower is better. The interesting result is the shape: Symplectic Euler is "
                + "less accurate, but its error never grows without limit. That trade-off — "
                + "cheaper and rougher, but stable forever — is why simpler methods are still "
                + "used in real-time games."),
            note("Your live pendulum is untouched throughout. The comparison runs on three "
               + "separate throwaway copies."),

            experiment("EXPERIMENT 8", "Where chaos begins (Bifurcation Map)"),
            text("Everything so far studied one setup. This studies ninety at once."),
            steps("Graphs tab → click Generate Bifurcation Map.",
                  "While the progress bar runs, drag the pendulum to confirm the program stays "
                + "responsive.",
                  "When it finishes, the graph switches to the result automatically."),
            expect("A chart made of vertical columns of dots. Some columns are a single dot, "
                 + "others two, others a smeared vertical band."),
            means("Each column is a completely separate simulation with a slightly different "
                + "starting angle. One dot means that setup repeats simply. Two dots means it "
                + "repeats every second swing. A smear means chaotic. Reading left to right "
                + "shows the transition from orderly to chaotic."),

            experiment("EXPERIMENT 9", "The map of predictability (Basin Fractal)"),
            text("The most expensive thing the program does: 40,000 separate simulations, run "
               + "in parallel across every core of your processor."),
            steps("Graphs tab → click Generate Basin Fractal.",
                  "Wait about 11 seconds, watching the progress bar."),
            expect("An image. Large smooth dark areas, bright magenta and blue areas, and "
                 + "between them a wildly intricate, shredded-looking boundary."),
            means("Imagine two dials — one sets the top link's starting angle, the other sets "
                + "the bottom link's. The program tries every combination, times how long until "
                + "the bottom link swings over the top, and colours that spot accordingly. Dark "
                + "means it never flips; bright means it flips fast. The shredded boundary is "
                + "the fractal: zoom in anywhere along it and it looks just as shredded, "
                + "forever. It is a picture of exactly where prediction becomes impossible."),

            experiment("EXPERIMENT 10", "Hearing the difference"),
            text("Your ears notice patterns your eyes miss in a tangle."),
            steps("Set your volume to a comfortable level.",
                  "Reduce the chain to 1 link and turn on Sonify. Listen.",
                  "Switch to 3 links and listen again."),
            expect("With one link, a steady rising-and-falling siren that repeats. With three, "
                 + "an erratic warble with no pattern."),
            means("The pitch follows the speed of the bottom ball — low when slow, high when "
                + "fast. Regular motion sounds regular; chaotic motion sounds chaotic. It is "
                + "the same information the graphs show, delivered to a sense that is very good "
                + "at spotting repetition."),

            // ---------------------------------------------------------------
            part("PART SEVEN", "Saving your work"),

            heading("Six ready-made setups"),
            text("Links tab → the dropdown at the top. Each loads instantly."),
            row("Classic Double Pendulum", "The textbook chaotic system — two equal links"),
            row("Near-Vertical Knife Edge", "Balanced almost straight up, then falls"),
            row("Heavy-Tip Whip", "Light links whipping a heavy end weight"),
            row("30-Link Rope", "So many links it moves like a rope, not a pendulum"),
            row("Small-Angle Harmonic", "Gentle and predictable — the opposite of chaos"),
            row("Butterfly Twins", "The best starting point for the Butterfly Effect"),

            heading("Saving, loading and exporting"),
            bullets("Save writes your setup to a .pendulum file — plain text you can open in any editor",
                    "Load reads one back. A damaged file shows a clear message and changes nothing.",
                    "Export CSV (Graphs tab) gives you the graph's numbers, openable in Excel",
                    "Export Trace Art (Display tab) saves a picture of the canvas — turn trails "
                  + "on and let it run a minute first"),

            heading("Describing a setup in words"),
            text("The Parse & Apply box accepts short descriptions:"),
            code("5 links, moon gravity, horizontal\n"
               + "3 links, heavy first link, gravity of 15\n"
               + "2 links, 45 degrees"),
            text("It recognises a fixed list of phrases — link counts, named gravity (moon, "
               + "mars, jupiter, earth), high/low/zero gravity, angles in degrees, and "
               + "heavy/light/long/short first/last link. It is a keyword matcher, not an AI; "
               + "anything it does not recognise is left unchanged."),

            // ---------------------------------------------------------------
            part("PART EIGHT", "If something looks wrong"),

            row("Almost nothing on screen", "Normal. The sidebar and graph start closed. Click [‹]."),
            row("Warnings in the terminal", "Normal — they come from the graphics library. Only "
                                          + "Exception matters."),
            row("Time not advancing", "You are paused, or on the menu — the simulation stops "
                                    + "when its screen is not visible."),
            row("Accessibility settings do nothing", "Reduced motion and the colour-blind "
                                                   + "palette apply the next time you open the "
                                                   + "simulation. Go to the menu and back."),
            row("Poincaré shows only a message", "You have one link. It needs at least two."),
            row("\"Maximum of N links\"", "Expected. The limit is measured on your computer at "
                                        + "each launch, so the number legitimately differs "
                                        + "between runs."),
            row("Drift turns red", "The calculation is struggling. Reduce speed, lower gravity, "
                                 + "use fewer links, or switch back to RK4."),
            row("A red banner appears", "The simulation has become numerically unstable. Press R."),
            row("Ghosts stay after Reset", "Known behaviour — Reset restarts your pendulum but "
                                         + "leaves the copies running. Toggle them off manually.")
        );
    }

    // -------------------------------------------------------------------------
    // Tab 02 — the N-Body Gravity simulation. Parts One to Eight, the same
    // structure tab 01 uses — see this class's own javadoc for why that
    // structure (not a copy of tab 01's content) is the promise being kept.
    // -------------------------------------------------------------------------

    private static List<Node> slotTwoManual() {
        return List.of(
            lede("Real gravity, no shortcuts: any number of bodies, each pulling on every other "
               + "one, starting from a roughly-real model of our own solar system."),

            // ---------------------------------------------------------------
            part("PART ONE", "What is this program?"),

            heading("Gravity, done honestly"),
            text("Drop two masses near each other and they pull on one another — every mass "
               + "attracts every other mass, always. A planet orbiting a star is just this rule "
               + "playing out for a very long time. This program computes that pull directly, "
               + "between every pair of bodies, at every instant."),
            text("Load the default scene and you get the Sun, the eight planets, their major "
               + "moons, a few dwarf planets and asteroids, and Halley's Comet — 34 bodies in "
               + "total, none of them scripted to move in a particular way. Every orbit you see "
               + "is the actual arithmetic, not an animation of one."),

            heading("How this differs from the pendulum"),
            text("The pendulum chain (tab 01) is chaotic because its links fight a fixed pivot. "
               + "Nothing here is pinned to anything — this is a free-floating system, and that "
               + "changes what's worth watching for: not sensitivity to tiny differences, but "
               + "whether energy, momentum, and angular momentum genuinely stay constant with "
               + "nothing external enforcing it."),

            heading("What you can do with it"),
            bullets("Watch it — the Moon circles Earth, not the Sun; Neptune's moon Triton "
                  + "orbits backwards",
                    "Touch it — drag any body, add a new one, delete one, edit its numbers exactly",
                    "Verify it — three conserved quantities, tracked live, that this program has "
                  + "nothing to hide behind"),

            // ---------------------------------------------------------------
            part("PART TWO", "Getting here"),

            text("From the main menu, click card 02, N-Body Gravity. The screen opens with the "
               + "home solar system already loaded and running."),
            note("If you haven't launched the program itself yet, see tab 01's Part Two — the "
               + "launch command and the two harmless terminal warnings are the same for every "
               + "screen in this app."),

            // ---------------------------------------------------------------
            part("PART THREE", "Understanding the screen"),

            text("The layout matches tab 01's on purpose — the same shell, a different scene."),
            row("← Menu", "Goes back. Also stops the simulation — time freezes until you return."),
            row("Tool rail (left)", "Two buttons: Edit and Add. No third \"Snap\" tool — free-form "
                                   + "body placement has no grid to snap to."),
            row("The canvas", "The solar system itself, true-to-scale in position (zoom out far "
                             + "enough and you'll see just how empty space really is)."),
            row("[‹] button", "Top-right of the canvas. Opens the control sidebar."),
            callout("Body SIZE on screen is not to scale — the Sun's real radius is under 1% of "
                  + "Earth's orbit, so true-to-scale bodies would be invisible dots. Distances "
                  + "and orbits are real; circle sizes are presentational."),

            // ---------------------------------------------------------------
            part("PART FOUR", "Your first two minutes"),

            steps("Just watch for 30 seconds at the default speed — the inner planets are "
                + "moving, just slowly at 1x.",
                  "Open the sidebar ([‹]) and go to the Motion tab. Drag the Sim Speed slider "
                + "right. Watch Mercury and Venus lap the screen.",
                  "Hover over any body without clicking — a panel shows its exact mass, radius, "
                + "position, and velocity.",
                  "Click a body once. It pauses the simulation and highlights that body — the "
                + "same press-to-pause rule tab 01 uses.",
                  "Press Space to resume. Click and drag a body instead — it follows your "
                + "pointer, then flies off with whatever velocity you released it at.",
                  "Press R. The current setup restarts from its own beginning."),
            expect("You now know how to change speed, inspect a body, and grab one. Everything "
                 + "else builds on this."),

            // ---------------------------------------------------------------
            part("PART FIVE", "Every control"),

            heading("The tool rail, on the left edge"),
            row("Edit", "Normal mode, on by default. Click a body to select it, drag to move it."),
            row("Add", "Click empty space to place a new body there, pre-filled with that position."),

            heading("Mouse actions on the canvas"),
            row("Hover a body", "Shows its live details (mass, radius, position, velocity)"),
            row("Click a body", "Selects it and pauses the simulation"),
            row("Drag a body", "Moves it; releasing mid-motion gives it that velocity"),
            row("Double-click a body", "Opens a dialog to type exact mass/radius/position/velocity"),
            row("Right-click a body", "Deletes it, after a confirmation (refused if only one remains)"),
            row("Click empty space (Add tool)", "Opens the Add dialog at that position"),

            heading("Keyboard"),
            row("Space", "Pause and resume"),
            row("R", "Restart the current setup from its own beginning"),
            row("→", "Advance one tiny step; works while paused"),

            heading("The sidebar's three tabs"),
            row("Motion", "G, simulation speed, pause/step/reset, integrator picker"),
            row("Bodies", "Preset picker and a compact, clickable list of every body"),
            row("Display", "Follow Center of Mass — appearance only, no effect on the physics"),

            heading("Reading Live Status"),
            text("This block stays visible above the tabs, whichever tab you are on."),
            row("N", "How many bodies are currently in the scene."),
            row("t", "Simulated time elapsed, in seconds — not real-world seconds; see Sim Speed."),
            row("E", "Total energy in the system."),
            row("E Drift", "Energy conservation error, as a percentage of the starting value. "
                          + "Green is good, red is bad — identical in spirit to tab 01's Drift."),
            row("p Drift / L Drift", "Momentum and angular-momentum conservation error. Neither "
                                    + "has a pendulum equivalent — a pinned pivot constantly "
                                    + "exerts force and torque, which breaks both. Nothing pins "
                                    + "this scene, so both are genuinely, checkably conserved."),

            heading("The G and speed sliders"),
            text("G's slider runs from zero to twice the real gravitational constant, so the "
               + "real value sits in the middle with room to push gravity stronger or weaker in "
               + "either direction."),
            text("The speed slider is a LOG scale, not a straight line — the numbers involved "
               + "make a straight slider useless. Mercury orbits the Sun in about 88 days; "
               + "Neptune takes about 165 years. Watching Earth complete one real year in "
               + "roughly 20 seconds needs a speed multiplier past a million. A log scale gives "
               + "usable control across that entire range instead of cramming everything "
               + "meaningful into the slider's first hair's-width."),

            // ---------------------------------------------------------------
            part("PART SIX", "The experiments"),

            experiment("EXPERIMENT 1", "Is the simulation trustworthy?"),
            text("The same question tab 01 asks first, for the same reason: before believing "
               + "anything else, check the arithmetic itself is sound."),
            steps("Press R to reset.",
                  "Open the sidebar and watch E Drift in Live Status.",
                  "Turn the speed up a fair way and leave it running for a minute."),
            expect("E Drift stays small and green, even at high speed."),
            means("RK4 is conserving energy the way it should. This is the number to cite if "
                + "anyone asks whether the physics here is real."),

            experiment("EXPERIMENT 2", "Conservation, three ways"),
            text("This is the experiment tab 01 cannot run. A pendulum's pivot is an external "
               + "anchor, constantly pushing and twisting to stay fixed — that quietly breaks "
               + "momentum and angular-momentum conservation for it. Nothing here is anchored."),
            steps("Reset, then watch p Drift and L Drift alongside E Drift for a minute or two."),
            expect("All three stay small. p Drift in particular stays near zero — the default "
                 + "scene is built with zero net momentum on purpose."),
            means("Nothing is propping this system up from outside. Every gram of momentum and "
                + "every bit of spin it started with is still there, because nothing exists to "
                + "take it away."),

            experiment("EXPERIMENT 3", "Moons follow planets, not the Sun"),
            steps("Bodies tab → click \"Moon\" in the list. Its parameter dialog opens; note its "
                + "position and velocity, then Cancel.",
                  "Watch the canvas (zoom into Earth if needed, scroll wheel to zoom). Follow "
                + "the Moon for one full loop."),
            expect("The Moon traces a small circle around Earth while Earth itself is doing a "
                 + "much larger one around the Sun — a circle riding on a circle."),
            means("There is no explicit \"Moon orbits Earth\" rule anywhere in this program. It "
                + "falls out entirely from Earth simply being far more massive than the Moon and "
                + "far closer to it than the Sun is."),

            experiment("EXPERIMENT 4", "Triton runs backwards"),
            steps("Zoom out to Neptune. Watch its moon Triton for one full orbit.",
                  "Compare the direction against any other moon in the system — Titan around "
                + "Saturn is an easy one to hold in view at the same zoom level."),
            expect("Every other moon in the default scene orbits the same way its planet orbits "
                 + "the Sun. Triton alone goes the opposite direction."),
            means("This isn't a bug or a random choice — Triton really does orbit retrograde, "
                + "one of the pieces of evidence that it's a captured object rather than one "
                + "that formed alongside Neptune."),

            experiment("EXPERIMENT 5", "Follow the center of mass"),
            steps("Display tab → turn on Follow Center of Mass.",
                  "Let it run a while, then drag a heavy body (try Jupiter) hard, or delete one."),
            expect("The view recenters smoothly on wherever the system's balance point actually "
                 + "is, rather than the fixed point bodies started around."),
            means("The Sun itself doesn't sit perfectly still — massive planets, Jupiter above "
                + "all, tug it into a small wobble around the system's true center of mass. This "
                + "toggle keeps that point on screen instead of the arbitrary spot the scene "
                + "happened to be built around."),

            experiment("EXPERIMENT 6", "Build your own small system"),
            steps("Add tool → click empty space to place a body. Give it a large mass.",
                  "Add two or three lighter bodies nearby with some sideways velocity.",
                  "Switch back to Edit and watch what happens — some may orbit, some may escape."),
            expect("Anything from a stable little orbit to a body flung off-screen entirely, "
                 + "depending on the numbers you typed."),
            means("Getting a genuinely stable orbit by typing numbers by hand is harder than it "
                + "looks — it's the same trial-and-error every orbital mechanics student goes "
                + "through, here made immediate instead of theoretical."),

            experiment("EXPERIMENT 7", "Comparing calculation methods"),
            text("Same three choices tab 01 offers, applied to orbital rather than pendulum "
               + "motion."),
            steps("Motion tab → switch Integrator from RK4 to Symplectic Euler, then Velocity "
                + "Verlet, watching E Drift after each switch."),
            expect("RK4 keeps drift lowest. The other two are visibly rougher, but keep running "
                 + "without the bodies flying apart."),
            means("Switching integrators mid-run must never freeze half the bodies in place — if "
                + "you ever see bodies stop moving the instant you switch, that's the exact bug "
                + "this program's own test suite was written to catch."),

            // ---------------------------------------------------------------
            part("PART SEVEN", "Scenarios"),

            heading("One preset today"),
            text("Bodies tab → the dropdown at the top. \"Home Solar System\" is the only entry "
               + "for now — Sun, eight planets, their major moons, Ceres, Vesta, Pluto and "
               + "Charon, and Halley's Comet, 34 bodies in total, all built from approximate "
               + "real-world figures."),
            note("Additional presets (other real star systems) and saving/loading your own "
               + "scenes to a file are both planned but not built yet — this tab will grow the "
               + "same way tab 01's Links tab did."),

            heading("Positions are approximate, on purpose"),
            text("Every orbit starts from a circular approximation, not a mission-planning-grade "
               + "ephemeris — good enough to look and behave right, not good enough to point a "
               + "telescope with. Halley's Comet in particular has a real orbit far too "
               + "stretched-out for a circular approximation to resemble; it's included anyway, "
               + "flagged here as a known simplification rather than a mistake."),

            // ---------------------------------------------------------------
            part("PART EIGHT", "If something looks wrong"),

            row("Almost nothing on screen", "Normal. The sidebar starts closed. Click [‹]."),
            row("Everything looks frozen", "You're likely at 1x speed. Motion tab → drag Sim "
                                         + "Speed right — the outer planets barely move even at "
                                         + "high speed; that's realistic, not broken."),
            row("A body vanished", "It may have been flung out of view by a close encounter, or "
                                 + "deleted. Check the Bodies tab's list — anything still in the "
                                 + "scene is listed there regardless of where it's drifted to."),
            row("Warnings in the terminal", "Normal — they come from the graphics library. Only "
                                          + "Exception matters."),
            row("A red banner appears", "The simulation has become numerically unstable — try "
                                      + "Reset, a smaller speed, or switching back to RK4."),
            row("\"Can't delete the only remaining body\"", "Expected — a zero-body scene has "
                                                           + "nothing left to simulate or select."),
            row("Accessibility settings do nothing", "Reduced motion and the colour-blind "
                                                   + "palette apply the next time you open this "
                                                   + "screen. Go to the menu and back.")
        );
    }

    // -------------------------------------------------------------------------
    // Tab 03 — also reserved, deliberately not a copy of tab 02: 02 covers
    // what you are looking at, 03 covers what changes when a slot is filled.
    // -------------------------------------------------------------------------

    private static List<Node> slotThreeManual() {
        return List.of(
            lede("A third slot, waiting for its simulation. Its status is identical to card 02: "
               + "reserved, disabled, and empty."),

            heading("How to tell when a slot has been filled"),
            text("You do not need to check the code. A reserved card is dashed and dim; a real "
               + "one is solid-bordered, brightly titled, and expands when you hover it to show "
               + "what it demonstrates. If card 02 or 03 ever looks like card 01, it is real."),
            row("Reserved", "Dashed border, dimmed text, ignores clicks, skipped by Tab"),
            row("Built", "Solid border, full-strength title, expands on hover, opens on click"),

            heading("What happens when one is built"),
            text("Filling a slot is a small, contained change — the card is told to configure "
               + "itself as a real destination instead of a placeholder, and pointed at the new "
               + "screen. Nothing about the menu's layout, spacing or navigation changes."),
            note("This tab changes with it. In place of what you are reading now it gains the "
               + "same structure as tab 01 — an introduction, a first-two-minutes walkthrough, "
               + "a control reference, its own experiments, and its own troubleshooting list — "
               + "and the tab's label changes from \"Coming Soon\" to the simulation's name.")
        );
    }

    // -------------------------------------------------------------------------
    // Content helpers — the manual's whole vocabulary. Each returns a styled
    // node; the style classes live in theme.css under "Manual screen".
    // -------------------------------------------------------------------------

    /**
     * The banner heading a tab's page — the number, title and a status pill.
     *
     * <p>It restates on the page what the tab bar already says, which is
     * deliberate: the bar has room for a label, not for the distinction
     * between a simulation you can open and a slot that is still empty. The
     * pill is where that is stated unambiguously, and the panel's styling
     * (solid accent rule versus a dashed grey outline) echoes the menu card
     * the tab corresponds to.
     *
     * <p>The status colour follows {@link Tab#built}: green for a simulation
     * you can actually open, muted grey for a reserved slot — never a hopeful
     * accent, because an empty slot should not advertise itself as a feature.
     */
    private static Node simulation(Tab tab) {
        Label num = new Label(tab.number);
        num.getStyleClass().add("manual-sim-number");

        Label name = new Label(tab.title);
        name.getStyleClass().add("manual-sim-title");
        name.setWrapText(true);

        Label pill = new Label(tab.built ? "AVAILABLE" : "NOT YET BUILT");
        pill.getStyleClass().addAll("manual-sim-status",
                tab.built ? "manual-sim-status-live" : "manual-sim-status-soon");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox line = new HBox(14, num, name, spacer, pill);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(line);
        box.getStyleClass().add(tab.built ? "manual-sim" : "manual-sim-reserved");
        box.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(box, new Insets(4, 0, 12, 0));
        return box;
    }

    /** The one-paragraph summary that opens a simulation section. */
    private static Node lede(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("manual-lede");
        l.setWrapText(true);
        VBox.setMargin(l, new Insets(0, 0, 8, 0));
        return l;
    }

    /** A major part divider: a small accent eyebrow above a large title. */
    private static Node part(String eyebrow, String title) {
        Label kicker = new Label(eyebrow);
        kicker.getStyleClass().add("manual-part-kicker");
        Label heading = new Label(title);
        heading.getStyleClass().add("manual-part-title");
        heading.setWrapText(true);
        VBox box = new VBox(2, kicker, heading);
        VBox.setMargin(box, new Insets(38, 0, 14, 0));
        return box;
    }

    /** A section heading within a part. */
    private static Node heading(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("manual-heading");
        l.setWrapText(true);
        VBox.setMargin(l, new Insets(20, 0, 6, 0));
        return l;
    }

    /** The numbered banner that opens each experiment. */
    private static Node experiment(String number, String title) {
        Label n = new Label(number);
        n.getStyleClass().add("manual-exp-number");
        Label t = new Label(title);
        t.getStyleClass().add("manual-exp-title");
        t.setWrapText(true);
        VBox box = new VBox(1, n, t);
        box.getStyleClass().add("manual-exp-head");
        VBox.setMargin(box, new Insets(28, 0, 8, 0));
        return box;
    }

    /** A paragraph of body text. */
    private static Node text(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("manual-text");
        l.setWrapText(true);
        VBox.setMargin(l, new Insets(0, 0, 10, 0));
        return l;
    }

    /** A numbered list of actions to perform. */
    private static Node steps(String... items) {
        VBox box = new VBox(5);
        for (int i = 0; i < items.length; i++) {
            Label num = new Label((i + 1) + ".");
            num.getStyleClass().add("manual-step-number");
            num.setMinWidth(22);
            Label body = new Label(items[i]);
            body.getStyleClass().add("manual-text");
            body.setWrapText(true);
            HBox line = new HBox(4, num, body);
            HBox.setHgrow(body, Priority.ALWAYS);
            box.getChildren().add(line);
        }
        VBox.setMargin(box, new Insets(2, 0, 12, 4));
        return box;
    }

    /** An unnumbered list, for things that are not a sequence. */
    private static Node bullets(String... items) {
        VBox box = new VBox(5);
        for (String item : items) {
            Label dot = new Label("•");
            dot.getStyleClass().add("manual-step-number");
            dot.setMinWidth(14);
            Label body = new Label(item);
            body.getStyleClass().add("manual-text");
            body.setWrapText(true);
            HBox line = new HBox(4, dot, body);
            HBox.setHgrow(body, Priority.ALWAYS);
            box.getChildren().add(line);
        }
        VBox.setMargin(box, new Insets(2, 0, 12, 4));
        return box;
    }

    /** A label/description pair — the manual's stand-in for a two-column table. */
    private static Node row(String term, String description) {
        Label t = new Label(term);
        t.getStyleClass().add("manual-row-term");
        t.setMinWidth(168);
        t.setMaxWidth(168);
        t.setWrapText(true);
        Label d = new Label(description);
        d.getStyleClass().add("manual-text");
        d.setWrapText(true);
        HBox line = new HBox(12, t, d);
        HBox.setHgrow(d, Priority.ALWAYS);
        line.getStyleClass().add("manual-row");
        VBox.setMargin(line, new Insets(0, 0, 2, 0));
        return line;
    }

    /** "What you should see" — the success criterion for an experiment. */
    private static Node expect(String s) {
        return banner("You should see:  " + s, "manual-expect");
    }

    /** "What it means" — the lesson an experiment teaches. */
    private static Node means(String s) {
        return banner("What it means:  " + s, "manual-means");
    }

    /** An aside worth knowing but not essential to the step. */
    private static Node note(String s) {
        return banner("Note:  " + s, "manual-note");
    }

    /** An emphasised standalone statement. */
    private static Node callout(String s) {
        return banner(s, "manual-callout");
    }

    private static Node banner(String s, String styleClass) {
        Label l = new Label(s);
        l.getStyleClass().addAll("manual-banner", styleClass);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(l, new Insets(2, 0, 10, 0));
        return l;
    }

    /** A literal command or example, in monospace. */
    private static Node code(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("manual-code");
        l.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(l, new Insets(2, 0, 12, 0));
        return l;
    }

    @FXML
    private void handleBack() {
        router.back();
    }

    @Override
    public void setRouter(SceneRouter router) {
        this.router = router;
    }

    /**
     * Re-selects the current tab on every visit. The tab you were last
     * reading is kept — coming back from the simulation to check one more
     * thing should not throw away your place in the manual — but the scroll
     * resets to the top, so re-opening never drops you mid-sentence in the
     * middle of Part Six.
     */
    @Override
    public void onShow() {
        select(active);
    }
}
