package ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import app.AppState;
import core.BotController;
import core.Generation;
import core.HuntMethodType;
import core.KeySender;
import core.TargetPlatform;
import core.switchcontrol.SwitchNxbtKeySender;
import methods.HuntMethod;
import methods.MethodRegistry;
import vision.capture.RectanglePicker;

public class MainWindow extends JFrame {

    // ========= Debug helpers =========
    private static final boolean DEBUG = true;

    private static void log(String msg) {
        if (!DEBUG) return;
        System.out.println("[MainWindow] " + msg);
    }

    private static void log(String msg, Throwable t) {
        if (!DEBUG) return;
        System.out.println("[MainWindow] " + msg);
        if (t != null) t.printStackTrace(System.out);
    }

    private static String rectToString(Rectangle r) {
        if (r == null) return "(null)";
        return "Rect{x=" + r.x + ", y=" + r.y + ", w=" + r.width + ", h=" + r.height + "}";
    }

    // ========= UI =========
    private final JComboBox<TargetPlatform> platformDropdown = new JComboBox<>(TargetPlatform.values());
    private final JComboBox<Generation> genDropdown = new JComboBox<>(Generation.values());
    private final JLabel statusLabel = new JLabel("Status: Idle");
    private final JLabel activeLabel = new JLabel("Active: None");

    // platform / switch UI
    private final JLabel platformLabel = new JLabel("Platform:");
    private final JLabel switchHostLabel = new JLabel("Switch Host:");
    private final JTextField switchHostField = new JTextField(12);
    private final JLabel switchPortLabel = new JLabel("Switch Port:");
    private final JTextField switchPortField = new JTextField(6);

    // capture area UI
    private final JButton setCaptureAreaBtn = new JButton("Set Capture Area");
    private final JLabel captureAreaLabel = new JLabel("Capture Area: (not set)");

    // game + pokedex inputs
    private final JComboBox<String> gameDropdown = new JComboBox<>(new String[] { "FRLG", "RSE" });
    private final JTextField pokedexField = new JTextField("1", 6);

    private final JButton softResetBtn = new JButton("Soft Reset Legendary");
    private final JButton spinAroundBtn = new JButton("Spin Around");
    private final JButton eggHatchBtn = new JButton("Egg Hatch");
    private final JButton starterRSEBtn = new JButton("RSE Starter");
    private final JButton starterFRLGBtn = new JButton("FRLG Starter");
    private final JButton stopBtn = new JButton("Stop");

    private final BotController controller;
    private final KeySender emulatorKeys;
    private final AppState state;

    // Prevent multiple concurrent picker runs
    private volatile boolean pickingCaptureArea = false;

