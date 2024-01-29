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

package software.amazon.smithy.go.codegen.service;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates the concrete type that serves traffic using a provided service implementation.
 */
public final class ServiceStruct implements GoWriter.Writable {
    // TODO: ???????? name
    public static final String NAME = "ConcreteServiceTodoIdkWhatToCallThis";
    public static final String OPTIONS_NAME = NAME + "Options";

    private final ProtocolGenerator protocolGenerator;

    public ServiceStruct(ProtocolGenerator protocolGenerator) {
        this.protocolGenerator = protocolGenerator;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(generateSource());
    }

    private GoWriter.Writable generateSource() {
        return goTemplate("""
                type $struct:L struct {
                    $transportFields:W
                }

                $options:W

                $new:W

                $run:W
                """,
                MapUtils.of(
                        "struct", NAME,
                        "transportFields", protocolGenerator.generateTransportFields(),
                        "options", generateOptions(),
                        "new", generateNew(),
                        "run", generateRun()
                ));
    }

    // TODO: should be separate codegen like it is in client
    private GoWriter.Writable generateOptions() {
        return goTemplate("""
                type $options:L struct {
                    $transportOptions:W
                }
                """,
                MapUtils.of(
                        "options", OPTIONS_NAME,
                        "transportOptions", protocolGenerator.generateTransportOptions()
                ));
    }

    private GoWriter.Writable generateNew() {
        // TODO should be New() but right now it codegens into existing client package
        return goTemplate("""
                func NewServiceTodoRenameThis(svc $interface:L) *$struct:L {
                    sv := &$struct:L{}

                    $transportInit:W

                    return sv
                }
                """,
                MapUtils.of(
                        "interface", ServiceInterface.NAME,
                        "struct", NAME,
                        "transportInit", protocolGenerator.generateTransportInit()
                ));
    }

    private GoWriter.Writable generateRun() {
        return goTemplate("""
                func (sv *$L) Run() error {
                    $W
                }
                """, NAME, protocolGenerator.generateTransportRun());
    }
}
