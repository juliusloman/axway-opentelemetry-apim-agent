package com.axway.apim.opentelemetry;

import com.vordel.circuit.Message;
import com.vordel.mime.HeaderSet;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
                              
public final class Utils {

    public static final String HTTP_HEADERS = "http.headers";
    public static final String DEFAULT = "default";
    public static final String AXWAY_CORRELATION_ID = "axway.apim.correlation_id";
    private static Pattern BEARER_TOKEN_PREFIX = Pattern.compile("^bearer\\s+", Pattern.CASE_INSENSITIVE);

    public static final TextMapGetter<HeaderSet> getter = new
        TextMapGetter<HeaderSet>() {
            @Override
            public Iterable<String> keys(HeaderSet carrier) {
                return convertIterableFromIterator(carrier.getHeaderNames());
            }

            @Nullable
            @Override
            public String get(@Nullable HeaderSet carrier, String key) {
                if (carrier != null) {
                    return carrier.getHeader(key);
                }
                return null;
            }
        };

    // setup context on outgoing headers
    public static final TextMapSetter<HeaderSet> setter =
            (carrier, key, value) -> {
                // Insert the context as Header
                if (carrier != null) {
                    carrier.setHeader(key, value);
                }
            };


    private Utils() {
        throw new IllegalStateException("Utility class");
    }

    public static Iterable<String> convertIterableFromIterator(Iterator<String> iterator) {
        // Overriding an abstract method iterator()
        return () -> iterator;
    }

    public static String getRequestURL(Message message) {
        return message.getOrDefault("http.request.uri", message.get("http.request.path")).toString();
    }

    public static String getHttpMethod(Message message) {
        return message.getOrDefault("http.request.verb", "GET").toString();
    }

    public static void addHttpDetails(Span span, String url, String requestUri, Message message) {
             /*
         One of the following is required:
         - http.scheme, http.host, http.target
         - http.scheme, http.server_name, net.host.port, http.target
         - http.scheme, net.host.name, net.host.port, http.target
         - http.url
        */
        span.setAttribute("component", "http");
        if (url != null) {
            span.setAttribute("http.url", url);
        } else {
            String port = (String) message.get("http.destination.port");
            String host = (String) message.get("http.destination.host");
            String protocol = (String) message.get("http.destination.protocol");
            span.setAttribute("http.scheme", protocol);
            if (port != null && !port.isEmpty())
                span.setAttribute("http.host", host + ":" + port);
            else
                span.setAttribute("http.host", host);
            span.setAttribute("http.target", requestUri);
        }
    }
        
    protected static String protectAuthorizationHeader(String value) {
        if (value != null) {
            Matcher matcher = BEARER_TOKEN_PREFIX.matcher(value);
            if (matcher.find()) {
                String prefix = matcher.group();
                String token = value.substring(prefix.length());
                int tokenLength = token.length();

                int copySubstringLength = 0;
                if (tokenLength > 11) {
                    copySubstringLength = 4;
                } else if (tokenLength > 5) {
                    copySubstringLength = 1;
                }

                StringBuffer buffer = new StringBuffer(prefix);
                if (copySubstringLength > 0) {
                    buffer.append(token.substring(0, copySubstringLength));
                }
                buffer.append("****");
                if (copySubstringLength > 0) {
                    buffer.append(token.substring(tokenLength - copySubstringLength, tokenLength));
                }
                value = buffer.toString();
            } else {
                value = "****";
            }
        }
        return value;
    }


    public static void addHttpHeaders(Span span, String type, HeaderSet headers) {
        StringBuilder headerPrefix = new StringBuilder();
        headerPrefix.append("http.");
        headerPrefix.append(type);
        headerPrefix.append(".header.");
        String prefix = headerPrefix.toString();
        if (headers != null) {
            for (Map.Entry<String, HeaderSet.HeaderEntry> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = getHeaderValues(entry);
                if (key.equalsIgnoreCase("Authorization")) {
                    value = protectAuthorizationHeader(value);
                }
                if (type.equals("request") && !HeaderFilter.matchRequestFilter(key)) {  
                    span.setAttribute(prefix + key, value);                    
                } else if (type.equals("response") && !HeaderFilter.matchResponseFilter(prefix)) {
                    span.setAttribute(prefix + key, value);                    
                }
            }
        }
    }

    public static String getHeaderValues(Map.Entry<String, HeaderSet.HeaderEntry> entry) {
        return entry.getValue().stream().
            map(Object::toString).
            collect(Collectors.joining(","));
    }

}
