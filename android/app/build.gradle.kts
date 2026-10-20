plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gdxs.keepalive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gdxs.keepalive"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}

// 把仓库根目录的 index.html 同步进 assets，保持一份源码
val syncWeb by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("index.html"))
    into(layout.projectDirectory.dir("src/main/assets/www"))
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(syncWeb)
}
