/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64

plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
    id("com.github.spotbugs") version "4.7.4"
}

allprojects {
    group = "software.amazon.smithy.go"
    version = "0.1.1"
}

// The root project doesn't produce a JAR.
tasks["jar"].enabled = false

repositories {
    mavenLocal()
    mavenCentral()
}

/*
 * Maven Central publishing (interim, pending internal releaser support)
 * ====================================================
 *
 * The internal releaser isn't wired up to sign/publish this package yet.
 * In the meantime, `./gradlew uploadToCentralPortal -PbundleDir=<dir>`
 * signs an already-built, unsigned Maven repository directory (the same
 * layout `./gradlew publish` produces, e.g. build/m2) and uploads it
 * directly to the Central Portal Publisher API. This should be removed
 * once the internal releaser takes over.
 *
 * <dir> should be a copy of an artifact that was already built and
 * tested elsewhere, not this project's own local build output — signing
 * a fresh local build defeats the purpose of this interim path, so
 * bundleDir is rejected if it's inside this project's own build/
 * directory.
 *
 * Signing reads an in-memory ASCII-armored PGP key from the environment,
 * nothing sensitive is stored in this file or in gradle.properties:
 *
 *   ORG_GRADLE_PROJECT_signingKey       (ASCII-armored private key)
 *   ORG_GRADLE_PROJECT_signingPassword
 *
 * Central Portal credentials (a user token generated at
 * central.sonatype.com/account) are read the same way:
 *
 *   ORG_GRADLE_PROJECT_mavenCentralUsername
 *   ORG_GRADLE_PROJECT_mavenCentralPassword
 *
 * Usage:
 *   ./gradlew uploadToCentralPortal -PbundleDir=/path/to/unsigned/m2
 */
val mavenCentralUsername: String? by project
val mavenCentralPassword: String? by project
val signingKey: String? by project
val signingPassword: String? by project

// Directory containing an already-built, unsigned Maven repository
// directory to sign and upload. Required — see comment block above.
val bundleDir: String? by project

/**
 * Signs an already-built Maven repository directory (the unsigned
 * `localStaging` tree, i.e. `build/m2`, produced elsewhere via a plain
 * `./gradlew publish`) in place, without rebuilding anything.
 *
 * For every artifact file (jar/pom/module — anything that isn't already a
 * signature or a checksum), this:
 *   1. Produces a detached ASCII-armored PGP signature (`<file>.asc`), using
 *      the same `signing` plugin machinery (`useInMemoryPgpKeys`) that
 *      `./gradlew publish` already uses successfully for the normal
 *      fresh-build path — this avoids hand-rolling a `gpg` CLI invocation
 *      (which is sensitive to local gpg-agent/keyring setup).
 *   2. Produces md5/sha1/sha256/sha512 checksums for that new `.asc` file,
 *      matching the checksums Gradle already generates for the artifacts
 *      themselves.
 *
 * This lets a human take the exact bytes that were already built and
 * tested elsewhere and sign only, with no second build involved. See
 * `signAndZipBundle` for the task that wires this to a specific source
 * directory.
 */
fun signMavenRepoInPlace(repoDir: File, signingKey: String, signingPassword: String) {
    val digestAlgorithms = mapOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha256" to "SHA-256",
        "sha512" to "SHA-512",
    )

    fun writeChecksums(file: File) {
        digestAlgorithms.forEach { (extension, algorithm) ->
            val digest = MessageDigest.getInstance(algorithm).digest(file.readBytes())
            val hex = digest.joinToString("") { "%02x".format(it) }
            File(file.parentFile, "${file.name}.$extension").writeText(hex)
        }
    }

    signing.useInMemoryPgpKeys(signingKey, signingPassword)

    repoDir.walkTopDown()
        .filter { it.isFile }
        .filterNot { it.name.endsWith(".asc") }
        .filterNot { it.name.endsWith(".md5") }
        .filterNot { it.name.endsWith(".sha1") }
        .filterNot { it.name.endsWith(".sha256") }
        .filterNot { it.name.endsWith(".sha512") }
        .forEach { file ->
            // SignOperation.sign(File) registers the file; execute() runs
            // the actual signing. getSignatureFiles() then gives us the
            // "<file>.asc" it wrote next to the input, matching the
            // convention writeChecksums below expects.
            val operation = signing.sign(file).execute()
            val signatureFiles = operation.signatureFiles.files
            check(signatureFiles.isNotEmpty()) { "gpg signing produced no output for ${file.path}" }
            signatureFiles.forEach { writeChecksums(it) }
        }
}

