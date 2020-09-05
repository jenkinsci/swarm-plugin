package hudson.plugins.swarm;

import static org.junit.Assert.assertNotNull;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.Test;

import java.io.IOException;

public class SwarmClientTest {

    @Test
    public void should_create_instance_on_default_options() {
        Options options = new Options();
        SwarmClient swc = new SwarmClient(options);
        assertNotNull(swc);
    }

    @Test
    public void should_try_to_create_http_connection_on_default_options() throws IOException {
        Options options = new Options();
        try (CloseableHttpClient client = SwarmClient.createHttpClient(options)) {
            assertNotNull(client);
        }
    }
}
