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
 * limitations under the License.
 */
package io.atomix.copycat.client;

import io.atomix.copycat.client.session.ClientSession;
import io.atomix.copycat.client.session.Session;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.serializer.ServiceLoaderTypeResolver;
import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.Transport;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.ConfigurationException;
import io.atomix.catalyst.util.Managed;
import io.atomix.catalyst.util.concurrent.Futures;
import io.atomix.catalyst.util.concurrent.ThreadContext;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Provides an interface for submitting {@link Command commands} and {@link Query} queries to the Raft cluster.
 * <p>
 * Raft clients can be constructed using the {@link CopycatClient.Builder}. To create a new client builder, use the
 * static {@link #builder(Address...)} method, passing one or more server {@link Address}:
 * <pre>
 *   {@code
 *     RaftClient client = RaftClient.builder(new Address("123.456.789.0", 5000), new Address("123.456.789.1", 5000).build();
 *   }
 * </pre>
 * By default, the client will attempt to use the {@code NettyTransport} to communicate with the cluster. See the {@link CopycatClient.Builder}
 * documentation for client configuration options.
 * <p>
 * Raft clients interact with one or more nodes in a Raft cluster through a session. When the client is {@link #open() opened},
 * the client will attempt to one of the known member {@link Address} provided to the builder. As long as the client can
 * communicate with at least one correct member of the cluster, it can open a session. Once the client is able to register a
 * {@link Session}, it will receive an updated list of members for the entire cluster and thereafter be allowed to communicate
 * with all servers.
 * <p>
 * Sessions are created by registering the client through the cluster leader. Clients always connect to a single node in the
 * cluster, and in the event of a node failure or partition, the client will detect the failure and reconnect to a correct server.
 * <p>
 * Clients periodically send <em>keep-alive</em> requests to the server to which they're connected. The keep-alive request
 * interval is determined by the cluster's session timeout, and the session timeout is determined by the leader's configuration
 * at the time that the session is registered. This ensures that clients cannot be misconfigured with a keep-alive interval
 * greater than the cluster's session timeout.
 * <p>
 * Clients communicate with the distributed state machine by submitting {@link Command commands} and {@link Query queries} to
 * the cluster through the {@link #submit(Command)} and {@link #submit(Query)} methods respectively:
 * <pre>
 *   {@code
 *   client.submit(new PutCommand("foo", "Hello world!")).thenAccept(result -> {
 *     System.out.println("Result is " + result);
 *   });
 *   }
 * </pre>
 * All client methods are fully asynchronous and return {@link CompletableFuture}. To block until a method is complete, use
 * the {@link CompletableFuture#get()} or {@link CompletableFuture#join()} methods.
 * <p>
 * Sessions work to provide linearizable semantics for client {@link Command commands}. When a command is submitted to the cluster,
 * the command will be forwarded to the leader where it will be logged and replicated. Once the command is stored on a majority
 * of servers, the leader will apply it to its state machine and respond according to the command's {@link Command#consistency()}.
 * See the {@link Command.ConsistencyLevel} documentation for more info.
 * <p>
 * Sessions also allow {@link Query queries} (read-only requests) submitted by the client to optionally be executed on follower
 * nodes. When a query is submitted to the cluster, the query's {@link Query#consistency()} will be used to determine how the
 * query is handled. For queries with stronger consistency levels, they will be forwarded to the cluster's leader. For weaker
 * consistency queries, they may be executed on follower nodes according to the consistency level constraints. See the
 * {@link Query.ConsistencyLevel} documentation for more info.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class CopycatClient implements Managed<CopycatClient> {

  /**
   * Returns a new Raft client builder.
   * <p>
   * The provided set of members will be used to connect to the Raft cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member that can communicate with
   * the cluster's leader.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  public static Builder builder(Address... members) {
    return builder(Arrays.asList(Assert.notNull(members, "members")));
  }

  /**
   * Returns a new Raft client builder.
   * <p>
   * The provided set of members will be used to connect to the Raft cluster. The members list does not have to represent
   * the complete list of servers in the cluster, but it must have at least one reachable member that can communicate with
   * the cluster's leader.
   *
   * @param members The cluster members to which to connect.
   * @return The client builder.
   */
  public static Builder builder(Collection<Address> members) {
    return new Builder(members);
  }

  private final Transport transport;
  private final Collection<Address> members;
  private final Serializer serializer;
  private ClientSession session;
  private CompletableFuture<CopycatClient> openFuture;
  private CompletableFuture<Void> closeFuture;

  protected CopycatClient(Transport transport, Collection<Address> members, Serializer serializer) {
    serializer.resolve(new ServiceLoaderTypeResolver());
    this.transport = Assert.notNull(transport, "transport");
    this.members = Assert.notNull(members, "members");
    this.serializer = Assert.notNull(serializer, "serializer");
  }

  /**
   * Returns the client execution context.
   * <p>
   * The thread context is the event loop that this client uses to communicate Raft servers.
   * Implementations must guarantee that all asynchronous {@link java.util.concurrent.CompletableFuture} callbacks are
   * executed on a single thread via the returned {@link io.atomix.catalyst.util.concurrent.ThreadContext}.
   * <p>
   * The {@link io.atomix.catalyst.util.concurrent.ThreadContext} can also be used to access the Raft client's internal
   * {@link io.atomix.catalyst.serializer.Serializer serializer} via {@link ThreadContext#serializer()}.
   *
   * @return The Raft context.
   */
  public ThreadContext context() {
    return session != null ? session.context() : null;
  }

  /**
   * Returns the client serializer.
   * <p>
   * The serializer can be used to manually register serializable types for submitted {@link Command commands} and
   * {@link Query queries}.
   * <pre>
   *   {@code
   *     client.serializer().register(MyObject.class, 1);
   *     client.serializer().register(MyOtherObject.class, new MyOtherObjectSerializer(), 2);
   *   }
   * </pre>
   *
   * @return The client operation serializer.
   */
  public Serializer serializer() {
    return serializer;
  }

  /**
   * Returns the client session.
   * <p>
   * The {@link Session} object can be used to receive session events from replicated state machines. Session events are
   * named messages. To register a session event handler, use the {@link Session#onEvent(String, Consumer)} method:
   * <pre>
   *   {@code
   *   client.session().onEvent("lock", v -> System.out.println("acquired lock!"));
   *   }
   * </pre>
   * When a server-side state machine {@link Session#publish(String, Object) publishes} an event message to this session, the
   * event message is guaranteed to be received in the order in which it was sent by the state machine. Note that the point
   * in time at which events are received by the client is determined by the {@link Command#consistency()} of the command being
   * executed when the state machine published the event. Events are not necessarily guaranteed to be received by the client
   * during command execution. See the {@link Command.ConsistencyLevel} documentation for more info.
   * <p>
   * The returned {@link Session} instance will remain constant as long as the client maintains its session with the cluster.
   * Maintaining the client's session requires that the client be able to communicate with one server that can communicate
   * with the leader at any given time. During periods where the cluster is electing a new leader, the client's session will
   * not timeout but will resume once a new leader is elected.
   * <p>
   * Once the client connects to the cluster and opens its session, session listeners registered via {@link Session#onOpen(Consumer)}
   * will be called. In the event of a session expiration wherein the client fails to communicate with the cluster for at least
   * a session timeout, the session will be expired and listeners registered via {@link Session#onClose(Consumer)} will be called.
   *
   * @return The client session or {@code null} if no session is open.
   */
  public Session session() {
    return session;
  }

  /**
   * Submits an operation to the Raft cluster.
   * <p>
   * This method is provided for convenience. The submitted {@link Operation} must be an instance
   * of {@link Command} or {@link Query}.
   *
   * @param operation The operation to submit.
   * @param <T> The operation result type.
   * @return A completable future to be completed with the operation result.
   * @throws IllegalArgumentException If the {@link Operation} is not an instance of {@link Command} or {@link Query}.
   * @throws NullPointerException if {@code operation} is null
   */
  public <T> CompletableFuture<T> submit(Operation<T> operation) {
    Assert.notNull(operation, "operation");
    if (operation instanceof Command) {
      return submit((Command<T>) operation);
    } else if (operation instanceof Query) {
      return submit((Query<T>) operation);
    } else {
      throw new IllegalArgumentException("unknown operation type");
    }
  }

  /**
   * Submits a command to the Raft cluster.
   * <p>
   * Commands are used to alter state machine state. All commands will be forwarded to the current Raft leader.
   * Once a leader receives the command, it will write the command to its internal {@code Log} and replicate it to a majority
   * of the cluster. Once the command has been replicated to a majority of the cluster, it will apply the command to its
   * {@code StateMachine} and respond with the result.
   * <p>
   * Once the command has been applied to a server state machine, the returned {@link java.util.concurrent.CompletableFuture}
   * will be completed with the state machine output.
   * <p>
   * Note that all client submissions are guaranteed to be completed in the same order in which they were sent (program order)
   * and on the same thread. This does not, however, mean that they'll be applied to the server-side replicated state machine
   * in that order. State machine order is dependent on the configured {@link Command.ConsistencyLevel}.
   *
   * @param command The command to submit.
   * @param <T> The command result type.
   * @return A completable future to be completed with the command result. The future is guaranteed to be completed after all
   * {@link Command} or {@link Query} submission futures that preceded it. The future will always be completed on the
   * {@link #context()} thread.
   * @throws NullPointerException if {@code command} is null
   * @throws IllegalStateException if the {@link #session()} is not open
   */
  public <T> CompletableFuture<T> submit(Command<T> command) {
    Assert.notNull(command, "command");
    if (session == null)
      return Futures.exceptionalFuture(new IllegalStateException("client not open"));
    return session.submit(command);
  }

  /**
   * Submits a query to the Raft cluster.
   * <p>
   * Queries are used to read state machine state. The behavior of query submissions is primarily dependent on the
   * query's {@link Query.ConsistencyLevel}. For {@link Query.ConsistencyLevel#LINEARIZABLE}
   * and {@link Query.ConsistencyLevel#BOUNDED_LINEARIZABLE} consistency levels, queries will be forwarded
   * to the Raft leader. For lower consistency levels, queries are allowed to read from followers. All queries are executed
   * by applying queries to an internal server state machine.
   * <p>
   * Once the query has been applied to a server state machine, the returned {@link java.util.concurrent.CompletableFuture}
   * will be completed with the state machine output.
   *
   * @param query The query to submit.
   * @param <T> The query result type.
   * @return A completable future to be completed with the query result. The future is guaranteed to be completed after all
   * {@link Command} or {@link Query} submission futures that preceded it. The future will always be completed on the
   * {@link #context()} thread.
   * @throws NullPointerException if {@code query} is null
   * @throws IllegalStateException if the {@link #session()} is not open
   */
  public <T> CompletableFuture<T> submit(Query<T> query) {
    Assert.notNull(query, "query");
    if (session == null)
      return Futures.exceptionalFuture(new IllegalStateException("client not open"));
    return session.submit(query);
  }

  /**
   * Connects the client to the Raft cluster.
   * <p>
   * When the client is opened, it will attempt to connect to and open a session with each unique configured server
   * {@link Address}. Once the session is open, the returned {@link CompletableFuture} will be completed.
   *
   * @return A completable future to be completed once the client's {@link #session()} is open.
   */
  @Override
  public CompletableFuture<CopycatClient> open() {
    if (session != null && session.isOpen())
      return CompletableFuture.completedFuture(this);

    if (openFuture == null) {
      synchronized (this) {
        if (openFuture == null) {
          ClientSession session = new ClientSession(transport, members, serializer);
          if (closeFuture == null) {
            openFuture = session.open().thenApply(s -> {
              synchronized (this) {
                openFuture = null;
                this.session = session;
                return this;
              }
            });
          } else {
            openFuture = closeFuture.thenCompose(v -> session.open().thenApply(s -> {
              synchronized (this) {
                openFuture = null;
                this.session = session;
                return this;
              }
            }));
          }
        }
      }
    }
    return openFuture;
  }

  /**
   * Returns a boolean value indicating whether the client is open.
   * <p>
   * Whether the client is open depends on whether the client has an open session to the cluster.
   *
   * @return Indicates whether the client is open.
   */
  @Override
  public boolean isOpen() {
    return session != null && session.isOpen();
  }

  @Override
  public CompletableFuture<Void> close() {
    if (session == null || !session.isOpen())
      return CompletableFuture.completedFuture(null);

    if (closeFuture == null) {
      synchronized (this) {
        if (session == null) {
          return CompletableFuture.completedFuture(null);
        }

        if (closeFuture == null) {
          if (openFuture == null) {
            closeFuture = session.close().whenComplete((result, error) -> {
              synchronized (this) {
                session = null;
                closeFuture = null;
              }
            });
          } else {
            closeFuture = openFuture.thenCompose(v -> session.close().whenComplete((result, error) -> {
              synchronized (this) {
                session = null;
                closeFuture = null;
              }
            }));
          }
        }
      }
    }
    return closeFuture;
  }

  /**
   * Returns a boolean value indicating whether the client is closed.
   * <p>
   * Whether the client is closed depends on whether the client has not connected to the cluster or its session has been
   * closed or expired.
   *
   * @return Indicates whether the client is closed.
   */
  @Override
  public boolean isClosed() {
    return session == null || session.isClosed();
  }

  /**
   * Builds a new Raft client.
   * <p>
   * New client builders should be constructed using the static {@link #builder(Address...)} factory method.
   * <pre>
   *   {@code
   *     RaftClient client = RaftClient.builder(new Address("123.456.789.0", 5000), new Address("123.456.789.1", 5000)
   *       .withTransport(new NettyTransport())
   *       .build();
   *   }
   * </pre>
   */
  public static class Builder extends io.atomix.catalyst.util.Builder<CopycatClient> {
    private Transport transport;
    private Serializer serializer;
    private Set<Address> members;

    private Builder(Collection<Address> members) {
      this.members = new HashSet<>(Assert.notNull(members, "members"));
    }

    @Override
    protected void reset() {
      transport = null;
      serializer = null;
      members = null;
    }

    /**
     * Sets the client transport.
     * <p>
     * By default, the client will use the {@code NettyTransport} with an event loop pool equal to
     * {@link Runtime#availableProcessors()}.
     *
     * @param transport The client transport.
     * @return The client builder.
     * @throws NullPointerException if {@code transport} is null
     */
    public Builder withTransport(Transport transport) {
      this.transport = Assert.notNull(transport, "transport");
      return this;
    }

    /**
     * Sets the client serializer.
     * <p>
     * By default, the client will use a {@link Serializer} configured with the {@link ServiceLoaderTypeResolver}.
     *
     * @param serializer The client serializer.
     * @return The client builder.
     * @throws NullPointerException if {@code serializer} is null
     */
    public Builder withSerializer(Serializer serializer) {
      this.serializer = Assert.notNull(serializer, "serializer");
      return this;
    }

    /**
     * @throws ConfigurationException if transport is not configured
     */
    @Override
    public CopycatClient build() {
      // If the transport is not configured, attempt to use the default Netty transport.
      if (transport == null) {
        try {
          transport = (Transport) Class.forName("io.atomix.catalyst.transport.NettyTransport").newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
          throw new ConfigurationException("transport not configured");
        }
      }

      // If no serializer instance was provided, create one.
      if (serializer == null) {
        serializer = new Serializer();
      }
      return new CopycatClient(transport, members, serializer);
    }
  }

}