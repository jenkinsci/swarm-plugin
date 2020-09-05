# Proxy Configuration

Swarm uses
[`HttpClientBuilder`](https://hc.apache.org/httpcomponents-client-5.0.x/httpclient5/apidocs/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.html),
which supports customization of the HTTP client through system
properties. Use the following system properties to configure a proxy:

* `http.proxyHost`
* `http.proxyPort`
* `https.proxyHost`
* `https.proxyPort`
* `http.nonProxyHosts`

For example:

```
java \
    -Dhttp.proxyHost=127.0.0.1 \
    -Dhttp.proxyPort=3128 \
    -jar swarm-client.jar
```

For more information about these properties, see [the Java
documentation](https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html).
