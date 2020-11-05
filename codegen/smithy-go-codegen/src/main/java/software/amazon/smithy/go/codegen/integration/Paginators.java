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

package software.amazon.smithy.go.codegen.integration;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.TriConsumer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.PaginatedIndex;
import software.amazon.smithy.model.knowledge.PaginationInfo;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.traits.DocumentationTrait;

/**
 * Implements support for PaginatedTrait.
 */
public class Paginators implements GoIntegration {
    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TriConsumer<String, String, Consumer<GoWriter>> writerFactory
    ) {
        ServiceShape serviceShape = settings.getService(model);

        PaginatedIndex paginatedIndex = PaginatedIndex.of(model);

        TopDownIndex topDownIndex = TopDownIndex.of(model);

        List<PaginationInfo> paginationInfos = topDownIndex.getContainedOperations(serviceShape).stream()
                .map(operationShape -> paginatedIndex.getPaginationInfo(serviceShape, operationShape))
                .filter(Optional::isPresent)
                .map(paginationInfo -> paginationInfo.get())
                .collect(Collectors.toList());

        if (paginationInfos.size() == 0) {
            return;
        }

        writerFactory.accept("paginators.go", settings.getModuleName(), writer -> {
            paginationInfos.forEach((paginationInfo) -> {
                generateOperationPaginator(model, symbolProvider, writer, paginationInfo);
            });
        });
    }

    private void generateOperationPaginator(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            PaginationInfo paginationInfo
    ) {
        Symbol operationSymbol = symbolProvider.toSymbol(paginationInfo.getOperation());
        Symbol inputSymbol = symbolProvider.toSymbol(paginationInfo.getInput());
        Symbol outputSymbol = symbolProvider.toSymbol(paginationInfo.getOutput());
        Optional<MemberShape> pageSizeMember = paginationInfo.getPageSizeMember();

        Symbol interfaceSymbol = SymbolUtils.createValueSymbolBuilder(String.format("%sAPIClient",
                operationSymbol.getName())).build();
        Symbol paginatorSymbol = SymbolUtils.createPointableSymbolBuilder(String.format("%sPaginator",
                operationSymbol.getName())).build();
        Symbol optionsSymbol = SymbolUtils.createPointableSymbolBuilder(String.format("%sOptions",
                paginatorSymbol.getName())).build();

        writeClientOperationInterface(writer, operationSymbol, inputSymbol, outputSymbol, interfaceSymbol);
        writePaginatorOptions(model, symbolProvider, writer, operationSymbol, pageSizeMember, optionsSymbol);
        writePaginator(symbolProvider, writer, paginationInfo, operationSymbol, inputSymbol, outputSymbol,
                pageSizeMember, interfaceSymbol, paginatorSymbol, optionsSymbol);
    }

    private void writePaginator(
            SymbolProvider symbolProvider,
            GoWriter writer,
            PaginationInfo paginationInfo,
            Symbol operationSymbol,
            Symbol inputSymbol,
            Symbol outputSymbol,
            Optional<MemberShape> pageSizeMember,
            Symbol interfaceSymbol,
            Symbol paginatorSymbol,
            Symbol optionsSymbol
    ) {
        String inputMember = symbolProvider.toMemberName(paginationInfo.getInputTokenMember());

        writer.writeDocs(String.format("%s is a paginator for %s", paginatorSymbol, operationSymbol));
        writer.openBlock("type $T struct {", "}", paginatorSymbol, () -> {
            writer.write("options $T", optionsSymbol);
            writer.write("client $T", interfaceSymbol);
            writer.write("params $P", inputSymbol);
            writer.write("nextToken *string");
            writer.write("firstPage bool");
            writer.write("done bool");
        });
        writer.write("");

        Symbol newPagiantor = SymbolUtils.createValueSymbolBuilder(String.format("New%s",
                paginatorSymbol.getName())).build();
        writer.writeDocs(String.format("%s returns a new %s", newPagiantor.getName(), paginatorSymbol.getName()));
        writer.openBlock("func $T(client $T, params $P, optFns ...func($P)) $P {", "}",
                newPagiantor, interfaceSymbol, inputSymbol, optionsSymbol, paginatorSymbol, () -> {
                    writer.write("options := $T{}", optionsSymbol);
                    writer.openBlock("for _, fn := range optFns {", "}", () -> {
                        writer.write("fn(&options)");
                    });

                    writer.openBlock("return &$T{", "}", paginatorSymbol, () -> {
                        writer.write("options: options,");
                        writer.write("client: client,");
                        writer.write("params: params,");
                        writer.write("firstPage: true,");
                    });
                });
        writer.write("");

        writer.writeDocs("HasMorePages returns a boolean indicating whether more pages are available");
        writer.openBlock("func (p $P) HasMorePages() bool {", "}", paginatorSymbol, () -> {
            writer.openBlock("if p.done {", "}", () -> writer.write("return false"));
            writer.write("return p.firstPage || (p.nextToken != nil && len(*p.nextToken) > 0)");
        });
        writer.write("");

        Symbol contextSymbol = SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT)
                .build();
        writer.writeDocs(String.format("NextPage retrieves the next %s page.", operationSymbol.getName()));
        writer.openBlock("func (p $P) NextPage(ctx $T, optFns ...func(*Options)) ($P, error) {", "}",
                paginatorSymbol, contextSymbol, outputSymbol, () -> {
                    writer.addUseImports(SmithyGoDependency.FMT);
                    writer.openBlock("if !p.HasMorePages() {", "}", () -> {
                        writer.write("return nil, fmt.Errorf(\"no more pages available\")");
                    });
                    writer.write("");
                    writer.write("params := *p.params");
                    writer.write("params.$L = p.nextToken", inputMember);
                    pageSizeMember.ifPresent(memberShape -> writer.write("params.$L = p.options.Limit",
                            symbolProvider.toMemberName(memberShape)));

                    writer.write("result, err := p.client.$L(ctx, &params, optFns...)",
                            operationSymbol.getName());
                    writer.openBlock("if err != nil {", "}", () -> {
                        writer.write("return nil, err");
                    });
                    writer.write("p.firstPage = false");

                    StringBuilder nilGuard = new StringBuilder();
                    StringBuilder outputPath = new StringBuilder("result");

                    List<MemberShape> outputMemberPath = paginationInfo.getOutputTokenPath();
                    for (int i = 0; i < outputMemberPath.size(); i++) {
                        MemberShape memberShape = outputMemberPath.get(i);
                        outputPath.append(".");
                        outputPath.append(symbolProvider.toMemberName(memberShape));

                        // Don't need to check the last member here as we won't dereference it
                        if (i != outputMemberPath.size() - 1) {
                            if (i != 0) {
                                nilGuard.append(" && ");
                            }
                            nilGuard.append(outputPath);
                            nilGuard.append(" != nil");
                        }
                    }

                    Runnable setToken = () -> {
                        writer.write("p.nextToken = $L", outputPath);
                    };
                    if (nilGuard.length() > 0) {
                        writer.openBlock("if $L {", "}", nilGuard.toString(), setToken::run);
                    } else {
                        setToken.run();
                    }

                    writer.openBlock("if p.options.StopOnDuplicateToken && "
                            + "params.$L != nil && p.nextToken != nil && "
                            + "*params.$L == *p.nextToken {", "", inputMember, inputMember, () -> {
                        writer.write("p.done = true");
                        writer.openBlock("} else {", "}", () -> writer.write("p.done = false"));
                    });

                    writer.write("");
                    writer.write("return result, nil");
                });
    }

    private void writePaginatorOptions(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            Symbol operationSymbol,
            Optional<MemberShape> pageSizeMember,
            Symbol optionsSymbol
    ) {
        writer.writeDocs(String.format("%s is the paginator options for %s", optionsSymbol.getName(),
                operationSymbol.getName()));
        writer.openBlock("type $T struct {", "}", optionsSymbol, () -> {
            pageSizeMember.ifPresent(memberShape -> {
                MemberShape limitShape = pageSizeMember.get();
                limitShape.getMemberTrait(model, DocumentationTrait.class).ifPresent(documentationTrait -> {
                    writer.writeDocs(documentationTrait.getValue());
                });
                writer.write("Limit $P", symbolProvider.toSymbol(limitShape));
                writer.write("");
            });
            writer.writeDocs("Set to true if pagination should stop if the service returns "
                    + "the same taken passed to it");
            writer.write("StopOnDuplicateToken bool");
        });
        writer.write("");
    }

    private void writeClientOperationInterface(
            GoWriter writer,
            Symbol operationSymbol,
            Symbol inputSymbol,
            Symbol outputSymbol,
            Symbol interfaceSymbol
    ) {
        Symbol contextSymbol = SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT)
                .build();

        writer.writeDocs(String.format("%s is a client that implements the %s operation.",
                interfaceSymbol.getName(), operationSymbol.getName()));
        writer.openBlock("type $T interface {", "}", interfaceSymbol, () -> {
            writer.write("$L($T, $P, ...func(*Options)) ($P, error)", operationSymbol.getName(), contextSymbol,
                    inputSymbol, outputSymbol);
        });
        writer.write("");
        writer.write("var _ $T = (*Client)(nil)", interfaceSymbol);
        writer.write("");
    }
}
