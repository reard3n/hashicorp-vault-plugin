/**
 * The MIT License (MIT)
 * <p>
 * Copyright (c) 2016 Datapipe, Inc.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.datapipe.jenkins.vault;

import com.google.common.annotations.VisibleForTesting;

import com.bettercloud.vault.VaultException;
import com.bettercloud.vault.response.LogicalResponse;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsUnavailableException;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.matchers.IdMatcher;
import com.datapipe.jenkins.vault.configuration.VaultConfigResolver;
import com.datapipe.jenkins.vault.configuration.VaultConfiguration;
import com.datapipe.jenkins.vault.credentials.VaultCredential;
import com.datapipe.jenkins.vault.exception.VaultPluginException;
import com.datapipe.jenkins.vault.log.MaskingConsoleLogFilter;
import com.datapipe.jenkins.vault.model.VaultSecret;
import com.datapipe.jenkins.vault.model.VaultSecretValue;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import hudson.EnvVars;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.tasks.SimpleBuildWrapper;

public class VaultBuildWrapper extends SimpleBuildWrapper {
    private VaultConfiguration configuration;
    private List<VaultSecret> vaultSecrets;
    private List<String> valuesToMask = new ArrayList<>();
    private VaultAccessor vaultAccessor = new VaultAccessor();
    private PrintStream logger;

    @DataBoundConstructor
    public VaultBuildWrapper(@CheckForNull List<VaultSecret> vaultSecrets) {
        this.vaultSecrets = vaultSecrets;
    }

    @Override
    public void setUp(Context context, Run<?, ?> build, FilePath workspace,
                      Launcher launcher, TaskListener listener, EnvVars initialEnvironment) {
        logger = listener.getLogger();
        pullAndMergeConfiguration(build);

        // JENKINS-44163 - Build fails with a NullPointerException when no secrets are given for a job
        if (null != vaultSecrets && !vaultSecrets.isEmpty()) {
            provideEnvironmentVariablesFromVault(context, build);
        }
    }


    public List<VaultSecret> getVaultSecrets() {
        return this.vaultSecrets;
    }

    @DataBoundSetter
    public void setConfiguration(VaultConfiguration configuration) {
        this.configuration = configuration;
    }

    public VaultConfiguration getConfiguration() {
        return this.configuration;
    }

    @VisibleForTesting
    public void setVaultAccessor(VaultAccessor vaultAccessor) {
        this.vaultAccessor = vaultAccessor;
    }

    private List<String> retrieveLeaseIds(List<LogicalResponse> logicalResponses) {
        List<String> leaseIds = new ArrayList<>();
        for (LogicalResponse response : logicalResponses) {
            String leaseId = response.getLeaseId();
            if (leaseId != null && !leaseId.isEmpty()) {
                leaseIds.add(leaseId);
            }
        }
        return leaseIds;
    }

    private List<LogicalResponse> provideEnvironmentVariablesFromVault(Context context, Run build) {
        String url = getConfiguration().getVaultUrl();
        String nameSpace = getConfiguration().getVaultNamespace();

        if (StringUtils.isBlank(url)) {
            throw new VaultPluginException("The vault url was not configured - please specify the vault url to use.");
        }

        VaultCredential credential = retrieveVaultCredentials(build);

        vaultAccessor.init(url, nameSpace, credential, configuration.isSkipSslVerification());
        ArrayList<LogicalResponse> responses = new ArrayList<>();
        for (VaultSecret vaultSecret : vaultSecrets) {
            try {
                LogicalResponse response = vaultAccessor.read(vaultSecret.getPath(), vaultSecret.getEngineVersion());
                responses.add(response);
                Map<String, String> values = response.getData();
                for (VaultSecretValue value : vaultSecret.getSecretValues()) {
                    valuesToMask.add(values.get(value.getVaultKey()));
                    context.env(value.getEnvVar(), values.get(value.getVaultKey()));
                }
            }catch (VaultPluginException ex) {
                VaultException e = (VaultException) ex.getCause();
                if (e.getHttpStatusCode() == 404 && !configuration.isFailIfNotFound())
                    logger.println("Vault credentials not found " + vaultSecret.getPath());
                else
                    throw ex;
            }
        }
        return responses;
    }

    private VaultCredential retrieveVaultCredentials(Run build) {
        String id = getConfiguration().getVaultCredentialId();
        if (StringUtils.isBlank(id)) {
            throw new VaultPluginException("The credential id was not configured - please specify the credentials to use.");
        }
        List<VaultCredential> credentials = CredentialsProvider.lookupCredentials(VaultCredential.class, build.getParent(), ACL.SYSTEM, Collections.<DomainRequirement>emptyList());
        VaultCredential credential = CredentialsMatchers.firstOrNull(credentials, new IdMatcher(id));

        if (credential == null) {
            throw new CredentialsUnavailableException(id);
        }

        return credential;
    }

    private void pullAndMergeConfiguration(Run<?, ?> build) {
        for (VaultConfigResolver resolver : ExtensionList.lookup(VaultConfigResolver.class)) {
            if (configuration != null) {
                configuration = configuration.mergeWithParent(resolver.forJob(build.getParent()));
            } else {
                configuration = resolver.forJob(build.getParent());
            }
        }
        if (configuration == null) {
            throw new VaultPluginException("No configuration found - please configure the VaultPlugin.");
        }
    }

    @Override
    public ConsoleLogFilter createLoggerDecorator(
            @Nonnull final Run<?, ?> build) {
        return new MaskingConsoleLogFilter(build.getCharset().name(), valuesToMask);
    }


    /**
     * Descriptor for {@link VaultBuildWrapper}. Used as a singleton. The class is marked as public so
     * that it can be accessed from views.
     */
    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        public DescriptorImpl() {
            super(VaultBuildWrapper.class);
            load();
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        @Override
        public String getDisplayName() {
            return "Vault Plugin";
        }
    }
}
