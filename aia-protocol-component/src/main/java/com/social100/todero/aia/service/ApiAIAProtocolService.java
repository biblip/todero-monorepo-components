package com.social100.todero.aia.service;

import com.social100.todero.common.channels.EventChannel;
import com.social100.todero.common.lineparser.LineParserUtil;
import com.social100.todero.common.aiatpio.AiatpRequest;
import com.social100.todero.remote.RemoteApiCommandLineInterface;
import com.social100.todero.remote.RemoteCliConfig;
import com.social100.todero.common.aiatpio.AiatpIO;
import com.social100.todero.common.aiatpio.AiatpIORequestWrapper;
import com.social100.todero.common.aiatpio.AiatpRuntimeAdapter;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ApiAIAProtocolService {
  public final static String MAIN_GROUP = "Main";
  public final static String RESERVED_GROUP = "Reserved";
  private final String server;
  private final Map<String, String> sessionHeaders = new ConcurrentHashMap<>();

  RemoteApiCommandLineInterface apiCommandLineInterface;

  public ApiAIAProtocolService(RemoteCliConfig cli, EventChannel.EventListener eventListener) {
    server = cli.getServerRawHost() + ":" + cli.getPort();
    apiCommandLineInterface = new RemoteApiCommandLineInterface(cli, eventListener);
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
    AiatpIORequestWrapper httpIORequestWrapper = null;
    LineParserUtil.ParsedLine parsedLine = LineParserUtil.parse(line);
    if (parsedLine.isDottedFormat) {
      AiatpRequest request = AiatpRuntimeAdapter.request("ACTION", "/" + parsedLine.first + (parsedLine.secondValid ? "/" + parsedLine.second : ""), AiatpIO.Body.none());
      if (parsedLine.remaining != null) {
        request = request.toBuilder().body(AiatpIO.Body.ofString(parsedLine.remaining, AiatpIO.UTF_8)).build();
      }
      request = applySessionHeaders(request);
      httpIORequestWrapper = AiatpIORequestWrapper.builder()
          .aiatpRequest(request)
          .build();
    } else {
      AiatpRequest request = AiatpRuntimeAdapter.request("ACTION", "/" + parsedLine.first + "/" + (parsedLine.secondValid ? parsedLine.second + "?" + (parsedLine.remaining != null ? parsedLine.remaining : "") : ""), AiatpIO.Body.none());
      request = applySessionHeaders(request);
      httpIORequestWrapper = AiatpIORequestWrapper.builder()
          .aiatpRequest(request)
          .build();
    }
    Optional.ofNullable(httpIORequestWrapper)
        .ifPresent(h -> apiCommandLineInterface.process(h));
  }

  private AiatpRequest applySessionHeaders(AiatpRequest request) {
    AiatpRequest out = request;
    for (Map.Entry<String, String> entry : sessionHeaders.entrySet()) {
      out = AiatpRuntimeAdapter.withHeader(out, entry.getKey(), entry.getValue());
    }
    return out;
  }

  public void unregister() {
    if (apiCommandLineInterface != null) {
      apiCommandLineInterface = null;
    }
  }

}
