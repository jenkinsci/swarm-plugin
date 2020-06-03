package hudson.plugins.swarm;

import static org.junit.Assert.assertNotNull;

import java.net.MalformedURLException;
import java.net.URL;
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
        SwarmClient swc = new SwarmClient(options);
        URL url = null;
        try {
            url = new URL("http://jenkins:8080");
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        CloseableHttpClient hc = swc.createHttpClient();
        assertNotNull(hc);
    }

}
