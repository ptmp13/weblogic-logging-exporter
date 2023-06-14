// Copyright (c) 2018, 2021, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package weblogic.logging.exporter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.jfrog.build.extractor.clientConfiguration.ClientProperties;
import weblogic.diagnostics.logging.LogVariablesImpl;
import weblogic.diagnostics.query.QueryException;
import weblogic.i18n.logging.Severities;
import weblogic.logging.WLLevel;
import weblogic.logging.WLLogRecord;
import weblogic.logging.exporter.config.Config;
import weblogic.logging.exporter.config.FilterConfig;

import static java.util.concurrent.TimeUnit.SECONDS;

@SuppressWarnings("UnnecessaryContinue")
class   LogExportHandler extends Handler {

  private static final String DOC_TYPE = "doc";
  private static final String INDEX = " { \"index\" : { }} ";
  private static final int offValue = Level.OFF.intValue();
//  private final Client httpClient = ClientBuilder.newClient();
  private final Client httpClient = ClientBuilder.newBuilder()
          .connectTimeout(10, SECONDS)
          .readTimeout(2, SECONDS)
          .build();
  //some more calls if necesary, e.g.
  //.register(LoggingFilter.class);

  //private final Client httpClient = builder.build();
  private List<FilterConfig> filterConfigs = new ArrayList<>();
  private final List<String> payloadBulkList = new ArrayList<>();

  //
  //  These will all be set by initialize()
  //
  private String indexName;
  private String publishHost;
  private int publishPort;
  private int bulkSize;
  private String httpHostPort;
  private String singleURL;
  private String bulkURL;
  private String fluentdURL;
  private String domainUID;
  private String destination;
  private String projectname;
  private String env;
  private String containerid;

  public LogExportHandler(Config config) {
    initialize(config);
//    createMappings();
  }

  @Override
  public void publish(LogRecord record) {
    WLLogRecord wlLogRecord = (WLLogRecord) record;
    if (!isLoggable(record)) {
      return;
    }
    String payload = recordToPayload(wlLogRecord);
    if (bulkSize <= 1) {
      Result result = executePutOrPostOnUrl(singleURL, payload, true);
      if (!result.successful) {
        System.out.println(
            "<weblogic.logging.exporter.LogExportHandler> logging of "
                + payload
                + " got result "
                + result);
      }
    } else {
      payloadBulkList.add(payload);
      if (payloadBulkList.size() >= bulkSize) {
        writeOutAllRecords();
      }
    }
  }

  @Override
  public void flush() {
    writeOutAllRecords();
  }

  @Override
  public void close() throws SecurityException {}

  @Override
  public boolean isLoggable(LogRecord logEntry) {
    final int levelValue = getLevel().intValue();
    if (logEntry.getLevel().intValue() < levelValue || levelValue == offValue) {
      return false;
    }
    for (FilterConfig oneConfig : filterConfigs) {

      List<String> servers = oneConfig.getServers();
      if (servers.size() == 0) {
        if (oneConfig.getQuery() != null) {
          if (applyFilter(oneConfig, (WLLogRecord) logEntry, null)) {
            continue;
          } else {
            return false;
          }
        }
      } else {
        for (String server : servers) {
          if (applyFilter(oneConfig, (WLLogRecord) logEntry, server)) {
            continue;
          } else {
            return false;
          }
        }
      }
    }
    return true;
  }

  private boolean applyFilter(FilterConfig oneConfig, WLLogRecord logEntry, String serverName) {
    try {
      if ((serverName == null) || (serverName.equals(logEntry.getServerName()))) {
        return oneConfig
            .getQuery()
            .executeQuery(LogVariablesImpl.getInstance().getLogVariablesResolver(logEntry));
      } else {
        return true;
      }
    } catch (QueryException ex) {
      // if there is any error with this expression.
      // TODO: give warning ?
      return true;
    }
  }

  private String dataAsJson(String fieldName, String data) {
    //return "\"" + fieldName + "\": \"" + data.replace("\"", "\\\"") + "\"";
    return "\"" + fieldName + "\": \"" + data.replace("\"", "\\\"").replaceAll("[\\t\\n\\r]+","\\\\n") + "\"";
  }

  private String dataAsJson(String fieldName, long data) {
    return "\"" + fieldName + "\": " + data;
  }

  private void writeOutAllRecords() {
    StringBuilder buffer = new StringBuilder();
    for (String oneRecord : payloadBulkList) {
      buffer.append(INDEX);
      buffer.append("\n");
      buffer.append(oneRecord);
      buffer.append("\n");
    }
    payloadBulkList.clear();
    Result result = executePutOrPostOnUrl(bulkURL, buffer.toString(), true);
    if (!result.successful) {
      System.out.println(
          "<weblogic.logging.exporter.LogExportHandler> logging of "
              + buffer.toString()
              + " got result "
              + result);
    }
  }

