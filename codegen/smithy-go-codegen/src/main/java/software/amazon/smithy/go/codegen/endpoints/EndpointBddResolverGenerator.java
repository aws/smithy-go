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

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goBlockTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.getExportedParameterName;
import static software.amazon.smithy.go.codegen.endpoints.FnGenerator.isFnResultOptional;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoUniverseTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.rulesengine.language.Endpoint;
import software.amazon.smithy.rulesengine.language.evaluation.type.OptionalType;
import software.amazon.smithy.rulesengine.language.syntax.Identifier;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Expression;
import software.amazon.smithy.rulesengine.language.syntax.expressions.ExpressionVisitor;
import software.amazon.smithy.rulesengine.language.syntax.expressions.Reference;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.FunctionDefinition;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.IsSet;
import software.amazon.smithy.rulesengine.language.syntax.expressions.functions.LibraryFunction;
import software.amazon.smithy.rulesengine.language.syntax.expressions.literal.Literal;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.rulesengine.language.syntax.rule.Condition;
import software.amazon.smithy.rulesengine.language.syntax.rule.EndpointRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.ErrorRule;
import software.amazon.smithy.rulesengine.language.syntax.rule.Rule;
import software.amazon.smithy.rulesengine.traits.EndpointBddTrait;
import software.amazon.smithy.rulesengine.logic.bdd.Bdd;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates a BDD-based endpoint resolver for Go.
 *
 * <p>Reads an {@link EndpointBddTrait} and emits:
 * <ol>
 *   <li>Node array literal + root constant</li>
 *   <li>{@code conditionContext} struct for condition-assigned values</li>
 *   <li>{@code evalCondition} switch dispatching each BDD condition</li>
 *   <li>{@code resolveResult} switch dispatching each BDD result</li>
 *   <li>{@code ResolveEndpoint} method wiring the BDD evaluator</li>
 *   <li>Resolver type boilerplate (interface, struct, constructor)</li>
 * </ol>
 */
public final class EndpointBddResolverGenerator {
    private static final String PARAMS_ARG_NAME = "params";
    private static final String CTX_ARG_NAME = "c";

    private final FnProvider fnProvider;
    private final Symbol endpointType;
    private final Symbol parametersType;
    private final Symbol resolverInterfaceType;
    private final Symbol resolverImplementationType;
    private final Symbol newResolverFn;
    private final String resolveEndpointMethodName;

    public EndpointBddResolverGenerator(FnProvider fnProvider) {
        this.fnProvider = fnProvider;
        this.endpointType = SymbolUtils.createValueSymbolBuilder("Endpoint",
                SmithyGoDependency.SMITHY_ENDPOINTS).build();
        this.parametersType = SymbolUtils.createValueSymbolBuilder(
                EndpointResolutionGenerator.PARAMETERS_TYPE_NAME).build();
        this.resolverInterfaceType = SymbolUtils.createValueSymbolBuilder(
                EndpointResolutionGenerator.RESOLVER_INTERFACE_NAME).build();
        this.resolverImplementationType = SymbolUtils.createValueSymbolBuilder(
                EndpointResolutionGenerator.RESOLVER_IMPLEMENTATION_NAME).build();
        this.newResolverFn = SymbolUtils.createValueSymbolBuilder(
                EndpointResolutionGenerator.NEW_RESOLVER_FUNC_NAME).build();
        this.resolveEndpointMethodName = EndpointResolutionGenerator.RESOLVER_ENDPOINT_METHOD_NAME;
    }

    /**
     * Generate the full BDD resolver from the trait.
     */
    public Writable generate(EndpointBddTrait trait) {
        var bdd = trait.getBdd();
        var conditions = trait.getConditions();
        var results = trait.getResults();
        var parameters = trait.getParameters();

        // Build a scope that maps parameter references to "params.FieldName" (pointer form, for isSet)
        var ptrScope = Scope.empty();
        // Build a scope that maps parameter references to dereferenced form (for value access)
        var derefScope = Scope.empty();
        for (Iterator<Parameter> iter = parameters.iterator(); iter.hasNext();) {
            Parameter p = iter.next();
            var ptrName = PARAMS_ARG_NAME + "." + getExportedParameterName(p);
            ptrScope = ptrScope.withIdent(p.toExpression(), ptrName);
            derefScope = derefScope.withIdent(p.toExpression(), "*" + ptrName);
        }
        // Add condition-assigned vars to ptrScope (pointer form, no deref)
        for (var cond : conditions) {
            if (cond.getResult().isPresent()) {
                var ident = cond.getResult().get();
                var ref = new Reference(ident, cond.getSourceLocation());
                ptrScope = ptrScope.withIdent(ref, CTX_ARG_NAME + "." + condFieldName(cond));
            }
        }

        // Build a scope that also includes condition-assigned variables mapped to "c.fieldName"
        var condScope = buildConditionScope(derefScope, conditions);

        return goTemplate("""
                $stringSlice:W

                $nodeArray:W

                $conditionContext:W

                $evalCondition:W

                $resolveResult:W

                $resolverType:W
                """,
                MapUtils.of(
                        "stringSlice", generateStringSliceHelper(),
                        "nodeArray", generateNodeArray(bdd),
                        "conditionContext", generateConditionContext(conditions),
                        "evalCondition", generateEvalCondition(conditions, ptrScope, condScope),
                        "resolveResult", generateResolveResult(results, condScope),
                        "resolverType", generateResolverType(parameters)));
    }

