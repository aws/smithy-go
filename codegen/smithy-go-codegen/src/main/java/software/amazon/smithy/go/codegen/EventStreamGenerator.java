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

package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.StringUtils;

public final class EventStreamGenerator {
    public static final String AMZ_CONTENT_TYPE = "application/vnd.amazon.eventstream";

    private static final String EVENT_STREAM_FILE = "eventstream.go";

    private static final Set<String> LEGACY_OPERATIONS = Set.of(
            "com.amazonaws.bedrockagentcore#InvokeCodeInterpreter",
            "com.amazonaws.sagemakerruntime#InvokeEndpointWithResponseStream",
            "com.amazonaws.s3#SelectObjectContent",
            "com.amazonaws.qbusiness#Chat",
            "com.amazonaws.kinesis#SubscribeToShard",
            "com.amazonaws.cloudwatchlogs#GetLogObject",
            "com.amazonaws.cloudwatchlogs#StartLiveTail",
            "com.amazonaws.bedrockruntime#ConverseStream",
            "com.amazonaws.bedrockruntime#InvokeModelWithResponseStream",
            "com.amazonaws.bedrockagentruntime#InvokeInlineAgent",
            "com.amazonaws.bedrockagentruntime#InvokeAgent",
            "com.amazonaws.bedrockagentruntime#OptimizePrompt",
            "com.amazonaws.bedrockagentruntime#RetrieveAndGenerateStream",
            "com.amazonaws.bedrockagentruntime#InvokeFlow",
            "com.amazonaws.lexruntimev2#StartConversation",
            "com.amazonaws.iotsitewise#InvokeAssistant",
            "com.amazonaws.transcribestreaming#StartStreamTranscription",
            "com.amazonaws.transcribestreaming#StartMedicalStreamTranscription",
            "com.amazonaws.transcribestreaming#StartMedicalScribeStream",
            "com.amazonaws.transcribestreaming#StartCallAnalyticsStreamTranscription",
            "com.amazonaws.lambda#InvokeWithResponseStream"
    );

    private final GoSettings settings;
    private final Model model;
    private final GoDelegator writers;
    private final ServiceShape serviceShape;
    private final EventStreamIndex streamIndex;
    private final SymbolProvider symbolProvider;

    public EventStreamGenerator(
            GoSettings settings,
            Model model,
            GoDelegator writers,
            SymbolProvider symbolProvider,
            ServiceShape serviceShape
    ) {
        this.settings = settings;
        this.model = model;
        this.writers = writers;
        this.symbolProvider = symbolProvider;
        this.serviceShape = serviceShape;
        this.streamIndex = EventStreamIndex.of(this.model);
    }

    public void generateEventStreamInterfaces() {
        if (!hasEventStreamOperations()) {
            return;
        }

        final Set<ShapeId> inputEvents = new TreeSet<>();
        final Set<ShapeId> outputEvents = new TreeSet<>();

        TopDownIndex.of(model).getContainedOperations(serviceShape).forEach(operationShape -> {
            streamIndex.getInputInfo(operationShape).ifPresent(eventStreamInfo ->
                    inputEvents.add(eventStreamInfo.getEventStreamMember().getTarget()));
            streamIndex.getOutputInfo(operationShape).ifPresent(eventStreamInfo ->
                    outputEvents.add(eventStreamInfo.getEventStreamMember().getTarget()));
        });

        Symbol context = SymbolUtils.createValueSymbolBuilder("Context",
                SmithyGoDependency.CONTEXT).build();

        writers.useFileWriter(EVENT_STREAM_FILE, settings.getModuleName(), writer -> {
            inputEvents.forEach(shapeId -> {
                Shape shape = model.expectShape(shapeId);
                String writerInterfaceName = getEventStreamWriterInterfaceName(serviceShape, shape);
                writer.writeDocs(String.format("%s provides the interface for writing events to a stream.",
                                writerInterfaceName))
                        .writeDocs("")
                        .writeDocs("The writer's Close method must allow multiple concurrent calls.");
                writer.openBlock("type $L interface {", "}", writerInterfaceName, () -> {
                    writer.write("Send($T, $T) error", context, symbolProvider.toSymbol(shape));
                    writer.write("Close() error");
                    writer.write("Err() error");
                });
            });
            outputEvents.forEach(shapeId -> {
                Shape shape = model.expectShape(shapeId);
                String readerInterfaceName = getEventStreamReaderInterfaceName(serviceShape, shape);
                writer.writeDocs(String.format("%s provides the interface for reading events from a stream.",
                                readerInterfaceName))
                        .writeDocs("")
                        .writeDocs("The writer's Close method must allow multiple concurrent calls.");
                writer.openBlock("type $L interface {", "}", readerInterfaceName, () -> {
                    writer.write("Events() <-chan $T", symbolProvider.toSymbol(shape));
                    writer.write("Close() error");
                    writer.write("Err() error");
                });
            });

            if (settings.useExperimentalSerde()) {
                inputEvents.forEach(shapeId -> {
                    var union = model.expectShape(shapeId, UnionShape.class);
                    generateWriterAdapter(writer, union);
                });
                outputEvents.forEach(shapeId -> {
                    var union = model.expectShape(shapeId, UnionShape.class);
                    generateReaderAdapter(writer, union);
                });
            }
        });
    }

