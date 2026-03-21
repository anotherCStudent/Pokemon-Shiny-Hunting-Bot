package vision.capture;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * RectanglePicker
 *
 * UX:
 *  - Click once to set anchor
 *  - Move mouse to size
 *  - Click again to lock
 *  - ENTER confirms (returns rectangle in ABSOLUTE screen coords)
 *  - ESC cancels (throws RuntimeException)
 *
 * Debugging:
 *  - Very verbose logs to track EDT usage, focus, window lifecycle, selection state,
 *    and whether result is being propagated back to caller.
 */
public final class RectanglePicker {

    // Toggle this to silence logs without deleting them.
    private static final boolean DEBUG = true;

    private static final long WAIT_TIMEOUT_MS = 0;

    // Wait loop tick
    private static final int WAIT_SLEEP_MS = 20;

    private static void log(String msg) {
        if (!DEBUG) return;
        System.out.println("[RectanglePicker] " + msg);
    }

    private static void log(String msg, Throwable t) {
        if (!DEBUG) return;
        System.out.println("[RectanglePicker] " + msg);
        if (t != null) t.printStackTrace(System.out);
    }

    private static String safeStr(String s) {
        return s == null ? "(null)" : s;
    }

    private static String rectToString(Rectangle r) {
        if (r == null) return "(null)";
        return "Rect{x=" + r.x + ", y=" + r.y + ", w=" + r.width + ", h=" + r.height + "}";
    }

    private static String pointToString(Point p) {
        if (p == null) return "(null)";
        return "Point{x=" + p.x + ", y=" + p.y + "}";
    }

    /**
     * Blocks until user confirms a rectangle with ENTER.
     * ESC cancels.
     *
     * Returns absolute screen coordinates.
     *
     * IMPORTANT:
     *  - This method is intended to be called OFF the Swing EDT, because it blocks.
     *  - If called ON the EDT, it will still work better than before (no instant-cancel),
     *    but blocking the EDT is still not recommended.
     */
    public static Rectangle pickRectangle() {
        boolean onEdt = SwingUtilities.isEventDispatchThread();
        log("pickRectangle() called. On EDT? " + onEdt);

        final AtomicReference<Rectangle> result = new AtomicReference<>();
        final Rectangle screenBounds = getAllScreensBounds();
        log("Computed all-screens bounds = " + rectToString(screenBounds));

        final JFrame window = new JFrame();
        window.setUndecorated(true);
        window.setAlwaysOnTop(true);
        window.setFocusable(true);
        window.setFocusableWindowState(true);
        window.setBackground(new Color(0, 0, 0, 90));
        window.setBounds(screenBounds);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        final AtomicBoolean overlayShown = new AtomicBoolean(false);
        final AtomicBoolean overlayDisposed = new AtomicBoolean(false);

        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                log("windowOpened()");
            }

            @Override
            public void windowClosing(WindowEvent e) {
                log("windowClosing()");
            }

            @Override
            public void windowClosed(WindowEvent e) {
                overlayDisposed.set(true);
                log("windowClosed()");
            }

