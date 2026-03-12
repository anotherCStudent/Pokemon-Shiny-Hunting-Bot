package core;

import methods.HuntMethod;

public class BotController {
    private HuntMethod active;

    public void start(HuntMethod method) {
        stop();
        this.active = method;
        this.active.start();
    }

    public void stop() {
        if (active != null && active.isRunning()) {
            active.stop();
        }
        active = null;
    }

    public boolean isRunning() {
        return active != null && active.isRunning();
    }

    public String activeName() {
        return active == null ? "None" : active.name();
    }
}