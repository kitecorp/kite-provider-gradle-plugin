# Publish to Maven Local After Changes

## When to Apply
Always apply after modifying any source files in this project (Java files, build.gradle, resources).

## Rule
After making changes to the kite-provider-gradle-plugin project, always run:

```bash
./gradlew publishToMavenLocal
```

This ensures that:
1. Other local projects using `mavenLocal()` get the latest plugin version
2. The kite-providers projects can immediately use any plugin changes
3. Local testing always reflects current code

## Workflow

1. Make code changes
2. Run `./gradlew publishToMavenLocal`
3. Verify the build succeeds
4. Then commit the changes

## Notes

- The plugin version in `build.gradle` determines what version is published
- Published to `~/.m2/repository/cloud/kitelang/kite-provider-gradle-plugin/`
- Consumer projects need `mavenLocal()` in their `pluginManagement.repositories`