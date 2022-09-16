/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.VALIDATE_REQUIRED_FUNC_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.getExportedParameterName;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.haveRequiredParameters;
import static software.amazon.smithy.go.codegen.endpoints.FnGenerator.isBuiltInFnResultOptional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.rulesengine.language.Endpoint;
import software.amazon.smithy.rulesengine.language.EndpointRuleset;
import software.amazon.smithy.rulesengine.language.eval.Type;
import software.amazon.smithy.rulesengine.language.syntax.Identifier;
import software.amazon.smithy.rulesengine.language.syntax.expr.Expr;
import software.amazon.smithy.rulesengine.language.syntax.expr.Literal;
import software.amazon.smithy.rulesengine.language.syntax.expr.Ref;
import software.amazon.smithy.rulesengine.language.syntax.fn.FunctionDefinition;
import software.amazon.smithy.rulesengine.language.syntax.fn.IsSet;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;
import software.amazon.smithy.rulesengine.language.syntax.rule.Condition;
import software.amazon.smithy.rulesengine.language.syntax.rule.Rule;
import software.amazon.smithy.rulesengine.language.visit.ExprVisitor;
import software.amazon.smithy.rulesengine.language.visit.RuleValueVisitor;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;

public final class EndpointRulesGenerator {
    public static final String DEFAULT_RESOLVE_ENDPOINT_METHOD_NAME = "ResolveEndpoint";
    public static final String DEFAULT_OPTIONS_NAME = "ClientEndpointOptions";
    public static final String DEFAULT_RESOLVER_TYPE_NAME = "ClientEndpointResolver";
    public static final String DEFAULT_NEW_RESOLVER_FUNC_NAME = "New" + DEFAULT_RESOLVER_TYPE_NAME;
    private static final String PARAMS_ARG_NAME = "params";
    private final Map<String, Object> commonCodegenArgs;

    private int conditionNameCounter = 0;

    private EndpointRulesGenerator(Builder builder) {
        var parametersType = SmithyBuilder.requiredState("parametersType", builder.parametersType);
        var resolverType = SmithyBuilder.requiredState("resolverType", builder.resolverType);
        var newResolverFn = SmithyBuilder.requiredState("newResolverFn", builder.newResolverFn);
        var endpointType = SmithyBuilder.requiredState("endpointType", builder.endpointType);
        var resolveEndpointMethodName = SmithyBuilder.requiredState("resolveEndpointMethodName",
                builder.resolveEndpointMethodName);

        commonCodegenArgs = MapUtils.of(
                "paramArgName", PARAMS_ARG_NAME,
                "parametersType", parametersType,
                "endpointType", endpointType,
                "resolverType", resolverType,
                "newResolverFn", newResolverFn,
                "resolveEndpointMethodName", resolveEndpointMethodName,
                "fmtErrorf", SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build()
        );
    }

    private static String getLocalVarParameterName(Parameter p) {
        return "_" + p.getName().toString();
    }

    private static String getMemberParameterName(Parameter p) {
        return PARAMS_ARG_NAME + "." + getExportedParameterName(p);
    }

    public GoWriter.Writable generate(EndpointRuleset ruleset) {
        return generateRulesType(generateResolveMethodBody(ruleset));
    }

    public GoWriter.Writable generateEmptyRules() {
        return generateRulesType(generateEmptyResolveMethodBody());
    }