val signBundle by tasks.registering {
    val workDir = layout.buildDirectory.dir("signed-bundle").get().asFile
    outputs.dir(workDir)

    doLast {
        val signingKey = requireNotNull(signingKey) { "signingKey must be set to sign a bundle." }
        val signingPassword = requireNotNull(signingPassword) { "signingPassword must be set to sign a bundle." }
        val bundleDirValue = requireNotNull(bundleDir) {
            "bundleDir must be set, e.g. -PbundleDir=/path/to/unsigned/m2"
        }

        val sourceDir = File(bundleDirValue)
        require(sourceDir.isDirectory) { "bundleDir '$sourceDir' is not a directory." }

        // Refuse anything under this project's own Gradle build output.
        // The point of this task is to sign+upload an artifact that was
        // already built and tested elsewhere; signing a fresh local
        // build defeats that, even accidentally.
        val sourceDirCanonical = sourceDir.canonicalFile
        val localBuildDirCanonical = layout.buildDirectory.get().asFile.canonicalFile
        require(!sourceDirCanonical.startsWith(localBuildDirCanonical)) {
            "bundleDir '$sourceDir' is inside this project's own local Gradle build output " +
                "($localBuildDirCanonical). This task only signs/uploads an artifact that was " +
                "already built and tested elsewhere, not a fresh local build. Point bundleDir " +
                "at a copy of that artifact's unsigned Maven repository directory instead."
        }

        // Work on a copy so the source directory itself is left untouched
        // (in case it needs to be re-signed or inspected).
        workDir.deleteRecursively()
        sourceDir.copyRecursively(workDir)

        signMavenRepoInPlace(workDir, signingKey, signingPassword)
    }
}

