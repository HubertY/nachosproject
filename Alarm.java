package nachos.threads;

import nachos.machine.*;

import java.util.PriorityQueue;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {

    PriorityQueue<ScheduledWakeup> wakeups = new PriorityQueue<>();

    public class ScheduledWakeup implements Comparable {
        public long time = -1;
        public KThread thread = null;

        public ScheduledWakeup(long time, KThread thread) {
            this.time = time;
            this.thread = thread;
        }

        public int compareTo(Object other) {
            return (int) (((ScheduledWakeup) (other)).time - this.time);
        }
    }

    /**
     * Allocate a new Alarm. Set the machine's timer interrupt handler to this
     * alarm's callback.
     *
     * <p>
     * <b>Note</b>: Nachos will not function correctly with more than one alarm.
     */
    public Alarm() {
        Machine.timer().setInterruptHandler(new Runnable() {
            public void run() {
                timerInterrupt();
            }
        });
    }

    public static KThread makeTest(long t, Alarm a) {
        return new KThread(new Runnable() {
            public void run() {
                long startTime = Machine.timer().getTime();
                long expected = startTime + t;
                System.out.println("Waiting " + t + " ticks; startTime = " + startTime + "; expected = " + expected);
                a.waitUntil(t);
                long endTime = Machine.timer().getTime();
                long diff = endTime - startTime;
                System.out.println("Waited " + t + " ticks; endTime = " + endTime + "; expected = " + expected
                        + "; diff = " + diff);
            }
        });
    }

    public void selfTest() {
        for (int i = 100; i < 5000; i += 100) {
            makeTest(i, this).fork();
        }
    }

    /**
     * The timer interrupt handler. This is called by the machine's timer
     * periodically (approximately every 500 clock ticks). Causes the current thread
     * to yield, forcing a context switch if there is another thread that should be
     * run.
     */
    public void timerInterrupt() {
        long time = Machine.timer().getTime();
        while (!wakeups.isEmpty() && wakeups.peek().time <= time) {
            ScheduledWakeup s = wakeups.remove();
            s.thread.ready();
        }
        KThread.currentThread().yield();
    }

    /**
     * Put the current thread to sleep for at least <i>x</i> ticks, waking it up in
     * the timer interrupt handler. The thread must be woken up (placed in the
     * scheduler ready set) during the first timer interrupt where
     *
     * <p>
     * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
     *
     * @param x the minimum number of clock ticks to wait.
     *
     * @see nachos.machine.Timer#getTime()
     */
    public void waitUntil(long x) {
        long wakeTime = Machine.timer().getTime() + x;
        wakeups.add(new ScheduledWakeup(wakeTime, KThread.currentThread()));
        boolean intStatus = Machine.interrupt().disable();
        KThread.sleep();
        Machine.interrupt().restore(intStatus);
    }
}
