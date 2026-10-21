plugins {
    kotlin("jvm") version "2.4.20"
    // 面板 UI 需要 Compose 编译器；版本必须与宿主一致（宿主 runtime 1.12.0 / Kotlin 2.4.20）。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.doggybear233"
version = "1.0.0"

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

// R3 / R2-01：把根目录的 LICENSE 与 THIRD-PARTY-NOTICES 打进 spmod。
// 插件内含 GPL-2.0-or-later 的 flac.exe，第三方位声明必须随包分发（不只是放仓库里）。
// 发布渠道目录另行各放一份，内容相同。
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("../LICENSE")) {
        rename { "LICENSE" }
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.file("../THIRD-PARTY-NOTICES.md")) {
        rename { "THIRD-PARTY-NOTICES.md" }
        into("META-INF")
    }
}

spmod {
    PluginClass = "com.doggybear233.saltaudiocopy.SaltAudioCopyPlugin"
    PluginId = "com.doggybear233.saltaudiocopy"
    PluginVersion = project.version.toString()
    PluginProvider = "doggybear233"
    PluginName = "Salt Audio Copy"
    PluginDescription = "Professional CD ripping: SPTI, secure re-reads, CUE and log, metadata and cover art."
    PluginOpenSourceUrl = "https://github.com/doggybear233/Salt-Audio-Copy"
    PluginHasConfig = true
    PluginPermissions = listOf(com.xuncorp.spw.workshop.gradle.PluginPermission.CLASS_TRANSFORM)
}

tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // 断言的期望值是中文原文；宿主语言的探测在测试里没有宿主，会落到 JVM 默认语言，
    // 而本机（以及多语言 CI）默认可能是日文/英文。钉死成中文，测的才是格式化逻辑本身。
    systemProperty("user.language", "zh")
    systemProperty("user.country", "CN")
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

// 需要真光驱 / 真网络的用例打 @Tag("live")，默认套件不跑：
// 否则换一台没有 F: 盘、或者离线机器，`gradlew test` 会一片红，
// 而这些红跟代码质量无关，反而掩盖真正的回归。
//
// 注意：过滤只配在 test 任务上，不能用 tasks.withType<Test>。
// 否则 liveTest 也会继承 excludeTags("live")，与自己 includeTags("live") 互斥，
// Gradle 以「排除优先」处理 —— liveTest 一个用例都不跑却返回成功（第 3 轮 F-410）。
tasks.test {
    useJUnitPlatform { excludeTags("live") }
}

/** 真机自检：`gradlew liveTest`（需要 F: 盘音频 CD + 能上网）。 */
tasks.register<Test>("liveTest") {
    group = "verification"
    description = "Runs tests tagged live (real CD drive / network)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // 独立任务上的 include，不继承 test 的 exclude。
    useJUnitPlatform { includeTags("live") }
    // 一个用例都没发现就失败：否则「环境没配好」会被读成「真机全部通过」。
    // 注意 `filter.isFailOnNoMatchingTests` 只管按名字过滤，对 tag 过滤无效（R4-02 / F-518），
    // 所以这里直接数结果文件：全部 skip 也视为没执行。
    filter { isFailOnNoMatchingTests = true }
    doLast {
        val dir = reports.junitXml.outputLocation.asFile.get()
        val ran = dir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            .orEmpty()
            .sumOf { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                val tests = Regex("<testsuite[^>]*\\btests=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val skipped = Regex("<testsuite[^>]*\\bskipped=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                (tests - skipped).coerceAtLeast(0)
            }
        if (ran == 0) {
            throw GradleException("liveTest 没有成功执行任何用例（tag/设备/网络没就绪），不能当作通过")
        }
    }
    shouldRunAfter(tasks.named("test"))
}
