/**
 * Copyright (C) 2018 Alauda.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.Secret;
import io.alauda.kubernetes.api.model.SecretList;
import io.alauda.kubernetes.client.Watcher;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.AlaudaUtils.getAuthenticatedAlaudaClient;
import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link Secret} objects in Kubernetes and syncs then to Credentials in
 * Jenkins
 */
public class SecretWatcher extends BaseWatcher {
    private Map<String, String> trackedSecrets;

    private final Logger logger = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public SecretWatcher(String[] namespaces) {
        super(namespaces);
        this.trackedSecrets = new ConcurrentHashMap<String, String>();
    }

    @Override
    public Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    logger.fine("No Alauda Kubernetes Token credential defined.");
                    return;
                }
                for (String namespace : namespaces) {
                    SecretList secrets = null;
                    try {
                        logger.fine("listing Secrets resources");
//                        secrets = getAuthenticatedAlaudaClient().secrets()
//                                .inNamespace(namespace)
//                                .withLabel(Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, Constants.VALUE_SECRET_SYNC).list();
                        secrets = AlaudaUtils.getAuthenticatedAlaudaClient().secrets()
                          .inNamespace(namespace).list();
                        onInitialSecrets(secrets);
                        logger.fine("handled Secrets resources");
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load Secrets: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (secrets == null) {
                            logger.warning("Unable to get secret list; impacts resource version used for watch");
                        } else {
                            resourceVersion = secrets.getMetadata()
                                    .getResourceVersion();
                        }
                        synchronized(SecretWatcher.this) {
                            if (watches.get(namespace) == null) {
                                logger.info("creating Secret watch for namespace "
                                        + namespace + " and resource version"
                                        + resourceVersion);
//                                watches.put(
//                                        namespace,
//                                        getAuthenticatedAlaudaClient()
//                                        .secrets()
//                                        .inNamespace(namespace)
//                                        .withLabel(Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC,
//                                                Constants.VALUE_SECRET_SYNC)
//                                                .withResourceVersion(
//                                                        resourceVersion)
//                                                        .watch(new WatcherCallback<Secret>(SecretWatcher.this,
//                                                                namespace)));
                              watches.put(
                                namespace,
                                AlaudaUtils.getAuthenticatedAlaudaClient()
                                  .secrets()
                                  .inNamespace(namespace)
                                    .withResourceVersion(resourceVersion)
                                    .watch(new WatcherCallback<Secret>(SecretWatcher.this,
                                      namespace)));
                            }
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load Secrets: " + e, e);
                    }
                }

            }
        };
    }

    public synchronized void start() {
        // lets process the initial state
        super.start();
        logger.info("Now handling startup secrets!!");
    }

    private synchronized void onInitialSecrets(SecretList secrets) {
        if (secrets == null)
            return;
        if (trackedSecrets == null)
            trackedSecrets = new ConcurrentHashMap<String, String>();
        List<Secret> items = secrets.getItems();
        if (items != null) {
            for (Secret secret : items) {
                try {
                    if (validSecret(secret) && shouldProcessSecret(secret)) {
                        upsertCredential(secret);
                        trackedSecrets.put(secret.getMetadata().getUid(),
                                secret.getMetadata().getResourceVersion());
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    public synchronized void eventReceived(Watcher.Action action, Secret secret) {
        try {
            switch (action) {
            case ADDED:
                upsertCredential(secret);
                break;
            case DELETED:
                deleteCredential(secret);
                break;
            case MODIFIED:
                modifyCredential(secret);
                break;
            case ERROR:
                logger.warning("watch for secret " + secret.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning("watch for secret " + secret.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Caught: " + e, e);
        }
    }
    @Override
    public <T> void eventReceived(Watcher.Action action, T resource) {
        Secret secret = (Secret)resource;
        eventReceived(action, secret);
    }

    private void upsertCredential(final Secret secret) throws Exception {
        if (validSecret(secret)) {
            CredentialsUtils.upsertCredential(secret);
            trackedSecrets.put(secret.getMetadata().getUid(), secret
                    .getMetadata().getResourceVersion());
        }
    }

    private void modifyCredential(Secret secret) throws Exception {
        if (validSecret(secret) && shouldProcessSecret(secret)) {
            CredentialsUtils.upsertCredential(secret);
            trackedSecrets.put(secret.getMetadata().getUid(), secret
                    .getMetadata().getResourceVersion());
        }
    }

    private boolean validSecret(Secret secret) {
        ObjectMeta metadata = secret.getMetadata();
        if (metadata != null) {
            String name = metadata.getName();
            String namespace = metadata.getNamespace();
            return name != null && !name.isEmpty() && namespace != null
                    && !namespace.isEmpty();
        }
        return false;
    }

    private boolean shouldProcessSecret(Secret secret) {
        String uid = secret.getMetadata().getUid();
        String rv = secret.getMetadata().getResourceVersion();
        String savedRV = trackedSecrets.get(uid);
        if (savedRV == null || !savedRV.equals(rv))
            return true;
        return false;
    }

    private void deleteCredential(final Secret secret) throws Exception {
        trackedSecrets.remove(secret.getMetadata().getUid());
        CredentialsUtils.deleteCredential(secret);
    }

}