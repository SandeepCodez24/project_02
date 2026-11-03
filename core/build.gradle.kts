plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.sqldelight)
}

// :core is pure Kotlin (no Android plugin) so its logic can be built and tested
// anywhere. Android consumes the JVM variant; iOS gets native klibs.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.jvm.driver)
        }
    }
}

sqldelight {
    databases {
        create("KharchaDb") {
            packageName.set("com.example.kharcha.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

kotlin.sourceSets.all {
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    languageSettings.optIn("kotlin.time.ExperimentalTime")
}
