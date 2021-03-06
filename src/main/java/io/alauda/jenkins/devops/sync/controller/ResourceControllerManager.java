package io.alauda.jenkins.devops.sync.controller;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_BASEDOMAIN;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_USED_BASEDOMAIN;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Jenkins;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.support.KubernetesCluster;
import io.alauda.jenkins.devops.support.KubernetesClusterConfiguration;
import io.alauda.jenkins.devops.support.KubernetesClusterConfigurationListener;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.monitor.Metrics;
import io.kubernetes.client.extended.controller.ControllerManager;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import jenkins.model.identity.IdentityRootAction;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
@SuppressFBWarnings("IS2_INCONSISTENT_SYNC")
public class ResourceControllerManager implements KubernetesClusterConfigurationListener {

  private static final Logger logger = LoggerFactory.getLogger(ResourceControllerManager.class);

  private ControllerManager controllerManager;
  private ExecutorService controllerManagerThread;
  private String managerStatus;
  private String baseDomain = ALAUDA_DEVOPS_USED_BASEDOMAIN;
  private AtomicBoolean started = new AtomicBoolean(false);

  @Override
  public void onConfigChange(KubernetesCluster cluster, ApiClient client) {
    this.start();
  }

  public synchronized void start() {
    // shutdown the controllerManager started before
    shutdown(null);

    controllerManagerThread = Executors.newSingleThreadExecutor();
    controllerManagerThread.submit(
        () -> {
          waitForJenkinsSetup();

          logger.info("[ResourceControllerManager] Starting initialize controller manager");
          SharedInformerFactory informerFactory = new SharedInformerFactory();

          ExtensionList<ResourceController> resourceControllers = ResourceController.all();
          logger.info(
              "[ResourceControllerManager] Found {} resource controllers",
              resourceControllers.size());

          ControllerManagerBuilder controllerManagerBuilder =
              ControllerBuilder.controllerManagerBuilder(informerFactory);

          resourceControllers.forEach(
              resourceSyncController -> {
                resourceSyncController.add(controllerManagerBuilder, informerFactory);
              });

          synchronized (ResourceControllerManager.this) {
            try {
              if (controllerManager != null) {
                logger.info(
                    "[ResourceControllerManager] The previous ControllerManager doesn't shutdown, try to stop it now..");
                controllerManager.shutdown();
              }
            } catch (Throwable e) {
              logger.info(
                  "[ResourceControllerManager] Failed to stop the previous ControllerManager, there might are potential problem");
            }
            controllerManager = controllerManagerBuilder.build();
          }

          logger.info(
              "[ResourceControllerManager] ControllerManager initialized, waiting for informers sync");
          informerFactory.startAllRegisteredInformers();

          if (!waitForInformersSync()) {
            logger.warn(
                "[ResourceControllerManager] Timeout to wait for informers sync, will restart controllerManager");
            this.restart();
            return;
          }

          managerStatus = "";
          started.set(true);
          Metrics.syncManagerUpGauge.set(1);

          logger.info("[ResourceControllerManager] Start controllerManager");
          controllerManager.run();
        });
  }

  private void waitForJenkinsSetup() {
    pollWithNoInitialDelay(
        Duration.ofMinutes(1),
        // we cannot set a infinite duration here, so we set it to one year, this should be long
        // enough
        Duration.ofDays(365),
        () -> {
          boolean isEnabled = AlaudaSyncGlobalConfiguration.get().isEnabled();
          if (!isEnabled) {
            managerStatus =
                "Alauda DevOps Sync plugin has been disabled, will not start to sync with devops-apiserver";
            logger.warn(
                "[ResourceControllerManager] Alauda DevOps Sync plugin has been disabled, will not start to sync with devops-apiserver");
            return false;
          }

          return checkAndSetupJenkins();
        });
  }

  private boolean waitForInformersSync() {
    return pollWithNoInitialDelay(
        Duration.ofSeconds(5),
        // if informers didn't sync after 30 minutes, we should stop to recheck it as there must
        // some network or configuration problems.
        Duration.ofMinutes(30),
        Clients::allRegisteredResourcesSynced);
  }

