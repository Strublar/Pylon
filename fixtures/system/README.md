Two services for Pylon's cross-service linking tests: `gateway` calls `catalog` over HTTP
(sttp, http4s client, Pekko HTTP client, Play WS, Tapir client), gRPC (fs2-grpc client, ScalaPB
server) and Kafka. `catalog.proto` and `CatalogEndpoints` are copied into both builds, the way a
shared artifact would provide them.
