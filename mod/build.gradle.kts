plugins {
    kotlin("jvm") version "2.4.20"
    // 面板 UI 需要 Compose 编译器；版本必须与宿主一致（宿主 runtime 1.12.0 / Kotlin 2.4.20）。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.saltrip"
version = "0.29.9"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // 宿主的 config/hook 入口标记为 Unstable，默认会报错；插件本就依赖这些接口。
        freeCompilerArgs.add("-opt-in=com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi")
    }
}

dependencies {
    // 宿主已提供：API + Kotlin stdlib + Compose。全部 compileOnly，绝不打进 spmod。
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    compileOnly(kotlin("stdlib"))
    // Compose 由宿主提供，这里只为编译期可见（绝不打进 spmod）。
    compileOnly("org.jetbrains.compose.runtime:runtime:1.12.0")
    compileOnly("org.jetbrains.compose.ui:ui:1.12.0")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.12.0")
    // 封面缩略图解码：loadImageBitmap 在 desktop 变体里（宿主运行时自带 skiko）。
    compileOnly("org.jetbrains.compose.ui:ui-desktop:1.12.0")
    // 读宿主真实配色（MaterialTheme.colorScheme）。宿主用的是 material3 体系，
    // 版本取与 compose 1.12.0 对应的 alpha03；只编译期可见，运行期由宿主提供。
    compileOnly("org.jetbrains.compose.material3:material3:1.12.0-alpha03")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Compose 编译器会作用于测试源集，测试 classpath 必须能找到 Compose runtime。
    testImplementation("org.jetbrains.compose.runtime:runtime:1.12.0")
    // PanelStyle 的对比度用例要引用 ui.graphics.Color。
    testImplementation("org.jetbrains.compose.ui:ui:1.12.0")
    testImplementation("org.jetbrains.compose.ui:ui-desktop:1.12.0")
    testImplementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
}

spmod {
    PluginClass = "com.saltrip.rip.SaltRipPlugin"
    PluginId = "com.saltrip.saltaudiocopy"
    PluginVersion = project.version.toString()
    PluginProvider = "saltrip"
    PluginName = "盐专辑 (Salt Audio Copy)"
    PluginDescription = "专业 CD 抓轨：SPTI 直连驱动器、安全双读校验、CUE/日志、元数据与封面。"
    PluginOpenSourceUrl = "https://github.com/saltrip/saltrip"
    PluginHasConfig = true
    PluginPermissions = listOf(com.xuncorp.spw.workshop.gradle.PluginPermission.CLASS_TRANSFORM)
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