    private void generateWriterAdapter(GoWriter writer, UnionShape union) {
        var unionSymbol = symbolProvider.toSymbol(union);
        var implName = StringUtils.uncapitalize(union.getId().getName(serviceShape)) + "Writer";
        var ifaceName = getEventStreamWriterInterfaceName(serviceShape, union);
        var members = union.members().stream()
                .filter(m -> !m.getMemberTrait(model, ErrorTrait.class).isPresent())
                .sorted()
                .toList();

        writer.addImport(settings.getModuleName() + "/schemas", "schemas");
        writer.writeGoTemplate("""
                type $impl:L struct {
                    writer $esWriter:P
                }

                var _ $iface:L = (*$impl:L)(nil)

                func (w *$impl:L) Send(ctx $context:T, event $union:T) error {
                    var variant $schema:P
                    switch event.(type) {
                    $cases:W
                    default:
                        return $fmtErrorf:T("unknown event type: %T", event)
                    }
                    sv, ok := event.($serializable:T)
                    if !ok {
                        return $fmtErrorf:T("event %T is not serializable", event)
                    }
                    return w.writer.Send(ctx, variant, sv)
                }

                func (w *$impl:L) Close() error {
                    return w.writer.Close()
                }

                func (w *$impl:L) Err() error {
                    return w.writer.Err()
                }
                """,
                MapUtils.of(
                        "impl", implName,
                        "iface", ifaceName,
                        "esWriter", SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("EventStreamWriter"),
                        "context", SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT).build(),
                        "union", unionSymbol,
                        "schema", SmithyGoDependency.SMITHY.pointableSymbol("Schema"),
                        "serializable", SmithyGoDependency.SMITHY.valueSymbol("Serializable"),
                        "fmtErrorf", SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build(),
                        "cases", (Writable) (GoWriter w) -> {
                            for (var member : members) {
                                var variantSymbol = SymbolUtils.createPointableSymbolBuilder(
                                        symbolProvider.toMemberName(member),
                                        unionSymbol.getNamespace()).build();
                                var schemaName = SchemaGenerator.getMemberSchemaName(union, member, serviceShape);
                                w.write("case $P:", variantSymbol);
                                w.write("    variant = schemas.$L", schemaName);
                            }
                        }
                ));
    }

    private void generateReaderAdapter(GoWriter writer, UnionShape union) {
        var unionSymbol = symbolProvider.toSymbol(union);
        var implName = StringUtils.uncapitalize(union.getId().getName(serviceShape)) + "Reader";
        var ifaceName = getEventStreamReaderInterfaceName(serviceShape, union);
        var members = union.members().stream()
                .filter(m -> !m.getMemberTrait(model, ErrorTrait.class).isPresent())
                .sorted()
                .toList();

        writer.writeGoTemplate("""
                type $impl:L struct {
                    reader $esReader:P
                    ch     chan $union:T
                    done   chan struct{}
                }

                var _ $iface:L = (*$impl:L)(nil)

                func $newImpl:L(reader $esReader:P) *$impl:L {
                    r := &$impl:L{
                        reader: reader,
                        ch:     make(chan $union:T),
                        done:   make(chan struct{}),
                    }
                    go r.pipe()
                    return r
                }

                func (r *$impl:L) pipe() {
                    defer close(r.ch)
                    for event := range r.reader.Events() {
                        var ev $union:T
                        switch v := event.(type) {
                        $cases:W
                        default:
                            continue
                        }
                        select {
                        case r.ch <- ev:
                        case <-r.done:
                            return
                        }
                    }
                }

                func (r *$impl:L) Events() <-chan $union:T {
                    return r.ch
                }

                func (r *$impl:L) Close() error {
                    close(r.done)
                    return r.reader.Close()
                }

                func (r *$impl:L) Err() error {
                    return r.reader.Err()
                }
                """,
                MapUtils.of(
                        "impl", implName,
                        "iface", ifaceName,
                        "newImpl", "new" + StringUtils.capitalize(implName),
                        "esReader", SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("EventStreamReader"),
                        "union", unionSymbol,
                        "cases", (Writable) (GoWriter w) -> {
                            for (var member : members) {
                                var targetSymbol = symbolProvider.toSymbol(model.expectShape(member.getTarget()));
                                var variantSymbol = SymbolUtils.createPointableSymbolBuilder(
                                        symbolProvider.toMemberName(member),
                                        unionSymbol.getNamespace()).build();
                                w.write("case $P:", targetSymbol);
                                w.write("    ev = &$T{Value: *v}", variantSymbol);
                            }
                            var esUnknown = SmithyGoDependency.SMITHY_EVENTSTREAM
                                    .pointableSymbol("UnknownUnionMember");
                            var typesUnknown = SymbolUtils.createPointableSymbolBuilder(
                                    "UnknownUnionMember", unionSymbol.getNamespace()).build();
                            w.write("case $P:", esUnknown);
                            w.write("    ev = &$T{Tag: v.Tag, Value: v.Value}", typesUnknown);
                        }
                ));
    }

    public boolean hasEventStreamOperations() {
        return hasEventStreamOperations(model, serviceShape, streamIndex);
    }

    public static boolean hasEventStreamOperations(Model model, ServiceShape serviceShape) {
        EventStreamIndex index = EventStreamIndex.of(model);
        return hasEventStreamOperations(model, serviceShape, index);
    }

    private static boolean hasEventStreamOperations(Model model, ServiceShape serviceShape, EventStreamIndex index) {
        return TopDownIndex.of(model).getContainedOperations(serviceShape).stream()
                .anyMatch(operationShape -> hasEventStream(model, operationShape, index));
    }

    public void writeEventStreamImplementation(Consumer<GoWriter> goWriterConsumer) {
        writers.useFileWriter(EVENT_STREAM_FILE, settings.getModuleName(), goWriterConsumer);
    }

    public boolean hasEventStream(OperationShape operationShape) {
        EventStreamIndex index = EventStreamIndex.of(model);
        return hasEventStreamOperations(model, serviceShape, index);
    }

    public static boolean isLegacyEventStreamGenerator(OperationShape operationShape) {
        return LEGACY_OPERATIONS.contains(operationShape.getId().toString());
    }

    // An operation is considered V2 event stream if:
    // 1. It has an event stream member in the input or output
    // 2. Has event stream output
    // 3. Has not been already generated as a legacy event stream
    public static boolean isV2EventStream(Model model, OperationShape operation) {
        OperationIndex operationIndex = OperationIndex.of(model);
        StructureShape inputShape = operationIndex.getInput(operation).get();
        StructureShape outputShape = operationIndex.getOutput(operation).get();
        boolean hasEventStream = Stream.concat(
                        inputShape.members().stream(),
                        outputShape.members().stream()
                    )
                    .anyMatch(memberShape -> StreamingTrait.isEventStream(model, memberShape));
        boolean hasEventStreamOutput = EventStreamIndex.of(model).getOutputInfo(operation).isPresent();
        return hasEventStream && hasEventStreamOutput && !isLegacyEventStreamGenerator(operation);
    }

    public static boolean hasEventStream(Model model, OperationShape operationShape) {
        EventStreamIndex index = EventStreamIndex.of(model);
        return hasEventStream(model, operationShape, index);
    }

    private static boolean hasEventStream(Model model, OperationShape operationShape, EventStreamIndex index) {
        return index.getInputInfo(operationShape).isPresent() || index.getOutputInfo(operationShape).isPresent();
    }

    public void generateOperationEventStreamStructure(OperationShape operationShape) {
        if (!hasEventStream(model, operationShape)) {
            return;
        }
        writers.useShapeWriter(operationShape, writer -> generateOperationEventStreamStructure(writer, operationShape));
    }

    private void generateOperationEventStreamStructure(GoWriter writer, OperationShape operationShape) {
        var opEventStreamStructure = getEventStreamOperationStructureSymbol(serviceShape, operationShape);
        var constructor = getEventStreamOperationStructureConstructor(serviceShape, operationShape);

        var inputInfo = streamIndex.getInputInfo(operationShape);
        var outputInfo = streamIndex.getOutputInfo(operationShape);

        writer.write("""
                     // $T provides the event stream handling for the $L operation.
                     //
                     // For testing and mocking the event stream this type should be initialized via
                     // the $T constructor function. Using the functional options
                     // to pass in nested mock behavior.""", opEventStreamStructure, operationShape.getId().getName(),
                constructor
                );
        writer.openBlock("type $T struct {", "}", opEventStreamStructure, () -> {
            inputInfo.ifPresent(eventStreamInfo -> {
                var eventStreamTarget = eventStreamInfo.getEventStreamTarget();
                var writerInterfaceName = getEventStreamWriterInterfaceName(serviceShape, eventStreamTarget);

                writer.writeDocs(String.format("""
                                               %s is the EventStream writer for the %s events. This value is
                                               automatically set by the SDK when the API call is made Use this
                                               member when unit testing your code with the SDK to mock out the
                                               EventStream Writer.""",
                                writerInterfaceName, eventStreamTarget.getId().getName(serviceShape)))
                        .writeDocs("")
                        .writeDocs("Must not be nil.")
                        .write("Writer $L", writerInterfaceName).write("");
            });

            outputInfo.ifPresent(eventStreamInfo -> {
                var eventStreamTarget = eventStreamInfo.getEventStreamTarget();
                var readerInterfaceName = getEventStreamReaderInterfaceName(serviceShape, eventStreamTarget);

                writer.writeDocs(String.format("""
                                               %s is the EventStream reader for the %s events. This value is
                                               automatically set by the SDK when the API call is made Use this
                                               member when unit testing your code with the SDK to mock out the
                                               EventStream Reader.""",
                                readerInterfaceName, eventStreamTarget.getId().getName(serviceShape)))
                        .writeDocs("")
                        .writeDocs("Must not be nil.")
                        .write("Reader $L", readerInterfaceName).write("");
            });

            writer.write("done chan struct{}")
                    .write("closeOnce $T", SymbolUtils.createValueSymbolBuilder("Once", SmithyGoDependency.SYNC)
                            .build())
                    .write("err $P", SymbolUtils.createPointableSymbolBuilder("OnceErr",
                            SmithyGoDependency.SMITHY_SYNC).build());
        }).write("");

        writer.write("""
                     // $T initializes an $T.
                     // This function should only be used for testing and mocking the $T
                     // stream within your application.""", constructor, opEventStreamStructure,
                opEventStreamStructure);
        if (inputInfo.isPresent()) {
            writer.writeDocs("");
            writer.writeDocs("The Writer member must be set before writing events to the stream.");
        }
        if (outputInfo.isPresent()) {
            writer.writeDocs("");
            writer.writeDocs("The Reader member must be set before reading events from the stream.");
        }
        writer.openBlock("func $T(optFns ...func($P)) $P {", "}", constructor,
                opEventStreamStructure, opEventStreamStructure, () -> writer
                        .openBlock("es := &$L{", "}", opEventStreamStructure, () -> writer
                                .write("done: make(chan struct{}),")
                                .write("err: $T(),", SymbolUtils.createValueSymbolBuilder("NewOnceErr",
                                        SmithyGoDependency.SMITHY_SYNC).build()))
                        .openBlock("for _, fn := range optFns {", "}", () -> writer
                                .write("fn(es)"))
                        .write("return es")).write("");

        if (inputInfo.isPresent()) {
            writer.write("""
                         // Send writes the event to the stream blocking until the event is written.
                         // Returns an error if the event was not written.
                         func (es $P) Send(ctx $P, event $P) error {
                             return es.Writer.Send(ctx, event)
                         }
                         """, opEventStreamStructure, SymbolUtils.createValueSymbolBuilder("Context",
                            SmithyGoDependency.CONTEXT).build(),
                    symbolProvider.toSymbol(inputInfo.get().getEventStreamTarget()));
        }

        if (outputInfo.isPresent()) {
            writer.write("""
                         // Events returns a channel to read events from.
                         func (es $P) Events() <-chan $P {
                             return es.Reader.Events()
                         }
                         """, opEventStreamStructure, symbolProvider.toSymbol(outputInfo.get().getEventStreamTarget()));
        }

        writer.write("""
                     // Close closes the stream. This will also cause the stream to be closed.
                     // Close must be called when done using the stream API. Not calling Close
                     // may result in resource leaks.
                     //
                     // Will close the underlying EventStream writer and reader, and no more events can be
                     // sent or received.
                     func (es $P) Close() error {
                         es.closeOnce.Do(es.safeClose)
                         return es.Err()
                     }
                     """, opEventStreamStructure);

        writer.openBlock("func (es $P) safeClose() {", "}",
                opEventStreamStructure, () -> {
                    writer.write("""
                                 close(es.done)
                                 """);

                    if (inputInfo.isPresent()) {
                        var newTicker = SymbolUtils.createValueSymbolBuilder("NewTicker",
                                SmithyGoDependency.TIME).build();
                        var second = SymbolUtils.createValueSymbolBuilder("Second",
                                SmithyGoDependency.TIME).build();
                        writer.write("""
                                     t := $T($T)
                                     defer t.Stop()
                                     writeCloseDone := make(chan error)
                                     go func() {
                                         if err := es.Writer.Close(); err != nil {
                                             es.err.SetError(err)
                                         }
                                         close(writeCloseDone)
                                     }()
                                     select {
                                     case <-t.C:
                                     case <-writeCloseDone:
                                     }
                                      """, newTicker, second);
                    }

                    if (outputInfo.isPresent()) {
                        writer.write("es.Reader.Close()");
                    }
                }).write("");

        writer.writeDocs("""
                         Err returns any error that occurred while reading or writing EventStream
                         Events from the service API's response. Returns nil if there were no errors.""");
        writer.openBlock("func (es $P) Err() error {", "}",
                opEventStreamStructure, () -> {
                    writer.write("""
                                 if err := es.err.Err(); err != nil {
                                     return err
                                 }
                                 """);

                    if (inputInfo.isPresent()) {
                        writer.write("""
                                     if err := es.Writer.Err(); err != nil {
                                         return err
                                     }
                                     """);
                    }

                    if (outputInfo.isPresent()) {
                        writer.write("""
                                     if err := es.Reader.Err(); err != nil {
                                         return err
                                     }
                                     """);
                    }

                    writer.write("return nil");
                }).write("");

        writer.openBlock("func (es $P) waitStreamClose() {", "}", opEventStreamStructure,
                () -> {
                    writer.write("""
                                 type errorSet interface {
                                     ErrorSet() <-chan struct{}
                                 }
                                 """);

                    if (inputInfo.isPresent()) {
                        writer.write("""
                                     var inputErrCh <-chan struct{}
                                     if v, ok := es.Writer.(errorSet); ok {
                                         inputErrCh = v.ErrorSet()
                                     }
                                     """);
                    }

                    if (outputInfo.isPresent()) {
                        writer.write("""
                                     var outputErrCh <-chan struct{}
                                     if v, ok := es.Reader.(errorSet); ok {
                                         outputErrCh = v.ErrorSet()
                                     }
                                     var outputClosedCh <-chan struct{}
                                     if v, ok := es.Reader.(interface{ Closed() <-chan struct{} }); ok {
                                         outputClosedCh = v.Closed()
                                     }
                                     """);
                    }

                    writer.openBlock("select {", "}", () -> {
                        writer.write("case <-es.done:");
                        if (inputInfo.isPresent()) {
                            writer.write("""
                                         case <-inputErrCh:
                                             es.err.SetError(es.Writer.Err())
                                             es.Close()
                                         """);
                        }
                        if (outputInfo.isPresent()) {
                            writer.write("""
                                         case <-outputErrCh:
                                             es.err.SetError(es.Reader.Err())
                                             es.Close()

                                         case <-outputClosedCh:
                                             if err := es.Reader.Err(); err != nil {
                                                 es.err.SetError(es.Reader.Err())
                                             }
                                             es.Close()
                                         """);
                        }
                    });

                }).write("");
    }


    public static Symbol getEventStreamOperationStructureConstructor(
            ServiceShape serviceShape,
            OperationShape operationShape
    ) {
        var symbol = getEventStreamOperationStructureSymbol(serviceShape, operationShape);
        return SymbolUtils.createValueSymbolBuilder("New" + symbol.getName()).build();
    }

    public static Symbol getEventStreamOperationStructureSymbol(
            ServiceShape serviceShape,
            OperationShape operationShape
    ) {
        String name = StringUtils.capitalize(operationShape.getId().getName(serviceShape));
        return SymbolUtils.createPointableSymbolBuilder(name + "EventStream")
                .build();
    }

    public static Symbol getEventStreamInitialReplyStructureSymbol(
            ServiceShape serviceShape,
            OperationShape operationShape
    ) {
        String name = StringUtils.capitalize(operationShape.getId().getName(serviceShape));
        return SymbolUtils.createValueSymbolBuilder(name + "InitialReply")
                .build();
    }

    public static Symbol getEventStreamInitialReplyPointableSymbol(
            ServiceShape serviceShape,
            OperationShape operationShape
    ) {
        String name = StringUtils.capitalize(operationShape.getId().getName(serviceShape));
        return SymbolUtils.createPointableSymbolBuilder(name + "InitialReply")
                .build();
    }

    public static String getEventStreamWriterInterfaceName(ServiceShape serviceShape, ToShapeId shape) {
        String name = StringUtils.capitalize(shape.toShapeId().getName(serviceShape));
        return name + "Writer";
    }

    public static String getEventStreamReaderInterfaceName(ServiceShape serviceShape, ToShapeId shape) {
        String name = StringUtils.capitalize(shape.toShapeId().getName(serviceShape));
        return name + "Reader";
    }

    public static String getEventStreamWriterAdapterName(ServiceShape serviceShape, ToShapeId shape) {
        return StringUtils.uncapitalize(shape.toShapeId().getName(serviceShape)) + "Writer";
    }

    public static String getEventStreamReaderAdapterName(ServiceShape serviceShape, ToShapeId shape) {
        return StringUtils.uncapitalize(shape.toShapeId().getName(serviceShape)) + "Reader";
    }

    public static String getEventStreamReaderAdapterConstructor(ServiceShape serviceShape, ToShapeId shape) {
        return "new" + StringUtils.capitalize(shape.toShapeId().getName(serviceShape)) + "Reader";
    }
}
