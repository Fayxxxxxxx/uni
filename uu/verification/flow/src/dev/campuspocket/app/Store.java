package dev.campuspocket.app;
class Store {
    static final class Config { boolean ready() { return true; } }
    static final class Voice {}
    Config config() { return new Config(); }
    Voice voice() { return new Voice(); }
    float trainingSpeed() { return 1; }
    int trainingMinimum() { return 90; }
}
