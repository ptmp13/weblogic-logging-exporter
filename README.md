# Weblogic Logging 2 ELK

https://github.com/oracle/weblogic-logging-exporter

## System Requirements

1. Work only on __WLS 14c+__ because in WLS 12.2.1.4 method does not have required method.
And you will get error like this:
```txt
java.lang.NoSuchMethodError: javax.ws.rs.client.ClientBuilder.connectTimeout(JLjava/util/concurrent/TimeUnit;)Ljavax/ws/rs/client/ClientBuilder;
```

2. You have installed ELK

### How to install

1. Download latest release
2. Create config for logstash
```yaml
input {
        http {
                port => 13000
                id => "weblogic-domain-logs"
        }
}

output {
        elasticsearch {
                hosts => [ "1.1.1.1:9200","1.1.1.2:9200" ]
                action => "create"
                index => "wlslogs_%{domainUID}"
                user => "logstash_internal"
                manage_template => "false"
                password => "AZAZAZ"
                retry_max_interval => 2
                timeout => 10
        }
}
```

Index in Elasticseasrch will be **%{domainUID}_logs**
where domainUID - you weblogic domain name (get from config file).

3. Create  *WebLogicLoggingExporter.yaml* in $DOMAIN_HOME/config:

```yaml
publishHost: logstash.local
publishPort: 13000
domainUID: stb_domain
weblogicLoggingExporterEnabled: true
weblogicLoggingIndexName: stb_domain_logs
weblogicLoggingExporterSeverity: Debug
weblogicLoggingExporterBulkSize: 0
```

Domain name in __lowercase__

4. Create template in Elasticsearch
```json
PUT _index_template/wldomain_template
{
  "priority": 8,
  "template": {
    "mappings": {
      "properties": {
        "severity": {
          "type": "keyword"
        },
        "sequenceNumber": {
          "type": "keyword"
        },
        "subSystem": {
          "type": "keyword"
        },
        "level": {
          "type": "keyword"
        },
        "messageID": {
          "type": "keyword"
        },
        "serverName": {
          "type": "keyword"
        },
        "domainUID": {
          "type": "keyword"
        },
        "userId": {
          "type": "keyword"
        },
        "machineName": {
          "type": "keyword"
        },
        "threadName": {
          "type": "keyword"
        },
        "transactionId": {
          "type": "keyword"
        },
        "loggerName": {
          "type": "keyword"
        },
        "timestamp": {
          "type": "date"
        }
      }
    }
  },
  "index_patterns": [
    "wlslogs_*"
  ],
  "data_stream": {
    "hidden": false
  }
}
```

5. Create startup class 

Modify connect command for you Weblogic
```python
connect('weblogic','Welcome1','t3://127.0.0.1:8080')
edit()
startEdit()
cd('/')
cmo.createStartupClass('elk-wls')

cd('/StartupClasses/elk-wls')
cmo.setClassName('weblogic.logging.exporter.Startup')
set('Targets',jarray.array([ObjectName('com.bea:Name=AdminServer,Type=Server')], ObjectName))

activate()
exit()
```

In the Administration Console, navigate to "Environment" then "Startup and Shutdown classes" in the main menu. Add Targets for _elk-wls_

6. Mkdir app in domain home add jar
```bash
mkdir $DOMAIN_HOME/app
cp weblogic-logging-exporter-jar-with-dependencies.jar $DOMAIN_HOME/app
cd $DOMAIN_HOME/bin

## ADD CLASSPATH to setUserOverrides.sh
cat > setUserOverrides.sh<<EOF
CLASSPATH="$DOMAIN_HOME/app/weblogic-logging-exporter-jar-with-dependencies.jar:$CLASSPATH"
EOF
```

P.S May be i forget something... write me if...