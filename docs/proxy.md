# Proxy Configuration

Swarm uses
[`SystemDefaultHttpClient`](https://hc.apache.org/httpcomponents-client-ga/httpclient/apidocs/org/apache/http/impl/client/SystemDefaultHttpClient.html),
which supports customization of the HTTP client through 18 different Java
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
