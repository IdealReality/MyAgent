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
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            // 腾讯镜像对 jitpack 组只镜像了 POM、缺 AAR（实测 hugeicons-compose-1.4.aar 404）。
            // Gradle 一旦在该仓库命中 POM 就锁定它，不会再查 jitpack.io 取 AAR，
            // 于是新环境（CI/新机器）必然失败。把这两个组排除出去，交给下面的 jitpack 源。
            content {
                excludeGroupByRegex("com\\.github\\.(rikkahub|yalantis).*")
            }
        }
        google()
        mavenCentral()
        // 腾讯的 jitpack 镜像同样只有 POM 没有 AAR（实测 jitpack/hugeicons-compose-1.4.aar 404），
        // 留在这里会重演同样的仓库粘性，所以只挂官方源。
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.(rikkahub|yalantis).*") }
        }
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
