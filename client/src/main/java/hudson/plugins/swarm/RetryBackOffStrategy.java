package hudson.plugins.swarm;

import java.security.SecureRandom;

public enum RetryBackOffStrategy {

    NONE {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime, int retryIntervalJitter) {
            return Math.min(maxTime, interval + jitter(0, retryIntervalJitter));
        }
    },

    LINEAR {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime, int retryIntervalJitter) {
            return Math.min(maxTime, (interval + jitter(0, retryIntervalJitter)) * (retry + 1));
        }
    },

    EXPONENTIAL {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime, int retryIntervalJitter) {
            return Math.min(maxTime, (interval + jitter(0, retryIntervalJitter)) * (int) Math.pow(2, retry));
        }
    };

    private static int jitter(int min, int max) {
        SecureRandom rng = new SecureRandom();
        return Math.round(min + (rng.nextFloat() * (max - min)));
    };

    abstract int waitForRetry(int retry, int interval, int maxTime, int retryIntervalJitter);

}
