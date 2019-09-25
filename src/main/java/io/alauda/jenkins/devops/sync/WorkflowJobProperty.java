/**
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
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import net.sf.json.JSONObject;



import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores the Alauda DevOps Pipeline Config related project properties.
 *
 * - Namespace - Pipeline Config name - Pipeline Config uid - Pipeline Config resource
 * version - Pipeline Config run policy
 */
public class WorkflowJobProperty extends JobProperty<Job<?, ?>> implements AlaudaJobProperty {

    // The build config uid this job relates to.
    private String uid;
    private String namespace;
    private String name;
    private String resourceVersion;
    private String contextAnnotation;

    @DataBoundConstructor
    public WorkflowJobProperty(String namespace, String name,
                               String uid, String resourceVersion, String contextAnnotation) {
        this.namespace = namespace;
        this.name = name;
        this.uid = uid;
        this.resourceVersion = resourceVersion;
        this.contextAnnotation = contextAnnotation;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getResourceVersion() {
        return resourceVersion;
    }

    public void setResourceVersion(String resourceVersion) {
        this.resourceVersion = resourceVersion;
    }

    @Override
    public String getContextAnnotation() {
        return contextAnnotation;
    }

    @Override
    public void setContextAnnotation(String contextAnnotation) {
        this.contextAnnotation = contextAnnotation;
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        public boolean isApplicable(Class<? extends Job> jobType) {
            return WorkflowJob.class.isAssignableFrom(jobType);
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Alauda Pipeline job";
        }
    }
}
