package hudson.plugins.swarm;

import org.junit.Test;

import static org.junit.Assert.*;

public class RetryBackOffStrategyTest {

    @Test
    public void should_return_same_interval_for_no_backoff() {
        RetryBackOffStrategy none = RetryBackOffStrategy.NONE;
        assertEquals(10, none.waitForRetry(0, 10, 30));
        assertEquals(10, none.waitForRetry(1, 10, 30));
        assertEquals(10, none.waitForRetry(2, 10, 30));
        assertEquals(10, none.waitForRetry(3, 10, 30));
    }

    @Test
    public void should_increase_interval_for_linear_backoff() {
        RetryBackOffStrategy linear = RetryBackOffStrategy.LINEAR;
        assertEquals(10, linear.waitForRetry(0, 10, 30));
        assertEquals(20, linear.waitForRetry(1, 10, 30));
        assertEquals(30, linear.waitForRetry(2, 10, 30));
        assertEquals(30, linear.waitForRetry(3, 10, 30));
    }

    @Test
    public void should_double_interval_up_to_max_for_exponential_backoff() {
        RetryBackOffStrategy exponential = RetryBackOffStrategy.EXPONENTIAL;
        assertEquals(10, exponential.waitForRetry(0, 10, 100));
        assertEquals(20, exponential.waitForRetry(1, 10, 100));
        assertEquals(40, exponential.waitForRetry(2, 10, 100));
        assertEquals(80, exponential.waitForRetry(3, 10, 100));
        assertEquals(100, exponential.waitForRetry(4, 10, 100));
    }

}
