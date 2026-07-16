package com.asketmc.timber;

import java.util.ArrayDeque;
import java.util.Deque;

/** Fair main-thread dispatcher for bounded crush spatial queries and target decisions. */
final class CrushDispatcher {
    enum Step { PROGRESS, BLOCKED, DONE }

    interface Job {
        Step step(RuntimeWorkLimiter limiter);
        default void failed(Throwable failure) {}
    }

    private final RuntimeWorkLimiter limiter;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private int maxJobs;

    CrushDispatcher(RuntimeWorkLimiter limiter, TimberConfig cfg) {
        this.limiter = limiter;
        updateLimits(cfg);
    }

    synchronized void updateLimits(TimberConfig cfg) {
        maxJobs = Math.max(1, cfg.maxConcurrentFells * 2);
    }

    synchronized boolean submit(Job job) {
        if (jobs.size() >= maxJobs) return false;
        jobs.addLast(job);
        return true;
    }

    void tick() {
        int blockedPasses;
        synchronized (this) { blockedPasses = jobs.size(); }
        while (blockedPasses > 0 && limiter.crushTimeAvailable()) {
            Job job;
            synchronized (this) { job = jobs.pollFirst(); }
            if (job == null) return;
            Step step;
            try {
                step = job.step(limiter);
            } catch (RuntimeException | LinkageError failure) {
                try {
                    job.failed(failure);
                } catch (RuntimeException | LinkageError ignored) {
                    // Drop only the defective job and keep the shared dispatcher alive.
                }
                step = Step.DONE;
            }
            if (step != Step.DONE) {
                synchronized (this) { jobs.addLast(job); }
            }
            if (step == Step.PROGRESS || step == Step.DONE) {
                synchronized (this) { blockedPasses = jobs.size(); }
            } else {
                blockedPasses--;
            }
        }
    }

    synchronized void clear() { jobs.clear(); }

    synchronized int size() { return jobs.size(); }
}
