plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

android {
    namespace = "com.example.companyinfo"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.companyinfo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Apache POI 의존성으로 메서드 수 65,536 초과 → 멀티덱스 필수
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    // Apache POI 빌드 시 중복 메타파일 충돌 방지 (필수)
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/module-info.class",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

// xmlbeans 버전 강제 통일 (충돌 방지)
configurations.all {
    resolutionStrategy {
        force("org.apache.xmlbeans:xmlbeans:5.1.1")
    }
}

dependencies {
    // ── 기존 의존성 (변경 없음) ──────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ── 멀티덱스 (Apache POI 사용으로 인해 추가) ─────────────────────────────
    implementation("androidx.multidex:multidex:2.0.1")

    // ── Apache POI (xlsx 읽기) ───────────────────────────────────────────────
    implementation("org.apache.poi:poi-ooxml:5.2.3") {
        exclude(group = "org.apache.xmlbeans",    module = "xmlbeans")
        exclude(group = "com.fasterxml.woodstox", module = "woodstox-core")
        exclude(group = "org.codehaus.woodstox",  module = "stax2-api")
        exclude(group = "com.github.virtuald",     module = "curvesapi")
        exclude(group = "org.apache.commons",      module = "commons-math3")
        exclude(group = "com.zaxxer",              module = "SparseBitSet")
    }
    implementation("org.apache.xmlbeans:xmlbeans:5.1.1") {
        exclude(group = "net.sf.saxon", module = "Saxon-HE")
    }
    implementation("javax.xml.stream:stax-api:1.0-2")
    implementation("com.fasterxml.woodstox:woodstox-core:6.5.1") {
        exclude(group = "org.codehaus.woodstox", module = "stax2-api")
    }
    implementation("org.codehaus.woodstox:stax2-api:4.2.1")
    implementation("org.jsoup:jsoup:1.18.1")
}
