package hudson.plugins.swarm;

public enum RetryBackOffStrategy {

    NONE {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime) {
            return Math.min(maxTime, interval);
        }
    },

    LINEAR {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime) {
            return Math.min(maxTime, interval * (retry + 1));
        }
    },

    EXPONENTIAL {
        @Override
        public int waitForRetry(int retry, int interval, int maxTime) {
            return Math.min(maxTime, interval * (int) Math.pow(2, retry));
        }
    };

    abstract int waitForRetry(int retry, int interval, int maxTime);

}
