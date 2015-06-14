/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package automenta.netention.net.proxy;


import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.HttpString;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Proxy server which caches requests and their data to files on disk
 * TODO broken since removing async-http-client which depended on netty 3 (we are using netty4 in other dependencies)
 */
public class CachingProxyServer extends PathHandler {

    public static final Logger logger = LoggerFactory.getLogger(CachingProxyServer.class);


    //public final Proxy proxy;
    final String cachePath;

//    public static void main(String[] args) throws Exception {
//        new CachingProxyServer(8080, "cache");
//    }

    final static int threads = 8;
    final ExecutorService executor = Executors.newFixedThreadPool(threads);
    //public final Undertow.Builder web;

    public static String decodeURIComponent(String s) {
        if (s == null) {
            return null;
        }

        String result = null;

        try {
            result = URLDecoder.decode(s, "UTF-8");
        }

        // This exception should never occur.
        catch (UnsupportedEncodingException e) {
            result = s;
        }

        return result;
    }

    public CachingProxyServer(String cachePath) {
        this.cachePath = cachePath + "/";
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        String url = exchange.getQueryString();

        if (url == null) return;

        url = decodeURIComponent(url);

        logger.info("request: " + url);

        CachedURL response = null;

        //attempt to read, or if expired, just get the newest
        try {
            response = get(url);
        } catch (Exception e) {
        }

        boolean write = true;

        if (response == null) {

            logger.debug("cache miss: " + url);

            response = executor.submit(
                    new Request(
                            new URL(url)
                    )
            ).get();

            logger.info("cache set: " + response.content.length + " bytes");

            write = true;

        } else {
            logger.info("cache hit: " + url);
        }

        if (response == null) {
            logger.error("undownloaded: " + url);
            exchange.setResponseCode(404);
        } else {

            exchange.setResponseCode(response.responseCode);
            for (String[] x : response.responseHeader) {
                exchange.getResponseHeaders().add(new HttpString(x[0]), x[1]);
            }
            exchange.getResponseSender().send(ByteBuffer.wrap(response.content));

            if (logger.isDebugEnabled())
                logger.debug("sending client cached " + response.content.length + " bytes");
        }

        if (write && response!=null) {
            try {
                put(decodeURIComponent(url.toString()), response);
            }
            catch (Exception e) {
                logger.error(e.toString());
            }
        }
    }

//    public void run() {
//        logger.info("Web server start:, saving to: " + cachePath);
//        web.build().start();
//    }

    public synchronized void put(String uri, CachedURL c) throws IOException {


        File f = getCacheFile(uri, true);
        logger.info("Caching " + uri + " to" + f.getAbsolutePath());

        ObjectOutputStream ff = new ObjectOutputStream(new FileOutputStream(f));
        ff.writeObject(c);
        ff.close();

        File g = getCacheFile(uri, false);
        FileOutputStream gg = new FileOutputStream(g);
        IOUtils.write(c.content, gg);
        gg.close();

        //cache.put(pedido.URI, f.getAbsolutePath());

    }

    public File getCacheFile(String filename, boolean header) throws UnsupportedEncodingException {
        if (filename.startsWith("https://")) filename = filename.substring(8);
        if (filename.startsWith("http://")) filename = filename.substring(7);
        if (filename.startsWith("http:/")) filename = filename.substring(6);

        filename = URLEncoder.encode(filename, "UTF-8");

        filename = filename.replace("%2F", "/"); //restore '/' for directory seprataion.
        filename = filename.replace("%26", "/&"); //separate at '&' also
        filename = filename.replace("%3F", "/?"); //separate at '&' also
        filename = filename.replace("%3D", "="); //restore '=' this is valid in unix filename
        filename = filename.replace("%2C", ","); //restore ',' this is valid in unix filename
        filename = filename.replace("%3A", ":"); //restore ',' this is valid in unix filename


        String target = cachePath + filename + (header ? ".h" : "");

        File f = new File(target);

        File parent = f.getParentFile();
        parent.mkdirs();

        return f;
    }

    public synchronized CachedURL get(String u) throws Exception {


        File header = getCacheFile(u, true);
        ObjectInputStream deficheiro = new ObjectInputStream(new FileInputStream(header));

        CachedURL x = (CachedURL) deficheiro.readObject();
        x.content = new byte[x.size];

        File content = getCacheFile(u, false);
        final FileInputStream cc = new FileInputStream(content);
        IOUtils.readFully(cc, x.content);
        cc.close();

        return x;
    }

    public static class CachedURL implements Serializable {

        transient public byte[] content; //stored separately

        public int responseCode;
        //public List<String[]> requestHeader;
        public List<String[]> responseHeader;
        public int size;

        public CachedURL() {

        }

        public CachedURL(Header[] responseHeaders, int responseCode, byte[] content) {
            responseHeader = new ArrayList(responseHeaders.length);

            for (Header h : responseHeaders) {
                responseHeader.add(new String[]{h.getName(), h.getValue()});
            }
            this.responseCode = responseCode;
            this.content = content;
            this.size = content.length;
        }
    }

//
//    public static void setHeader(HttpServerExchange exchange, FluentCaseInsensitiveStringsMap response) {
//        for (Map.Entry<String, List<String>> entry : response.entrySet()) {
//            if (!entry.getValue().isEmpty()) {
//                exchange.getResponseHeaders().put(new HttpString(entry.getKey()),
//                        entry.getValue().iterator().next());
//            }
//        }
//    }

    public class Request implements Callable<CachedURL> {
        int responseCode;
        private URL url;
        private CachedURL curl;
        public HttpResponse response;

        public Request(URL url) throws URISyntaxException {
            this.url = url;
        }

        @Override
        public CachedURL call() throws Exception {

            //

            CloseableHttpClient httpclient = HttpClients.createDefault();
            byte[] data;
            try {
                HttpGet httpget = new HttpGet(url.toURI());

                logger.info("HTTP GET " + httpget.getRequestLine());

                // Create a custom response handler
                ResponseHandler<byte[]> responseHandler = new ResponseHandler<byte[]>() {
                    ;

                    @Override
                    public byte[] handleResponse(final HttpResponse hresponse) throws ClientProtocolException, IOException {
                        response = hresponse;
                        responseCode = hresponse.getStatusLine().getStatusCode();
                        if (responseCode >= 200 && responseCode < 300) {
                            HttpEntity entity = hresponse.getEntity();
                            return entity != null ? EntityUtils.toByteArray(entity) : null;
                        } else {
                            return null;
                        }
                    }

                };
                data = httpclient.execute(httpget, responseHandler);

            } finally {
                httpclient.close();
            }


            if (data != null) {
                this.curl = new CachedURL(response.getAllHeaders(), responseCode, data);
            }

            return curl;
        }
    }

}
