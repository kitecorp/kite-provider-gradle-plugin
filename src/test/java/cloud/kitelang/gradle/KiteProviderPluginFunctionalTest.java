package cloud.kitelang.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the plugin against a minimal provider project. The project carries its own
 * {@code DocGeneratorCli} in place of the SDK's: it writes one marker file per requested
 * format, so the tests can see where each task wrote without depending on the SDK.
 */
class KiteProviderPluginFunctionalTest {

    @TempDir
    Path projectDir;

    @BeforeEach
    void writeProviderProject() throws IOException {
        write("settings.gradle", "rootProject.name = 'demo'\n");
        write("build.gradle", """
                plugins {
                    id 'cloud.kitelang.provider'
                }

                version = '1.0.0'

                kiteProvider {
                    name = 'demo'
                    mainClass = 'demo.Main'
                }
                """);
        write("src/main/java/demo/Main.java", """
                package demo;

                public class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        write("src/main/java/cloud/kitelang/provider/docgen/DocGeneratorCli.java", """
                package cloud.kitelang.provider.docgen;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public class DocGeneratorCli {
                    public static void main(String[] args) throws Exception {
                        Path output = null;
                        String provider = null;
                        String version = null;
                        String formats = null;
                        for (int i = 0; i < args.length; i++) {
                            switch (args[i]) {
                                case "--provider" -> provider = args[++i];
                                case "--output" -> output = Path.of(args[++i]);
                                case "--version" -> version = args[++i];
                                case "--format" -> formats = args[++i];
                                default -> { }
                            }
                        }
                        var base = version == null ? output : output.resolve(version);
                        for (var format : formats.split(",")) {
                            var target = switch (format) {
                                case "schemas" -> base.resolve("schemas/demo.kite");
                                case "markdown" -> base.resolve("markdown/demo.md");
                                default -> base.resolve(format + "/index.html");
                            };
                            Files.createDirectories(target.getParent());
                            Files.writeString(target, format + " for " + version + " from " + provider);
                        }
                        // One extra schema per line of extra-types.txt, so a test can remove a resource
                        try (var extra = DocGeneratorCli.class.getResourceAsStream("/extra-types.txt")) {
                            if (extra != null) {
                                for (var type : new String(extra.readAllBytes()).lines().toList()) {
                                    Files.writeString(base.resolve("schemas/" + type + ".kite"), type);
                                }
                            }
                        }
                    }
                }
                """);
    }

    @Test
    void buildDoesNotWriteTheDocsDirectory() {
        run("build");

        assertEquals(false, Files.exists(projectDir.resolve("docs")));
    }

    @Test
    void installDistShipsSchemasGeneratedUnderBuild() throws IOException {
        run("installDist");

        // "for null": the dist's schemas are generated without --version, straight into schemas/
        assertEquals("schemas for null from demo.Main",
                Files.readString(projectDir.resolve("build/install/demo/schemas/demo.kite")));
        assertEquals(false, Files.exists(projectDir.resolve("docs")));
    }

    @Test
    void installMinDistShipsSchemasGeneratedUnderBuild() throws IOException {
        run("installMinDist");

        assertEquals("schemas for null from demo.Main",
                Files.readString(projectDir.resolve("build/install/demo-min/schemas/demo.kite")));
        assertEquals(false, Files.exists(projectDir.resolve("docs")));
    }

    @Test
    void installDistDropsTheSchemaOfARemovedResource() throws IOException {
        write("src/main/resources/extra-types.txt", "Removed\n");
        run("installDist");
        assertEquals("Removed", Files.readString(projectDir.resolve("build/install/demo/schemas/Removed.kite")));

        write("src/main/resources/extra-types.txt", "");
        run("installDist");

        assertEquals(false, Files.exists(projectDir.resolve("build/install/demo/schemas/Removed.kite")));
    }

    @Test
    void withDocsDisabledInstallDistShipsHandMaintainedSchemas() throws IOException {
        write("build.gradle", Files.readString(projectDir.resolve("build.gradle"))
                .replace("mainClass = 'demo.Main'", "mainClass = 'demo.Main'\n    docsEnabled = false"));
        write("docs/1.0.0/schemas/Hand.kite", "hand-written schema");

        run("installDist");

        assertEquals("hand-written schema",
                Files.readString(projectDir.resolve("build/install/demo/schemas/Hand.kite")));
        assertEquals(false, Files.exists(projectDir.resolve("build/generated/kite/provider-schemas")));
    }

    @Test
    void generateProviderDocsWritesVersionedDocsWhenAskedFor() throws IOException {
        run("generateProviderDocs");

        assertEquals("schemas for 1.0.0 from demo.Main", Files.readString(projectDir.resolve("docs/1.0.0/schemas/demo.kite")));
        assertEquals("markdown for 1.0.0 from demo.Main", Files.readString(projectDir.resolve("docs/1.0.0/markdown/demo.md")));
    }

    @Test
    void installDistManifestCarriesTheToolchainAsMinJava() throws IOException {
        useToolchain25();

        run("installDist");

        assertEquals("""
                {
                    "name": "demo",
                    "version": "1.0.0",
                    "protocolVersion": 1,
                    "executable": "bin/provider",
                    "minJava": 25
                }
                """, Files.readString(projectDir.resolve("build/install/demo/provider.json")));
    }

    @Test
    void installMinDistManifestCarriesTheToolchainAsMinJava() throws IOException {
        useToolchain25();

        run("installMinDist");

        assertEquals("""
                {
                    "name": "demo",
                    "version": "1.0.0",
                    "protocolVersion": 1,
                    "executable": "bin/provider",
                    "minJava": 25
                }
                """, Files.readString(projectDir.resolve("build/install/demo-min/provider.json")));
    }

    @Test
    void jarResourceManifestCarriesTheToolchainAsMinJava() throws IOException {
        useToolchain25();
        write("build.gradle", Files.readString(projectDir.resolve("build.gradle"))
                .replace("mainClass = 'demo.Main'", "mainClass = 'demo.Main'\n    logoUrl = 'https://example.com/logo.svg'"));

        run("generateProviderInfo");

        assertEquals("""
                {
                    "name": "demo",
                    "version": "1.0.0",
                    "protocolVersion": 1,
                    "logoUrl": "https://example.com/logo.svg",
                    "minJava": 25
                }
                """, Files.readString(projectDir.resolve("build/generated/resources/kite/META-INF/kite/provider.json")));
    }

    @Test
    void withoutAToolchainMinJavaIsTheJavaTheBuildTargets() throws IOException {
        // 24, not the 25 this test runs on: the value comes from the build, not from the JVM running Gradle
        write("build.gradle", Files.readString(projectDir.resolve("build.gradle")) + """

                java {
                    sourceCompatibility = JavaVersion.VERSION_24
                    targetCompatibility = JavaVersion.VERSION_24
                }
                """);

        run("installDist");

        assertEquals("""
                {
                    "name": "demo",
                    "version": "1.0.0",
                    "protocolVersion": 1,
                    "executable": "bin/provider",
                    "minJava": 24
                }
                """, Files.readString(projectDir.resolve("build/install/demo/provider.json")));
    }

    @Test
    void jarResourceManifestEscapesQuotesSoItStaysValidJson() throws IOException {
        useToolchain25();
        write("build.gradle", Files.readString(projectDir.resolve("build.gradle"))
                .replace("mainClass = 'demo.Main'", "mainClass = 'demo.Main'\n    logoUrl = 'https://example.com/\"logo\".svg'"));

        run("generateProviderInfo");

        assertEquals("""
                {
                    "name": "demo",
                    "version": "1.0.0",
                    "protocolVersion": 1,
                    "logoUrl": "https://example.com/\\"logo\\".svg",
                    "minJava": 25
                }
                """, Files.readString(projectDir.resolve("build/generated/resources/kite/META-INF/kite/provider.json")));
    }

    @Test
    void jarResourceManifestFollowsAChangedJavaTarget() throws IOException {
        write("build.gradle", Files.readString(projectDir.resolve("build.gradle")) + """

                java {
                    sourceCompatibility = JavaVersion.VERSION_24
                    targetCompatibility = JavaVersion.VERSION_24
                }
                """);
        run("generateProviderInfo");
        write("build.gradle", Files.readString(projectDir.resolve("build.gradle")).replace("VERSION_24", "VERSION_23"));

        run("generateProviderInfo");

        assertEquals("""
                {
                    "name": "demo",
                    "version": "1.0.0",
                    "protocolVersion": 1,
                    "minJava": 23
                }
                """, Files.readString(projectDir.resolve("build/generated/resources/kite/META-INF/kite/provider.json")));
    }

    @Test
    void installMinDistLauncherRunsTheJavaFromJavaHome() throws Exception {
        run("installMinDist");
        var javaHome = fakeJava("java-home/bin", "java from JAVA_HOME");
        fakeJava("path-bin", "java from PATH");

        var output = runLauncher(Map.of("JAVA_HOME", javaHome.toString(),
                "PATH", projectDir.resolve("path-bin") + ":/usr/bin:/bin"));

        assertEquals("java from JAVA_HOME\n", output);
    }

    @Test
    void installMinDistLauncherFallsBackToTheJavaOnPath() throws Exception {
        run("installMinDist");
        fakeJava("path-bin", "java from PATH");

        var output = runLauncher(Map.of("JAVA_HOME", "",
                "PATH", projectDir.resolve("path-bin") + ":/usr/bin:/bin"));

        assertEquals("java from PATH\n", output);
    }

    private void useToolchain25() throws IOException {
        write("build.gradle", Files.readString(projectDir.resolve("build.gradle")) + """

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(25)
                    }
                }
                """);
    }

    /**
     * Writes {@code <dir>/java}, a script that prints {@code message}, and returns the
     * directory above {@code dir}: for "java-home/bin" that is the JAVA_HOME to set.
     */
    private Path fakeJava(String dir, String message) throws IOException {
        var java = projectDir.resolve(dir).resolve("java");
        write(projectDir.relativize(java).toString(), "#!/bin/sh\necho '" + message + "'\n");
        assertTrue(java.toFile().setExecutable(true));
        return java.getParent().getParent();
    }

    /** Runs the minimized distribution's launcher with exactly {@code env} and returns its output. */
    private String runLauncher(Map<String, String> env) throws Exception {
        var builder = new ProcessBuilder(projectDir.resolve("build/install/demo-min/bin/provider").toString())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(env);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return output;
    }

    private BuildResult run(String task) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(task, "--stacktrace")
                .build();
    }

    private void write(String relativePath, String content) throws IOException {
        var file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent() == null ? projectDir : file.getParent());
        Files.writeString(file, content);
    }
}
