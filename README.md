# Kite Provider Gradle Plugin

[![CI](https://github.com/kitecorp/kite-provider-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/kitecorp/kite-provider-gradle-plugin/actions/workflows/ci.yml)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/cloud.kitelang.provider)](https://plugins.gradle.org/plugin/cloud.kitelang.provider)
[![Maven Central](https://img.shields.io/maven-central/v/cloud.kitelang/kite-provider-gradle-plugin)](https://central.sonatype.com/artifact/cloud.kitelang/kite-provider-gradle-plugin)

Gradle plugin that simplifies building infrastructure providers for [Kite](https://github.com/kitecorp/kite), the multi-cloud Infrastructure as Code tool.

## Features

- Automatically configures Java, Application, and Shadow plugins
- Generates `provider.json` manifest for provider discovery (both in distribution and as JAR resource)
- Creates distribution packages with launcher scripts
- Injects Kite Provider SDK dependency
- Auto-detects mainClass by scanning for `extends ProviderServer` or `extends KiteProvider`
- Configures shadow JAR with proper manifest

## Installation

The plugin is available from multiple sources. Choose the one that fits your needs:

### Option 1: Gradle Plugin Portal (Recommended)

The simplest option - no additional repository configuration needed:

```groovy
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}
```

### Option 2: Maven Central

For users who prefer Maven Central:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}
```

### Option 3: GitHub Packages

For organizations using GitHub Packages:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/kitecorp/kite-provider-gradle-plugin")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: settings.ext.find('gpr.user') ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: settings.ext.find('gpr.token') ?: ""
            }
        }
        gradlePluginPortal()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}
```

**Note:** GitHub Packages requires authentication. Set `GITHUB_TOKEN` environment variable or add credentials to `~/.gradle/gradle.properties`:
```properties
gpr.user=your-github-username
gpr.token=your-github-token
```

### Option 4: Maven Local (Development)

For local development or testing unreleased versions:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

```groovy
// build.gradle
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}
```

Build and install locally:
```bash
./gradlew publishToMavenLocal
```

## Usage

### Basic Configuration

```groovy
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}

group = 'com.example'
version = '1.0.0'

kiteProvider {
    name = 'my-provider'
}

dependencies {
    // Add your provider-specific dependencies
    implementation 'software.amazon.awssdk:ec2:2.40.7'
}
```

### Configuration Options

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `name` | String | project name | Provider name used in `provider.json` |
| `mainClass` | String | auto-detected | Fully qualified main class extending `ProviderServer` or `KiteProvider` |
| `protocolVersion` | Integer | `1` | Provider protocol version |
| `sdkVersion` | String | `0.1.0` | Kite Provider SDK version |

#### Examples

Custom provider name:

```groovy
kiteProvider {
    name = 'aws-provider'
}
```

Explicit main class (when auto-detection fails or multiple providers exist):

```groovy
kiteProvider {
    mainClass = 'com.example.provider.MyCloudProvider'
}
```

Custom protocol version:

```groovy
kiteProvider {
    protocolVersion = 2
}
```

Specific SDK version:

```groovy
kiteProvider {
    sdkVersion = '0.2.0'
}
```

Full configuration:

```groovy
kiteProvider {
    name = 'my-cloud'
    mainClass = 'com.example.provider.MyCloudProvider'
    protocolVersion = 1
    sdkVersion = '0.1.0'
}
```

**Note:** The `mainClass` is automatically detected by scanning source files for a class that extends `ProviderServer` or `KiteProvider`. You only need to specify it manually if auto-detection fails or you have multiple provider classes.

### Tasks

The plugin registers the following tasks:

| Task | Description |
|------|-------------|
| `installDist` | Creates distribution with launcher scripts |
| `generateProviderManifest` | Generates `provider.json` (runs after `installDist`) |
| `generateProviderInfo` | Generates `provider.json` as JAR resource |
| `installMinDist` | Creates minimized distribution using shadow JAR |
| `shadowJar` | Creates fat JAR with all dependencies |

### Build Output

After running `./gradlew installDist`, the distribution is created at:

```
build/install/<provider-name>/
├── bin/
│   └── provider          # Launcher script
├── lib/
│   └── *.jar            # Dependencies
└── provider.json        # Provider manifest
```

## Example Provider

### build.gradle

```groovy
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}

group = 'cloud.kitelang'
version = '0.1.0'

repositories {
    mavenCentral()
}

kiteProvider {
    name = 'my-cloud'
}

dependencies {
    // Lombok (optional)
    compileOnly 'org.projectlombok:lombok:1.18.42'
    annotationProcessor 'org.projectlombok:lombok:1.18.42'

    // Your cloud SDK
    implementation 'com.example:cloud-sdk:1.0.0'
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
```

### Provider Class

```java
public class MyCloudProvider extends KiteProvider {
    public MyCloudProvider() {
        // Name and version auto-loaded from provider.json
    }

    public static void main(String[] args) throws Exception {
        ProviderServer.serve(new MyCloudProvider());
    }
}
```

## Provider Manifest

The generated `provider.json` follows this format:

```json
{
    "name": "my-cloud",
    "version": "0.1.0",
    "protocolVersion": 1,
    "executable": "bin/provider"
}
```

This file is generated in two locations:
1. **Distribution directory** (`build/install/<name>/provider.json`) - for engine discovery
2. **JAR resource** (`META-INF/kite/provider.json`) - for runtime name/version auto-detection

## Publishing (For Plugin Maintainers)

### To Gradle Plugin Portal

Requires `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` from [plugins.gradle.org](https://plugins.gradle.org):

```bash
./gradlew publishPlugins
```

Or trigger the GitHub Action by creating a release.

### To GitHub Packages

```bash
GITHUB_TOKEN=your-token ./gradlew publishAllPublicationsToGitHubPackagesRepository
```

Or trigger the GitHub Action by creating a release.

### To Maven Central

Requires Sonatype Central Portal credentials and GPG signing key. Uses [JReleaser](https://jreleaser.org/) for publishing:

```bash
JRELEASER_MAVENCENTRAL_SONATYPE_USERNAME=your-token-username \
JRELEASER_MAVENCENTRAL_SONATYPE_PASSWORD=your-token-password \
JRELEASER_GPG_SECRET_KEY="$(cat private-key.asc)" \
JRELEASER_GPG_PASSPHRASE=your-passphrase \
./gradlew publishToMavenCentral
```

Generate your token credentials at [central.sonatype.com/account](https://central.sonatype.com/account).

Or trigger the GitHub Action by creating a release.

### To Maven Local

```bash
./gradlew publishToMavenLocal
```

### Publish to All

```bash
./gradlew publishAll
```

## CI/CD

This project uses GitHub Actions for:

- **CI** (`ci.yml`) - Builds and tests on every push/PR to main
- **Publish to Gradle Plugin Portal** (`publish-gradle-portal.yml`) - Publishes on release
- **Publish to GitHub Packages** (`publish-github-packages.yml`) - Publishes on release
- **Publish to Maven Central** (`publish-maven-central.yml`) - Publishes on release

### Required Secrets

| Secret | Description | Required For |
|--------|-------------|--------------|
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portal API key | Gradle Plugin Portal |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal API secret | Gradle Plugin Portal |
| `GITHUB_TOKEN` | Auto-provided by GitHub Actions | GitHub Packages, JReleaser |
| `MAVEN_USERNAME` | Sonatype Central Portal token username | Maven Central |
| `MAVEN_PASSWORD` | Sonatype Central Portal token password | Maven Central |
| `GPG_PUBLIC_KEY` | GPG public key (armored ASCII format) | Maven Central (JReleaser) |
| `GPG_PRIVATE_KEY` | GPG private key (armored ASCII format) | Maven Central, Signing |
| `GPG_PASSPHRASE` | GPG key passphrase (can be empty) | Maven Central, Signing |

### Obtaining GPG Keys

Generate a GPG key pair if you don't have one:

```bash
gpg --full-generate-key
```

Export the public key (for `GPG_PUBLIC_KEY` secret):

```bash
gpg --armor --export YOUR_KEY_ID
```

Export the private key (for `GPG_PRIVATE_KEY` secret):

```bash
gpg --armor --export-secret-keys YOUR_KEY_ID
```

To find your key ID:

```bash
gpg --list-keys --keyid-format SHORT
```

## Requirements

- Java 21+
- Gradle 8.0+

## Related Projects

- [kite](https://github.com/kitecorp/kite) - Kite IaC CLI
- [kite-provider-sdk](https://github.com/kitecorp/kite-provider-sdk) - SDK for building providers

## License

Apache License 2.0
