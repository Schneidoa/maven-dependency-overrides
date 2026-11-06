import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

// The newest IntelliJ IDEA release the test suite is run against, in addition to the
// 2026.2.1 compile baseline below. Bump both together when JetBrains ships a new major:
// the test framework artifact is published under the build number, not the marketing
// version.
val LATEST_VERIFIED_IDEA = "2026.3"
val LATEST_VERIFIED_IDEA_BUILD = "263.7307.200"

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// The default mode (ENABLE) makes every class implementing a Kotlin platform interface carry a
// generated bridge for each inherited default method. Those bridges override and call members of
// ToolWindowFactory this plugin never touches (isApplicable, isDoNotActivateOnStart, manage,
// getIcon, getAnchor), which the Marketplace verifier reports as deprecated and experimental API
// usages. Nothing here compiles against this plugin's classes, so the compatibility layer has no
// consumer to serve.
kotlin {
    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
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

        // Build 262 = IntelliJ IDEA 2026.2, the version compiled against above and the
        // oldest one supported. No upper bound on purpose: a plugin capped at the current
        // major disappears for every user on the day a new IDE ships. Compatibility with
        // newer builds is checked instead of assumed - verifyPlugin covers every released
        // IDE from 262 on (see pluginVerification below) and `check` runs the whole test
        // suite against the newest supported release as well (testIdeLatest).
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
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

    pluginVerification {
        ides {
            // The compile baseline and the newest release are named explicitly so they're
            // verified even before JetBrains' recommended set catches up; recommended()
            // then adds every IDE release within sinceBuild.. - which, with no untilBuild,
            // grows by itself as new versions ship, so CI notices a break in a future IDE
            // without anyone editing this file.
            create(IntelliJPlatformType.IntellijIdea, "2026.2.1")
            create(IntelliJPlatformType.IntellijIdea, LATEST_VERIFIED_IDEA)
            recommended()
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

// The same test suite, run against the newest IDE release instead of the 2026.2.1 baseline
// the plugin compiles against. Compiling against the oldest supported version keeps the
// plugin from using API that doesn't exist there; this task catches behavior that changed
// in the newer one, which neither the compiler nor the Plugin Verifier can see.
val testIdeLatest by intellijPlatformTesting.testIde.registering {
    type = IntelliJPlatformType.IntellijIdea
    version = LATEST_VERIFIED_IDEA
    task {
        // A custom test task gets the compiled test classes but not processed test
        // resources, so every fixture-backed test would fail to find local-repo/.
        classpath += sourceSets.test.get().output.resourcesDir.let { files(it) }
        dependsOn(tasks.processTestResources)
    }
    prepareSandboxTask {
        // Same reason as for the regular test sandbox below.
        disabledPlugins.add("org.jetbrains.plugins.vue")
    }
    plugins {
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.idea.maven")
    }
}

// testIdeLatest needs the test framework matching its own IDE; without it Gradle can't see
// that BasePlatformTestCase subclasses are tests and silently skips them, leaving a green
// run that only covered the platform-free half of the suite. The configuration only exists
// once the task above is registered, hence a second dependencies block down here.
dependencies {
    intellijPlatform {
        testFramework(TestFrameworkType.Platform, LATEST_VERIFIED_IDEA_BUILD, "intellijPlatformTestDependencies_testIdeLatest")
    }
}

tasks.named("check") {
    dependsOn(testIdeLatest)
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
