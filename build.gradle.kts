import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URL

// val resolvableConfiguration = configurations.resolvable("resolvable") {
//     // 用于新的不打包配置:
//     // attributes.attribute(KlibPackaging.ATTRIBUTE, project.objects.named(KlibPackaging.NON_PACKED))
//
//     // 用于之前的打包配置:
//     attributes.attribute(KlibPackaging.ATTRIBUTE, project.objects.named(KlibPackaging.PACKED))
// }

object Libs {

    // ktor HTTP library
    const val ktorVersion = "2.3.13" //由2.3.1升级到2.3.7则linuxArm64平台可以正常编译

    // XmlUtil library
    const val xmlVersion = "0.86.0"

    const val kotestVersion = "5.6.2"

    const val klockVersion = "4.0.3"

    const val coroutinesDebugVersion = "1.7.1"

    const val logbackVersion = "1.4.8"

}

repositories {
    mavenCentral()
}

group="com.github.bitfireAT"
version="2.2-mpp"

plugins {
    kotlin("multiplatform") version "1.9.20"
    id("io.kotest.multiplatform") version "5.6.2"
    `maven-publish`

    id("org.jetbrains.dokka") version "1.8.10"
}

publishing {

    repositories {
        maven {
            name = "dav4jvm"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}

tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets {
        named("commonMain") {
            moduleName.set("dav4jvm")
            sourceLink {
                localDirectory.set(file("src/commonMain/kotlin"))
                remoteUrl.set(URL("https://github.com/bitfireAT/dav4jvm/tree/main/src/main/kotlin/"))
                remoteLineSuffix.set("#L")
            }
        }
    }
}


kotlin {





    // 修复点：统一配置 Native 二进制文件名
    // targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
    //     binaries.configureEach {
    //
    //         baseName = "dav4jvm-${target.konanTarget.name}-${buildType.name.lowercase()}"
    //     }
    // }




    jvm()
    js(IR) {
        browser()
        nodejs()
    }

    linuxArm64() {
        binaries {
            sharedLib {
                baseName = "yicheng-webdav" // 生成动态库 .so 文件
            }
        }
    }
    linuxX64()

    // 解决去除依赖libgcc_s.so.1
    // val linuxTargets = listOf(linuxArm64(), linuxX64())
    // linuxTargets.forEach {
    //     it.binaries {
    //         executable {
    //             this.compilation.compileTaskProvider.configure {
    //                 this.compilerOptions.freeCompilerArgs.addAll(
    //                     listOf(
    //                         "-Xoverride-konan-properties=linkerGccFlags=-lgcc",
    //                         "-linker-options", "-as-needed",
    //                     )
    //                 )
    //             }
    //         }
    //         sharedLib {
    //             //生成 libkn.so
    //             baseName = "kn2"
    //         }
    //     }
    // }

    mingwX64() {

    }

    sourceSets {

        // val linuxArm64 by getting {
        //     dependencies {
        //         implementation("io.ktor:ktor-client-curl:${Libs.ktorVersion}") // 使用与核心库相同的版本
        //     }
        // }

        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                api("io.ktor:ktor-client-core:${Libs.ktorVersion}")
                // api("io.ktor:ktor-client-cio:${Libs.ktorVersion}")
                api("io.ktor:ktor-client-auth:${Libs.ktorVersion}")
                implementation("io.github.pdvrieze.xmlutil:core:${Libs.xmlVersion}")
                implementation("com.soywiz.korlibs.klock:klock:${Libs.klockVersion}")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.kotest:kotest-framework-engine:${Libs.kotestVersion}")
                implementation("io.kotest:kotest-framework-datatest:${Libs.kotestVersion}")
                implementation("io.kotest:kotest-assertions-core:${Libs.kotestVersion}")
                implementation("io.ktor:ktor-client-mock:${Libs.ktorVersion}")
                implementation("io.ktor:ktor-client-auth:${Libs.ktorVersion}")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("io.kotest:kotest-runner-junit5:${Libs.kotestVersion}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:${Libs.coroutinesDebugVersion}")
                implementation("ch.qos.logback:logback-classic:${Libs.logbackVersion}")
            }
        }


    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    filter {
        isFailOnNoMatchingTests = false
    }
    testLogging {
        showExceptions = true
        showStandardStreams = true
        events = setOf(
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}