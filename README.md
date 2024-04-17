Aerospike Circuit Breaker in Java
=================================

[![Aerospike Enterprise](https://img.shields.io/badge/Aerospike-Enterprise_Editition-C22127?labelColor=white&logo=aerospike&logoColor=C22127&style=flat)](https://aerospike.com/download/#aerospike-server-enterprise-edition)
[![Aerospike Community](https://img.shields.io/badge/Aerospike-Community_Editition-C22127?labelColor=white&logo=aerospike&logoColor=C22127&style=flat)](https://aerospike.com/download/#aerospike-server-community-edition)
[![Aerospike Java](https://img.shields.io/badge/Aerospike-Java_Client-C22127?labelColor=white&logo=aerospike&logoColor=C22127&style=flat)](https://aerospike.com/download/#aerospike-clients-java-client-library)

[![Build](https://github.com/aerospike-examples/aerospike-circuit-breaker-java/actions/workflows/build.yml/badge.svg)](https://github.com/aerospike-examples/aerospike-circuit-breaker-java/actions/workflows/build.yml)

[Aerospike](http://www.aerospike.com) is a low-latency distributed NoSQL database. This project is an example Java application that demonstrates how the "circuit breaker" design pattern is implemented in Aerospike. See my blog post [Aerospike Circuit Breaker Pattern](https://aerospike.com/blog/) for a complete discussion.

This project uses the asynchronous API of the Aerospike Java Client. You can read about how that works in [Understanding Asynchronous Operations](https://aerospike.com/developer/tutorials/java/async_ops) on Aerospike Developer Hub.

Questions, comments, feedback? Find me in the `#ask-the-community` channel in the [Aerospike Developer Discord server](https://discord.com/invite/NfC93wJEJU).

Set Up
------

Clone the repo and build the package:

```bash
git clone https://github.com/aerospike-examples/aerospike-circuit-breaker-java.git
cd aerospike-circuit-breaker-java
mvn package
```

For local testing you can run [Aerospike Database Enterprise in Docker](https://aerospike.com/docs/deploy_guides/docker/). Aerospike Enterprise comes with a free developer license for single-node configuration which is used for this example project.

The following command will run Aerospike Database Enterprise using the free developer license in a container listening on port

```bash
docker run -tid --name aerospike -p 3000:3000 \
-v "$(pwd)/docker/opt/aerospike/conf":/opt/aerospike/conf \
-e "FEATURE_KEY_FILE=/etc/aerospike/features.conf" \
aerospike/aerospike-server-enterprise \
--config-file /opt/aerospike/conf/aerospike.conf
```

Run the jar file passing in 3 command-line arguments: `HOST:PORT`, `MAX_ERROR_RATE`, and `WRITE_OPS`. The application will connect to the Aerospike Database running in Docker, set the [maxErrorRate](https://javadoc.io/doc/com.aerospike/aerospike-client/latest/com/aerospike/client/policy/ClientPolicy.html#maxErrorRate) policy to `MAX_ERROR_RATE`, and perform the number asynchronous write operations indicated by `WRITE_OPS`.

For example, run the application against the Aerospike Database running in docker at `172.17.0.2` on port `3000`, set [maxErrorRate](https://javadoc.io/doc/com.aerospike/aerospike-client/latest/com/aerospike/client/policy/ClientPolicy.html#maxErrorRate) to `100`, and perform 10,000 write operations:

```bash
java -jar \
target/aerospike-circuit-breaker-java-1.0-SNAPSHOT-jar-with-dependencies.jar \
172.17.0.2:3000 100 10000
```

Output
------

The application will output a summary at the end of the run:

```
Run time:                 0.283 seconds
Successful writes:        10000
Connection errors:        0
Timeout errors:           0
Max errors exceeded:      0
Node unavailable errors:  0
Other errors:             0
Connections opened:       96
Connections closed:       0
```

Demonstrating the Circuit Breaker
---------------------------------

### Step 1 - Run a steady-state baseline

Perform 5 million write operations with `maxErrorRate=0` (disabled):

```bash
java -jar \
target/aerospike-circuit-breaker-java-1.0-SNAPSHOT-jar-with-dependencies.jar \
172.17.0.2:3000 0 5000000
```

The output shows that all write operations were successful:

```
Run time:                 20.354 seconds
Successful writes:        5000000
Connection errors:        0
Timeout errors:           0
Max errors exceeded:      0
Node unavailable errors:  0
Other errors:             0
Connections opened:       96
Connections closed:       0
```

### Step 2 - Run with a 10 second period of latency without circuit breaker

Perform 5 million write operations with `maxErrorRate=0` (disabled). __While the application is running__, add 20ms of latency to the Docker network interface, wait ~10 seconds, then remove the latency:

_Terminal 1_
```bash
java -jar \
target/aerospike-circuit-breaker-java-1.0-SNAPSHOT-jar-with-dependencies.jar \
172.17.0.2:3000 0 5000000
```

_Terminal 2_
```bash
sudo tc qdisc add dev docker0 root netem delay 20ms;
sleep 10;
sudo tc qdisc del dev docker0 root netem delay 20ms
```

The output now shows that the application slowed down while waiting on sockets (lower TPS), many timeout errors, and that each of those timeouts resulted in a churned connection:

```
Run time:                 31.616 seconds
Successful writes:        4991677
Connection errors:        0
Timeout errors:           8323
Max errors exceeded:      0
Node unavailable errors:  0
Other errors:             0
Connections opened:       8419
Connections closed:       8323
```

### Step 3 - Run with a 10 second period of latency with circuit breaker

Perform 5 million write operations with `maxErrorRate=100` (default). __While the application is running__, add 20ms of latency to the Docker network interface, wait 10 seconds, then remove the latency:

_Terminal 1_
```bash
java -jar \
target/aerospike-circuit-breaker-java-1.0-SNAPSHOT-jar-with-dependencies.jar \
172.17.0.2:3000 100 5000000
```

_Terminal 2_
```bash
sudo tc qdisc add dev docker0 root netem delay 20ms;
sleep 10;
sudo tc qdisc del dev docker0 root netem delay 20ms
```

The output now shows that the application didn't slow down waiting on sockets (same or better TPS), timeout errors were capped and thus connections churned were capped. 

```
Run time:                 16.205 seconds
Successful writes:        1373172
Connection errors:        0
Timeout errors:           1428
Max errors exceeded:      3625400
Node unavailable errors:  0
Other errors:             0
Connections opened:       1524
Connections closed:       1428
```

In a real-world scenario, the exception handler for the `MAX_ERROR_RATE` would be able handle the back pressure according to the use case. For example, if the application is still within SLA and/or there isn't an end-user waiting on a response, the failed operations can be queued for a retry later when the network stabilizes.

```java
public void onFailure(AerospikeException e) {

    switch (e.getResultCode()) {
        // ...
        case ResultCode.MAX_ERROR_RATE:
            // Exceeded max number of errors per tend interval (~1s)
            // The operation can be queued for retry with something like
            // like an exponential backoff, captured in a dead letter
            // queue, etc.
            break;
        // ...
    }
}
```