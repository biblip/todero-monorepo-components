package aia.component.com.social100.todero.ws;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class WebPageServer {
  private final HttpServer http;

  public WebPageServer(int port) throws IOException {
    http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

    // Serve index at "/"
    http.createContext("/", new ClasspathFileHandler("/web/websocket.html", "text/html; charset=utf-8"));

    // Optionally serve other static assets under /static/*
    http.createContext("/static/", new StaticPrefixHandler("/static/"));
  }

  public void start() {
    http.start();
    System.out.println("HTTP serving on http://127.0.0.1:" + http.getAddress().getPort() + "/");
  }

  // ----- Handlers -----

  static class ClasspathFileHandler implements HttpHandler {
    private final String resourcePath;
    private final String contentType;

    ClasspathFileHandler(String resourcePath, String contentType) {
      this.resourcePath = resourcePath;
      this.contentType = contentType;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
      try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
        byte[] body;
        if (in == null) {
          String notFound = "Not Found";
          body = notFound.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(404, body.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
          }
          return;
        }
        body = in.readAllBytes();
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", contentType);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
          os.write(body);
        }
      } finally {
        ex.close();
      }
    }
  }

  static class StaticPrefixHandler implements HttpHandler {
    private final String classpathPrefix;

    StaticPrefixHandler(String classpathPrefix) {
      this.classpathPrefix = classpathPrefix;
    }

    private static String guessContentType(String path) {
      if (path.endsWith(".html")) return "text/html; charset=utf-8";
      if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
      if (path.endsWith(".css")) return "text/css; charset=utf-8";
      if (path.endsWith(".json")) return "application/json; charset=utf-8";
      return "application/octet-stream";
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
      String reqPath = ex.getRequestURI().getPath(); // e.g. /static/app.js
      String cpPath = reqPath; // classpath uses same path
      try (InputStream in = getClass().getResourceAsStream(cpPath)) {
        if (in == null) {
          String notFound = "Not Found";
          byte[] body = notFound.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(404, body.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
          }
          return;
        }
        byte[] body = in.readAllBytes();
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", guessContentType(cpPath));
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
          os.write(body);
        }
      } finally {
        ex.close();
      }
    }
  }
}
