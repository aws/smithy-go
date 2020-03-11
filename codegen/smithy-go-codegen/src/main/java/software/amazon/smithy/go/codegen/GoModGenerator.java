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

package software.amazon.smithy.go.codegen;

import software.amazon.smithy.build.FileManifest;

/**
 * Generates a go.mod file for the project.
 *
 * <p>See here for more information on the format: https://github.com/golang/go/wiki/Modules#gomod
 *
 * TODO: pull in dependencies
 */
final class GoModGenerator {

    private GoModGenerator() {}

    static void writeGoMod(GoSettings settings, FileManifest manifest) {
        CodegenUtils.runCommand("go mod init " + settings.getModuleName(), manifest.getBaseDir());
    }
}