  private Result executePutOrPostOnUrl(String url, String payload, boolean post) {
//    httpClient.property(ClientProperties.PROP_TIMEOUT,1000);
//    httpClient.property(ClientProperties.READ_TIMEOUT,1000);
    try {
      WebTarget target = httpClient.target(url);
      Invocation.Builder invocationBuilder = target.request().accept("application/json");
      Response response =
              post
                      ? invocationBuilder.post(Entity.json(payload))
                      : invocationBuilder.put(Entity.json(payload));
      String responseString = null;
      int status = response.getStatus();
      boolean successful = false;
      if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
        successful = true;
        if (response.hasEntity()) {
          responseString = String.valueOf(response.readEntity(String.class));
        }
      }
      return new Result(responseString, status, successful);
    }
    catch (Exception ex) { return new Result("", 500, false); }
  }

  private String recordToPayload(WLLogRecord wlLogRecord) {
    return "{"
        + dataAsJson("messageID", wlLogRecord.getId())
        + ","
        + dataAsJson("message", wlLogRecord.getMessage())
        + ","
        + dataAsJson("timestamp", wlLogRecord.getMillis())
        + ","
        + dataAsJson("serverName", wlLogRecord.getServerName())
        + ","
        + dataAsJson("threadName", wlLogRecord.getThreadName())
        + ","
        + dataAsJson("severity", wlLogRecord.getSeverityString())
        + ","
        + dataAsJson("userId", wlLogRecord.getUserId())
        + ","
        + dataAsJson("level", wlLogRecord.getLevel().toString())
        + ","
        + dataAsJson("loggerName", wlLogRecord.getLoggerName())
        + ","
        + dataAsJson("formattedDate", wlLogRecord.getFormattedDate())
        + ","
        + dataAsJson("subSystem", wlLogRecord.getSubsystem())
        + ","
        + dataAsJson("machineName", wlLogRecord.getMachineName())
        + ","
        + dataAsJson("transactionId", wlLogRecord.getTransactionId())
        + ","
        + dataAsJson("diagnosticContextId", wlLogRecord.getDiagnosticContextId())
        + ","
        + dataAsJson("sequenceNumber", wlLogRecord.getSequenceNumber())
        + ","
        + dataAsJson("domainUID", domainUID)
        + ","
        + dataAsJson("projectname", projectname)
        + ","
        + dataAsJson("env", env)
        + ","
        + dataAsJson("container.id", containerid)
        + ","
        + dataAsJson("host.name", wlLogRecord.getMachineName())
        + "}";
  }

  private void initialize(Config config) {
    String filePathString="/.dockerenv";
    File f = new File(filePathString);
    if(f.exists() && !f.isDirectory()) {
      ProcessBuilder processBuilder = new ProcessBuilder();
      processBuilder.command("bash", "-c", "awk -F\"/\" '/1:name/ {print $3}' /proc/self/cgroup");
      try {
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));

        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line + "\n");
        }

        int exitVal = process.waitFor();
        if (exitVal == 0) {
          System.out.println("Success!");
          System.out.println(output.toString());
          //System.exit(0);
          containerid = output.toString();
        } else {
          containerid = "NONE";
        }

      } catch (IOException e) {
        e.printStackTrace();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    else {
      containerid = "NONE";
    }

    publishHost = config.getHost();
    publishPort = config.getPort();
    @SuppressWarnings("unused")
    boolean enabled = config.getEnabled();
    String severity = config.getSeverity();
    if (severity != null) {
      setLevel(WLLevel.getLevel(Severities.severityStringToNum(severity)));
    }
    indexName = config.getIndexName();
    bulkSize = config.getBulkSize();
    filterConfigs = config.getFilterConfigs();
    httpHostPort = "http://" + publishHost + ":" + publishPort;
    singleURL = httpHostPort + "/" + indexName + "/" + DOC_TYPE + "/?pretty";
    bulkURL = httpHostPort + "/" + indexName + "/" + DOC_TYPE + "/_bulk?pretty";
    domainUID = config.getDomainUID();

    projectname = System.getProperty("projectname");
    env = System.getProperty("env");
    //
    //  Set up the publishing variables...
    //

    httpHostPort = "http://" + publishHost + ":" + publishPort;
    singleURL = httpHostPort + "/" + indexName + "/" + DOC_TYPE + "/?pretty";
    bulkURL = httpHostPort + "/" + indexName + "/" + DOC_TYPE + "/_bulk?pretty";
    fluentdURL = httpHostPort + "/" + indexName;
  }

  private void createMappings() {
    // create mapping for wls elasticsearch document
    final String mappings =
        "{"
            + "  \"mappings\": {"
            + "    \""
            + DOC_TYPE
            + "\": {"
            + "      \"properties\": {"
            + "        \"timestamp\": {"
            + "\"type\": \"date\" "
            + "},"
            + "        \"sequenceNumber\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"severity\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"level\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"serverName\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"threadName\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"userId\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"loggerName\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"subSystem\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"machineName\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"transactionId\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"messageID\": {"
            + "\"type\": \"keyword\" "
            + "},"
            + "        \"domainUID\": {"
            + "\"type\": \"keyword\" "
            + "}"
            + "      }"
            + "    }"
            + "  }"
            + "}";

    Result result = executePutOrPostOnUrl(httpHostPort + "/" + indexName, mappings, false);
    if (!result.successful) {
      //noinspection StatementWithEmptyBody
      if (result.getStatus() == HttpURLConnection.HTTP_BAD_REQUEST) {
        // ignore.  this is the case where the index has been created in elastic search.
      } else {
        System.out.println(
            "<weblogic.logging.exporter.LogExportHandler> issue of "
                + mappings
                + " got result "
                + result);
      }
    }
  }
}
