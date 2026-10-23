pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // 高德 SDK Maven 镜像（官方推荐）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 高德地图 / 搜索 / 定位 SDK
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "MotoHeadUnit"
include(":app")
