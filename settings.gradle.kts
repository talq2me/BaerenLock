pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "BaerenLock"
include(":app")
include(":BaerenSettingsProvider")
project(":BaerenSettingsProvider").projectDir = File(settingsDir, "../BaerenSettingsProvider")
