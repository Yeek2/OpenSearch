/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.dataframe.process;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.ml.dataframe.process.results.AnalyticsResult;
import org.elasticsearch.xpack.ml.process.ProcessPipes;
import org.elasticsearch.xpack.ml.process.StateToProcessWriterHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class NativeAnalyticsProcess extends AbstractNativeAnalyticsProcess<AnalyticsResult> {

    private static final Logger logger = LogManager.getLogger(NativeAnalyticsProcess.class);

    private static final String NAME = "analytics";

    private final AnalyticsProcessConfig config;

    protected NativeAnalyticsProcess(String jobId, ProcessPipes processPipes,
                                     int numberOfFields, List<Path> filesToDelete,
                                     Consumer<String> onProcessCrash, Duration processConnectTimeout, AnalyticsProcessConfig config,
                                     NamedXContentRegistry namedXContentRegistry) {
        super(NAME, AnalyticsResult.PARSER, jobId, processPipes, numberOfFields,
            filesToDelete, onProcessCrash, processConnectTimeout, namedXContentRegistry);
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void persistState() {
        // Nothing to persist
    }

    @Override
    public void writeEndOfDataMessage() throws IOException {
        new AnalyticsControlMessageWriter(recordWriter(), numberOfFields()).writeEndOfData();
    }

    @Override
    public AnalyticsProcessConfig getConfig() {
        return config;
    }

    @Override
    public void restoreState(Client client, String stateDocIdPrefix) throws IOException {
        Objects.requireNonNull(stateDocIdPrefix);
        try (OutputStream restoreStream = processRestoreStream()) {
            int docNum = 0;
            while (true) {
                if (isProcessKilled()) {
                    return;
                }

                // We fetch the documents one at a time because all together they can amount to too much memory
                SearchResponse stateResponse = client.prepareSearch(AnomalyDetectorsIndex.jobStateIndexPattern())
                    .setSize(1)
                    .setQuery(QueryBuilders.idsQuery().addIds(stateDocIdPrefix + ++docNum)).get();
                if (stateResponse.getHits().getHits().length == 0) {
                    break;
                }
                SearchHit stateDoc = stateResponse.getHits().getAt(0);
                logger.debug(() -> new ParameterizedMessage("[{}] Restoring state document [{}]", config.jobId(), stateDoc.getId()));
                StateToProcessWriterHelper.writeStateToStream(stateDoc.getSourceRef(), restoreStream);
            }
        }
    }
}
