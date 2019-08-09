/*
 * Copyright (C) 2018 Alauda.io
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.*;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.action.AlaudaQueueAction;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.listener.PipelineSyncRunListener;
import io.alauda.jenkins.devops.sync.util.PipelineGenerator;
import io.alauda.jenkins.devops.sync.util.PipelineToActionMapper;
import io.kubernetes.client.ApiException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In this handler, we just handle the case of triggered by user. We will pass of other cases.
 *
 * @author suren
 */
@Extension
public class PipelineDecisionHandler extends Queue.QueueDecisionHandler {

    private static final Logger LOGGER = Logger.getLogger(PipelineDecisionHandler.class.getName());

    @Override
    public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
        if (!(p instanceof WorkflowJob)) {
            return true;
        }

        if (triggerFromJenkins(actions)) {
            // in case of triggered by users or triggers
            WorkflowJob workflowJob = (WorkflowJob) p;
            String taskName = p.getName();

            AlaudaJobProperty alaudaJobProperty;
            if (isMultiBranch(workflowJob)) {
                alaudaJobProperty = ((WorkflowMultiBranchProject) workflowJob.getParent())
                        .getProperties().get(MultiBranchProperty.class);
            } else {
                alaudaJobProperty = workflowJob.getProperty(WorkflowJobProperty.class);
            }

            if (!isValidProperty(alaudaJobProperty)) {
                return true;
            }

            final String namespace = alaudaJobProperty.getNamespace();
            final String name = alaudaJobProperty.getName();
            final String jobURL = getJobUrl(workflowJob, namespace);

            LOGGER.info(() -> "Got this namespace " + namespace + " from this alaudaJobProperty: " + name);
            // TODO: Add trigger API for pipelineconfig (like above)

            V1alpha1PipelineConfig config = null;
            config = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
            if (config == null) {
                return false;
            } else if (config.getMetadata() == null) {
                LOGGER.warning("PipelineConfig metadata is null");
                return false;
            }

            V1alpha1Pipeline pipeline;
            try {
                // create k8s resource(Pipeline)
                pipeline = PipelineGenerator.buildPipeline(config, workflowJob, jobURL, actions);
            } catch (ApiException e) {
                LOGGER.log(Level.WARNING, String.format("Unable to create Pipeline '%s/%s', reason: %s", namespace, config.getMetadata().getName(), e.getMessage()), e);

                return false;
            }

            actions.add(new CauseAction(new JenkinsPipelineCause(pipeline, config.getMetadata().getUid())));
            actions.add(new AlaudaQueueAction());

            ParametersAction params = dumpParams(actions);
            if (params != null) {
                LOGGER.fine(() -> "ParametersAction: " + params.toString());
                PipelineToActionMapper.addParameterAction(pipeline.getMetadata().getName(), params);
            } else {
                LOGGER.log(Level.FINE, "The param is null in task : {0}", taskName);
            }

            CauseAction cause = dumpCause(actions);
            if (cause != null) {
                LOGGER.fine(() -> "get CauseAction: " + cause.getDisplayName());
                for (Cause c : cause.getCauses()) {
                    LOGGER.fine(() -> "Cause: " + c.getShortDescription());
                }

                // TODO consider how to keep other actions which are not just causeAction
                // TODO should we add a extension point here?
                List<Cause> causes = new ArrayList<>(cause.getCauses());
                for (Action action : actions) {
                    if (action instanceof SCMRevisionAction) {
                        causes.add((SCMRevisionAction) action);
                        break;
                    }
                }

                PipelineToActionMapper.addCauseAction(pipeline.getMetadata().getName(), new CauseAction(causes));
            } else {
                LOGGER.fine(() -> "Get null CauseAction in task : " + taskName);
            }
        }

        return true;
    }

    private boolean isMultiBranch(WorkflowJob wfJob) {
        ItemGroup parent = wfJob.getParent();
        return parent instanceof WorkflowMultiBranchProject;
    }

    private String getJobUrl(WorkflowJob workflowJob, String namespace) {
        String jenkinsUrl = "";
        return PipelineSyncRunListener.joinPaths(jenkinsUrl, workflowJob.getUrl());
    }

    private boolean isValidProperty(AlaudaJobProperty property) {
        if (property == null) {
            return false;
        }

        return (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName()));
    }

    private static boolean triggerFromJenkins(@Nonnull List<Action> actions) {
        return !triggerFromPlatform(actions);
    }

    private static boolean triggerFromPlatform(@Nonnull List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof CauseAction) {
                CauseAction causeAction = (CauseAction) action;
                for (Cause cause : causeAction.getCauses()) {
                    if (cause instanceof JenkinsPipelineCause) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Just for find the first CauseAction and print debug info
     *
     * @param actions action list
     * @return causeAction
     */
    private CauseAction dumpCause(List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof CauseAction) {
                CauseAction causeAction = (CauseAction) action;
                if (LOGGER.isLoggable(Level.FINE)) {
                    for (Cause cause : causeAction.getCauses()) {
                        LOGGER.fine(() -> "cause: " + cause.getShortDescription());
                    }
                }

                return causeAction;
            }
        }
        return null;
    }

    private static ParametersAction dumpParams(List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof ParametersAction) {
                ParametersAction paramAction = (ParametersAction) action;
                if (LOGGER.isLoggable(Level.FINE)) {
                    for (ParameterValue param : paramAction.getAllParameters()) {
                        LOGGER.fine(() -> "param name " + param.getName() + " param value " + param.getValue());
                    }
                }
                return paramAction;
            }
        }
        return null;
    }

}