    private GoWriter.Writable generateRulesType(GoWriter.Writable resolveMethodBody) {
        return goTemplate("""
                        $resolverTypeDocs:W
                        type $resolverType:T struct{}

                        func $newResolverFn:T() *$resolverType:T {
                            return &$resolverType:T{}
                        }

                        $resolveEndpointMethodDocs:W
                        func (r *$resolverType:T) $resolveEndpointMethodName:L(
                            ctx $context:T, $paramArgName:L $parametersType:T,
                        ) (
                            endpoint $endpointType:T, err error,
                        ) {
                            $resolveMethodBody:W
                        }
                        """,
                commonCodegenArgs,
                MapUtils.of(
                        "context", SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT).build(),
                        "resolverTypeDocs", generateRulesTypeDocs(),
                        "resolveEndpointMethodDocs", generateResolveEndpointMethodDocs(),
                        "resolveMethodBody", resolveMethodBody
                )
        );
    }

    private GoWriter.Writable generateResolveMethodBody(EndpointRuleset ruleset) {
        var scope = Scope.empty();
        for (Parameter p : ruleset.getParameters().toList()) {
            // Required parameters can be dereferenced directly so that read access are always by value.
            // Optional parameters will be dereferenced via conditional checks.
            String identName;
            if (p.isRequired()) {
                identName = getLocalVarParameterName(p);
            } else {
                identName = getMemberParameterName(p);
            }
            scope = scope.withIdent(p.expr(), identName);
        }

        ruleset.typecheck();
        return goTemplate("""
                            $paramsWithDefaults:W
                            $validateParams:W
                            $paramVars:W

                            ec := $newErrorCollector:T()
                            defer func () {
                               if !ec.IsEmpty() {
                                    $logPrintln:T("Endpoint resolution had errors,", ec)
                               }
                            }()

                            $rules:W
                        """,
                commonCodegenArgs,
                MapUtils.of(
                        "logPrintln", SymbolUtils.createValueSymbolBuilder("Println", SmithyGoDependency.LOG).build(),
                        "newErrorCollector", SymbolUtils.createValueSymbolBuilder("NewErrorCollector",
                                SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build(),
                        "paramsWithDefaults", generateParamsWithDefaults(),
                        "validateParams", generateValidateParams(ruleset.getParameters()),
                        "paramVars", (GoWriter.Writable) (GoWriter w) -> {
                            ruleset.getParameters().toList().stream().filter(Parameter::isRequired).forEach((p) -> {
                                w.write("$L := *$L", getLocalVarParameterName(p), getMemberParameterName(p));
                            });
                        },
                        "rules", generateRulesList(ruleset.getRules(), scope)
                ));
    }

    private GoWriter.Writable generateParamsWithDefaults() {
        return goTemplate("$paramArgName:L = $paramArgName:L.$withDefaults:L()",
                commonCodegenArgs,
                MapUtils.of(
                        "withDefaults", EndpointParametersGenerator.DEFAULT_VALUE_FUNC_NAME
                ));
    }

    private GoWriter.Writable generateEmptyResolveMethodBody() {
        return goTemplate("return endpoint, $fmtErrorf:T(\"no endpoint rules defined\")", commonCodegenArgs);
    }

    private GoWriter.Writable generateRulesTypeDocs() {
        return goDocTemplate("$resolverType:T provides the implementation for resolving endpoints.", commonCodegenArgs);
    }

    private GoWriter.Writable generateResolveEndpointMethodDocs() {
        return goDocTemplate("$resolveEndpointMethodName:L attempts to resolve the endpoint with the provided options,"
                        + " returning the endpoint if found. Otherwise an error is returned.",
                commonCodegenArgs);
    }

    private GoWriter.Writable generateValidateParams(Parameters parameters) {
        if (!haveRequiredParameters(parameters)) {
            return emptyGoTemplate();
        }

        return goTemplate("""
                        if err = $paramArgName:L.$paramsValidateMethod:L(); err != nil {
                            return endpoint, $fmtErrorf:T("endpoint parameters are not valid, %w", err)
                        }
                        """,
                commonCodegenArgs,
                MapUtils.of(
                        "paramsValidateMethod", VALIDATE_REQUIRED_FUNC_NAME
                ));
    }

    private GoWriter.Writable generateRulesList(List<Rule> rules, Scope scope) {
        return (w) -> {
            rules.forEach(rule -> {
                rule.getDocumentation().ifPresent(w::writeDocs);
                w.write("$W", generateRule(rule, rule.getConditions(), scope));
            });

            // TODO Creates duplicate return statement in s3 API model for some reason.
            if (!rules.isEmpty() && !(rules.get(rules.size() - 1).getConditions().isEmpty())) {
                // TODO better error including parameters that were used?
                w.writeGoTemplate("return endpoint, $fmtErrorf:T("
                                + "\"no rules matched these parameters. This is a bug, %#v\", $paramArgName:L)",
                        commonCodegenArgs);
            }
        };
    }

    private GoWriter.Writable generateRule(Rule rule, List<Condition> conditions, Scope scope) {
        if (conditions.isEmpty()) {
            return rule.accept(new RuleVisitor(scope));
        }

        var condition = conditions.get(0);
        var remainingConditions = conditions.subList(1, conditions.size());

        var generator = new ExprGenerator(scope);
        var fn = conditionalFunc(condition);

        String conditionName;
        if (condition.getResult().isPresent()) {
            var ident = condition.getResult().get();
            conditionName = "_" + ident.asString();

            // Store the condition result so that it can be referenced in the future by the result identifier.
            scope = scope.withIdent(new Ref(ident, SourceLocation.NONE), conditionName);
        } else {
            conditionName = nameForExpr(fn);
        }

        if (fn.type() instanceof Type.Option || isConditionalFnResultOptional(condition, fn)) {
            return goTemplate("""
                            if exprVal := $target:W; exprVal != nil {
                                $condName:L := *exprVal
                                _ = $condName:L
                                $next:W
                            }
                            """,
                    MapUtils.of(
                            "condName", conditionName,
                            "target", generator.generate(fn),
                            "next", generateRule(rule, remainingConditions, scope.withMember(fn, conditionName))
                    ));
        }

        if (condition.getResult().isPresent()) {
            return goTemplate("""
                            $condName:L := $target:W
                            _ = $condName:L
                            $next:W
                            """,
                    MapUtils.of(
                            "condName", conditionName,
                            "target", generator.generate(fn),
                            "next", generateRule(rule, remainingConditions, scope.withMember(fn, conditionName))
                    ));
        }

        return goTemplate("""
                        if $target:W {
                            $next:W
                        }
                        """,
                MapUtils.of(
                        "target", generator.generate(fn),
                        "next", generateRule(rule, remainingConditions, scope)
                ));
    }

    private static Expr conditionalFunc(Condition condition) {
        var fn = condition.getFn();
        if (fn instanceof IsSet) {
            return ((IsSet) fn).target();
        }
        return fn;
    }


    private String nameForExpr(Expr expr) {
        conditionNameCounter++;
        if (expr instanceof Ref) {
            return nameForRef((Ref) expr);
        }
        return String.format("_var_%d", conditionNameCounter);
    }

    /**
     * Returns a name for a reference.
     *
     * @param ref reference to get name for
     * @return name
     */
    private static String nameForRef(Ref ref) {
        return "_" + ref.getName();
    }

    private GoWriter.Writable generateEndpoint(Endpoint endpoint, Scope scope) {
        return goTemplate("""
                        $endpointType:T{
                            URI: $url:W,
                            $fieldSet:W
                            $properties:W
                        }
                        """,
                commonCodegenArgs,
                MapUtils.of(
                        "url", new ExprGenerator(scope).generate(endpoint.getUrl()),
                        "fieldSet", generateEndpointFieldSet(endpoint.getHeaders(), scope),
                        "properties", generateEndpointProperties(endpoint.getProperties(), scope)
                ));
    }

    private GoWriter.Writable generateEndpointFieldSet(Map<String, List<Expr>> fields, Scope scope) {
        Map<String, Object> args = MapUtils.of(
                "memberName", "Fields",
                "fieldSetType", SymbolUtils.createPointableSymbolBuilder("FieldSet",
                        SmithyGoDependency.SMITHY_TRANSPORT).build(),
                "newFieldSet", SymbolUtils.createValueSymbolBuilder("NewFieldSet",
                        SmithyGoDependency.SMITHY_TRANSPORT).build()
        );

        if (fields.isEmpty()) {
            return goTemplate("Fields: $newFieldSet:T(),", args);
        }

        var writableFields = new TreeMap<String, List<GoWriter.Writable>>();
        var generator = new ExprGenerator(scope);
        fields.forEach((k, vs) -> {
            var writables = new ArrayList<GoWriter.Writable>();
            vs.forEach(v -> writables.add(generator.generate(v)));
            writableFields.put(k, writables);
        });

        return goBlockTemplate("$memberName:L: func() *$fieldSetType:T {", "}(),", args, (w) -> {
            w.writeGoTemplate("fieldSet := $newFieldSet:T()", args);
            writableFields.forEach((k, vs) -> {
                w.write("fieldSet.SetHeader($W)", generateNewFieldValue(k, vs));
            });
            w.write("return fieldSet");
        });
    }

    private GoWriter.Writable generateNewFieldValue(String fieldName, List<GoWriter.Writable> fieldValues) {
        Map<String, Object> args = MapUtils.of(
                "newField", SymbolUtils.createValueSymbolBuilder("NewField",
                        SmithyGoDependency.SMITHY_TRANSPORT).build(),
                "fieldName", fieldName,
                "fieldValues", joinWritables(fieldValues, ", ")
        );

        if (fieldValues.isEmpty()) {
            return goTemplate("$newField:T($fieldName:W)", args);
        }

        return goTemplate("$newField:T($fieldName:S, $fieldValues:W)", args);
    }

    private GoWriter.Writable generateEndpointProperties(Map<Identifier, Literal> properties, Scope scope) {
        Map<String, Object> propertyTypeArg = MapUtils.of(
                "memberName", "Properties",
                "propertyType", SymbolUtils.createValueSymbolBuilder("Properties",
                        SmithyGoDependency.SMITHY).build()
        );

        if (properties.isEmpty()) {
            return emptyGoTemplate();
        }

        var writableProperties = new TreeMap<String, GoWriter.Writable>();
        var generator = new ExprGenerator(scope);
        properties.forEach((k, v) -> {
            writableProperties.put(k.toString(), generator.generate(v));
        });

        return goBlockTemplate(
                """
                        $memberName:L: func() $propertyType:T{
                            var out $propertyType:T
                        """,
                """
                        return out
                        }(),
                        """, propertyTypeArg,
                (w) -> {
                    writableProperties.forEach((k, v) -> {
                        // TODO these properties should be typed, and ignore properties that are unknown.
                        w.write("out.Set($S, $W)", k, v);
                    });
                });
    }

    class RuleVisitor implements RuleValueVisitor<GoWriter.Writable> {
        final Scope scope;

        RuleVisitor(Scope scope) {
            this.scope = scope;
        }

        @Override
        public GoWriter.Writable visitTreeRule(List<Rule> rules) {
            return generateRulesList(rules, scope);
        }

        @Override
        public GoWriter.Writable visitErrorRule(Expr errorExpr) {
            return goTemplate("""
                            return endpoint, $fmtErrorf:T("endpoint rule error, %s", $errorExpr:W)
                            """,
                    commonCodegenArgs,
                    MapUtils.of(
                            "errorExpr", new ExprGenerator(scope).generate(errorExpr)
                    ));
        }

        @Override
        public GoWriter.Writable visitEndpointRule(Endpoint endpoint) {
            return goTemplate("""
                            return $endpoint:W, nil
                            """,
                    MapUtils.of(
                            "endpoint", generateEndpoint(endpoint, scope)
                    ));
        }

    }

    private static boolean isConditionalFnResultOptional(Condition condition, Expr fn) {
        if (condition.getResult().isEmpty()) {
            return false;
        }

        final boolean[] isOptionalResult = {false};
        fn.accept(new ExprVisitor.Default<Void>() {
            @Override
            public Void getDefault() {
                return null;
            }

            @Override
            public Void visitLibraryFunction(FunctionDefinition fn, List<Expr> args) {
                isOptionalResult[0] = isBuiltInFnResultOptional(fn);
                return null;
            }
        });

        return isOptionalResult[0];
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<EndpointRulesGenerator> {
        private Symbol resolverType;
        private Symbol newResolverFn;
        private Symbol parametersType;
        private Symbol endpointType;
        private String resolveEndpointMethodName;

        private Builder() {
        }

        public Builder endpointType(Symbol endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public Builder resolverType(Symbol resolverType) {
            this.resolverType = resolverType;
            return this;
        }

        public Builder newResolverFn(Symbol newResolverFn) {
            this.newResolverFn = newResolverFn;
            return this;
        }

        public Builder resolveEndpointMethodName(String resolveEndpointMethodName) {
            this.resolveEndpointMethodName = resolveEndpointMethodName;
            return this;
        }

        public Builder parametersType(Symbol parametersType) {
            this.parametersType = parametersType;
            return this;
        }

        @Override
        public EndpointRulesGenerator build() {
            return new EndpointRulesGenerator(this);
        }
    }
}
