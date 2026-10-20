plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号自动递增：CI 用 run number，本地用 git 提交数，避免每次装的都是「1.0」
fun buildNumber(): Int {
    System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { return it }
    return try {
        val p = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootProject.projectDir.parentFile)
            .redirectErrorStream(true)
            .start()
        val n = p.inputStream.bufferedReader().use { it.readText() }.trim().toIntOrNull() ?: 1
        p.waitFor()
        n
    } catch (_: Exception) {
        1
    }
}

val appBuildNumber = buildNumber()

android {
    namespace = "com.gdxs.keepalive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gdxs.keepalive"
        minSdk = 26
        targetSdk = 35
        versionCode = appBuildNumber
        versionName = "1.0.$appBuildNumber"
    }

    signingConfigs {
        // 固定签名：随仓库提交，保证每次构建的 APK 签名一致，能直接覆盖安装
        create("stable") {
            storeFile = file("gdxs.jks")
            storePassword = "gdxs123456"
            keyAlias = "gdxs"
            keyPassword = "gdxs123456"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
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
