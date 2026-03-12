package methods;

public interface HuntMethod {
    String name();
    void start();
    void stop();
    boolean isRunning();
}