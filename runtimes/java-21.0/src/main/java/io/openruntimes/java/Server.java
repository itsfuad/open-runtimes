package io.openruntimes.java;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import org.rapidoid.http.Req;
import org.rapidoid.http.Resp;
import org.rapidoid.setup.On;


import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.ArrayList;

public class Server {
    private static final Gson gson = new GsonBuilder().serializeNulls().create();
    private static final Gson gsonInternal = new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        On.port(3000);

        On.get("/*").plain(Server::execute);
        On.post("/*").plain(Server::execute);
        On.put("/*").plain(Server::execute);
        On.delete("/*").plain(Server::execute);
        On.patch("/*").plain(Server::execute);
        On.options("/*").plain(Server::execute);
        On.head("/*").plain(Server::execute);
    }

    public static Resp execute(Req req, Resp resp) {
        RuntimeLogger logger = null;

        try {
            logger = new RuntimeLogger(req.headers().get("x-open-runtimes-logging"), req.headers().get("x-open-runtimes-log-id"));
        } catch(IOException e) {
            // Ignore missing logs
            try {
                logger = new RuntimeLogger("disabled", "");
            } catch(IOException e2) {
                // Never happens
            }
        }

        try {
            return Server.action(logger, req, resp);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String message = sw.toString();

            resp = resp.header("x-open-runtimes-log-id", logger.getId());

            try {
                logger.write(message, RuntimeLogger.TYPE_ERROR, false);
                logger.end();
            } catch(IOException e2) {
                // Ignore missing logs
            }

            return resp
                .code(500)
                .result("");
        }
    }

    public static Resp action(RuntimeLogger logger, Req req, Resp resp) {
        Map<String, String> reqHeaders = req.headers();

        ArrayList<String> cookieHeaders = new ArrayList<String>();

        for (Map.Entry<String, String> entry : req.cookies().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            cookieHeaders.add(key + "=" + value);
        }

        if (!(cookieHeaders.isEmpty())) {
            reqHeaders.put("cookie", String.join("; ", cookieHeaders));
        }

        int safeTimeout = -1;
        String timeout = reqHeaders.get("x-open-runtimes-timeout");
        if (timeout != null && !timeout.isEmpty()) {
            boolean invalid = false;

            try {
                safeTimeout = Integer.parseInt(timeout);
            } catch (NumberFormatException e) {
                invalid = true;
            }

            if (invalid || safeTimeout == 0) {
                return resp.code(500).result("Header \"x-open-runtimes-timeout\" must be an integer greater than 0.");
            }
        }

        String serverSecret = System.getenv("OPEN_RUNTIMES_SECRET");
        if (serverSecret == null) {
            serverSecret = "";
        }

        if(!serverSecret.equals("") && !reqHeaders.getOrDefault("x-open-runtimes-secret", "").equals(serverSecret)) {
            return resp.code(500).result("Unauthorized. Provide correct \"x-open-runtimes-secret\" header.");
        }
        byte[] bodyBinary = req.body();

        Map<String, String> headers = new HashMap<>();
        String method = req.verb();

        for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
            String header = entry.getKey().toLowerCase();
            if (!(header.startsWith("x-open-runtimes-"))) {
                headers.put(header, entry.getValue());
            }
        }

        String enforcedHeadersString = System.getenv("OPEN_RUNTIMES_HEADERS");
        if (enforcedHeadersString == null || enforcedHeadersString.isEmpty()) {
            enforcedHeadersString = "{}";
        }
        Map<String, Object> enforcedHeaders = gsonInternal.fromJson(enforcedHeadersString, Map.class);
        for (Map.Entry<String, Object> entry : enforcedHeaders.entrySet()) {
            headers.put(entry.getKey().toLowerCase(), String.valueOf(entry.getValue()));
        }

        String scheme = reqHeaders.getOrDefault("x-forwarded-proto", "http");
        String defaultPort = scheme.equals("https") ? "443" : "80";

        String hostHeader = reqHeaders.getOrDefault("host", "");
        String host = "";
        int port = Integer.parseInt(defaultPort);

        if (hostHeader.contains(":")) {
            host = hostHeader.split(":")[0];
            port = Integer.parseInt(hostHeader.split(":")[1]);
        } else {
            host = hostHeader;
            port = Integer.parseInt(defaultPort);
        }

        String path = req.path();
        String queryString = req.query();
        Map<String, String> query = new HashMap<>();

        for (String param : queryString.split("&")) {
            String[] pair = param.split("=", 2);

            if (pair.length >= 1 && pair[0] != null && !pair[0].isEmpty()) {
                String value = pair.length == 2 ? pair[1] : "";
                query.put(pair[0], value);
            }
        }

        String url = scheme + "://" + host;

        if (port != Integer.parseInt(defaultPort)) {
            url += ":" + port;
        }

        url += path;

        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }

        RuntimeRequest runtimeRequest = new RuntimeRequest(
                method,
                scheme,
                host,
                port,
                path,
                query,
                queryString,
                headers,
                bodyBinary,
                url
        );
        RuntimeResponse runtimeResponse = new RuntimeResponse();
        RuntimeContext context = new RuntimeContext(runtimeRequest, runtimeResponse, logger);

        logger.overrideNativeLogs();

        RuntimeOutput output;

        try {
            String entrypoint = System.getenv("OPEN_RUNTIMES_ENTRYPOINT");
            entrypoint = entrypoint.substring(0, entrypoint.length() - 5); // Remove .java
            entrypoint = entrypoint.replaceAll("/", ".");

            final Class classToLoad = Class.forName("io.openruntimes.java." + entrypoint);
            final Method classMethod = classToLoad.getDeclaredMethod("main", RuntimeContext.class);
            final Object instance = classToLoad.newInstance();

            if (safeTimeout > 0) {
                Future<RuntimeOutput> future = executor.submit(() -> {
                    try {
                        return (RuntimeOutput) classMethod.invoke(instance, context);
                    } catch (Exception e) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        e.printStackTrace(pw);

                        context.error(sw.toString());
                        System.out.println(sw.toString());
                        context.getRes().send("", 500);
                    }

                    return null;
                });

                try {
                    output = future.get(safeTimeout, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    context.error("Execution timed out.");
                    System.out.println("Execution timed out.");
                    output = context.getRes().send("", 500);
                }
            } else {
                output = (RuntimeOutput) classMethod.invoke(instance, context);
            }

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            context.error(sw.toString());
            System.out.println(sw.toString());
            output = context.getRes().send("", 500);
        } finally {
            logger.revertNativeLogs();
        }

        if (output == null) {
            context.error("Return statement missing. return context.res.empty() if no response is expected.");
            System.out.println("Return statement missing. return context.res.empty() if no response is expected.");
            output = context.getRes().send("", 500);
        }

        for (Map.Entry<String, String> entry : output.getHeaders().entrySet()) {
            String header = entry.getKey().toLowerCase();
            String headerValue = entry.getValue();

            if (header.startsWith("x-open-runtimes-")) {
                continue;
            }

            if (header.equals("content-type") && !headerValue.startsWith("multipart/")) {
                headerValue = headerValue.toLowerCase();

                if(!headerValue.contains("charset=")) {
                    headerValue += "; charset=utf-8";
                }
            }

            resp = resp.header(header, headerValue);
        }

        resp = resp.header("x-open-runtimes-log-id", logger.getId());

        try {
            logger.end();
        } catch(IOException e) {
            // Ignore missing logs
        }

        return resp
                .code(output.getStatusCode())
                .result(output.getBody());
    }
}