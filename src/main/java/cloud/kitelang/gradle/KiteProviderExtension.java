package cloud.kitelang.gradle;

import org.gradle.api.provider.Property;

/**
 * Extension for configuring Kite provider builds.
 * <p>
 * Usage in build.gradle:
 * <pre>
 * kiteProvider {
 *     name = 'aws'
 *     mainClass = 'cloud.kitelang.provider.aws.AwsProvider'
 *     docs {
 *         enabled = true
 *         formats = 'html,markdown'
 *     }
 * }
 * </pre>
 */
public abstract class KiteProviderExtension {

    /**
     * The provider name (e.g., "aws", "gcp", "azure").
     * Used in the provider.json manifest.
     */
    public abstract Property<String> getName();

    /**
     * The fully qualified main class name for the provider.
     * This class should extend ProviderServer from the SDK.
     */
    public abstract Property<String> getMainClass();

    /**
     * The protocol version for provider communication.
     * Defaults to 1.
     */
    public abstract Property<Integer> getProtocolVersion();

    /**
     * The SDK version to use.
     * Defaults to "0.1.0".
     */
    public abstract Property<String> getSdkVersion();

    /**
     * Whether to generate documentation during build.
     * Defaults to true.
     */
    public abstract Property<Boolean> getDocsEnabled();

    /**
     * Documentation output formats.
     * Comma-separated list: html, markdown, combined-markdown, kite
     * Defaults to "html,markdown,kite".
     */
    public abstract Property<String> getDocsFormats();

    /**
     * Documentation output directory.
     * Defaults to "build/docs/provider".
     */
    public abstract Property<String> getDocsOutputDir();
}
