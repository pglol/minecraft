package com.pglol.aotrpg;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Runs tasks a number of server ticks from now (always on the server thread). */
public final class Scheduler {
    private record Task(long at, Runnable run) { }

    private final List<Task> tasks = new ArrayList<>();
    private long now;

    public void later(int ticks, Runnable r) {
        tasks.add(new Task(now + Math.max(1, ticks), r));
    }

    public void tick() {
        now++;
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.at <= now) {
                due.add(t);
                it.remove();
            }
        }
        for (Task t : due) {
            try {
                t.run.run();
            } catch (Exception e) {
                AotRpg.LOG.error("Scheduled task failed", e);
            }
        }
    }
}
