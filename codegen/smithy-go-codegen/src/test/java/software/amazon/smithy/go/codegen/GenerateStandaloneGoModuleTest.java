package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.TestUtils.hasGoInstalled;
import static software.amazon.smithy.go.codegen.TestUtils.makeGoModule;
import static software.amazon.smithy.go.codegen.TestUtils.testGoModule;

import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.utils.MapUtils;

public class GenerateStandaloneGoModuleTest {
    private static final Logger LOGGER = Logger.getLogger(GenerateStandaloneGoModuleTest.class.getName());

    @Test
    public void testGenerateGoModule() throws Exception {
        if (!hasGoInstalled()) {
            LOGGER.warning("Skipping testGenerateGoModule, go command cannot be executed.");
            return;
        }

        var testPath = getTestOutputDir();
        LOGGER.warning("generating test suites into " + testPath);

        var fileManifest = FileManifest.create(testPath);
        var writers = new GoWriterDelegator(fileManifest);

        writers.useFileWriter("test-directory/package-name/gofile.go",
                "github.com/aws/smithy-go/internal/testmodule/packagename",
                (w) -> {
                    w.writeGoTemplate("""
                                    type $name:L struct {
                                        Bar $barType:T
                                        Baz *$bazType:T
                                    }

                                    $somethingElse:W
                                    """,
                            MapUtils.of(
                                    "name", "Foo",
                                    "barType", SymbolUtils.createValueSymbolBuilder("string").build(),
                                    "bazType", SymbolUtils.createValueSymbolBuilder("Request",
                                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build(),
                                    "somethingElse", generateSomethingElse()
                            ));
                });

        writers.useFileWriter("test-directory/package-name/gofile_test.go",
                "github.com/aws/smithy-go/internal/testmodule/packagename",
                (w) -> {
                    w.writeGoTemplate("""
                                    func Test$name:L(t *$testingT:T) {
                                        v := $name:L{}
                                        v.Baz = nil
                                    }
                                    """,
                            MapUtils.of(
                                    "name", "Foo",
                                    "testingT", SymbolUtils.createValueSymbolBuilder("T",
                                            SmithyGoDependency.TESTING).build()
                            ));
                });

        var dependencies = writers.getDependencies();
        writers.flushWriters();

        ManifestWriter.builder()
                .moduleName("github.com/aws/smithy-go/internal/testmodule")
                .fileManifest(fileManifest)
                .dependencies(dependencies)
                .build()
                .writeManifest();

        makeGoModule(testPath);
        testGoModule(testPath);
    }

    private GoWriter.Writable generateSomethingElse() {
        return goTemplate("""
                        func (s *$name:L) SomeFunction(i int) string {
                            return "hello there!"
                        }
                        """,
                MapUtils.of(
                        "name", "Foo"
                ));
    }

    private static Path getTestOutputDir() {
        var testWorkspace = System.getenv("SMITHY_GO_TEST_WORKSPACE");
        if (testWorkspace != null) {
            return Path.of(testWorkspace).toAbsolutePath();
        }

        return Path.of(System.getProperty("user.dir"))
                .resolve("build")
                .resolve("test-generated")
                .resolve("go")
                .resolve("internal")
                .resolve("testmodule")
                .toAbsolutePath();
    }
}