    public MainWindow(BotController controller, KeySender keys, AppState state) {
        super("Pokémon Shiny Hunting Bot");
        this.controller = controller;
        this.emulatorKeys = keys;
        this.state = state;

        log("Constructor: controller=" + (controller == null ? "null" : controller.getClass().getSimpleName())
                + " emulatorKeys=" + (keys == null ? "null" : keys.getClass().getSimpleName())
                + " state@" + System.identityHashCode(state));

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(900, 360);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout(12, 12));
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        loadStateIntoUi();
        wireActions();
        refreshPlatformFields();
        refreshUiState();
    }

    private JPanel buildTopPanel() {
        log("buildTopPanel()");
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        panel.add(platformLabel);
        panel.add(platformDropdown);

        panel.add(switchHostLabel);
        panel.add(switchHostField);

        panel.add(switchPortLabel);
        panel.add(switchPortField);

        panel.add(new JLabel("Generation:"));
        panel.add(genDropdown);

        panel.add(new JLabel("Game:"));
        panel.add(gameDropdown);

        panel.add(new JLabel("Pokédex #:"));
        panel.add(pokedexField);

        panel.add(setCaptureAreaBtn);
        panel.add(captureAreaLabel);

        return panel;
    }

    private JPanel buildCenterPanel() {
        log("buildCenterPanel()");
        JPanel panel = new JPanel(new GridLayout(3, 2, 10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Choose a hunting method"));
        panel.add(softResetBtn);
        panel.add(spinAroundBtn);
        panel.add(eggHatchBtn);
        panel.add(starterRSEBtn);
        panel.add(starterFRLGBtn);
        panel.add(stopBtn);
        return panel;
    }

    private JPanel buildBottomPanel() {
        log("buildBottomPanel()");
        JPanel panel = new JPanel(new GridLayout(2, 1));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        panel.add(statusLabel);
        panel.add(activeLabel);
        return panel;
    }

    private void loadStateIntoUi() {
        platformDropdown.setSelectedItem(state.getTargetPlatform());
        switchHostField.setText(state.getSwitchHost());
        switchPortField.setText(String.valueOf(state.getSwitchPort()));
    }

    private void refreshPlatformFields() {
        TargetPlatform selected = (TargetPlatform) platformDropdown.getSelectedItem();
        boolean switchMode = selected == TargetPlatform.NINTENDO_SWITCH;

        switchHostLabel.setVisible(switchMode);
        switchHostField.setVisible(switchMode);
        switchPortLabel.setVisible(switchMode);
        switchPortField.setVisible(switchMode);

        revalidate();
        repaint();
    }

    private void wireActions() {
        log("wireActions() on EDT? " + SwingUtilities.isEventDispatchThread());

        platformDropdown.addActionListener(e -> {
            TargetPlatform selected = (TargetPlatform) platformDropdown.getSelectedItem();
            if (selected != null) {
                state.setTargetPlatform(selected);
                log("Platform changed to " + selected);
            }
            refreshPlatformFields();
        });

        // rectangle picker wiring (FIXED: run off EDT)
        setCaptureAreaBtn.addActionListener(e -> {
            log("SetCaptureArea clicked. EDT? " + SwingUtilities.isEventDispatchThread()
                    + " pickingCaptureArea=" + pickingCaptureArea
                    + " hasCaptureRect(before)=" + state.hasCaptureRect()
                    + " captureRect(before)=" + rectToString(state.getCaptureRect()));

            if (pickingCaptureArea) {
                log("Ignoring SetCaptureArea click because a picker is already running.");
                return;
            }

            pickingCaptureArea = true;
            setCaptureAreaBtn.setEnabled(false);
            captureAreaLabel.setText("Capture Area: (selecting...)");

            Thread t = new Thread(() -> {
                log("Picker thread started. EDT? " + SwingUtilities.isEventDispatchThread());
                Rectangle r = null;
                Throwable err = null;

                try {
                    r = RectanglePicker.pickRectangle();
                    log("Picker returned rectangle: " + rectToString(r));
                } catch (Throwable ex) {
                    err = ex;
                    log("Picker threw exception: " + ex.getClass().getName() + " -> " + ex.getMessage(), ex);
                }

                final Rectangle picked = r;
                final Throwable pickedErr = err;

                SwingUtilities.invokeLater(() -> {
                    log("Picker result handling on EDT? " + SwingUtilities.isEventDispatchThread()
                            + " picked=" + rectToString(picked)
                            + " err=" + (pickedErr == null ? "null" : pickedErr.getClass().getName()));

                    try {
                        if (pickedErr == null && picked != null) {
                            state.setCaptureRect(picked);
                            log("state.setCaptureRect done. hasCaptureRect(after)=" + state.hasCaptureRect()
                                    + " captureRect(after)=" + rectToString(state.getCaptureRect()));

                            captureAreaLabel.setText("Capture Area: " + picked.width + "x" + picked.height
                                    + " @ (" + picked.x + "," + picked.y + ")");
                        } else {
                            captureAreaLabel.setText(state.hasCaptureRect()
                                    ? ("Capture Area: " + state.getCaptureRect().width + "x"
                                            + state.getCaptureRect().height + " @ (" + state.getCaptureRect().x + ","
                                            + state.getCaptureRect().y + ")")
                                    : "Capture Area: (not set)");

                            if (pickedErr != null) {
                                log("Picker did not produce a rectangle. See console for details.");
                            } else {
                                log("Picker cancelled (no rectangle).");
                            }
                        }
                    } finally {
                        pickingCaptureArea = false;
                        setCaptureAreaBtn.setEnabled(true);
                        log("SetCaptureArea UI reset. pickingCaptureArea=" + pickingCaptureArea);
                    }
                });

            }, "CaptureAreaPickerThread");

            t.setDaemon(true);
            t.start();
        });

        softResetBtn.addActionListener(e -> {
            log("SoftReset button clicked.");
            startMethod(HuntMethodType.SOFT_RESET);
        });

        spinAroundBtn.addActionListener(e -> {
            log("SpinAround button clicked.");
            startMethod(HuntMethodType.SPIN_METHOD);
        });

        eggHatchBtn.addActionListener(e -> {
            log("EggHatch button clicked.");
            startMethod(HuntMethodType.EGG_HATCH);
        });

        starterRSEBtn.addActionListener(e -> {
            log("StarterRSE button clicked.");
            startMethod(HuntMethodType.STARTER_RSE);
        });

        starterFRLGBtn.addActionListener(e -> {
            log("StarterFRLG button clicked.");
            startMethod(HuntMethodType.STARTER_FRLG);
        });

        stopBtn.addActionListener(e -> {
            log("Stop button clicked. controller.isRunning(before)=" + controller.isRunning());
            controller.stop();
            log("controller.stop() called. controller.isRunning(after)=" + controller.isRunning());
            refreshUiState();
        });
    }

    private void startMethod(HuntMethodType type) {
        log("startMethod(" + type + ") called. EDT? " + SwingUtilities.isEventDispatchThread()
                + " state@" + System.identityHashCode(state)
                + " hasCaptureRect=" + state.hasCaptureRect()
                + " captureRect=" + rectToString(state.getCaptureRect()));

        if (!state.hasCaptureRect()) {
            log("startMethod blocked: capture area not set.");
            JOptionPane.showMessageDialog(
                    this,
                    "Click 'Set Capture Area' first, then draw a rectangle around the game screen area.",
                    "Capture Area Required",
                    JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        if (!savePlatformSettingsFromUi()) {
            return;
        }

        Generation gen = (Generation) genDropdown.getSelectedItem();
        log("Selected Generation=" + gen);
        if (gen == null) return;

        String gameCode = (String) gameDropdown.getSelectedItem();
        log("Selected gameCode(raw)=" + gameCode);
        if (gameCode == null || gameCode.isBlank()) gameCode = "FRLG";
        log("Using gameCode=" + gameCode);

        int pokedexNumber = parsePokedexOrWarn();
        log("Parsed pokedexNumber=" + pokedexNumber);
        if (pokedexNumber < 1) return;

        KeySender activeKeys;
        try {
            activeKeys = createKeySenderForCurrentPlatform();
            log("Active KeySender created: " + activeKeys.getClass().getName());
        } catch (Throwable t) {
            log("Failed to create active KeySender: " + t.getMessage(), t);
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to initialize input backend:\n" + t.getMessage(),
                    "Backend Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        HuntMethod method;
        try {
            log("Creating method via MethodRegistry.create(gen=" + gen + ", type=" + type
                    + ", state@" + System.identityHashCode(state)
                    + ", pokedex=" + pokedexNumber + ", gameCode=" + gameCode + ")");
            method = MethodRegistry.create(gen, type, activeKeys, state, pokedexNumber, gameCode);
            log("MethodRegistry returned: " + (method == null ? "null" : method.getClass().getName()));
        } catch (Throwable t) {
            log("MethodRegistry.create threw: " + t.getClass().getName() + " -> " + t.getMessage(), t);
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to create hunting method:\n" + t.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        try {
            controller.start(method);
            log("controller.start(method) called. controller.isRunning(after)=" + controller.isRunning()
                    + " activeName=" + controller.activeName());
        } catch (Throwable t) {
            log("controller.start threw: " + t.getClass().getName() + " -> " + t.getMessage(), t);
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to start method:\n" + t.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        refreshUiState();
    }

    private boolean savePlatformSettingsFromUi() {
        try {
            TargetPlatform selected = (TargetPlatform) platformDropdown.getSelectedItem();
            if (selected == null) {
                selected = TargetPlatform.GBA_EMULATOR;
            }
            state.setTargetPlatform(selected);

            if (selected == TargetPlatform.NINTENDO_SWITCH) {
                String host = switchHostField.getText() == null ? "" : switchHostField.getText().trim();
                String portRaw = switchPortField.getText() == null ? "" : switchPortField.getText().trim();

                if (host.isEmpty()) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Enter the Switch bridge host or IP address.",
                            "Switch Host Required",
                            JOptionPane.WARNING_MESSAGE
                    );
                    return false;
                }

                int port;
                try {
                    port = Integer.parseInt(portRaw);
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Switch port must be a valid integer.",
                            "Invalid Switch Port",
                            JOptionPane.ERROR_MESSAGE
                    );
                    return false;
                }

                state.setSwitchHost(host);
                state.setSwitchPort(port);
            }

            return true;
        } catch (Throwable t) {
            log("savePlatformSettingsFromUi failed", t);
            JOptionPane.showMessageDialog(
                    this,
                    "Invalid platform settings:\n" + t.getMessage(),
                    "Settings Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return false;
        }
    }

    private KeySender createKeySenderForCurrentPlatform() {
        TargetPlatform platform = state.getTargetPlatform();
        log("createKeySenderForCurrentPlatform platform=" + platform);

        if (platform == TargetPlatform.NINTENDO_SWITCH) {
            String host = state.getSwitchHost();
            int port = state.getSwitchPort();

            SwitchNxbtKeySender switchKeys = new SwitchNxbtKeySender(host, port);
            log("Connecting to switch bridge at " + host + ":" + port);
            switchKeys.connect();
            return switchKeys;
        }

        return emulatorKeys;
    }

    private int parsePokedexOrWarn() {
        String raw = pokedexField.getText() == null ? "" : pokedexField.getText().trim();
        log("parsePokedexOrWarn raw='" + raw + "'");
        if (raw.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Enter a Pokédex number (e.g., 1).",
                    "Pokédex Number Required",
                    JOptionPane.WARNING_MESSAGE
            );
            return -1;
        }

        try {
            int n = Integer.parseInt(raw);
            if (n < 1) throw new NumberFormatException("must be >= 1");
            return n;
        } catch (NumberFormatException ex) {
            log("parsePokedexOrWarn error: " + ex.getMessage(), ex);
            JOptionPane.showMessageDialog(
                    this,
                    "Invalid Pokédex number: " + raw + "\nUse an integer >= 1.",
                    "Invalid Input",
                    JOptionPane.ERROR_MESSAGE
            );
            return -1;
        }
    }

    private void refreshUiState() {
        boolean running = controller.isRunning();
        log("refreshUiState() running=" + running
                + " activeName=" + controller.activeName()
                + " hasCaptureRect=" + state.hasCaptureRect()
                + " captureRect=" + rectToString(state.getCaptureRect())
                + " platform=" + state.getTargetPlatform());

        statusLabel.setText("Status: " + (running ? "Running" : "Idle"));
        activeLabel.setText("Active: " + controller.activeName());
        stopBtn.setEnabled(running);

        if (state.hasCaptureRect() && state.getCaptureRect() != null) {
            Rectangle r = state.getCaptureRect();
            captureAreaLabel.setText("Capture Area: " + r.width + "x" + r.height + " @ (" + r.x + "," + r.y + ")");
        } else if (!pickingCaptureArea) {
            captureAreaLabel.setText("Capture Area: (not set)");
        }

        platformDropdown.setSelectedItem(state.getTargetPlatform());
        switchHostField.setText(state.getSwitchHost());
        switchPortField.setText(String.valueOf(state.getSwitchPort()));
        refreshPlatformFields();
    }
}