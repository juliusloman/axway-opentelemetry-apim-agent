package com.axway.apim.opentelemetry;

import com.vordel.circuit.Message;
import com.vordel.config.Circuit;
import com.vordel.mime.HeaderSet;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.ErrorAttributes;

import java.net.MalformedURLException;
import java.net.URL;

import org.aspectj.lang.ProceedingJoinPoint;

public class ConnectToUrl {

    private static final Tracer TRACER = Configuration.getInstance().getTracer("io.opentelemetry.axway.apim.http.HttpClient");
    private static final TextMapPropagator TEXT_MAP_PROPAGATOR = Configuration.getInstance().getPropagators().getTextMapPropagator();

    public Object httpClient(ProceedingJoinPoint pjp, Message message, Circuit circuit, HeaderSet requestHeaders, String httpVerb) throws Throwable {
        Object pjpReturnObject;
        String requestUrl = Utils.getRequestURL(message);
        Span span = TRACER.spanBuilder(requestUrl).setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, httpVerb);
            span.setAttribute("axway.apim.routing.policy", circuit.getName());

            try {
                URL url = new URL(requestUrl);
                if (url != null) {                                        
                    span.setAttribute(UrlAttributes.URL_PATH, url.getPath());
                    span.setAttribute(UrlAttributes.URL_QUERY, url.getQuery());
                    span.setAttribute(UrlAttributes.URL_SCHEME, url.getProtocol());
                    span.setAttribute(UrlAttributes.URL_FULL, url.toString());
                    span.setAttribute(ServerAttributes.SERVER_ADDRESS, url.getHost());
                    span.setAttribute(ServerAttributes.SERVER_PORT, url.getPort());
                }
            } catch (MalformedURLException e) {
                span.setAttribute(UrlAttributes.URL_FULL, message.get("destinationURL").toString());
            }
            // Add request headers
            Utils.addHttpHeaders(span, "request", (HeaderSet) message.get(Utils.HTTP_HEADERS));
            TEXT_MAP_PROPAGATOR.inject(Context.current(), requestHeaders, Utils.setter);
            pjpReturnObject = pjp.proceed();
            int httpStatus = (int) message.getOrDefault("http.response.status", 0);
            String httpStatusMessage = (String) message.getOrDefault("http.response.info", "");
            if (httpStatus >= 400 && httpStatus < 500) {
                span.setStatus(StatusCode.ERROR, httpStatusMessage);
            } else if (httpStatus >= 500) {
                span.setStatus(StatusCode.ERROR, httpStatusMessage);
                span.setAttribute(ErrorAttributes.ERROR_TYPE, httpStatusMessage);
            }
        } catch (Throwable e) {
            int httpStatus = (int) message.getOrDefault("http.response.status", 0);
            String httpStatusMessage = (String) message.getOrDefault("http.response.info", "");
            span.setStatus(StatusCode.ERROR, httpStatus + "-" +httpStatusMessage);
            span.recordException(e);
            throw e;
        } finally {
            Utils.addHttpHeaders(span, "response", (HeaderSet) message.get(Utils.HTTP_HEADERS));
            span.end();
        }
        return pjpReturnObject;
    }
}
