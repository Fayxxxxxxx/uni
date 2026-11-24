package dev.campuspocket.app;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

class ModelClient {
    static final ManualWorker QUEUE = new ManualWorker();
    static final java.util.concurrent.ExecutorService WORK = QUEUE;
    static String message(Exception error) { return error.getMessage(); }

    static final class ManualWorker extends AbstractExecutorService {
        private final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        public void execute(Runnable command) { command.run(); }
        public void shutdown() {}
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public List<Runnable> shutdownNow() { return new ArrayList<>(jobs); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
        void deliver() { jobs.removeFirst().run(); }
    }
}