    // ---- Component 0: stringSlice helper ----

    private Writable generateStringSliceHelper() {
        return goTemplate("""
                type stringSlice []string

                func (s stringSlice) Get(i int) *string {
                    if i < 0 || i >= len(s) {
                        return nil
                    }

                    v := s[i]
                    return &v
                }""");
    }

    // ---- Component 1: Node array ----

    private Writable generateNodeArray(Bdd bdd) {
        return (GoWriter w) -> {
            w.write("const bddRoot int32 = $L", bdd.getRootRef());
            w.openBlock("var bddNodes = [$L]int32{", "}", bdd.getNodeCount() * 3, () -> {
                bdd.getNodes((varIdx, high, low) -> {
                    w.writeInline("$L, $L, $L, ", varIdx, high, low);
                });
            });
        };
    }

    // ---- Component 2: conditionContext struct ----

    private Writable generateConditionContext(List<Condition> conditions) {
        return (GoWriter w) -> {
            w.openBlock("type conditionContext struct {", "}", () -> {
                for (int i = 0; i < conditions.size(); i++) {
                    var cond = conditions.get(i);
                    if (cond.getResult().isEmpty()) {
                        continue;
                    }
                    var fieldName = condFieldName(cond);
                    var goType = goTypeForConditionFn(cond);
                    w.write("$L $P", fieldName, goType);
                }
            });
        };
    }

    // ---- Component 3: evalCondition switch ----

    private Writable generateEvalCondition(List<Condition> conditions, Scope paramScope, Scope condScope) {
        return (GoWriter w) -> {
            w.openBlock(
                    "func evalCondition(idx int, $L *$T, $L *conditionContext) bool {",
                    "}",
                    PARAMS_ARG_NAME, parametersType, CTX_ARG_NAME,
                    () -> {
                        w.openBlock("switch idx {", "}", () -> {
                            for (int i = 0; i < conditions.size(); i++) {
                                w.write("case $L:", i);
                                w.indent();
                                w.write("$W", generateConditionCase(conditions.get(i), i, paramScope, condScope));
                                w.dedent();
                            }
                        });
                        w.write("return false");
                    });
        };
    }

    private Writable generateConditionCase(Condition condition, int idx, Scope paramScope, Scope condScope) {
        var fn = condition.getFunction();

        // For isSet conditions: check if the parameter is non-nil
        if (fn.getName().equals("isSet")) {
            var inner = fn.getArguments().get(0);
            var generator = new ExpressionGenerator(paramScope, fnProvider);
            if (condition.getResult().isPresent()) {
                // isSet with assign: store value and return true if non-nil
                return goTemplate("""
                        if v := $target:W; v != nil {
                            $ctx:L.$field:L = v
                            return true
                        }
                        return false
                        """,
                        MapUtils.of(
                                "target", generator.generate(inner),
                                "ctx", CTX_ARG_NAME,
                                "field", condFieldName(condition)));
            }
            // isSet without assign: just check non-nil
            return goTemplate("return $target:W != nil",
                    MapUtils.of("target", generator.generate(inner)));
        }

        // For function calls that produce an optional result (e.g. parseURL, substring)
        boolean isOptional = fn.type() instanceof OptionalType || isConditionalFnResultOptional(condition, fn);
        var generator = new ExpressionGenerator(condScope, fnProvider);

        if (condition.getResult().isPresent() && isOptional) {
            return goTemplate("""
                    if v := $target:W; v != nil {
                        $ctx:L.$field:L = v
                        return true
                    }
                    return false
                    """,
                    MapUtils.of(
                            "target", generator.generate(fn),
                            "ctx", CTX_ARG_NAME,
                            "field", condFieldName(condition)));
        }

        if (condition.getResult().isPresent()) {
            // Non-optional function with assign (e.g. uriEncode)
            return goTemplate("""
                    $ctx:L.$field:L = $target:W
                    return true
                    """,
                    MapUtils.of(
                            "target", generator.generate(fn),
                            "ctx", CTX_ARG_NAME,
                            "field", condFieldName(condition)));
        }

        // Boolean condition (no assign) — optional results need nil check
        if (isOptional) {
            return goTemplate("return $target:W != nil",
                    MapUtils.of("target", generator.generate(fn)));
        }
        return goTemplate("return $target:W",
                MapUtils.of("target", generator.generate(fn)));
    }

