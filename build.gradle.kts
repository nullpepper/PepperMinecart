plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "ltd.pepper.pepperminecart"
version = "2.0.0"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    // 共享基础库坐标依赖（发布到 mavenLocal / 内部仓库）
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.bstats:bstats-bukkit:3.1.0")
    // PepperLib 共享基础库（前置插件模式）：compileOnly，绝不 shade，由服务器前置插件 PepperLib.jar 提供。
    // 版本单一来源：gradle.properties 的 pepperLibVersion。
    compileOnly("ltd.pepper:pepper-lib:${property("pepperLibVersion")}")

    // PepperClaim 联动瘦包（libs/PepperClaim-linkage-v1.jar）：**编译期基线，运行时零耦合**。
    // 只在编译期提供 ltd.pepper.claim.ClaimApi 的签名，绝不 shade / 不 relocate：运行期由
    // 服务器上的 PepperClaim.jar 经软依赖（plugin.yml softdepend）提供这些类；
    // PepperClaim 未安装时相关类不可加载，PepperClaimProtection 按「未就绪 → 放行」降级。
    compileOnly(files("libs/PepperClaim-linkage-v1.jar"))
    testImplementation(files("libs/PepperClaim-linkage-v1.jar"))

    // 测试源集需要显式声明 API（测试运行于纯 JVM，仅使用 API 级类）
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.45.0")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // PepperLib 类型在测试期同样需要（compileOnly 不传递给测试源集）
    testImplementation("ltd.pepper:pepper-lib:${property("pepperLibVersion")}")
}

// 配置期捕获版本：processResources 执行期访问 project/Task.project 已被 Gradle 弃用
val pluginVersion: String = version.toString()
val pepperLibVersion: String = providers.gradleProperty("pepperLibVersion").get()

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
        // pepperlib-api.properties 与坐标依赖同源注入（运行期版本门读取此资源）
        filesMatching("pepperlib-api.properties") {
            expand("pepperLibVersion" to pepperLibVersion)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    // 交付产物：shadowJar 使用 -all 分类器，避免与普通 jar 同名互相覆盖。
    // 若同名，单独运行 `gradlew jar` 会把缺 bstats 的瘦包覆盖成部署产物，
    // 服务器加载时 NoClassDefFoundError 直接启用失败。
    shadowJar {
        archiveClassifier.set("all")
        archiveFileName.set("PepperMinecart-${pluginVersion}-all.jar")
        relocate("org.bstats", "ltd.pepper.pepperminecart.libs.bstats")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        // BuildArtifactContractTest 断言交付 jar 已生成且包含重定位 bstats
        dependsOn(shadowJar, jar)
    }
}

// 防版本漂移：gradle.properties 声明的 pepperLibVersion 必须与 compileClasspath 实际解析的
// pepper-lib 版本一致（主类经 pepperlib-api.properties 读取同一来源）。
tasks.register("verifyPepperLibVersion") {
    group = "verification"
    description = "校验 pepperLibVersion 声明与 compileClasspath 解析结果一致"
    doLast {
        val declared = providers.gradleProperty("pepperLibVersion").get()
        val resolved = configurations.compileClasspath.get().resolvedConfiguration
            .firstLevelModuleDependencies
            .filter { it.moduleGroup == "ltd.pepper" && it.moduleName == "pepper-lib" }
            .joinToString { it.moduleVersion }
        check(resolved == declared) {
            "PepperLib 版本不一致：gradle.properties 声明 $declared，实际解析 $resolved"
        }
        logger.lifecycle("verifyPepperLibVersion: 声明 $declared == 解析 $resolved")
    }
}

tasks.named("check") {
    dependsOn(tasks.named("verifyPepperLibVersion"))
}
