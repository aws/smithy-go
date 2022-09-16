package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.TestUtils.hasGoInstalled;
import static software.amazon.smithy.go.codegen.TestUtils.makeGoModule;
import static software.amazon.smithy.go.codegen.TestUtils.testGoModule;

import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.go.codegen.GoWriterDelegator;
import software.amazon.smithy.go.codegen.ManifestWriter;
import software.amazon.smithy.rulesengine.testutil.TestDiscovery;

public class RuleEngineTestSuiteGeneratorTest {
    private static final Logger LOGGER = Logger.getLogger(RuleEngineTestSuiteGeneratorTest.class.getName());

    @Test
    public void testGenerateTestSuite() throws Exception {
        if (!hasGoInstalled()) {
            LOGGER.warning("Skipping testGenerateTestSuite, go command cannot be executed.");
            return;
        }

        var testPath = getTestOutputDir();
        LOGGER.warning("generating test suites into " + testPath);

        var fileManifest = FileManifest.create(testPath);
        var writers = new GoWriterDelegator(fileManifest);

        var generator = RuleEngineTestSuiteGenerator.builder()
                .moduleName("github.com/aws/smithy-go/internal/endpointstest")
                .writerFactory(writers)
                .build();

        generator.generateTestSuites(new TestDiscovery().testSuites());

        var dependencies = writers.getDependencies();
        writers.flushWriters();

        ManifestWriter.builder()
                .moduleName(generator.getModuleName())
                .fileManifest(fileManifest)
                .dependencies(dependencies)
                .minimumGoVersion("1.15")
                .build()
                .writeManifest();

        makeGoModule(testPath);
        testGoModule(testPath);
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
                .resolve("endpointstest")
                .toAbsolutePath();
    }
}
