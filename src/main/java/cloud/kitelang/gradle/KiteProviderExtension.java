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
 *     docsFormats = 'html,markdown'
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
     * Whether the provider has generated documentation and schemas. When true, the
     * explicit {@code generateProviderDocs} task exists and distributions ship schemas
     * generated under build/. When false, a hand-maintained docs/{version}/schemas
     * directory is shipped if present. Defaults to true.
     */
    public abstract Property<Boolean> getDocsEnabled();

    /**
     * Documentation output formats.
     * Comma-separated list: html, markdown, combined-markdown, schemas
     * Defaults to "html,markdown,schemas".
     */
    public abstract Property<String> getDocsFormats();

    /**
     * Documentation output directory.
     * Defaults to "docs" (in project root, suitable for git tracking).
     */
    public abstract Property<String> getDocsOutputDir();

    /**
     * URL to the provider's logo image.
     * Used in documentation header. Optional.
     */
    public abstract Property<String> getLogoUrl();
}
