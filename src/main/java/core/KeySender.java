package core;

public interface KeySender {
    void pressA();
    void pressB();
    void pressStart();
    void pressSelect();
    void up();
    void down();
    void left();
    void right();
    void softReset();

    void sleepMs(long ms);
    void holdDown(long ms);
    void holdLeft(long ms);
    void holdRight(long ms);
    void holdUp(long ms);
}