    // ---- Component 4: resolveResult switch ----

    private Writable generateResolveResult(List<Rule> results, Scope scope) {
        return (GoWriter w) -> {
            w.addUseImports(SmithyGoDependency.FMT);
            w.openBlock(
                    "func resolveResult(idx int32, $L *$T, $L *conditionContext) ($T, error) {",
                    "}",
                    PARAMS_ARG_NAME, parametersType, CTX_ARG_NAME, endpointType,
                    () -> {
                        w.openBlock("switch idx {", "}", () -> {
                            for (int i = 0; i < results.size(); i++) {
                                w.write("case $L:", i);
                                w.indent();
                                w.write("$W", generateResultCase(results.get(i), scope));
                                w.dedent();
                            }
                        });
                        w.write("return $T{}, fmt.Errorf(\"endpoint rule error, invalid result index: %d\", idx)",
                                endpointType);
                    });
        };
    }

    private Writable generateResultCase(Rule result, Scope scope) {
        if (result instanceof ErrorRule errorRule) {
            var generator = new ExpressionGenerator(scope, fnProvider);
            return goTemplate(
                    "return $endpointType:T{}, fmt.Errorf(\"endpoint rule error, %s\", $errorExpr:W)",
                    MapUtils.of(
                            "endpointType", endpointType,
                            "errorExpr", generator.generate(errorRule.getError())));
        }

        if (result instanceof EndpointRule endpointRule) {
            var endpoint = endpointRule.getEndpoint();
            var generator = new ExpressionGenerator(scope, fnProvider);
            return goTemplate("""
                    uriString := $url:W
                    uri, err := url.Parse(uriString)
                    if err != nil {
                        return $endpointType:T{}, fmt.Errorf("Failed to parse uri: %s", uriString)
                    }
                    return $endpoint:W, nil
                    """,
                    MapUtils.of(
                            "endpointType", endpointType,
                            "url", generator.generate(endpoint.getUrl()),
                            "endpoint", generateEndpoint(endpoint, scope)));
        }

        // NoMatchRule (index 0)
        return goTemplate(
                "return $endpointType:T{}, fmt.Errorf(\"endpoint resolution failed: no matching rule\")",
                MapUtils.of("endpointType", endpointType));
    }

    private Writable generateEndpoint(Endpoint endpoint, Scope scope) {
        return goTemplate("""
                $endpointType:T{
                    URI: *uri,
                    $headers:W
                    $properties:W
                }
                """,
                MapUtils.of(
                        "endpointType", endpointType,
                        "headers", generateEndpointHeaders(endpoint.getHeaders(), scope),
                        "properties", generateEndpointProperties(endpoint.getProperties(), scope)));
    }

    private Writable generateEndpointHeaders(Map<String, List<Expression>> headers, Scope scope) {
        if (headers.isEmpty()) {
            return goTemplate("Headers: $T,",
                    SymbolUtils.createValueSymbolBuilder("Header{}", SmithyGoDependency.NET_HTTP).build());
        }

        var generator = new ExpressionGenerator(scope, fnProvider);
        var writableHeaders = new TreeMap<String, List<Writable>>();
        headers.forEach((k, vs) -> {
            var writables = new ArrayList<Writable>();
            vs.forEach(v -> writables.add(generator.generate(v)));
            writableHeaders.put(k, writables);
        });

        return goBlockTemplate("Headers: func() $headerType:T {", "}(),",
                MapUtils.of(
                        "headerType", SymbolUtils.createPointableSymbolBuilder("Header",
                                SmithyGoDependency.NET_HTTP).build()),
                (w) -> {
                    w.write("headers := $T{}",
                            SymbolUtils.createValueSymbolBuilder("Header", SmithyGoDependency.NET_HTTP).build());
                    writableHeaders.forEach((k, vs) -> {
                        w.write("headers.Set($S, $W)", k, joinWritables(vs, ", "));
                    });
                    w.write("return headers");
                });
    }

