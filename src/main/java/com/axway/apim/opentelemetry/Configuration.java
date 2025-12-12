package com.axway.apim.opentelemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.instrumentation.runtimemetrics.java8.*;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class Configuration {

    private Configuration() {
        throw new IllegalStateException("Configuration class");
    }

    private static OpenTelemetry openTelemetry;

    public static synchronized OpenTelemetry getInstance() {
        if (openTelemetry != null)
            return openTelemetry;
        openTelemetry = initOpenTelemetry();
        return openTelemetry;
    }

    private static OpenTelemetry initOpenTelemetry() {

        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder().addResourceCustomizer((resource, properties) -> {
            Resource dtMetadata = Resource.empty();

            for (String name : new String[]{"dt_metadata_e617c525669e072eebe3d0f08212e8f2.properties", "/var/lib/dynatrace/enrichment/dt_metadata.properties"}) {
                try {
                    Properties props = new Properties();
                    props.load(name.startsWith("/var") ? new FileInputStream(name) : new FileInputStream(Files.readAllLines(Paths.get(name)).get(0)));
                    dtMetadata = dtMetadata.merge(Resource.create(props.entrySet().stream()
                            .collect(Attributes::builder, (b, e) -> b.put(e.getKey().toString(), e.getValue().toString()), (b1, b2) -> b1.putAll(b2.build()))
                            .build())
                    );
                } catch (IOException e) {
                }
            }
            return resource.merge(dtMetadata);
        }).build().getOpenTelemetrySdk();        

        Classes.registerObservers(sdk);
        Cpu.registerObservers(sdk);
        MemoryPools.registerObservers(sdk);
        Threads.registerObservers(sdk);
        GarbageCollector.registerObservers(sdk, true);
        return sdk;
    }
}
