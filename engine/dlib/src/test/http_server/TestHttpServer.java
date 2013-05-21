import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.*;
import java.io.*;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.bio.SocketConnector;

public class TestHttpServer extends AbstractHandler
{
    Pattern m_AddPattern = Pattern.compile("/add/(\\d+)/(\\d+)");
    Pattern m_ArbPattern = Pattern.compile("/arb/(\\d+)");
    Pattern m_CachedPattern = Pattern.compile("/cached/(\\d+)");
    Pattern m_EchoPattern = Pattern.compile("/echo/(.*)");
    public TestHttpServer()
    {
        super();
    }

    private void sendFile(String target, HttpServletResponse response) throws IOException {
        Reader r = new FileReader(target.substring(1));
        char[] buf = new char[1024 * 128];
        int n = r.read(buf);
        if (n > 0) {
            response.getWriter().print(new String(buf, 0, n));
        }
        // NOTE: We flush here to force chunked encoding
        response.getWriter().flush();
        r.close();
    }

    private static char convertDigit(int value) {

        value &= 0x0f;
        if (value >= 10)
            return ((char) (value - 10 + 'a'));
        else
            return ((char) (value + '0'));
    }

    public static String toHex(byte bytes[]) {
        StringBuffer sb = new StringBuffer(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(convertDigit((int) (bytes[i] >> 4)));
            sb.append(convertDigit((int) (bytes[i] & 0x0f)));
        }
        return (sb.toString());
    }

    private static String calculateSHA1(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            BufferedInputStream is = new BufferedInputStream(
                    new FileInputStream(file));
            byte[] buffer = new byte[1024];
            int n = is.read(buffer);
            while (n != -1) {
                md.update(buffer, 0, n);
                n = is.read(buffer);
            }
            is.close();
            return toHex(md.digest());

        } catch (IOException e) {
            return null;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }


    public void handle(String target,
                       Request baseRequest,
                       HttpServletRequest request,
                       HttpServletResponse response)
        throws IOException, ServletException
    {
        Matcher addm = m_AddPattern.matcher(target);
        Matcher arbm = m_ArbPattern.matcher(target);
        Matcher cachedm = m_CachedPattern.matcher(target);
        Matcher echom = m_EchoPattern.matcher(target);

        if (target.equals("/"))
        {
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
        }
        else if (target.equals("/__verify_etags__")) {
            baseRequest.setHandled(true);
            if (!request.getMethod().equals("POST")) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST );
                return;
            }

            StringBuffer responseBuffer = new StringBuffer();

            BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()));
            String line = reader.readLine();
            while (line != null) {
                int i = line.indexOf(' ');
                URI uri;
                try {
                    uri = new URI(URLDecoder.decode(line.substring(0, i), "UTF-8"));
                    uri = uri.normalize(); // http://foo.com//a -> http://foo.com/a
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                String etag = line.substring(i + 1);

                String actualETag = String.format("W/\"" + calculateSHA1(new File(uri.getPath().substring(1))) + "\"");

                if (etag.equals(actualETag)) {
                    responseBuffer.append(line.substring(0, i));
                    responseBuffer.append('\n');
                }

                line = reader.readLine();
            }
            reader.close();

            response.getWriter().print(responseBuffer);
            response.setStatus(HttpServletResponse.SC_OK);

        }
        else if (cachedm.matches())
        {
            String id = cachedm.group(1);
            String tag = String.format("W/\"A TAG " + id + "\"");
            response.setHeader(HttpHeaders.ETAG, tag);
            int status = HttpServletResponse.SC_OK;

            String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
            if (ifNoneMatch != null) {
                if (ifNoneMatch.equals(tag)) {
                    baseRequest.setHandled(true);
                    status = HttpStatus.NOT_MODIFIED_304;
                }
            }

            response.setStatus(status);
            baseRequest.setHandled(true);
            if (status == HttpServletResponse.SC_OK)
                response.getWriter().print("cached_content");
        }
        else if (target.startsWith("/tmp/http_files"))
        {
            String tag = String.format("W/\"" + calculateSHA1(new File(target.substring(1))) + "\"");
            response.setHeader(HttpHeaders.ETAG, tag);
            int status = HttpServletResponse.SC_OK;

            String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
            if (ifNoneMatch != null) {
                if (ifNoneMatch.equals(tag)) {
                    baseRequest.setHandled(true);
                    status = HttpStatus.NOT_MODIFIED_304;
                }
            }

            response.setStatus(status);
            baseRequest.setHandled(true);
            if (status == HttpServletResponse.SC_OK) {
                sendFile(target, response);
            }
        }

        else if (addm.matches())
        {
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
            String xscaleStr = request.getHeader("X-Scale");
            int xscale = 1;
            if (xscaleStr != null) {
            	xscale = Integer.parseInt(xscaleStr);
            }
            int sum = Integer.parseInt(addm.group(1)) + Integer.parseInt(addm.group(2));
            response.getWriter().println(sum * xscale);
        }
        else if (arbm.matches())
        {
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);

            int n = Integer.parseInt(arbm.group(1));
            // NOTE: If content lenght is not set here chunked transfer encoding might be enabled (Transfer-Encoding: chunked)
            response.setContentLength(n);

            byte[] buffer = new byte[n];

            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < n; ++i)
                buffer[i] = (byte) ((i % 255) & 0xff);
            response.getOutputStream().write(buffer);
        }
        else if (target.equals("/src/test/data/test.config")) {
            // For dmConfigFile test
            response.setStatus(HttpServletResponse.SC_OK);
            baseRequest.setHandled(true);
            sendFile(target, response);
        }
        else if (target.equals("/post")) {
            baseRequest.setHandled(true);
            if (!request.getMethod().equals("POST")) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST );
                return;
            }

            InputStream is = request.getInputStream();
            byte[] buf = new byte[1024];
            int n = is.read(buf);
            int sum = 0;
            while (n != -1) {
                for (int i = 0; i < n; ++i) {
                    sum += buf[i];
                }
                n = is.read(buf);
            }
            is.close();

            response.getWriter().println(sum);
            response.setStatus(HttpServletResponse.SC_OK);
        } else if (echom.matches()) {
            String s = echom.group(1);
            s = URLDecoder.decode(s, "UTF-8");
            baseRequest.setHandled(true);
            response.getWriter().print(s);
            response.setStatus(HttpServletResponse.SC_OK);
        }
        // No match? Let ResourceHandler handle the request. See setup code.
    }

    public static void main(String[] args) throws Exception
    {
        try
        {
            Server server = new Server();
            SocketConnector connector = new SocketConnector();
            connector.setMaxIdleTime(100);
            connector.setPort(7000);
            server.addConnector(connector);
            HandlerList handlerList = new HandlerList();
            handlerList.addHandler(new TestHttpServer());
            ResourceHandler resourceHandler = new ResourceHandler() {

                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                    if (baseRequest.isHandled())
                        return;

                    String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
                    if (ifNoneMatch != null) {
                        Resource resource = getResource(request);
                        if (resource != null && resource.exists()) {
                            File file = resource.getFile();
                            if (file != null) {
                                String thisEtag = String.format("%d", file.lastModified());
                                if (ifNoneMatch.equals(thisEtag)) {
                                    baseRequest.setHandled(true);
                                    response.setHeader(HttpHeaders.ETAG, thisEtag);
                                    response.setStatus(HttpStatus.NOT_MODIFIED_304);
                                    return;
                                }
                            }
                        }
                    }

                    super.handle(target, baseRequest, request, response);
                }

                @Override
                protected void doResponseHeaders(HttpServletResponse response,
                        Resource resource,
                        String mimeType) {
                    super.doResponseHeaders(response, resource, mimeType);
                    try {
                        File file = resource.getFile();
                        if (file != null) {
                            response.setHeader(HttpHeaders.ETAG, String.format("%d", file.lastModified()));
                        }
                    }
                    catch(IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
            resourceHandler.setResourceBase(".");
            handlerList.addHandler(resourceHandler);
            server.setHandler(handlerList);

            server.start();
            Thread.sleep(1000 * 400);
            System.out.println("ERROR: HTTP server wasn't terminated by the tests after 400 seconds. Quiting...");
            System.exit(1);
        }
        catch (Throwable e)
        {
            System.out.println(e);
            System.exit(1);
        }
    }
}
