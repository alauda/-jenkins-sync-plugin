package io.alauda.jenkins.devops.sync.event;

import hudson.Extension;
import hudson.model.Run;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

@Extension
public class EventGlobalVariable extends GlobalVariable {

  @Nonnull
  @Override
  public String getName() {
    return "alaudaEvent";
  }

  @Nonnull
  @Override
  public Object getValue(@Nonnull CpsScript script) throws Exception {
    Run<?, ?> build = script.$build();
    if (build == null) {
      throw new IllegalStateException("cannot find owning build");
    }

    if (build instanceof WorkflowRun) {
      return new EventContext((WorkflowRun) build);
    }
    throw new IllegalStateException("not instance of WorkflowRun");
  }
}