val signAndZipBundle by tasks.registering(Zip::class) {
    dependsOn(signBundle)
    from(layout.buildDirectory.dir("signed-bundle")) {
        // Central Portal's bundle validator expects every directory in the
        // upload to contain a .pom for the version it represents. The
        // localStaging repo's own maven-metadata.xml (plus its checksums and,
        // once signed, its .asc) sits one level up from the versioned
        // directory that actually has the .pom, so it trips a spurious
        // "Bundle has content that does NOT have a .pom file" validation
        // failure. Central generates its own metadata, so exclude ours from
        // the upload.
        exclude("**/maven-metadata.xml*")
    }
    archiveFileName.set("smithy-go-codegen.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

/**
 * Builds the multipart/form-data POST request to upload [bundle] to the
 * Central Portal Publisher API.
 *
 * Central Portal API reference: https://central.sonatype.com/api-doc
 * (see "Upload Bundle" under the Publisher API — a single `bundle` file
 * field containing a zip of the Maven-layout staging repository, Basic
 * auth via a base64 `username:password` user token passed as a Bearer
 * value, and a `publishingType` query param of `USER_MANAGED` or
 * `AUTOMATIC`).
 *
 * The JDK's [HttpClient] has no native multipart support, so the
 * request body is assembled by hand here: a boundary-delimited part
 * header/footer wrapped around the raw bundle file bytes.
 */
fun buildCentralPortalUploadRequest(bundle: File, username: String, password: String): HttpRequest {
    val token = Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    val boundary = "----GradleCentralPortalUpload"
    val bodyPrefix = buildString {
        append("--$boundary\r\n")
        append("Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n")
        append("Content-Type: application/zip\r\n\r\n")
    }.toByteArray(Charsets.UTF_8)
    val bodySuffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    return HttpRequest.newBuilder()
        .uri(URI.create("https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED"))
        .header("Authorization", "Bearer $token")
        .header("Content-Type", "multipart/form-data; boundary=$boundary")
        .POST(
            HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(bodyPrefix),
                HttpRequest.BodyPublishers.ofFile(bundle.toPath()),
                HttpRequest.BodyPublishers.ofByteArray(bodySuffix),
            ),
        )
        .build()
}

tasks.register("uploadToCentralPortal") {
    dependsOn(signAndZipBundle)
    doLast {
        require(!mavenCentralUsername.isNullOrBlank() && !mavenCentralPassword.isNullOrBlank()) {
            "mavenCentralUsername/mavenCentralPassword must be set (e.g. via " +
                "ORG_GRADLE_PROJECT_mavenCentralUsername / ORG_GRADLE_PROJECT_mavenCentralPassword) " +
                "to upload to Central Portal."
        }

        val bundle = signAndZipBundle.get().archiveFile.get().asFile
        val request = buildCentralPortalUploadRequest(bundle, mavenCentralUsername!!, mavenCentralPassword!!)

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        logger.lifecycle("Central Portal response (${response.statusCode()}): ${response.body()}")
        require(response.statusCode() in 200..299) {
            "Upload to Central Portal failed with status ${response.statusCode()}: ${response.body()}"
        }
    }
}

subprojects {
    val subproject = this

    /*
     * Java
     * ====================================================
     */
    if (subproject.name != "smithy-go-codegen-test") {
        apply(plugin = "java-library")

        java {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }

        tasks.withType<JavaCompile> {
            options.encoding = "UTF-8"
        }

        tasks.withType<Javadoc> {
            options.encoding = "UTF-8"
        }

        // Use Junit5's test runner.
        tasks.withType<Test> {
            useJUnitPlatform()
        }

        // Apply junit 5 and hamcrest test dependencies to all java projects.
        dependencies {
            testImplementation("org.junit.jupiter:junit-jupiter-api:5.4.0")
            testImplementation("org.junit.jupiter:junit-jupiter-engine:5.4.0")
            testImplementation("org.junit.jupiter:junit-jupiter-params:5.4.0")
            testImplementation("org.hamcrest:hamcrest:2.1")
        }

        // Reusable license copySpec
        val licenseSpec = copySpec {
            from("${project.rootDir}/LICENSE")
            from("${project.rootDir}/NOTICE")
        }

        // Set up tasks that build source and javadoc jars.
        tasks.register<Jar>("sourcesJar") {
            metaInf.with(licenseSpec)
            from(sourceSets.main.get().allJava)
            archiveClassifier.set("sources")
        }

        tasks.register<Jar>("javadocJar") {
            metaInf.with(licenseSpec)
            from(tasks.javadoc)
            archiveClassifier.set("javadoc")
        }

        // Configure jars to include license related info
        tasks.jar {
            metaInf.with(licenseSpec)
            inputs.property("moduleName", subproject.extra["moduleName"])
            manifest {
                attributes["Automatic-Module-Name"] = subproject.extra["moduleName"]
            }
        }

        // Always run javadoc after build.
        tasks["build"].finalizedBy(tasks["javadoc"])

        /*
         * Maven
         * ====================================================
         */
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        repositories {
            mavenLocal()
            mavenCentral()
        }

        publishing {
            repositories {
                // Picked up by the internal releaser and signed/uploaded there,
                // and consumed as the bundleDir input to uploadToCentralPortal
                // (interim path until the internal releaser supports this
                // package — see the comment near that task's definition).
                maven {
                    name = "localStaging"
                    url = uri("${rootProject.buildDir}/m2")
                }
            }

            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])

                    // Ship the source and javadoc jars.
                    artifact(tasks["sourcesJar"])
                    artifact(tasks["javadocJar"])

                    // Include extra information in the POMs.
                    afterEvaluate {
                        pom {
                            name.set(subproject.extra["displayName"].toString())
                            description.set(subproject.description)
                            url.set("https://github.com/awslabs/smithy")
                            licenses {
                                license {
                                    name.set("Apache License 2.0")
                                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                                    distribution.set("repo")
                                }
                            }
                            developers {
                                developer {
                                    id.set("smithy")
                                    name.set("Smithy")
                                    organization.set("Amazon Web Services")
                                    organizationUrl.set("https://aws.amazon.com")
                                    roles.add("developer")
                                }
                            }
                            scm {
                                url.set("https://github.com/awslabs/smithy.git")
                            }
                        }
                    }
                }
            }
        }

        // Don't sign the artifacts if we didn't get a key and password to use.
        val signingKey: String? by project
        val signingPassword: String? by project
        if (signingKey != null && signingPassword != null) {
            signing {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(publishing.publications["mavenJava"])
            }
        }

        /*
         * Tests
         * ====================================================
         *
         * Configure the running of tests.
         */
        // Log on passed, skipped, and failed test events if the `-Plog-tests` property is set.
        if (project.hasProperty("log-tests")) {
            tasks.test {
                testLogging.events("passed", "skipped", "failed")
            }
        }

        /*
         * Code coverage
         * ====================================================
         */
        apply(plugin = "jacoco")

        // Always run the jacoco test report after testing.
        tasks["test"].finalizedBy(tasks["jacocoTestReport"])

        // Configure jacoco to generate an HTML report.
        tasks.jacocoTestReport {
            reports {
                xml.isEnabled = false
                csv.isEnabled = false
                html.destination = file("$buildDir/reports/jacoco")
            }
        }

        /*
         * Spotbugs
         * ====================================================
         */
        apply(plugin = "com.github.spotbugs")

        // We don't need to lint tests.
        tasks["spotbugsTest"].enabled = false

        // Configure the bug filter for spotbugs.
        tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
            effort.set(com.github.spotbugs.snom.Effort.MAX)
            excludeFilter.set(file("${project.rootDir}/config/spotbugs/filter.xml"))
            reports.maybeCreate("xml").isEnabled = false
            reports.maybeCreate("html").isEnabled = true
        }
    }
}
