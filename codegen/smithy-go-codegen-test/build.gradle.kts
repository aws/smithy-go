/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

extra["displayName"] = "Smithy :: Go :: Codegen :: Test"
extra["moduleName"] = "software.amazon.smithy.go.codegen.test"

tasks["jar"].enabled = false

plugins {
    id("software.amazon.smithy").version("0.5.2")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("software.amazon.smithy:smithy-protocol-test-traits:[1.2.0,2.0.0[")
    implementation(project(":smithy-go-codegen"))
}

// ensure built artifacts are put into the SDK's folders
tasks.create<Exec>("verifyGoCodegen") {
    dependsOn ("build")
    workingDir("$buildDir/smithyprojections/smithy-go-codegen-test/source/go-codegen")
    commandLine ("go", "test", "-mod", "mod", "-run", "NONE", "./...")
}
tasks["build"].finalizedBy(tasks["verifyGoCodegen"])
