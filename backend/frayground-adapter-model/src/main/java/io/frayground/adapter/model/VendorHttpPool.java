package io.frayground.adapter.model;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

/**
 * Async HTTP pool owned by one model vendor.
 *
 * <p>The client, connection manager, and I/O reactor are not shared. One I/O
 * thread multiplexes the vendor's connections. {@code maxConnections} caps
 * both the pool and each route. A full pool waits inside the caller-supplied
 * budget instead of opening another thread.
 */
public final class VendorHttpPool implements AutoCloseable {
  private final String vendor;
  private final int maxConnections;
  private final PoolingAsyncClientConnectionManager connectionManager;
  private final CloseableHttpAsyncClient client;

  public VendorHttpPool(String vendor, int maxConnections) {
    if (vendor == null || vendor.isBlank()) {
      throw new IllegalArgumentException("vendor");
    }
    if (maxConnections < 1) {
      throw new IllegalArgumentException("maxConnections");
    }
    this.vendor = vendor;
    this.maxConnections = maxConnections;
    this.connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
        .setMaxConnTotal(maxConnections)
        .setMaxConnPerRoute(maxConnections)
        .setDefaultConnectionConfig(ConnectionConfig.custom()
            .setConnectTimeout(Timeout.of(Duration.ofSeconds(5)))
            .build())
        .build();
    this.client = HttpAsyncClients.custom()
        .setConnectionManager(connectionManager)
        .setIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(1).build())
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .build();
    this.client.start();
  }

  public String vendor() {
    return vendor;
  }

  public int maxConnections() {
    return maxConnections;
  }

  PoolingAsyncClientConnectionManager connectionManager() {
    return connectionManager;
  }

  CloseableHttpAsyncClient client() {
    return client;
  }

  public CompletionStage<SimpleHttpResponse> execute(SimpleHttpRequest request, long budgetMs) {
    CompletableFuture<SimpleHttpResponse> bridge = new CompletableFuture<>();
    Future<SimpleHttpResponse> http = client.execute(request, new FutureCallback<>() {
      @Override
      public void completed(SimpleHttpResponse result) {
        bridge.complete(result);
      }

      @Override
      public void failed(Exception exception) {
        bridge.completeExceptionally(exception);
      }

      @Override
      public void cancelled() {
        bridge.completeExceptionally(new CancellationException("http"));
      }
    });
    CompletableFuture<SimpleHttpResponse> timed = new CompletableFuture<>();
    bridge.orTimeout(budgetMs, TimeUnit.MILLISECONDS).whenComplete((result, error) -> {
      if (error != null) {
        http.cancel(true);
        timed.completeExceptionally(unwrap(error));
      } else {
        timed.complete(result);
      }
    });
    return timed;
  }

  @Override
  public void close() {
    client.close(CloseMode.GRACEFUL);
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
