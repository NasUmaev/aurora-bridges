package com.aurora.gtnh;

import java.net.HttpURLConnection;
import java.net.URL;

/** Creates connections only to the configured loopback Ollama endpoint. */
final class OllamaHttp {

    private OllamaHttp() {}

    static HttpURLConnection open(String path, int connectTimeout, int readTimeout) throws Exception {
        URL base = new URL(BridgeConfig.ollamaUrl);
        String host = base.getHost();
        if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host))) {
            throw new SecurityException("Ollama URL must use loopback");
        }
        if (!("http".equalsIgnoreCase(base.getProtocol()) || "https".equalsIgnoreCase(base.getProtocol()))) {
            throw new SecurityException("Unsupported Ollama URL protocol");
        }
        URL endpoint = new URL(base.getProtocol(), host, base.getPort(), path);
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        return connection;
    }
}
