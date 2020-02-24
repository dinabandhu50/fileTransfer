package org.mitre.synthea.helpers;

import com.google.gson.Gson;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;

public class ExportLogHelper {
    public static final String LOG_API_URL = "http://proxi-synthea-logs-api.us-east-1.elasticbeanstalk.com/v1/runs/";

    public static void sendUpdate(Map<String, Integer> counts) {
        String state = ValueStore.getState();
        int population = ValueStore.getPopulation();
        String runId = Config.get("logging.run_id");
        String instanceId = Config.get("logging.instance_id");

        try {
            Gson gson = new Gson();
            CloseableHttpClient client = HttpClients.createDefault();
            HttpPost postRequest = new HttpPost(LOG_API_URL + runId + "/updates");
            LogEntity bodyObj = new LogEntity(counts, instanceId, state, population);
            StringEntity stringEntity = new StringEntity(gson.toJson(bodyObj));
            postRequest.setEntity(stringEntity);
            postRequest.setHeader("Content-type", "application/json");
            client.execute(postRequest);
        } catch(UnsupportedEncodingException uee) {
            uee.printStackTrace();
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }
    }
}

class LogEntity {
    Map<String, Integer> counts;
    String instance;
    String state;
    int state_total;

    public LogEntity(Map<String, Integer> counts, String instance, String state, int state_total) {
        this.counts = counts;
        this.instance = instance;
        this.state = state;
        this.state_total = state_total;
    }

    public Map<String, Integer> getCounts() {
        return counts;
    }

    public void setCounts(Map<String, Integer> counts) {
        this.counts = counts;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public int getState_total() {
        return state_total;
    }

    public void setState_total(int state_total) {
        this.state_total = state_total;
    }


}
