# Kite Provider Gradle Plugin

Gradle plugin that simplifies building infrastructure providers for [Kite](https://github.com/kitecorp/kite), the multi-cloud Infrastructure as Code tool.

## Features

- Automatically configures Java, Application, and Shadow plugins
- Generates `provider.json` manifest for provider discovery
- Creates distribution packages with launcher scripts
- Injects Kite Provider SDK dependency
- Configures shadow JAR with proper manifest

## Installation

Add the plugin to your `build.gradle`:

```groovy
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}
```

Make sure the plugin is available in your repositories:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

## Usage

### Basic Configuration

```groovy
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}

kiteProvider {
    name = 'aws'
    // mainClass auto-detected from class extending ProviderServer/KiteProvider
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

**Note:** The `mainClass` is automatically detected by scanning source files for a class that extends `ProviderServer` or `KiteProvider`. You only need to specify it manually if auto-detection fails or you have multiple provider classes.

### Tasks

The plugin registers the following tasks:

| Task | Description |
|------|-------------|
| `installDist` | Creates distribution with launcher scripts |
| `generateProviderManifest` | Generates `provider.json` (runs after `installDist`) |
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

```groovy
// build.gradle
plugins {
    id 'cloud.kitelang.provider' version '0.1.0'
}

group = 'cloud.kitelang'
version = '0.1.0'

repositories {
    mavenLocal()
    mavenCentral()
}

kiteProvider {
    name = 'my-cloud'
    // mainClass auto-detected from class extending ProviderServer/KiteProvider
}

dependencies {
    // Lombok (optional)
    compileOnly 'org.projectlombok:lombok:1.18.36'
    annotationProcessor 'org.projectlombok:lombok:1.18.36'

    // Your cloud SDK
    implementation 'com.example:cloud-sdk:1.0.0'
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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

## Requirements

- Java 21+
- Gradle 8.0+

## Related Projects

- [kite](https://github.com/kitecorp/kite) - Kite IaC CLI
- [kite-provider-sdk](https://github.com/kitecorp/kite-provider-sdk) - SDK for building providers

## License

Apache License 2.0
