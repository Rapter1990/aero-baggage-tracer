package com.aerotrace.search.base;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.aerotrace.common.event.Topics;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.constant.SearchTopics;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Elasticsearch (security on, the pinned image whose version is the client's) and a real
 * Kafka in Docker for every Spring test, started once per JVM and shared, so tests must not assume
 * empty indexes: each uses its own bag tags and ids. Tests are skipped when Docker is not
 * available, except in CI, where that is a failure.
 */
@ActiveProfiles("test")
public abstract class AbstractSearchTest {

  /** The client and the server image are the same version (set by the build, see the POM). */
  public static final String ELASTICSEARCH_VERSION =
      System.getProperty("elasticsearch.version", "9.2.8");

  public static final String ELASTICSEARCH_IMAGE =
      "docker.elastic.co/elasticsearch/elasticsearch:" + ELASTICSEARCH_VERSION;

  /** Test-only password of the throwaway container. */
  private static final String ELASTIC_PASSWORD = "test-elastic-password";

  private static final GenericContainer<?> ELASTICSEARCH =
      new GenericContainer<>(DockerImageName.parse(ELASTICSEARCH_IMAGE))
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "true")
          .withEnv("xpack.security.http.ssl.enabled", "false")
          .withEnv("xpack.security.transport.ssl.enabled", "false")
          .withEnv("xpack.ml.enabled", "false")
          .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
          .withEnv("ELASTIC_PASSWORD", ELASTIC_PASSWORD)
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withExposedPorts(9200)
          .waitingFor(
              Wait.forHttp("/_cluster/health")
                  .forPort(9200)
                  .withBasicCredentials("elastic", ELASTIC_PASSWORD)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(ELASTICSEARCH::stop));
    Runtime.getRuntime().addShutdownHook(new Thread(KAFKA::stop));
  }

  @BeforeAll
  static void startInfrastructure() {
    boolean docker = DockerClientFactory.instance().isDockerAvailable();
    if (System.getenv("CI") != null) {
      if (!docker) {
        throw new AssertionError("Docker is required to run the search-service tests in CI");
      }
    } else {
      assumeTrue(docker, "Docker is not available");
    }
    start();
  }

  private static void start() {
    synchronized (ELASTICSEARCH) {
      if (!ELASTICSEARCH.isRunning()) {
        ELASTICSEARCH.start();
      }
      if (!KAFKA.isRunning()) {
        KAFKA.start();
        createTopics();
      }
    }
  }

  private static void createTopics() {
    Properties admin = new Properties();
    admin.put("bootstrap.servers", KAFKA.getBootstrapServers());
    try (AdminClient client = AdminClient.create(admin)) {
      client
          .createTopics(
              List.of(
                  new NewTopic(Topics.CASE_CHANGED, 2, (short) 1),
                  new NewTopic(Topics.SCAN_RECEIVED, 2, (short) 1),
                  new NewTopic(Topics.RISK_CHANGED, 2, (short) 1),
                  new NewTopic(SearchTopics.BAG_REGISTERED, 2, (short) 1),
                  new NewTopic(Topics.DEAD_LETTER, 1, (short) 1)))
          .all()
          .get();
    } catch (Exception e) {
      throw new IllegalStateException("topics not created", e);
    }
  }

  /** Freezes Elasticsearch (it stops answering, connections hang) until {@link #resume}. */
  protected static void pauseElasticsearch() {
    DockerClientFactory.instance()
        .client()
        .pauseContainerCmd(ELASTICSEARCH.getContainerId())
        .exec();
  }

  protected static void resumeElasticsearch() {
    DockerClientFactory.instance()
        .client()
        .unpauseContainerCmd(ELASTICSEARCH.getContainerId())
        .exec();
  }

  public static String kafkaBootstrapServers() {
    start();
    return KAFKA.getBootstrapServers();
  }

  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.elasticsearch.uris",
        () -> {
          start();
          return "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200);
        });
    registry.add("spring.elasticsearch.password", () -> ELASTIC_PASSWORD);
    registry.add("spring.kafka.bootstrap-servers", AbstractSearchTest::kafkaBootstrapServers);
  }

  protected static RequestPostProcessor as(Role role) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            token -> {
              token.subject("user-" + role.name().toLowerCase(Locale.ROOT));
              token.claim(JwtClaims.ROLES, List.of(role.name()));
            })
        .authorities(new SimpleGrantedAuthority(role.authority()));
  }

  /** A ten-digit bag tag no other test uses. */
  protected static String newBagTag() {
    long value = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 10_000_000_000L);
    return String.format("%010d", value);
  }

  protected static String newId(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
