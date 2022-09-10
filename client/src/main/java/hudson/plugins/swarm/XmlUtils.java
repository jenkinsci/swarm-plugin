package hudson.plugins.swarm;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public final class XmlUtils {

    private static final Logger logger =
            LogManager.getLogManager().getLogger(XmlUtils.class.getName());

    /**
     * Parse the supplied XML stream data to a {@link Document}.
     *
     * <p>This function does not close the stream.
     *
     * @param stream The XML stream.
     * @return The XML {@link Document}.
     * @throws SAXException Error parsing the XML stream data e.g. badly formed XML.
     * @throws IOException Error reading from the steam.
     */
    public static @NonNull Document parse(@NonNull InputStream stream)
            throws IOException, SAXException {
        DocumentBuilder docBuilder;

        try {
            docBuilder = newDocumentBuilderFactory().newDocumentBuilder();
            docBuilder.setEntityResolver(RestrictiveEntityResolver.INSTANCE);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Unexpected error creating DocumentBuilder.", e);
        }

        return docBuilder.parse(stream);
    }

    private static DocumentBuilderFactory newDocumentBuilderFactory() {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        // Set parser features to prevent against XXE etc.
        // Note: setting only the external entity features on DocumentBuilderFactory instance
        // (ala how safeTransform does it for SAXTransformerFactory) does seem to work (was still
        // processing the entities - tried Oracle JDK 7 and 8 on OSX). Setting seems a bit extreme,
        // but looks like there's no other choice.
        documentBuilderFactory.setXIncludeAware(false);
        documentBuilderFactory.setExpandEntityReferences(false);
        setDocumentBuilderFactoryFeature(
                documentBuilderFactory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setDocumentBuilderFactoryFeature(
                documentBuilderFactory,
                "http://xml.org/sax/features/external-general-entities",
                false);
        setDocumentBuilderFactoryFeature(
                documentBuilderFactory,
                "http://xml.org/sax/features/external-parameter-entities",
                false);
        setDocumentBuilderFactoryFeature(
                documentBuilderFactory,
                "http://apache.org/xml/features/disallow-doctype-decl",
                true);

        return documentBuilderFactory;
    }

    private static void setDocumentBuilderFactoryFeature(
            DocumentBuilderFactory documentBuilderFactory, String feature, boolean state) {
        try {
            documentBuilderFactory.setFeature(feature, state);
        } catch (Exception e) {
            logger.log(
                    Level.WARNING,
                    String.format(
                            "Failed to set the XML Document Builder factory feature %s to %s",
                            feature, state),
                    e);
        }
    }
}
