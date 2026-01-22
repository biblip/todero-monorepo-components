package com.social100.todero.aia;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class PersistentRemoteServerCommunicator implements AutoCloseable {
  private String serverHost;
  private int serverPort;
  private Socket socket;
  private PrintWriter out;
  private BufferedReader in;
  private ExecutorService executor;

  public PersistentRemoteServerCommunicator(String serverHost, int serverPort) throws IOException {
    this.serverHost = serverHost;
    this.serverPort = serverPort;
    connect();
  }

  public static void main(String[] args) {
    String serverHost = "localhost";
    int serverPort = 8070;

    try (PersistentRemoteServerCommunicator communicator = new PersistentRemoteServerCommunicator(serverHost, serverPort)) {

      // Example usage: sending multiple requests
      String response1 = communicator.sendData("help");
      System.out.println("Server response: " + response1);

            /*String response2 = communicator.sendData("simple ping Hello");
            System.out.println("Server response: " + response2);
*/
      String response3 = communicator.sendData("exit");
      System.out.println("Server response: " + response3);

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void connect() throws IOException {
    socket = new Socket(serverHost, serverPort);
    out = new PrintWriter(socket.getOutputStream(), true);
    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    executor = Executors.newSingleThreadExecutor();
  }

  /**
   * Sends data to the server and waits for the response.
   *
   * @param dataToSend The data to send to the server.
   * @return The response received from the server.
   * @throws IOException If an I/O error occurs.
   */
  public String sendData(String dataToSend) throws IOException {
    if (socket == null || socket.isClosed()) {
      throw new IOException("Connection is closed");
    }

    // Send data to the server
    out.println(dataToSend);

    // Wait and read the response
    Future<String> futureResponse = executor.submit(() -> {
      try {
        return in.readLine(); // Blocking until server sends a response
      } catch (IOException e) {
        throw new RuntimeException("Error reading response", e);
      }
    });

    try {
      // Wait for the response with a timeout if desired (e.g., 30 seconds)
      return futureResponse.get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IOException("Error waiting for server response", e);
    }
  }

  /**
   * Closes the connection to the server.
   */
  public void close() {
    try {
      if (socket != null) socket.close();
      if (executor != null) executor.shutdownNow();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}

