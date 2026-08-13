import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation(libs.maven.model.builder)
    implementation(libs.maven.artifact)
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")

        // Target build 262 = IntelliJ IDEA 2026.2. Restricted to 262.* on purpose:
        // this plugin is verified only against the exact IDE version pinned above,
        // so opening the range to future majors without a re-verification pass
        // would risk shipping broken behavior. Bump when a new baseline is verified.
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }

        // Sourced from CHANGELOG.md via the org.jetbrains.changelog plugin: for the
        // version being built, prefer a matching [x.y.z] section, else fall back to
        // whatever sits under [Unreleased] (useful before patchChangelog has renamed it).
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(providers.gradleProperty("version").get()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    publishing {
        // Marketplace upload token, generated at https://plugins.jetbrains.com/author/me/tokens.
        // Read from the environment so it never lands in git; `./gradlew publishPlugin` picks it up.
        token = providers.environmentVariable("PUBLISH_TOKEN")

        // A version carrying a pre-release suffix (0.3.0-beta.1, 0.3.0-rc.2) is published to
        // the "eap" channel, which only reaches users who added its repository URL by hand;
        // a plain 0.3.0 goes to the default channel every user of the plugin sees. All
        // suffixes share one channel on purpose - a tester subscribes once, rather than
        // re-subscribing when a beta becomes an rc.
        channels = providers.gradleProperty("version").map { version ->
            listOf(if (version.contains('-')) "eap" else "default")
        }
    }
}

changelog {
    version = providers.gradleProperty("version")
    groups.empty()
}

tasks {
    // The bundled Vue.js plugin throws during Light-test-fixture VFS teardown on this IDE
    // version (its `lib/modules` resource layout doesn't satisfy a "should be lib directory"
    // check the plugin performs on class init - see JetBrains/intellij-platform-gradle-plugin#2070).
    // That logged error gets promoted into a spurious test failure by TestLoggerFactory even
    // when the test's own assertions pass. This project doesn't use Vue, so disable it in the
    // test sandbox rather than weakening TestLoggerFactory's general logged-error-fails-test
    // behavior, which we want to keep as a safety net for our own bugs.
    named<PrepareSandboxTask>("prepareTestSandbox") {
        disabledPlugins.add("org.jetbrains.plugins.vue")
    }
}
