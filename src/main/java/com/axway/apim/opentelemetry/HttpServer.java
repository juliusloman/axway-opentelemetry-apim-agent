package com.axway.apim.opentelemetry;

import com.vordel.circuit.Message;
import com.vordel.coreapireg.runtime.broker.InvokableMethod;
import com.vordel.dwe.CorrelationID;
import com.vordel.dwe.http.HTTPMessage;
import com.vordel.dwe.http.ServerTransaction;
import com.vordel.mime.HeaderSet;
import com.vordel.trace.Trace;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.NetworkAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.ErrorAttributes;

import org.aspectj.lang.ProceedingJoinPoint;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class HttpServer {


    private static final OpenTelemetry openTelemetry = Configuration.getInstance();
    private static final Tracer tracer =
        openTelemetry.getTracer("io.opentelemetry.axway.apim.http.HttpServer");

    private static final TextMapPropagator TEXT_MAP_PROPAGATOR =
        openTelemetry.getPropagators().getTextMapPropagator();


    public Object aroundHttpServer(ProceedingJoinPoint pjp, Message message, String apiName, String httpVerb, 
        InvokableMethod runMethod) throws Throwable {

        Object pjpReturnObject;
        HeaderSet headerSet = (HeaderSet) message.get(Utils.HTTP_HEADERS);
        Context context = TEXT_MAP_PROPAGATOR.extract(Context.current(), headerSet, Utils.getter);
        Trace.debug("OpenTelemetry Context " + context);
                      
        String requestPath = message.getOrDefault("http.request.path", "").toString();
        String apiPath = message.getOrDefault("api.path", "").toString();
        String apiID = message.getOrDefault("api.id", "").toString();
        String resolvedToPath = message.getOrDefault("resolved.to.path", "").toString();
        
        // Default values for httpRoute and spanName, these will be overridden if more specific information is available
        String httpRoute = apiName; // Using apiName as default route, if nothing else is available
        String spanName = httpVerb + " " + apiName; // Default span name
        if (resolvedToPath != null && !resolvedToPath.isEmpty()) {
            // resolved.to.path exists, using it as route
            httpRoute = resolvedToPath;
            spanName = httpVerb + " " + resolvedToPath;
        }
        if ( !requestPath.isEmpty()) {
            // Update only request path
            spanName = httpVerb + " " + requestPath;
        }
        if (runMethod != null) {
            // api.path and method source path exist, using them as route and span name
            httpRoute = apiPath + runMethod.getMethod().getSourcePath();
            spanName = httpVerb + " " + httpRoute;
        }

        Span span = tracer.spanBuilder(spanName).setParent(context).setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, httpVerb);
            span.setAttribute(HttpAttributes.HTTP_ROUTE, httpRoute);

            if (message instanceof HTTPMessage) {
                HTTPMessage httpMsg = (HTTPMessage) message;
                ServerTransaction serverTxn = httpMsg.getTxn();
                if (serverTxn!=null) {
                    try {
                        span.setAttribute(NetworkAttributes.NETWORK_PROTOCOL_VERSION, serverTxn.getVersion());
                        span.setAttribute(NetworkAttributes.NETWORK_PEER_ADDRESS, serverTxn.getRemoteAddr().getHostString());
                        span.setAttribute(NetworkAttributes.NETWORK_PEER_PORT, serverTxn.getRemoteAddr().getPort());
                        span.setAttribute(ServerAttributes.SERVER_ADDRESS, serverTxn.getLocalAddr().getHostString());
                        span.setAttribute(ServerAttributes.SERVER_PORT, serverTxn.getLocalAddr().getPort());
                    } catch (NullPointerException e) {
                        Trace.error("OpenTelemetry :: Unable to set span attribute for network peer / server address: " + e.getMessage());
                    }
                }
            }
            span.setAttribute("axway.message.http.request.path", requestPath);
            span.setAttribute("axway.message.api.name", apiName);
            span.setAttribute("axway.message.api.path", apiPath);
            span.setAttribute("axway.message.api.id", apiID);
            span.setAttribute("axway.message.resolved.to.path", resolvedToPath);

            URL requestUrl = (URL) message.get("http.request.url");            
            if (requestUrl != null) {
                span.setAttribute(UrlAttributes.URL_PATH, requestUrl.getPath());
                span.setAttribute(UrlAttributes.URL_QUERY, requestUrl.getQuery());
                span.setAttribute(UrlAttributes.URL_SCHEME, requestUrl.getProtocol());
            }
            Utils.addHttpHeaders(span, "request", headerSet);
            String appName = (String) message.getOrDefault("authentication.application.name", Utils.DEFAULT);
            String orgName = (String) message.getOrDefault("authentication.organization.name", Utils.DEFAULT);
            String appId = (String) message.getOrDefault("authentication.subject.id", Utils.DEFAULT);
            addRequestAttributes(span, appName, orgName, appId, message.getIDBase());
            pjpReturnObject = pjp.proceed();
            int httpStatus = (int) message.getOrDefault("http.response.status", 0);
            if (httpStatus!=0) {
                span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, httpStatus);
            }            
            String httpStatusMessage = (String) message.getOrDefault("http.response.info", "");
            if (httpStatus >= 500) {
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
            // Close the span
            Utils.addHttpHeaders(span, "response", (HeaderSet) message.get(Utils.HTTP_HEADERS));
            span.end();
        }
        return pjpReturnObject;
    }


    public void addRequestAttributes(Span span, String appName, String orgName, String appId, CorrelationID correlationId) {
        Map<String, String> map = new HashMap<>();
        if (appName != null && !appName.equals(Utils.DEFAULT)) {
            map.put("AxwayAppName", appName);
        }
        if (orgName != null && !orgName.equals(Utils.DEFAULT)) {
            map.put("AxwayOrgName", orgName);
        }
        if (appId != null && !appId.equals(Utils.DEFAULT)) {
            map.put("AxwayAppId", appId);
        }
        if (correlationId != null) {
            map.put(Utils.AXWAY_CORRELATION_ID, "Id-" + correlationId);
        }
        Trace.info("OpenTelemetry :: Application Id :" + appId + " - Application Name : " + appName);
        addRequestAttributes(span, map);
    }

    public void addRequestAttributes(Span span, Map<String, String> attributes) {
        attributes.forEach(span::setAttribute);
    }

}
