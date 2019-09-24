package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.alauda.jenkins.devops.sync.controller.ResourceSyncManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Extension
public class ConnectionAliveDetectTask extends AsyncPeriodicWork {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionAliveDetectTask.class);

    private ConcurrentHashMap<HeartbeatResourceDetector, AtomicInteger> heartbeatLostCount = new ConcurrentHashMap<>();

    public ConnectionAliveDetectTask() {
        super("Kubernetes watch connection detect task");
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        if (!ResourceSyncManager.getSyncManager().isStarted()) {
            logger.info("ResourceSyncManager has not started yet, will skip this task");
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        HeartbeatResourceDetector.all()
                .forEach(detector -> {
                    logger.debug("Starting to check if the watch connection of resource {} is alive", detector.resourceName());

                    LocalDateTime lastEventComingTime = detector.lastEventComingTime();
                    // controller might not be initialized, or no resource exist in k8s so that we cannot receive event
                    if (lastEventComingTime == null) {
                        logger.debug("The controller of resource {} seems not start or no resource exists in k8s, will skip check for it", detector.resourceName());
                        return;
                    }

                    Duration elapsed = Duration.between(lastEventComingTime, now);
                    heartbeatLostCount.putIfAbsent(detector, new AtomicInteger(0));

                    // the apiserver will use heartbeat to update resource per 30 seconds, so if we didn't receive an update event in last 1 minute,
                    // the watch connection might broken
                    if (!elapsed.minus(Duration.ofMinutes(1)).isNegative()) {
                        int count = heartbeatLostCount.get(detector).incrementAndGet();
                        logger.warn("The watch connection of resource {} seems broken, " +
                                "last event coming at {}, time since last event coming {}s, retry count {}", detector.resourceName(), lastEventComingTime, elapsed.getSeconds(), count);
                    } else {
                        heartbeatLostCount.get(detector).set(0);
                    }
                });

        boolean needToReconnect = false;
        for (Map.Entry<HeartbeatResourceDetector, AtomicInteger> entry : heartbeatLostCount.entrySet()) {
            AtomicInteger count = entry.getValue();
            // we only retry 3 times
            if (count.get() > 3) {
                logger.warn("The watch connection of resource {} is broken, will try to reestablish connection", entry.getKey().resourceName());
                needToReconnect = true;
                break;
            }
        }

        if (needToReconnect) {
            heartbeatLostCount.clear();
            ResourceSyncManager.getSyncManager().restart();
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    /**
     * Resource that has heartbeat task to update in server-end
     */
    public interface HeartbeatResourceDetector extends ExtensionPoint {
        LocalDateTime lastEventComingTime();

        String resourceName();

        static ExtensionList<HeartbeatResourceDetector> all() {
            return ExtensionList.lookup(HeartbeatResourceDetector.class);
        }
    }

}
