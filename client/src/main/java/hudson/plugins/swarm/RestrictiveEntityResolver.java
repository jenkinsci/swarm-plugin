package hudson.plugins.swarm;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * An EntityResolver that will fail to resolve any entities. Useful in preventing External XML
 * Entity injection attacks.
 */
public final class RestrictiveEntityResolver implements EntityResolver {

    public static final RestrictiveEntityResolver INSTANCE = new RestrictiveEntityResolver();

    private RestrictiveEntityResolver() {
        // prevent multiple instantiation.
        super();
    }

    /** Throws a SAXException if this tried to resolve any entity. */
    @Override
    public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
        throw new SAXException(
                "Refusing to resolve entity with publicId(" + publicId + ") and systemId (" + systemId + ")");
    }
}
