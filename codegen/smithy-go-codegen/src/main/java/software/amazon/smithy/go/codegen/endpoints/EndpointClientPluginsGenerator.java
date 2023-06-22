package software.amazon.smithy.go.codegen.endpoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoCodegenPlugin;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.MiddlewareRegistrar;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.rulesengine.traits.ClientContextParamsTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;

import software.amazon.smithy.utils.StringUtils;



public class EndpointClientPluginsGenerator implements GoIntegration {

    private final List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();


    private static String getAddEndpointMiddlewareFuncName(String operationName) {
        return String.format("add%sResolveEndpointMiddleware", operationName);
    }

    private static String getExportedParameterName(Parameter parameter) {
        return StringUtils.capitalize(parameter.getName().asString());
    }

    private static Symbol parameterAsSymbol(Parameter parameter) {
        return switch (parameter.getType()) {
            case STRING -> SymbolUtils.createPointableSymbolBuilder("string")
                    .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build();

            case BOOLEAN -> SymbolUtils.createPointableSymbolBuilder("bool")
                    .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build();
        };
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        runtimeClientPlugins.add(RuntimeClientPlugin.builder()
        .configFields(ListUtils.of(
            ConfigField.builder()
                    .name("BaseEndpoint")
                    .type(SymbolUtils.createPointableSymbolBuilder("string")
                        .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build())
                    .documentation(
                        """
                        This endpoint will be given as input to an EndpointResolverV2.
                        It is used for providing a custom base endpoint that is subject 
                        to modifications by the processing EndpointResolverV2.        
                        """
                    )
                    .build()
        ))
        .build());
        return runtimeClientPlugins;
    }

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        ServiceShape service = settings.getService(model);
        var rulesetTrait = service.getTrait(EndpointRuleSetTrait.class);
        Optional<EndpointRuleSet> rulesetOpt = (rulesetTrait.isPresent()) 
        ? Optional.of(EndpointRuleSet.fromNode(rulesetTrait.get().getRuleSet()))
        : Optional.empty();
        var clientContextParamsTrait = service.getTrait(ClientContextParamsTrait.class);

        var topDownIndex = TopDownIndex.of(model);

        for (ToShapeId operationId : topDownIndex.getContainedOperations(service)) {
            OperationShape operationShape = model.expectShape(operationId.toShapeId(), OperationShape.class);

            SymbolProvider symbolProvider = GoCodegenPlugin.createSymbolProvider(model, settings);

            String inputHelperFuncName = getAddEndpointMiddlewareFuncName(
                    symbolProvider.toSymbol(operationShape).getName()
            );
            runtimeClientPlugins.add(RuntimeClientPlugin.builder()
                    .operationPredicate((m, s, o) -> {
                        return o.equals(operationShape);
                    })
                    .registerMiddleware(MiddlewareRegistrar.builder()
                            .resolvedFunction(SymbolUtils.createValueSymbolBuilder(inputHelperFuncName)
                                    .build())
                            .useClientOptions()
                            .build())
                    .build());

            if (clientContextParamsTrait.isPresent()) {
                if (rulesetOpt.isPresent()) {
                    var clientContextParams = clientContextParamsTrait.get();
                    var parameters = rulesetOpt.get().getParameters();
                    parameters.toList().stream().forEach(param -> {
                        if (
                            clientContextParams.getParameters().containsKey(param.getName().asString()) &&
                            !param.getBuiltIn().isPresent()
                        ) {
                            var documentation = param.getDocumentation().isPresent() ?  param.getDocumentation().get() : "";
                            runtimeClientPlugins.add(RuntimeClientPlugin.builder()
                            .configFields(ListUtils.of(
                                ConfigField.builder()
                                        .name(getExportedParameterName(param))
                                        .type(parameterAsSymbol(param))
                                        .documentation(documentation)
                                        .build()
                            ))
                            .build());
                        }
                    });
                }
            }
        }
    }
}

