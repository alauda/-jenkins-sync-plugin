package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.util.XStream2;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServiceEnum;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.kubernetes.api.model.CodeRepoBinding;
import io.alauda.kubernetes.api.model.CodeRepoBindingAccount;
import io.alauda.kubernetes.api.model.CodeRepoBindingSpec;
import io.alauda.kubernetes.api.model.CodeRepository;
import io.alauda.kubernetes.api.model.CodeRepositoryRef;
import io.alauda.kubernetes.api.model.CodeRepositorySpec;
import io.alauda.kubernetes.api.model.Condition;
import io.alauda.kubernetes.api.model.MultiBranchBehaviours;
import io.alauda.kubernetes.api.model.MultiBranchOrphan;
import io.alauda.kubernetes.api.model.MultiBranchPipeline;
import io.alauda.kubernetes.api.model.OriginCodeRepository;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigSpec;
import io.alauda.kubernetes.api.model.PipelineConfigStatus;
import io.alauda.kubernetes.api.model.PipelineSource;
import io.alauda.kubernetes.api.model.PipelineSourceGit;
import io.alauda.kubernetes.api.model.PipelineStrategyJenkins;
import jenkins.branch.BranchProperty;
import jenkins.branch.BranchSource;
import jenkins.branch.DefaultBranchPropertyStrategy;
import jenkins.model.Jenkins;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceBuilder;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND_MULTI_BRANCH;

/**
 * TODO 需要考虑的兼容问题： 当 git 服务从不支持 PR 转到支持的情况下，如何保持不修改已经有结构（例如：gitlab 暂时不支持）
 * 对于不支持 pr 的情况，fallback 到普通的 git
 */
@Extension
public class ConvertToMultiBranch implements PipelineConfigConvert<WorkflowMultiBranchProject> {
    private final Logger logger = Logger.getLogger(ConvertToMultiBranch.class.getName());

    @Override
    public boolean accept(PipelineConfig pipelineConfig) {
        if(pipelineConfig == null) {
            return false;
        }

        Map<String, String> labels = pipelineConfig.getMetadata().getLabels();
        return (PIPELINECONFIG_KIND_MULTI_BRANCH.equals(labels.get(PIPELINECONFIG_KIND)));
    }

    @Override
    public WorkflowMultiBranchProject convert(PipelineConfig pipelineConfig) throws IOException {
        String jobName = AlaudaUtils.jenkinsJobName(pipelineConfig);
        String jobFullName = AlaudaUtils.jenkinsJobFullName(pipelineConfig);
        String namespace = pipelineConfig.getMetadata().getNamespace();
        String name = pipelineConfig.getMetadata().getName();
        String uid = pipelineConfig.getMetadata().getUid();
        String resourceVer = pipelineConfig.getMetadata().getResourceVersion();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return null;
        }

        WorkflowMultiBranchProject job = PipelineConfigToJobMap.getMultiBranchByPC(pipelineConfig);
        Jenkins activeInstance = Jenkins.getInstance();
        ItemGroup parent = activeInstance;
        if (job == null) {
            logger.info(String.format("No job [namespace: %s, name: %s] found from the cache.", namespace, name));
            job = (WorkflowMultiBranchProject) activeInstance.getItemByFullName(jobFullName);
        }

        boolean newJob = job == null;
        if (newJob) {
            parent = AlaudaUtils.getFullNameParent(activeInstance, jobFullName, AlaudaUtils.getNamespace(pipelineConfig));
            job = new WorkflowMultiBranchProject(parent, jobName);
            job.addProperty(new MultiBranchProperty(namespace, name, uid, resourceVer));

            logger.info(String.format("New MultiBranchProject [%s] will be created.", job.getFullName()));
        } else {
            MultiBranchProperty mbProperty = job.getProperties().get(MultiBranchProperty.class);
            if(mbProperty == null) {
                logger.warning(String.format("No MultiBranchProperty in job: %s.", job.getFullName()));
                return null;
            }

            if(isSameJob(pipelineConfig, mbProperty)) {
                mbProperty.setResourceVersion(resourceVer);

                PipelineConfigToJobMap.putJobWithPipelineConfig(job, pipelineConfig);
            } else {
                return null;
            }
        }

        // we just support only one source
        job.getSourcesList().clear();

        PipelineConfigSpec spec = pipelineConfig.getSpec();
        PipelineStrategyJenkins strategy = spec.getStrategy().getJenkins();
        if(strategy == null) {
            logger.severe(String.format("No strategy in here, namespace: %s, name: %s.", namespace, name));
            return null;
        }

        WorkflowBranchProjectFactory wfFactory = new WorkflowBranchProjectFactory();
        wfFactory.setScriptPath(strategy.getJenkinsfilePath());
        job.setProjectFactory(wfFactory);

        MultiBranchBehaviours behaviours = null;
        // orphaned setting
        MultiBranchPipeline multiBranch = strategy.getMultiBranch();
        if(multiBranch != null) {
            MultiBranchOrphan orphaned = multiBranch.getOrphaned();
            DefaultOrphanedItemStrategy orphanedStrategy;
            if(orphaned != null) {
                orphanedStrategy = new DefaultOrphanedItemStrategy(
                        true, String.valueOf(orphaned.getDays()), String.valueOf(orphaned.getMax()));
            } else {
                orphanedStrategy = new DefaultOrphanedItemStrategy(false, "", "");
            }
            job.setOrphanedItemStrategy(orphanedStrategy);

            behaviours = multiBranch.getBehaviours();
        }

