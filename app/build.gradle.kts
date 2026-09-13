import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val legacyMigration = providers.gradleProperty("legacyMigration").orNull == "true"
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseVersionName = appVersion.getProperty("versionName")
val releaseVersionCode = appVersion.getProperty("versionCode").toInt()
val visualChecks = providers.gradleProperty("visualChecks").orNull == "true"
if (visualChecks) apply(plugin = "app.cash.paparazzi")
check(releaseVersionName.matches(Regex("\\d+\\.\\d+\\.\\d+")) && releaseVersionCode > 0)
val releaseStore = providers.environmentVariable("OWLETT_SIGNING_STORE").orNull
val releasePassword = providers.environmentVariable("OWLETT_SIGNING_PASSWORD").orNull

android {
    namespace = "com.example.birdingsoundmvp"
    compileSdk = 35

    defaultConfig {
        applicationId = when {
            legacyMigration -> "com.example.birdingsoundmvp"
            else -> "io.github.greatwyj1.owlett"
        }
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStore != null && releasePassword != null) create("owlettRelease") {
            storeFile = file(releaseStore)
            storePassword = releasePassword
            keyAlias = "owlett"
            keyPassword = releasePassword
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseStore != null && releasePassword != null) signingConfig = signingConfigs.getByName("owlettRelease")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/owlettNotices"))
    providers.gradleProperty("bundledResourcesDir").orNull?.let {
        check(file(it).isDirectory) { "bundledResourcesDir must be an existing verified resource directory" }
        sourceSets.getByName("main").assets.srcDir(file(it))
    }
    if (visualChecks) sourceSets.getByName("test").java.srcDir("src/visualTest/java")
    androidResources {
        noCompress += "tflite"
        noCompress += "db"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyDeliveryMetadata by tasks.registering {
    inputs.files(rootProject.file("version.properties"), rootProject.file("CHANGELOG.md"))
    doLast {
        val changelog = rootProject.file("CHANGELOG.md").readText()
        val entry = changelog.split(Regex("(?m)^## ")).drop(1).firstOrNull { it.startsWith("$releaseVersionName · ") }
        check(entry != null) { "CHANGELOG.md must contain an entry for $releaseVersionName" }
        check("内部版本号：$releaseVersionCode。" in entry) { "CHANGELOG versionCode does not match version.properties" }
    }
}

val generateNotices by tasks.registering {
    val output = layout.buildDirectory.dir("generated/owlettNotices")
    inputs.file(project.file("build.gradle.kts"))
    inputs.files(rootProject.fileTree("licenses"), rootProject.file("LICENSE"), rootProject.file("NOTICE"), rootProject.file("chatui/LICENSE"), rootProject.file("USER_GUIDE.md"), rootProject.file("PRIVACY.md"), rootProject.file("CHANGELOG.md"))
    outputs.dir(output)
    doLast {
        copy {
            from(rootProject.projectDir) { include("LICENSE", "NOTICE", "licenses/**", "chatui/LICENSE", "PRIVACY.md", "USER_GUIDE.md", "CHANGELOG.md") }
            into(output)
        }
        val dependencies = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents
            .map { it.id.displayName }.sorted().joinToString("\n")
        val target = output.get().file("notices/dependencies.txt").asFile
        target.parentFile.mkdirs()
        target.writeText(dependencies)
    }
}
tasks.named("preBuild") { dependsOn(generateNotices, verifyDeliveryMetadata) }

dependencies {
    implementation(project(":chatui"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.aallam.openai:openai-client-bom:4.1.0"))
    implementation("com.aallam.openai:openai-client")
    implementation("io.ktor:ktor-client-okhttp")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}
