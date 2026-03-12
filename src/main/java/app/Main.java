package app;

import app.AppState;
import core.BotController;
import core.KeySender;
import core.mgba.MgbaKeySender;
import ui.MainWindow;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            BotController controller = new BotController();
            KeySender keys = new MgbaKeySender();

            AppState state = new AppState();

            MainWindow window = new MainWindow(controller, keys, state);
            window.setVisible(true);
        });
    }
}