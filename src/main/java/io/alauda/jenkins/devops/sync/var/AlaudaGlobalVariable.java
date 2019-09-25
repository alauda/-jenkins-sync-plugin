package io.alauda.jenkins.devops.sync.var;

import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import net.sf.json.JSONObject;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.Job;
import hudson.Extension;

import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;


import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Allows access to {@link ParametersAction}.
 */
@Extension
public class AlaudaGlobalVariable extends GlobalVariable {

    @Nonnull
    @Override
    public String getName(){
        return "alaudaContext";
    }
    @Nonnull
    @Override
    public Object getValue(@Nonnull CpsScript script) throws Exception{
        Run<?,?> build = script.$build();
        if (build == null) {
            throw new IllegalStateException("cannot find owning build");
        }

        Job<?,?> parent =  build.getParent();
        if(parent instanceof ){
            AlaudaJobProperty property = getAlaudaJobProperty((WorkflowJob) parent);
            if (property==null){
                return new AlaudaContext("","",null,false);
            }

            String namespace = property.getNamespace();
            String name = property.getName();
            String contextannotation = property.getContextAnnotation();
            Map data = null;
            if(contextannotation != null){
                data = JSONObject.fromObject(contextannotation);
            }
            return new AlaudaContext(name,namespace,data,true);
        }
        throw new IllegalStateException("not intance of WorkflowJob");
    }

    /**
     * It's quite different between different Jenkins jobs.
     * @param workflowJob pipeline job
     * @return the AlaudaJobProperty which comes from Alauda pipeline job
     */
    private AlaudaJobProperty getAlaudaJobProperty(WorkflowJob workflowJob) {
        AlaudaJobProperty property = workflowJob.getProperty(WorkflowJobProperty.class);
        if (property == null && workflowJob.getParent() instanceof WorkflowMultiBranchProject) {
            return ((WorkflowMultiBranchProject) workflowJob.getParent()).getProperties().get(MultiBranchProperty.class);
        }

        return null;
    }
}