            @Override
            public void windowActivated(WindowEvent e) {
                log("windowActivated()");
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                log("windowDeactivated()");
            }
        });

        window.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                log("Window focusGained()");
            }

            @Override
            public void focusLost(FocusEvent e) {
                log("Window focusLost()");
            }
        });

        final SelectionPane pane = new SelectionPane(screenBounds, result, window);
        pane.setOpaque(false);
        pane.setFocusable(true);
        pane.setRequestFocusEnabled(true);

        window.setContentPane(pane);

        // Optional: a global key dispatcher while the overlay is up.
        // This is a fallback for weird focus issues. It only acts while window is visible.
        // It does NOT replace key bindings; it just helps if focus is lost.
        final KeyEventDispatcher dispatcher = new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                // Only care while overlay is visible, and only on KEY_PRESSED.
                if (!window.isVisible()) return false;
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;

                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ESCAPE) {
                    log("KeyEventDispatcher: ESC detected (fallback).");
                    pane.cancelFromDispatcher();
                    return true;
                }
                if (code == KeyEvent.VK_ENTER) {
                    log("KeyEventDispatcher: ENTER detected (fallback).");
                    pane.confirmFromDispatcher();
                    return true;
                }
                return false;
            }
        };

        // Show overlay synchronously when off-EDT to avoid the “not displayable” race.
        // If on-EDT, we show immediately (still not recommended to block EDT afterwards).
        showOverlay(window, pane, overlayShown, dispatcher);

        // Poll loop - logs periodically so you can see it stuck or progressing.
        AtomicLong loops = new AtomicLong(0);
        long lastLog = System.currentTimeMillis();
        long startWait = System.currentTimeMillis();

        while (result.get() == null) {
            long c = loops.incrementAndGet();

            // Timeout 
            if (WAIT_TIMEOUT_MS > 0) {
                long elapsed = System.currentTimeMillis() - startWait;
                if (elapsed >= WAIT_TIMEOUT_MS) {
                    log("WAIT LOOP: timeout hit (" + WAIT_TIMEOUT_MS + "ms). Cancelling.");
                    result.set(SelectionPane.CANCEL_SENTINEL);
                    safeClose(window, "timeout");
                    break;
                }
            }

            // Do NOT treat "not displayable" as cancel until overlay has actually been shown.
            if (overlayShown.get()) {
                if (!window.isDisplayable() || overlayDisposed.get()) {
                    log("WAIT LOOP: window no longer displayable AFTER show. Treating as cancel. loops=" + c
                            + " displayable=" + window.isDisplayable()
                            + " disposedFlag=" + overlayDisposed.get());
                    throw new RuntimeException("Rectangle selection cancelled.");
                }
            } else {
                // Before shown, it's normal for displayable to be false.
                // Log once in a while, but do not cancel.
                if (c == 1) {
                    log("WAIT LOOP: overlayShown=false; not checking displayable yet.");
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastLog >= 500) {
                lastLog = now;
                log("WAIT LOOP: still waiting... loops=" + c
                        + " overlayShown=" + overlayShown.get()
                        + " displayable=" + window.isDisplayable()
                        + " visible=" + window.isVisible()
                        + " focused=" + window.isFocused()
                        + " active=" + window.isActive()
                        + " resultNull=" + (result.get() == null));
            }

            try {
                Thread.sleep(WAIT_SLEEP_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                log("WAIT LOOP: interrupted while sleeping", ignored);
                throw new RuntimeException("Rectangle selection interrupted.");
            }
        }

        Rectangle r = result.get();
        log("WAIT LOOP: result became non-null -> " + rectToString(r));

        // Remove dispatcher (best effort).
        tryRemoveDispatcher(dispatcher);

        if (r == SelectionPane.CANCEL_SENTINEL) {
            log("Result is CANCEL_SENTINEL. Throwing cancel RuntimeException.");
            throw new RuntimeException("Rectangle selection cancelled.");
        }

        log("pickRectangle() returning absolute rect: " + rectToString(r));
        return r;
    }

    /**
     * Show overlay. If not on EDT, uses invokeAndWait so setVisible completes before waiting.
     */
    private static void showOverlay(JFrame window,
                                    SelectionPane pane,
                                    AtomicBoolean overlayShown,
                                    KeyEventDispatcher dispatcher) {

        Runnable show = () -> {
            log("showOverlay(): running on EDT? " + SwingUtilities.isEventDispatchThread());

            // Install dispatcher fallback.
            try {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
                log("showOverlay(): KeyEventDispatcher installed.");
            } catch (Throwable t) {
                log("showOverlay(): failed to install KeyEventDispatcher (non-fatal).", t);
            }

            log("showOverlay(): showing overlay window now...");
            window.setVisible(true);

            overlayShown.set(true);

            log("showOverlay(): window.setVisible(true) complete. displayable=" + window.isDisplayable()
                    + " visible=" + window.isVisible()
                    + " focusable=" + window.isFocusableWindow());

            window.toFront();
            log("showOverlay(): window.toFront()");

            window.requestFocus();
            log("showOverlay(): window.requestFocus()");

            boolean focused = pane.requestFocusInWindow();
            log("showOverlay(): pane.requestFocusInWindow() -> " + focused);
        };

        if (SwingUtilities.isEventDispatchThread()) {
            // We are already on EDT: run directly.
            log("showOverlay(): already on EDT, showing directly (NOTE: blocking EDT later is still not recommended).");
            show.run();
        } else {
            // Not on EDT: show synchronously so that window is displayable before the wait loop.
            try {
                log("showOverlay(): invokeAndWait begin...");
                SwingUtilities.invokeAndWait(show);
                log("showOverlay(): invokeAndWait complete.");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log("showOverlay(): invokeAndWait interrupted.", ie);
                throw new RuntimeException("Rectangle selection interrupted while showing overlay.");
            } catch (InvocationTargetException ite) {
                log("showOverlay(): invokeAndWait threw InvocationTargetException.", ite);
                throw new RuntimeException("Failed to show overlay: " + ite.getCause());
            }
        }
    }

    private static void safeClose(Window window, String reason) {
        log("safeClose(" + reason + "): window.displayable=" + window.isDisplayable()
                + " visible=" + window.isVisible());
        try {
            window.setVisible(false);
        } catch (Throwable t) {
            log("safeClose(" + reason + "): exception while hiding window", t);
        }
        try {
            window.dispose();
        } catch (Throwable t) {
            log("safeClose(" + reason + "): exception while disposing window", t);
        }
    }

    private static void tryRemoveDispatcher(KeyEventDispatcher dispatcher) {
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
            log("KeyEventDispatcher removed.");
        } catch (Throwable t) {
            log("Failed to remove KeyEventDispatcher (non-fatal).", t);
        }
    }

    private static Rectangle getAllScreensBounds() {
        Rectangle all = new Rectangle();
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = ge.getScreenDevices();
        log("Detected " + devices.length + " screen device(s).");
        for (int i = 0; i < devices.length; i++) {
            GraphicsDevice gd = devices[i];
            Rectangle b = gd.getDefaultConfiguration().getBounds();
            log("Screen[" + i + "] bounds = " + rectToString(b) + " id=" + safeStr(gd.getIDstring()));
            all = all.union(b);
        }
        return all;
    }

    private static final class SelectionPane extends JComponent {
        static final Rectangle CANCEL_SENTINEL = new Rectangle(-1, -1, 0, 0);
        private static final int MIN_SIZE = 10;

        private final Rectangle screenBounds;
        private final AtomicReference<Rectangle> result;
        private final Window window;

        private Point anchor;
        private Point current;
        private Rectangle finalRect;

        SelectionPane(Rectangle screenBounds, AtomicReference<Rectangle> result, Window window) {
            this.screenBounds = screenBounds;
            this.result = result;
            this.window = window;

            log("SelectionPane created. screenBounds=" + rectToString(screenBounds)
                    + " windowClass=" + (window == null ? "(null)" : window.getClass().getName()));

            MouseAdapter adapter = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    log("mouseClicked: button=" + e.getButton()
                            + " clicks=" + e.getClickCount()
                            + " point=" + pointToString(e.getPoint())
                            + " anchor=" + pointToString(anchor)
                            + " finalRect=" + rectToString(finalRect));

                    if (anchor == null) {
                        anchor = e.getPoint();
                        current = anchor;
                        finalRect = null;
                        log("First click -> anchor set to " + pointToString(anchor));
                        repaint();
                        return;
                    }

                    if (finalRect == null) {
                        current = e.getPoint();
                        Rectangle r = toRect(anchor, current);
                        log("Second click -> computed rect (component coords): " + rectToString(r));

                        if (r.width < MIN_SIZE || r.height < MIN_SIZE) {
                            log("Rect too small (<" + MIN_SIZE + "). Resetting selection.");
                            anchor = null;
                            current = null;
                            finalRect = null;
                            repaint();
                            return;
                        }

                        finalRect = r;
                        log("Locked finalRect = " + rectToString(finalRect));
                        repaint();
                    } else {
                        log("mouseClicked ignored because finalRect already locked. (Press Enter or Esc)");
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    if (anchor != null && finalRect == null) {
                        current = e.getPoint();
                        repaint();
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (anchor != null && finalRect == null) {
                        current = e.getPoint();
                        repaint();
                    }
                }
            };

            addMouseListener(adapter);
            addMouseMotionListener(adapter);

            // Key bindings (primary)
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ENTER"), "confirm");
            getActionMap().put("confirm", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    log("ENTER pressed (key binding). finalRect=" + rectToString(finalRect)
                            + " anchor=" + pointToString(anchor)
                            + " current=" + pointToString(current)
                            + " window.displayable=" + window.isDisplayable()
                            + " window.visible=" + window.isVisible()
                            + " window.focused=" + (window instanceof JFrame jf ? jf.isFocused() : "(n/a)"));
                    confirmInternal("key-binding");
                }
            });

            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
            getActionMap().put("cancel", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    log("ESC pressed (key binding). anchor=" + pointToString(anchor)
                            + " current=" + pointToString(current)
                            + " finalRect=" + rectToString(finalRect));
                    cancelInternal("key-binding");
                }
            });
        }

        // Called by KeyEventDispatcher fallback
        void confirmFromDispatcher() {
            confirmInternal("dispatcher");
        }

        // Called by KeyEventDispatcher fallback
        void cancelFromDispatcher() {
            cancelInternal("dispatcher");
        }

        private void confirmInternal(String source) {
            log("confirmInternal(" + source + "): finalRect=" + rectToString(finalRect));

            if (finalRect == null) {
                log("confirmInternal(" + source + "): ignored; finalRect is null (user hasn't clicked twice yet).");
                return;
            }

            Rectangle absolute = new Rectangle(
                    finalRect.x + screenBounds.x,
                    finalRect.y + screenBounds.y,
                    finalRect.width,
                    finalRect.height
            );

            log("confirmInternal(" + source + "): setting result absolute rect = " + rectToString(absolute)
                    + " (finalRect=" + rectToString(finalRect)
                    + " screenBounds=" + rectToString(screenBounds) + ")");

            result.set(absolute);
            log("confirmInternal(" + source + "): result set. Now closing window.");
            closeWindow();
        }

        private void cancelInternal(String source) {
            log("cancelInternal(" + source + "): setting CANCEL_SENTINEL and closing. "
                    + "anchor=" + pointToString(anchor)
                    + " current=" + pointToString(current)
                    + " finalRect=" + rectToString(finalRect));

            result.set(CANCEL_SENTINEL);
            closeWindow();
        }

        private void closeWindow() {
            log("closeWindow(): called. window.displayable=" + window.isDisplayable()
                    + " visible=" + window.isVisible());
            try {
                window.setVisible(false);
                log("closeWindow(): window.setVisible(false) done.");
            } catch (Throwable t) {
                log("closeWindow(): exception while hiding window", t);
            } finally {
                try {
                    window.dispose();
                    log("closeWindow(): window.dispose() done. displayable=" + window.isDisplayable());
                } catch (Throwable t) {
                    log("closeWindow(): exception while disposing window", t);
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Rectangle r = getDisplayRectangle();
            if (r != null) {
                // Fill selected area
                g2.setColor(new Color(0, 170, 255, 60));
                g2.fillRect(r.x, r.y, r.width, r.height);

                // Border
                g2.setColor(new Color(0, 170, 255, 230));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRect(r.x, r.y, r.width, r.height);

                // Corner handles (visual only)
                drawHandle(g2, r.x, r.y);
                drawHandle(g2, r.x + r.width, r.y);
                drawHandle(g2, r.x, r.y + r.height);
                drawHandle(g2, r.x + r.width, r.y + r.height);

                // Size label
                String sizeText = r.width + " x " + r.height;
                drawLabel(g2, sizeText, r.x, r.y - 8);
            }

            // Instruction label
            String instructions;
            if (anchor == null) {
                instructions = "Click once to start drawing a capture area. ESC = cancel";
            } else if (finalRect == null) {
                instructions = "Move mouse to size the rectangle, then click again to lock it.";
            } else {
                instructions = "Press ENTER to confirm capture area, or ESC to cancel.";
            }

            drawLabel(g2, instructions, 20, 30);

            g2.dispose();
        }

        private Rectangle getDisplayRectangle() {
            if (finalRect != null) {
                return finalRect;
            }
            if (anchor != null && current != null) {
                return toRect(anchor, current);
            }
            return null;
        }

        private void drawHandle(Graphics2D g2, int centerX, int centerY) {
            int size = 8;
            int x = centerX - size / 2;
            int y = centerY - size / 2;

            g2.setColor(Color.WHITE);
            g2.fillRect(x, y, size, size);
            g2.setColor(new Color(0, 120, 215));
            g2.drawRect(x, y, size, size);
        }

        private void drawLabel(Graphics2D g2, String text, int x, int y) {
            FontMetrics fm = g2.getFontMetrics();
            int pad = 6;
            int w = fm.stringWidth(text) + pad * 2;
            int h = fm.getHeight() + pad;

            int drawX = Math.max(8, x);
            int drawY = Math.max(h, y);

            g2.setColor(new Color(0, 0, 0, 170));
            g2.fillRoundRect(drawX - pad, drawY - fm.getAscent(), w, h, 10, 10);

            g2.setColor(Color.WHITE);
            g2.drawString(text, drawX, drawY);
        }

        private static Rectangle toRect(Point a, Point b) {
            int x = Math.min(a.x, b.x);
            int y = Math.min(a.y, b.y);
            int w = Math.abs(a.x - b.x);
            int h = Math.abs(a.y - b.y);
            return new Rectangle(x, y, w, h);
        }
    }

    private RectanglePicker() {}
}
