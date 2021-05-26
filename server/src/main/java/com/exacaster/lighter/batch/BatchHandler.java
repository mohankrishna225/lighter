package com.exacaster.lighter.batch;

import static org.slf4j.LoggerFactory.getLogger;

import com.exacaster.lighter.backend.ApplicationState;
import com.exacaster.lighter.backend.Backend;
import com.exacaster.lighter.backend.Application;
import com.exacaster.lighter.backend.ApplicationBuilder;
import com.exacaster.lighter.log.LogService;
import com.exacaster.lighter.spark.SparkApp;
import io.micronaut.scheduling.annotation.Scheduled;
import java.io.IOException;
import javax.inject.Singleton;
import org.slf4j.Logger;

@Singleton
public class BatchHandler {
    private static final Logger LOG = getLogger(BatchHandler.class);

    private final Backend backend;
    private final BatchService batchService;
    private final LogService logService;

    public BatchHandler(Backend backend, BatchService batchService, LogService logService) {
        this.backend = backend;
        this.batchService = batchService;
        this.logService = logService;
    }

    public ApplicationState launch(Application application) {
        var app = new SparkApp(application.submitParams());
        try {
            app.launch(backend.getSubmitConfiguration(application));
        } catch (IOException | IllegalArgumentException e) {
            LOG.error("Error launching");
            return ApplicationState.ERROR;
        }
        return ApplicationState.STARTING;
    }

    @Scheduled(fixedRate = "1m")
    public void processScheduledBatches() {
        batchService.fetchByState(ApplicationState.NOT_STARTED)
                .forEach(batch -> {
                    LOG.info("Launching {}", batch);
                    var state = launch(batch);
                    batchService.update(ApplicationBuilder.builder(batch).state(state).build());
                });
    }

    @Scheduled(fixedRate = "2m")
    public void processNonFinalBatches() {
        batchService.fetchNonFinished()
                .forEach(batch -> {
                    backend.getInfo(batch.id()).ifPresentOrElse(info -> {
                        LOG.info("Tracking {}, info: {}", batch, info);
                        if (info.state().isComplete()) {
                            backend.getLogs(batch.id()).ifPresent(logService::save);
                        }
                        batchService.update(ApplicationBuilder.builder(batch)
                                .state(info.state())
                                .appId(info.applicationId())
                                .build());
                    }, () -> LOG.info("No info for {}", batch));
                });
    }


}
