/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen.auth;

import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AbstractTrait;

/**
 * Fake trait for aws.auth#sigv4a until smithy adds it.
 */
public final class SigV4aTrait extends AbstractTrait {
    public static final ShapeId ID = ShapeId.from("aws.auth#sigv4a");

    public SigV4aTrait() {
        super(ID, Node.objectNode());
    }

    @Override
    protected Node createNode() {
        return Node.objectNode();
    }

    @Override
    public boolean isSynthetic() {
        return true;
    }
}
