package hudson.plugins.swarm;

import java.security.SecureRandom;

public enum RetryBackOffStrategy {

    NONE {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime, boolean useJitter) {
            if (useJitter) {
                interval = jitter(0, interval);
            }
            return Math.min(maxTime, interval);
        }
    },

    LINEAR {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime, boolean useJitter) {
            if (useJitter) {
                interval = jitter(0, interval);
            }
            return Math.min(maxTime, interval * (retry + 1));
        }
    },

    EXPONENTIAL {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime, boolean useJitter) {
            if (useJitter) {
                interval = jitter(0, interval);
            }
            return Math.min(maxTime, interval * (int) Math.pow(2, retry));
        }
    };

    private static int jitter(int min, int max) {
        Random rng = new SecureRandom();
        return Math.round(min + (rng.nextFloat() * (max-min)));
    };

    abstract int waitForRetry(int retry, int interval, int maxTime, boolean useJitter);

}
