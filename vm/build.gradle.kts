plugins {
    id("myagent.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.vm"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
