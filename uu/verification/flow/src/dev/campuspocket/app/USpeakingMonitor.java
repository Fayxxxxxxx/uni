package dev.campuspocket.app;
class USpeakingMonitor {
    final CampusService service;
    String status;
    USpeakingMonitor(CampusService service) { this.service = service; }
    CampusService.Capture trainingCapture() { return service.current; }
    boolean trainingCapturePending() { return false; }
    void trainingEvent(String stage, String info) {}
    void trainingProgress(String notice) { status = notice; }
    void trainingFinished(USpeakingTraining run, String message) { status = message; }
}
