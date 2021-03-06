package org.ruk.pipelining;

import com.beust.jcommander.JCommander;
import org.ruk.pipelining.config.Args;
import org.ruk.pipelining.config.TimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TestRunner {
    private static final Logger log = LoggerFactory.getLogger(TestRunner.class);

    public static void main(String[] argv) throws InterruptedException {
        // Use JCommander for argument parsing
        Args args = new Args();
        JCommander.newBuilder().addObject(args).build().parse(argv);

        String url = args.getUrl();
        int noOfConnections = args.getNoOfConnections();
        int noOfMessages = args.getNoOfMessages();
        int payloadInBytes = args.getPayloadInBytes();
        int testTimeInMinutes = args.getTestTimeInMinutes();
        long messageDelay = args.getMessageDelay();

        CountDownLatch countDownLatch = new CountDownLatch(noOfConnections);
        List<PipeliningRunner> pipeliningRunners = new LinkedList<>();
        ExecutorService executor = Executors.newFixedThreadPool(noOfConnections);
        log.info("Creating connections...");

        long testStartTime = 0L;
        try {
            for (int clientId = 0; clientId < noOfConnections; clientId++) {
                PipeliningRunner pipeliningRunner = new PipeliningRunner(
                        clientId, url, testTimeInMinutes > 0 ? -1 : noOfMessages, payloadInBytes, messageDelay,
                        countDownLatch);
                pipeliningRunners.add(pipeliningRunner);
                if (clientId == 0L) {
                    testStartTime = System.currentTimeMillis();
                }
                executor.execute(pipeliningRunner);
            }

            if (testTimeInMinutes > 0) {
                Thread.sleep((long) testTimeInMinutes * 60 * 1000);
                pipeliningRunners.forEach(
                        pipeliningRunnerConsumer -> pipeliningRunnerConsumer.setStopSendingMessages(true));
            }

            if (!countDownLatch.await(Long.MAX_VALUE, TimeUnit.SECONDS)) {
                log.error("Latch countdown without completion");
            }

        } finally {
            long testEndTime = System.currentTimeMillis();
            executor.shutdown();
            long totalNoOfMessages = 0;
            double totalTPS = 0;
            int totalNoOfErrorMessages = 0;
            for (PipeliningRunner pi : pipeliningRunners) {
                totalNoOfMessages = totalNoOfMessages + pi.getNoOfMessagesReceived();
                totalNoOfErrorMessages = totalNoOfErrorMessages + pi.getNoOfErrorMessages();
                double tps = calculateTPS(noOfMessages, pi);
                totalTPS = totalTPS + tps;
               // log.info("Client {}: Test run TPS: {}", pi.getClientId(), tps);
            }

          //  log.info("Average TPS per client: {}", (totalTPS / noOfConnections));

            TimeFormatter timeFormatter = new TimeFormatter(testTimeInMinutes > 0 ? testTimeInMinutes * 60 * 1000 :
                    testEndTime - testStartTime);
            log.info("Total time taken for the test: {}hr {}min {}sec {}ms", timeFormatter.getHours(),
                    timeFormatter.getMinutes(), timeFormatter.getSeconds(), timeFormatter.getMilliSeconds());

            log.info("Max no of concurrent connections: {}/{}", PipeliningRunner.getMaxNoOfActiveConnections(),
                    noOfConnections);

         //   log.info("Total no of message round trips: {}", totalNoOfMessages);

            log.info("No of error messages: {} out of {}", totalNoOfErrorMessages, totalNoOfMessages);

            log.info("Throughput: {}", getThroughput(testStartTime, testEndTime, totalNoOfMessages));

            log.info("Done!");
        }
    }

    private static double calculateTPS(int noOfMessages, PipeliningRunner pi) {
        double timeInSecs = getTimeInSecs(pi.getStartTime(), pi.getEndTime());
        return (double) noOfMessages / timeInSecs;
    }

    private static double getThroughput(long testStartTime, long testEndTime, long totalNoOfMessages) {
        long totalTimeInMillis = testEndTime - testStartTime;
        double totalTimeInSecs = (double) totalTimeInMillis / 1000;
        return (double) totalNoOfMessages / totalTimeInSecs;
    }

    private static double getTimeInSecs(long startTime, long endTime) {
        return (double) (endTime - startTime) / 1000;
    }

}
