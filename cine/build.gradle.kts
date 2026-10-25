import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Clé de signature : keystore.properties + selvania.keystore à la racine (jamais dans git ;
// en intégration continue, ils sont recréés à partir des secrets du dépôt). Sans eux, l'APK de
// version n'est pas signé : utiliser alors assembleDebug.
val signingFile = rootProject.file("keystore.properties")
val signing = Properties().apply {
    if (signingFile.exists()) signingFile.inputStream().use { load(it) }
}
val canSign = signingFile.exists() && rootProject.file("selvania.keystore").exists()

android {
    namespace = "fr.selvania.cine"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.selvania.cine"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "2.0.3"
        // Dépôt des mises à jour (gradle.properties : updateFeed) ; vide = désactivé.
        buildConfigField("String", "UPDATE_FEED", "\"${providers.gradleProperty("updateFeed").getOrElse("")}\"")
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = rootProject.file("selvania.keystore")
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Interface Ciné intégrée à l'appli (version « appli » : serveur Jellyfin au choix).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/cine-ui"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Copie l'interface construite par « npm run build:app » dans cine-web
// (dossier voisin par défaut, ou -PcineUi=/chemin/vers/dist-app).
val cineUi = file(providers.gradleProperty("cineUi").getOrElse(rootProject.file("../cine-web/dist-app").path))
val syncCineUi by tasks.registering(Sync::class) {
    from(cineUi) {
        exclude("apps/**")
    }
    into(layout.buildDirectory.dir("generated/cine-ui/cine"))
    doFirst {
        check(cineUi.resolve("index.html").exists()) {
            "Interface absente : lancez « npm run build:app » dans cine-web"
        }
    }
}
tasks.named("preBuild") { dependsOn(syncCineUi) }

val media3 = "1.8.0"

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.1.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
