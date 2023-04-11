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

 import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
 import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;

 import java.util.ArrayList;
 import java.util.List;
 import software.amazon.smithy.codegen.core.Symbol;
 import software.amazon.smithy.go.codegen.GoWriter;
 import software.amazon.smithy.go.codegen.SmithyGoDependency;
 import software.amazon.smithy.go.codegen.SymbolUtils;
 import software.amazon.smithy.rulesengine.language.syntax.expr.Expression;
 import software.amazon.smithy.rulesengine.language.syntax.fn.FunctionDefinition;
 import software.amazon.smithy.utils.MapUtils;

 public class FnGenerator {
     private final Scope scope;
     private final FnProvider fnProvider;

     FnGenerator(Scope scope) {
         this.scope = scope;
         this.fnProvider = new BuiltInFnProvider();
     }

     GoWriter.Writable generate(FunctionDefinition fnDef, List<Expression> fnArgs) {
         var goFn = fnProvider.fnFor(fnDef.getId());

         List<GoWriter.Writable> writableFnArgs = new ArrayList<>();
         fnArgs.forEach((expr) -> {
             writableFnArgs.add(new ExpressionGenerator(scope).generate(expr));
         });

         // Add error collector as last parameter in all builtin functions so that
         // unexpected or failed endpoint resolution can be investigated.
         writableFnArgs.add((GoWriter w) -> w.write("ec"));

         return goTemplate("$fn:T($args:W)", MapUtils.of(
                 "fn", goFn,
                 "args", joinWritables(writableFnArgs, ", ")
         ));
     }

     interface FnProvider {
         Symbol fnFor(String name);
     }

     static class BuiltInFnProvider implements FnProvider {

         @Override
         public Symbol fnFor(String name) {
             return switch (name) {
                 case "aws.partition" -> SymbolUtils.createValueSymbolBuilder("GetPartition",
                         SmithyGoDependency.SMITHY_ENDPOINT_AWSRULESFN).build();
                 case "aws.parseArn" -> SymbolUtils.createValueSymbolBuilder("ParseARN",
                         SmithyGoDependency.SMITHY_ENDPOINT_AWSRULESFN).build();
                 case "aws.isVirtualHostableS3Bucket" ->
                         SymbolUtils.createValueSymbolBuilder("IsVirtualHostableS3Bucket",
                         SmithyGoDependency.SMITHY_ENDPOINT_AWSRULESFN).build();
                 case "isValidHostLabel" -> SymbolUtils.createValueSymbolBuilder("IsValidHostLabel",
                         SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();
                 case "parseURL" -> SymbolUtils.createValueSymbolBuilder("ParseURL",
                         SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();
                 case "substring" -> SymbolUtils.createValueSymbolBuilder("SubString",
                         SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();
                 case "uriEncode" -> SymbolUtils.createValueSymbolBuilder("URIEncode",
                         SmithyGoDependency.SMITHY_ENDPOINT_RULESFN).build();

                 default -> SymbolUtils.createValueSymbolBuilder("TODO_" + name).build();
             };
         }
     }

     static boolean isBuiltInFnResultOptional(FunctionDefinition fn) {
             return switch (fn.getId()) {
                 case "aws.partition" -> true;
                 case "aws.parseArn" -> true;
                 case "isValidHostLabel" -> true;
                 case "aws.isVirtualHostableS3Bucket" -> true;
                 case "parseURL" -> true;
                 case "substring" -> true;
                 case "uriEncode" -> false;

                 default -> false;
             };
     }
 }
