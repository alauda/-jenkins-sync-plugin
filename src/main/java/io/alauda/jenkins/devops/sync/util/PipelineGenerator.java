package io.alauda.jenkins.devops.sync.util;

import hudson.model.*;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.alauda.devops.java.client.models.*;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ObjectMetaBuilder;
import jenkins.branch.Branch;
import jenkins.scm.api.SCMHead;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

public abstract class PipelineGenerator {

    private static final Logger LOGGER = Logger.getLogger(PipelineGenerator.class.getName());
    private static String TRIGGER_BY = "Triggered by Jenkins job at ";

    public static V1alpha1Pipeline buildPipeline(V1alpha1PipelineConfig config, List<Action> actions) throws ApiException {
        return buildPipeline(config, null, actions);
    }

    public static V1alpha1Pipeline buildPipeline(V1alpha1PipelineConfig config, @NotNull WorkflowJob job,
                                                 String triggerURL, List<Action> actions) throws ApiException {
        ItemGroup parent = job.getParent();
        Map<String, String> annotations = new HashMap<>();
        if (parent instanceof WorkflowMultiBranchProject) {
            BranchJobProperty property = job.getProperty(BranchJobProperty.class);
            if (property != null) {
                Branch branch = property.getBranch();
                annotations.put(Annotations.MULTI_BRANCH_NAME, branch.getName());

                // TODO need to consider multi-tag like GitTagSCMHead
                if (isPR(job)) {
                    annotations.put(Annotations.MULTI_BRANCH_CATEGORY, "pr");
                } else {
                    annotations.put(Annotations.MULTI_BRANCH_CATEGORY, "branch");
                }
            }
        }

        return buildPipeline(config, annotations, triggerURL, actions);
    }

    public static boolean isPR(Item item) {
        SCMHead head = SCMHead.HeadByItem.findHead(item);
        if (head == null) {
            return false;
        }

        String headClsName = head.getClass().getName();
        return "org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead".equals(headClsName)
                || "com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMHead".equals(headClsName);
    }


    @Deprecated
    public static V1alpha1Pipeline buildPipeline(V1alpha1PipelineConfig config, String triggerURL, List<Action> actions) throws ApiException {
        return buildPipeline(config, new HashMap<>(), triggerURL, actions);
    }

    public static V1alpha1Pipeline buildPipeline(V1alpha1PipelineConfig config, Map<String, String> annotations, String triggerURL, List<Action> actions) throws ApiException {
        V1alpha1PipelineSpec pipelineSpec = buildPipelineSpec(config, triggerURL);

        // TODO here should be multi-cause, fix later
        String cause = null;
        for (Action action : actions) {
            if (!(action instanceof CauseAction)) {
                continue;
            }

            // TODO just get first causeAction for now
            CauseAction causeAction = (CauseAction) action;
            if (cause == null) {
                if (causeAction.findCause(SCMTrigger.SCMTriggerCause.class) != null) {
                    cause = PIPELINE_TRIGGER_TYPE_CODE_CHANGE;
                } else if (causeAction.findCause(TimerTrigger.TimerTriggerCause.class) != null) {
                    cause = PIPELINE_TRIGGER_TYPE_CRON;
                }
            } else {
                LOGGER.fine("CauseAction is : " + causeAction.getDisplayName());
            }
        }

        // we think of the default cause is manual
        if (cause == null) {
            cause = PIPELINE_TRIGGER_TYPE_MANUAL;
        }

        V1alpha1PipelineCause pipelineCause = new V1alpha1PipelineCause().type(cause).message(TRIGGER_BY + triggerURL);
        pipelineSpec.setCause(pipelineCause);

        // add parameters
        for (Action action : actions) {
            if (!(action instanceof ParametersAction)) {
                continue;
            }

            ParametersAction paramAction = (ParametersAction) action;
            if (paramAction.getParameters() == null) {
                continue;
            }

            List<V1alpha1PipelineParameter> parameters = new ArrayList<>();

            for (ParameterValue param : paramAction.getParameters()) {
                V1alpha1PipelineParameter pipeParam = ParameterUtils.to(param);
                if (pipeParam != null) {
                    parameters.add(pipeParam);
                }
            }

            pipelineSpec.setParameters(parameters);
        }

        // mark this pipeline created by Jenkins
        Map<String, String> labels = new HashMap<>();
        labels.put(Constants.PIPELINE_CREATED_BY, Constants.ALAUDA_SYNC_PLUGIN);

        String namespace = config.getMetadata().getNamespace();

        // update pipeline to k8s


        V1alpha1Pipeline pipe = new V1alpha1PipelineBuilder().withMetadata(
                new V1ObjectMetaBuilder()
                        .withName(config.getMetadata().getName())
                        .withNamespace(namespace)
                        .addToAnnotations(annotations)
                        .addToLabels(labels).build()).withSpec(pipelineSpec).build();


        return Clients.get(V1alpha1Pipeline.class).create(pipe);

    }

    public static V1alpha1PipelineSpec buildPipelineSpec(V1alpha1PipelineConfig config) {
        return buildPipelineSpec(config, null);
    }

    private static V1alpha1PipelineSpec buildPipelineSpec(V1alpha1PipelineConfig config, String triggerURL) {
        V1alpha1PipelineSpec pipeSpec = new V1alpha1PipelineSpec();
        V1alpha1PipelineConfigSpec spec = config.getSpec();
        pipeSpec.setPipelineConfig(new V1alpha1LocalObjectReference().name(config.getMetadata().getName()));
        pipeSpec.setJenkinsBinding(spec.getJenkinsBinding());
        pipeSpec.setRunPolicy(spec.getRunPolicy());
        pipeSpec.setTriggers(spec.getTriggers());
        pipeSpec.setStrategy(spec.getStrategy());
        pipeSpec.setHooks(spec.getHooks());
        pipeSpec.setSource(spec.getSource());
        return pipeSpec;
    }
}
