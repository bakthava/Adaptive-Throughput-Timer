package com.adaptive.jmeter.plugins;

import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.timers.Timer;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterThread;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Adaptive Timer that reads CSV file with time/TPS targets and adjusts thread count dynamically
 */
public class AdaptiveTimerFromCSV extends AbstractTestElement implements Timer {

    private static final long serialVersionUID = 1L;

    // Properties
    public static final String CSV_FILE_PATH = "csvFilePath";
    public static final String MIN_THREADS = "minThreads";
    public static final String MAX_THREADS = "maxThreads";
    public static final String ADJUSTMENT_INTERVAL_MS = "adjustmentIntervalMs";
    public static final String RAMP_UP_STEP = "rampUpStep";
    public static final String RAMP_DOWN_STEP = "rampDownStep";
    public static final String P90_THRESHOLD_MS = "p90ThresholdMs";
    public static final String START_TIME = "startTime";
    public static final String END_TIME = "endTime";
    public static final String DEFAULT_START_TIME = "defaultStartTime";
    public static final String RANGE_MODE = "rangeMode"; // "default", "step", or "time"
    public static final String INFINITE_EXECUTION = "infiniteExecution"; // true/false for 24-hour cycling

    // Runtime state
    private static volatile List<CSVThroughputEntry> csvEntries;
    private static volatile ThroughputMetrics metrics;
    private static volatile long testStartTime;
    private static volatile long lastAdjustmentTime;
    private static volatile int currentThreadTarget = 1;
    private static volatile boolean initialized = false;
    private static final Object INIT_LOCK = new Object();

    @Override
    public long delay() {
        // Initialize on first call
        if (!initialized) {
            synchronized (INIT_LOCK) {
                if (!initialized) {
                    initializeTest();
                }
            }
        }

        // Record sample start
        long sampleStartTime = System.currentTimeMillis();

        // Check if we need to adjust threads every N milliseconds
        if (System.currentTimeMillis() - lastAdjustmentTime >= getAdjustmentIntervalMs()) {
            adjustThreadCount();
            lastAdjustmentTime = System.currentTimeMillis();
        }

        // Return delay based on target TPS
        long elapsedTime = System.currentTimeMillis() - testStartTime;
        
        // Apply 24-hour cycling if infinite execution is enabled
        long adjustedElapsedTime = elapsedTime;
        if (isInfiniteExecution()) {
            long twentyFourHoursMs = 24 * 60 * 60 * 1000; // 86400000 ms
            adjustedElapsedTime = elapsedTime % twentyFourHoursMs;
        }
        
        int targetTps = CSVThroughputReader.getTargetTpsForTime(csvEntries, adjustedElapsedTime);

        if (targetTps <= 0) {
            return 0; // No delay if TPS not specified
        }

        long delayPerSample = 1000 / targetTps; // milliseconds between samples
        return Math.max(0, delayPerSample);
    }