    private Writable generateEndpointProperties(Map<Identifier, Literal> properties, Scope scope) {
        if (properties.isEmpty()) {
            return emptyGoTemplate();
        }

        var generator = new ExpressionGenerator(scope, fnProvider);
        return goTemplate("""
                Properties: func() $1T {
                    var out $1T
                    $2W
                    return out
                }(),
                """,
                SmithyGoDependency.SMITHY.struct("Properties"),
                ChainWritable.of(
                        properties.entrySet().stream()
                                .map(it -> generateSetProperty(generator, it.getKey(), it.getValue()))
                                .toList()
                ).compose(false));
    }

    private Writable generateSetProperty(ExpressionGenerator generator, Identifier ident, Expression expr) {
        return ident.toString().equals("authSchemes")
                ? new AuthSchemePropertyGenerator(generator).generate(expr)
                : goTemplate("out.Set($S, $W)", ident.toString(), generator.generate(expr));
    }

    // ---- Component 5 & 6: ResolveEndpoint method + resolver type boilerplate ----

    private Writable generateResolverType(Parameters parameters) {
        var hasRequired = EndpointParametersGenerator.haveRequiredParameters(parameters);

        return goTemplate("""
                // $resolverInterfaceType:T provides the interface for resolving service endpoints.
                type $resolverInterfaceType:T interface {
                    $resolveEndpointMethodName:L(ctx $context:T, $paramArgName:L $parametersType:T) (
                        $endpointType:T, error,
                    )
                }

                // $resolverImplementationType:T provides the implementation for resolving endpoints.
                type $resolverImplementationType:T struct{}

                func $newResolverFn:T() $resolverInterfaceType:T {
                    return &$resolverImplementationType:T{}
                }

                // $resolveEndpointMethodName:L attempts to resolve the endpoint with the provided options,
                // returning the endpoint if found. Otherwise an error is returned.
                func (r *$resolverImplementationType:T) $resolveEndpointMethodName:L(
                    ctx $context:T, $paramArgName:L $parametersType:T,
                ) (
                    endpoint $endpointType:T, err error,
                ) {
                    $paramArgName:L = $paramArgName:L.$withDefaults:L()
                    $validateParams:W

                    $ctx:L := &conditionContext{}
                    ref := $bdd:T(bddNodes[:], bddRoot, func(idx int) bool {
                        return evalCondition(idx, &$paramArgName:L, $ctx:L)
                    })
                    return resolveResult(ref, &$paramArgName:L, $ctx:L)
                }
                """,
                MapUtils.of(
                        "context", SymbolUtils.createValueSymbolBuilder("Context",
                                SmithyGoDependency.CONTEXT).build(),
                        "paramArgName", PARAMS_ARG_NAME,
                        "parametersType", parametersType,
                        "endpointType", endpointType,
                        "resolverInterfaceType", resolverInterfaceType,
                        "resolverImplementationType", resolverImplementationType,
                        "newResolverFn", newResolverFn,
                        "resolveEndpointMethodName", resolveEndpointMethodName,
                        "withDefaults", EndpointParametersGenerator.DEFAULT_VALUE_FUNC_NAME,
                        "ctx", CTX_ARG_NAME),
                MapUtils.of(
                        "bdd", SymbolUtils.createValueSymbolBuilder("Evaluate",
                                SmithyGoDependency.SMITHY_ENDPOINT_BDD).build(),
                        "validateParams", hasRequired
                                ? goTemplate("""
                                        if err = $paramArgName:L.$validate:L(); err != nil {
                                            return endpoint, $fmtErrorf:T("endpoint parameters are not valid, %w", err)
                                        }
                                        """,
                                        MapUtils.of(
                                                "paramArgName", PARAMS_ARG_NAME,
                                                "validate", EndpointParametersGenerator.VALIDATE_REQUIRED_FUNC_NAME,
                                                "fmtErrorf", SymbolUtils.createValueSymbolBuilder("Errorf",
                                                        SmithyGoDependency.FMT).build()))
                                : emptyGoTemplate()));
    }

    // ---- Helpers ----

