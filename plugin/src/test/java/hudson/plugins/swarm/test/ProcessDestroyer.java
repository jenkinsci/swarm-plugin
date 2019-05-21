package hudson.plugins.swarm.test;

import java.util.HashSet;
import java.util.Set;

/**
 * Records processes started during Swarm Client integration tests so that they can be destroyed at
 * the end of each test method.
 */
public final class ProcessDestroyer {

    private final Set<Process> processes = new HashSet<>();

    /** Record a spawned process for later cleanup. */
    public void record(Process process) {
        processes.add(process);
    }

    /** Clean up all processes that were spawned during the test. */
    public void clean() throws InterruptedException {
        for (Process process : processes) {
            process.destroy();
            process.waitFor();
        }
        processes.clear();
    }
}
