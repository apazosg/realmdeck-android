import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.JavaExec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val contractSource = providers.gradleProperty("contractSource")
    .orElse(rootProject.file("../forever/contract.json").absolutePath)
val generatedContractDir = layout.buildDirectory.dir("generated/contract/assets")

// Versión inyectable por CI (`.github/workflows/release.yml`). Sin propiedades se
// conserva la versión de desarrollo local.
val appVersionName: String = providers.gradleProperty("appVersionName")
    .orElse("0.1.1-diagnostic").get()
val appVersionCode: Int = providers.gradleProperty("appVersionCode")
    .map { it.toInt() }.orElse(2).get()

// Firma de release opcional: solo si se aporta un keystore (secrets de CI). Sin
// ella `assembleRelease` sigue produciendo un APK sin firmar.
val releaseStoreFile: String? = providers.gradleProperty("releaseStoreFile").orNull

val generateContractAsset = tasks.register<Sync>("generateContractAsset") {
    description = "Genera el asset contract.json desde el contrato canónico."
    from(contractSource.map { file(it) })
    into(generatedContractDir)
    rename { "contract.json" }
    inputs.file(contractSource)
    outputs.file(generatedContractDir.map { it.file("contract.json") })
}

android {
    namespace = "com.wowforever.launcher"
    compileSdk = 35
    // Emparejado con build-tools 35.0.0 y con compileSdk 35:
    // r27 es el primer NDK estable con cabeceras finales de android-35.
    // Instalado vía: sdkmanager "ndk;27.0.12077973"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.wowforever.launcher"
        minSdk = 26
        // targetSdk 28 NO es un descuido: desde API 29 Android niega a las apps la
        // ejecución (execve) de ficheros escritos en su directorio de datos, y el
        // runtime necesita ejecutar Wine/Box64 desde el imagefs. Medido en el
        // emulador (Android 15): con 28 exec OK en filesDir/codeCacheDir; con 35,
        // EACCES. Es la misma razón por la que Winlator/FrontMage usan 28.
        // Ver docs/fase-5-launcher.md.
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            // x86_64 = emulador (Fase 5, puntos 1-3); arm64-v8a = móvil real.
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    sourceSets["main"].assets.srcDir(generatedContractDir)

    // Keep JNI libraries extracted into nativeLibraryDir. This makes ExecProbe
    // able to exercise C2 on Android 15 without changing targetSdk or runtime
    // execution policy. It does not grant storage permissions or weaken SELinux.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("releaseStorePassword").get()
                keyAlias = providers.gradleProperty("releaseKeyAlias").get()
                keyPassword = providers.gradleProperty("releaseKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sin keystore el APK de release queda sin firmar; CI siempre lo aporta.
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    lint {
        // targetSdk 28 es deliberado (ver defaultConfig y docs/fase-5-launcher.md):
        // el requisito de Google Play no aplica a una distribución manual por APK.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") {
    dependsOn(generateContractAsset)
}

// Pruebas puras de parser/políticas, sin JUnit ni dependencias nuevas.
tasks.register<JavaExec>("runDiagnosticLogicSelfTest") {
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("com.wowforever.launcher.DiagnosticLogicSelfTest")
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        configurations.getByName("debugUnitTestRuntimeClasspath"),
    )
}
