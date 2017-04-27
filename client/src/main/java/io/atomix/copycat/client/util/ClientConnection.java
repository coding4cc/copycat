/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.client.util;

import io.atomix.catalyst.concurrent.Listener;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Client;
import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.transport.TransportException;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.error.CopycatError;
import io.atomix.copycat.protocol.ConnectRequest;
import io.atomix.copycat.protocol.ConnectResponse;
import io.atomix.copycat.protocol.Request;
import io.atomix.copycat.protocol.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client connection that recursively connects to servers in the cluster and attempts to submit requests.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class ClientConnection implements Connection {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientConnection.class);
  private final String id;
  private final Client client;
  private final AddressSelector selector;
  private CompletableFuture<Connection> connectFuture;
  private Queue<CompletableFuture<Connection>> connectFutures = new LinkedList<>();
  private final Map<Class<?>, Function> handlers = new ConcurrentHashMap<>();
  private Connection connection;
  private boolean open = true;

  public ClientConnection(String id, Client client, AddressSelector selector) {
    this.id = Assert.notNull(id, "id");
    this.client = Assert.notNull(client, "client");
    this.selector = Assert.notNull(selector, "selector");
  }

  /**
   * Returns the current selector leader.
   *
   * @return The current selector leader.
   */
  public Address leader() {
    return selector.leader();
  }

  /**
   * Returns the current set of servers.
   *
   * @return The current set of servers.
   */
  public Collection<Address> servers() {
    return selector.servers();
  }

  /**
   * Resets the client connection.
   *
   * @return The client connection.
   */
  public ClientConnection reset() {
    selector.reset();
    return this;
  }

  /**
   * Resets the client connection.
   *
   * @param leader The current cluster leader.
   * @param servers The current servers.
   * @return The client connection.
   */
  public ClientConnection reset(Address leader, Collection<Address> servers) {
    selector.reset(leader, servers);
    return this;
  }

  @Override
  public CompletableFuture<Void> send(Object request) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    sendRequest((Request) request, (r, c) -> c.send(r), future);
    return future;
  }

  @Override
  public <T, U> CompletableFuture<U> sendAndReceive(T request) {
    CompletableFuture<U> future = new CompletableFuture<>();
    sendRequest((Request) request, (r, c) -> c.sendAndReceive(r), future);
    return future;
  }

  /**
   * Sends the given request attempt to the cluster.
   */
  private <T extends Request, U> void sendRequest(T request, BiFunction<Request, Connection, CompletableFuture<U>> sender, CompletableFuture<U> future) {
    if (open) {
      connect().whenComplete((c, e) -> sendRequest(request, sender, c, e, future));
    }
  }

  /**
   * Sends the given request attempt to the cluster via the given connection if connected.
   */
  private <T extends Request, U> void sendRequest(T request, BiFunction<Request, Connection, CompletableFuture<U>> sender, Connection connection, Throwable error, CompletableFuture<U> future) {
    if (open) {
      if (error == null) {
        if (connection != null) {
          LOGGER.trace("{} - Sending {}", id, request);
          sender.apply(request, connection).whenComplete((r, e) -> {
            if (e != null || r != null) {
              handleResponse(request, sender, connection, (Response) r, e, future);
            } else {
              future.complete(null);
            }
          });
        } else {
          future.completeExceptionally(new ConnectException("Failed to connect to the cluster"));
        }
      } else {
        resendRequest(error, request, sender, connection, future);
      }
    }
  }

  /**
   * Resends a request due to a request failure, resetting the connection if necessary.
   */
  @SuppressWarnings("unchecked")
  private <T extends Request> void resendRequest(Throwable cause, T request, BiFunction sender, Connection connection, CompletableFuture future) {
    // If the connection has not changed, reset it and connect to the next server.
    if (this.connection == connection) {
      LOGGER.trace("{} - Resetting connection. Reason: {}", id, cause);
      next().whenComplete((c, e) -> sendRequest(request, sender, c, e, future));
    }
    // Otherwise, piggyback on any existing connection attempts.
    else {
      connect().whenComplete((c, e) -> sendRequest(request, sender, c, e, future));
    }
  }

  /**
   * Handles a response from the cluster.
   */
  @SuppressWarnings("unchecked")
  private <T extends Request> void handleResponse(T request, BiFunction sender, Connection connection, Response response, Throwable error, CompletableFuture future) {
    if (open) {
      if (error == null) {
        if (response.status() == Response.Status.OK
          || response.error() == CopycatError.Type.COMMAND_ERROR
          || response.error() == CopycatError.Type.QUERY_ERROR
          || response.error() == CopycatError.Type.APPLICATION_ERROR
          || response.error() == CopycatError.Type.UNKNOWN_SESSION_ERROR) {
          LOGGER.trace("{} - Received {}", id, response);
          future.complete(response);
        } else {
          resendRequest(response.error().createException(), request, sender, connection, future);
        }
      } else if (error instanceof ConnectException || error instanceof TimeoutException || error instanceof TransportException || error instanceof ClosedChannelException) {
        resendRequest(error, request, sender, connection, future);
      } else {
        LOGGER.debug("{} - {} failed! Reason: {}", id, request, error);
        future.completeExceptionally(error);
      }
    }
  }

  /**
   * Connects to the cluster.
   */
  private CompletableFuture<Connection> connect() {
    CompletableFuture<Connection> future = new CompletableFuture<>();

    // If the address selector has been reset then reset the connection.
    if (selector.state() == AddressSelector.State.RESET && connection != null) {
      if (connectFuture != null) {
        connectFutures.add(future);
        return connectFuture;
      }

      CompletableFuture<Connection> newConnectFuture = new CompletableFuture<>();
      connectFuture = newConnectFuture;
      connectFutures.add(future);

      connection.close().whenComplete((result, error) -> connect(newConnectFuture));

      return awaitConnect(newConnectFuture);
    }

    // If a connection was already established then use that connection.
    if (connection != null) {
      return CompletableFuture.completedFuture(connection);
    }

    // If a connection is currently being established then piggyback on the connect future.
    if (connectFuture != null) {
      connectFutures.add(future);
      return connectFuture;
    }

    // Create a new connect future and connect to the first server in the cluster.
    connectFuture = new CompletableFuture<>();
    connectFutures.add(future);

    reset().connect(connectFuture);

    // Reset the connect future field once the connection is complete.
    return awaitConnect(connectFuture);
  }

  /**
   * Awaits the completion of a connection and triggers connect futures in creation order once complete.
   */
  private CompletableFuture<Connection> awaitConnect(CompletableFuture<Connection> future) {
    return future.whenComplete((result, error) -> {
      Queue<CompletableFuture<Connection>> connectedFutures = connectFutures;
      connectFuture = null;
      connectFutures = new LinkedList<>();
      if (error == null) {
        connectedFutures.forEach(f -> f.complete(result));
      } else {
        connectedFutures.forEach(f -> f.completeExceptionally(error));
      }
    });
  }

  /**
   * Connects to the cluster using the next connection.
   */
  private CompletableFuture<Connection> next() {
    if (connection != null) {
      Connection connection = this.connection;
      this.connection = null;
      return connection.close().thenCompose(v -> {
        if (connectFuture == null) {
          connectFuture = new CompletableFuture<>();
          connect(connectFuture);
          return connectFuture.whenComplete((result, error) -> connectFuture = null);
        }
        return connectFuture;
      });
    } else {
      if (connectFuture == null) {
        connectFuture = new CompletableFuture<>();
        connect(connectFuture);
        return connectFuture.whenComplete((result, error) -> connectFuture = null);
      }
      return connectFuture;
    }
  }

  /**
   * Attempts to connect to the cluster.
   */
  private void connect(CompletableFuture<Connection> future) {
    if (!selector.hasNext()) {
      LOGGER.debug("{} - Failed to connect to the cluster", id);
      future.complete(null);
    } else {
      Address address = selector.next();
      LOGGER.debug("{} - Connecting to {}", id, address);
      client.connect(address).whenComplete((c, e) -> handleConnection(address, c, e, future));
    }
  }

  /**
   * Handles a connection to a server.
   */
  private void handleConnection(Address address, Connection connection, Throwable error, CompletableFuture<Connection> future) {
    if (open) {
      if (error == null) {
        setupConnection(address, connection, future);
      } else {
        LOGGER.debug("{} - Failed to connect! Reason: {}", id, error);
        connect(future);
      }
    }
  }

  /**
   * Sets up the given connection.
   */
  @SuppressWarnings("unchecked")
  private void setupConnection(Address address, Connection connection, CompletableFuture<Connection> future) {
    LOGGER.debug("{} - Setting up connection to {}", id, address);

    this.connection = connection;

    connection.onClose(c -> {
      if (c.equals(this.connection)) {
        LOGGER.debug("{} - Connection closed", id);
        this.connection = null;
      }
    });
    connection.onException(c -> {
      if (c.equals(this.connection)) {
        LOGGER.debug("{} - Connection lost", id);
        this.connection = null;
      }
    });

    for (Map.Entry<Class<?>, Function> entry : handlers.entrySet()) {
      connection.handler(entry.getKey(), entry.getValue());
    }

    // When we first connect to a new server, first send a ConnectRequest to the server to establish
    // the connection with the server-side state machine.
    ConnectRequest request = ConnectRequest.builder()
      .withClientId(id)
      .build();

    LOGGER.trace("{} - Sending {}", id, request);
    connection.<ConnectRequest, ConnectResponse>sendAndReceive(request).whenComplete((r, e) -> handleConnectResponse(r, e, future));
  }

  /**
   * Handles a connect response.
   */
  private void handleConnectResponse(ConnectResponse response, Throwable error, CompletableFuture<Connection> future) {
    if (open) {
      if (error == null) {
        LOGGER.trace("{} - Received {}", id, response);
        // If the connection was successfully created, immediately send a keep-alive request
        // to the server to ensure we maintain our session and get an updated list of server addresses.
        if (response.status() == Response.Status.OK) {
          selector.reset(response.leader(), response.members());
          future.complete(connection);
        } else {
          connect(future);
        }
      } else {
        LOGGER.debug("{} - Failed to connect! Reason: {}", id, error);
        connect(future);
      }
    }
  }

  @Override
  public <T, U> Connection handler(Class<T> type, Consumer<T> handler) {
    return handler(type, r -> {
      handler.accept(r);
      return null;
    });
  }

  @Override
  public <T, U> Connection handler(Class<T> type, Function<T, CompletableFuture<U>> handler) {
    Assert.notNull(type, "type");
    Assert.notNull(handler, "handler");
    handlers.put(type, handler);
    if (connection != null)
      connection.handler(type, handler);
    return this;
  }

  @Override
  public Listener<Throwable> onException(Consumer<Throwable> listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Listener<Connection> onClose(Consumer<Connection> listener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public CompletableFuture<Void> close() {
    open = false;
    return CompletableFuture.completedFuture(null);
  }

}
