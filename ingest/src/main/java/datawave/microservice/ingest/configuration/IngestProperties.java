package datawave.microservice.ingest.configuration;

import datawave.microservice.config.accumulo.AccumuloProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;
import java.util.List;

@Validated
@EnableConfigurationProperties(IngestProperties.class)
@ConfigurationProperties(prefix = "ingest")
public class IngestProperties {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    
    private List<String> fsConfigResources;
    
    @NotNull
    private boolean liveIngest;
    
    @NotNull
    private AccumuloProperties accumuloProperties;
    
    public List<String> getFsConfigResources() {
        return fsConfigResources;
    }
    
    public void setFsConfigResources(List<String> fsConfigResources) {
        this.fsConfigResources = fsConfigResources;
        log.info("Got resources: " + fsConfigResources);
    }
    
    public AccumuloProperties getAccumuloProperties() {
        return accumuloProperties;
    }
    
    public void setAccumuloProperties(AccumuloProperties accumuloProperties) {
        this.accumuloProperties = accumuloProperties;
    }
    
    public boolean isLiveIngest() {
        return liveIngest;
    }
    
    public void setLiveIngest(boolean liveIngest) {
        this.liveIngest = liveIngest;
    }
}