    /**
     * Initialize test - read file and setup metrics
     */
    private void initializeTest() {
        try {
            String filePath = getCsvFilePath();
            csvEntries = CSVThroughputReader.readFile(filePath);
            
            if (csvEntries.isEmpty()) {
                throw new RuntimeException("No valid entries found in file: " + filePath);
            }

            metrics = new ThroughputMetrics(1000); // 1-second window
            testStartTime = System.currentTimeMillis();
            lastAdjustmentTime = testStartTime;
            
            // Validate 24-hour coverage if infinite execution is enabled
            if (isInfiniteExecution()) {
                ValidationResult validation = validate24HourCoverage();
                if (!validation.isValid) {
                    throw new RuntimeException(validation.errorMessage);
                }
                System.out.println("Infinite execution enabled - 24-hour coverage validated successfully");
            }
            
            String rangeMode = getRangeMode();
            
            // If default mode, adjust start time based on current system time
            if ("default".equals(rangeMode)) {
                String defaultStartTime = getDefaultStartTime(); // HH:mm format
                long offsetMs = calculateOffsetFromCurrentTime(defaultStartTime);
                testStartTime = System.currentTimeMillis() - offsetMs;
                System.out.println("Default mode: Current time offset = " + offsetMs + "ms from " + defaultStartTime);
            }
            
            // Start with Min Threads instead of Initial Threads
            currentThreadTarget = (int) getMinThreads();

            System.out.println("AdaptiveTimerFromCSV initialized:");
            System.out.println("  File: " + filePath);
            System.out.println("  Total entries: " + csvEntries.size());
            System.out.println("  Mode: " + rangeMode);
            System.out.println("  Infinite Execution: " + isInfiniteExecution());
            System.out.println("  Starting threads (min): " + currentThreadTarget);
            System.out.println("  Entries: " + csvEntries);

            initialized = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + e.getMessage(), e);
        }
    }

    /**
     * Calculate offset in milliseconds from now till the specified HH:mm time
     * If the time has already passed today, calculate for tomorrow
     */
    private long calculateOffsetFromCurrentTime(String hhmmTime) {
        try {
            java.util.Calendar now = java.util.Calendar.getInstance();
            int currentHour = now.get(java.util.Calendar.HOUR_OF_DAY);
            int currentMinute = now.get(java.util.Calendar.MINUTE);
            int currentSecond = now.get(java.util.Calendar.SECOND);
            
            String[] parts = hhmmTime.split(":");
            int targetHour = Integer.parseInt(parts[0]);
            int targetMinute = Integer.parseInt(parts[1]);
            
            // Calculate total minutes for current and target times
            int currentTotalMinutes = currentHour * 60 + currentMinute;
            int targetTotalMinutes = targetHour * 60 + targetMinute;
            
            // Calculate difference
            long offsetMs = 0;
            if (targetTotalMinutes > currentTotalMinutes) {
                // Target time is later today
                offsetMs = (targetTotalMinutes - currentTotalMinutes) * 60 * 1000 - (currentSecond * 1000);
            } else {
                // Target time is tomorrow (or has passed)
                offsetMs = ((24 * 60) - currentTotalMinutes + targetTotalMinutes) * 60 * 1000 - (currentSecond * 1000);
            }
            
            return Math.max(0, offsetMs);
        } catch (Exception e) {
            System.err.println("Error parsing default start time: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Adjust thread count based on current vs target TPS
     */
    private void adjustThreadCount() {
        if (csvEntries == null || csvEntries.isEmpty() || metrics == null) {
            return;
        }

        long elapsedTime = System.currentTimeMillis() - testStartTime;
        int targetTps = CSVThroughputReader.getTargetTpsForTime(csvEntries, elapsedTime);

        if (targetTps <= 0) {
            return;
        }

        double currentTps = metrics.getCurrentThroughput();
        long p90Latency = metrics.get90thPercentile();

        double tpsDifference = targetTps - currentTps;
        double tpsPercentageDiff = (tpsDifference / targetTps) * 100;

        // Decision logic
        int newThreadTarget = currentThreadTarget;

        if (Math.abs(tpsPercentageDiff) > 5) { // Threshold: 5% deviation
            if (tpsPercentageDiff > 0) {
                // Current TPS is lower than target - increase threads
                newThreadTarget = Math.min(
                    (int) getMaxThreads(),
                    currentThreadTarget + (int) getRampUpStep()
                );
            } else if (tpsPercentageDiff < -10 && p90Latency < getP90ThresholdMs()) {
                // Current TPS is much higher than target and latency is good - can reduce threads
                newThreadTarget = Math.max(
                    (int) getMinThreads(),
                    currentThreadTarget - (int) getRampDownStep()
                );
            }
        }

        if (newThreadTarget != currentThreadTarget) {
            System.out.println(String.format(
                "[%dms] Adjustment: Current TPS=%.2f, Target=%d, P90=%.2fms, " +
                "Threads: %d -> %d",
                elapsedTime, currentTps, targetTps, (double) p90Latency,
                currentThreadTarget, newThreadTarget
            ));
            currentThreadTarget = newThreadTarget;
            updateThreadCount(newThreadTarget);
        }

        // Reset metrics for next window
        metrics.reset();
    }

    /**
     * Update thread count in JMeter context
     */
    private void updateThreadCount(int newThreadCount) {
        try {
            // This would need to be implemented using JMeter's thread controller APIs
            // For now, just log the intention
            System.out.println("Thread count adjustment requested: " + newThreadCount);
        } catch (Exception e) {
            System.err.println("Error updating thread count: " + e.getMessage());
        }
    }

    /**
     * Record response time (called by sampler)
     */
    public void recordResponseTime(long responseTimeMs) {
        if (metrics != null) {
            metrics.recordResponseTime(responseTimeMs);
        }
    }

    /**
     * Record error (called by sampler)
     */
    public void recordError() {
        if (metrics != null) {
            metrics.recordError();
        }
    }

    // Property getters and setters

    public void setCsvFilePath(String path) {
        setProperty(new StringProperty(CSV_FILE_PATH, path));
    }

    public String getCsvFilePath() {
        return getPropertyAsString(CSV_FILE_PATH);
    }

    public void setMinThreads(long threads) {
        setProperty(new LongProperty(MIN_THREADS, threads));
    }

    public long getMinThreads() {
        return getPropertyAsLong(MIN_THREADS, 1);
    }

    public void setMaxThreads(long threads) {
        setProperty(new LongProperty(MAX_THREADS, threads));
    }

    public long getMaxThreads() {
        return getPropertyAsLong(MAX_THREADS, 100);
    }

    public void setAdjustmentIntervalMs(long interval) {
        setProperty(new LongProperty(ADJUSTMENT_INTERVAL_MS, interval));
    }

    public long getAdjustmentIntervalMs() {
        return getPropertyAsLong(ADJUSTMENT_INTERVAL_MS, 5000); // Default: 5 seconds
    }

    public void setRampUpStep(long step) {
        setProperty(new LongProperty(RAMP_UP_STEP, step));
    }

    public long getRampUpStep() {
        return getPropertyAsLong(RAMP_UP_STEP, 1);
    }

    public void setRampDownStep(long step) {
        setProperty(new LongProperty(RAMP_DOWN_STEP, step));
    }

    public long getRampDownStep() {
        return getPropertyAsLong(RAMP_DOWN_STEP, 1);
    }

    public void setP90ThresholdMs(long threshold) {
        setProperty(new LongProperty(P90_THRESHOLD_MS, threshold));
    }

    public long getP90ThresholdMs() {
        return getPropertyAsLong(P90_THRESHOLD_MS, 500); // Default: 500ms
    }

    public void setStartTime(String time) {
        setProperty(new StringProperty(START_TIME, time));
    }

    public String getStartTime() {
        return getPropertyAsString(START_TIME, "00:00");
    }

    public void setEndTime(String time) {
        setProperty(new StringProperty(END_TIME, time));
    }

    public String getEndTime() {
        return getPropertyAsString(END_TIME, "00:00");
    }

    public void setDefaultStartTime(String time) {
        setProperty(new StringProperty(DEFAULT_START_TIME, time));
    }

    public String getDefaultStartTime() {
        return getPropertyAsString(DEFAULT_START_TIME, "00:00");
    }

    public void setRangeMode(String mode) {
        setProperty(new StringProperty(RANGE_MODE, mode));
    }

    public String getRangeMode() {
        return getPropertyAsString(RANGE_MODE, "step"); // Default: "step" mode
    }

    public void setInfiniteExecution(boolean infinite) {
        setProperty(new StringProperty(INFINITE_EXECUTION, String.valueOf(infinite)));
    }

    public boolean isInfiniteExecution() {
        return Boolean.parseBoolean(getPropertyAsString(INFINITE_EXECUTION, "false"));
    }

    /**
     * Validate that CSV covers full 24-hour period (00:00 to 23:59) for infinite execution
     * @return validation result with error message if invalid
     */
    public ValidationResult validate24HourCoverage() {
        if (!isInfiniteExecution()) {
            return new ValidationResult(true, null); // No validation needed for non-infinite mode
        }

        if (csvEntries == null || csvEntries.isEmpty()) {
            return new ValidationResult(false, "No CSV entries loaded. Cannot validate 24-hour coverage.");
        }

        // Find min and max times in entries
        long minTimeMs = Long.MAX_VALUE;
        long maxTimeMs = Long.MIN_VALUE;
        
        for (CSVThroughputEntry entry : csvEntries) {
            long timeMs = entry.getTotalTimeMs();
            minTimeMs = Math.min(minTimeMs, timeMs);
            maxTimeMs = Math.max(maxTimeMs, timeMs);
        }

        // Check for 00:00 (0ms) and 23:59 (86340000ms = 1439 minutes)
        long twentyThreeFiftyNineMs = 23 * 60 * 60 * 1000 + 59 * 60 * 1000; // 86340000ms
        
        StringBuilder errorMsg = new StringBuilder();
        boolean isValid = true;
        
        if (minTimeMs > 0) {
            isValid = false;
            errorMsg.append("CSV missing entry for 00:00 (start of day). ");
        }
        
        if (maxTimeMs < twentyThreeFiftyNineMs) {
            isValid = false;
            errorMsg.append("CSV missing entry for 23:59 (end of day). ");
        }

        if (!isValid) {
            String foundRange = formatTimeFromMs(minTimeMs) + " to " + formatTimeFromMs(maxTimeMs);
            errorMsg.append("Found: ").append(foundRange).append(" (incomplete 24-hour coverage). ");
            errorMsg.append("Infinite execution requires full 24-hour coverage from 00:00 to 23:59.");
            return new ValidationResult(false, errorMsg.toString());
        }

        return new ValidationResult(true, null);
    }

    /**
     * Format milliseconds as HH:mm time string
     */
    private String formatTimeFromMs(long timeMs) {
        long totalSeconds = timeMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        return String.format("%02d:%02d", hours, minutes);
    }

    /**
     * Inner class to hold validation result
     */
    public static class ValidationResult {
        public final boolean isValid;
        public final String errorMessage;

        public ValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }
    }

    public static ThroughputMetrics getMetrics() {
        return metrics;
    }
}
