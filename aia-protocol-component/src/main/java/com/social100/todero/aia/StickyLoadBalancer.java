package com.social100.todero.aia;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StickyLoadBalancer {
  private ServerSocket serverSocket;
  private List<String> backendServers;
  private int currentServerIndex = 0;
  private Map<String, String> clientToServerMap;

  public StickyLoadBalancer(int port, List<String> backendServers) throws IOException {
    serverSocket = new ServerSocket(port);
    this.backendServers = backendServers;
    this.clientToServerMap = new ConcurrentHashMap<>();
    System.out.println("Load Balancer started on port " + port);
  }

  public static void main(String[] args) throws IOException {
    List<String> backendServers = List.of(
        "localhost:8061"
    );

    int port = 8070;
    StickyLoadBalancer loadBalancer = new StickyLoadBalancer(port, backendServers);
    loadBalancer.start();
  }

  public void start() {
    while (true) {
      try {
        Socket clientSocket = serverSocket.accept();
        String clientIP = clientSocket.getInetAddress().getHostAddress();
        new Thread(new ClientHandler(clientSocket, getBackendServerForClient(clientIP))).start();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private synchronized String getBackendServerForClient(String clientIP) {
    // Check if the client already has an assigned backend server
    if (clientToServerMap.containsKey(clientIP)) {
      return clientToServerMap.get(clientIP);
    } else {
      // Assign a new backend server using round-robin
      String server = backendServers.get(currentServerIndex);
      currentServerIndex = (currentServerIndex + 1) % backendServers.size();
      clientToServerMap.put(clientIP, server);
      return server;
    }
  }

  private static class ClientHandler implements Runnable {
    private Socket clientSocket;
    private String backendServer;

    public ClientHandler(Socket clientSocket, String backendServer) {
      this.clientSocket = clientSocket;
      this.backendServer = backendServer;
    }

    @Override
    public void run() {
      try {
        String[] serverInfo = backendServer.split(":");
        String backendHost = serverInfo[0];
        int backendPort = Integer.parseInt(serverInfo[1]);

        Socket backendSocket = new Socket(backendHost, backendPort);

        Thread t1 = new Thread(() -> forwardData(clientSocket, backendSocket));
        Thread t2 = new Thread(() -> forwardData(backendSocket, clientSocket));

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        clientSocket.close();
        backendSocket.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    private void forwardData(Socket inputSocket, Socket outputSocket) {
      try {
        InputStream is = inputSocket.getInputStream();
        OutputStream os = outputSocket.getOutputStream();

        byte[] buffer = new byte[4096];
        int bytesRead;

        while ((bytesRead = is.read(buffer)) != -1) {
          os.write(buffer, 0, bytesRead);
          os.flush();
        }
      } catch (IOException e) {
        // Connection might be closed by the client or server
      }
    }
  }
}
