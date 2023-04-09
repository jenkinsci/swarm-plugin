package hudson.plugins.swarm;

import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.Writer;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SwarmClientTest {

    @ClassRule(order = 20)
    public static TemporaryFolder temporaryFolder =
            TemporaryFolder.builder().assureDeletion().build();

    @Test
    public void should_create_instance_on_default_options() {
        Options options = new Options();
        SwarmClient swc = new SwarmClient(options);
        assertNotNull(swc);
    }

    @Test
    public void should_try_to_create_http_connection_on_default_options() throws IOException {
        Options options = new Options();
        HttpClient client = SwarmClient.createHttpClient(options);
        assertNotNull(client);
    }

    /* Below we have a series of tests which make sure that different ways
     * of passing labels (usually via labelsFile) end up with a sane set.
     * Customized options may be provided to test e.g. concatenation of
     * label sets passed explicitly and by file.
     * See PR https://github.com/jenkinsci/swarm-plugin/pull/420 for how
     * this parsing misbehaved earlier.
     */
    private static void test_labelsFile(String labelsToAdd, String... expected) throws IOException {
        test_labelsFile(null, labelsToAdd, expected);
    }

    private static void test_labelsFile(Options options, String labelsToAdd, String... expected) throws IOException {
        Path labelsFile = Files.createTempFile(temporaryFolder.getRoot().toPath(), "labelsFile", ".txt");

        try (Writer writer = Files.newBufferedWriter(labelsFile, StandardCharsets.UTF_8)) {
            writer.write(labelsToAdd);
        }

        if (options == null) {
            options = new Options();
        }

        options.labelsFile = labelsFile.toString();

        SwarmClient swc = new SwarmClient(options);
        assertNotNull(swc);

        List<String> labels = swc.getOptionsLabels();
        assertNotNull(labels);

        /* Exactly same amount of entries as expected */
        /* import of containsInAnyOrder failed for me, so...
         * assertThat(labels, containsInAnyOrder(expected)); */
        assertThat(labels, hasItems(expected));
        assertEquals(labels.size(), expected.length);

        Files.delete(labelsFile);
    }

    @Test
    public void parse_options_separated_by_spaces() throws IOException {
        String labelsFileContent = "COMPILER=GCC COMPILER=CLANG ARCH64=amd64 ARCH32=i386";
        test_labelsFile(labelsFileContent, "COMPILER=GCC", "COMPILER=CLANG", "ARCH64=amd64", "ARCH32=i386");
    }

    @Test
    public void parse_options_surrounded_by_spaces() throws IOException {
        String labelsFileContent = "  COMPILER=GCC COMPILER=CLANG ARCH64=amd64 ARCH32=i386 ";
        test_labelsFile(labelsFileContent, "COMPILER=GCC", "COMPILER=CLANG", "ARCH64=amd64", "ARCH32=i386");
    }

    @Test
    public void parse_options_separated_by_tabs_and_eols() throws IOException {
        String labelsFileContent = "  COMPILER=GCC\n COMPILER=CLANG\tARCH64=amd64 \n ARCH32=i386 \n";
        test_labelsFile(labelsFileContent, "COMPILER=GCC", "COMPILER=CLANG", "ARCH64=amd64", "ARCH32=i386");
    }

    @Test
    public void parse_options_multiline_indented_all() throws IOException {
        String labelsFileContent = " COMPILER=GCC\n COMPILER=CLANG\n ARCH64=amd64\n ARCH32=i386\n";
        test_labelsFile(labelsFileContent, "COMPILER=GCC", "COMPILER=CLANG", "ARCH64=amd64", "ARCH32=i386");
    }

    @Test
    public void parse_options_multiline_indented_all_but_first() throws IOException {
        String labelsFileContent = "COMPILER=GCC\n COMPILER=CLANG\n ARCH64=amd64\n ARCH32=i386\n";
        test_labelsFile(labelsFileContent, "COMPILER=GCC", "COMPILER=CLANG", "ARCH64=amd64", "ARCH32=i386");
    }

    @Test
    public void parse_options_multiline_not_indented() throws IOException {
        String labelsFileContent = "COMPILER=GCC\nCOMPILER=CLANG\nARCH64=amd64\nARCH32=i386\n";
        test_labelsFile(labelsFileContent, "COMPILER=GCC", "COMPILER=CLANG", "ARCH64=amd64", "ARCH32=i386");
    }

    @Test
    public void parse_options_multiline_trailins_whitespace() throws IOException {
        String labelsFileContent = "COMPILER=GCC\t\nCOMPILER=CLANG \nARCH64=amd64  \n\nARCH32=i386\n";
        test_labelsFile(labelsFileContent, "COMPILER=GCC", "COMPILER=CLANG", "ARCH64=amd64", "ARCH32=i386");
    }
}
