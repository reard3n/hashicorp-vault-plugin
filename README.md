[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins/hashicorp-vault-plugin/master)](https://ci.jenkins.io/job/Plugins/job/hashicorp-vault-plugin/job/master/)
# Jenkins Vault Plugin

This plugin adds a build wrapper to set environment variables from a HashiCorp [Vault](https://www.vaultproject.io/) secret. Secrets are generally masked in the build log, so you can't accidentally print them.  
It also has the ability to inject Vault credentials into a build pipeline or freestyle job for fine-grained vault interactions.

# Vault Authentication Backends
This plugin allows authenticating against Vault using the AppRole authentication backend. Hashicorp recommends using AppRole for Servers / automated workflows (like Jenkins) and using Tokens (default mechanism, Github Token, ...) for every developer's machine.
  Furthermore, this plugin allows using a Github personal access token, or a Vault Token - either configured directly in Jenkins or read from an arbitrary file on the Jenkins Machine.

### How does AppRole work?
In short: you register an approle auth backend using a self-chosen name (e.g. Jenkins). This approle is identified by a `role-id` and secured with a `secret_id`. If you have both of those values you can ask Vault for a token that can be used to access vault.
When registering the approle backend you can set a couple of different parameters:
* How long should the `secret_id` live (can be indefinite)
* how often can one use a token that is obtained via this backend
* which IP addresses can obtain a token using `role-id` and `secret-id`?
* many more

This is just a short introduction, please refer to [Hashicorp itself](https://www.vaultproject.io/docs/auth/approle.html) to get detailed information.
### What about other backends?
Hashicorp explicitly recommends the AppRole Backend for machine-to-machine authentication. Token based auth is mainly supported for backwards compatibility.
Other backends that might make sense are the AWS EC2 backend, the Azure backend, and the Kubernetes backend. But we do not support these yet. Feel free to contribute!

Implementing additional authentication backends is actually quite easy:

Simply provide a class extending `AbstractVaultTokenCredential` that contains a `Descriptor` extending `BaseStandardCredentialsDescriptor`.
The `Descriptor` needs to be annotated with `@Extension`. Your credential needs to know how to authenticate with Vault and provide an authenticated Vault session.
See [VaultAppRoleCredential.java](https://github.com/jenkinsci/hashicorp-vault-plugin/blob/master/src/main/java/com/datapipe/jenkins/vault/credentials/VaultAppRoleCredential.java) for an example.


# Plugin Usage
### Configuration
You can configure the plugin on three different levels:
* Global: in your global config
* [Folder](https://wiki.jenkins-ci.org/display/JENKINS/CloudBees+Folders+Plugin)-Level: on the folder your job is running in
* Job-Level: either on your freestyle project job or directly in the Jenkinsfile

The lower the level the higher its priority, meaning: if you configure a URL in your global settings, but override it in your particular job, this URL will be used for communicating with Vault.
In your configuration (may it be global, folder or job) you see the following screen:
![Global Configuration](docs/images/configuration_screen.png)

The credential you provide determines what authentication backend will be used.
Currently, there are five different Credential Types you can use:

#### Vault App Role Credential

![App Role Credential](docs/images/approle_credential.png)

You enter your `role-id` and `secret-id` there. The description helps to find your credential later, the id is not mandatory (a UUID is generated by default), but it helps to set it if you want to use your credential inside the Jenkinsfile.

The `path` field is the approle authentication path. This is, by default, "approle" and this will also be used if no path is specified here.

#### Vault Github Credential

![Github Credentia](docs/images/github_credential.png)

You enter your github personal access token to authenticate to vault.

#### Vault GCP Credential

![GCP Credential](docs/images/gcp_credential.png)

You enter your Vault GCP auth `role` name and `audience`. The JWT will be automatically retrieved from GCE metdata. This requires that Jenkins master is running on a GCE instance.

#### Vault Token Credential

![Token Credential](docs/images/token_credential.png)

Directly specify a token to be used when authenticating with vault.

#### Vault Token File Credential

![Token File Credential](docs/images/tokenfile_credential.png)

Basically the same as the Vault Token Credential, just that the token is read from a file on your Jenkins Machine.
You can use this in combination with a script that periodically refreshes your token.

### Usage in FreeStyle Jobs
If you still use free style jobs (hint: you should consider migrating to [Jenkinsfile](https://jenkins.io/doc/book/pipeline/)), you can configure both configuration and the secrets you need on the job level.

![Job Configuration](docs/images/job_screen.png)

The secrets are available as environment variables then.

### Usage via Jenkinsfile
Let the code speak for itself:
```groovy
node {
  // define the secrets and the env variables
  def secrets = [
      [$class: 'VaultSecret', path: 'secret/testing', secretValues: [
          [$class: 'VaultSecretValue', envVar: 'testing', vaultKey: 'value_one'],
          [$class: 'VaultSecretValue', envVar: 'testing_again', vaultKey: 'value_two']]],
      [$class: 'VaultSecret', path: 'secret/another_test', secretValues: [
          [$class: 'VaultSecretValue', envVar: 'another_test', vaultKey: 'value']]]
  ]

  // optional configuration, if you do not provide this the next higher configuration
  // (e.g. folder or global) will be used
  def configuration = [$class: 'VaultConfiguration',
                       vaultUrl: 'http://my-very-other-vault-url.com',
                       vaultNamespace: 'mynamespace',
                       vaultCredentialId: 'my-vault-cred-id']

  // inside this block your credentials will be available as env variables
  wrap([$class: 'VaultBuildWrapper', configuration: configuration, vaultSecrets: secrets]) {
      sh 'echo $testing'
      sh 'echo $testing_again'
      sh 'echo $another_test'
  }
}
```
In the future we might migrate to a [BuildStep](http://javadoc.jenkins-ci.org/hudson/tasks/BuildStep.html) instead of a BuildWrapper.

# Inject Vault Credentials into your Job
### Pipeline Usage
![withCredentials Block](docs/images/pipeline_withCredentials.png)

Specify the variables for the vault address and token.  Vault Address and Credentials are both required.  
`addrVariable` and `tokenVariable` are optional.  They will be set to `VAULT_ADDR` and `VAULT_TOKEN` repectively if omitted.

```groovy
node {
    withCredentials([[$class: 'VaultTokenCredentialBinding', credentialsId: 'vaulttoken', vaultAddr: 'https://localhost:8200']]) {
        // values will be masked
        sh 'echo TOKEN=$VAULT_TOKEN'
        sh 'echo ADDR=$VAULT_ADDR'
    }
}
```

### FreeStyle Job
![freeStyle Credential Binding](docs/images/freestyle_injectCreds.png)


# Migration Guide

### Upgrade from 1.x to 2.0
The `BuildWrapper` did not change, so no changes to your Jenkinsfile should be necessary. However, you need to reconfigure Vault in your Jenkins instance based on the instructions above. There is no way to smoothly upgrade this, because this is a major rewrite and handling of configuration completly changed.

# CHANGELOG
* **2018/08/22** - Feature Release - 2.2.0
  * Add support for GCP authentication
* **2018/05/01** - Bugfix Release - 2.1.1
  * MaskingConsoleLogFilter should filter out null secrets [JENKINS-46792](https://issues.jenkins-ci.org/browse/JENKINS-38647)
  * [Avoid NPE Crash](https://github.com/jenkinsci/hashicorp-vault-plugin/pull/13)
  * Switch to SimpleBuildWrapper for pipeline compatibility [JENKINS-48049](https://issues.jenkins-ci.org/browse/JENKINS-48049)
  * Dynamic secrets should be revoked after build wrapper completes [JENKINS-46794](https://issues.jenkins-ci.org/browse/JENKINS-46794)
* **2017/05/22** - Feature Release - 2.1.0
  * Vault Key Not Saved In Vault Error Messaging [JENKINS-38647](https://issues.jenkins-ci.org/browse/JENKINS-38647)
  * Add support github token auth [JENKINS-38939](https://issues.jenkins-ci.org/browse/JENKINS-38939)
* **2017/05/19** - Bugfix Release - 2.0.1
  * Build fails if plugin is enabled for a job without secrets specified [JENKINS-44163](https://issues.jenkins-ci.org/browse/JENKINS-441630)
* **2017/04/27** - Breaking change release (AppRole auth backend, Folder ability, improved configuration, ...)
* **2017/04/10** - Feature Release - 1.4
  * Support reading Vault Token from file on disk [JENKINS-37713](issues.jenkins-ci.org/browse/JENKINS-37713)
  * Using credentials plugin for authentication token [JENKINS-38646](issues.jenkins-ci.org/browse/JENKINS-38646)
* **2017/03/03** - Feature Release - 1.3
  * Vault Plugin should mask credentials in build log [JENKINS-39383](issues.jenkins-ci.org/browse/JENKINS-39383)
* **2016/08/15** - Re-release due to failed maven release - 1.2
* **2016/08/11** - Bugfix release - 1.1
  * Refactor to allow getting multiple vault keys in a single API call [JENKINS-37151](https://issues.jenkins-ci.org/browse/JENKINS-37151)
* **2016/08/02** - Initial release - 1.0

[global_configuration]: docs/images/global_configuration.png
[job_configuration]: docs/images/job_configuration.png