  private boolean checkAndSetupJenkins() {
    String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();

    if (StringUtils.isEmpty(jenkinsService)) {
      managerStatus =
          "[ResourceControllerManager] Plugin cannot get mapped Jenkins resource, jenkins service name in configuration is empty";
      return false;
    }

    V1alpha1Jenkins jenkinsResource;
    try {
      jenkinsResource = getJenkins(jenkinsService);
    } catch (ApiException e) {
      managerStatus =
          String.format(
              "[ResourceControllerManager] Plugin cannot get mapped Jenkins resource by name %s in devops-apiserver, reason %s, body %s",
              jenkinsService, e.getMessage(), e.getResponseBody());
      return false;
    }

    final String currentFingerprint = new IdentityRootAction().getFingerprint();
    Map<String, String> annotations = jenkinsResource.getMetadata().getAnnotations();

    if (annotations == null) {
      annotations = new HashMap<>();
      jenkinsResource.getMetadata().setAnnotations(annotations);
    }

    baseDomain = annotations.get(ALAUDA_DEVOPS_ANNOTATIONS_BASEDOMAIN);
    if (StringUtils.isEmpty(baseDomain)) {
      baseDomain = ALAUDA_DEVOPS_USED_BASEDOMAIN;
    }

    String fingerprintInJenkinsService =
        annotations.get(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY.get());
    // if Jenkins resource already has fingerprint, check if its fingerprint match the current
    // Jenkins
    if (!StringUtils.isEmpty(fingerprintInJenkinsService)
        && !StringUtils.equals(currentFingerprint, fingerprintInJenkinsService)) {
      managerStatus =
          String.format(
              "[ResourceControllerManager] Fingerprint from target Jenkins resource %s does not match with current Jenkins %s.",
              fingerprintInJenkinsService, currentFingerprint);
      return false;
    }

    if (StringUtils.isEmpty(fingerprintInJenkinsService)) {
      V1alpha1Jenkins newJenkins = DeepCopyUtils.deepCopy(jenkinsResource);
      newJenkins
          .getMetadata()
          .getAnnotations()
          .put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY.get(), currentFingerprint);
      if (!JenkinsClient.getInstance().updateJenkins(jenkinsResource, newJenkins)) {
        managerStatus =
            String.format(
                "[ResourceControllerManager] Unable to generate patch for Jenkins '%s', reason: %s",
                jenkinsService, "Patch failed");
        return false;
      }
    }

    return true;
  }

  @Override
  public void onConfigError(KubernetesCluster cluster, Throwable reason) {
    shutdown(reason);
  }

  public synchronized void shutdown(Throwable reason) {
    started.set(false);
    Metrics.syncManagerUpGauge.set(0);

    if (controllerManager != null) {
      controllerManager.shutdown();
      controllerManager = null;
    }

    if (controllerManagerThread != null && !controllerManagerThread.isShutdown()) {
      controllerManagerThread.shutdown();
    }

    if (reason == null) {
      logger.warn(
          "[ResourceControllerManager] ResourceControllerManager is stopped, reason is null, seems stopped by user");
    } else {
      logger.warn(
          "[ResourceControllerManager] ResourceControllerManager is stopped, reason: {}",
          reason.getMessage());
    }
  }

  private V1alpha1Jenkins getJenkins(String name) throws ApiException {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    return api.readJenkins(name, null, null, null);
  }

  public String getManagerStatus() {
    return managerStatus;
  }

  // TODO: should throw Exception or something can warn us when baseDomain is wrong
  public Supplier<String> getFormattedAnnotation(String annotation) {
    return new Supplier<String>() {
      @Override
      public String get() {
        return String.format("%s/%s", baseDomain, annotation);
      }
    };
  }

  public synchronized boolean isStarted() {
    return started.get()
        && controllerManagerThread != null
        && !controllerManagerThread.isShutdown();
  }

  public synchronized void restart() {
    KubernetesCluster cluster = KubernetesClusterConfiguration.get().getCluster();
    this.onConfigChange(cluster, Configuration.getDefaultApiClient());
  }

  public static ResourceControllerManager getControllerManager() {
    return ExtensionList.lookup(ResourceControllerManager.class).get(0);
  }

  /**
   * Will check condition immediately, if failed then start polling in interval
   *
   * @param interval the interval period
   * @param timeout the timeout period
   * @param condition condition func which polling will check
   * @return
   */
  private boolean pollWithNoInitialDelay(
      Duration interval, Duration timeout, Supplier<Boolean> condition) {
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    AtomicBoolean result = new AtomicBoolean(false);
    long dueDate = System.currentTimeMillis() + timeout.toMillis();
    ScheduledFuture<?> future =
        executorService.scheduleAtFixedRate(
            () -> {
              try {
                result.set(condition.get());
              } catch (Exception e) {
                result.set(false);
              }
            },
            Duration.ZERO.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      while (System.currentTimeMillis() < dueDate) {
        if (result.get()) {
          future.cancel(true);
          return true;
        }
      }
    } catch (Exception e) {
      return result.get();
    }
    future.cancel(true);
    return result.get();
  }
}
