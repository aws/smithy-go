/*
 * Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import static software.amazon.smithy.go.codegen.util.ShapeUtil.STRING_SHAPE;
import static software.amazon.smithy.go.codegen.util.ShapeUtil.expectMember;
import static software.amazon.smithy.go.codegen.util.ShapeUtil.listOf;
import static software.amazon.smithy.utils.StringUtils.capitalize;

import java.util.List;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.jmespath.JmespathExpression;
import software.amazon.smithy.jmespath.ast.FieldExpression;
import software.amazon.smithy.jmespath.ast.FunctionExpression;
import software.amazon.smithy.jmespath.ast.ProjectionExpression;
import software.amazon.smithy.jmespath.ast.Subexpression;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Traverses a JMESPath expression, producing a series of statements that evaluate the entire expression. The generator
 * is shape-aware and the return indicates the underlying shape being referenced in the final result.
 * <br/>
 * Note that the use of writer.write() here is deliberate, it's easier to structure the code in that way instead of
 * trying to recursively compose/organize Writable templates.
 */
@SmithyInternalApi
public class GoJmespathExpressionGenerator {
    private final GoCodegenContext ctx;
    private final GoWriter writer;
    private final Shape input;
    private final JmespathExpression root;

    private int idIndex = 1;

    public GoJmespathExpressionGenerator(GoCodegenContext ctx, GoWriter writer, Shape input, JmespathExpression expr) {
        this.ctx = ctx;
        this.writer = writer;
        this.input = input;
        this.root = expr;
    }

    public Result generate(String ident) {
        writer.write("v1 := $L", ident);
        return visit(root, input);
    }

    private Result visit(JmespathExpression expr, Shape current) {
        if (expr instanceof FunctionExpression tExpr) {
            return visitFunction(tExpr, current);
        } else if (expr instanceof FieldExpression tExpr) {
            return visitField(tExpr, current);
        } else if (expr instanceof Subexpression tExpr) {
            return visitSub(tExpr, current);
        } else if (expr instanceof ProjectionExpression tExpr) {
            return visitProjection(tExpr, current);
        } else {
            throw new CodegenException("unhandled jmespath expression " + expr.getClass().getSimpleName());
        }
    }

    private Result visitProjection(ProjectionExpression expr, Shape current) {
        var left = visit(expr.getLeft(), current);

        // left of projection HAS to be an array by spec, otherwise something is wrong
        if (!left.shape.isListShape()) {
            throw new CodegenException("left side of projection did not create a list");
        }

        var leftMember = expectMember(ctx.model(), (ListShape) left.shape);

        // We have to know the element type for the list that we're generating, use a dummy writer to "peek" ahead and
        // get the traversal result
        var lookahead = new GoJmespathExpressionGenerator(ctx, new GoWriter(""), leftMember, expr.getRight())
                .generate("v");

        ++idIndex;
        writer.write("""
                var v$L []$P
                for _, v := range $L {""", idIndex, ctx.symbolProvider().toSymbol(lookahead.shape), left.ident);

        // new scope inside loop, but now we actually want to write the contents
        // projected.shape is the _member_ of the resulting list
        var projected = new GoJmespathExpressionGenerator(ctx, writer, leftMember, expr.getRight())
                .generate("v");

        writer.write("v$1L = append(v$1L, $2L)", idIndex, projected.ident);
        writer.write("}");

        return new Result(listOf(projected.shape), "v" + idIndex);
    }

    private Result visitSub(Subexpression expr, Shape current) {
        var left = visit(expr.getLeft(), current);
        return visit(expr.getRight(), left.shape);
    }

    private Result visitField(FieldExpression expr, Shape current) {
        ++idIndex;
        writer.write("v$L := v$L.$L", idIndex, idIndex - 1, capitalize(expr.getName()));
        return new Result(expectMember(ctx.model(), current, expr.getName()), "v" + idIndex);
    }

    private Result visitFunction(FunctionExpression expr, Shape current) {
        return switch (expr.name) {
            case "keys" -> visitKeysFunction(expr.arguments, current);
            default -> throw new CodegenException("unsupported function " + expr.name);
        };
    }

    private Result visitKeysFunction(List<JmespathExpression> args, Shape current) {
        if (args.size() != 1) {
            throw new CodegenException("unexpected keys() arg length " + args.size());
        }

        var arg = visit(args.get(0), current);
        ++idIndex;
        writer.write("""
                var v$1L []string
                for k := range $2L {
                    v$1L = append(v$1L, k)
                }""", idIndex, arg.ident);

        return new Result(listOf(STRING_SHAPE), "v" + idIndex);
    }

    public record Result(Shape shape, String ident) {}
}
