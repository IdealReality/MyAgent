pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        maven("https://mirrors.cloud.tencent.com/nexus/repository/jitpack/")
        // 腾讯的 jitpack 镜像缺 com.github.rikkahub:* 的部分 artifact
        // （hugeicons-compose-1.4.aar 等，实测 404），镜像失败时回落到官方源。
        maven("https://jitpack.io")
    }
}

rootProject.name = "myagent"
include(":app")
include(":highlight")
include(":ai")
include(":local-llm")
include(":llama-cpp")
include(":search")
include(":speech")
include(":common")
include(":document")
include(":web")
include(":material3")
include(":workspace")
include(":vm")
include(":oauth")
