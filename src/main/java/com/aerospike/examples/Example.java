package com.aerospike.examples;

import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.Bin;
import com.aerospike.client.Info;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.proxy.AerospikeClientFactory;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.ResultCode;
import com.aerospike.client.async.EventPolicy;
import com.aerospike.client.async.EventLoop;
import com.aerospike.client.async.EventLoops;
import com.aerospike.client.async.NettyEventLoops;
import com.aerospike.client.async.Monitor;
import com.aerospike.client.async.Throttles;
import com.aerospike.client.listener.WriteListener;

import io.netty.channel.nio.NioEventLoopGroup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Example {
    private final AtomicInteger currentCount = new AtomicInteger();
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger connectErrorCounter = new AtomicInteger();
    private final AtomicInteger maxErrorCounter = new AtomicInteger();
    private final AtomicInteger timeoutErrorCounter = new AtomicInteger();
    private final AtomicInteger otherErrorCounter = new AtomicInteger();
    private final AtomicInteger nodeUnavailableCounter = new AtomicInteger();
    private Throttles throttles;

    public void runExample(Host[] hosts, int maxErrorRate, int write_ops) {

        ClientPolicy policy = new ClientPolicy();
        EventPolicy eventPolicy = new EventPolicy();

        // the maxErrorRate acts as the "circuit breaker"
        policy.maxErrorRate = maxErrorRate;

        // use the Aerospike client in async mode
        int numLoops = Runtime.getRuntime().availableProcessors();
        int maxCommands = 96 / numLoops;
        eventPolicy.maxCommandsInProcess = maxCommands;
        eventPolicy.maxCommandsInQueue = write_ops;
        throttles = new Throttles(numLoops, maxCommands);
        NioEventLoopGroup eventGroup = new NioEventLoopGroup(numLoops);
        EventLoops eventLoops = new NettyEventLoops(eventPolicy, eventGroup);
        policy.eventLoops = eventLoops;

        System.out.println("Loops=" + numLoops + ", Max Commands=" + maxCommands);

        IAerospikeClient client = AerospikeClientFactory.getClient(policy, false, hosts);

        // check the number of opened and closed connections before and after running the operations
        // to track "connection churn"
        int startConnsOpened = 0;
        int startConnsClosed = 0;
        int stopConnsOpened = 0;
        int stopConnsClosed = 0;
        Node node = client.getNodes()[0];

        try {
            startConnsOpened = Integer.parseInt(getStat(node, "client_connections_opened"));
            startConnsClosed = Integer.parseInt(getStat(node, "client_connections_closed"));
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        // lower the socket timeout in the write policy to 10ms so that introducing a latency
        // greater than 10ms results in a timeout error
        WritePolicy writePolicy = new WritePolicy(client.getWritePolicyDefault());
        writePolicy.connectTimeout = 100; // 1s
        writePolicy.socketTimeout = 10; // 10ms

        // submit n async write operations where n is the "write_ops" passed in as an argument
        Monitor monitor = new Monitor();

        // track the total run time
        long startTime = System.nanoTime();

        for (int i = 1; i <= write_ops; i++) {
            Key key = new Key("testNamespace", "testSet", i);
            Bin bin = new Bin("testBin", "testValue");

            EventLoop eventLoop = eventLoops.next();
            int loopIndex = eventLoop.getIndex();

            if (throttles.waitForSlot(loopIndex, 1)) {
                try {
                    client.put(eventLoop, new WriteHandler(monitor, loopIndex, write_ops), writePolicy, key, bin);
                }
                catch (AerospikeException e) {
                    throttles.addSlot(loopIndex, 1);
                    System.out.println(e.getMessage());
                }
            }
        }

        monitor.waitTillComplete();

        // calculate total run time
        long endTime = System.nanoTime();
        double runTime = TimeUnit.MILLISECONDS.convert((endTime - startTime), TimeUnit.NANOSECONDS)  / 1000.0;

        // store the number of opened and closed connections after running the operations
        try {
            stopConnsOpened = Integer.parseInt(getStat(node, "client_connections_opened"));
            stopConnsClosed = Integer.parseInt(getStat(node, "client_connections_closed"));
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        System.out.println();
        System.out.println("Run time:                 " + runTime + " seconds");
        System.out.println("Successful writes:        " + successCount);
        System.out.println("Connection errors:        " + connectErrorCounter);
        System.out.println("Timeout errors:           " + timeoutErrorCounter);
        System.out.println("Max errors exceeded:      " + maxErrorCounter);
        System.out.println("Node unavailable errors:  " + nodeUnavailableCounter);
        System.out.println("Other errors:             " + otherErrorCounter);
        System.out.println("Connections opened:       " + (stopConnsOpened - startConnsOpened));
        System.out.println("Connections closed:       " + (stopConnsClosed - startConnsClosed));

        eventLoops.close();
        client.close();
    }

    private class WriteHandler implements WriteListener {
        private final Monitor monitor;
        private final int loopIndex;
        private final int totalCount;

        public WriteHandler(Monitor monitor, int loopIndex, int totalCount) {
            this.monitor = monitor;
            this.loopIndex = loopIndex;
            this.totalCount = totalCount;
        }

        public void onSuccess(Key key) {
            throttles.addSlot(loopIndex, 1);
            currentCount.getAndIncrement();
            successCount.getAndIncrement();
            showProgress();
            notifyIfComplete();
        }

        public void onFailure(AerospikeException e) {
            throttles.addSlot(loopIndex, 1);
            currentCount.getAndIncrement();

            switch (e.getResultCode()) {
                case ResultCode.SERVER_NOT_AVAILABLE:
                    // A connection error occurred which would cause the connection to close.
                    connectErrorCounter.getAndIncrement();
                    //System.out.println(ae.getMessage());
                    break;
                case ResultCode.TIMEOUT:
                    // A socket timeout occurred which would cause the connection to close.
                    timeoutErrorCounter.getAndIncrement();
                    break;
                case ResultCode.MAX_ERROR_RATE:
                    // The maximum number of errors per tend interval (~1s) occurred which WILL NOT cause the
                    // connection to close.
                    maxErrorCounter.getAndIncrement();
                    break;
                case ResultCode.INVALID_NODE_ERROR:
                    // There are no nodes available in the cluster which would be the case in this single-node
                    // example when the network to that one node is broken.
                    nodeUnavailableCounter.getAndIncrement();
                    break;
                default:
                    System.out.println(e.getMessage());
                    otherErrorCounter.getAndIncrement();
            }

            showProgress();
            notifyIfComplete();
        }

        private void showProgress() {
            // progress indicator
            int tenPercent = Math.floorDiv(totalCount, 10);
            int current = currentCount.get();
            if (current % tenPercent == 0) {
                System.out.println(current + " of " + totalCount + " records written");
            }
        }

        private void notifyIfComplete() {
            if (currentCount.get() >= totalCount) {
                monitor.notifyComplete();
            }
        }
    }

    private static String getStat(Node node, String stat) throws Exception {
        String statsLine = Info.request(null, node, "statistics");
        String[] stats = statsLine.split(";");

        for (String value : stats) {
            String[] tokens = value.split("=");
            if (tokens[0].equals(stat)) return tokens[1];
        }

        throw new Exception("Stat not found: " + stat);
    }

    public static void main(String[] args) {

        if (args.length < 3) {
            usage();
            System.exit(-1);
        }

        // parse command-line arguments
        Host[] hosts = Host.parseHosts(args[0], 3000);
        int maxErrorRate = Integer.parseInt(args[1]);
        int write_ops = Integer.parseInt(args[2]);

        Example example = new Example();
        example.runExample(hosts, maxErrorRate, write_ops);
    }

    private static void usage() {
        System.out.println("Arguments:HOST:PORT  MAX_ERROR_RATE WRITE_OPS");
        System.out.println("Example: 172.17.0.2:3000 5 10000");
    }
}
