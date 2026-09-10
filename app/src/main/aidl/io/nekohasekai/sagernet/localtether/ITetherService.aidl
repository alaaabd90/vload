package io.nekohasekai.sagernet.localtether;

interface ITetherService {

    String start(boolean logging);

    void setLogging(boolean enabled);

    String stop();

    String getStatus();

    String runProbes(boolean attemptTethering, int availabilityTimeoutMs);

    void clearLog();

    String checkCompatibility();

    int getContractVersion();

    // Terminates the daemon process. One-way: the caller can't wait for a reply
    // from a process that's exiting mid-transaction.
    oneway void shutdown();
}
