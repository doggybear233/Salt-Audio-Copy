pluginManagement {
    repositories {
        maven("https://jitpack.io")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    resolutionStrategy {
        eachPlugin {
            // JitPack 上 gradle-plugin 的 groupId 与 docs 中的 api 坐标不一致，必须重映射
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule("com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
        mavenCentral()
        google()
    }
}

rootProject.name = "saltaudiocopy"
