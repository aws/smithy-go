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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.utils.StringUtils;

/**
 * Utility methods likely to be needed across packages.
 */
public final class CodegenUtils {

    private static final Logger LOGGER = Logger.getLogger(CodegenUtils.class.getName());

    private static final String SYNTHETIC_NAMESPACE = "smithy.go.synthetic";

    private CodegenUtils() {
    }

    /**
     * Executes a given shell command in a given directory.
     *
     * @param command   The string command to execute, e.g. "go fmt".
     * @param directory The directory to run the command in.
     * @return Returns the console output of the command.
     */
    public static String runCommand(String command, Path directory) {
        String[] finalizedCommand;
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            finalizedCommand = new String[]{"cmd.exe", "/c", command};
        } else {
            finalizedCommand = new String[]{"sh", "-c", command};
        }

        ProcessBuilder processBuilder = new ProcessBuilder(finalizedCommand)
                .redirectErrorStream(true)
                .directory(directory.toFile());

        try {
            Process process = processBuilder.start();
            List<String> output = new ArrayList<>();

            // Capture output for reporting.
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    LOGGER.finest(line);
                    output.add(line);
                }
            }

            process.waitFor();
            process.destroy();

            String joinedOutput = String.join(System.lineSeparator(), output);
            if (process.exitValue() != 0) {
                throw new CodegenException(String.format(
                        "Command `%s` failed with output:%n%n%s", command, joinedOutput));
            }
            return joinedOutput;
        } catch (InterruptedException | IOException e) {
            throw new CodegenException(e);
        }
    }

    /**
     * Gets the name under which the given package will be exported by default.
     *
     * @param packageName The full package name of the exported package.
     * @return The name a the package will be imported under by default.
     */
    public static String getDefaultPackageImportName(String packageName) {
        if (StringUtils.isBlank(packageName) || !packageName.contains("/")) {
            return packageName;
        }
        return packageName.substring(packageName.lastIndexOf('/') + 1);
    }

    /**
     * Gets the alias to use when referencing the given symbol outside of its namespace.
     *
     * <p>The default value is the last path component of the symbol's namespace.
     *
     * @param symbol The symbol whose whose namespace alias should be retrieved.
     * @return The alias of the symbol's namespace.
     */
    public static String getSymbolNamespaceAlias(Symbol symbol) {
        return symbol.getProperty(SymbolUtils.NAMESPACE_ALIAS, String.class)
                .filter(StringUtils::isNotBlank)
                .orElse(CodegenUtils.getDefaultPackageImportName(symbol.getNamespace()));
    }

    /**
     * Detects if an annotated mediatype indicates JSON contents.
     *
     * @param mediaType The media type to inspect.
     * @return If the media type indicates JSON contents.
     */
    public static boolean isJsonMediaType(String mediaType) {
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    /**
     * Get the namespace where synthetic types are generated at runtime.
     *
     * @return synthetic type namespace
     */
    public static String getSyntheticTypeNamespace() {
        return CodegenUtils.SYNTHETIC_NAMESPACE;
    }

    /**
     * Get the pointer reference to operand , if symbol is pointable.
     * This method can be used by deserializers to get pointer to
     * operand.
     *
     * @param writer  The writer dependencies will be added to, if needed.
     * @param shape   The shape whose value needs to be assigned.
     * @param operand The Operand is the value to be assigned to the symbol shape.
     * @return The Operand, along with pointer reference if applicable
     */
    public static String generatePointerValueIfPointable(GoWriter writer, Shape shape, String operand) {
        String prefix = "";
        String suffix = ")";

        switch (shape.getType()) {
            case STRING:
                if (shape.hasTrait(EnumTrait.class)) {
                    return operand;
                }

                prefix = "ptr.String(";
                break;

            case BOOLEAN:
                prefix = "ptr.Bool(";
                break;

            case BYTE:
                prefix = "ptr.Int8(";
                break;
            case SHORT:
                prefix = "ptr.Int16(";
                break;
            case INTEGER:
                prefix = "ptr.Int32(";
                break;
            case LONG:
                prefix = "ptr.Int64(";
                break;

            case FLOAT:
                prefix = "ptr.Float32(";
                break;
            case DOUBLE:
                prefix = "ptr.Float64(";
                break;

            case TIMESTAMP:
                prefix = "ptr.Time(";
                break;

            default:
                if (isShapePassByReference(shape)) {
                    return '&' + operand;
                }
                return operand;
        }

        writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
        return prefix + operand + suffix;
    }

    /**
     * Gets a value version of the operate based on the shape type. Returns a string with dereferencing the provided
     * operand value if needed. Shapes like Structure, maps, and slices are not dereferenced.
     *
     * @param writer  The writer dependencies will be added to, if needed.
     * @param shape   The shape whose value needs to be assigned.
     * @param operand The Operand is the value to be assigned to the symbol shape.
     * @return The Operand, along with pointer reference if applicable
     */
    public static String operandValueIfScalar(GoWriter writer, Shape shape, String operand) {
        String prefix = "";
        String suffix = ")";

        switch (shape.getType()) {
            case STRING:
                if (shape.hasTrait(EnumTrait.class)) {
                    return operand;
                }

                prefix = "ptr.ToString(";
                break;

            case BOOLEAN:
                prefix = "ptr.ToBool(";
                break;

            case BYTE:
                prefix = "ptr.ToInt8(";
                break;
            case SHORT:
                prefix = "ptr.ToInt16(";
                break;
            case INTEGER:
                prefix = "ptr.ToInt32(";
                break;
            case LONG:
                prefix = "ptr.ToInt64(";
                break;

            case FLOAT:
                prefix = "ptr.ToFloat32(";
                break;
            case DOUBLE:
                prefix = "ptr.ToFloat64(";
                break;

            case TIMESTAMP:
                prefix = "ptr.ToTime(";
                break;

            default:
                return operand;
        }

        writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
        return prefix + operand + suffix;
    }

    /**
     * Returns whether the shape should be passed by value in Go.
     *
     * @param shape the shape
     * @return whether the shape should be passed by value
     */
    public static boolean isShapePassByValue(Shape shape) {
        return shape.getType() == ShapeType.LIST
                || shape.getType() == ShapeType.SET
                || shape.getType() == ShapeType.UNION
                || shape.getType() == ShapeType.MAP
                || shape.getType() == ShapeType.BLOB
                || (shape.getType() == ShapeType.STRING && shape.hasTrait(EnumTrait.class));
    }

    /**
     * Returns whether the shape should be passed by pointer reference in Go.
     *
     * @param shape the shape
     * @return whether the shape should be passed by reference
     */
    public static boolean isShapePassByReference(Shape shape) {
        return !isShapePassByValue(shape);
    }

    /**
     * Returns whether the provided shape can have a Go nil value assigned to it.
     * If provided a MemberShape it will use the target shape and the aggregate shape containing
     * the member is a reference frame to determine if nil is allowed.
     *
     * @param model the model
     * @param shape the shape to test
     * @return if the shape can be assigned a nil Go value
     */
    public static boolean isNilAssignableToShape(Model model, Shape shape) {
        if (shape instanceof MemberShape) {
            ShapeId memberShapeId = shape.getId();

            Shape aggregateShape = model.expectShape(ShapeId.fromParts(memberShapeId.getNamespace(),
                    memberShapeId.getName()));

            // If the aggregate parent shape is not a structure the member shape is expected to not be a pointer
            if (!(aggregateShape instanceof StructureShape)) {
                return false;
            }

            shape = model.expectShape(((MemberShape) shape).getTarget());
        }

        return !shape.hasTrait(EnumTrait.class);
    }

    /**
     * Returns the shape unpacked as a CollectionShape. Throws and exception if the passed in
     * shape is not a list or set.
     *
     * @param shape the list or set shape.
     * @return The unpacked CollectionShape.
     */
    public static CollectionShape expectCollectionShape(Shape shape) {
        if (shape instanceof CollectionShape) {
            return (CollectionShape) (shape);
        }

        throw new CodegenException("expect shape " + shape.getId() + " to be Collection, was " + shape.getType());
    }

    /**
     * Comparator to sort ShapeMember lists alphabetically, with required members first followed by optional members.
     */
    public static final class SortedMembers implements Comparator<MemberShape> {
        private final SymbolProvider symbolProvider;

        /**
         * Initializes the SortedMembers.
         *
         * @param symbolProvider symbol provider used for codegen.
         */
        public SortedMembers(SymbolProvider symbolProvider) {
            this.symbolProvider = symbolProvider;
        }

        @Override
        public int compare(MemberShape a, MemberShape b) {
            // first compare if the members are required or not, which ever member is required should win. If both
            // members are required or not required, continue on to alphabetic search.

            // If a is required but b isn't return -1 so a is sorted before b
            // If b is required but a isn't, return 1 so a is sorted after b
            // If both a and b are required or optional, use alphabetic sorting of a and b's member name.

            int requiredMember = 0;
            if (a.hasTrait(RequiredTrait.class)) {
                requiredMember -= 1;
            }
            if (b.hasTrait(RequiredTrait.class)) {
                requiredMember += 1;
            }
            if (requiredMember != 0) {
                return requiredMember;
            }

            return symbolProvider.toMemberName(a)
                    .compareTo(symbolProvider.toMemberName(b));
        }
    }

    /**
     * Attempts to find the first member by exact name in the containing structure. If the member is not found an
     * exception will be thrown.
     *
     * @param shape structure containing member
     * @param name  member name
     * @return MemberShape if found
     */
    public static MemberShape expectMember(StructureShape shape, String name) {
        return expectMember(shape, name::equals);
    }

    /**
     * Attempts to find the first member by name using a member name predicate in the containing structure. If the
     * member is not found an exception will be thrown.
     *
     * @param shape               structure containing member
     * @param memberNamePredicate member name to search for
     * @return MemberShape if found
     */
    public static MemberShape expectMember(StructureShape shape, Predicate<String> memberNamePredicate) {
        return expectMemberByPredicate(shape, (p) -> memberNamePredicate.test(p.getMemberName()));
    }

    /**
     * Attempts to find the first member by a predicate in the containing structure. If the member is not found an
     * exception will be thrown.
     *
     * @param shape                structure containing member
     * @param memberShapePredicate member shape to search for
     * @return MemberShape if found
     */
    public static MemberShape expectMemberByPredicate(
            StructureShape shape,
            Predicate<MemberShape> memberShapePredicate
    ) {
        return shape.getAllMembers().values().stream()
                .filter(memberShapePredicate)
                .findFirst()
                .orElseThrow(() -> new CodegenException("did not find member in structure shape, " + shape.getId()));
    }
}
