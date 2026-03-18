package org.xxdc.oss.example.transport.http;

/**
 * Factory utility for creating HTTP transport instances.
 *
 * <p>Provides a centralized creation point for {@link HttpTransportServer} instances, following the
 * same factory pattern used by {@code TcpTransports} in the TCP module.
 */
public final class HttpTransports {

  private HttpTransports() {}

  /**
   * Creates a new {@link HttpTransportServer} instance.
   *
   * @return a new HTTP transport server ready for initialization
   */
  public static HttpTransportServer createServer() {
    return new HttpTransportServer();
  }
}
