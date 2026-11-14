// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Beans shared across the gateway: the clock, the HTTP client to upstreams.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Configuration
@EnableConfigurationProperties(AduanaProperties.class)
@EnableScheduling
public class GatewayConfiguration {

  /**
   * The one clock everything that depends on time reads.
   *
   * @return the UTC system clock
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The HTTP client for upstream calls, never following redirects so a server cannot bounce a call
   * somewhere else.
   *
   * @return the client
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean(destroyMethod = "close")
  public HttpClient upstreamHttpClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
  }
}
