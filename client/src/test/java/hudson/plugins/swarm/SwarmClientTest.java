package hudson.plugins.swarm;

import java.net.URL;
import java.net.MalformedURLException;

import org.apache.commons.httpclient.HttpClient;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;

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
        HttpClient hc = swc.createHttpClient(url);
        assertNotNull(hc);
    }

}
