package hudson.plugins.swarm;

import hudson.remoting.Launcher;
import hudson.remoting.jnlp.Main;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.CmdLineException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;

/**
 * Swarm client.
 *
 * <p>
 * Discovers nearby Hudson via UDP broadcast, and pick eligible one randomly and joins it.
 *
 * @author Kohsuke Kawaguchi
 */
public class Client {
    /**
     * Used to discover the server.
     */
    protected final DatagramSocket socket;

    /**
     * The Hudson that we are trying to connect to.
     */
    protected Candidate target;

    @Option(name="-name",usage="Name of the slave")
    public String name;

    @Option(name="-description",usage="Description to be put on the slave")
    public String description;

    @Option(name="-labels",usage="Whitespace-separated list of labels to be assigned for this slave")
    public String labels;

    @Option(name="-fsroot",usage="Directory where Hudson places files")
    public File remoteFsRoot = new File(".");

    @Option(name="-executors",usage="Number of executors")
    public int executors = Runtime.getRuntime().availableProcessors();

    @Option(name="-master",usage="Host name or IP address of the master. If this option is specified, auto-discovery will be skipped")
    public String master;

    @Option(name="-help",aliases="--help",usage="Show the help screen")
    public boolean help;

    public static void main(String... args) throws InterruptedException, IOException {
        Client client = new Client();
        CmdLineParser p = new CmdLineParser(client);
        try {
            p.parseArgument(args);
        } catch (CmdLineException e) {
            System.out.println(e.getMessage());
            p.printUsage(System.out);
            System.exit(-1);
        }
        if(client.help) {
            p.printUsage(System.out);
            System.exit(0);
        }
        client.run();
    }

    public Client() throws IOException {
        socket = new DatagramSocket();
        socket.setBroadcast(true);
        name = InetAddress.getLocalHost().getCanonicalHostName();
    }

    class Candidate {
        final String url;
        final String secret;

        Candidate(String url, String secret) {
            this.url = url;
            this.secret = secret;
        }
    }

    /**
     * Finds a Hudson master that supports swarming, and join it.
     *
     * This method never returns.
     */
    public void run() throws InterruptedException {
        System.out.println("Discovering Hudson master");

        // wait until we get the ACK back
        while(true) {
            try {
                List<Candidate> candidates = new ArrayList<Candidate>();
                for (DatagramPacket recv : discover()) {
                    String responseXml = new String(recv.getData(), 0, recv.getLength());

                    Document xml;
                    try {
                        xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                            new ByteArrayInputStream(recv.getData(), 0, recv.getLength()));
                    } catch (SAXException e) {
                        System.out.println("Invalid response XML from "+recv.getAddress()+": "+ responseXml);
                        continue;
                    }
                    String swarm = getChildElementString(xml.getDocumentElement(), "swarm");
                    if(swarm==null) {
                        System.out.println(recv.getAddress()+" doesn't support swarm");
                        continue;
                    }
                    String url = getChildElementString(xml.getDocumentElement(), "url");
                    if(url==null) {
                        System.out.println(recv.getAddress()+" doesn't have the configuration set yet. Please go to the sytem configuration page of this Hudson and submit it: "+ responseXml);
                        continue;
                    }
                    candidates.add(new Candidate(url,swarm));
                }

                if(candidates.size()==0)
                    throw new RetryException("No nearby Hudson supports swarming");

                System.out.println("Found "+candidates.size()+" eligible Hudson.");
                // randomly pick up the Hudson to connect to
                target = candidates.get(new Random().nextInt(candidates.size()));


                verifyThatUrlIsHudson();

                // create a new swarm slave
                createSwarmSlave();
                connect();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ParserConfigurationException e) {
                e.printStackTrace();
            } catch (RetryException e) {
                System.out.println(e.getMessage());
                if(e.getCause()!=null)
                    e.getCause().printStackTrace();
            }
            
            // retry
            System.out.println("Retrying in 10 seconds");
            Thread.sleep(10*1000);
        }

    }

    /**
     * Discovers Hudson running nearby.
     *
     * To give every nearby Hudson a fair chance, wait for some time until we hear all the responses.
     */
    protected List<DatagramPacket> discover() throws IOException, InterruptedException, RetryException {
        sendBroadcast();

        List<DatagramPacket> responses = new ArrayList<DatagramPacket>();

        // wait for 5 secs to gather up all the replies
        long limit = System.currentTimeMillis()+5*1000;
        while(true) {
            try {
                socket.setSoTimeout(Math.max(1,(int)(limit-System.currentTimeMillis())));

                DatagramPacket recv = new DatagramPacket(new byte[2048], 2048);
                socket.receive(recv);
                responses.add(recv);
            } catch (SocketTimeoutException e) {
                // timed out
                if(responses.isEmpty())
                    throw new RetryException("Failed to receive a reply to broadcast.");
                return responses;
            }
        }
    }

    protected void sendBroadcast() throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[0], 0);
        packet.setAddress(InetAddress.getByName(master!=null ? master : "255.255.255.255"));
        packet.setPort(Integer.getInteger("hudson.udp",33848));
        socket.send(packet);
    }

    protected void connect() throws InterruptedException {
        try {
            Launcher launcher = new Launcher();
            launcher.slaveJnlpURL = new URL(target.url+"/computer/"+name+"/slave-agent.jnlp");
            List<String> jnlpArgs = launcher.parseJnlpArguments();
            jnlpArgs.add("-noreconnect");
            Main.main(jnlpArgs.toArray(new String[jnlpArgs.size()]));
        } catch (Exception e) {
            System.out.println("Failed to establish JNLP connection to "+target.url);
            Thread.sleep(10*1000);
        }
    }

    protected void createSwarmSlave() throws IOException, InterruptedException, RetryException {
        HttpURLConnection con = (HttpURLConnection)new URL(target.url + "/plugin/swarm/createSlave?name=" + name +
                "&executors=" + executors +
                param("remoteFsRoot",remoteFsRoot.getAbsolutePath()) +
                param("description",description)+
                param("labels", labels)+
                "&secret=" + target.secret).openConnection();
        if(con.getResponseCode()!=200) {
            copy(con.getErrorStream(),System.out);
            throw new RetryException("Failed to create a slave on Hudson: "+con.getResponseCode()+" "+con.getResponseMessage());
        }
    }

    private String param(String name, String value) throws UnsupportedEncodingException {
        if(value==null) return "";
        return "&"+name+"="+ URLEncoder.encode(value,"UTF-8");
    }

    protected void verifyThatUrlIsHudson() throws InterruptedException, RetryException {
        try {
            System.out.println("Connecting to "+target.url);
            HttpURLConnection con = (HttpURLConnection)new URL(target.url).openConnection();
            con.connect();
            String v = con.getHeaderField("X-Hudson");
            if(v==null)
                throw new RetryException("This URL doesn't look like Hudson.");
        } catch (IOException e) {
            throw new RetryException("Failed to connect to "+target.url,e);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) >= 0)
            out.write(buf, 0, len);
    }

    private static String getChildElementString(Element parent, String tagName) {
        for (Node n=parent.getFirstChild(); n!=null; n=n.getNextSibling()) {
            if (n instanceof Element) {
                Element e = (Element) n;
                if(e.getTagName().equals(tagName)) {
                    StringBuilder buf = new StringBuilder();
                    for (n=e.getFirstChild(); n!=null; n=n.getNextSibling()) {
                        if(n instanceof Text)
                            buf.append(n.getTextContent());
                    }
                    return buf.toString();
                }
            }
        }
        return null;
    }
}
