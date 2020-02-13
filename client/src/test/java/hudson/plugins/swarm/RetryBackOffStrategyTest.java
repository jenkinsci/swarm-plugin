package hudson.plugins.swarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import org.junit.Test;

public class RetryBackOffStrategyTest {

    @Test
    public void should_return_same_interval_for_no_backoff() {
        RetryBackOffStrategy none = RetryBackOffStrategy.NONE;
        assertEquals(10, none.waitForRetry(0, 10, 30, false));
        assertEquals(10, none.waitForRetry(1, 10, 30, false));
        assertEquals(10, none.waitForRetry(2, 10, 30, false));
        assertEquals(10, none.waitForRetry(3, 10, 30, false));
    }

    @Test
    public void should_increase_interval_for_linear_backoff() {
        RetryBackOffStrategy linear = RetryBackOffStrategy.LINEAR;
        assertEquals(10, linear.waitForRetry(0, 10, 30, false));
        assertEquals(20, linear.waitForRetry(1, 10, 30, false));
        assertEquals(30, linear.waitForRetry(2, 10, 30, false));
        assertEquals(30, linear.waitForRetry(3, 10, 30, false));
    }

    @Test
    public void should_double_interval_up_to_max_for_exponential_backoff() {
        RetryBackOffStrategy exponential = RetryBackOffStrategy.EXPONENTIAL;
        assertEquals(10, exponential.waitForRetry(0, 10, 100, false));
        assertEquals(20, exponential.waitForRetry(1, 10, 100, false));
        assertEquals(40, exponential.waitForRetry(2, 10, 100, false));
        assertEquals(80, exponential.waitForRetry(3, 10, 100, false));
        assertEquals(100, exponential.waitForRetry(4, 10, 100, false));
    }

    @Test
    public void should_return_random_intervals_when_jitter_enabled() {
        RetryBackOffStrategy none = RetryBackOffStrategy.NONE;
        HashSet<Integer> waits = new HashSet();
        for(int i=0; i<10; i++) {
            int waitTime = none.waitForRetry(i, 10, 30, true);
            waits.add(waitTime);
            assertTrue(waitTime >= 0);
            assertTrue(waitTime <= 10);
        }
        // Verify randomization is happenning
        assertNotEquals(waits.size(), 1);
    }

}
