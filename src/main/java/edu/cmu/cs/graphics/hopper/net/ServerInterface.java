package edu.cmu.cs.graphics.hopper.net;

import com.google.gson.Gson;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;

/**
 * Used for sending gameplay data off to remote server for analysis
 */
public class ServerInterface {
    protected String hostURI;
    protected int hostPort;
    protected HttpClient session;
    protected Gson gsonSend;
    protected Gson gsonReceive;

    public ServerInterface(String hostURI, int hostPort) {
        this.hostURI = hostURI;
        this.hostPort = hostPort;

        session = new DefaultHttpClient();
        gsonSend = GsonUtils.getCrowdAnimSenderGson();
        gsonReceive = GsonUtils.getCrowdAnimReceiverGson();
    }

    public void sendTestMsg() {
        HttpPost msg;
        try {
            msg = new HttpPost(new URI("http", null, hostURI, hostPort, "/test", "", "anchor"));
        }
        catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }

        try {
            HttpResponse response = session.execute(msg);
            HttpEntity respEntity = response.getEntity();

            if (respEntity != null) {
                String content =  EntityUtils.toString(respEntity);
                System.out.println("Test response: " + content);
            }
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
