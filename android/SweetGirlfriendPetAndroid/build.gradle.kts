plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
}

// Keep generated files outside the Chinese-named source path. This avoids a
// Windows/JDK argument-file encoding edge case in forked JVM test workers.
providers.gradleProperty("externalBuildRoot").orNull?.let { externalRoot ->
    val resolvedBuildRoot = rootProject.file(externalRoot)
    subprojects {
        layout.buildDirectory.set(resolvedBuildRoot.resolve(project.name))
    }
}
