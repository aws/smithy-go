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

package software.amazon.smithy.go.codegen;

import software.amazon.smithy.codegen.core.Symbol;

/**
 * Collection of Symbol constants for golang universe types.
 * See <a href="https://go.dev/ref/spec#Predeclared_identifiers">predeclared identifiers</a>.
 */
public final class GoUniverseTypes {
    public static Symbol tAny = universe("any");
    public static Symbol tBool = universe("bool");
    public static Symbol tByte = universe("byte");
    public static Symbol tComparable = universe("comparable");

    public static Symbol tComplex64 = universe("complex64");
    public static Symbol tComplex128 = universe("complex128");
    public static Symbol tError = universe("error");
    public static Symbol tFloat32 = universe("float32");
    public static Symbol tFloat64 = universe("float64");

    public static Symbol tInt = universe("int");
    public static Symbol tInt8 = universe("int8");
    public static Symbol tInt16 = universe("int16");
    public static Symbol tInt32 = universe("int32");
    public static Symbol tInt64 = universe("int64");
    public static Symbol tRune = universe("rune");
    public static final Symbol STRING = universe("string");

    public static Symbol tUint = universe("uint");
    public static Symbol tUint8 = universe("uint8");
    public static Symbol tUint16 = universe("uint16");
    public static Symbol tUint32 = universe("uint32");
    public static Symbol tUint64 = universe("uint64");
    public static Symbol tUintptr = universe("uintptr");

    private GoUniverseTypes() {}

    private static Symbol universe(String name) {
        return SymbolUtils.createValueSymbolBuilder(name).putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build();
    }
}
