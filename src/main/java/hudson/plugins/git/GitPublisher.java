/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Oracle Corporation, Andrew Bayer, Anton Kozak, Nikita Levyankov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.git;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.Launcher;
import hudson.matrix.MatrixAggregatable;
import hudson.matrix.MatrixAggregator;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixRun;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.plugins.git.opt.PreBuildMergeOptions;
import hudson.plugins.git.util.GitConstants;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class GitPublisher extends Recorder implements Serializable, MatrixAggregatable {
    private static final long serialVersionUID = 1L;

    /**
     * Store a config version so we're able to migrate config on various
     * functionality upgrades.
     */
    private Long configVersion;

    private boolean pushMerge;
    private boolean pushOnlyIfSuccess;

    private List<TagToPush> tagsToPush;
    // Pushes HEAD to these locations
    private List<BranchToPush> branchesToPush;

    @DataBoundConstructor
    public GitPublisher(List<TagToPush> tagsToPush,
                        List<BranchToPush> branchesToPush,
                        boolean pushOnlyIfSuccess,
                        boolean pushMerge) {
        this.tagsToPush = tagsToPush;
        this.branchesToPush = branchesToPush;
        this.pushMerge = pushMerge;
        this.pushOnlyIfSuccess = pushOnlyIfSuccess;
        this.configVersion = 2L;
    }

    public boolean isPushOnlyIfSuccess() {
        return pushOnlyIfSuccess;
    }

    public boolean isPushMerge() {
        return pushMerge;
    }

    public boolean isPushTags() {
        if (tagsToPush == null) {
            return false;
        }
        return !tagsToPush.isEmpty();
    }

    public boolean isPushBranches() {
        if (branchesToPush == null) {
            return false;
        }
        return !branchesToPush.isEmpty();
    }

    public List<TagToPush> getTagsToPush() {
        if (tagsToPush == null) {
            tagsToPush = new ArrayList<TagToPush>();
        }

        return tagsToPush;
    }

    public List<BranchToPush> getBranchesToPush() {
        if (branchesToPush == null) {
            branchesToPush = new ArrayList<BranchToPush>();
        }

        return branchesToPush;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    /**
     * For a matrix project, push should only happen once.
     */
    public MatrixAggregator createAggregator(MatrixBuild build, Launcher launcher, BuildListener listener) {
        return new MatrixAggregator(build, launcher, listener) {
            @Override
            public boolean endBuild() throws InterruptedException, IOException {
                return GitPublisher.this.perform(build, launcher, listener);
            }
        };
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build,
                           Launcher launcher, final BuildListener listener)
        throws InterruptedException {

        // during matrix build, the push back would happen at the very end only once for the whole matrix,
        // not for individual configuration build.
        if (build instanceof MatrixRun) {
            return true;
        }

        SCM scm = build.getProject().getScm();

        if (!(scm instanceof GitSCM)) {
            return false;
        }

        final GitSCM gitSCM = (GitSCM) scm;

        final String projectName = build.getProject().getName();
        final FilePath workspacePath = build.getWorkspace();
        final int buildNumber = build.getNumber();
        final Result buildResult = build.getResult();

        // If pushOnlyIfSuccess is selected and the build is not a success, don't push.
        if (pushOnlyIfSuccess && buildResult.isWorseThan(Result.SUCCESS)) {
            listener.getLogger()
                .println(
                    "Build did not succeed and the project is configured to only push after a successful build, so no pushing will occur.");
            return true;
        } else {
            final String gitExe = gitSCM.getGitExe(build.getBuiltOn(), listener);
            listener.getLogger().println("Git Exe: " + gitExe);
            EnvVars tempEnvironment;
            try {
                tempEnvironment = build.getEnvironment(listener);
            } catch (IOException e) {        
                e.printStackTrace(listener.error("IOException publishing in git plugin"));
                tempEnvironment = new EnvVars();
            }

            String confName = gitSCM.getGitConfigNameToUse();
            if (StringUtils.isNotBlank(confName)) {
                tempEnvironment.put(GitConstants.GIT_COMMITTER_NAME_ENV_VAR, confName);
                tempEnvironment.put(GitConstants.GIT_AUTHOR_NAME_ENV_VAR, confName);
            }
            String confEmail = gitSCM.getGitConfigEmailToUse();
            if (StringUtils.isNotBlank(confEmail)) {
                tempEnvironment.put(GitConstants.GIT_COMMITTER_EMAIL_ENV_VAR, confEmail);
                tempEnvironment.put(GitConstants.GIT_AUTHOR_EMAIL_ENV_VAR, confEmail);
            }

            final EnvVars environment = tempEnvironment;
            final FilePath workingDirectory = gitSCM.workingDirectory(workspacePath);

            boolean pushResult = true;
            // If we're pushing the merge back...
            if (pushMerge) {
                boolean mergeResult;
                try {
                    mergeResult = workingDirectory.act(new FileCallable<Boolean>() {
                        private static final long serialVersionUID = 1L;

                        public Boolean invoke(File workspace,
                                              VirtualChannel channel) throws IOException {

                            IGitAPI git = new GitAPI(
                                gitExe, new FilePath(workspace),
                                listener, environment);
                            try {
                                // We delete the old tag generated by the SCM plugin
                                String tagName = new StringBuilder()
                                    .append(GitConstants.INTERNAL_TAG_NAME_PREFIX)
                                    .append(GitConstants.HYPHEN_SYMBOL)
                                    .append(projectName)
                                    .append(GitConstants.HYPHEN_SYMBOL)
                                    .append(buildNumber)
                                    .toString();

                                git.deleteTag(tagName);

                                // And add the success / fail state into the tag.
                                tagName += "-" + buildResult.toString();

                                git.tag(tagName, GitConstants.INTERNAL_TAG_COMMENT_PREFIX + buildNumber);

                                PreBuildMergeOptions mergeOptions = gitSCM.getMergeOptions();

                                if (mergeOptions.doMerge() && buildResult.isBetterOrEqualTo(
                                    Result.SUCCESS)) {
                                    RemoteConfig remote = mergeOptions.getMergeRemote();
                                    listener.getLogger().println(new StringBuilder().append("Pushing result ")
                                        .append(tagName)
                                        .append(" to ")
                                        .append(mergeOptions.getMergeTarget())
                                        .append(" branch of ")
                                        .append(remote.getName())
                                        .append(" repository")
                                        .toString());

                                    git.push(remote, "HEAD:" + mergeOptions.getMergeTarget());
    //                            } else {
                                    //listener.getLogger().println("Pushing result " + buildnumber + " to origin repository");
                                    //git.push(null);
                                }
                            } finally {
                                git.close();
                            }

                            return true;
                        }
                    });
                } catch (Throwable ex) {
                    ex.printStackTrace(listener.error("Failed to push merge to origin repository: "));
                    build.setResult(Result.FAILURE);
                    mergeResult = false;

                }

                if (!mergeResult) {
                    pushResult = false;
                }
            }
            if (isPushTags()) {
                boolean allTagsResult = true;
                for (final TagToPush t : tagsToPush) {
                    boolean tagResult = true;
                    if (t.getTagName() == null) {
                        listener.getLogger().println("No tag to push defined");
                        tagResult = false;
                    }
                    if (t.getTargetRepoName() == null) {
                        listener.getLogger().println("No target repo to push to defined");
                        tagResult = false;
                    }
                    if (tagResult) {
                        final String tagName = environment.expand(t.getTagName());
                        final String targetRepo = environment.expand(t.getTargetRepoName());

                        try {
                            tagResult = workingDirectory.act(new FileCallable<Boolean>() {
                                private static final long serialVersionUID = 1L;

                                public Boolean invoke(File workspace,
                                                      VirtualChannel channel) throws IOException {

                                    IGitAPI git = new GitAPI(gitExe, new FilePath(workspace),
                                        listener, environment);
                                    try {
                                        RemoteConfig remote = gitSCM.getRepositoryByName(targetRepo);

                                        if (remote == null) {
                                            listener.getLogger()
                                                .println("No repository found for target repo name " + targetRepo);
                                            return false;
                                        }

                                        if (t.isCreateTag()) {
                                            if (git.tagExists(tagName)) {
                                                listener.getLogger()
                                                    .println("Tag " + tagName
                                                        + " already exists and Create Tag is specified, so failing.");
                                                return false;
                                            }
                                            git.tag(tagName, "Hudson Git plugin tagging with " + tagName);
                                        } else if (!git.tagExists(tagName)) {
                                            listener.getLogger()
                                                .println("Tag " + tagName
                                                    + " does not exist and Create Tag is not specified, so failing.");
                                            return false;
                                        }

                                        listener.getLogger().println("Pushing tag " + tagName + " to repo "
                                            + targetRepo);
                                        git.push(remote, tagName);
                                    } finally {
                                        git.close();
                                    }

                                    return true;
                                }
                            });
                        } catch (Throwable ex) {
                            ex.printStackTrace(listener.error("Failed to push tag " + tagName + " to " + targetRepo));
                            build.setResult(Result.FAILURE);
                            tagResult = false;
                        }
                    }

                    if (!tagResult) {
                        allTagsResult = false;
                    }
                }
                if (!allTagsResult) {
                    pushResult = false;
                }
            }

            if (isPushBranches()) {
                boolean allBranchesResult = true;
                for (final BranchToPush b : branchesToPush) {
                    boolean branchResult = true;
                    if (b.getBranchName() == null) {
                        listener.getLogger().println("No branch to push defined");
                        return false;
                    }
                    if (b.getTargetRepoName() == null) {
                        listener.getLogger().println("No branch repo to push to defined");
                        return false;
                    }
                    final String branchName = environment.expand(b.getBranchName());
                    final String targetRepo = environment.expand(b.getTargetRepoName());

                    if (branchResult) {
                        try {
                            branchResult = workingDirectory.act(new FileCallable<Boolean>() {
                                private static final long serialVersionUID = 1L;

                                public Boolean invoke(File workspace,
                                                      VirtualChannel channel) throws IOException {

                                    IGitAPI git = new GitAPI(gitExe, new FilePath(workspace),
                                        listener, environment);
                                    try {
                                        RemoteConfig remote = gitSCM.getRepositoryByName(targetRepo);

                                        if (remote == null) {
                                            listener.getLogger()
                                                .println("No repository found for target repo name " + targetRepo);
                                            return false;
                                        }

                                        listener.getLogger().println("Pushing HEAD to branch " + branchName + " at repo "
                                            + targetRepo);
                                        git.push(remote, "HEAD:" + branchName);
                                    } finally {
                                        git.close();
                                    }

                                    return true;
                                }
                            });
                        } catch (Throwable ex) {
                            ex.printStackTrace(listener.error("Failed to push branch " + branchName + " to "
                                + targetRepo));
                            build.setResult(Result.FAILURE);
                            branchResult = false;
                        }
                    }

                    if (!branchResult) {
                        allBranchesResult = false;
                    }
                }
                if (!allBranchesResult) {
                    pushResult = false;
                }

            }

            return pushResult;
        }
    }

    /**
     * Handles migration from earlier version - if we were pushing merges, we'll be
     * instantiated but tagsToPush will be null rather than empty.
     *
     * @return This.
     */
    private Object readResolve() {
        // Default unspecified to v0
        if (configVersion == null) {
            this.configVersion = 0L;
        }

        if (this.configVersion < 1L && tagsToPush == null) {
            this.pushMerge = true;
        }

        return this;
    }

    @Extension(ordinal = -1)
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public String getDisplayName() {
            return "Git Publisher";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/git/gitPublisher.html";
        }

        /**
         * Performs on-the-fly validation on the file mask wildcard.
         * <p/>
         * I don't think this actually ever gets called, but I'm modernizing it anyway.
         */
        public FormValidation doCheck(@AncestorInPath AbstractProject project, @QueryParameter String value)
            throws IOException {
            return FilePath.validateFileMask(project.getSomeWorkspace(), value);
        }

        public FormValidation doCheckTagName(@QueryParameter String value) {
            return checkFieldNotEmpty(value, "Tag Name");
        }

        public FormValidation doCheckBranchName(@QueryParameter String value) {
            return checkFieldNotEmpty(value, "Branch Name");
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        private FormValidation checkFieldNotEmpty(String value, String field) {
            value = StringUtils.strip(value);

            if (StringUtils.isBlank(value)) {
                return FormValidation.error(field + " is required.");
            }
            return FormValidation.ok();
        }
    }

    public static abstract class PushConfig implements Serializable {
        private static final long serialVersionUID = 1L;

        private String targetRepoName;

        public PushConfig(String targetRepoName) {
            this.targetRepoName = targetRepoName;
        }

        public String getTargetRepoName() {
            return targetRepoName;
        }

        public void setTargetRepoName() {
            this.targetRepoName = targetRepoName;
        }
    }

    public static final class BranchToPush extends PushConfig {
        private String branchName;

        public String getBranchName() {
            return branchName;
        }

        @DataBoundConstructor
        public BranchToPush(String targetRepoName, String branchName) {
            super(targetRepoName);
            this.branchName = branchName;
        }
    }

    public static final class TagToPush extends PushConfig {
        private String tagName;
        private boolean createTag;

        public String getTagName() {
            return tagName;
        }

        public boolean isCreateTag() {
            return createTag;
        }

        @DataBoundConstructor
        public TagToPush(String targetRepoName, String tagName, boolean createTag) {
            super(targetRepoName);
            this.tagName = tagName;
            this.createTag = createTag;
        }
    }


}
