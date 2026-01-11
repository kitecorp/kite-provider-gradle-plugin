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
        extension.getSdkVersion().convention("0.2.1");
        extension.getDocsEnabled().convention(true);
        extension.getDocsFormats().convention("html,markdown,schemas");
        extension.getDocsOutputDir().convention("docs");

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
                var logoUrl = extension.getLogoUrl().getOrNull();
                var logoLine = logoUrl != null ? ",\n        \"logoUrl\": \"" + logoUrl + "\"" : "";
                var content = String.format("""
                    {
                        "name": "%s",
                        "version": "%s",
                        "protocolVersion": %d%s
                    }
                    """, name, version, protocolVersion, logoLine);

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

        // Store docs enabled status for later use
        var docsEnabled = extension.getDocsEnabled().get();
        var docsOutputDir = extension.getDocsOutputDir().get();

        // Wire up installDist to generate manifest and include schemas
        installDistTask.configure(task -> {
            task.finalizedBy("generateProviderManifest");

            // Include schemas from docs/{version}/schemas/ if available
            var schemasDir = project.file(docsOutputDir + "/" + version + "/schemas");
            task.from(schemasDir, spec -> {
                spec.into("schemas");
            });
        });

        // Register minimized distribution task
        var installMinDistTask = project.getTasks().register("installMinDist", Copy.class, task -> {
            var shadowJarTask = project.getTasks().named("shadowJar", ShadowJar.class);
            task.dependsOn(shadowJarTask);

            task.from(shadowJarTask.map(ShadowJar::getArchiveFile), spec -> {
                spec.into("lib");
            });

            // Include schemas from docs/{version}/schemas/ if available
            var schemasDir = project.file(docsOutputDir + "/" + version + "/schemas");
            task.from(schemasDir, spec -> {
                spec.into("schemas");
            });
            // Handle case where schemas don't exist - task will just skip them
            task.setDuplicatesStrategy(org.gradle.api.file.DuplicatesStrategy.INCLUDE);

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

        // Register global installation task - installs to ~/.kite/providers/
        project.getTasks().register("installGlobal", Copy.class, task -> {
            task.setGroup("distribution");
            task.setDescription("Installs the provider globally to ~/.kite/providers/");
            task.dependsOn(installDistTask);
            task.dependsOn("generateProviderManifest");

            var distDir = installDistTask.get().getDestinationDir();
            task.from(distDir);

            // Version directory format: {name}-v{version}
            var versionDir = name + "-v" + version;
            var globalProviderPath = System.getProperty("user.home") + "/.kite/providers/" + name + "/" + versionDir;
            task.into(globalProviderPath);

            task.doLast(t -> {
                // Update 'current' symlink to point to this version
                updateCurrentSymlink(project, name, versionDir);
                project.getLogger().lifecycle("Provider installed to: " + globalProviderPath);
            });
        });

        // Register documentation generation task
        if (extension.getDocsEnabled().get()) {
            project.getTasks().register("generateProviderDocs", org.gradle.api.tasks.JavaExec.class, task -> {
                task.setGroup("documentation");
                task.setDescription("Generates HTML and Markdown documentation for provider resources");

                // Depend on classes compilation
                task.dependsOn("classes");

                // Use the runtime classpath
                task.setClasspath(sourceSets.getByName("main").getRuntimeClasspath());
                task.getMainClass().set("cloud.kitelang.provider.docgen.DocGeneratorCli");

                // Pass arguments - versioned mode with base output directory
                // CLI will generate non-versioned index.html at docs root
                // and resource pages at docs/{version}/
                var baseOutputDir = extension.getDocsOutputDir().get();
                var formats = extension.getDocsFormats().get();

                task.args(
                    "--provider", mainClassProvider.get(),
                    "--output", project.file(baseOutputDir).getAbsolutePath(),
                    "--version", version,
                    "--format", formats
                );

                task.doFirst(t -> {
                    project.getLogger().lifecycle("Generating provider documentation for version " + version + "...");
                });

                // After docs generated, update versions.json and generate changelog
                task.doLast(t -> {
                    updateVersionsJson(project, baseOutputDir, name, version);
                    generateChangelog(project, baseOutputDir, version);
                });
            });

            // Wire up to build lifecycle - generate docs after build
            project.getTasks().named("build").configure(task -> {
                task.finalizedBy("generateProviderDocs");
            });

            // Make installMinDist depend on generateProviderDocs so schemas exist
            installMinDistTask.configure(task -> {
                task.dependsOn("generateProviderDocs");
            });

            // Make installDist depend on generateProviderDocs so schemas exist
            installDistTask.configure(task -> {
                task.dependsOn("generateProviderDocs");
            });
        }
    }

    /**
     * Updates or creates versions.json at the root docs folder.
     * Maintains a list of all generated documentation versions.
     */
    private void updateVersionsJson(Project project, String baseOutputDir, String providerName, String version) {
        var docsDir = project.file(baseOutputDir);
        var versionsFile = new File(docsDir, "versions.json");
        var today = java.time.LocalDate.now().toString();

        try {
            // Simple JSON handling without external dependencies
            StringBuilder versions = new StringBuilder();
            boolean versionExists = false;

            if (versionsFile.exists()) {
                var content = Files.readString(versionsFile.toPath());
                // Check if this version already exists
                if (content.contains("\"" + version + "\"")) {
                    versionExists = true;
                    // Update the date for existing version
                    content = content.replaceAll(
                        "(\"version\":\\s*\"" + version + "\"[^}]*\"date\":\\s*\")[^\"]*\"",
                        "$1" + today + "\""
                    );
                    Files.writeString(versionsFile.toPath(), content);
                    project.getLogger().lifecycle("Updated version " + version + " in versions.json");
                    return;
                }

                // Extract existing versions array content
                int arrayStart = content.indexOf('[');
                int arrayEnd = content.lastIndexOf(']');
                if (arrayStart >= 0 && arrayEnd > arrayStart) {
                    versions.append(content.substring(arrayStart + 1, arrayEnd).trim());
                    if (!versions.isEmpty() && !versions.toString().isBlank()) {
                        versions.append(",\n        ");
                    }
                }
            }

            // Add new version entry
            versions.append(String.format("""
                {
                            "version": "%s",
                            "path": "%s",
                            "date": "%s"
                        }""", version, version, today));

            // Write complete JSON
            var json = String.format("""
                {
                    "provider": "%s",
                    "latest": "%s",
                    "versions": [
                        %s
                    ]
                }
                """, providerName, version, versions.toString());

            docsDir.mkdirs();
            Files.writeString(versionsFile.toPath(), json);
            project.getLogger().lifecycle("Added version " + version + " to versions.json");

        } catch (IOException e) {
            project.getLogger().warn("Failed to update versions.json: " + e.getMessage());
        }
    }

    /**
     * Generates a changelog by comparing current version manifest with previous version.
     */
    private void generateChangelog(Project project, String baseOutputDir, String currentVersion) {
        var docsDir = project.file(baseOutputDir);
        var versionsFile = new File(docsDir, "versions.json");

        if (!versionsFile.exists()) return;

        try {
            var versionsContent = Files.readString(versionsFile.toPath());

            // Find previous version (simple parsing)
            var versions = new java.util.ArrayList<String>();
            var matcher = java.util.regex.Pattern.compile("\"version\":\\s*\"([^\"]+)\"").matcher(versionsContent);
            while (matcher.find()) {
                versions.add(matcher.group(1));
            }

            // Sort versions and find previous
            versions.sort((a, b) -> b.compareTo(a)); // descending
            String previousVersion = null;
            for (int i = 0; i < versions.size(); i++) {
                if (versions.get(i).equals(currentVersion) && i + 1 < versions.size()) {
                    previousVersion = versions.get(i + 1);
                    break;
                }
            }

            if (previousVersion == null) {
                project.getLogger().lifecycle("No previous version found for changelog");
                return;
            }

            // Read manifests (versioned structure: {version}/manifest.json)
            var currentManifest = new File(docsDir, currentVersion + "/manifest.json");
            var previousManifest = new File(docsDir, previousVersion + "/manifest.json");

            if (!currentManifest.exists() || !previousManifest.exists()) {
                project.getLogger().lifecycle("Manifest files not found for changelog generation");
                return;
            }

            var currentContent = Files.readString(currentManifest.toPath());
            var previousContent = Files.readString(previousManifest.toPath());

            // Generate changelog by comparing manifests
            var changelog = compareManifests(previousContent, currentContent, previousVersion, currentVersion);
            var changelogFile = new File(docsDir, currentVersion + "/changelog.json");
            Files.writeString(changelogFile.toPath(), changelog);

            project.getLogger().lifecycle("Generated changelog comparing " + previousVersion + " to " + currentVersion);

        } catch (IOException e) {
            project.getLogger().warn("Failed to generate changelog: " + e.getMessage());
        }
    }

    /**
     * Compares two manifest JSON strings and returns a changelog JSON.
     */
    private String compareManifests(String oldManifest, String newManifest, String oldVersion, String newVersion) {
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"fromVersion\": \"").append(oldVersion).append("\",\n");
        sb.append("  \"toVersion\": \"").append(newVersion).append("\",\n");
        sb.append("  \"generatedAt\": \"").append(java.time.LocalDate.now()).append("\",\n");

        // Extract resources from both manifests (simple regex parsing)
        var oldResources = extractResources(oldManifest);
        var newResources = extractResources(newManifest);

        // Find added resources
        var addedResources = new java.util.ArrayList<String>();
        for (String res : newResources.keySet()) {
            if (!oldResources.containsKey(res)) {
                addedResources.add(res);
            }
        }

        // Find removed resources
        var removedResources = new java.util.ArrayList<String>();
        for (String res : oldResources.keySet()) {
            if (!newResources.containsKey(res)) {
                removedResources.add(res);
            }
        }

        // Find changed resources (property changes)
        var changedResources = new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
        for (String res : newResources.keySet()) {
            if (oldResources.containsKey(res)) {
                var oldProps = oldResources.get(res);
                var newProps = newResources.get(res);

                var addedProps = new java.util.ArrayList<String>();
                var removedProps = new java.util.ArrayList<String>();

                for (String prop : newProps) {
                    if (!oldProps.contains(prop)) addedProps.add(prop);
                }
                for (String prop : oldProps) {
                    if (!newProps.contains(prop)) removedProps.add(prop);
                }

                if (!addedProps.isEmpty() || !removedProps.isEmpty()) {
                    var changes = new java.util.LinkedHashMap<String, Object>();
                    if (!addedProps.isEmpty()) changes.put("addedProperties", addedProps);
                    if (!removedProps.isEmpty()) changes.put("removedProperties", removedProps);
                    changedResources.put(res, changes);
                }
            }
        }

        // Build JSON output
        sb.append("  \"addedResources\": [");
        sb.append(addedResources.stream().map(r -> "\"" + r + "\"").reduce((a, b) -> a + ", " + b).orElse(""));
        sb.append("],\n");

        sb.append("  \"removedResources\": [");
        sb.append(removedResources.stream().map(r -> "\"" + r + "\"").reduce((a, b) -> a + ", " + b).orElse(""));
        sb.append("],\n");

        sb.append("  \"changedResources\": {\n");
        var entries = new java.util.ArrayList<>(changedResources.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            sb.append("    \"").append(entry.getKey()).append("\": {\n");
            var changes = entry.getValue();
            var changeEntries = new java.util.ArrayList<>(changes.entrySet());
            for (int j = 0; j < changeEntries.size(); j++) {
                var ce = changeEntries.get(j);
                sb.append("      \"").append(ce.getKey()).append("\": [");
                @SuppressWarnings("unchecked")
                var props = (java.util.List<String>) ce.getValue();
                sb.append(props.stream().map(p -> "\"" + p + "\"").reduce((a, b) -> a + ", " + b).orElse(""));
                sb.append("]");
                if (j < changeEntries.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("    }");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  }\n");
        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Extracts resource names and their properties from a manifest JSON string.
     * Uses brace counting to handle nested JSON properly.
     */
    private java.util.Map<String, java.util.Set<String>> extractResources(String manifest) {
        var resources = new java.util.LinkedHashMap<String, java.util.Set<String>>();

        // Find "resources": { and extract the block
        int resourcesStart = manifest.indexOf("\"resources\":");
        if (resourcesStart == -1) return resources;

        int resourcesBlockStart = manifest.indexOf('{', resourcesStart);
        if (resourcesBlockStart == -1) return resources;

        // Extract the entire resources block
        String resourcesBlock = extractJsonBlock(manifest, resourcesBlockStart);

        // Find each resource within the resources block
        // Pattern: "ResourceName": { followed by "domain"
        var resourceNamePattern = java.util.regex.Pattern.compile("\"(\\w+)\":\\s*\\{");
        var matcher = resourceNamePattern.matcher(resourcesBlock);

        while (matcher.find()) {
            var resourceName = matcher.group(1);
            // Skip if this is a property name like "domain", "type", "properties", etc.
            if (resourceName.equals("domain") || resourceName.equals("properties") ||
                resourceName.equals("type") || resourceName.equals("required") ||
                resourceName.equals("cloudManaged") || resourceName.equals("deprecated") ||
                resourceName.equals("default") || resourceName.equals("description") ||
                resourceName.equals("validValues") || resourceName.equals("importable")) {
                continue;
            }

            int resourceBlockStart = matcher.end() - 1; // Position of '{'
            String resourceBlock = extractJsonBlock(resourcesBlock, resourceBlockStart);

            // Find the properties block within this resource
            int propsIndex = resourceBlock.indexOf("\"properties\":");
            if (propsIndex == -1) continue;

            int propsBlockStart = resourceBlock.indexOf('{', propsIndex);
            if (propsBlockStart == -1) continue;

            String propsBlock = extractJsonBlock(resourceBlock, propsBlockStart);

            // Extract property names from the properties block
            var props = new java.util.HashSet<String>();
            var propPattern = java.util.regex.Pattern.compile("\"(\\w+)\":\\s*\\{");
            var propMatcher = propPattern.matcher(propsBlock);
            while (propMatcher.find()) {
                var propName = propMatcher.group(1);
                // Skip metadata field names
                if (!propName.equals("type") && !propName.equals("required") &&
                    !propName.equals("cloudManaged") && !propName.equals("deprecated") &&
                    !propName.equals("default") && !propName.equals("description") &&
                    !propName.equals("validValues") && !propName.equals("importable")) {
                    props.add(propName);
                }
            }

            if (!props.isEmpty()) {
                resources.put(resourceName, props);
            }
        }

        return resources;
    }

    /**
     * Extracts a JSON block starting at the given brace position using brace counting.
     */
    private String extractJsonBlock(String json, int startBrace) {
        int depth = 0;
        int end = startBrace;
        for (int i = startBrace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        return json.substring(startBrace, end);
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
     * Updates the 'current' symlink in the global providers directory.
     * Points to the specified version directory.
     */
    private void updateCurrentSymlink(Project project, String providerName, String versionDir) {
        var providersPath = Path.of(System.getProperty("user.home"), ".kite", "providers", providerName);
        var currentLink = providersPath.resolve("current");

        try {
            Files.deleteIfExists(currentLink);

            // Try symlink first
            try {
                Files.createSymbolicLink(currentLink, Path.of(versionDir));
                project.getLogger().lifecycle("Updated current symlink: " + currentLink + " -> " + versionDir);
            } catch (IOException | UnsupportedOperationException e) {
                // Fallback: write version to text file (Windows without Developer Mode)
                Files.writeString(currentLink, versionDir);
                project.getLogger().lifecycle("Updated current file: " + currentLink + " -> " + versionDir);
            }
        } catch (IOException e) {
            project.getLogger().warn("Failed to update current pointer: " + e.getMessage());
        }
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