        PipelineSource source = spec.getSource();
        AbstractGitSCMSource gitSCMSource = null;

        CodeRepositoryRef codeRepoRef = source.getCodeRepository();
        PipelineSourceGit gitSource = source.getGit();
        // TODO maybe put some redundancy into annotation
        if(codeRepoRef != null) {
            String codeRepoName = codeRepoRef.getName();

            CodeRepository codeRep = client.codeRepositories().inNamespace(namespace).withName(codeRepoName).get();
            if(codeRep != null) {
                CodeRepositorySpec codeRepoSpec = codeRep.getSpec();
                OriginCodeRepository codeRepo = codeRepoSpec.getRepository();
                String repoOwner = codeRepo.getOwner().getName();
                String repository = codeRepo.getName();
                String codeRepoType = codeRepo.getCodeRepoServiceType();
                String codeBindName = codeRepoSpec.getCodeRepoBinding().getName();

                CodeRepoBinding codeRepoBind = client.codeRepoBindings().inNamespace(namespace).withName(codeBindName).get();
                CodeRepoBindingSpec codeRepoBindSpec = codeRepoBind.getSpec();
                CodeRepoBindingAccount account = codeRepoBindSpec.getAccount();

                if(haveSupported(codeRepoSpec, pipelineConfig.getStatus())) {
                    // TODO need to deal with the private repo

                    try {
                        gitSCMSource = createGitSCMSource(codeRepoType, repoOwner, repository);
                        if(gitSCMSource == null) {
                            logger.warning(String.format("Can't create instance for AbstractGitSCMSource. Type is %s.", codeRepoType));
                            return null;
                        }
                    } catch (ReflectiveOperationException e) {
                        e.printStackTrace();
                    }
                } else {
                    // TODO should take care of clean up job
                    logger.warning(String.format("Not support for %s, codeRepo name is %s.", codeRepoType, codeRepoName));
                    return null;
                }
            } else {
                logger.warning(String.format("Can't found codeRepository %s, namespace %s.", codeRepoName, namespace));
            }
        } else if(gitSource != null) {
            String uri = gitSource.getUri();
            String credentialId = CredentialsUtils.updateSourceCredentials(pipelineConfig);

            GitSCMSource scmSource = new GitSCMSource(uri);
            scmSource.setCredentialsId(credentialId);
            gitSCMSource = scmSource;
        } else {
            logger.warning("Not found git repository.");
        }

        if(gitSCMSource != null) {
            List<SCMSourceTrait> traits = gitSCMSource.getTraits();

            if(behaviours != null && StringUtils.isNotBlank(behaviours.getFilterExpression())) {
                traits.add(new RegexSCMHeadFilterTrait(behaviours.getFilterExpression()));
            }

            traits.add(new BranchDiscoveryTrait());

            job.getSourcesList().add(new BranchSource(gitSCMSource));
        }

        InputStream jobStream = new StringInputStream(new XStream2().toXML(job));
        if (newJob) {
            if (parent instanceof Folder) {
                Folder folder = (Folder) parent;
                folder.createProjectFromXML(jobName, jobStream).save();
            } else {
                activeInstance.createProjectFromXML(jobName, jobStream).save();
            }

            logger.info("Created job " + jobName + " from PipelineConfig " + NamespaceName.create(pipelineConfig)
                    + " with revision: " + resourceVer);
        } else {
            updateJob(job, jobStream, jobName, pipelineConfig);
        }

        updatePipelineConfigPhase(pipelineConfig);
        PipelineConfigToJobMap.putJobWithPipelineConfig(job, pipelineConfig);

        return job;
    }

    private AbstractGitSCMSource createGitSCMSource(@NotNull String type, @NotNull String repoOwner, @NotNull String repository)
            throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        String gitSCMSourceClz;
        if(CodeRepoServiceEnum.Github.name().equals(type)) {
            gitSCMSourceClz = "org.jenkinsci.plugins.github_branch_source.GitHubSCMSource";
        } else if(CodeRepoServiceEnum.Bitbucket.name().equals(type)) {
            gitSCMSourceClz = "com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource";
        } else {
            return null;
        }

        Class<?> gitSCMSource = Class.forName(gitSCMSourceClz);
        AbstractGitSCMSource instance = (AbstractGitSCMSource) gitSCMSource.getConstructor(String.class, String.class).newInstance(repoOwner, repository);

        Method setApiUri = gitSCMSource.getMethod("setApiUri", String.class);
        setApiUri.invoke(instance, "http://baidu.com");

        return instance;
    }

    private boolean haveSupported(@NotNull CodeRepositorySpec codeRepoSpec, @NotNull PipelineConfigStatus status) {
        OriginCodeRepository repo = codeRepoSpec.getRepository();
//        if(repo.getPrivate() != null || repo.getPrivate()) {
//            Condition condition = new Condition();
//            condition.setStatus("ERROR");
//            condition.setReason("No support for private git service");
//            status.getConditions().add(condition);
//            return false;
//        }

        String type = repo.getCodeRepoServiceType();
        if(!CodeRepoServiceEnum.Bitbucket.name().equals(type) && !CodeRepoServiceEnum.Github.name().equals(type)) {
            Condition condition = new Condition();
            condition.setStatus("ERROR");
            condition.setReason("No support for " + type);
            status.getConditions().add(condition);
            return false;
        }

        return true;
    }
}
