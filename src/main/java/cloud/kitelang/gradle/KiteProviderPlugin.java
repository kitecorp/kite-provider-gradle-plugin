package cloud.kitelang.gradle;

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gradle plugin that simplifies building Kite infrastructure providers.
 * <p>
 * Applies necessary plugins and configures:
 * <ul>
 *   <li>Java compilation with SDK dependency</li>
 *   <li>Application plugin with main class</li>
 *   <li>Shadow plugin for fat JAR creation</li>
 *   <li>provider.json manifest generation</li>
 *   <li>Distribution tasks for deployment</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * plugins {
 *     id 'cloud.kitelang.provider'
 * }
 *
 * kiteProvider {
 *     name = 'aws'
 *     mainClass = 'cloud.kitelang.provider.aws.AwsProvider'
 * }
 * </pre>
 */
public class KiteProviderPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        // Apply required plugins
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(ApplicationPlugin.class);
        project.getPluginManager().apply(ShadowPlugin.class);

        // Create extension
        var extension = project.getExtensions().create("kiteProvider", KiteProviderExtension.class);

        // Set defaults
        extension.getProtocolVersion().convention(1);
        extension.getSdkVersion().convention("0.1.0");

        // Configure after evaluation (when extension values are set)
        project.afterEvaluate(p -> configure(p, extension));
    }

    private void configure(Project project, KiteProviderExtension extension) {
        var name = extension.getName().getOrElse(project.getName());
        var version = project.getVersion().toString();
        var protocolVersion = extension.getProtocolVersion().get();
        var sdkVersion = extension.getSdkVersion().get();

        // Create a provider that resolves the main class either from config or from generated manifest
        Provider<String> mainClassProvider = extension.getMainClass().orElse(
                project.provider(() -> readMainClassFromManifest(project))
        );

        // Configure application plugin with lazy main class resolution
        var javaApplication = project.getExtensions().getByType(JavaApplication.class);
        javaApplication.getMainClass().set(mainClassProvider);

        // Add SDK dependency
        project.getDependencies().add("implementation", "cloud.kitelang:kite-provider-sdk:" + sdkVersion);
        project.getDependencies().add("annotationProcessor", "cloud.kitelang:kite-provider-sdk:" + sdkVersion);

        // Generate provider.json as a resource (same format as distribution manifest)
        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        var mainSourceSet = sourceSets.getByName("main");
        var generatedResourcesDir = project.getLayout().getBuildDirectory().dir("generated/resources/kite");

        // Register task to generate provider.json resource
        var generateProviderInfo = project.getTasks().register("generateProviderInfo", task -> {
            task.getOutputs().dir(generatedResourcesDir);

            task.doLast(t -> {
                var outputDir = generatedResourcesDir.get().getAsFile();
                var metaInfDir = new File(outputDir, "META-INF/kite");
                metaInfDir.mkdirs();

                var providerJson = new File(metaInfDir, "provider.json");
                var content = String.format("""
                    {
                        "name": "%s",
                        "version": "%s",
                        "protocolVersion": %d
                    }
                    """, name, version, protocolVersion);

                try {
                    Files.writeString(providerJson.toPath(), content);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write provider.json", e);
                }
            });
        });

        // Add generated resources to source set and wire up task dependency
        mainSourceSet.getResources().srcDir(generatedResourcesDir);
        project.getTasks().named("processResources").configure(task -> {
            task.dependsOn(generateProviderInfo);
        });

        // Configure shadow JAR
        project.getTasks().withType(ShadowJar.class).configureEach(shadowJar -> {
            shadowJar.getArchiveBaseName().set(name + "-provider");
            shadowJar.getArchiveClassifier().set("");
            shadowJar.getArchiveVersion().set("");
            shadowJar.mergeServiceFiles();

            shadowJar.manifest(manifest -> {
                manifest.getAttributes().put("Main-Class", mainClassProvider.get());
            });
        });

        // Configure startScripts task
        project.getTasks().named("startScripts", task -> {
            task.setProperty("applicationName", "provider");
        });

        // Register provider manifest generation task
        var installDistTask = project.getTasks().named("installDist", Sync.class);

        project.getTasks().register("generateProviderManifest", task -> {
            task.dependsOn(installDistTask);

            var installDir = installDistTask.get().getDestinationDir();
            var manifestFile = new File(installDir, "provider.json");

            task.getOutputs().file(manifestFile);

            task.doLast(t -> {
                var content = String.format("""
                    {
                        "name": "%s",
                        "version": "%s",
                        "protocolVersion": %d,
                        "executable": "bin/provider"
                    }
                    """, name, project.getVersion(), protocolVersion);

                try {
                    manifestFile.getParentFile().mkdirs();
                    Files.writeString(manifestFile.toPath(), content);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write provider.json", e);
                }
            });
        });

        // Wire up installDist to generate manifest
        installDistTask.configure(task -> {
            task.finalizedBy("generateProviderManifest");
        });

        // Register minimized distribution task
        project.getTasks().register("installMinDist", Copy.class, task -> {
            var shadowJarTask = project.getTasks().named("shadowJar", ShadowJar.class);
            task.dependsOn(shadowJarTask);

            task.from(shadowJarTask.map(ShadowJar::getArchiveFile), spec -> {
                spec.into("lib");
            });

            task.into(project.getLayout().getBuildDirectory().dir("install/" + name + "-min"));

            task.doLast(t -> {
                var buildDir = project.getLayout().getBuildDirectory().get().getAsFile();
                var binDir = new File(buildDir, "install/" + name + "-min/bin");
                binDir.mkdirs();

                // Create launcher script
                var launcherScript = new File(binDir, "provider");
                var scriptContent = """
                    #!/bin/sh
                    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
                    exec java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -jar "$SCRIPT_DIR/../lib/%s-provider.jar" "$@"
                    """.formatted(name);

                try {
                    Files.writeString(launcherScript.toPath(), scriptContent);
                    launcherScript.setExecutable(true);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create launcher script", e);
                }

                // Generate provider.json
                var manifestFile = new File(buildDir, "install/" + name + "-min/provider.json");
                var manifestContent = String.format("""
                    {
                        "name": "%s",
                        "version": "%s",
                        "protocolVersion": %d,
                        "executable": "bin/provider"
                    }
                    """, name, project.getVersion(), protocolVersion);

                try {
                    Files.writeString(manifestFile.toPath(), manifestContent);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to write provider.json", e);
                }
            });
        });
    }

    /**
     * Auto-detect the main class by scanning source files for a class extending ProviderServer.
     * Looks for "extends ProviderServer" pattern in Java source files.
     */
    private String readMainClassFromManifest(Project project) {
        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        var mainSourceSet = sourceSets.getByName("main");

        for (File srcDir : mainSourceSet.getJava().getSrcDirs()) {
            if (!srcDir.exists()) continue;

            try {
                var result = scanForProviderServer(srcDir.toPath(), srcDir.toPath());
                if (result != null) {
                    project.getLogger().lifecycle("Auto-detected provider main class: " + result);
                    return result;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan source files", e);
            }
        }

        throw new IllegalStateException(
                "Could not auto-detect mainClass. Either set kiteProvider.mainClass explicitly, " +
                "or ensure your provider class extends ProviderServer.");
    }

    /**
     * Recursively scan for Java files containing a class extending ProviderServer or KiteProvider.
     */
    private String scanForProviderServer(Path baseDir, Path currentDir) throws IOException {
        try (var stream = Files.list(currentDir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    var result = scanForProviderServer(baseDir, path);
                    if (result != null) return result;
                } else if (path.toString().endsWith(".java")) {
                    var content = Files.readString(path);
                    // Check for both ProviderServer and KiteProvider (which extends ProviderServer)
                    if (content.contains("extends ProviderServer") || content.contains("extends KiteProvider")) {
                        // Extract class name from file path
                        var relativePath = baseDir.relativize(path).toString();
                        var className = relativePath
                                .replace(File.separator, ".")
                                .replace(".java", "");
                        return className;
                    }
                }
            }
        }
        return null;
    }
}
