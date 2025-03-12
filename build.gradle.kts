import java.util.Base64

plugins {
	java
	application
	id("com.github.johnrengelman.shadow") version "7.1.2"
	id("com.palantir.git-version") version "0.13.0"
	id("com.github.breadmoirai.github-release") version "2.4.1"
	kotlin("jvm") version "1.7.10"
	id("com.github.jk1.dependency-license-report") version "2.0"
	`maven-publish`

	id("com.github.gmazzo.buildconfig") version "2.1.0"
}

buildConfig {
    packageName("link.infra.packwiz.installer.metadata.curseforge")

    val curseforgeApiKey = System.getenv("CURSEFORGE_API_KEY")
        ?: error("Missing API key")
	val apiKeyBase64 = Base64.getEncoder().encodeToString(curseforgeApiKey.toByteArray())

    buildConfigField("String", "API_KEY_BASE64", "\"${apiKeyBase64}\"")
}

java {
	sourceCompatibility = JavaVersion.VERSION_1_8
}

repositories {
	mavenCentral()
	google()
	maven {
		url = uri("https://jitpack.io")
	}
}

val r8 by configurations.creating
val distJarOutput by configurations.creating {
	isCanBeResolved = false
	isCanBeConsumed = true

	attributes {
		attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
		attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling::class.java, Bundling.EMBEDDED))
	}
}

dependencies {
	implementation("commons-cli:commons-cli:1.5.0")
	implementation("com.google.code.gson:gson:2.9.0")
	implementation("com.squareup.okio:okio:3.1.0")
	implementation(kotlin("stdlib-jdk8"))
	implementation("com.squareup.okhttp3:okhttp:4.10.0")
	implementation("cc.ekblad:4koma:1.1.0")

	r8("com.android.tools:r8:3.3.28")
}

application {
	mainClass.set("link.infra.packwiz.installer.RequiresBootstrap")
}

val gitVersion: groovy.lang.Closure<*> by extra
version = gitVersion()

tasks.jar {
	manifest {
		attributes["Main-Class"] = "link.infra.packwiz.installer.RequiresBootstrap"
		attributes["Implementation-Version"] = project.version
	}
}

licenseReport {
	renderers = arrayOf<com.github.jk1.license.render.ReportRenderer>(
		com.github.jk1.license.render.InventoryMarkdownReportRenderer("licenses.md", "packwiz-installer")
	)
	filters = arrayOf<com.github.jk1.license.filter.DependencyFilter>(com.github.jk1.license.filter.LicenseBundleNormalizer())
}

tasks.shadowJar {
	// 4koma uses kotlin-reflect; requires Kotlin metadata
	//exclude("**/*.kotlin_metadata")
	//exclude("**/*.kotlin_builtins")
	exclude("META-INF/maven/**/*")
	exclude("META-INF/proguard/**/*")

	// Relocate Commons CLI, so that it doesn't clash with old packwiz-installer-bootstrap (that shades it)
	relocate("org.apache.commons.cli", "link.infra.packwiz.installer.deps.commons-cli")

	// from Commons CLI
	exclude("META-INF/LICENSE.txt")
	exclude("META-INF/NOTICE.txt")
}

val shrinkJar by tasks.registering(JavaExec::class) {
	val rules = file("src/main/proguard.txt")
	val r8File = base.libsDirectory.file(provider {
		base.archivesName.get() + "-" + project.version + "-all-shrink.jar"
	})
	dependsOn(configurations.named("runtimeClasspath"))
	inputs.files(tasks.shadowJar, rules)
	outputs.file(r8File)

	classpath(r8)
	mainClass.set("com.android.tools.r8.R8")
	args = mutableListOf(
		"--release",
		"--classfile",
		"--output", r8File.get().toString(),
		"--pg-conf", rules.toString(),
		"--lib", System.getProperty("java.home"),
		*(if (System.getProperty("java.version").startsWith("1.")) {
			// javax.crypto, necessary on <1.9 for compiling Okio
			arrayOf("--lib", System.getProperty("java.home") + "/lib/jce.jar")
		} else { arrayOf() }),
		tasks.shadowJar.get().archiveFile.get().asFile.toString()
	)
}

// MANIFEST.MF must be one of the first 2 entries in the zip for JarInputStream to see it
// Gradle's JAR creation handles this whereas R8 doesn't, so the dist JAR is repacked
val distJar by tasks.registering(Jar::class) {
	from(shrinkJar.map { zipTree(it.outputs.files.singleFile) })
	archiveClassifier.set("all-repacked")
	manifest {
		from(shrinkJar.map { zipTree(it.outputs.files.singleFile).matching {
			include("META-INF/MANIFEST.MF")
		}.singleFile })
	}
}

artifacts {
	add("distJarOutput", distJar) {
		classifier = "dist"
	}
}

// Used for vscode launch.json
val copyJar by tasks.registering(Copy::class) {
	from(distJar)
	rename("packwiz-installer-(.*)\\.jar", "packwiz-installer.jar")
	into(layout.buildDirectory.dir("dist"))
	outputs.file(layout.buildDirectory.dir("dist").map { it.file("packwiz-installer.jar") })
}

tasks.build {
	dependsOn(copyJar)
}

githubRelease {
	owner("vspr-sh")
	repo("packwiz-installer")
	tagName("${project.version}")
	releaseName("Release ${project.version}")
	draft(true)
	token(System.getenv("GITHUB_TOKEN") as String?)
	releaseAssets(layout.buildDirectory.dir("dist").map { it.file("packwiz-installer.jar") }.get())
}

tasks.githubRelease {
	dependsOn(copyJar)
	enabled = System.getenv("GITHUB_TOKEN") != null && project.findProperty("release") == "true"
}

tasks.publish {
	dependsOn(tasks.githubRelease)
}

tasks.compileKotlin {
	kotlinOptions {
		jvmTarget = "1.8"
		freeCompilerArgs = listOf("-Xjvm-default=all", "-Xallow-result-return-type", "-opt-in=kotlin.io.path.ExperimentalPathApi", "-Xlambdas=indy")
	}
}
tasks.compileTestKotlin {
	kotlinOptions {
		jvmTarget = "1.8"
		freeCompilerArgs = listOf("-Xjvm-default=all", "-Xallow-result-return-type", "-opt-in=kotlin.io.path.ExperimentalPathApi", "-Xlambdas=indy")
	}
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.addVariantsFromConfiguration(distJarOutput) {
	mapToMavenScope("runtime")
	mapToOptional()
}
javaComponent.withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) {
	skip()
}

if (System.getenv("GITHUB_TOKEN") != null) {
	publishing {
		repositories {
			maven {
				name = "GitHubPackages"
				url = uri("https://maven.pkg.github.com/vspr-sh/packwiz-installer")

				credentials {
					username = System.getenv("GITHUB_ACTOR")
					password = System.getenv("GITHUB_TOKEN")
				}
			}
		}
    	publications {
            create<MavenPublication>("maven") {
                groupId = "com.github.vspr-sh"
                artifactId = "packwiz-installer"
                version = project.version.toString()

                from(components["java"])
            }
    	}
	}
}

