package io.alauda.jenkins.devops.sync.controller;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.extend.controller.Controller;
import io.alauda.devops.java.client.extend.controller.builder.ControllerBuilder;
import io.alauda.devops.java.client.extend.controller.builder.ControllerManangerBuilder;
import io.alauda.devops.java.client.extend.controller.reconciler.Request;
import io.alauda.devops.java.client.extend.controller.reconciler.Result;
import io.alauda.devops.java.client.extend.workqueue.DefaultRateLimitingQueue;
import io.alauda.devops.java.client.extend.workqueue.RateLimitingQueue;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBindingList;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsBindingClient;
import io.alauda.jenkins.devops.sync.controller.util.InformerUtils;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Extension
public class JenkinsBindingController implements ResourceSyncController {
    @Override
    public void add(ControllerManangerBuilder managerBuilder, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        
        SharedIndexInformer<V1alpha1JenkinsBinding> informer = InformerUtils.getExistingSharedIndexInformer(factory, V1alpha1JenkinsBinding.class);
        if (informer == null) {
            informer = factory.sharedIndexInformerFor(
                    callGeneratorParams -> {
                        try {
                            return api.listJenkinsBindingForAllNamespacesCall(
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    callGeneratorParams.resourceVersion,
                                    callGeneratorParams.timeoutSeconds,
                                    callGeneratorParams.watch,
                                    null,
                                    null
                            );
                        } catch (ApiException e) {
                            throw new RuntimeException(e);
                        }
                    }, V1alpha1JenkinsBinding.class, V1alpha1JenkinsBindingList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
        }


        JenkinsBindingClient client = new JenkinsBindingClient(informer);
        Clients.register(V1alpha1JenkinsBinding.class, client);

        RateLimitingQueue<Request> rateLimitingQueue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadScheduledExecutor());

        Controller controller =
                ControllerBuilder.defaultBuilder(factory).watch(
                        ControllerBuilder.controllerWatchBuilder(V1alpha1JenkinsBinding.class)
                                .withWorkQueueKeyFunc(jenkinsBinding ->
                                        new Request(jenkinsBinding.getMetadata().getName(), jenkinsBinding.getMetadata().getNamespace()))
                                .withWorkQueue(rateLimitingQueue)
                                .build())
                        .withReconciler(request -> new Result(false))
                        .withName("JenkinsBindingController")
                        .withReadyFunc(informer::hasSynced)
                        .withWorkQueue(rateLimitingQueue)
                        .withWorkerCount(1)
                        .build();

        managerBuilder.addController(controller);
    }

}
