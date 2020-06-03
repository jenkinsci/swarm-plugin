package hudson.plugins.swarm;

import static org.junit.Assert.assertNotNull;

import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Test;

public class SwarmClientTest {

    @Test
    public void should_create_instance_on_default_options() {
        Options options = new Options();
        SwarmClient swc = new SwarmClient(options);
        assertNotNull(swc);
    }

    @Test
    public void should_try_to_create_http_connection_on_default_options() {
        Options options = new Options();
        CloseableHttpClient hc = SwarmClient.createHttpClient(options);
        assertNotNull(hc);
    }
}
