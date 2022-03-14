package Download.Protocol;

import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.InputStream;

public class Http implements Protocols{

    private final CloseableHttpClient httpclient;
    private final String url;

    public Http(String url) {
        this.httpclient = HttpClients.createDefault();
        this.url = url;
    }

    @Override
    public boolean connect() {
        return true;
    }

    @Override
    public NetFile getContentInfo() throws IOException{
        HttpGet httpget = new HttpGet(url);
        HttpResponse httpResponse = httpclient.execute(httpget);
        int status = httpResponse.getStatusLine().getStatusCode();
        if (!(status >= 200 && status < 300)) {
            throw new ClientProtocolException("Unexpected response status: " + status);
        }
        HttpEntity httpEntity = httpResponse.getEntity();

        String filename = this.hashCode() + ".zip";

        Header contentHead = httpResponse.getFirstHeader("Content-Disposition");

        if(contentHead != null) {
            HeaderElement[] elements = contentHead.getElements();

            for (HeaderElement el : elements) {
                //遍历，获取filename
                NameValuePair pair = el.getParameterByName("filename");
                filename = pair.getValue();

                if (null != filename) {
                    break;
                }
            }
        }

        return new NetFile(httpEntity.getContentLength(), filename);
    }

    @Override
    public boolean checkMutiThread() {
        int status = 0;
        HttpGet httpget = new HttpGet(url);
        httpget.addHeader("Range", "bytes=0-0");
        try {
            HttpResponse httpResponse = httpclient.execute(httpget);
            status = httpResponse.getStatusLine().getStatusCode();
            httpResponse.getEntity().getContent().close();
        }
        catch (IOException e) {
            System.err.println(e.getMessage());
        }
        return status == 206;
    }

    @Override
    public InputStream getDownloadBlock(long start, long end) throws IOException {
        HttpGet httpget = new HttpGet(url);
        httpget.addHeader("Range", "bytes=" + start + "-" + end);
        HttpResponse httpResponse = httpclient.execute(httpget);
        HttpEntity httpEntity = httpResponse.getEntity();
        return httpEntity.getContent();
    }

    @Override
    public InputStream getAll() throws IOException {
        HttpGet httpget = new HttpGet(url);
        HttpResponse httpResponse = httpclient.execute(httpget);
        HttpEntity httpEntity = httpResponse.getEntity();
        return httpEntity.getContent();
    }

    @Override
    public int getType() {
        return Protocols.HTTP;
    }

    @Override
    public String getSource() {
        return url;
    }

}