    /**
     * Build a scope that includes both parameter references and condition-assigned variable
     * references pointing to conditionContext fields.
     */
    private Scope buildConditionScope(Scope paramScope, List<Condition> conditions) {
        var scope = paramScope;
        for (var cond : conditions) {
            if (cond.getResult().isPresent()) {
                var ident = cond.getResult().get();
                var ref = new Reference(ident, cond.getSourceLocation());
                var fieldAccess = CTX_ARG_NAME + "." + condFieldName(cond);

                // Only dereference simple pointer fields (*string, *bool).
                // Struct pointers (*PartitionConfig, *URL, *ARN) auto-deref in Go for field access.
                var goType = goTypeForConditionFn(cond);
                if (SymbolUtils.isPointable(goType) && SymbolUtils.isUniverseType(goType)) {
                    fieldAccess = "*" + fieldAccess;
                }

                scope = scope.withIdent(ref, fieldAccess);
            }
        }
        return scope;
    }

    /**
     * Derive the Go struct field name for a condition's assigned result.
     */
    private static String condFieldName(Condition condition) {
        return condition.getResult()
                .map(ident -> ident.getName().getValue())
                .orElseThrow(() -> new IllegalStateException("condition has no result identifier"));
    }

    /**
     * Determine the Go type string for a condition function's return value.
     * This is used for the conditionContext struct fields.
     */
    private Symbol goTypeForConditionFn(Condition condition) {
        var fn = condition.getFunction();

        // isSet stores the pointer value directly — type comes from the inner expression
        if (fn instanceof IsSet) {
            var inner = ((IsSet) fn).getArguments().get(0);
            return goTypeForExpression(inner);
        }

        // Derive Go type from the function's return type
        var fnDef = fn.getFunctionDefinition();
        var fnId = fnDef.getId();

        // Look up the Go function symbol to determine its return type
        Symbol goFn = fnProvider.fnFor(fnId);
        if (goFn == null) {
            goFn = new FnGenerator.DefaultFnProvider().fnFor(fnId);
        }

        if (goFn != null) {
            return goReturnTypeForFn(fnId, fn.type());
        }

        // Fallback based on Smithy type
        var type = fn.type();
        if (type instanceof OptionalType opt) {
            return SymbolUtils.pointerTo(ExpressionGenerator.goTypeForType(opt.inner()));
        }
        return ExpressionGenerator.goTypeForType(type);
    }

    /**
     * Map known function IDs to their Go return types for conditionContext fields.
     */
    private static Symbol goReturnTypeForFn(String fnId,
            software.amazon.smithy.rulesengine.language.evaluation.type.Type smithyType) {
        return switch (fnId) {
            case "parseURL" -> SymbolUtils.createPointableSymbolBuilder("URL",
                    SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();
            case "substring" -> SymbolUtils.pointerTo(GoUniverseTypes.String);
            case "aws.parseArn" -> SymbolUtils.createPointableSymbolBuilder("ARN",
                    "github.com/aws/aws-sdk-go-v2/internal/endpoints/awsrulesfn").build();
            case "aws.partition" -> SymbolUtils.createPointableSymbolBuilder("PartitionConfig",
                     "github.com/aws/aws-sdk-go-v2/internal/endpoints/awsrulesfn").build();
            case "isValidHostLabel", "aws.isVirtualHostableS3Bucket" -> GoUniverseTypes.Bool;
            case "uriEncode" -> GoUniverseTypes.String;
            case "split" -> SymbolUtils.sliceOf(GoUniverseTypes.String);
            case "ite", "coalesce" -> {
                var type = smithyType instanceof OptionalType opt ? opt.inner() : smithyType;
                yield ExpressionGenerator.goTypeForType(type);
            }
            default -> GoUniverseTypes.Any;
        };
    }

    private static Symbol goTypeForExpression(Expression expr) {
        var type = expr.type();
        if (type instanceof OptionalType opt) {
            return SymbolUtils.pointerTo(ExpressionGenerator.goTypeForType(opt.inner()));
        }
        return SymbolUtils.pointerTo(ExpressionGenerator.goTypeForType(type));
    }

    private static boolean isConditionalFnResultOptional(Condition condition, Expression fn) {
        if (condition.getResult().isEmpty()) {
            return false;
        }
        final boolean[] isOptionalResult = {false};
        fn.accept(new ExpressionVisitor.Default<Void>() {
            @Override
            public Void getDefault() {
                return null;
            }

            @Override
            public Void visitLibraryFunction(FunctionDefinition fnDef, List<Expression> args) {
                isOptionalResult[0] = isFnResultOptional(fnDef);
                return null;
            }
        });
        return isOptionalResult[0];
    }
}
