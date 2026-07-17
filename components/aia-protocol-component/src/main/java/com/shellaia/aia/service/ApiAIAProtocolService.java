package com.shellaia.aia.service;

import com.social100.todero.common.channels.EventChannel;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.remote.RemoteCliConfig;
import com.social100.todero.remote.HttpRemoteEngineClient;
import com.social100.todero.remote.RemoteCommandManager;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ApiAIAProtocolService {
  public final static String MAIN_GROUP = "Main";
  public final static String RESERVED_GROUP = "Reserved";
  private final String server;
  private final Map<String, String> sessionHeaders = new ConcurrentHashMap<>();

  RemoteCommandManager remoteCommandManager;

  public ApiAIAProtocolService(RemoteCliConfig cli, EventChannel.EventListener eventListener) {
    server = cli.getServerRawHost() + ":" + cli.getPort();
    HttpRemoteEngineClient remoteEngineClient = new HttpRemoteEngineClient(cli.getBaseUri(), Duration.ofSeconds(30));
    remoteCommandManager = new RemoteCommandManager(remoteEngineClient, eventListener);
  }

  public String getServer() {
    return server;
  }

  public String getStatus() {
    return "status";
  }

  public void setSessionHeader(String name, String value) {
    if (name == null || name.isEmpty()) return;
    if (value == null) {
      sessionHeaders.remove(name);
    } else {
      sessionHeaders.put(name, value);
    }
  }

  public void removeSessionHeader(String name) {
    if (name == null || name.isEmpty()) return;
    sessionHeaders.remove(name);
  }

  public void exec(String line) {
    LineParserUtil.ParsedLine parsedLine = LineParserUtil.parse(line);
    AiatpRequest request;
    if (parsedLine.isDottedFormat) {
      request = AiatpRuntimeAdapter.request("ACTION", "/" + parsedLine.first + (parsedLine.secondValid ? "/" + parsedLine.second : ""), AiatpIO.Body.none());
      if (parsedLine.remaining != null) {
        request = request.toBuilder().body(AiatpIO.Body.ofString(parsedLine.remaining, AiatpIO.UTF_8)).build();
      }
    } else {
      request = AiatpRuntimeAdapter.request("ACTION", "/" + parsedLine.first + "/" + (parsedLine.secondValid ? parsedLine.second + "?" + (parsedLine.remaining != null ? parsedLine.remaining : "") : ""), AiatpIO.Body.none());
    }
    request = applySessionHeaders(request);
    Optional.ofNullable(request)
        .ifPresent(h -> remoteCommandManager.process(AiatpIORequestWrapper.builder()
            .aiatpRequest(h)
            .sourceId(server)
            .build()));
  }

  private AiatpRequest applySessionHeaders(AiatpRequest request) {
    AiatpRequest out = request;
    for (Map.Entry<String, String> entry : sessionHeaders.entrySet()) {
      out = AiatpRuntimeAdapter.withHeader(out, entry.getKey(), entry.getValue());
    }
    return out;
  }

  public void unregister() {
    if (remoteCommandManager != null) {
      remoteCommandManager.terminate();
      remoteCommandManager = null;
    }
  }

}
